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
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
        for ((id, name) in listOf(REMINDERS to "Upcoming tasks and calendar", UPDATES to "Household updates")) {
            // Existing user changes to importance/sound are never reset.
            if (manager.getNotificationChannel(id) == null) manager.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audio)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                })
        }
        if (manager.getNotificationChannel(CONNECTION) == null) manager.createNotificationChannel(
            NotificationChannel(CONNECTION, "Private background connection", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                // GrapheneOS does not forward this ongoing status; real alerts
                // remain PRIVATE and eligible for its system forwarding.
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
    }
    fun allowed(context: Context, channel: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun post(context: Context, channel: String, key: String, title: String, text: String): Boolean {
        channels(context)
        if (!allowed(context, channel)) return false
        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("fieldnotes://notification/" + digest(key)))
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_field_notes)
            .setContentTitle(title).setContentText(text).setContentIntent(open)
            .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_field_notes).setContentTitle("Field Notes alert").build())
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setOnlyAlertOnce(true).build()
        return try {
            // A unique tag per occurrence lets later reminders sound; retrying
            // this same occurrence updates its existing notification quietly.
            context.getSystemService(NotificationManager::class.java).notify("field-notes:" + digest(key), 0, notification)
            true
        } catch (_: SecurityException) { false }
    }
    fun test(context: Context) = post(context, REMINDERS, "test:" + System.nanoTime(), "Field Notes sound test", "This is the reminder channel. Android and profile settings control its sound.")
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
    val scope: String = "", val scheduled: Map<String, Long> = emptyMap(), val seen: Map<String, Long> = emptyMap(),
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
    suspend fun reconcile(context: Context, force: Boolean = false) = mutex.withLock {
        val app = context.applicationContext as FieldNotesApplication
        Alerts.channels(context)
        val previous = state(app)
        val cache = cache(app)
        val binding = cache?.let(::scope) ?: ""
        val same = binding == previous.scope
        val seen = if (same) previous.seen else emptyMap()
        val now = Clock.System.now()
        val desired = if (cache == null || !Alerts.allowed(context, Alerts.REMINDERS)) emptyMap() else
            localAlerts(cache, now, TimeZone.currentSystemDefault()).filter { it.key !in seen }.take(64).associate { it.key to it.at }
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Save desired state before external effects; boot/restart forcefully
        // reapplies it, including after a crash between two OS calls.
        save(app, AlertState(binding, desired, seen))
        for (key in previous.scheduled.keys - desired.keys) alarms.cancel(intent(context, key))
        for ((key, at) in desired) if (force || !same || previous.scheduled[key] != at) {
            val whenMs = maxOf(at * 1000, System.currentTimeMillis() + 1000)
            val action = intent(context, key)
            try {
                if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, action)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, action)
            } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, action) }
        }
    }
    suspend fun fire(context: Context, key: String) = mutex.withLock {
        val app = context.applicationContext as FieldNotesApplication
        val cache = cache(app) ?: return@withLock
        val now = Clock.System.now()
        val candidates = localAlerts(cache, now, TimeZone.currentSystemDefault())
        val previous = state(app)
        val binding = scope(cache)
        if (previous.scope != binding || key in previous.seen) return@withLock
        if (candidates.none { it.key == key && it.at <= now.epochSeconds + 1 }) return@withLock
        // Drain simultaneous reminders in one alert. Android limits idle alarm
        // frequency; a household with many 09:00 due dates needs one digest.
        val due = candidates.filter { it.at <= now.epochSeconds + 1 && it.key !in previous.seen }
        val keys = due.map { it.key }.sorted()
        val text = if (due.size > 1) "${due.size} reminders are ready. Open Field Notes for details." else when (due.single().kind) {
            "calendar" -> "A calendar event is coming up. Open Field Notes for details."
            "overdue" -> "You have overdue household work to review."
            else -> "You have a household task due today."
        }
        if (Alerts.post(context, Alerts.REMINDERS, "$binding:" + keys.joinToString("|"), "Field Notes reminder", text)) {
            val receipts = (previous.seen + keys.associateWith { now.epochSeconds }).entries
                .sortedByDescending { it.value }.take(2048).associate { it.toPair() }
            save(app, previous.copy(seen = receipts, scheduled = previous.scheduled - keys.toSet()))
            val alarms = context.getSystemService(AlarmManager::class.java)
            for (other in keys) alarms.cancel(intent(context, other))
        }
        PlannerWidget.refresh(context)
        UpcomingWidget.refresh(context)
    }
    suspend fun clear(context: Context) = mutex.withLock {
        val app = context.applicationContext as FieldNotesApplication
        val previous = state(app)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(Alerts.profileTestIntent(context))
        for (key in previous.scheduled.keys) alarms.cancel(intent(context, key))
        save(app, AlertState())
    }
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
                    else { LocalAlarms.reconcile(context, true); PlannerWidget.refresh(context); UpcomingWidget.refresh(context) }
                }
            } catch(_:Exception){app.deliveryError.value="Reminder delivery needs attention. Open Field Notes."}
            finally { pending.finish() }
        }
    }
}
