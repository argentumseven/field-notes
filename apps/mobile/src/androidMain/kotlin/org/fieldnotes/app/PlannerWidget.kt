package org.fieldnotes.app

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.widget.RemoteViews
import kotlinx.coroutines.*
import kotlinx.datetime.*
import org.fieldnotes.domain.*

/** RemoteViews need no Play services. Titles are an explicit disclosure to the
 * launcher, not a protected rendering surface; private/redacted is the default. */
class PlannerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as FieldNotesApplication
        app.scope.launch { try { withTimeout(8_000) { app.initialized.await() }; refresh(context) } finally { pending.finish() } }
    }
    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlannerWidget::class.java))
            if (ids.isEmpty()) return
            val app = context.applicationContext as FieldNotesApplication
            val state = app.model.state.value
            val cache = state.cache
            val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked && !context.getSystemService(KeyguardManager::class.java).isDeviceLocked
            manager.updateAppWidget(ids, views(context, cache, unlocked, !state.needsLogin))
        }
        fun views(context: Context, cache: Cache, unlocked: Boolean, authenticated: Boolean = true): RemoteViews {
            val show = widgetMayShowTitles(cache, unlocked, authenticated)
            val view = RemoteViews(context.packageName, R.layout.planner_widget)
            val open = PendingIntent.getActivity(context, 41, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            listOf(R.id.widget_brand, R.id.widget_title, R.id.widget_tasks, R.id.widget_footer).forEach { view.setOnClickPendingIntent(it, open) }
            if (show) {
                val now = Clock.System.now(); val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
                val tasks = focusTasks(cache, today, now.epochSeconds).take(4)
                view.setTextViewText(R.id.widget_title, "Focus · ${tasks.size} next")
                view.setTextViewText(R.id.widget_tasks, tasks.joinToString("\n\n") { "${priorityName(it.first.record.data.number("priority", 3).toInt())} · ${it.first.record.data.text("title").take(120)}" }.ifBlank { "No available tasks" })
                view.setTextViewText(R.id.widget_footer, "Cached locally · tap to open and sync")
            } else {
                view.setTextViewText(R.id.widget_title, "Open your private planner")
                view.setTextViewText(R.id.widget_tasks, "")
                view.setTextViewText(R.id.widget_footer, "Tap to unlock · titles hidden")
            }
            return view
        }
    }
}
