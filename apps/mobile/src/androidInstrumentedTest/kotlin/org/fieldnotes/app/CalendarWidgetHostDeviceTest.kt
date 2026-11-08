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
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        instrumentation.uiAutomation.serviceInfo=info
        val app=instrumentation.targetContext.applicationContext as FieldNotesApplication
        runBlocking { app.initialized.await() }
        val zone=TimeZone.currentSystemDefault();val today=Clock.System.now().toLocalDateTime(zone).date
        val start=scheduledInstant(today.toString(),"10:00",zone)
        val event=Record("calendar-fixture","event",1,obj("title" to "Household check-in".j(),"author_id" to "owner".j(),"starts_at" to start.j(),"ends_at" to (start+3600).j()))
        val cache=Cache(keys=app.model.state.value.cache.keys,config=obj("server_key" to "synthetic-calendar".j()),token="synthetic-session",snapshot=Snapshot(1,"calendar-fixture",1,1,listOf(event),Actor("owner","Owner","owner","session")),
            preferences=Preferences(connectionPaused=true,backgroundSync=false,reminders=false,liveUpdates=false,widgetTitles=true,appLock=true))
        runBlocking { app.vault.save(cache);app.model.initialize() }
        app.deliveryError.value=null
        fun root(window:android.view.accessibility.AccessibilityWindowInfo)=if(android.os.Build.VERSION.SDK_INT>=33)window.getRoot(0)else window.root
        fun child(node:AccessibilityNodeInfo,index:Int)=if(android.os.Build.VERSION.SDK_INT>=33)node.getChild(index,0)else node.getChild(index)
        fun search(text:String,description:Boolean=false):List<AccessibilityNodeInfo> {
            val windows=instrumentation.uiAutomation.windows
            for(window in windows){val r=root(window)?:continue
                if(r.packageName?.toString()=="com.android.launcher3"){
                    if(!description){
                        val matches=r.findAccessibilityNodeInfosByText(text).filter{it.isVisibleToUser}
                        if(matches.isNotEmpty())return matches
                        continue
                    }
                    val ids=listOf("calendar_next","calendar_previous","calendar_today","calendar_refresh","calendar_cell","week_day")+(1..7).map{"calendar_weekday_$it"}
                    for(id in ids){
                        val matches=r.findAccessibilityNodeInfosByViewId("org.fieldnotes.app:id/$id").filter{it.isVisibleToUser&&it.contentDescription?.contains(text,true)==true}
                        if(matches.isNotEmpty())return matches
                    }
                    continue
                }
                fun visit(node:AccessibilityNodeInfo):AccessibilityNodeInfo?{
                    if(node.isVisibleToUser&&(if(description)node.contentDescription else node.text)?.contains(text,ignoreCase=true)==true)return node
                    for(i in 0 until node.childCount)child(node,i)?.let{visit(it)}?.let{return it};return null
                }
                visit(r)?.let{return listOf(it)}

            };return emptyList()
        }
        fun shell(command:String){instrumentation.uiAutomation.executeShellCommand(command).use{fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes()}}
        fun nodes(text:String)=search(text)
        fun launcher(text:String)=search(text)
        fun described(value:String)=search(value,true)
        fun picture(name:String) {
            File(app.filesDir,"calendar-gallery").mkdirs()
            instrumentation.uiAutomation.takeScreenshot().also { bitmap -> File(app.filesDir,"calendar-gallery/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        }
        fun waitFor(stage:String,condition:()->Boolean) {
            android.util.Log.i("FieldNotesWidgetTest",stage)
            val end=System.currentTimeMillis()+90_000
            while(System.currentTimeMillis()<end) { if(condition())return;Thread.sleep(500) }
            instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().forEach {it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}}
            picture("host-failure")
            error("Calendar host did not reach $stage; unlocked="+WidgetVisibility.unlocked(app)+"; delivery="+app.deliveryError.value+"; active="+instrumentation.uiAutomation.rootInActiveWindow?.packageName)
        }
        check(android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).bufferedReader().use{it.readText().trim()}=="field-notes-test")
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
            instrumentation.runOnMainSync {
                activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity!!.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            // Pin from the actual resumed activity. The selected-date assertion
            // below proves navigation without walking the full Compose tree first.
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
                if(described(if(weekly)"Next week" else "Next month").isNotEmpty()&&described(friendlyDate(today)).any { it.contentDescription?.contains("1 scheduled")==true })true else {
                    if(page<6&&System.currentTimeMillis()>=nextSwipe) {
                        val bounds=android.graphics.Rect()
                        instrumentation.uiAutomation.windows.firstOrNull{root(it)?.packageName?.toString()=="com.android.launcher3"}?.let{root(it)?.getBoundsInScreen(bounds)}
                        check(bounds.width()>0&&bounds.height()>0){"Launcher bounds unavailable"}
                        shell("input swipe ${bounds.right-bounds.width()/10} ${bounds.top+bounds.height()/3} ${bounds.left+bounds.width()/10} ${bounds.top+bounds.height()/3} 300")
                        page++;nextSwipe=System.currentTimeMillis()+5000
                    };false
                }
            }
            runBlocking { app.model.enqueue("event.update",event.id,obj("title" to "Updated check-in".j()),1) }
            val tomorrow=today.plus(1,DateTimeUnit.DAY)
            runBlocking { app.model.enqueue("event.create",app.model.newId(),obj("title" to "Tomorrow’s reminder".j(),"starts_at" to scheduledInstant(tomorrow.toString(),"10:00",zone).j()),null) }
            waitFor("automatic offline repaint") { described(friendlyDate(tomorrow)).any { it.contentDescription?.contains("1 scheduled")==true } }
            if(!weekly)assertTrue(described("Monday").isNotEmpty())
            assertTrue(described(if(weekly)"Next week" else "Next month").isNotEmpty())
            if(weekly)assertTrue(nodes("Updated check-in").isNotEmpty())
            val beforeSequence=app.model.state.value.cache.snapshot!!.sequence

            shell("input keyevent KEYCODE_SLEEP")
            waitFor("screen-off privacy state"){!WidgetVisibility.unlocked(app)}
            Thread.sleep(1500)
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            waitFor("reveal after unlock without a new sync"){described(friendlyDate(tomorrow)).any{it.contentDescription?.contains("1 scheduled")==true}}
            assertEquals(beforeSequence,app.model.state.value.cache.snapshot!!.sequence)
            picture(if(weekly)"42-home-week" else "32-home-calendar")
            assertTrue(described(friendlyDate(tomorrow)).first { it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("calendar date link") { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="org.fieldnotes.app"&&nodes("Tomorrow’s reminder").any { it.packageName?.toString()=="org.fieldnotes.app" } }
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().forEach {it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}
            }
            // The timeline hint can be outside a short viewport; the navigation
            // controls identify Day rather than the previous Month view.
            waitFor("day view after widget link") {described("Next day").isNotEmpty()}
            picture(if(weekly)"74-week-widget-day-link" else "73-calendar-widget-day-link")
            instrumentation.runOnMainSync {
                val current=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
                assertEquals(tomorrow.toString(),current.intent.getStringExtra("open_date"))
            }
        } finally {
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().forEach { it.finish() } }
        }
    }
}
