package org.fieldnotes.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.datetime.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual native UI + Keystore cache; the seed comes from a real synthetic
 * Rust household. Never run this capture against an enrolled user device. */
class HistoryDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun householdTrailFiltersDetailsAndOfflineCacheStayReadable() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fieldNotesHistoryGallery")=="synthetic-only")
        val avd=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).bufferedReader().use {it.readText().trim()}
        assertEquals("field-notes-test",avd)
        val app=instrumentation.targetContext.applicationContext as FieldNotesApplication
        runBlocking {app.initialized.await()}
        val fixture=WireJson.parseToJsonElement(File(app.filesDir,"history-fixture.json").readText()).jsonObject
        val snapshot=WireJson.decodeFromJsonElement<Snapshot>(fixture.getValue("snapshot"))
        val history=SyncJson.decodeFromJsonElement<HistoryPage>(fixture.getValue("history"))
        val original=app.model;val keys=original.state.value.cache.keys
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val transport=HistoryFixtureTransport(fixture,snapshot,history)
        val cache=Cache(keys=keys,config=obj("server_key" to "synthetic-history-peer".j(),"endpoint" to "home.example:49272".j()),token="synthetic-history-session",snapshot=snapshot,
            people=WireJson.decodeFromJsonElement(fixture.getValue("people")),
            preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false,autoRefresh=false,theme="dark",autoDownloadUpdates=false),
            compatibility=ServerCompatibility.read(fixture.getValue("head").jsonObject,Clock.System.now().epochSeconds))
        val model=Workspace(transport,app.vault,{"synthetic-history-operation"},scope)
        runBlocking {app.vault.save(cache);model.initialize();model.poll()};app.model=model
        fun waitText(text:String)=compose.waitUntil(90_000) {compose.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}
        fun shot(name:String) {
            compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(1500)
            val bitmap=instrumentation.uiAutomation.takeScreenshot()
            check((1..5).count {bitmap.getPixel(bitmap.width*it/6,bitmap.height/3) and 0x00ffffff!=0}>=3){"Synthetic capture was still protected"}
            val directory=File(app.filesDir,"history-gallery");directory.mkdirs()
            File(directory,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        try {ActivityScenario.launch(MainActivity::class.java).use {activity->
            activity.onActivity {it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            try {
            compose.waitUntil(90_000){compose.onAllNodesWithContentDescription("Browse workspace").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Browse workspace").performClick()
            compose.onNodeWithTag("drawer-history").performScrollTo().performClick()
            waitText("Bananas");compose.onNodeWithText("Who changed what, and when.").assertIsDisplayed()
            shot("59-history")
            compose.onNodeWithText("Show changes (2) ▾").performScrollTo().performClick()
            compose.onNodeWithText("Four for the breakfast smoothies").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Two for breakfast").assertIsDisplayed();shot("60-history-details")
            compose.onNodeWithText("Hide changes ▴").performScrollTo().performClick()
            compose.onNodeWithTag("history-timeline").performScrollToNode(hasText("Load older changes"))
            compose.onNodeWithText("Load older changes").performClick()
            compose.waitUntil(60_000){model.state.value.cache.currentHistory()?.entries?.size==4}
            compose.onNodeWithTag("history-search").performClick().performTextInput("Two for breakfast")
            compose.waitUntil(60_000){transport.lastQuery=="Two for breakfast"&&transport.lastCount==2}
            // Reproduce the constrained layout with a real sync warning as
            // well as the IME; removing the warning must not be the fix.
            transport.offline=true;runBlocking {withTimeout(30_000){while(model.state.value.problem==null){model.poll();delay(50)}}}
            waitText("Needs attention")
            compose.onNodeWithTag("history-timeline").performScrollToNode(hasText("Server timestamps determine order.",substring=true))
            compose.onNode(hasText("Server timestamps determine order.",substring=true)).assertIsDisplayed()
            shot("63-history-keyboard")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("history-timeline").performScrollToIndex(0)
            transport.offline=true
            compose.onNodeWithText("Refresh history").performClick();waitText("History needs attention")
            waitText("Cached history");shot("61-history-offline")
            val persisted=runBlocking {app.vault.load()};assertNotNull(persisted.history);assertEquals(keys,persisted.keys)
            assertTrue(persisted.history!!.entries.any {it.title=="Bananas"&&it.changes.any {c->c.before=="Two for breakfast".j()}})
            transport.offline=false
            runBlocking {withTimeout(30_000){while(model.state.value.problem!=null){model.poll();delay(50)}}}
            compose.onNodeWithText("Refresh history").performClick()
            compose.waitUntil(60_000){compose.onAllNodesWithText("History needs attention").fetchSemanticsNodes().isEmpty()}
            runBlocking {model.preferences(model.state.value.cache.preferences.copy(theme="light"))};compose.waitForIdle();shot("62-history-light")
            }catch(failure:Throwable){runCatching{shot("failure")};throw failure}
        }} finally {app.model=original;scope.cancel()}
    }
}
private class HistoryFixtureTransport(private val fixture:JsonObject,private val snapshot:Snapshot,private val history:HistoryPage):Transport {
    var offline=false
    @Volatile var lastQuery=""
    @Volatile var lastCount=0
    override suspend fun keys()=error("No new identity")
    override suspend fun configure(configuration:JsonObject){}
    override suspend fun stop(){}
    override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?):Reply {
        if(offline)error("The home server is offline. Cached history and queued changes stay on this phone. Reconnect to refresh.")
        val value=when(path){
            "/api/history"->{
                val request=checkNotNull(body);val anchor=request.number("anchor",history.anchor);val before=request.number("before",anchor+1)
                val filter=HistoryFilter(query=request.text("query"),actor=request.text("actor"),kind=request.text("kind"),action=request.text("action"))
                val rows=history.entries.filter {it.sequence<=anchor&&it.sequence<before&&historyMatches(it,filter,TimeZone.currentSystemDefault())}
                lastQuery=request.text("query");lastCount=rows.size
                val entries=rows.take(2)
                WireJson.encodeToJsonElement(history.copy(anchor=anchor,entries=entries,next_before=entries.lastOrNull()?.sequence?:before,has_more=rows.size>2))
            }
            "/api/snapshot"->WireJson.encodeToJsonElement(snapshot)
            "/api/head"->fixture.getValue("head")
            "/api/people"->obj("people" to fixture.getValue("people"))
            "/api/device/update"->fixture.getValue("apk_update")
            else->error("Unexpected synthetic history route")
        }
        return Reply(200,value.toString())
    }
}
