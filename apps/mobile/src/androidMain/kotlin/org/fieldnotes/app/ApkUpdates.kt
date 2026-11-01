package org.fieldnotes.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

/** Downloads software through the same pinned peer. Never writes the workspace or its keys. */
class ApkUpdates(private val app:FieldNotesApplication) {
    private val installed=app.packageManager.getPackageInfo(app.packageName,0)
    private val code=if(Build.VERSION.SDK_INT>=28)installed.longVersionCode else @Suppress("DEPRECATION") installed.versionCode.toLong()
    private val folder=File(app.cacheDir,"updates")
    private val apk=File(folder,"field-notes-update.apk")
    val state=MutableStateFlow(AppUpdateUi(installed=installed.versionName?:code.toString()))
    private data class Binding(val server:String,val token:String,val address:String)
    private var offer:UpdateOffer?=null
    private var bound:Binding?=null
    private var job:Job?=null
    private var checkedAt:Long?=null
    init {
        runCatching { if(folder.exists()){cacheFolder();apk.delete();File(folder,"download.partial").delete()} }
            .onFailure { failure->state.update{it.copy(message=failure.message?:"The update cache is unavailable. Your planner data is kept.")} }
    }
    private fun cacheFolder(){
        check(!java.nio.file.Files.isSymbolicLink(folder.toPath()) && folder.canonicalFile.parentFile==app.cacheDir.canonicalFile){"Update cache needs review; planner data is kept."}
        check(folder.isDirectory||folder.mkdirs()){"Update cache could not be opened. Your planner data is kept."}
    }
    private fun binding():Binding {
        val s=app.model.state.value
        check(s.ready&&!s.needsLogin&&!s.connectionPaused){"Sign in and resume your private connection to check updates."}
        val c=s.cache.config?:error("Enroll this phone first.")
        return Binding(c.text("server_key"),s.cache.token?:error("Sign in first."),c.text("address"))
    }
    private fun same(b:Binding){check(binding()==b){"The paired connection changed. Check updates again; your data is kept."}}
    private suspend fun request(path:String,b:Binding):Reply {
        same(b)
        val reply=app.model.softwareUpdate(path)
        same(b)
        check(reply.status==200){when(reply.status){404->"This server has no in-app update bundle yet. Update the paired server first.";409->"The server APK changed. Check for updates again.";401,403->"Sign in to your paired server again. Your pairing and edits are kept.";else->"Update request failed (${reply.status}). Retry when your server is reachable."}}
        return reply
    }
    fun checkForUpdate(automatic:Boolean=false) {
        if(job?.isCompleted==false)return
        val now=SystemClock.elapsedRealtime()
        if(automatic&&bound!=null&&runCatching{binding()==bound}.getOrDefault(false)&&checkedAt?.let { now-it<6*60*60*1000L }==true)return
        job=app.scope.launch {
            state.update{it.copy(busy=true,progress=null,message="Checking the paired server…")}
            try {
                val b=binding();val reply=request("/api/device/update",b)
                val next=UpdateOffer.read(WireJson.parseToJsonElement(reply.body).jsonObject)
                val reusable=next==offer&&b==bound&&state.value.ready&&apk.isFile&&apk.length()==next?.size
                checkedAt=now;offer=next;bound=b
                val ready=next!=null&&next.code>code
                if(ready){
                    check(next!!.architecture in Build.SUPPORTED_ABIS){"The server APK does not support this device architecture."}
                    check(next.signer==ApkUpdateVerifier.installedSigner(app.packageManager,app.packageName)){"The server APK uses a different signing key. Rebuild it with this installation's signer. Do not uninstall or reset pairing."}
                }
                if(!reusable){cacheFolder();apk.delete()}
                state.update{it.copy(offered=next?.version,available=ready,ready=reusable,message=when{reusable->"Verified update ${next?.version} is ready to install.";next==null->"The paired server has no APK bundle.";next.code<code->"This phone is newer than the paired server. Downgrades are blocked.";ready->"Update ${next.version} is available from your paired server. Pairing and queued edits stay in place.";else->"Up to date with your paired server."})}
            }catch(e:Exception){if(e is CancellationException){state.update{it.copy(message="Update check canceled. Your data is kept.")};throw e};offer=null;bound=null;state.update{it.copy(available=false,ready=false,message=e.message?:"Update check failed.")}}
            finally{state.update{it.copy(busy=false)}}
        }
    }
    fun download(){
        if(job?.isCompleted==false)return
        val target=offer?:return;val b=bound?:return
        if(target.code<=code)return
        job=app.scope.launch {
            val partial=File(folder,"download.partial")
            state.update{it.copy(busy=true,ready=false,progress=0f,message="Downloading from your paired server…")}
            try {
                same(b)
                withContext(Dispatchers.IO){
                    cacheFolder()
                    partial.outputStream().use{output->
                        val digest=MessageDigest.getInstance("SHA-256");var count=0L
                        for(index in 0 until target.chunks){
                            currentCoroutineContext().ensureActive();same(b)
                            val reply=request("/api/device/update/chunks/${target.sha256}/$index",b)
                            val encoded=reply.binary?:error("The server returned an invalid APK chunk.")
                            check(encoded.length<=((target.chunkSize+2)/3)*4){"APK chunk exceeds its bound."}
                            val bytes=Base64.getDecoder().decode(encoded)
                            check(bytes.size.toLong()==minOf(target.chunkSize.toLong(),target.size-count)){"APK chunk is incomplete."}
                            output.write(bytes);digest.update(bytes);count+=bytes.size
                            state.update{it.copy(progress=count.toFloat()/target.size)}
                        }
                        output.fd.sync()
                        check(count==target.size&&ApkUpdateVerifier.hex(digest.digest())==target.sha256){"APK checksum mismatch. Nothing was installed."}
                    }
                    currentCoroutineContext().ensureActive();same(b)
                    ApkUpdateVerifier.verify(app.packageManager,app.packageName,code,partial,target)
                    check(partial.renameTo(apk)){"Could not save the verified update."}
                }
                state.update{it.copy(ready=true,progress=null,message="Verified update ${target.version}. Android will ask before installing; your keys and edits are preserved.")}
            }catch(e:Exception){apk.delete();state.update{it.copy(ready=false,progress=null,message=if(e is CancellationException)"Download canceled. Your pairing and edits are kept." else e.message?:"Download failed.")}}
            finally{partial.delete();state.update{it.copy(busy=false)}}
        }
    }
    fun cancel(){job?.cancel();state.update{it.copy(message="Canceling the current bounded request…")}}
    fun install(activity:Activity){
        if(job?.isCompleted==false||!state.value.ready)return
        val target=offer?:return;val b=bound?:return
        if(!app.packageManager.canRequestPackageInstalls()){
            try{activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${app.packageName}")))
                state.update{it.copy(message="Allow updates from Field Notes in Android Settings, then return and tap Install update. You can revoke this permission later.")}
            }catch(e:Exception){state.update{it.copy(message=e.message?:"Android installation settings are unavailable.")}}
            return
        }
        job=app.scope.launch {
            state.update{it.copy(busy=true,message="Checking the verified update against your paired server…")}
            try {
                same(b)
                val latest=UpdateOffer.read(WireJson.parseToJsonElement(request("/api/device/update",b).body).jsonObject)
                if(latest!=target){offer=null;bound=null;state.update{it.copy(available=false,ready=false)};error("The paired server changed its APK. Check for updates again.")}
                withContext(Dispatchers.IO){ApkUpdateVerifier.verify(app.packageManager,app.packageName,code,apk,target)}
                same(b)
                check(activity is androidx.lifecycle.LifecycleOwner&&activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)){"Return to Field Notes and tap Install update."}
                val uri=FileProvider.getUriForFile(app,"${app.packageName}.updates",apk)
                activity.startActivity(ApkUpdateVerifier.installIntent(app.packageManager,uri))
                state.update{it.copy(message="Finish the update in Android's installer. Do not uninstall Field Notes.")}
            }catch(e:Exception){state.update{it.copy(message=e.message?:"Android's installer could not open. Your data is kept.")}}
            finally{state.update{it.copy(busy=false)}}
        }
    }

}

