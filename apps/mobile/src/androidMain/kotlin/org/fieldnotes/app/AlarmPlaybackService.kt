package org.fieldnotes.app

import android.app.*
import android.content.*
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.fieldnotes.domain.*

/** Bounded local alarm audio, with a visible Stop action. Volume changes require separate phone and reminder opt-ins.
 * Do Not Disturb and ringer mode remain under Android control. No network/media URLs are used. */
class AlarmPlaybackService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val playback=Mutex()
    private var requests=0
    private var player:MediaPlayer?=null
    private var wake:PowerManager.WakeLock?=null
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){scope.coroutineContext.cancelChildren();stopSelf();return START_NOT_STICKY}
        val app=application as FieldNotesApplication
        val manager=getSystemService(NotificationManager::class.java)
        if(manager.getNotificationChannel(CHANNEL)==null)manager.createNotificationChannel(NotificationChannel(CHANNEL,"Alarm playback controls",NotificationManager.IMPORTANCE_LOW).apply {setSound(null,null);lockscreenVisibility=Notification.VISIBILITY_PRIVATE})
        startForeground(ID,controls(ReminderNotice("Field Notes reminder","Preparing local sound…","",null,0),app.model.state.value.cache.preferences,AlertProfile(alarm=true)))
        if(requests>=8){app.deliveryError.value="Too many simultaneous alarms. Reminder cards are retained; review their schedules.";return START_NOT_STICKY}
        requests++
        scope.launch {
            try {
                val keys=intent?.getStringArrayListExtra("occurrences").orEmpty().take(32).toSet()
                playback.withLock {
                    val state=app.model.state.value
                    val cache=if(state.ready)state.cache else app.vault.load()
                    if(intent?.action !in setOf(TEST,TEST_SOUND)&&(cache.token==null||state.ready&&state.needsLogin))return@withLock
                    val alerts=if(intent?.action in setOf(TEST,TEST_SOUND))emptyList() else localAlerts(cache,Clock.System.now(),TimeZone.currentSystemDefault()).filter { it.key in keys&&it.profile?.let{p->p.alarm||p.notificationUnmute}==true }
                    val profile=if(intent?.action in setOf(TEST,TEST_SOUND))if(intent?.action==TEST_SOUND)SyncJson.decodeFromString<AlertProfile>(intent.getStringExtra("sound_profile") ?: error("Missing sound profile")) else AlertProfile("urgent","beacon",100,true,5,true,true,100) else alerts.firstOrNull()?.profile ?: return@withLock
                    if(profile.importance=="quiet")return@withLock
                    val notice=if(intent?.action in setOf(TEST,TEST_SOUND))ReminderNotice("Reminder sound test","Selected sound and volume policy; Stop restores any temporary volume.","",null,Clock.System.now().epochSeconds) else reminderNotice(cache,alerts,TimeZone.currentSystemDefault())
                    manager.notify(ID,controls(notice,cache.preferences,profile))
                    val started={ manager.notify(ID,controls(notice,cache.preferences,profile,if(profile.alarm)"Alarm playing"else"Notification sound playing")) }
                    if(intent?.action in setOf(TEST,TEST_SOUND))play(profile,if(profile.alarm)cache.preferences.allowAlarmVolumeOverride else cache.preferences.allowNotificationVolumeOverride,started) else coroutineScope {
                        val playing=launch { play(profile,if(profile.alarm)cache.preferences.allowAlarmVolumeOverride else cache.preferences.allowNotificationVolumeOverride,started) }
                        val guard=launch {
                            app.model.state.first { current ->
                                current.ready && (current.needsLogin || current.cache.token!=cache.token || current.cache.snapshot?.basis!=cache.snapshot?.basis ||
                                    localAlerts(current.cache,Clock.System.now(),TimeZone.currentSystemDefault()).none { it.key in keys&&it.profile?.let{p->p.alarm||p.notificationUnmute}==true })
                            }
                            playing.cancel()
                        }
                        try { playing.join() } finally { guard.cancel() }
                    }
                }
            }catch(e:Exception) {
                if(e is TimeoutCancellationException||e !is CancellationException)app.deliveryError.value=e.message ?: "Alarm audio could not play. The reminder card remains available."
            }finally {
                requests--
                if(requests==0){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
            }
        }
        return START_NOT_STICKY
    }
    private fun controls(notice:ReminderNotice,prefs:Preferences,profile:AlertProfile,status:String="Preparing audio"):Notification {
        val openIntent=Intent(this,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(android.net.Uri.parse("fieldnotes://playing/"+Alerts.digest(notice.target.orEmpty())))
            .putExtra("open_record",notice.target)
        if(notice.target==null)openIntent.putExtra("open_page","Settings")
        val open=PendingIntent.getActivity(this,761,openIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val details=notice.details.ifBlank{notice.summary}
        val publicNotice=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_field_notes)
            .setContentTitle(if(prefs.notificationOnLockScreen)notice.title else "Field Notes alert")
            .setContentText(if(prefs.notificationOnLockScreen)notice.summary else "Reminder details are hidden on the lock screen.")
        if(prefs.notificationOnLockScreen)publicNotice.setStyle(NotificationCompat.BigTextStyle().bigText(details))
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_field_notes).setContentTitle(notice.title).setContentText(notice.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details)).setSubText(status).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setWhen(if(notice.whenAt>0)notice.whenAt*1000 else System.currentTimeMillis()).setShowWhen(true)
            .setPublicVersion(publicNotice.build()).setVisibility(if(prefs.notificationOnLockScreen)NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(if(profile.alarm)NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .addAction(0,if(profile.alarm)"Stop alarm"else"Stop sound",stopIntent(this)).build()
    }
    private suspend fun play(profile:AlertProfile,allowVolume:Boolean,onStarted:()->Unit) {
        val audio=getSystemService(AudioManager::class.java)
        val stream=if(profile.alarm)AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
        if(!profile.alarm&&audio.ringerMode!=AudioManager.RINGER_MODE_NORMAL) {
            if(profile.vibrate&&audio.ringerMode==AudioManager.RINGER_MODE_VIBRATE)getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(250,VibrationEffect.DEFAULT_AMPLITUDE))
            return // Notification audio never overrides Silent or Vibrate.
        }
        val attributes=AudioAttributes.Builder().setUsage(if(profile.alarm)AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener { change->runCatching {when(change){AudioManager.AUDIOFOCUS_GAIN->player?.start();AudioManager.AUDIOFOCUS_LOSS,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT->player?.pause()}} }.build()
        check(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Android denied reminder audio focus. Check Do Not Disturb and sound settings."}
        var lease:String?=null
        val media=MediaPlayer();player=media
        try {
            lease=if(profile.alarm)AlarmAudio.raise(this,profile,allowVolume) else NotificationAudio.raise(this,profile,allowVolume)
            if(audio.isStreamMute(stream)||audio.getStreamVolume(stream)==0)(application as FieldNotesApplication).deliveryError.value="Reminder sound is muted. Review the notification or alarm volume controls in Settings."
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"fieldnotes:alarm-audio").also {it.setReferenceCounted(false);it.acquire((profile.duration.takeIf {it>0} ?: 20)*1000L+20_000)}
            media.setAudioAttributes(attributes);media.setDataSource(this,ReminderSounds.sound(this,profile))
            media.isLooping=profile.alarm&&profile.duration>0
            val completed=CompletableDeferred<Unit>()
            media.setOnCompletionListener {completed.complete(Unit)}
            media.setOnErrorListener {_,_,_->completed.completeExceptionally(IllegalStateException("Android could not decode this reminder sound. Choose another sound."));true}
            val ready=CompletableDeferred<Unit>();media.setOnPreparedListener {ready.complete(Unit)}
            media.prepareAsync()
            withTimeout(15_000){ready.await()}
            media.start();onStarted()
            if(profile.vibrate&&(profile.alarm||audio.ringerMode!=AudioManager.RINGER_MODE_SILENT))getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(250,VibrationEffect.DEFAULT_AMPLITUDE),attributes)
            if(profile.alarm&&profile.duration>0)withTimeoutOrNull(profile.duration.coerceIn(5,60)*1000L){completed.await()} else withTimeout(20_000){completed.await()}
        }finally {
            runCatching {media.setOnPreparedListener(null);media.setOnCompletionListener(null);media.setOnErrorListener(null);media.release()};if(player===media)player=null
            runCatching {audio.abandonAudioFocusRequest(focus)};wake?.takeIf {it.isHeld}?.release();wake=null
            withContext(NonCancellable){try{if(lease!=null){if(profile.alarm)AlarmAudio.restore(this@AlarmPlaybackService,lease) else NotificationAudio.restore(this@AlarmPlaybackService,lease)}}catch(_:Exception){(application as FieldNotesApplication).deliveryError.value="Previous alarm volume could not be restored. Open Android sound settings."}}
        }
    }
    override fun onDestroy(){runCatching {player?.pause()};scope.cancel();super.onDestroy()}
    companion object {
        private const val ID=93
        private const val CHANNEL="alarm-playback-controls"
        internal const val STOP="org.fieldnotes.STOP_ALARM"
        private const val TEST_SOUND="org.fieldnotes.TEST_REMINDER_SOUND"
        private const val TEST="org.fieldnotes.TEST_LOUD_ALARM"
        fun stopIntent(context:Context)=PendingIntent.getService(context,760,Intent(context,AlarmPlaybackService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun start(context:Context,keys:List<String>):Boolean=startIntent(context,Intent(context,AlarmPlaybackService::class.java).putStringArrayListExtra("occurrences",ArrayList(keys.take(32))))
        fun test(context:Context):Boolean=startIntent(context,Intent(context,AlarmPlaybackService::class.java).setAction(TEST))
        fun testSound(context:Context,profile:AlertProfile):Boolean {
            val channel=ReminderSounds.channel(context,profile)
            if(!Alerts.post(context,channel,"sound-test:"+System.nanoTime(),"Reminder sound test","Selected sound, importance and delivery mode.",alarm=profile.alarm,manualSound=profile.alarm||profile.notificationUnmute)){(context.applicationContext as FieldNotesApplication).deliveryError.value="This reminder channel is blocked. Review Android notification settings before testing its sound.";return false}
            return if(profile.alarm||profile.notificationUnmute)startIntent(context,Intent(context,AlarmPlaybackService::class.java).setAction(TEST_SOUND).putExtra("sound_profile",WireJson.encodeToString(AlertProfile.serializer(),profile))) else true
        }
        private fun startIntent(context:Context,intent:Intent):Boolean=try {
            check(Alerts.permissionAllowed(context)){"Allow notifications before playing alarm reminders."}
            ContextCompat.startForegroundService(context,intent);true
        }catch(e:Exception){(context.applicationContext as FieldNotesApplication).deliveryError.value=e.message ?: "Android blocked alarm playback. Allow notifications and precise alarms, then use the sound test.";false}
        fun stop(context:Context){context.stopService(Intent(context,AlarmPlaybackService::class.java))}
    }
}
