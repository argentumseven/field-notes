package org.fieldnotes.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import org.fieldnotes.app.R
import org.fieldnotes.domain.Preferences
import org.fieldnotes.domain.dueReminderKeys

class CatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FieldNotesApplication
        app.initialized.await()
        if (!app.model.state.value.ready) return Result.retry()
        val before = app.model.state.value.cache
        val preferences = before.preferences
        if (!preferences.backgroundSync && !preferences.reminders) return Result.success()
        if (preferences.backgroundSync && before.token != null && before.config != null) app.model.sync()
        val after = app.model.state.value
        if (!after.needsLogin && before.snapshot != null && after.cache.snapshot != null && preferences.backgroundSync && after.online && after.cache.snapshot.sequence > before.snapshot.sequence) {
            notify(applicationContext, "updates", 81, "Your workspace has updates", "Open Field Notes to see the latest changes.")
        }
        if (!after.needsLogin) {
            val now = LocalDateTime.now()
            val due = dueReminderKeys(after.cache, now.toLocalDate().toString(), now.hour)
            if (due.isNotEmpty() && notify(applicationContext, "reminders", 82, "A little reminder", "You have household tasks to check in Field Notes.")) app.model.markReminded(due)
        }
        return Result.success()
    }
    companion object {
        fun schedule(context: Context, preferences: Preferences) {
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (!preferences.backgroundSync) notifications.cancel(81)
            if (!preferences.reminders) notifications.cancel(82)
            val wm = WorkManager.getInstance(context)
            if (!preferences.backgroundSync && !preferences.reminders) { wm.cancelUniqueWork("field-notes-catch-up"); return }
            val request = PeriodicWorkRequestBuilder<CatchUpWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            wm.enqueueUniquePeriodicWork("field-notes-catch-up", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        private suspend fun notify(context: Context, channel: String, id: Int, title: String, text: String): Boolean = withContext(Dispatchers.Main.immediate) {
            val current = (context.applicationContext as FieldNotesApplication).model.state.value
            if (current.needsLogin || (channel == "updates" && !current.cache.preferences.backgroundSync) || (channel == "reminders" && !current.cache.preferences.reminders)) return@withContext false
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@withContext false
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return@withContext false
            manager.createNotificationChannel(NotificationChannel(channel, if (channel == "updates") "Workspace updates" else "Local task reminders", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return@withContext false
            val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_field_notes)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).build()
            manager.notify(id, notification)
            true
        }
    }
}
