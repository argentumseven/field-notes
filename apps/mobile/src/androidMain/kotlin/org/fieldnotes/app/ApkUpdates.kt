package org.fieldnotes.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

/** The paired server selects software. Preparing an update never resets local
 * work, pairing or keys, and Android still owns installation consent. */
class ApkUpdates(private val app:FieldNotesApplication) {
    private val installed=app.packageManager.getPackageInfo(app.packageName,0)
    private val code=if(Build.VERSION.SDK_INT>=28)installed.longVersionCode else @Suppress("DEPRECATION") installed.versionCode.toLong()
    private val apk=File(app.cacheDir,"updates/field-notes-update.apk")
    private val transfer=UpdateTransfer(app,apk.parentFile!!,apk,code)
    val state=MutableStateFlow(AppUpdateUi(installed=installed.versionName ?: code.toString()))
    private data class Binding(val server:String,val token:String,val address:String,val user:String) {
        val cacheKey get()=ApkUpdateVerifier.hex(MessageDigest.getInstance("SHA-256").digest("$server|$address|$user".toByteArray()))
    }
    private var offer:UpdateOffer?=null
    private var bound:Binding?=null
    private var job:Job?=null
    private var automaticJob=false
    private var userCanceled=false
    private var checkedAt:Long?=null
    private var attemptedAt:Long?=null
    private var installerOpened=false
    private var lastRecommendation:Int?=null
    private fun binding():Binding {
        val s=app.model.state.value
        check(s.ready&&!s.needsLogin&&!s.connectionPaused){"Sign in and resume the private connection. Pairing and cached work are kept."}
        val c=s.cache.config ?: error("Enroll this phone first.")
        return Binding(c.text("server_key"),s.cache.token ?: error("Sign in first."),c.text("address"),s.cache.snapshot?.actor?.user_id.orEmpty())
    }
    private fun same(b:Binding){check(binding()==b){"The paired connection changed. Check updates again; pairing and saved work are kept."}}
    private suspend fun request(path:String,b:Binding):Reply {
        same(b)
        var reply=app.model.softwareUpdate(path,if(path=="/api/device/update")obj("installed_signer_sha256" to ApkUpdateVerifier.installedSigner(app.packageManager,app.packageName).j()) else null)
        if(path=="/api/device/update"&&reply.status==405)reply=app.model.softwareUpdate(path)
        same(b)
        check(reply.status==200){when(reply.status){404->"This server has no update endpoint. Update the paired server; core notes remain available.";409->"The server changed its APK. Check updates again; nothing was installed.";401,403->"Sign in to the paired server again. Pairing and queued edits are kept.";else->"Update request failed (${reply.status}). Complete chunks are retained for retry."}}
        return reply
    }
    private fun failure(message:String) {
        state.update{it.copy(problem=true,message=message,progress=null)}
        if(app.deliveryError.value==null||app.deliveryError.value?.startsWith("App update: ")==true)app.deliveryError.value="App update: $message"
    }
    private fun healthy() {if(app.deliveryError.value?.startsWith("App update: ")==true)app.deliveryError.value=null}
    private fun ready(target:UpdateOffer) {
        healthy();state.update{it.copy(ready=true,available=true,problem=false,progress=null,message="Verified update ${target.version} is ready. Android asks before installing; pairing and queued edits stay in place.")}
        if(!app.uiVisible)Alerts.post(app,Alerts.SOFTWARE,"software-update","Field Notes ${target.version} is ready","Tap to review and install the verified update. Pairing and saved work are kept.")
    }
    fun checkForUpdate(automatic:Boolean=false):Job? {
        if(job?.isCompleted==false)return job.takeIf {automatic&&automaticJob}
        val now=SystemClock.elapsedRealtime()
        val recommended=app.model.state.value.cache.compatibility?.recommendedClient ?: code.toInt()
        val changedRecommendation=lastRecommendation!=null&&lastRecommendation!=recommended
        if(automatic&&!changedRecommendation&&attemptedAt?.let{now-it<15*60_000L}==true)return null
        if(automatic&&!changedRecommendation&&bound!=null&&runCatching{binding()==bound}.getOrDefault(false)&&checkedAt?.let{now-it<6*60*60_000L}==true)return null
        lastRecommendation=recommended
        attemptedAt=now;automaticJob=automatic;userCanceled=false
        job=app.scope.launch {
            state.update{it.copy(busy=true,progress=null,message="Checking the paired server…")}
            try {
                val b=binding();val data=WireJson.parseToJsonElement(request("/api/device/update",b).body).jsonObject
                if(data.containsKey("compatibility"))app.model.observeCompatibility(data)
                check(data.text("error").isBlank()){data.text("error")}
                if(data.text("reason")=="signer_unavailable"&&data.number("offered_code")<=code){
                    checkedAt=now;bound=b;offer=null;transfer.clear();healthy()
                    state.update{it.copy(offered=data.text("offered_version"),available=false,ready=false,problem=false,lastCheck=kotlinx.datetime.Clock.System.now().epochSeconds,message="This app is already at or ahead of the server's offered version. Your original signing identity, pairing and work are kept; no APK replacement is needed.")};return@launch
                }
                if(data.text("reason")=="signer_unavailable")error(data.text("message","Ask the administrator to prepare the original-signer compatibility APK. Keep this app and pairing."))
                val next=UpdateOffer.read(data) ?: error("The paired server has no verified APK bundle. Ask its administrator to install one; notes and pairing remain usable.")
                if(next.code<=code&&next.signer!=ApkUpdateVerifier.installedSigner(app.packageManager,app.packageName)){
                    checkedAt=now;bound=b;offer=null;transfer.clear();healthy();state.update{it.copy(offered=next.version,available=false,ready=false,problem=false,lastCheck=kotlinx.datetime.Clock.System.now().epochSeconds,message="Your installed app is current. The older server offers another signing identity; the existing app and pairing are kept. New server bundles can provide a matching compatibility APK.")};return@launch
                }
                check(next.signer==ApkUpdateVerifier.installedSigner(app.packageManager,app.packageName)){"A newer update needs this phone's original signer. Ask the administrator to prepare its compatibility APK. Keep this app, pairing and queued work."}
                if(next.code>code)check(next.architecture in Build.SUPPORTED_ABIS){"The server APK does not support this phone's architecture. The existing app is kept."}
                checkedAt=now;offer=next;bound=b
                val available=next.code>code
                val reusable=if(available)transfer.prepare(next,b.cacheKey) else {transfer.clear();false}
                if(!available)app.getSystemService(android.app.NotificationManager::class.java).cancel("field-notes:"+Alerts.digest("software-update"),0)
                healthy()
                state.update{it.copy(offered=next.version,available=available,ready=reusable,problem=false,lastCheck=kotlinx.datetime.Clock.System.now().epochSeconds,message=when{reusable->"Verified update ${next.version} is ready to install.";available->"Update ${next.version} is available. Core sync can continue while it is prepared.";next.code<code->"This app is newer than the server's APK. Compatible core sync continues; an APK downgrade is unnecessary.";else->"Up to date with this server's APK. ${app.model.state.value.cache.compatibility?.message.orEmpty()}"})}
                if(reusable)ready(next)
                else if(available&&automatic&&app.model.state.value.cache.preferences.autoDownloadUpdates)downloadVerified(next,b)
                else if(!available&&app.model.state.value.cache.compatibility?.blocked==true)failure("The server requires a newer app but offers an older APK. Its administrator must install a compatible client bundle; your edits are retained.")
            }catch(e:Exception) {
                if(e is CancellationException&&e !is TimeoutCancellationException){state.update{it.copy(message="Update preparation paused. Complete chunks and planner data are kept.")};throw e}
                offer=null;bound=null;state.update{it.copy(available=false,ready=false)};failure(e.message ?: "Update check failed. Cached work is kept; use Check for updates to retry.")
            }finally{state.update{it.copy(busy=false)}}
        }
        return job
    }
    private suspend fun downloadVerified(target:UpdateOffer,b:Binding) {
        state.update{it.copy(busy=true,ready=false,problem=false,progress=0f,message="Preparing the verified update from your paired server…")}
        try {
            transfer.download(target,b.cacheKey,{same(b)},{path->request(path,b)}) {progress->state.update{it.copy(progress=progress)}}
            same(b);ready(target)
        }catch(e:Exception) {
            if(e is CancellationException&&e !is TimeoutCancellationException){if(userCanceled)transfer.cancelPartial();state.update{it.copy(ready=false,progress=null,message=if(userCanceled)"Download canceled. Pairing and saved work are kept." else "Update preparation interrupted. Complete chunks will resume on retry.")};throw e}
            checkedAt=null;state.update{it.copy(ready=false)};failure(e.message ?: "Download failed. Complete chunks are retained for retry.")
        }finally{state.update{it.copy(busy=false)}}
    }
    fun download() {
        if(job?.isCompleted==false)return
        val target=offer;val b=bound
        if(target==null||b==null){failure("Check the paired server's update offer before downloading. No planner data was changed.");return}
        if(target.code<=code){state.update{it.copy(message="This APK is already installed or older. Compatible core sync remains available.")};return}
        automaticJob=false;userCanceled=false;job=app.scope.launch{downloadVerified(target,b)}
    }
    suspend fun prepareBackground() {
        val current=app.model.state.value
        if(!current.ready||current.needsLogin||current.connectionPaused||current.cache.token==null)return
        val pending=checkForUpdate(automatic=true) ?: return
        try {withTimeout(8*60_000L){pending.join()}}
        catch(e:TimeoutCancellationException){if(job===pending&&automaticJob)pending.cancel();failure("Background update preparation timed out. Complete chunks and planner data are kept; check updates to resume.")}
        catch(e:CancellationException){if(job===pending&&automaticJob)pending.cancel();throw e}
    }
    fun cancel(){if(job?.isActive==true){userCanceled=true;job?.cancel()}else state.update{it.copy(message="No update request is running. The current app and data are kept.")}}
    fun installerReturned(){if(installerOpened){installerOpened=false;state.update{it.copy(message="The update is not installed yet. Tap Install update to continue with Android's installer.")}}}
    fun install(activity:Activity) {
        if(job?.isCompleted==false)return
        val target=offer;val b=bound
        if(!state.value.ready||target==null||b==null){failure("Download and verify the paired server's update first. The existing app is kept.");return}
        if(!app.packageManager.canRequestPackageInstalls()) {
            try {activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${app.packageName}")));state.update{it.copy(message="Allow installations from Field Notes in Android Settings, then return and tap Install update.")}}
            catch(e:Exception){failure(e.message ?: "Android installation settings are unavailable.")}
            return
        }
        automaticJob=false;job=app.scope.launch {
            state.update{it.copy(busy=true,message="Confirming the paired server's current offer…")}
            try {
                same(b)
                val latest=UpdateOffer.read(WireJson.parseToJsonElement(request("/api/device/update",b).body).jsonObject)
                check(latest==target){"The server changed its APK. Check updates again. Nothing was installed."}
                withContext(Dispatchers.IO){ApkUpdateVerifier.verify(app.packageManager,app.packageName,code,apk,target)}
                same(b)
                check(activity is androidx.lifecycle.LifecycleOwner&&activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)){"Return to Field Notes and tap Install update."}
                activity.startActivity(ApkUpdateVerifier.installIntent(app.packageManager,FileProvider.getUriForFile(app,"${app.packageName}.updates",apk)))
                installerOpened=true;state.update{it.copy(problem=false,message="Finish the update in Android's installer. The existing app, pairing and data stay in place.")}
            }catch(e:Exception){if(e is CancellationException&&e !is TimeoutCancellationException)throw e;failure(e.message ?: "Android's installer could not open. Your current app and data are kept.")}
            finally{state.update{it.copy(busy=false)}}
        }
    }
}
