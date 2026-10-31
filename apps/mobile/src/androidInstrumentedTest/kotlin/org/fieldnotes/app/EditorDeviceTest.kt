package org.fieldnotes.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
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
                compose.waitUntil(60_000){compose.onAllNodesWithText("Focus").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("Add").performClick()
                compose.onNodeWithText("Task title · dates optional").performTextInput("Buy flowers")
                compose.onNodeWithText("Create").performClick()
                compose.waitUntil(30_000){compose.onAllNodesWithText("Disk is full").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Buy flowers").assertIsDisplayed()
                assertTrue(fixture.state.value.cache.outbox.isEmpty())
            }
        } finally {app.model=original;scope.cancel()}
    }
}
