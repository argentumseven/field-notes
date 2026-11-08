package org.fieldnotes.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlinx.serialization.json.decodeFromJsonElement
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic-only, real Compose editor and actual launcher AppWidgetHost. */
class CalendarExperienceDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun calendarFirstLocalTimeAndDynamicLauncherMonth() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val info=instrumentation.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo=info
        assumeTrue("Dedicated disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val app=instrumentation.targetContext.applicationContext as FieldNotesApplication
        runBlocking { app.initialized.await() }
        val zone=TimeZone.currentSystemDefault();val today=Clock.System.now().toLocalDateTime(zone).date
        val start=scheduledInstant(today.toString(),"10:00",zone)
        val event=Record("11111111-1111-4111-8111-111111111112","event",1,obj("title" to "Household check-in".j(),"author_id" to "owner".j(),"created_by_name" to "Owner".j(),"updated_by_name" to "Owner".j(),"created_at" to (Clock.System.now().epochSeconds-86400).j(),"updated_at" to Clock.System.now().epochSeconds.j(),"all_day" to kotlinx.serialization.json.JsonPrimitive(false),"starts_at" to start.j(),"ends_at" to (start+3600).j()))
        val snapshot=Snapshot(1,"calendar-fixture",1,1,listOf(event),Actor("owner","Owner","owner","session"))
        val cache=Cache(keys=app.model.state.value.cache.keys,config=obj("server_key" to "synthetic-calendar".j()),token="synthetic-session",snapshot=snapshot,
            preferences=Preferences(connectionPaused=true,backgroundSync=false,reminders=false,liveUpdates=false,widgetTitles=true),
            people=listOf(HouseholdPerson("owner","Morgan",true),HouseholdPerson("sam","Sam",true)),compatibility=ServerCompatibility("0.6.0",features=setOf(REMINDER_OPTIONS_FEATURE,PLANNER_FEATURE),checkedAt=Clock.System.now().epochSeconds))
        runBlocking { app.vault.save(cache);app.model.initialize() }
        app.deliveryError.value=null
        fun waitFor(condition:()->Boolean) { val until=System.currentTimeMillis()+90_000;while(System.currentTimeMillis()<until){if(condition())return;Thread.sleep(250)};error("Calendar fixture did not reach expected state; active="+instrumentation.uiAutomation.rootInActiveWindow?.packageName+"; windows="+instrumentation.uiAutomation.windows.map { it.root?.packageName }) }
        fun nodes(text:String)=instrumentation.uiAutomation.windows.flatMap { it.root?.findAccessibilityNodeInfosByText(text).orEmpty() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var originalIntent:android.content.Intent
            scenario.onActivity { originalIntent=it.intent }
            scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            fun shot(name:String) {
                compose.waitForIdle();Thread.sleep(1500)
                val done=CountDownLatch(1);var result=-1;lateinit var bitmap:Bitmap
                scenario.onActivity { activity ->
                    fun dialogWindow(view:View):android.view.Window? {
                        if(view is DialogWindowProvider)return view.window
                        if(view is ViewGroup)for(i in 0 until view.childCount)dialogWindow(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val focused=WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
                    val window=dialogWindow(focused) ?: activity.window
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
                    PixelCopy.request(window,bitmap,{result=it;done.countDown()},Handler(Looper.getMainLooper()))
                }
                check(done.await(30,TimeUnit.SECONDS)&&result==PixelCopy.SUCCESS)
                File(app.filesDir,"calendar-gallery").mkdirs()
                File(app.filesDir,"calendar-gallery/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
            compose.waitUntil(90_000) { compose.onAllNodesWithText("Household check-in").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nav-calendar").assertIsSelected()
            compose.onNodeWithText("10:00 AM – 11:00 AM").assertIsDisplayed()
            shot("30-calendar-home")
            compose.onNodeWithText("Week",useUnmergedTree=true).performClick();shot("50-sunday-week")
            compose.onNodeWithText("Month",useUnmergedTree=true).performClick();shot("51-sunday-month")
            compose.onNodeWithText("Day",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Household check-in").performClick()
            compose.onNodeWithText("10:00 AM").assertIsDisplayed()
            shot("31-local-event")
            compose.onNode(hasText("Repeat: Does not repeat",substring=true)).performScrollTo().performClick()
            compose.onNodeWithText("Every other day",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Repeating event").performScrollTo();shot("52-repeat-event")
            compose.onNode(hasText("Repeat: Every other day",substring=true)).performScrollTo().performClick()
            compose.onNodeWithText("Does not repeat",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Reminders",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Only me").performScrollTo().performClick()
            compose.onNode(isToggleable() and hasAnySibling(hasText("Repeat during a time window"))).performScrollTo().performClick()
            compose.onNodeWithText("Every week").performScrollTo().performClick()
            compose.onNodeWithText("Every day").performScrollTo().performClick()
            shot("40-reminder-rules")
            compose.onNodeWithText("Sound",useUnmergedTree=true).performScrollTo().performClick()
            compose.onNodeWithText("Urgent").performClick();compose.onNodeWithText("Bell",useUnmergedTree=true).performClick();compose.onNodeWithText("100%").performClick()
            shot("41-reminder-sound")
            compose.onNodeWithText("Sunrise",useUnmergedTree=true).performScrollTo().performClick()
            compose.onNodeWithText("Alarm",useUnmergedTree=true).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Temporarily raise alarm volume").performScrollTo().performClick()
            compose.onNodeWithText("30 s").performScrollTo().performClick()
            compose.onNodeWithText("Temporarily raise alarm volume").performScrollTo()
            shot("44-long-alarm-controls")
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Schedule",useUnmergedTree=true).performClick()
            compose.onAllNodesWithText("Choose time").onFirst().performScrollTo().performClick()
            shot("33-time-picker")
            compose.onNodeWithText("Type a time").performClick()
            compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("time-input"))).onFirst().performTextReplacement("10")
            compose.onNodeWithText("Set time").performClick()
            compose.onNodeWithText("10:00 AM").assertIsDisplayed()
            compose.onNodeWithText("Save event").performClick()
            compose.waitUntil(30_000) { app.model.state.value.cache.outbox.isNotEmpty() }
            assertEquals(start,app.model.state.value.cache.outbox.last().command.data.number("starts_at"))
            assertEquals("Household check-in",app.model.state.value.cache.outbox.last().command.data.text("title"))
            val saved=app.model.state.value.cache.outbox.last().command.data.getValue("reminder_options")
            val options=SyncJson.decodeFromJsonElement<ReminderOptions>(saved)
            assertEquals(listOf("owner"),options.audience.people);assertTrue(options.playback.values.any{it.tone=="sunrise"&&it.duration_seconds==30&&it.alarm&&it.unmute})
            // Real provider rendering must redact both titles and schedule markers.
            val privateView=CalendarWidget.views(app,cache,false).apply(app,android.widget.FrameLayout(app))
            assertFalse(privateView.findViewById<android.widget.TextView>(R.id.calendar_agenda).text.contains("Household check-in"))
            assertTrue(privateView.findViewById<android.widget.TextView>(R.id.calendar_refresh).text.toString().contains("Unlock phone"))
            val markerCount=(privateView.findViewById<android.widget.LinearLayout>(R.id.calendar_weeks)).let { weeks ->
                (0 until weeks.childCount).sumOf { w -> val row=weeks.getChildAt(w) as android.widget.LinearLayout;(0 until row.childCount).count { d -> row.getChildAt(d).findViewById<android.widget.TextView>(R.id.calendar_marker).text.isNotBlank() } }
            }
            assertEquals(0,markerCount)
            compose.onNodeWithContentDescription("Workspace menu").performClick()
            compose.onNodeWithText("Settings & widgets").performClick()
            compose.onNodeWithText("Detailed reminder notifications").performScrollTo()
            shot("45-independent-visibility")
            compose.onNodeWithText("Require a credential to open the app").assertExists()
            scenario.onActivity { it.intent=originalIntent } // Preserve ActivityScenario lifecycle ownership.
        }
    }
}
