package org.fieldnotes.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class EditorDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun failedTaskPersistenceKeepsTheEnteredTitleVisible() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication
        runBlocking {app.initialized.await()}
        val original=app.model
        val cache=Cache(preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false),keys=obj("private_key" to "synthetic".j()),config=obj("server_key" to "fixture".j()),token="synthetic",snapshot=Snapshot(1,"fixture",1,0,listOf(Record("project","project",1,obj("title" to "Garden".j()))),Actor("user","Test","owner","session")))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val vault=object:Vault{override suspend fun load()=cache;override suspend fun save(cache:Cache){error("Disk is full")};override suspend fun clearImages(){}}
        val transport=object:Transport{override suspend fun keys()=obj();override suspend fun configure(configuration:kotlinx.serialization.json.JsonObject){};override suspend fun stop(){};override suspend fun exchange(path:String,token:String?,body:kotlinx.serialization.json.JsonObject?,serverKey:String?)=error("Offline")}
        val fixture=Workspace(transport,vault,{"test-operation"},scope)
        runBlocking {fixture.initialize()}
        app.model=fixture
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(60_000){compose.onAllNodesWithText("Calendar").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("nav-tasks").performClick()
                compose.onNodeWithContentDescription("Add").performClick()
                compose.onNodeWithText("Task title · dates optional").performTextInput("Buy flowers")
                compose.onNodeWithText("Create").performClick()
                compose.waitUntil(30_000){compose.onAllNodesWithText("Disk is full").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Buy flowers").assertIsDisplayed()
                assertTrue(fixture.state.value.cache.outbox.isEmpty())
            }
        } finally {app.model=original;scope.cancel()}
    }
    @Test fun groceryEditorCanReachTheBottomAndSaveWithTheSoftKeyboardOpen() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication
        runBlocking{app.initialized.await()};val original=app.model
        val list=Record("list","list",1,obj("title" to "Costco Shopping List".j(),"template" to "custom".j(),"fields" to JsonArray(listFieldLabels.keys.map{it.j()}),"category" to "food".j()))
        var cache=Cache(preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false,sharedAdditions=false),keys=obj("private_key" to "synthetic".j()),config=obj("server_key" to "fixture".j()),token="synthetic",snapshot=Snapshot(1,"fixture",1,0,listOf(list),Actor("user","Test","owner","session")),compatibility=ServerCompatibility(version="0.6.0",features=setOf(PLANNER_FEATURE,REMINDER_OPTIONS_FEATURE)))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val vault=object:Vault{override suspend fun load()=cache;override suspend fun save(value:Cache){cache=value};override suspend fun clearImages(){}}
        val transport=object:Transport{override suspend fun keys()=obj();override suspend fun configure(configuration:JsonObject){};override suspend fun stop(){};override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?)=error("Offline fixture")}
        val fixture=Workspace(transport,vault,{java.util.UUID.randomUUID().toString()},scope);runBlocking{fixture.initialize()};app.model=fixture
        try{ActivityScenario.launch(MainActivity::class.java).use{activity->
            activity.onActivity{it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}
            compose.waitUntil(60_000){compose.onAllNodesWithText("Calendar").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("nav-lists").performClick();compose.onNodeWithText("Costco Shopping List").performClick();compose.onNodeWithContentDescription("Add").performClick()
            compose.onNodeWithText("Item name").performTextInput("Coffee beans")
            compose.onNode(hasText("Notes") and hasSetTextAction()).performScrollTo().performClick().performTextInput("Please keep the full note visible above the keyboard")
            compose.onNodeWithText("Effort · minutes").performScrollTo().performClick().performTextInput("10")
            compose.onNodeWithText("Add item").assertIsDisplayed()
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{bitmap->java.io.File(context.filesDir,"keyboard-grocery-editor.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            compose.onNodeWithText("Add item").performClick()
            compose.waitUntil(20_000){fixture.state.value.cache.outbox.any{it.command.action=="task.create"}}
            val saved=fixture.state.value.cache.outbox.last().command.data
            assertTrue(saved.text("notes").contains("above the keyboard"));assertTrue(saved.text("title")=="Coffee beans")
        }}finally{app.model=original;scope.cancel()}
    }
}
