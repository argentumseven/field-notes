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

/** UI/queue/vault checks on synthetic server-created records. Only the dedicated
 * AVD may run this; no household server or physical phone is accessed. */
class ItemContextDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun itemCategoriesFollowTheListAndCustomListsRemainUnrestricted() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fieldNotesContextGallery")=="synthetic-only")
        val avd=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).bufferedReader().use{it.readText().trim()}
        assertEquals("field-notes-test",avd)
        val app=instrumentation.targetContext.applicationContext as FieldNotesApplication;runBlocking{app.initialized.await()}
        val seed=WireJson.parseToJsonElement(File(app.filesDir,"item-context-fixture.json").readText()).jsonObject
        val original=app.model;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val initial=WireJson.decodeFromJsonElement<Snapshot>(seed.getValue("snapshot"));val transport=ContextFixtureTransport(seed,initial)
        val cache=Cache(keys=original.state.value.cache.keys,config=obj("server_key" to "synthetic-context-peer".j()),token="synthetic-context-session",snapshot=initial,
            people=WireJson.decodeFromJsonElement(seed.getValue("people")),directoryRevision=seed.getValue("head").jsonObject.number("directory_revision"),
            preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false,sharedAdditions=false,autoDownloadUpdates=false,theme="dark"),
            compatibility=ServerCompatibility.read(seed.getValue("head").jsonObject,Clock.System.now().epochSeconds))
        val model=Workspace(transport,app.vault,{UUID.randomUUID().toString()},scope)
        runBlocking{app.vault.save(cache);model.initialize()};app.model=model
        fun waitText(text:String)=compose.waitUntil(90_000){compose.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}
        fun shot(name:String){compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(1000);val bitmap=instrumentation.uiAutomation.takeScreenshot();val dir=File(app.filesDir,"context-gallery");dir.mkdirs();File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        fun open(list:String,item:String,task:Boolean=false){
            compose.onNodeWithTag("nav-lists").performClick();waitText("Search names and descriptions")
            compose.onNodeWithText("Search names and descriptions").performTextClearance()
            compose.onNodeWithText("Search names and descriptions").performTextInput(list)
            compose.onNodeWithText("Search names and descriptions").performImeAction()
            compose.onNode(hasText(list) and hasSetTextAction().not(),useUnmergedTree=true).performScrollTo().performClick();waitText(item)
            compose.onNodeWithTag("checklist-items").performScrollToNode(hasText(item))
            compose.onNodeWithText(item).performClick();waitText(if(task)"Task details" else "Item name")
        }
        fun save(id:String,value:String,task:Boolean=false){
            if(task)compose.onNodeWithTag("task-details-content").performScrollToNode(hasText("Save details"))
            compose.onNodeWithText(if(task)"Save details" else "Save item").performClick()
            compose.waitUntil(90_000){model.state.value.cache.outbox.isEmpty()&&model.state.value.cache.snapshot!!.records.first{it.id==id}.data.text("item_category")==value}
            val saved=runBlocking{app.vault.load()}
            assertEquals(value,saved.snapshot!!.records.first{it.id==id}.data.text("item_category"))
            assertEquals(cache.keys,saved.keys);assertEquals(cache.config,saved.config)
            if(task)compose.onNodeWithContentDescription("Back").performClick()
        }
        try{ActivityScenario.launch(MainActivity::class.java).use{activity->
            activity.onActivity{it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            try{
                compose.waitUntil(90_000){compose.onAllNodesWithTag("nav-lists").fetchSemanticsNodes().isNotEmpty()}
                open("Costco Shopping List","Bananas")
                compose.onAllNodes(hasText("Category:",substring=true) and hasClickAction()).assertCountEquals(0)
                compose.onNodeWithText("Grocery category:",substring=true).performClick()
                compose.onNodeWithText("Produce",substring=false).assertIsDisplayed()
                compose.onAllNodesWithText("Work",substring=false).assertCountEquals(0)
                compose.onAllNodesWithText("Action",substring=false).assertCountEquals(0)
                shot("100-native-grocery-category")
                compose.onNodeWithText("Produce",substring=false).performClick();save(seed.text("grocery_item"),"Produce")
                assertEquals("work",model.state.value.cache.snapshot!!.records.first{it.id==seed.text("grocery_item")}.data.text("category"))
                open("Household work","Repair the chair",true)
                compose.onNodeWithTag("task-details-content").performScrollToNode(hasText("Household category:",substring=true))
                compose.onNodeWithText("Household category:",substring=true).performClick()
                compose.onNodeWithText("Repairs",substring=false).assertIsDisplayed()
                compose.onAllNodesWithText("Produce",substring=false).assertCountEquals(0)
                shot("101-native-household-category")
                compose.onNodeWithText("Repairs",substring=false).performClick();save(seed.text("home_item"),"Repairs",true)
                open("Anything list","Unrestricted example")
                compose.onNodeWithText("Item category · optional").performTextInput("Someday / custom idea")
                compose.onNodeWithText("Item category · optional").performImeAction()
                shot("102-native-unrestricted-category");save(seed.text("custom_item"),"Someday / custom idea")
            }catch(failure:Throwable){runCatching{shot("failure")};throw failure}
        }}finally{app.model=original;scope.cancel()}
    }
}

internal class ContextFixtureTransport(private val seed:JsonObject,private var snapshot:Snapshot):Transport {
    private val receipts=mutableMapOf<String,JsonObject>()
    override suspend fun keys()=error("No new identity")
    override suspend fun configure(configuration:JsonObject){}
    override suspend fun stop(){}
    override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?):Reply {
        val value=when(path){
            "/api/head"->JsonObject(seed.getValue("head").jsonObject+("sequence" to snapshot.sequence.j())+("server_time_ms" to Clock.System.now().toEpochMilliseconds().j()))
            "/api/snapshot"->WireJson.encodeToJsonElement(snapshot)
            "/api/people"->obj("people" to seed.getValue("people"),"revision" to seed.getValue("head").jsonObject.getValue("directory_revision"))
            "/api/device/update"->seed.getValue("apk_update")
            "/api/commands"->{
                val command=WireJson.decodeFromJsonElement<Command>(checkNotNull(body));check(command.action in setOf("task.update","event.update","note.update","note.create"))
                receipts[command.operation_id]?:run{
                    val old=snapshot.records.firstOrNull{it.id==command.target} ?: Record(command.target,command.action.substringBefore("."),0,obj());check(if(command.action.endsWith(".create"))command.expected_revision==null else old.revision==command.expected_revision)
                    val clean=if(command.data.containsKey("reminders"))JsonObject(old.data.filterKeys{it !in setOf("reminder_rules","reminder_timing_rules","reminder_schedule_rules")})else old.data
                    val updated=old.copy(revision=old.revision+1,data=JsonObject(clean+command.data))
                    snapshot=snapshot.copy(sequence=snapshot.sequence+1,records=if(snapshot.records.none{it.id==old.id})snapshot.records+updated else snapshot.records.map{if(it.id==old.id)updated else it})
                    obj("sequence" to snapshot.sequence.j(),"record" to WireJson.encodeToJsonElement(updated)).also{receipts[command.operation_id]=it}
                }
            }
            else->error("Unexpected synthetic context route: $path")
        }
        return Reply(200,value.toString())
    }
}
