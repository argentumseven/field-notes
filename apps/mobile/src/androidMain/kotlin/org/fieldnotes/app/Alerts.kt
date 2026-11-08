package org.fieldnotes.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import org.fieldnotes.domain.*

/** App-owned alerts. Normal Android channels and DND remain authoritative. */
object Alerts {
    const val REMINDERS = "reminders"
    const val UPDATES = "updates"
    const val CONNECTION = "private-connection"
    const val ISSUES="delivery-issues"
    const val SOFTWARE="software-updates"
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
        for ((id, name) in listOf(REMINDERS to "Upcoming tasks and calendar", UPDATES to "Household updates",ISSUES to "Delivery and compatibility issues",SOFTWARE to "Verified app updates")) {
            // Existing user changes to importance/sound are never reset.
            if (manager.getNotificationChannel(id) == null) manager.createNotificationChannel(
                NotificationChannel(id, name, if(id==ISSUES||id==SOFTWARE)NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(if(id==ISSUES||id==SOFTWARE)null else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audio)
                    enableVibration(id!=ISSUES&&id!=SOFTWARE)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                })
        }
        connectionChannel(context)
    }
    internal fun connectionChannel(context:Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CONNECTION) == null) manager.createNotificationChannel(
            NotificationChannel(CONNECTION, "Private background connection", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                // GrapheneOS does not forward this ongoing status; real alerts
                // remain PRIVATE and eligible for its system forwarding.
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
    }
    fun permissionAllowed(context:Context):Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
    fun allowed(context: Context, channel: String): Boolean = permissionAllowed(context)&&context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    fun post(context: Context, channel: String, key: String, title: String, text: String, target:String?=null,details:String=text,whenAt:Long=0,alarm:Boolean=false,manualSound:Boolean=alarm): Boolean {
        channels(context)
        if (!allowed(context, channel)) return false
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.parse("fieldnotes://notification/" + digest(key))).putExtra("open_record",target)
        if(channel==ISSUES||channel==SOFTWARE)intent.putExtra("open_page","Settings")
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val prefs=(context.applicationContext as FieldNotesApplication).model.state.value.cache.preferences
        val publicNotice=NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_field_notes)
            .setContentTitle(if(prefs.notificationOnLockScreen)title else "Field Notes alert")
            .setContentText(if(prefs.notificationOnLockScreen)text else "Reminder details are hidden on the lock screen.")
        if(prefs.notificationOnLockScreen)publicNotice.setStyle(NotificationCompat.BigTextStyle().bigText(details))
        val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_field_notes)
            .setContentTitle(title).setContentText(text).setContentIntent(open)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details)).setWhen(if(whenAt>0)whenAt*1000 else System.currentTimeMillis()).setShowWhen(true)
            .setPublicVersion(publicNotice.build())
            .setCategory(if(alarm)NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER).setVisibility(if(prefs.notificationOnLockScreen)NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setOnlyAlertOnce(true)
        if(target!=null)builder.addAction(0,"Open item",open)
        if(alarm||channel==REMINDERS||channel.startsWith("fn-reminder-"))builder.setSubText(if(alarm)"Alarm"else"Reminder")
        if(manualSound)builder.setSilent(true).setDeleteIntent(AlarmPlaybackService.stopIntent(context)).addAction(0,if(alarm)"Stop alarm"else"Stop sound",AlarmPlaybackService.stopIntent(context))
        if(channel==ISSUES&&key=="delivery-health") {
            val dismiss=PendingIntent.getBroadcast(context,0,Intent(context,DeliveryIssueReceiver::class.java)
                .setData(Uri.parse("fieldnotes://dismiss-issue/"+digest(text))).putExtra("issue_digest",digest(text)),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setDeleteIntent(dismiss).addAction(0,"Dismiss",dismiss)
        }
        val notification=builder.build()
        return try {
            // A unique tag per occurrence lets later reminders sound; retrying
            // this same occurrence updates its existing notification quietly.
            context.getSystemService(NotificationManager::class.java).notify("field-notes:" + digest(key), 0, notification)
            true
        } catch (_: SecurityException) { false }
    }
    fun test(context: Context) = post(context, ReminderSounds.channel(context,AlertProfile()), "test:" + System.nanoTime(), "Field Notes notification test", "One short notification sound. Uses notification volume and respects Silent, Vibrate and Do Not Disturb.")
    internal fun profileTestIntent(context:Context,nonce:String="")=PendingIntent.getBroadcast(context,99,
        Intent(context,AlertReceiver::class.java).setAction("org.fieldnotes.PROFILE_TEST").setData(Uri.parse("fieldnotes://profile-test")).putExtra("nonce",nonce),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun scheduleProfileTest(context:Context):Boolean {
        channels(context);if(!allowed(context,REMINDERS))return false
        val alarms=context.getSystemService(AlarmManager::class.java)
        val action=profileTestIntent(context,System.nanoTime().toString());val whenMs=System.currentTimeMillis()+30_000
        try{if(Build.VERSION.SDK_INT<31||alarms.canScheduleExactAlarms())alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenMs,action)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenMs,action)}catch(_:SecurityException){alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenMs,action)}
        return true
    }
    internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Serializable private data class AlertState(
    val scope: String = "", val scheduled: Map<String, Long> = emptyMap(), val seen: Map<String, Long> = emptyMap(),val delivered:Map<String,Long> = emptyMap(),
    val applyPending:Boolean = false,
)

