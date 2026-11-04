package org.fieldnotes.app

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.view.View
import android.appwidget.AppWidgetProviderInfo
import android.os.UserManager
import android.widget.RemoteViews
import kotlinx.datetime.*
import kotlinx.coroutines.*
import org.fieldnotes.domain.*

/** A real month grid hosted by the launcher, independent of the Upcoming agenda. */
open class CalendarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { updateAsync(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { updateAsync(context) }
    override fun onDeleted(context: Context, ids: IntArray) {
        context.getSharedPreferences(PREFS,0).edit().apply { ids.forEach { remove("month-$it");remove("week-$it") } }.apply()
    }
    override fun onDisabled(context: Context) { if(widgetIds(context).isEmpty())context.getSystemService(AlarmManager::class.java).cancel(tick(context)) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context,intent)
        if(intent.action == MOVE || intent.action==WEEK_MOVE) {
            val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1)
            if(id !in widgetIds(context))return
            val today=Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val weekly=intent.action==WEEK_MOVE
            val month=if(weekly)displayWeek(context,id,today) else displayMonth(context,id,today)
            val delta=intent.getIntExtra("delta",0).coerceIn(-1,1)
            val next=if(delta==0)today else month.plus(if(weekly)delta*7 else delta,if(weekly)DateTimeUnit.DAY else DateTimeUnit.MONTH)
            val key="${if(weekly)"week" else "month"}-$id"
            context.getSharedPreferences(PREFS,0).edit().apply { if(delta==0)remove(key) else putString(key,next.toString()) }.apply()
            updateAsync(context)
        } else if(intent.action == TICK) updateAsync(context)
    }
    private fun updateAsync(context:Context) {
        val pending=goAsync()
        schedule(context).invokeOnCompletion { pending.finish() }
    }
    companion object {
        private val renderLock=Any()
        private var renderJob:Job?=null
        private var requested=false
        private fun schedule(context:Context):Job = synchronized(renderLock) {
            val app=context.applicationContext as FieldNotesApplication
            requested=true
            renderJob?.takeIf { it.isActive } ?: app.scope.launch(Dispatchers.Default) {
                while(synchronized(renderLock) { if(requested) { requested=false;true } else { renderJob=null;false } }) {
                    try { render(app) } catch(e:Exception) {
                        if(e is CancellationException)throw e
                        app.deliveryError.value="Calendar widget needs a refresh. Open Field Notes."
                    }
                }
            }.also { renderJob=it }
        }
        fun refresh(context:Context) { schedule(context) }

        private const val PREFS="calendar-widget"
        private const val MOVE="org.fieldnotes.CALENDAR_MONTH"
        internal const val WEEK_MOVE="org.fieldnotes.CALENDAR_WEEK"
        private const val TICK="org.fieldnotes.CALENDAR_MIDNIGHT"
        private fun displayMonth(context:Context,id:Int,today:LocalDate):LocalDate = runCatching {
            LocalDate.parse(context.getSharedPreferences(PREFS,0).getString("month-$id",null) ?: "")
        }.getOrDefault(LocalDate(today.year,today.monthNumber,1))
        private fun displayWeek(context:Context,id:Int,today:LocalDate):LocalDate = runCatching {
            LocalDate.parse(context.getSharedPreferences(PREFS,0).getString("week-$id",null) ?: "")
        }.getOrDefault(today.minus(today.dayOfWeek.isoDayNumber-1,DateTimeUnit.DAY))
        private fun widgetIds(context:Context):IntArray {
            val manager=context.getSystemService(AppWidgetManager::class.java)
            return manager.getAppWidgetIds(ComponentName(context,CalendarWidget::class.java))+manager.getAppWidgetIds(ComponentName(context,WeekCalendarWidget::class.java))
        }
        private fun tick(context:Context)=PendingIntent.getBroadcast(context,731,Intent(context,CalendarWidget::class.java).setAction(TICK),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun render(context:Context) {
            val manager=context.getSystemService(AppWidgetManager::class.java)
            val ids=widgetIds(context)
            if(ids.isEmpty())return
            val state=(context.applicationContext as FieldNotesApplication).model.state.value
            val unlocked=context.getSystemService(UserManager::class.java).isUserUnlocked&&!context.getSystemService(KeyguardManager::class.java).isDeviceLocked
            val zone=TimeZone.currentSystemDefault();val today=Clock.System.now().toLocalDateTime(zone).date
            val weekly=manager.getAppWidgetIds(ComponentName(context,WeekCalendarWidget::class.java)).toSet()
            for(id in ids)manager.updateAppWidget(id,if(id in weekly)WeekCalendarWidget.views(context,state.cache,unlocked,!state.needsLogin,id,displayWeek(context,id,today),today,zone) else views(context,state.cache,unlocked,!state.needsLogin,id,displayMonth(context,id,today),today,zone))
            val midnight=today.plus(1,DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,midnight,tick(context))
        }
        private val dayIntents=object:android.util.LruCache<String,PendingIntent>(128) {}
        internal fun open(context:Context,day:LocalDate):PendingIntent = synchronized(dayIntents) {
            dayIntents.get(day.toString()) ?: PendingIntent.getActivity(context,0,
                Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).setData(Uri.parse("fieldnotes-widget://calendar/$day"))
                    .putExtra("open_page","Calendar").putExtra("open_date",day.toString()),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE).also { dayIntents.put(day.toString(),it) }
        }
        fun views(context:Context,cache:Cache,unlocked:Boolean,authenticated:Boolean=true,id:Int=0,
                  month:LocalDate=Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
                  today:LocalDate=Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
                  zone:TimeZone=TimeZone.currentSystemDefault(),interactive:Boolean=true):RemoteViews {
            val view=RemoteViews(context.packageName,R.layout.calendar_widget)
            val palette=widgetPalette(context,cache)
            view.setInt(R.id.calendar_root,"setBackgroundColor",palette.background)
            for(textId in listOf(R.id.calendar_month,R.id.calendar_agenda,R.id.calendar_previous,R.id.calendar_next,R.id.calendar_today,R.id.calendar_refresh))view.setTextColor(textId,palette.text)
            for(textId in listOf(R.id.calendar_weekday_1,R.id.calendar_weekday_2,R.id.calendar_weekday_3,R.id.calendar_weekday_4,R.id.calendar_weekday_5,R.id.calendar_weekday_6,R.id.calendar_weekday_7,R.id.calendar_busy_title,R.id.calendar_tick_0,R.id.calendar_tick_1,R.id.calendar_tick_2,R.id.calendar_tick_3,R.id.calendar_tick_4))view.setTextColor(textId,palette.muted)
            for(buttonId in listOf(R.id.calendar_previous,R.id.calendar_next,R.id.calendar_today,R.id.calendar_refresh)) {
                if(Build.VERSION.SDK_INT>=31)view.setColorStateList(buttonId,"setBackgroundTintList",ColorStateList.valueOf(palette.selected)) else view.setInt(buttonId,"setBackgroundColor",palette.selected)
            }
            val reveal=widgetMayShowTitles(cache,unlocked,authenticated)
            val records=if(reveal)widgetRecords(cache) else emptyList()
            view.setTextViewText(R.id.calendar_month,"${month.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${month.year}")
            if(interactive)view.setOnClickPendingIntent(R.id.calendar_month,open(context,today))
            for((control,delta) in listOf(R.id.calendar_previous to -1,R.id.calendar_next to 1,R.id.calendar_today to 0)) {
                if(!interactive)continue
                val intent=Intent(context,CalendarWidget::class.java).setAction(MOVE).setData(Uri.parse("fieldnotes-widget://month/$id/$delta"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("delta",delta)
                view.setOnClickPendingIntent(control,PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            view.removeAllViews(R.id.calendar_weeks)
            for(week in monthCells(month).chunked(7)) {
                val row=RemoteViews(context.packageName,R.layout.calendar_widget_week)
                for(day in week) {
                    val cell=RemoteViews(context.packageName,R.layout.calendar_widget_day)
                    val entries=if(day!=null&&reveal)calendarEntries(records,day,zone) else emptyList()
                    cell.setTextViewText(R.id.calendar_day,day?.dayOfMonth?.toString() ?: "")
                    cell.setTextColor(R.id.calendar_day,if(day==today)palette.accent else palette.text)
                    val busy=if(day!=null&&reveal)occupiedFraction(records,day.atStartOfDayIn(zone).epochSeconds,day.plus(1,DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds) else 0.0
                    cell.setTextViewText(R.id.calendar_marker,if(busy>0)"▬".repeat(kotlin.math.ceil(busy*8).toInt().coerceIn(1,4)) else if(entries.isNotEmpty())"•" else "")
                    cell.setTextColor(R.id.calendar_marker,palette.accent)
                    cell.setInt(R.id.calendar_day,"setBackgroundColor",if(day==today)palette.selected else android.graphics.Color.TRANSPARENT)
                    if(day!=null) {
                        cell.setContentDescription(R.id.calendar_cell,friendlyDate(day)+(if(day==today)" · Today" else "")+(if(reveal)" · ${entries.size} scheduled" else ""))
                        if(interactive)cell.setOnClickPendingIntent(R.id.calendar_cell,open(context,day))
                    }
                    row.addView(R.id.calendar_week,cell)
                }
                view.addView(R.id.calendar_weeks,row)
            }
            val dayStart=today.atStartOfDayIn(zone).epochSeconds
            val dayEnd=today.plus(1,DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds
            view.removeAllViews(R.id.calendar_busy)
            for(i in 0 until 48) {
                val from=dayStart+(dayEnd-dayStart)*i/48;val until=dayStart+(dayEnd-dayStart)*(i+1)/48
                val busy=if(reveal)occupiedFraction(records,from,until) else 0.0
                val point=reveal&&records.any { it.kind=="event"&&!it.flag("all_day")&&it.data.number("ends_at")==0L&&it.data.number("starts_at") in from until until }
                val slot=RemoteViews(context.packageName,R.layout.calendar_widget_busy_slot)
                slot.setInt(R.id.calendar_busy_fill,"setBackgroundColor",blendWidgetColor(palette.track,palette.accent,busy))
                slot.setTextViewText(R.id.calendar_busy_point,if(point)"•" else "");slot.setTextColor(R.id.calendar_busy_point,palette.accent)
                view.addView(R.id.calendar_busy,slot)
            }
            view.setTextViewText(R.id.calendar_busy_title,if(reveal)"Today’s schedule · blocks are occupied time" else "Today’s schedule · details hidden")
            for((i,tick) in listOf(R.id.calendar_tick_0,R.id.calendar_tick_1,R.id.calendar_tick_2,R.id.calendar_tick_3,R.id.calendar_tick_4).withIndex()) {
                val at=dayStart+(dayEnd-dayStart)*i/4
                view.setTextViewText(tick,friendlyTime(Instant.fromEpochSeconds(at).toLocalDateTime(zone).time).replace(":00","").replace(" AM","a").replace(" PM","p"))
            }
            val options=context.getSystemService(AppWidgetManager::class.java).getAppWidgetOptions(id)
            val height=options.getInt(if(context.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT)AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,480)
            view.setViewVisibility(R.id.calendar_agenda,if(height<430)View.GONE else View.VISIBLE)
            val agenda=if(reveal)calendarEntries(records,today,zone) else emptyList()
            view.setTextViewText(R.id.calendar_agenda,if(!reveal)"Choose Show schedule to enable details. The app lock is a separate setting." else agenda.take(2).joinToString("\n") { "${calendarEntryLabel(it,zone)}  ${it.record.data.text("title").take(80)}" }.ifBlank { "Today · No scheduled items" })
            if(interactive)view.setOnClickPendingIntent(R.id.calendar_agenda,open(context,today))
            val state=(context.applicationContext as FieldNotesApplication).model.state.value
            view.setTextViewText(R.id.calendar_refresh,when {
                !interactive->"Schedule visibility settings";!reveal->widgetHiddenLabel(cache,unlocked,authenticated);widgetProblem(context)->"Needs attention · open settings";state.connectionPaused->"Paused · open app";state.syncing->"Syncing…";cache.outbox.isNotEmpty()->"${cache.outbox.size} queued · retry";cache.preferences.liveUpdates->if(state.online)"Auto refresh · sync now" else "Offline · auto retry";cache.preferences.backgroundSync->"Periodic sync · refresh";else->"Manual sync · refresh"
            })
            if(interactive)view.setOnClickPendingIntent(R.id.calendar_refresh,if(!reveal||widgetProblem(context))widgetSettings(context) else if(state.connectionPaused)open(context,today) else PendingIntent.getBroadcast(context,43,Intent(context,WidgetRefreshReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return view
        }
        fun pin(context:Context):Boolean {
            val manager=context.getSystemService(AppWidgetManager::class.java)
            val component=ComponentName(context,CalendarWidget::class.java)
            if(Build.VERSION.SDK_INT>=35)runCatching {
                // Generated previews show the actual month, never household data.
                val state=(context.applicationContext as FieldNotesApplication).model.state.value
                manager.setWidgetPreview(component,AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,views(context,state.cache,false,interactive=false))
            }
            return manager.isRequestPinAppWidgetSupported&&manager.requestPinAppWidget(component,null,null)
        }
    }
}
