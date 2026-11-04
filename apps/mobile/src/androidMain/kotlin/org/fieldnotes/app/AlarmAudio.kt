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
internal object AlarmAudio {
    private val mutex=Mutex()
    @Volatile private var active:String?=null
    private fun recovery(context:Context,token:String="")=PendingIntent.getBroadcast(context,759,
        Intent(context,AlarmVolumeRecoveryReceiver::class.java).setData(Uri.parse("fieldnotes://alarm-volume-restore")).putExtra("lease_token",token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private suspend fun read(app:FieldNotesApplication):AlarmVolumeLease? {
        val bytes=app.vault.readEffect("alarm-volume") ?: return null
        return try { SyncJson.decodeFromString<AlarmVolumeState>(bytes.decodeToString()).lease } finally { bytes.fill(0) }
    }
    private suspend fun write(app:FieldNotesApplication,lease:AlarmVolumeLease?) {
        val bytes=WireJson.encodeToString(AlarmVolumeState.serializer(),AlarmVolumeState(lease)).encodeToByteArray()
        try { app.vault.writeEffect("alarm-volume",bytes) } finally { bytes.fill(0) }
    }
    private suspend fun restoreLocked(context:Context,lease:AlarmVolumeLease) {
        val app=context.applicationContext as FieldNotesApplication
        val audio=context.getSystemService(AudioManager::class.java)
        if(audio.getStreamVolume(AudioManager.STREAM_ALARM)==lease.raised&&audio.isStreamMute(AudioManager.STREAM_ALARM)==lease.raisedMuted) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM,lease.previous,0)
                if(lease.previousMuted)audio.adjustStreamVolume(AudioManager.STREAM_ALARM,AudioManager.ADJUST_MUTE,0)
                check(audio.getStreamVolume(AudioManager.STREAM_ALARM)==lease.previous&&audio.isStreamMute(AudioManager.STREAM_ALARM)==lease.previousMuted){"Android refused the previous alarm volume or mute state. Open Android sound settings to restore it manually."}
            }catch(error:Exception) {
                // A setter can partially succeed or clamp zero. Keep the new
                // app-owned state so recovery can retry without overwriting a
                // later manual change, and never claim restoration succeeded.
                write(app,lease.copy(raised=audio.getStreamVolume(AudioManager.STREAM_ALARM),raisedMuted=audio.isStreamMute(AudioManager.STREAM_ALARM)))
                throw error
            }
        }
        write(app,null)
        context.getSystemService(AlarmManager::class.java).cancel(recovery(context))
        if(active==lease.token)active=null
    }
    suspend fun raise(context:Context,profile:AlertProfile,allow:Boolean?=null):String? = withContext(Dispatchers.IO) { mutex.withLock {
        if(!profile.alarm||!profile.unmute)return@withLock null
        val app=context.applicationContext as FieldNotesApplication
        if(!(allow ?: app.model.state.value.cache.preferences.allowAlarmVolumeOverride)) {
            app.deliveryError.value="This alarm requested a louder volume, but volume control is off on this phone. Enable loud alarms in Settings."
            return@withLock null
        }
        read(app)?.let { if(active!=it.token)restoreLocked(context,it) }
        val audio=context.getSystemService(AudioManager::class.java)
        val previous=audio.getStreamVolume(AudioManager.STREAM_ALARM);val muted=audio.isStreamMute(AudioManager.STREAM_ALARM)
        val target=kotlin.math.ceil(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)*profile.alarmVolume.coerceIn(25,100)/100.0).toInt().coerceAtLeast(previous)
        if(previous==target&&!muted)return@withLock null
        val lease=AlarmVolumeLease(UUID.randomUUID().toString(),previous,muted,target,SystemClock.elapsedRealtime()+(profile.duration.takeIf {it>0} ?: 20)*1000L+20_000)
        write(app,lease) // Durable before touching the shared Android setting.
        val alarms=context.getSystemService(AlarmManager::class.java)
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,lease.untilElapsed,recovery(context,lease.token))
        active=lease.token
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_ALARM,AudioManager.ADJUST_UNMUTE,0)
            audio.setStreamVolume(AudioManager.STREAM_ALARM,target,0)
            check(!audio.isStreamMute(AudioManager.STREAM_ALARM)&&audio.getStreamVolume(AudioManager.STREAM_ALARM)>0){"Android kept the alarm stream muted. Open Android sound settings."}
        } catch(error:Exception) {
            withContext(NonCancellable){
                val current=audio.getStreamVolume(AudioManager.STREAM_ALARM)
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

class AlarmVolumeRecoveryReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val token=intent.getStringExtra("lease_token") ?: return
        val pending=goAsync();val app=context.applicationContext as FieldNotesApplication
        app.scope.launch {
            try { AlarmAudio.restore(context,token,expired=true) }
            catch(_:Exception){app.deliveryError.value="Previous alarm volume could not be restored. Open Android sound settings."}
            finally{pending.finish()}
        }
    }
}
