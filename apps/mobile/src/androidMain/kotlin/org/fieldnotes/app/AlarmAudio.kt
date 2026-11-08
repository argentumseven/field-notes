package org.fieldnotes.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.fieldnotes.domain.*
import java.util.UUID

@Serializable internal data class AlarmVolumeLease(val token:String,val previous:Int,val previousMuted:Boolean,val raised:Int,val untilElapsed:Long,val raisedMuted:Boolean=false)
@Serializable private data class AlarmVolumeState(val lease:AlarmVolumeLease?=null)

/** A durable, purpose-separated lease around an explicitly authorized temporary
 * volume change. Never restore over a different level/mute state chosen later. */
internal class VolumeLeaseControl(private val stream:Int,private val name:String,private val requestCode:Int) {
    private val mutex=Mutex()
    @Volatile private var active:String?=null
    private fun recovery(context:Context,token:String="")=PendingIntent.getBroadcast(context,requestCode,
        Intent(context,AlarmVolumeRecoveryReceiver::class.java).setData(Uri.parse("fieldnotes://$name-restore")).putExtra("lease_token",token).putExtra("volume_kind",name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private suspend fun read(app:FieldNotesApplication):AlarmVolumeLease? {
        val bytes=app.vault.readEffect(name) ?: return null
        return try { SyncJson.decodeFromString<AlarmVolumeState>(bytes.decodeToString()).lease } finally { bytes.fill(0) }
    }
    private suspend fun write(app:FieldNotesApplication,lease:AlarmVolumeLease?) {
        val bytes=WireJson.encodeToString(AlarmVolumeState.serializer(),AlarmVolumeState(lease)).encodeToByteArray()
        try { app.vault.writeEffect(name,bytes) } finally { bytes.fill(0) }
    }
    private suspend fun restoreLocked(context:Context,lease:AlarmVolumeLease) {
        val app=context.applicationContext as FieldNotesApplication
        val audio=context.getSystemService(AudioManager::class.java)
        if((stream!=AudioManager.STREAM_NOTIFICATION||audio.ringerMode==AudioManager.RINGER_MODE_NORMAL)&&audio.getStreamVolume(stream)==lease.raised&&audio.isStreamMute(stream)==lease.raisedMuted) {
            try {
                audio.setStreamVolume(stream,lease.previous,0)
                if(lease.previousMuted)audio.adjustStreamVolume(stream,AudioManager.ADJUST_MUTE,0)
                check(audio.getStreamVolume(stream)==lease.previous&&audio.isStreamMute(stream)==lease.previousMuted){"Android refused the previous alarm volume or mute state. Open Android sound settings to restore it manually."}
            }catch(error:Exception) {
                // A setter can partially succeed or clamp zero. Keep the new
                // app-owned state so recovery can retry without overwriting a
                // later manual change, and never claim restoration succeeded.
                write(app,lease.copy(raised=audio.getStreamVolume(stream),raisedMuted=audio.isStreamMute(stream)))
                throw error
            }
        }
        write(app,null)
        context.getSystemService(AlarmManager::class.java).cancel(recovery(context))
        if(active==lease.token)active=null
    }
    suspend fun raise(context:Context,profile:AlertProfile,allow:Boolean?=null):String? = withContext(Dispatchers.IO) { mutex.withLock {
        if(if(stream==AudioManager.STREAM_ALARM) !profile.alarm||!profile.unmute else profile.alarm||!profile.notificationUnmute)return@withLock null
        val app=context.applicationContext as FieldNotesApplication
        if(!(allow ?: if(stream==AudioManager.STREAM_ALARM) app.model.state.value.cache.preferences.allowAlarmVolumeOverride else app.model.state.value.cache.preferences.allowNotificationVolumeOverride)) {
            app.deliveryError.value=if(stream==AudioManager.STREAM_ALARM)ALARM_VOLUME_DISABLED else NOTIFICATION_VOLUME_DISABLED
            return@withLock null
        }
        read(app)?.let { restoreLocked(context,it) } // A replacement owns a new lease; never inherit an elevated baseline.
        val audio=context.getSystemService(AudioManager::class.java)
        if(stream==AudioManager.STREAM_NOTIFICATION&&audio.ringerMode!=AudioManager.RINGER_MODE_NORMAL){app.deliveryError.value="Notification sound respects Android Silent and Vibrate modes. Choose Alarm or change Android sound settings.";return@withLock null}
        val previous=audio.getStreamVolume(stream);val muted=audio.isStreamMute(stream)
        val target=kotlin.math.ceil(audio.getStreamMaxVolume(stream)*(if(stream==AudioManager.STREAM_ALARM)profile.alarmVolume else profile.notificationVolume).coerceIn(25,100)/100.0).toInt().coerceAtLeast(previous)
        if(previous==target&&!muted)return@withLock null
        val lease=AlarmVolumeLease(UUID.randomUUID().toString(),previous,muted,target,SystemClock.elapsedRealtime()+(profile.duration.takeIf {it>0} ?: 20)*1000L+20_000)
        write(app,lease) // Durable before touching the shared Android setting.
        val alarms=context.getSystemService(AlarmManager::class.java)
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,lease.untilElapsed,recovery(context,lease.token))
        active=lease.token
        try {
            audio.adjustStreamVolume(stream,AudioManager.ADJUST_UNMUTE,0)
            audio.setStreamVolume(stream,target,0)
            check(!audio.isStreamMute(stream)&&audio.getStreamVolume(stream)>0){"Android kept the alarm stream muted. Open Android sound settings."}
        } catch(error:Exception) {
            withContext(NonCancellable){
                val current=audio.getStreamVolume(stream)
                restoreLocked(context,if(current==previous)lease.copy(raised=previous) else lease)
            }
            throw error
        }
        lease.token
    } }
    suspend fun restore(context:Context,token:String?=null,expired:Boolean=false) = withContext(Dispatchers.IO) { mutex.withLock {
        val lease=read(context.applicationContext as FieldNotesApplication) ?: return@withLock
        if(token!=null&&lease.token!=token)return@withLock
        if(token==null&&active==lease.token&&!expired)return@withLock
        if(expired&&SystemClock.elapsedRealtime()<lease.untilElapsed)return@withLock
        restoreLocked(context,lease)
    } }
    suspend fun restoreAfterStop(context:Context)=restore(context,active)
}

internal object AlarmAudio {
    private val control=VolumeLeaseControl(AudioManager.STREAM_ALARM,"alarm-volume",759)
    suspend fun raise(context:Context,profile:AlertProfile,allow:Boolean?=null)=control.raise(context,profile,allow)
    suspend fun restore(context:Context,token:String?=null,expired:Boolean=false)=control.restore(context,token,expired)
    suspend fun restoreAfterStop(context:Context)=control.restoreAfterStop(context)
}
internal object NotificationAudio {
    private val control=VolumeLeaseControl(AudioManager.STREAM_NOTIFICATION,"notification-volume",762)
    suspend fun raise(context:Context,profile:AlertProfile,allow:Boolean?=null)=control.raise(context,profile,allow)
    suspend fun restore(context:Context,token:String?=null,expired:Boolean=false)=control.restore(context,token,expired)
    suspend fun restoreAfterStop(context:Context)=control.restoreAfterStop(context)
}
class AlarmVolumeRecoveryReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val token=intent.getStringExtra("lease_token") ?: return
        val pending=goAsync();val app=context.applicationContext as FieldNotesApplication
        app.scope.launch {
            try { if(intent.getStringExtra("volume_kind")=="notification-volume")NotificationAudio.restore(context,token,expired=true) else AlarmAudio.restore(context,token,expired=true) }
            catch(_:Exception){app.deliveryError.value="Previous sound volume could not be restored. Open Android sound settings."}
            finally{pending.finish()}
        }
    }
}
