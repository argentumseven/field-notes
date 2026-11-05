package org.fieldnotes.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import kotlinx.datetime.*
import org.fieldnotes.domain.*

/** Seven real launcher-hosted days, each with its agenda and occupied-time bar. */
class WeekCalendarWidget:CalendarWidget() {
    companion object {
        fun views(context:Context,cache:Cache,unlocked:Boolean,authenticated:Boolean=true,id:Int=0,
                  first:LocalDate=Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
                  today:LocalDate=Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
                  zone:TimeZone=TimeZone.currentSystemDefault(),interactive:Boolean=true):RemoteViews {
            val start=first.minus(first.dayOfWeek.isoDayNumber%7,DateTimeUnit.DAY)
            val p=widgetPalette(context,cache);val reveal=widgetMayShowTitles(cache,unlocked,authenticated)
            val records=if(reveal)widgetRecords(cache) else emptyList()
            val root=RemoteViews(context.packageName,R.layout.week_calendar_widget)
            root.setInt(R.id.calendar_root,"setBackgroundColor",p.background)
            for(text in listOf(R.id.calendar_month,R.id.calendar_previous,R.id.calendar_next,R.id.calendar_today,R.id.calendar_refresh))root.setTextColor(text,p.text)
            root.setTextColor(R.id.week_legend,p.muted)
            val last=start.plus(6,DateTimeUnit.DAY)
            fun monthName(day:LocalDate)=day.month.name.take(3).lowercase().replaceFirstChar{it.uppercase()}
            root.setTextViewText(R.id.calendar_month,"${monthName(start)} ${start.dayOfMonth}"+(if(start.year!=last.year)" ${start.year}" else "")+" – "+(if(start.month!=last.month)"${monthName(last)} " else "")+last.dayOfMonth+(if(start.year!=last.year)" ${last.year}" else ""))
            val options=context.getSystemService(AppWidgetManager::class.java).getAppWidgetOptions(id)
            val height=options.getInt(if(context.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT)AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,480)
            root.setViewVisibility(R.id.week_legend,if(height<360)View.GONE else View.VISIBLE)
            root.removeAllViews(R.id.week_days)
            for(n in 0..6) {
                val day=start.plus(n,DateTimeUnit.DAY);val entries=calendarEntries(records,day,zone)
                val row=RemoteViews(context.packageName,R.layout.week_calendar_widget_day)
                row.setTextViewText(R.id.week_day_title,"${day.dayOfWeek.name.take(3).lowercase().replaceFirstChar{it.uppercase()}}\n${day.dayOfMonth}")
                row.setTextColor(R.id.week_day_title,if(day==today)p.accent else p.text)
                row.setInt(R.id.week_day_title,"setBackgroundColor",if(day==today)p.selected else android.graphics.Color.TRANSPARENT)
                row.setTextColor(R.id.week_day_summary,p.text)
                row.setInt(R.id.week_day_summary,"setMaxLines",if(height<420)1 else 2)
                row.setTextViewText(R.id.week_day_summary,if(!reveal)"Schedule details hidden" else entries.take(2).joinToString(" · "){"${calendarEntryLabel(it,zone)} ${it.record.data.text("title")}"}.ifBlank{"No scheduled items"}+(if(entries.size>2)" · +${entries.size-2} more" else ""))
                row.setViewVisibility(R.id.week_day_busy,if(height<360||!reveal)View.GONE else View.VISIBLE)
                val from=day.atStartOfDayIn(zone).epochSeconds;val until=day.plus(1,DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds
                for(slot in 0 until 24) {
                    val a=from+(until-from)*slot/24;val b=from+(until-from)*(slot+1)/24
                    val cell=RemoteViews(context.packageName,R.layout.calendar_widget_busy_slot)
                    cell.setInt(R.id.calendar_busy_fill,"setBackgroundColor",blendWidgetColor(p.track,p.accent,if(reveal)occupiedFraction(records,a,b) else 0.0))
                    val point=reveal&&records.any { it.kind=="event"&&!it.flag("all_day")&&it.data.number("ends_at")==0L&&it.data.number("starts_at") in a until b }
                    cell.setTextViewText(R.id.calendar_busy_point,if(point)"•" else "");cell.setTextColor(R.id.calendar_busy_point,p.accent)
                    row.addView(R.id.week_day_busy,cell)
                }
                row.setContentDescription(R.id.week_day,friendlyDate(day)+(if(reveal)" · ${entries.size} scheduled" else ""))
                if(interactive)row.setOnClickPendingIntent(R.id.week_day,CalendarWidget.open(context,day))
                root.addView(R.id.week_days,row)
            }
            val state=(context.applicationContext as FieldNotesApplication).model.state.value
            root.setTextViewText(R.id.calendar_refresh,when{!reveal->widgetHiddenLabel(cache,unlocked,authenticated);widgetProblem(context)->"Needs attention · open settings";state.connectionPaused->"Paused · open app";state.syncing->"Syncing…";else->"Refresh week"})
            if(interactive) {
                for((control,delta) in listOf(R.id.calendar_previous to -1,R.id.calendar_next to 1,R.id.calendar_today to 0)) {
                    val move=Intent(context,WeekCalendarWidget::class.java).setAction(CalendarWidget.WEEK_MOVE).setData(Uri.parse("fieldnotes-widget://week/$id/$delta")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("delta",delta)
                    root.setOnClickPendingIntent(control,PendingIntent.getBroadcast(context,0,move,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                root.setOnClickPendingIntent(R.id.calendar_refresh,if(!reveal||widgetProblem(context))widgetSettings(context) else if(state.connectionPaused)CalendarWidget.open(context,today) else PendingIntent.getBroadcast(context,43,Intent(context,WidgetRefreshReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            return root
        }
        fun pin(context:Context):Boolean {
            val manager=context.getSystemService(AppWidgetManager::class.java)
            return manager.isRequestPinAppWidgetSupported&&manager.requestPinAppWidget(ComponentName(context,WeekCalendarWidget::class.java),null,null)
        }
    }
}