internal object ApkUpdateVerifier {
    @Suppress("DEPRECATION")
    fun installIntent(pm:PackageManager,uri:Uri):Intent {
        val intent=Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val installer=pm.queryIntentActivities(intent,PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY).firstOrNull()?.activityInfo
            ?:error("Android's system package installer is unavailable. Your existing app and data are kept.")
        return intent.setClassName(installer.packageName,installer.name)
    }
    fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
    fun installedSigner(pm:PackageManager,name:String):String {
        @Suppress("DEPRECATION") val info=pm.getPackageInfo(name,if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION") val signatures=if(Build.VERSION.SDK_INT>=28)info.signingInfo!!.apkContentsSigners else info.signatures!!
        check(signatures.size==1){"Multiple APK signers require an operator-reviewed upgrade."}
        return hex(MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray()))
    }
    fun verify(pm:PackageManager,name:String,current:Long,file:File,target:UpdateOffer){
        check(target.code>current){"APK downgrade or same-version replacement blocked."}
        check(file.length()==target.size){"APK length mismatch."}
        val actual=file.inputStream().use{input->val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)};hex(hash.digest())}
        check(actual==target.sha256){"APK checksum mismatch."}
        val verified=ApkVerifier.Builder(file).setMinCheckedPlatformVersion(26).build().verify()
        check(verified.isVerified&&verified.signerCertificates.size==1){"APK signature verification failed. Nothing was installed."}
        val signer=hex(MessageDigest.getInstance("SHA-256").digest(verified.signerCertificates.single().encoded))
        check(signer==target.signer&&signer==installedSigner(pm,name)){"APK signer differs from this installation. Do not uninstall or reset pairing; rebuild the APK with the original signer."}
        @Suppress("DEPRECATION") val archive=pm.getPackageArchiveInfo(file.path,0)?:error("APK package could not be read.")
        val archiveCode=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        check(archive.packageName==name&&archiveCode==target.code&&archive.versionName==target.version){"APK identity/version does not match the paired server."}
        check(archive.applicationInfo!!.minSdkVersion<=Build.VERSION.SDK_INT){"This update needs a newer Android version."}
        java.util.zip.ZipFile(file).use{zip->check(Build.SUPPORTED_ABIS.any{abi->zip.entries().asSequence().any{it.name.startsWith("lib/$abi/")}}){"APK architecture does not support this device."}}
    }
}
