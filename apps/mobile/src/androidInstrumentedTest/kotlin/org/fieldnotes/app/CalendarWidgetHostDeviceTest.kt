package org.fieldnotes.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Uses the real system AppWidgetHost, independently of Compose's test clock. */
class CalendarWidgetHostDeviceTest {
    @Test fun monthIsHostedRepaintsOfflineAndOpensTheChosenDate()=host(false)
    @Test fun weekIsHostedRepaintsOfflineAndOpensTheChosenDate()=host(true)
    private fun host(weekly:Boolean) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue("Disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val info=instrumentation.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo=info
        val app=instrumentation.targetContext.applicationContext as FieldNotesApplication
        runBlocking { app.initialized.await() }
        val zone=TimeZone.currentSystemDefault();val today=Clock.System.now().toLocalDateTime(zone).date
        val start=scheduledInstant(today.toString(),"10:00",zone)
        val event=Record("calendar-fixture","event",1,obj("title" to "Household check-in".j(),"author_id" to "owner".j(),"starts_at" to start.j(),"ends_at" to (start+3600).j()))
        val cache=Cache(keys=app.model.state.value.cache.keys,config=obj("server_key" to "synthetic-calendar".j()),token="synthetic-session",snapshot=Snapshot(1,"calendar-fixture",1,1,listOf(event),Actor("owner","Owner","owner","session")),
            preferences=Preferences(connectionPaused=true,backgroundSync=false,reminders=false,liveUpdates=false,widgetTitles=true))
        runBlocking { app.vault.save(cache);app.model.initialize() }
        app.deliveryError.value=null
        fun nodes(text:String):List<AccessibilityNodeInfo> {
            val found=mutableListOf<AccessibilityNodeInfo>()
            fun visit(node:AccessibilityNodeInfo) {
                if(node.text?.contains(text)==true)found+=node
                for(i in 0 until node.childCount)node.getChild(i)?.let(::visit)
            }
            instrumentation.uiAutomation.windows.forEach { it.root?.let(::visit) }
            return found
        }
        // RemoteViews descendants retain the provider package name. Select the
        // launcher window, rather than incorrectly filtering each child by it.
        fun launcher(text:String)=instrumentation.uiAutomation.windows.filter { it.root?.packageName?.toString()=="com.android.launcher3" }.flatMap { it.root?.findAccessibilityNodeInfosByText(text).orEmpty() }
        fun described(value:String):List<AccessibilityNodeInfo> {
            val found=mutableListOf<AccessibilityNodeInfo>()
            fun visit(node:AccessibilityNodeInfo) {
                if(node.contentDescription?.contains(value)==true)found+=node
                for(i in 0 until node.childCount)node.getChild(i)?.let(::visit)
            }
            instrumentation.uiAutomation.windows.forEach { it.root?.let(::visit) }
            return found
        }
        fun picture(name:String) {
            File(app.filesDir,"calendar-gallery").mkdirs()
            instrumentation.uiAutomation.takeScreenshot().also { bitmap -> File(app.filesDir,"calendar-gallery/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        }
        fun waitFor(stage:String,condition:()->Boolean) {
            val end=System.currentTimeMillis()+90_000
            while(System.currentTimeMillis()<end) { if(condition())return;Thread.sleep(250) }
            picture("host-failure")
            error("Calendar host did not reach $stage; active="+instrumentation.uiAutomation.rootInActiveWindow?.packageName)
        }
        // A launcher PendingIntent uses a real app task. ActivityScenario owns a
        // special launch Intent and cannot follow that task's lifecycle reliably.
        // MonitoringInstrumentation's fixed 45-second idle budget is too short
        // for a cold software-rendered emulator. Observe the real resumed task.
        app.startActivity(android.content.Intent(app,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        var activity:MainActivity?=null
        waitFor("resumed app") {
            instrumentation.runOnMainSync { activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() }
            activity!=null
        }
        try {
            instrumentation.runOnMainSync { assertTrue(if(weekly)WeekCalendarWidget.pin(activity!!) else CalendarWidget.pin(activity!!)) }
            waitFor("pin confirmation") { launcher("Add").any { it.isClickable||it.parent?.isClickable==true } }
            picture("pin-confirmation")
            val choices=launcher("Add")
            val add=choices.firstOrNull { it.className?.toString()?.contains("Button")==true } ?: choices.first { it.isClickable||it.parent?.isClickable==true }
            assertTrue((if(add.isClickable)add else add.parent).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val manager=app.getSystemService(AppWidgetManager::class.java)
            waitFor("binding") { manager.getAppWidgetIds(ComponentName(app,if(weekly)WeekCalendarWidget::class.java else CalendarWidget::class.java)).isNotEmpty() }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            var page=0;var nextSwipe=System.currentTimeMillis()+8000
            waitFor("visible month") {
                if(described(friendlyDate(today)).any { it.contentDescription?.contains("1 scheduled")==true })true else {
                    if(page<6&&System.currentTimeMillis()>=nextSwipe) { val m=app.resources.displayMetrics;instrumentation.uiAutomation.executeShellCommand("input swipe ${m.widthPixels*9/10} ${m.heightPixels/3} ${m.widthPixels/10} ${m.heightPixels/3} 250").close();page++;nextSwipe=System.currentTimeMillis()+5000 };false
                }
            }
            runBlocking { app.model.enqueue("event.update",event.id,obj("title" to "Updated check-in".j()),1) }
            val tomorrow=today.plus(1,DateTimeUnit.DAY)
            runBlocking { app.model.enqueue("event.create",app.model.newId(),obj("title" to "Tomorrow’s reminder".j(),"starts_at" to scheduledInstant(tomorrow.toString(),"10:00",zone).j()),null) }
            waitFor("automatic offline repaint") { described(friendlyDate(tomorrow)).any { it.contentDescription?.contains("1 scheduled")==true } }
            if(!weekly)assertTrue(described("Monday").isNotEmpty())
            assertTrue(described(if(weekly)"Next week" else "Next month").isNotEmpty())
            if(weekly)assertTrue(nodes("Updated check-in").isNotEmpty())
            picture(if(weekly)"42-home-week" else "32-home-calendar")
            assertTrue(described(friendlyDate(tomorrow)).first { it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("calendar date link") { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="org.fieldnotes.app"&&nodes("Tomorrow’s reminder").any { it.packageName?.toString()=="org.fieldnotes.app" } }
            instrumentation.runOnMainSync {
                val current=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
                assertEquals(tomorrow.toString(),current.intent.getStringExtra("open_date"))
            }
        } finally {
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().forEach { it.finish() } }
        }
    }
}
