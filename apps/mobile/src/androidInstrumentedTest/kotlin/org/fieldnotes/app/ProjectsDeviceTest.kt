package org.fieldnotes.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real native navigation, shared recipient controls and Keystore-backed cache.
 * Records are seeded by a real isolated Rust server; network replies here are
 * a deterministic test transport. The guard excludes all user phones. */
class ProjectsDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun projectContextRecipientsAndGroceryEditorsStayConsistent() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fieldNotesProjectsGallery")=="synthetic-only")
        val avd=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).bufferedReader().use{it.readText().trim()}
        assertEquals("field-notes-test",avd)
        val app=instrumentation.targetContext.applicationContext as FieldNotesApplication
        runBlocking{app.initialized.await()}
        val fixture=WireJson.parseToJsonElement(File(app.filesDir,"projects-fixture.json").readText()).jsonObject
        val snapshot=WireJson.decodeFromJsonElement<Snapshot>(fixture.getValue("snapshot"));val original=app.model
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val transport=ProjectsFixtureTransport(fixture,snapshot)
        val cache=Cache(keys=original.state.value.cache.keys,config=obj("server_key" to "synthetic-project-peer".j()),token="synthetic-project-session",snapshot=snapshot,
            people=WireJson.decodeFromJsonElement(fixture.getValue("people")),
            preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false,autoRefresh=false,sharedAdditions=false,theme="dark",autoDownloadUpdates=false),
            compatibility=ServerCompatibility.read(fixture.getValue("head").jsonObject,Clock.System.now().epochSeconds))
        val model=Workspace(transport,app.vault,{UUID.randomUUID().toString()},scope)
        runBlocking{app.vault.save(cache);model.initialize();model.poll()};app.model=model
        fun waitText(text:String)=compose.waitUntil(90_000){compose.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}
        fun shot(name:String){
            compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(1200)
            val image=instrumentation.uiAutomation.takeScreenshot();val directory=File(app.filesDir,"projects-gallery");directory.mkdirs()
            File(directory,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }
        try{ActivityScenario.launch(MainActivity::class.java).use{activity->
            activity.onActivity{it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            try{
                compose.waitUntil(90_000){compose.onAllNodesWithContentDescription("Browse workspace").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("Browse workspace").performClick()
                compose.onNodeWithTag("drawer-projects").performScrollTo().performClick()
                waitText("Bathroom repairs");compose.onNodeWithText("Bathroom repairs",useUnmergedTree=true).performClick()
                waitText("1 of 5 tasks complete");shot("87-native-project-workspace")
                compose.onNodeWithTag("project-workspace").performScrollToNode(hasText("Search project tasks and notes"))
                compose.onNodeWithText("Search project tasks and notes").performTextInput("replacement")
                compose.onNodeWithTag("project-workspace").performScrollToNode(hasText("Choose replacement fittings"))
                compose.onNodeWithText("Choose replacement fittings").performClick();waitText("Task details")
                compose.onNodeWithText("Open project · Bathroom repairs").performScrollTo().performClick()
                compose.onNodeWithTag("project-workspace").performScrollToNode(hasText("Search project tasks and notes"))
                compose.onNode(hasText("replacement") and hasSetTextAction()).assertExists()
                compose.onNodeWithText("Search project tasks and notes").performTextClearance()
                compose.onNodeWithTag("project-workspace").performScrollToNode(hasText("Choose replacement fittings"))
                compose.onNodeWithText("Choose replacement fittings").performClick();waitText("Task details")
                compose.onNodeWithText("Reminders & sound").performScrollTo().performClick()
                compose.onNode(hasText("Reminders ·",substring=true) and hasClickAction()).performClick()
                compose.onNodeWithText("Sophia",substring=false).performScrollTo().performClick()
                compose.onNodeWithText("Alex",substring=false).performClick()
                compose.onNodeWithText("Sophia",substring=false).performClick()
                compose.onNodeWithText("Sophia",substring=false).performClick()
                shot("88-native-reminder-recipients")
                compose.onNodeWithText("Save reminders").performClick()
                compose.waitUntil(90_000){transport.sent.isNotEmpty()&&model.state.value.cache.outbox.isEmpty()}
                val saved=SyncJson.decodeFromJsonElement<ReminderOptions>(transport.sent.last().data.getValue("reminder_options"))
                assertEquals(setOf("Alex","Sophia"),cache.people.filter{it.id in saved.audience.people}.map{it.name}.toSet())
                assertEquals("people",saved.audience.mode)
                assertEquals(saved,reminderOptions(runBlocking{app.vault.load()}.snapshot!!.records.first{it.id==transport.sent.last().target}))
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithTag("nav-lists").performClick();waitText("Costco Shopping List")
                compose.onNodeWithText("Costco Shopping List",useUnmergedTree=true).performClick();waitText("Bananas")
                compose.onNodeWithText("Bananas").performClick();waitText("Item name")
                compose.onAllNodes(hasText("Reminders",substring=true)).assertCountEquals(0)
                compose.onAllNodesWithText("Who should receive these reminders?").assertCountEquals(0)
                shot("89-native-grocery-item")
            }catch(failure:Throwable){runCatching{shot("failure")};throw failure}
        }}finally{app.model=original;scope.cancel()}
    }
}
private class ProjectsFixtureTransport(private val fixture:JsonObject,private var snapshot:Snapshot):Transport {
    val sent=java.util.concurrent.CopyOnWriteArrayList<Command>()
    override suspend fun keys()=error("No new identity")
    override suspend fun configure(configuration:JsonObject){}
    override suspend fun stop(){}
    override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?):Reply {
        val value=when(path){
            "/api/head"->JsonObject(fixture.getValue("head").jsonObject+("sequence" to snapshot.sequence.j()))
            "/api/snapshot"->WireJson.encodeToJsonElement(snapshot)
            "/api/people"->obj("people" to fixture.getValue("people"))
            "/api/device/update"->fixture["apk_update"] ?: obj("available" to JsonPrimitive(false))
            "/api/commands"->{
                val command=WireJson.decodeFromJsonElement<Command>(checkNotNull(body));sent+=command
                val old=snapshot.records.first{it.id==command.target};check(old.revision==command.expected_revision)
                val data=JsonObject(old.data+command.data+(command.data["reminders"]?.let{mapOf("reminder_rules" to it)} ?: emptyMap()))
                val updated=old.copy(revision=old.revision+1,data=data)
                snapshot=snapshot.copy(sequence=snapshot.sequence+1,records=snapshot.records.map{if(it.id==old.id)updated else it})
                obj("sequence" to snapshot.sequence.j(),"record" to WireJson.encodeToJsonElement(updated))
            }
            else->error("Unexpected synthetic project route: $path")
        }
        return Reply(200,value.toString())
    }
}
