package org.fieldnotes.app

import android.app.*
import android.content.*
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.fieldnotes.domain.*

/** Bounded local alarm audio, with a visible Stop action. Volume changes require separate phone and reminder opt-ins.
 * Do Not Disturb and ringer mode remain under Android control. No network/media URLs are used.
 * The on-screen card is posted by [Alerts]; this service owns the sound, the vibration and the ongoing playback controls. */
class AlarmPlaybackService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val playback=Mutex()
    private var requests=0
    private var player:MediaPlayer?=null
    private var wake:PowerManager.WakeLock?=null
    /** The controls last shown, so a queued alarm keeps the sounding one on screen instead of a placeholder. */
    @Volatile private var shown:Notification?=null
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){scope.coroutineContext.cancelChildren();stopSelf();return START_NOT_STICKY}
        val app=application as FieldNotesApplication
        val manager=getSystemService(NotificationManager::class.java)
        if(manager.getNotificationChannel(CHANNEL)==null)manager.createNotificationChannel(NotificationChannel(CHANNEL,"Alarm playback controls",NotificationManager.IMPORTANCE_LOW).apply {setSound(null,null);lockscreenVisibility=Notification.VISIBILITY_PRIVATE})
        startForeground(ID,shown ?: controls(ReminderNotice("Field Notes reminder","Preparing local sound…","",null,0),app.model.state.value.cache.preferences,AlertProfile(alarm=true)))
        if(requests>=8){app.deliveryError.value="Too many simultaneous alarms. Reminder cards are retained; review their schedules.";return START_NOT_STICKY}
        requests++
        val cardKey=intent?.getStringExtra(CARD_KEY)
        scope.launch {
            try {
                val keys=intent?.getStringArrayListExtra("occurrences").orEmpty().take(32).toSet()
                playback.withLock {
                    val state=app.model.state.value
                    val cache=if(state.ready)state.cache else app.vault.load()
                    val testing=intent?.action in setOf(TEST,TEST_SOUND)
                    if(!testing&&(cache.token==null||state.ready&&state.needsLogin))return@withLock
                    val alerts=if(testing)emptyList() else localAlerts(cache,Clock.System.now(),TimeZone.currentSystemDefault()).filter { it.key in keys&&it.profile?.let{p->p.alarm||p.notificationUnmute}==true }
                    val profile=if(testing)if(intent?.action==TEST_SOUND)SyncJson.decodeFromString<AlertProfile>(intent.getStringExtra("sound_profile") ?: error("Missing sound profile")) else TEST_ALARM else alerts.firstOrNull()?.profile ?: return@withLock
                    if(profile.importance=="quiet")return@withLock
                    val notice=if(testing)ReminderNotice("Reminder sound test","Selected sound and volume policy; Stop restores any temporary volume.","",null,Clock.System.now().epochSeconds) else reminderNotice(cache,alerts,TimeZone.currentSystemDefault())
                    fun show(status:String)=controls(notice,cache.preferences,profile,status).also {shown=it;manager.notify(ID,it)}
                    show("Preparing audio")
                    val started={show(if(profile.alarm)"Alarm playing"else"Notification sound playing");Unit}
                    val allowVolume=if(profile.alarm)cache.preferences.allowAlarmVolumeOverride else cache.preferences.allowNotificationVolumeOverride
                    try {
                        if(testing)play(profile,allowVolume,started) else coroutineScope {
                            val playing=launch { play(profile,allowVolume,started) }
                            val guard=launch {
                                app.model.state.first { current ->
                                    current.ready && (current.needsLogin || current.cache.token!=cache.token || current.cache.snapshot?.basis!=cache.snapshot?.basis ||
                                        localAlerts(current.cache,Clock.System.now(),TimeZone.currentSystemDefault()).none { it.key in keys&&it.profile?.let{p->p.alarm||p.notificationUnmute}==true })
                                }
                                playing.cancel()
                            }
                            try { playing.join() } finally { guard.cancel() }
                        }
                    } finally {
                        // The sound is over, so the card stops offering Stop. A card the user already dismissed stays dismissed.
                        if(cardKey!=null)runCatching { Alerts.settle(this@AlarmPlaybackService,ReminderSounds.channel(this@AlarmPlaybackService,profile),cardKey,notice.title,notice.summary,notice.target,notice.details,notice.whenAt,profile.alarm) }
                    }
                }
            }catch(e:Exception) {
                if(e is TimeoutCancellationException||e !is CancellationException)app.deliveryError.value=e.message ?: "Alarm audio could not play. The reminder card remains available."
            }finally {
                requests--
                if(requests==0){shown=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
            }
        }
        return START_NOT_STICKY
    }
    private fun controls(notice:ReminderNotice,prefs:Preferences,profile:AlertProfile,status:String="Preparing audio"):Notification {
        val openIntent=Intent(this,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(android.net.Uri.parse("fieldnotes://playing/"+Alerts.digest(notice.target.orEmpty())))
            .putExtra("open_record",notice.target).putExtra(Alerts.STOP_ALARM_EXTRA,true)
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
    /** Moves the player's gain along a perceptual (squared) curve; each step is a few milliseconds. */
    private suspend fun ramp(media:MediaPlayer,from:Float,to:Float,millis:Long) {
        val steps=(millis/STEP_MS).toInt().coerceAtLeast(1)
        for(step in 1..steps) {
            val level=from+(to-from)*step/steps
            runCatching { media.setVolume(level*level,level*level) }
            delay(millis/steps)
        }
    }
    private suspend fun play(profile:AlertProfile,allowVolume:Boolean,onStarted:()->Unit) {
        val audio=getSystemService(AudioManager::class.java)
        val vibrator=getSystemService(Vibrator::class.java)
        val stream=if(profile.alarm)AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
        if(!profile.alarm&&audio.ringerMode!=AudioManager.RINGER_MODE_NORMAL) {
            if(profile.vibrate&&audio.ringerMode==AudioManager.RINGER_MODE_VIBRATE)vibrator.vibrate(VibrationEffect.createOneShot(250,VibrationEffect.DEFAULT_AMPLITUDE))
            return // Notification audio never overrides Silent or Vibrate.
        }
        val attributes=AudioAttributes.Builder().setUsage(if(profile.alarm)AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener { change->runCatching {when(change){AudioManager.AUDIOFOCUS_GAIN->player?.start();AudioManager.AUDIOFOCUS_LOSS,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT->player?.pause()}} }.build()
        check(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Android denied reminder audio focus. Check Do Not Disturb and sound settings."}
        var lease:String?=null
        var vibrating=false
        val media=MediaPlayer();player=media
        try {
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"fieldnotes:alarm-audio").also {it.setReferenceCounted(false);it.acquire((profile.duration.takeIf {it>0} ?: 20)*1000L+20_000)}
            media.setAudioAttributes(attributes);media.setDataSource(this,ReminderSounds.sound(this,profile))
            media.isLooping=profile.alarm&&profile.duration>0
            val completed=CompletableDeferred<Unit>()
            media.setOnCompletionListener {completed.complete(Unit)}
            media.setOnErrorListener {_,_,_->completed.completeExceptionally(IllegalStateException("Android could not decode this reminder sound. Choose another sound."));true}
            val ready=CompletableDeferred<Unit>();media.setOnPreparedListener {ready.complete(Unit)}
            media.prepareAsync()
            withTimeout(15_000){ready.await()}
            // Raise the phone's volume only once the sound is ready, while the player is still silent, so
            // the jump is never heard as a burst. The sound then eases in.
            if(profile.alarm)media.setVolume(0f,0f)
            lease=if(profile.alarm)AlarmAudio.raise(this,profile,allowVolume) else NotificationAudio.raise(this,profile,allowVolume)
            if(audio.isStreamMute(stream)||audio.getStreamVolume(stream)==0)(application as FieldNotesApplication).deliveryError.value="Reminder sound is muted. Review the notification or alarm volume controls in Settings."
            media.start();onStarted()
            val began=SystemClock.elapsedRealtime()
            if(profile.vibrate) {
                // An alarm pulses for as long as it sounds; a notification gives one short buzz unless the phone is Silent.
                if(profile.alarm){vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0,450,450),0),attributes);vibrating=true}
                else if(audio.ringerMode!=AudioManager.RINGER_MODE_SILENT)vibrator.vibrate(VibrationEffect.createOneShot(250,VibrationEffect.DEFAULT_AMPLITUDE),attributes)
            }
            if(profile.alarm)ramp(media,0f,1f,if(profile.sound=="sunrise")SUNRISE_FADE_IN_MS else FADE_IN_MS)
            if(profile.alarm&&profile.duration>0) {
                // Ring for the chosen time, ending on a short fade instead of a cut.
                val total=profile.duration.coerceIn(5,60)*1000L
                val hold=total-FADE_OUT_MS-(SystemClock.elapsedRealtime()-began)
                if(hold>0)delay(hold)
                ramp(media,1f,0f,FADE_OUT_MS)
            } else withTimeout(20_000){completed.await()}
        }finally {
            // Silence first, so releasing the player or restoring the phone's volume is never heard.
            runCatching {media.setVolume(0f,0f)}
            if(vibrating)runCatching {vibrator.cancel()}
            runCatching {media.setOnPreparedListener(null);media.setOnCompletionListener(null);media.setOnErrorListener(null);media.release()};if(player===media)player=null
            runCatching {audio.abandonAudioFocusRequest(focus)};wake?.takeIf {it.isHeld}?.release();wake=null
            withContext(NonCancellable){try{if(lease!=null){if(profile.alarm)AlarmAudio.restore(this@AlarmPlaybackService,lease) else NotificationAudio.restore(this@AlarmPlaybackService,lease)}}catch(_:Exception){(application as FieldNotesApplication).deliveryError.value="Previous alarm volume could not be restored. Open Android sound settings."}}
        }
    }
    override fun onDestroy(){runCatching {player?.pause()};scope.cancel();super.onDestroy()}
    companion object {
        private const val ID=93
        private const val CHANNEL="alarm-playback-controls"
        private const val CARD_KEY="card_key"
        private const val STEP_MS=20L
        private const val FADE_IN_MS=350L
        private const val SUNRISE_FADE_IN_MS=1_200L
        private const val FADE_OUT_MS=450L
        private val TEST_ALARM=AlertProfile("urgent","beacon",100,true,5,true,true,100)
        internal const val STOP="org.fieldnotes.STOP_ALARM"
        private const val TEST_SOUND="org.fieldnotes.TEST_REMINDER_SOUND"
        private const val TEST="org.fieldnotes.TEST_LOUD_ALARM"
        fun stopIntent(context:Context)=PendingIntent.getService(context,760,Intent(context,AlarmPlaybackService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun start(context:Context,keys:List<String>,cardKey:String?=null):Boolean=startIntent(context,Intent(context,AlarmPlaybackService::class.java).putStringArrayListExtra("occurrences",ArrayList(keys.take(32))).putExtra(CARD_KEY,cardKey))
        /** Posts the same on-screen card a real reminder shows, so a test reveals what will actually appear. */
        private fun postTestCard(context:Context,profile:AlertProfile):String? {
            val key="sound-test:"+System.nanoTime()
            val posted=Alerts.post(context,ReminderSounds.channel(context,profile),key,"Reminder sound test","Selected sound, importance and delivery mode.",alarm=profile.alarm,manualSound=profile.alarm||profile.notificationUnmute)
            if(!posted)(context.applicationContext as FieldNotesApplication).deliveryError.value="This reminder channel is blocked. Review Android notification settings before testing its sound."
            return key.takeIf {posted}
        }
        fun test(context:Context):Boolean {
            val key=postTestCard(context,TEST_ALARM) ?: return false
            return startIntent(context,Intent(context,AlarmPlaybackService::class.java).setAction(TEST).putExtra(CARD_KEY,key))
        }
        fun testSound(context:Context,profile:AlertProfile):Boolean {
            val key=postTestCard(context,profile) ?: return false
            return if(profile.alarm||profile.notificationUnmute)startIntent(context,Intent(context,AlarmPlaybackService::class.java).setAction(TEST_SOUND).putExtra("sound_profile",WireJson.encodeToString(AlertProfile.serializer(),profile)).putExtra(CARD_KEY,key)) else true
        }
        private fun startIntent(context:Context,intent:Intent):Boolean=try {
            check(Alerts.permissionAllowed(context)){"Allow notifications before playing alarm reminders."}
            check(context.startForegroundService(intent)!=null){"Android could not start audio playback. The reminder card remains available."};true
        }catch(e:Exception){(context.applicationContext as FieldNotesApplication).deliveryError.value=e.message ?: "Android blocked alarm playback. Allow notifications and precise alarms, then use the sound test.";false}
        fun stop(context:Context){context.stopService(Intent(context,AlarmPlaybackService::class.java))}
    }
}
