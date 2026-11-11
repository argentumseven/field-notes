package org.fieldnotes.app

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.content.ContentResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.fieldnotes.platform.PortableCipher
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** CreateDocument providers may create metadata before the first file bytes.
 * Verify both reported and readable size; never truncate a nonempty result. */
internal fun emptyExportDocument(resolver:ContentResolver,uri:Uri):Boolean {
    val size=resolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{cursor->
        val index=cursor.getColumnIndex(OpenableColumns.SIZE)
        if(index>=0&&cursor.moveToFirst()&&!cursor.isNull(index))cursor.getLong(index)else null
    }
    if(size!=null&&size>0)return false
    return try{resolver.openInputStream(uri)?.use{it.read()==-1}?:true}catch(_:FileNotFoundException){true}
}

/** SAF owns the user-visible file; only encrypted temporary bytes are stored in
 * cache. Imports are verified completely before preparing a server transaction. */
class PortableFiles(private val app:FieldNotesApplication) {
    val state=MutableStateFlow(TransferUi())
    private val mutex=Mutex()
    private var selected:File?=null
    private var passphrase:CharArray?=null
    private var manifest:JsonObject?=null
    private var plan:JsonObject?=null
    private var mapping=JsonObject(emptyMap())
    private var basis:Basis?=null
    private var actorId:String?=null
    private var ownerScope:String?=null
    private var observation:Job?=null
    private val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    init {
        app.cacheDir.listFiles().orEmpty().filter{it.name.startsWith("portable-")&&it.name.endsWith(".encrypted")}.forEach{it.delete()}
        observation=app.scope.launch(Dispatchers.IO){app.initialized.await();app.model.state.collect{workspace->
            val next=workspace.cache.snapshot?.let{"${workspace.cache.config?.text("server_key")}:${it.actor.user_id}:${it.epoch}:${it.generation}"}
            mutex.withLock {if(ownerScope!=null&&next!=ownerScope){clear();state.value=TransferUi(message="Workspace access changed. Choose an archive again to preview it here.")};ownerScope=next}
        }}
    }
    internal fun dispose(){observation?.cancel();clear()}
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun message(value:String){state.value=state.value.copy(message=value)}
    private fun run(work:suspend()->Unit){app.scope.launch(Dispatchers.IO){mutex.withLock{
        state.value=state.value.copy(busy=true)
        try { work() } catch(e:Exception){if(e is CancellationException)throw e;message(e.message?:"This file operation failed. Existing planner data is kept.")}
        finally { state.value=state.value.copy(busy=false) }
    }}}
    private fun clear(){selected?.delete();selected=null;passphrase?.fill('\u0000');passphrase=null;manifest=null;plan=null;basis=null;actorId=null;mapping=JsonObject(emptyMap());state.value=state.value.copy(preview=null,reviewed=false,fileName="")}
    private fun current(){val state=app.model.state.value;val cache=state.cache;check(!state.needsLogin&&cache.token!=null&&basis!=null&&cache.snapshot?.basis==basis&&cache.snapshot?.actor?.user_id==actorId){"The workspace or account changed. Choose the file again; original data is kept."}}
    private suspend fun plannedTransfer(action:String,body:JsonObject):JsonObject {
        current();return app.model.transfer(action,body,checkNotNull(basis),checkNotNull(actorId))
    }
    fun choose(uri:Uri)=run {
        plan?.let{try{plannedTransfer("cancel",obj("plan_id" to it.getValue("plan_id")))}catch(_:Exception){}}
        clear();val cache=app.model.state.value.cache;basis=cache.snapshot?.basis;actorId=cache.snapshot?.actor?.user_id;current()
        val file=File(app.cacheDir,"portable-${UUID.randomUUID()}.encrypted")
        try { app.contentResolver.openInputStream(uri)!!.use{input->file.outputStream().use{output->val buffer=ByteArray(65536);var size=0L;while(true){val n=input.read(buffer);if(n<0)break;size+=n;check(size<=PortableCipher.MAX_FILE){"Choose a file smaller than 256 MiB."};output.write(buffer,0,n)}}};selected=file
            state.value=state.value.copy(fileName="Encrypted Field Notes file",message="File selected. Enter its passphrase, then preview the import.")
        } catch(e:Exception){file.delete();throw e}
    }
    private suspend fun scan(file:File,pass:CharArray,consume:suspend(String,ByteArray)->Unit={_,_->}):JsonObject =
        org.fieldnotes.platform.PortableArchive.read(file.inputStream().buffered(),pass,consume)
    fun preview(password:String)=run {
        current()
        val file=checkNotNull(selected){"Choose an encrypted Field Notes file first."};passphrase?.fill('\u0000');passphrase=password.toCharArray()
        message("Unlocking and verifying every attachment…");val m=scan(file,passphrase!!)
        current()
        val result=plannedTransfer("prepare",obj("manifest" to m));current();manifest=m;plan=result
        mapping=JsonObject(result["people"]!!.jsonArray.associate{it.jsonObject.text("id") to it.jsonObject.getValue("target")})
        state.value=state.value.copy(preview=result,reviewed=true,message="Preview ready. Nothing has been added yet.")
    }
    fun mapPerson(id:String,target:String){if(state.value.busy)return;mapping=JsonObject(mapping+(id to target.j()));state.value=state.value.copy(reviewed=false,message="People mapping changed. Review again before importing.")}
    fun review()=run {current();val p=checkNotNull(plan);val result=plannedTransfer("review",obj("plan_id" to p.getValue("plan_id"),"mapping" to mapping));current();plan=result;state.value=state.value.copy(preview=result,reviewed=true,message="Preview refreshed. Current data is unchanged.")}
    fun commit(password:String)=run {
        current();check(state.value.reviewed){"Review again before importing."};val p=checkNotNull(plan);val id=p.getValue("plan_id");val needed=p["needed_media"]!!.jsonArray.map{it.jsonPrimitive.content}.toSet();val uploaded=p["uploaded_chunks"]!!.jsonObject
        message("Preparing the reviewed images…")
        scan(checkNotNull(selected),checkNotNull(passphrase)){image,bytes->if(image in needed){val already=uploaded[image].orEmptyArray().map{it.jsonPrimitive.int}.toSet();var index=0;for(offset in bytes.indices step 131072){current();if(index !in already){val chunk=bytes.copyOfRange(offset,minOf(offset+131072,bytes.size));try{plannedTransfer("image",obj("plan_id" to id,"image_id" to image.j(),"index" to index.j(),"bytes" to Base64.getEncoder().encodeToString(chunk).j()))}finally{chunk.fill(0)}};index++;message("Preparing image ${needed.indexOf(image)+1} of ${needed.size} · part $index")}}}
        current();if(p.number("new_categories")>0){app.model.reauthenticate(password);current()}
        message("Importing the reviewed items…")
        val result=try{plannedTransfer("commit",obj("plan_id" to id))}catch(e:ApiError){if(e.status==409)state.value=state.value.copy(reviewed=false);throw e}catch(e:Exception){if(e is CancellationException)throw e;plannedTransfer("commit",obj("plan_id" to id))}
        clear();app.model.sync();message("Import complete · ${result.number("imported")} added, ${result.number("already_present")} already present, ${result.number("retained_changes")} retained for review.")
        refreshRecoveredNow()
    }
    fun cancel()=run{
        val previous=plan?.getValue("plan_id");val previousBasis=basis;val previousActor=actorId;clear()
        try{if(previous!=null)app.model.transfer("cancel",obj("plan_id" to previous),previousBasis,previousActor);message("Preview canceled. Existing data was kept.")}
        catch(e:Exception){if(e is CancellationException)throw e;message("Preview canceled on this phone. Server staging expires automatically; existing data was kept.")}
    }
    private suspend fun refreshRecoveredNow(){val result=app.model.transfer("recovered");state.value=state.value.copy(recovered=result["items"]!!.jsonArray.map{it.jsonObject})}
    fun refreshRecovered()=run{refreshRecoveredNow();message("Recovered changes are up to date.")}
    fun restore(id:String,parent:String?)=run {app.model.transfer("restore",obj("id" to id.j(),"parent_id" to (parent?.j()?:JsonNull)));app.model.sync();message("Restored as a separate item. Current items were kept.")}
    fun discard(id:String)=run {app.model.transfer("discard",obj("id" to id.j()));refreshRecoveredNow();message("The recovered copy was removed. Current planner items were kept.")}
    fun export(uri:Uri,password:CharArray)=run {
        var startedWriting=false
        try{
            // CreateDocument grants one new URI; refuse a nonempty result rather
            // than truncating a file from an unusual document provider.
            check(emptyExportDocument(app.contentResolver,uri)){"Choose a new empty file for this export."}
            app.model.portableSnapshot{cache->
                var m=portableManifest(cache);val peer=cache.config?.text("server_key").orEmpty();val user=cache.snapshot!!.actor.user_id
                val normalScope=digest("$peer:$user".toByteArray());val recoveredScope=digest("$peer:$user:recovered".toByteArray())
                val pending=(cache.outbox.map{it.command}+cache.conflicts.map{it.command}).filter{it.action=="image.create"}.associateBy{it.target}
                val imageIds=(m["records"]!!.jsonArray.filter{it.jsonObject.text("kind")=="image"}.map{it.jsonObject.text("id")}+m["retained"]!!.jsonArray.mapNotNull{it.jsonObject["media_id"]?.jsonPrimitive?.contentOrNull}).distinct()
                val recoveredIds=cache.recoveredImports.filter{it["has_image"]==JsonPrimitive(true)}.map{it.text("id")}.toSet();val descriptors=mutableListOf<JsonElement>();var total=0L
                message("Exporting cached content and retained phone changes…")
                val raw=checkNotNull(app.contentResolver.openOutputStream(uri,"w")){"The chosen file cannot be written."}
                startedWriting=true
                PortableCipher.encrypting(raw.buffered(),password).use{cipher->ZipOutputStream(cipher).use{zip->
                    for((index,id)in imageIds.withIndex()){
                        val recovered=id in recoveredIds;val scope=if(recovered)recoveredScope else normalScope
                        val bytes=pending[id]?.data?.text("bytes")?.takeIf{it.isNotEmpty()}?.let{Base64.getDecoder().decode(it)}?:app.vault.readImage(scope,id)?:kotlin.run{
                            check(!cache.preferences.connectionPaused&&cache.token!=null&&!app.model.state.value.needsLogin){"An image is not cached. Reconnect this phone and retry; this is not a complete export."}
                            val reply=app.transport.exchange(if(recovered)"/api/transfer/media/$id" else "/api/images/$id",cache.token,serverKey=peer)
                            check(reply.status==200&&reply.binary!=null){"An attachment could not be retrieved. Retry before treating this as a complete export."}
                            Base64.getDecoder().decode(reply.binary).also{app.vault.saveImage(scope,id,it)}
                        }
                        try{total+=bytes.size;check(bytes.size in 1..8*1024*1024&&total<=192L*1024*1024){"Export images exceed the portable size limit."};descriptors+=obj("id" to id.j(),"size" to bytes.size.j(),"sha256" to digest(bytes).j());zip.putNextEntry(ZipEntry("media/$id.bin"));zip.write(bytes);zip.closeEntry();message("Exporting image ${index+1} of ${imageIds.size}…")}finally{bytes.fill(0)}
                    }
                    m=JsonObject(m+("media" to JsonArray(descriptors)));val json=m.toString().toByteArray();try{check(json.size<=24*1024*1024){"Export manifest exceeds 24 MiB."};zip.putNextEntry(ZipEntry("manifest.json"));zip.write(json);zip.closeEntry()}finally{json.fill(0)}
                }}
                message("Encrypted export saved · ${m["records"]!!.jsonArray.size} items, ${imageIds.size} images, ${m["retained"]!!.jsonArray.size} retained changes. Keep its passphrase.")
            }
        }catch(e:Exception){
            if(startedWriting){val removed=try{DocumentsContract.deleteDocument(app.contentResolver,uri)}catch(_:Exception){false}
                if(!removed)throw IllegalStateException("${e.message} Remove the incomplete export file before retrying.",e)}
            throw e
        }finally{password.fill('\u0000')}
    }
    private fun JsonElement?.orEmptyArray()=(this as? JsonArray).orEmpty()
}
