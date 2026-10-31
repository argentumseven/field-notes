package org.fieldnotes.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import java.util.UUID
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Real provider -> system confirmation -> actual launcher AppWidgetHost.
 * Only synthetic household content is seeded; FLAG_SECURE is never disabled. */
class HomeWidgetDeviceTest {
    @Test fun upcomingWidgetIsActuallyHostedOnTheHomeScreen() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val info=instrumentation.uiAutomation.serviceInfo
        info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo=info
        val app=instrumentation.targetContext.applicationContext as FieldNotesApplication
        runBlocking { app.initialized.await() }
        org.junit.Assume.assumeTrue("Use capture-home-widget.mjs on the disposable AVD",InstrumentationRegistry.getArguments().getString("fieldNotesHomeGallery")=="synthetic-only")
        val originalCache=app.model.state.value.cache
        val now=Clock.System.now().epochSeconds
        val record=Record(UUID.randomUUID().toString(),"event",1,obj("title" to "Household check-in".j(),"starts_at" to (now+3600).j(),"ends_at" to (now+7200).j()))
        val today=Clock.System.now().toString().take(10)
        val planned=Record("planned-task","task",1,obj("title" to "Plan weekend meals".j(),"status" to "todo".j(),"planned_for" to today.j()))
        val parcel=Record("parcel-task","task",1,obj("title" to "Collect parcel".j(),"status" to "todo".j(),"due" to today.j()))
        val snapshot=Snapshot(1,"synthetic-home-widget",1,1,listOf(record,planned,parcel),Actor("owner","Owner","owner","session"))
        val seed=Cache(keys=originalCache.keys,config=obj("server_key" to "synthetic-widget-server".j()),token="synthetic-widget-session",snapshot=snapshot,
            preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false,widgetTitles=true))
        val transport=object:Transport {
            override suspend fun keys()=originalCache.keys!!
            override suspend fun configure(configuration:JsonObject){}
            override suspend fun stop(){}
            override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?)=when(path){
                "/api/head"->Reply(200,obj("epoch" to snapshot.epoch.j(),"generation" to 1.j(),"sequence" to 1.j()).toString())
                "/api/people"->Reply(200,"""{"people":[]}""")
                "/api/snapshot"->Reply(200,WireJson.encodeToJsonElement(snapshot).toString())
                else->error("Unexpected synthetic widget request")
            }
        }
        runBlocking { app.vault.save(seed) }
        val model=Workspace(transport,app.vault,{UUID.randomUUID().toString()},app.scope)
        runBlocking { model.initialize() };app.model=model
        fun nodes(text:String)=instrumentation.uiAutomation.windows.flatMap { it.root?.findAccessibilityNodeInfosByText(text).orEmpty() }
        fun waitFor(timeout:Long=90_000,condition:()->Boolean){val end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(250)};error("Launcher widget did not reach the expected state.")}
        // The dedicated script captures and clears this synthetic fixture after
        // instrumentation exits. Do not replace the launcher's real host with
        // an in-activity RemoteViews preview.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue("Launcher must support standard widget pinning",UpcomingWidget.pin(it)) }
            waitFor { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.launcher3" && nodes("Add").any { it.isClickable||it.parent?.isClickable==true } }
            val choices=nodes("Add")
            val button=choices.firstOrNull { it.className?.toString()?.contains("Button")==true }
                ?:choices.first { it.isClickable||it.parent?.isClickable==true }
            assertTrue((if(button.isClickable)button else button.parent).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val manager=app.getSystemService(AppWidgetManager::class.java)
            waitFor { manager.getAppWidgetIds(ComponentName(app,UpcomingWidget::class.java)).isNotEmpty() }
            UpcomingWidget.refresh(app)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        }
    }
}