/** One process-local coordinator and a purpose-separated encrypted receipt file.
 * Alarm intents contain only an opaque occurrence key, never task text/tokens. */
object LocalAlarms {
    private val mutex = Mutex()
    private fun intent(context: Context, key: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlertReceiver::class.java).setAction("org.fieldnotes.ALERT")
            .setData(Uri.parse("fieldnotes://alarm/" + Uri.encode(key)))
            .putExtra("occurrence", key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private suspend fun state(app: FieldNotesApplication): AlertState {
        val bytes = app.vault.readEffect("alerts") ?: return AlertState()
        return try { WireJson.decodeFromString<AlertState>(bytes.decodeToString()) } finally { bytes.fill(0) }
    }
    private suspend fun save(app: FieldNotesApplication, state: AlertState) {
        val bytes = WireJson.encodeToString(state).encodeToByteArray()
        try { app.vault.writeEffect("alerts", bytes) } finally { bytes.fill(0) }
    }
    private fun scope(cache: Cache) = Alerts.digest("${cache.config?.text("server_key")}:${cache.snapshot?.actor?.user_id}:${cache.snapshot?.epoch}:${cache.snapshot?.generation}")
    private suspend fun cache(app: FieldNotesApplication): Cache? {
        if (!app.getSystemService(UserManager::class.java).isUserUnlocked) return null
        val live = app.model.state.value
        if (live.ready) return live.cache.takeUnless { live.needsLogin }
        // Local alarms must not wait for DNS or a network tunnel to initialize.
        return app.vault.load().takeIf { it.token != null && it.snapshot != null }
    }
    suspend fun reconcile(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) { mutex.withLock {
        val app = context.applicationContext as FieldNotesApplication
        Alerts.channels(context)
        val previous = state(app)
        val cache = cache(app)
        val binding = cache?.let(::scope) ?: ""
        val same = binding == previous.scope
        val seen = if (same) previous.seen else emptyMap()
        val active=mutableSetOf("overdue")
        cache?.let { value -> for(row in projectRecords(value)) {
            val r=row.record;if(r.kind=="task")active+="task:${r.id}" else if(r.kind=="event")active+="calendar:${r.id}"
            reminderSet(r)?.let { rules -> for(rule in rules.alerts)active+="${r.id}:${rule.id}";rules.repeat?.let { active+="${r.id}:${it.id}" } }
        } }
        val delivered=if(same)previous.delivered.filterKeys { it in active } else emptyMap()
        val now = Clock.System.now()
        val candidates=if(cache==null)emptyList() else localAlerts(cache,now,TimeZone.currentSystemDefault(),seen.keys,delivered)
        cache?.let { reminderCompatibilityWarning(it)?.let { message->app.deliveryError.value=message } }
        val enabled=candidates.filter { alert ->
                alert.key !in seen && Alerts.allowed(context,alert.profile?.let { ReminderSounds.channel(context,it) } ?: Alerts.REMINDERS)
            }
        if(enabled.size<candidates.size)app.deliveryError.value="Some reminders are blocked by Android notification permissions or channel settings. Review Alerts in Settings."
        val desired=enabled.take(64).associate { it.key to it.at }
        if(!force&&!previous.applyPending&&same&&previous.scheduled==desired&&previous.delivered==delivered) return@withLock
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Save desired state before external effects; boot/restart forcefully
        // reapplies it, including after a crash between two OS calls.
        val next=AlertState(binding, desired, seen,delivered,applyPending=true)
        save(app,next)
        for (key in previous.scheduled.keys - desired.keys) alarms.cancel(intent(context, key))
        for ((key, at) in desired) if (force || previous.applyPending || !same || previous.scheduled[key] != at) {
            val whenMs = maxOf(at * 1000, System.currentTimeMillis() + 1000)
            val action = intent(context, key)
            try {
                if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, action)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, action)
            } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, action) }
        }
        // A cancellation or storage failure leaves applyPending set. The next
        // reconciliation reapplies the same immutable PendingIntents safely.
        save(app,next.copy(applyPending=false))
    }
    }
    suspend fun fire(context: Context, key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
        val app = context.applicationContext as FieldNotesApplication
        val cache = cache(app) ?: return@withLock
        val now = Clock.System.now()
        val previous = state(app)
        val candidates = localAlerts(cache, now, TimeZone.currentSystemDefault(),previous.seen.keys,previous.delivered)
        val binding = scope(cache)
        if (previous.scope != binding || key in previous.seen) return@withLock
        if (previous.scheduled[key]?.let { it<=now.epochSeconds+1 } != true && candidates.none { it.key == key && it.at <= now.epochSeconds + 1 }) return@withLock
        // Drain simultaneous reminders in one alert. Android limits idle alarm
        // frequency; a household with many 09:00 due dates needs one digest.
        val due = candidates.filter { it.at <= now.epochSeconds + 1 && it.key !in previous.seen }
        val posted=mutableListOf<String>()
        for((identity,group) in due.groupBy { it.profile to it.target }) {
            val profile=identity.first
            val keys=group.map { it.key }.sorted()
            val notice=reminderNotice(cache,group,TimeZone.currentSystemDefault())
            val channel=profile?.let { ReminderSounds.channel(context,it) } ?: Alerts.REMINDERS
            if(Alerts.post(context,channel,"$binding:"+keys.joinToString("|"),notice.title,notice.summary,notice.target,notice.details,notice.whenAt,profile?.alarm==true,profile?.let{it.alarm||it.notificationUnmute}==true)) {
                posted+=keys
                if(profile?.let{it.alarm||it.notificationUnmute}==true&&!AlarmPlaybackService.start(context,keys))Alerts.post(context,Alerts.REMINDERS,"alarm-fallback:"+keys.joinToString("|"),notice.title,"Alarm playback needs attention · ${notice.summary}",notice.target,notice.details)
            } else app.deliveryError.value="A reminder channel or notification permission is blocked. Open Android notification settings."
        }
        if(posted.isNotEmpty()) {
            val receipts=(previous.seen+posted.associateWith { now.epochSeconds }).entries.sortedByDescending { it.value }.take(2048).associate { it.toPair() }
            val progress=previous.delivered.toMutableMap()
            for(alert in due.filter { it.key in posted })progress[alert.progressKey]=maxOf(progress[alert.progressKey] ?: Long.MIN_VALUE,alert.at)
            save(app,previous.copy(seen=receipts,delivered=progress,scheduled=previous.scheduled-posted.toSet()))
            val alarms=context.getSystemService(AlarmManager::class.java)
            for(other in posted)alarms.cancel(intent(context,other))
        }
        PlannerWidget.refresh(context)
        UpcomingWidget.refresh(context);CalendarWidget.refresh(context)
        }
        // Refill the bounded rolling alarm set even with no network or UI.
        reconcile(context)
    }
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        val app = context.applicationContext as FieldNotesApplication
        val previous = state(app)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(Alerts.profileTestIntent(context))
        for (key in previous.scheduled.keys) alarms.cancel(intent(context, key))
        save(app, AlertState())
    } }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as FieldNotesApplication
        app.scope.launch {
            try {
                withTimeout(8_000) {
                    if(intent.action=="org.fieldnotes.PROFILE_TEST") {
                        val state=app.model.state.value
                        val cache=if(state.ready)state.cache.takeUnless { state.needsLogin } else app.vault.load()
                        if(cache?.token!=null)Alerts.post(context,Alerts.REMINDERS,"profile-test:"+intent.getStringExtra("nonce"),"Field Notes profile test","This alert was generated in the Field Notes profile. GrapheneOS forwarding and sound settings control delivery to your active profile.")
                    }
                    else if (intent.action == "org.fieldnotes.ALERT") LocalAlarms.fire(context, intent.getStringExtra("occurrence") ?: return@withTimeout)
                    else { LocalAlarms.reconcile(context, true); PlannerWidget.refresh(context); UpcomingWidget.refresh(context);CalendarWidget.refresh(context) }
                }
            } catch(_:Exception){app.deliveryError.value="Reminder delivery needs attention. Open Field Notes."}
            finally { pending.finish() }
        }
    }
}
