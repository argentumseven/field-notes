package org.fieldnotes.app

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.content.res.Configuration
import android.os.Bundle
import android.os.UserManager
import android.widget.RemoteViews
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.datetime.*
import org.fieldnotes.domain.*

/** A real launcher provider. The agenda and the ranked Focus widget are separate. */
class UpcomingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refresh(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { refresh(context) }
    companion object {
        fun refresh(context: Context) {
            val manager=context.getSystemService(AppWidgetManager::class.java)
            val ids=manager.getAppWidgetIds(ComponentName(context,UpcomingWidget::class.java))
            if(ids.isEmpty())return
            val state=(context.applicationContext as FieldNotesApplication).model.state.value
            val unlocked=context.getSystemService(UserManager::class.java).isUserUnlocked&&!context.getSystemService(KeyguardManager::class.java).isDeviceLocked
            for(id in ids){
                val options=manager.getAppWidgetOptions(id)
                // Launcher options describe both orientations. Portrait uses
                // the taller bound; the landscape minimum can hide half the
                // agenda even when the visible widget has plenty of space.
                val height=if(context.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT)
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,240)
                    else options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,240)
                val rows=if(height<220)2 else 4
                manager.updateAppWidget(id,views(context,state.cache,unlocked,!state.needsLogin,rows))
            }
        }
        fun views(context:Context,cache:Cache,unlocked:Boolean,authenticated:Boolean=true,rows:Int=4):RemoteViews {
            val view=RemoteViews(context.packageName,R.layout.upcoming_widget)
            val now=Clock.System.now();val zone=TimeZone.currentSystemDefault();val today=now.toLocalDateTime(zone).date
            val open=PendingIntent.getActivity(context,42,Intent(context,MainActivity::class.java).putExtra("open_page","Calendar"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            for(id in listOf(R.id.widget_brand,R.id.widget_title,R.id.widget_tasks))view.setOnClickPendingIntent(id,open)
            val refresh=PendingIntent.getBroadcast(context,43,Intent(context,WidgetRefreshReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            view.setOnClickPendingIntent(R.id.widget_footer,refresh)
            val all=upcomingAgenda(cache,now,zone)
            val agenda=all.take(rows)
            view.setTextViewText(R.id.widget_title,"Upcoming · ${today.monthNumber}/${today.dayOfMonth}")
            if(widgetMayShowTitles(cache,unlocked,authenticated)){
                val content=agenda.joinToString("\n\n") { item->
                    val time=Instant.fromEpochSeconds(item.at).toLocalDateTime(zone)
                    val day=if(time.date==today)"Today" else "${time.monthNumber}/${time.dayOfMonth}"
                    val label=if(item.label=="Event"||item.label=="Now")"$day ${time.hour.toString().padStart(2,'0')}:${time.minute.toString().padStart(2,'0')}" else "$day · ${item.label}"
                    "$label  ${item.title.take(100)}"
                }.ifBlank {
                    focusTasks(cache,today,now.epochSeconds).filter { it.first.record.data.text("due").isBlank() && it.first.record.data.text("planned_for").isBlank() }.take(rows).joinToString("\n\n") { "No date · ${it.first.record.data.text("title").take(100)}" }.ifBlank { "Nothing coming up in the next 7 days" }
                }
                view.setTextViewText(R.id.widget_tasks,content)
                view.setInt(R.id.widget_tasks,"setMaxLines",rows*3)
            } else view.setTextViewText(R.id.widget_tasks,"${all.size} upcoming items\nOpen Field Notes to unlock or enable home-screen titles.")
            view.setTextViewText(R.id.widget_footer,"Cached locally · tap here to refresh")
            return view
        }
        fun pin(context:Context):Boolean {
            val manager=context.getSystemService(AppWidgetManager::class.java)
            return manager.isRequestPinAppWidgetSupported&&manager.requestPinAppWidget(ComponentName(context,UpcomingWidget::class.java),null,null)
        }
    }
}

class WidgetRefreshReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        WorkManager.getInstance(context).enqueueUniqueWork("field-notes-widget-refresh",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
    }
}
class WidgetRefreshWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result {
        val app=applicationContext as FieldNotesApplication
        app.initialized.await()
        if(app.model.state.value.ready&&!app.model.state.value.needsLogin)app.model.sync()
        PlannerWidget.refresh(applicationContext);UpcomingWidget.refresh(applicationContext)
        return Result.success()
    }
}
