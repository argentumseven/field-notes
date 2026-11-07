package org.fieldnotes.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ReminderTimingDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun monthAndYearControlsSaveDistinctRemindersIntoTheVault() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fieldNotesContextGallery")=="synthetic-only")
        val avd=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).bufferedReader().use{it.readText().trim()}
        assertEquals("field-notes-test",avd)
        val app=instrumentation.targetContext.applicationContext as FieldNotesApplication;runBlocking{app.initialized.await()}
        val seed=WireJson.parseToJsonElement(File(app.filesDir,"item-context-fixture.json").readText()).jsonObject
        val original=app.model;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val snapshot=WireJson.decodeFromJsonElement<Snapshot>(seed.getValue("snapshot"));val transport=ContextFixtureTransport(seed,snapshot)
        val cache=Cache(keys=original.state.value.cache.keys,config=obj("server_key" to "synthetic-timing-peer".j()),token="synthetic-timing-session",snapshot=snapshot,
            people=WireJson.decodeFromJsonElement(seed.getValue("people")),directoryRevision=seed.getValue("head").jsonObject.number("directory_revision"),
            preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false,sharedAdditions=false,autoDownloadUpdates=false,theme="dark"),
            compatibility=ServerCompatibility.read(seed.getValue("head").jsonObject,Clock.System.now().epochSeconds))
        val model=Workspace(transport,app.vault,{UUID.randomUUID().toString()},scope)
        runBlocking{app.vault.save(cache);model.initialize()};app.model=model
        fun waitText(text:String)=compose.waitUntil(90_000){compose.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}
        fun shot(name:String){compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(1000);val bitmap=instrumentation.uiAutomation.takeScreenshot();val dir=File(app.filesDir,"context-gallery");dir.mkdirs();File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        try{ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).putExtra("open_record",seed.text("home_item")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{activity->
            activity.onActivity{it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            try{
                waitText("Task details")
                compose.onNodeWithTag("task-details-content").performScrollToNode(hasText("Reminders & sound"))
                compose.onNodeWithText("Reminders & sound").performClick()
                compose.onNode(hasText("Reminders ·",substring=true) and hasClickAction()).performClick()
                for((index,unit)in listOf("Months","Years").withIndex()){
                    compose.onNodeWithText("Add reminder").performScrollTo().performClick()
                    compose.onNodeWithText("Custom time…").performClick();waitText("Amount before")
                    compose.onNodeWithText("Time unit:",substring=true).performClick()
                    compose.onNodeWithText(unit,substring=false).performClick()
                    compose.onNodeWithText("Amount before").performTextClearance()
                    compose.onNodeWithText("Amount before").performTextInput("1")
                    compose.onNodeWithText("Amount before").performImeAction()
                    if(index==1)shot("103-native-reminder-time-units")
                    compose.onNodeWithText("Save time").performClick()
                }
                waitText("1 month before");waitText("1 year before");shot("104-native-month-year-reminders")
                compose.onNodeWithText("Save reminders").performClick()
                compose.waitUntil(90_000){model.state.value.cache.outbox.isEmpty()&&reminderSet(model.state.value.cache.snapshot!!.records.first{it.id==seed.text("home_item")})?.alerts?.size==2}
                val saved=runBlocking{app.vault.load()};val rules=reminderSet(saved.snapshot!!.records.first{it.id==seed.text("home_item")})!!
                assertEquals(listOf("months","years"),rules.alerts.map{it.lead_time!!.unit})
                assertEquals(2,rules.alerts.map{it.id}.distinct().size);assertEquals(1,rules.timing_version)
                assertEquals(cache.keys,saved.keys);assertEquals(cache.config,saved.config)
            }catch(failure:Throwable){runCatching{shot("failure")};throw failure}
        }}finally{app.model=original;scope.cancel()}
    }
}
