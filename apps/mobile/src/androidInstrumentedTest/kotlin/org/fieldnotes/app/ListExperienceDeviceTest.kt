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
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Activity lifecycle/foreground loop, native controls and encrypted vault.
 * Source snapshots/deletion results come from an isolated real Rust household. */
class ListExperienceDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun shoppingListReceivesLiveAmendmentsAndSupportsIndividualAndScopedBulkDeletion() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fieldNotesListGallery")=="synthetic-only")
        val avd=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).bufferedReader().use{it.readText().trim()}
        assertEquals("field-notes-test",avd)
        val app=instrumentation.targetContext.applicationContext as FieldNotesApplication;runBlocking{app.initialized.await()}
        val seed=WireJson.parseToJsonElement(File(app.filesDir,"list-experience-fixture.json").readText()).jsonObject
        val original=app.model;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val transport=ListExperienceTransport(seed)
        val initial=transport.snapshot
        val cache=Cache(keys=original.state.value.cache.keys,token="synthetic-list-session",config=obj("server_key" to "synthetic-list-peer".j()),snapshot=initial,
            people=WireJson.decodeFromJsonElement(seed.getValue("people")),directoryRevision=seed.getValue("head").jsonObject.number("directory_revision"),
            preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false,autoRefresh=false,sharedAdditions=false,autoDownloadUpdates=false,theme="dark"),
            compatibility=ServerCompatibility.read(seed.getValue("head").jsonObject,Clock.System.now().epochSeconds))
        val model=Workspace(transport,app.vault,{UUID.randomUUID().toString()},scope)
        runBlocking{app.vault.save(cache);model.initialize()};app.model=model
        fun waitText(text:String)=compose.waitUntil(90_000){compose.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}
        fun shot(name:String){compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(1000);val bitmap=instrumentation.uiAutomation.takeScreenshot();val dir=File(app.filesDir,"list-gallery");dir.mkdirs();File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        try{ActivityScenario.launch(MainActivity::class.java).use{activity->
            activity.onActivity{it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            try{
                compose.waitUntil(90_000){compose.onAllNodesWithTag("nav-lists").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("nav-lists").performClick();waitText("Costco Shopping List")
                compose.onNodeWithText("Costco Shopping List",useUnmergedTree=true).performClick();waitText("Bananas")
                val before=transport.heads
                transport.publishAmendments()
                // Do not press refresh: the legacy Auto refresh=false and
                // background service=false preferences must not stop live UI sync.
                compose.waitUntil(90_000){model.state.value.cache.snapshot!!.records.any{it.data.text("title")=="Bread"}&&transport.heads>before}
                assertEquals("8",model.state.value.cache.snapshot!!.records.first{it.id==seed.text("bananas")}.data.text("quantity"))
                compose.onNodeWithTag("checklist-items").performScrollToNode(hasText("Bread"))
                compose.onNodeWithText("Bread").assertIsDisplayed();shot("95-native-live-groceries")
                compose.onNodeWithText("Bread").performClick();waitText("Item name")
                compose.onNodeWithText("Delete item").performClick();waitText("Delete Bread?");shot("94-native-delete-grocery")
                transport.offline=true
                compose.onNode(hasText("Delete item") and hasClickAction() and hasAnyAncestor(hasAnyDescendant(hasText("Delete Bread?")))).performClick()
                compose.waitUntil(90_000){model.state.value.cache.outbox.any{it.command.target==seed.text("bread")}&&!model.state.value.online}
                assertEquals(cache.keys,runBlocking{app.vault.load()}.keys)
                assertTrue(transport.snapshot.records.any{it.id==seed.text("bread")})
                shot("96-native-queued-removal")
                transport.offline=false
                compose.waitUntil(90_000){model.state.value.cache.outbox.isEmpty()&&model.state.value.online}
                assertFalse(transport.snapshot.records.any{it.id==seed.text("bread")})
                compose.onNodeWithText("List options ▾").performClick()
                compose.onNode(hasText("Store: All stores",substring=true) and hasClickAction()).performClick()
                compose.onNode(hasText("Costco") and hasClickAction()).performClick()
                compose.onNodeWithText("List options ▴").performClick()
                compose.onNodeWithText("Delete checked (1)").assertIsDisplayed();shot("93-native-list-cleanup")
                compose.onNodeWithText("Delete checked (1)").performClick();waitText("Delete 1 checked item?")
                compose.onNodeWithText("Delete checked",substring=false).performClick()
                compose.waitUntil(90_000){model.state.value.cache.outbox.isEmpty()&&transport.sent.size==2}
                assertFalse(transport.snapshot.records.any{it.id==seed.text("apples")})
                assertTrue(transport.snapshot.records.any{it.id==seed.text("milk")})
                assertTrue(transport.snapshot.records.any{it.id==seed.text("bananas")})
                assertEquals(cache.keys,runBlocking{app.vault.load()}.keys)
            }catch(e:Throwable){runCatching{shot("failure")};throw e}
        }}finally{app.model=original;scope.cancel()}
    }
}
private class ListExperienceTransport(private val seed:JsonObject):Transport {
    @Volatile var snapshot=WireJson.decodeFromJsonElement<Snapshot>(seed.getValue("initial"))
    @Volatile var offline=false
    @Volatile var heads=0
    val sent=java.util.concurrent.CopyOnWriteArrayList<Command>()
    private val receipts=mutableMapOf<String,JsonObject>()
    fun publishAmendments(){snapshot=WireJson.decodeFromJsonElement(seed.getValue("amended"))}
    override suspend fun keys()=error("No identity replacement")
    override suspend fun configure(configuration:JsonObject){}
    override suspend fun stop(){}
    override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?):Reply {
        if(offline)error("The server cannot be reached. Saved work is retained.")
        val result=when(path){
            "/api/head"->{heads++;JsonObject(seed.getValue("head").jsonObject+obj("epoch" to snapshot.epoch.j(),"generation" to snapshot.generation.j(),"sequence" to snapshot.sequence.j(),"history_revision" to snapshot.history_revision.j()))}
            "/api/snapshot"->WireJson.encodeToJsonElement(snapshot)
            "/api/people"->obj("people" to seed.getValue("people"),"revision" to seed.getValue("head").jsonObject.number("directory_revision").j())
            "/api/device/update"->seed.getValue("apk_update")
            "/api/commands"->{
                val command=WireJson.decodeFromJsonElement<Command>(checkNotNull(body));receipts[command.operation_id]?.let{return Reply(200,it.toString())}
                check(command.action=="task.delete")
                val old=snapshot.records.first{it.id==command.target};check(old.revision==command.expected_revision)
                val name=if(command.target==seed.text("bread"))"bread" else {check(command.target==seed.text("apples"));"apples"}
                snapshot=WireJson.decodeFromJsonElement(seed.getValue("after_"+name));val reply=seed.getValue("deleted_"+name).jsonObject
                sent+=command;receipts[command.operation_id]=reply;reply
            }
            else->error("Unexpected synthetic list route: $path")
        }
        return Reply(200,result.toString())
    }
}
