package org.fieldnotes.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.datetime.Clock
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Small-phone regression: the actual workspace must have one scrolling body. */
class ListViewportDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun headersRowsKeyboardAndReturnNavigationShareTheFullViewport() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fieldNotesListViewport")=="synthetic-only")
        val avd=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).bufferedReader().use{it.readText().trim()}
        assertEquals("field-notes-test",avd)
        val app=instrumentation.targetContext.applicationContext as FieldNotesApplication
        runBlocking{app.initialized.await()}
        val original=runBlocking{app.vault.load()};val now=Clock.System.now().epochSeconds
        val listId="viewport-list";val title="Costco Shopping List"
        val list=Record(listId,"list",1,obj("title" to title.j(),"template" to "groceries".j(),"perpetual" to JsonPrimitive(true),"notes" to "A shared shopping list with many items. Store, filter and cleanup controls must scroll with the items instead of pinning the entire page.".j()))
        val audit=obj("created_by_name" to "Sophia".j(),"last_activity_by_name" to "Alex".j(),"created_at" to (now-3600).j(),"last_activity_at" to now.j())
        val groceries=(1..18).map { n->Record("viewport-item-$n","task",1,obj("title" to "Pantry item ${n.toString().padStart(2,'0')}".j(),"project_id" to listId.j(),"status" to "todo".j(),"store" to "Costco".j(),"quantity" to "1 pack".j()).let{JsonObject(it+audit)}) }
        val references=(1..18).map { n->Record("reference-$n","list",1,obj("title" to "Reference list ${n.toString().padStart(2,'0')}".j(),"template" to "general".j()).let{JsonObject(it+audit)}) }
        val seed=Cache(keys=original.keys,config=obj("server_key" to "synthetic-viewport".j()),token="synthetic-viewport",snapshot=Snapshot(1,"viewport-fixture",1,1,listOf(list)+groceries+references,Actor("owner","Sophia","owner","session")),
            preferences=Preferences(connectionPaused=true,backgroundSync=false,reminders=false,liveUpdates=false,sharedAdditions=false,autoDownloadUpdates=false,notificationTitles=false,widgetTitles=false,theme="dark"),
            compatibility=ServerCompatibility(version="fixture",minimumClient=10,recommendedClient=CURRENT_CLIENT_CODE,features=setOf("household-planner-v3","project-workspaces-v1","history-v1","item-context-v1")))
        runBlocking{app.vault.save(seed);app.model.initialize()}
        fun waitText(value:String)=compose.waitUntil(180_000){compose.onAllNodesWithText(value,substring=true).fetchSemanticsNodes().isNotEmpty()}
        fun shot(name:String){compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(250);val image=instrumentation.uiAutomation.takeScreenshot();val dir=File(app.filesDir,"calendar-gallery");dir.mkdirs();File(dir,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
        val itemRows=SemanticsMatcher("checklist rows"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("checklist-row-")==true}
        try {ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).putExtra("open_page","Tasks").putExtra("open_record",listId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario->
            var activity:MainActivity?=null
            scenario.onActivity{activity=it;it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            try {
                compose.waitUntil(180_000){compose.onAllNodesWithTag("checklist-items").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("nav-lists").assertIsSelected()
                val before=compose.onNodeWithTag("workspace-heading").fetchSemanticsNode().boundsInRoot.top
                compose.onNodeWithTag("workspace-heading").performTouchInput{swipeUp()}
                compose.waitUntil(30_000){compose.onAllNodesWithTag("workspace-heading").fetchSemanticsNodes().let{it.isEmpty()||it.first().boundsInRoot.top<before-8}}
                compose.onNodeWithTag("checklist-items").performScrollToIndex(3)
                compose.waitUntil(30_000){compose.onAllNodes(itemRows).fetchSemanticsNodes().count{it.boundsInRoot.height>0}>=3}
                compose.onNodeWithTag("workspace-heading").assertIsNotDisplayed()
                shot("120-native-list-viewport")
                compose.onNodeWithTag("checklist-items").performScrollToNode(hasText("Pantry item 16"))
                compose.onNodeWithText("Pantry item 16").performClick();waitText("Item name")
                // Opening a modal must retain the underlying list and its state.
                compose.onNodeWithTag("checklist-items").assertExists()
                compose.onNodeWithText("Cancel",substring=false).performClick()
                compose.onNodeWithText("Pantry item 16").assertIsDisplayed();shot("121-native-list-return")
                compose.onNodeWithTag("nav-lists").performClick()
                compose.onNodeWithTag("collection-library").performScrollToNode(hasText("Reference list 10"))
                compose.onNodeWithText("Reference list 10").performClick();compose.waitUntil(90_000){compose.onAllNodesWithTag("checklist-items").fetchSemanticsNodes().isNotEmpty()}
                scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
                compose.onNodeWithText("Reference list 10").assertIsDisplayed();shot("124-native-library-viewport")
                compose.onNodeWithTag("collection-library").performScrollToNode(hasText(title))
                compose.onNodeWithText(title,substring=false).performClick()
                compose.onNodeWithTag("checklist-items").performScrollToNode(hasText("Search titles and item content"))
                compose.onNodeWithText("Search titles and item content").performScrollTo().performClick().performTextInput("Pantry")
                compose.waitUntil(30_000){ViewCompat.getRootWindowInsets(activity!!.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true}
                shot("122-native-list-keyboard")
                compose.onNodeWithTag("checklist-items").performTouchInput{swipeUp()}
                compose.waitUntil(30_000){ViewCompat.getRootWindowInsets(activity!!.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
                compose.onNodeWithTag("checklist-items").performScrollToIndex(3)
                compose.waitUntil(30_000){compose.onAllNodes(itemRows).fetchSemanticsNodes().count{it.boundsInRoot.height>0}>=3}
                runBlocking{app.model.preferences(app.model.state.value.cache.preferences.copy(theme="light"))}
                compose.waitForIdle();Thread.sleep(250);shot("123-native-list-light")
                val saved=runBlocking{app.vault.load()};assertEquals(original.keys,saved.keys);assertEquals(seed.config,saved.config);assertEquals(18,saved.snapshot!!.records.count{it.data.text("project_id")==listId});assertTrue(saved.outbox.isEmpty())
            }catch(failure:Throwable){runCatching{shot("list-viewport-failure")};throw failure}
        }}finally{runBlocking{app.vault.save(original);app.model.initialize()}}
    }
}
