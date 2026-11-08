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

class NativePlannerPolishDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun allDayReminderAndRecipeEditsPersistAndKeyboardCanClose() {
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
            preferences=Preferences(connectionPaused=true,backgroundSync=false,reminders=false,liveUpdates=false,sharedAdditions=false,autoDownloadUpdates=false,theme="dark"),
            compatibility=ServerCompatibility.read(seed.getValue("head").jsonObject,Clock.System.now().epochSeconds))
        val model=Workspace(transport,app.vault,{UUID.randomUUID().toString()},scope)
        runBlocking{app.vault.save(cache);model.initialize()};app.model=model
        fun flushQueued(){
            runBlocking{model.resumeConnection()};model.requestSync()
            // sync() deliberately returns when another sync owns the mutex.
            // Wait for the UI's queued sync before intentionally pausing again.
            compose.waitUntil(90_000){model.state.value.cache.outbox.isEmpty()&&!model.state.value.syncing}
            check(model.state.value.cache.conflicts.isEmpty()){model.state.value.cache.conflicts.joinToString{it.reason}}
            runBlocking{model.pauseConnection()}
        }
        fun waitText(text:String)=compose.waitUntil(90_000){compose.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}
        fun shot(name:String){compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(1000);val bitmap=instrumentation.uiAutomation.takeScreenshot();val dir=File(app.filesDir,"context-gallery");dir.mkdirs();File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        try{ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).putExtra("open_record",seed.text("planner_event")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{activity->
            activity.onActivity{it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            try{
                waitText("Schedule")
                compose.onNodeWithText("Reminders",useUnmergedTree=true).performClick()
                compose.onNodeWithText("Time",useUnmergedTree=true).performScrollTo().performClick()
                waitText("Day & time")
                compose.onNodeWithText("Time unit:",substring=true).performClick()
                compose.onNodeWithText("Weeks",useUnmergedTree=true).performClick()
                compose.onNode(hasText("1") and isSelectable()).performClick()
                shot("111-native-all-day-reminder")
                compose.onNodeWithText("Save time").performClick()
                compose.onNodeWithText("Sound",useUnmergedTree=true).performScrollTo().performClick()
                compose.onNodeWithText("Alarm",useUnmergedTree=true).performScrollTo().performClick()
                compose.onNode(hasText("5 s") and isSelectable()).assertIsSelected()
                shot("112-native-five-second-alarm")
                compose.onNodeWithText("Done",useUnmergedTree=true).performClick()
                compose.onNodeWithText("Save event").performClick()
                compose.waitUntil(90_000){model.state.value.cache.outbox.any{it.command.action=="event.update"}}
                val queued=projectRecords(model.state.value.cache).first{it.record.id==seed.text("planner_event")}.record
                assertEquals(7,reminderSet(queued)!!.alerts.single().day_time!!.days_before)
                flushQueued()
                assertEquals(7,reminderSet(runBlocking{app.vault.load()}.snapshot!!.records.first{it.id==seed.text("planner_event")})!!.alerts.single().day_time!!.days_before)
                compose.onNodeWithContentDescription("Browse workspace").performClick()
                compose.onNodeWithText("Recipes",useUnmergedTree=true).performClick()
                compose.onNodeWithContentDescription("Add",useUnmergedTree=true).performClick()
                waitText("Recipe name")
                compose.onNodeWithText("Recipe name").performTextInput("Lentil soup")
                compose.onNodeWithText("Ingredients").performScrollTo().performTextInput("Lentils, carrots, cumin")
                compose.onNodeWithText("Cooking instructions").performScrollTo().performClick().performTextInput("Simmer gently for 25 minutes.")
                compose.waitUntil(60_000){compose.onAllNodesWithText("Done typing",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
                shot("113-native-recipe-keyboard")
                compose.onNodeWithText("Done typing",useUnmergedTree=true).performClick()
                compose.waitUntil(30_000){compose.onAllNodesWithText("Done typing",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
                shot("115-native-recipe-editor")
                compose.onNodeWithText("Create recipe").performClick()
                compose.waitUntil(90_000){model.state.value.cache.outbox.any{it.command.action=="note.create"}}
                assertEquals("Simmer gently for 25 minutes.",checkNotNull(projectRecords(model.state.value.cache).firstOrNull{it.record.isRecipe()}){"Recipe missing from queued projection"}.record.data.text("instructions"))
                flushQueued()
                val saved=runBlocking{app.vault.load()};assertEquals("Lentil soup",checkNotNull(saved.snapshot!!.records.firstOrNull{it.isRecipe()}){"Recipe missing after acknowledged sync"}.data.text("title"))
                assertEquals(cache.keys,saved.keys);assertEquals(cache.config,saved.config)
            }catch(failure:Throwable){runCatching{shot("failure")};throw failure}
        }}finally{app.model=original;scope.cancel()}
    }
}
