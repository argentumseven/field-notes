package org.fieldnotes.app

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.fieldnotes.domain.*

@Serializable private data class UpdateCheckpoint(val offer:UpdateOffer,val binding:String)

/** Verified software only: the journal is encrypted separately from the outbox.
 * Complete chunks survive a disconnect/process death; a different server offer
 * or identity starts a fresh transfer. The complete APK is always reverified. */
internal class UpdateTransfer(private val app:FieldNotesApplication,private val folder:File,val apk:File,private val installedCode:Long) {
    private val partial=File(folder,"download.partial")
    private fun directory() {
        check(!java.nio.file.Files.isSymbolicLink(folder.toPath())&&folder.canonicalFile.parentFile==app.cacheDir.canonicalFile){"Update cache needs review; planner data is kept."}
        check(folder.isDirectory||folder.mkdirs()){"Update cache is unavailable. Free local storage; planner data is kept."}
        check(!java.nio.file.Files.isSymbolicLink(apk.toPath())&&!java.nio.file.Files.isSymbolicLink(partial.toPath())){"Update cache contains an unexpected link. Nothing was installed."}
    }
    private suspend fun checkpoint():UpdateCheckpoint? {
        val bytes=app.vault.readEffect("software-transfer") ?: return null
        return try {runCatching {SyncJson.decodeFromString<UpdateCheckpoint>(bytes.decodeToString())}.getOrNull()}finally{bytes.fill(0)}
    }
    private suspend fun remember(value:UpdateCheckpoint) {
        val bytes=WireJson.encodeToString(UpdateCheckpoint.serializer(),value).encodeToByteArray()
        try{app.vault.writeEffect("software-transfer",bytes)}finally{bytes.fill(0)}
    }
    suspend fun prepare(offer:UpdateOffer,binding:String):Boolean=withContext(Dispatchers.IO) {
        directory()
        val saved=checkpoint()
        if(saved!=UpdateCheckpoint(offer,binding)) {apk.delete();partial.delete();remember(UpdateCheckpoint(offer,binding))}
        if(!apk.exists())return@withContext false
        try {ApkUpdateVerifier.verify(app.packageManager,app.packageName,installedCode,apk,offer);true}
        catch(error:Exception){apk.delete();throw error}
    }
    suspend fun download(offer:UpdateOffer,binding:String,same:()->Unit,request:suspend (String)->Reply,progress:(Float)->Unit)=withContext(Dispatchers.IO) {
        if(prepare(offer,binding))return@withContext
        var verifyStarted=false
        try {
            var count=partial.takeIf {it.isFile}?.length() ?: 0L
            if(count>offer.size){partial.delete();count=0}
            if(count!=offer.size) {
                val complete=count/offer.chunkSize*offer.chunkSize
                if(complete!=count)java.io.RandomAccessFile(partial,"rw").use {it.setLength(complete)}
                count=complete
            }
            val digest=MessageDigest.getInstance("SHA-256")
            if(count>0)partial.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            progress(count.toFloat()/offer.size)
            FileOutputStream(partial,true).use {output->
                for(index in (if(count==offer.size)offer.chunks else (count/offer.chunkSize).toInt()) until offer.chunks) {
                    currentCoroutineContext().ensureActive();same()
                    val reply=request("/api/device/update/chunks/${offer.sha256}/$index")
                    val encoded=reply.binary ?: error("The server returned an invalid APK chunk. Retry after checking its client bundle.")
                    check(encoded.length<=((offer.chunkSize+2)/3)*4){"APK chunk exceeds its bound. Nothing was installed."}
                    val bytes=Base64.getDecoder().decode(encoded)
                    check(bytes.size.toLong()==minOf(offer.chunkSize.toLong(),offer.size-count)){"APK chunk is incomplete; complete earlier chunks are retained."}
                    output.write(bytes);output.fd.sync();digest.update(bytes);count+=bytes.size
                    progress(count.toFloat()/offer.size)
                }
            }
            verifyStarted=true
            check(count==offer.size&&ApkUpdateVerifier.hex(digest.digest())==offer.sha256){"APK checksum mismatch. Nothing was installed; retry starts with clean software bytes."}
            currentCoroutineContext().ensureActive();same()
            ApkUpdateVerifier.verify(app.packageManager,app.packageName,installedCode,partial,offer)
            check(partial.renameTo(apk)){"Verified update could not be staged. Free local storage and retry."}
        }catch(error:Exception){if(verifyStarted&&error !is CancellationException){partial.delete();apk.delete()};throw error}
    }
    fun cancelPartial(){partial.delete()}
    fun clear(){apk.delete();partial.delete()}
}
