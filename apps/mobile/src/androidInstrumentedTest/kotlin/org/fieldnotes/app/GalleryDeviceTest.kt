package org.fieldnotes.app

import android.graphics.Bitmap
import android.view.WindowManager
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.Build
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.jsonObject
import org.fieldnotes.domain.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Documentation capture only, using an isolated AVD and explicit UI fixtures.
 * This class is in the test APK. It never changes production screenshot policy. */
class GalleryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun dismissEmulatorSystemDialog() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        val titles = root.findAccessibilityNodeInfosByText("isn't responding")
        if (titles.isEmpty()) return
        // Unaccelerated AOSP can time out System UI/Quickstep during rendering.
        // Close only those fixture-system dialogs, never an application ANR.
        check(titles.all { it.text.toString().let { s -> s.contains("System UI") || s.contains("Quickstep") } }) {
            "Unexpected application ANR; capture aborted."
        }
        val close = root.findAccessibilityNodeInfosByText("Close app").firstOrNull()
            ?: error("Emulator system dialog could not be dismissed.")
        check(close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(30_000) {
            instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("isn't responding").isNullOrEmpty()
        }
    }
    private fun text(value: String): SemanticsNodeInteraction {
        dismissEmulatorSystemDialog()
        return compose.onNodeWithText(value, useUnmergedTree = true)
    }
    private fun field(value: String): SemanticsNodeInteraction {
        dismissEmulatorSystemDialog()
        return compose.onNode(hasSetTextAction() and hasText(value))
    }
    private fun search(): SemanticsNodeInteraction {
        dismissEmulatorSystemDialog()
        return compose.onNode(hasSetTextAction())
    }
    private fun scrollTo(value: String): SemanticsNodeInteraction {
        dismissEmulatorSystemDialog()
        return compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(value))
    }
    private fun awaitText(value: String) = compose.waitUntil(90_000) {
        compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun captureSyntheticHouseholdWalkthrough() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the dedicated documentation fixture.", args.getString("fieldNotesGallery") == "synthetic-only")
        val app = instrumentation.targetContext.applicationContext as FieldNotesApplication
        runBlocking { app.initialized.await() }
        val original = app.model
        val fixture = WireJson.parseToJsonElement(Base64.decode(checkNotNull(args.getString("fieldNotesFixture")), Base64.NO_WRAP).decodeToString()).jsonObject
        val seed = WireJson.decodeFromJsonElement<Snapshot>(fixture.getValue("snapshot"))
        val transport = GalleryTransport(seed, original.state.value.cache.keys!!, fixture.getValue("stale_error").jsonObject)
        val model = Workspace(transport, app.vault, { UUID.randomUUID().toString() }, app.scope)
        runBlocking { model.initialize() }
        model.state.value.cache.snapshot?.let { transport.snapshot = it }
        app.model = model
        val output = File(app.filesDir, "gallery").apply { mkdirs() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                // The user requested screenshots. Only this test instance, containing
                // synthetic demo content, permits screen capture; the shipped app does not.
                it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            fun shot(name: String) {
                dismissEmulatorSystemDialog()
                compose.waitForIdle()
                val frame = CountDownLatch(1)
                scenario.onActivity {
                    it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (Build.VERSION.SDK_INT >= 29) it.window.decorView.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                    else frame.countDown()
                    it.window.decorView.invalidate()
                }
                check(frame.await(30, TimeUnit.SECONDS)) { "The emulator did not commit a screenshot frame." }
                val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                println("Captured android/$name")
            }
            try {
                if (args.getString("galleryMode") in setOf("light", "image")) {
                    awaitText("Make room for what matters.")
                    runBlocking { model.sync() }
                    if (args.getString("galleryMode") == "image") {
                        text("List").performClick()
                        search().performTextInput("shelves")
                        text("Plan the kitchen shelves").performClick()
                        scrollTo("Attach image")
                        compose.waitUntil(90_000) { compose.onAllNodesWithContentDescription("Kitchen shelf sketch.png").fetchSemanticsNodes().isNotEmpty() }
                        shot("09-task-image")
                    } else shot("16-matrix-light")
                    return
                }
                awaitText("Bring your workspace home.")
                shot("01-pairing")
                val config = WireJson.parseToJsonElement(Base64.decode(checkNotNull(args.getString("fieldNotesConfig")), Base64.NO_WRAP).decodeToString()).jsonObject
                runBlocking { model.pair(config.toString()) }
                awaitText("Welcome home.")
                shot("02-sign-in")
                runBlocking { model.login("owner", "household-test-password-only"); model.sync() }
                val image = seed.records.single { it.kind == "image" }
                val imageScope = MessageDigest.getInstance("SHA-256").digest("${config.text("server_key")}:${seed.actor.user_id}".toByteArray()).joinToString("") { "%02x".format(it) }
                runBlocking { app.vault.saveImage(imageScope, image.id, Base64.decode(fixture.text("image"), Base64.NO_WRAP)) }
                awaitText("Make room for what matters.")
                shot("03-matrix")
                scrollTo("DELEGATE")
                shot("04-matrix-collections")
                text("Board").performClick()
                scrollTo("TO DO")
                shot("05-board")
                scrollTo("IN PROGRESS")
                shot("06-board-claims")
                text("List").performClick()
                shot("07-list")
                search().performTextInput("shelves")
                text("Plan the kitchen shelves").performClick()
                awaitText("Task details")
                shot("08-task-notes")
                scrollTo("Attach image")
                compose.waitUntil(90_000) {
                    compose.onAllNodesWithContentDescription("Kitchen shelf sketch.png").fetchSemanticsNodes().isNotEmpty()
                }
                shot("09-task-image")
                scrollTo("Post comment")
                shot("10-task-conversation")
                scrollTo("Delete"); text("Delete").performClick()
                awaitText("Delete this task?")
                shot("11-delete-confirmation")
                text("Cancel").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                search().performTextClearance()
                text("Add task").performClick()
                field("What would you like to do?").performTextInput("Hang the hallway print")
                shot("12-new-task")
                text("Cancel").performClick()
                compose.onNodeWithContentDescription("Workspace menu").performClick()
                text("Notifications & connection").performClick()
                awaitText("Private, on your terms.")
                shot("13-notifications-connection")
                text("Edit server endpoint").performClick()
                awaitText("Update server endpoint")
                shot("14-server-endpoint")
                text("Cancel").performClick()
                text("Close").performClick()
                val task = model.state.value.cache.snapshot!!.records.first { it.kind == "task" && it.data.text("title") == "Plan the kitchen shelves" }
                runBlocking {
                    // The fixture replays the real server's stale-command response.
                    model.enqueue("task.update", task.id, obj("title" to "Plan the kitchen shelves".j(), "notes" to "Bring the drill on Sunday.".j(), "quadrant" to "decide".j(), "due" to "2026-11-08".j()), 0)
                    model.sync()
                }
                assertTrue(model.state.value.cache.conflicts.isNotEmpty())
                compose.onNodeWithContentDescription("Workspace menu").performClick()
                text("Retained changes (1)").performClick()
                awaitText("Retained changes")
                shot("15-retained-conflict")
                text("Close").performClick()
                runBlocking {
                    model.resolve(model.state.value.cache.conflicts.single().command.operation_id, false)
                    transport.stop()
                    model.enqueue("task.create", model.newId(), obj("title" to "Pick up picture hooks".j(), "project_id" to task.data.getValue("project_id")), null)
                    model.sync()
                }
                search().performTextInput("picture hooks")
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithText("The private connection is unavailable. Your changes stay on this phone.").fetchSemanticsNodes().isEmpty()
                }
                assertTrue(model.state.value.cache.outbox.size == 1)
                shot("17-offline-queue")
                runBlocking { transport.configure(model.state.value.cache.config!!); model.sync() }
            } finally {
                scenario.onActivity {
                    it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                app.model = original
            }
        }
    }
}

/** Only in the instrumentation APK. Stable rendering data is intentionally
 * separate from the real JNI/WireGuard integration tests. */
private class GalleryTransport(var snapshot: Snapshot, private val deviceKeys: JsonObject, private val stale: JsonObject) : Transport {
    private var connected = true
    override suspend fun keys() = deviceKeys
    override suspend fun configure(configuration: JsonObject) { connected = true }
    override suspend fun stop() { connected = false }
    override suspend fun exchange(path: String, token: String?, body: JsonObject?, serverKey: String?): Reply {
        check(connected) { "The private connection is unavailable. Your changes stay on this phone." }
        return when (path) {
            "/api/login" -> Reply(200, obj("token" to "documentation-only".j(), "actor" to WireJson.encodeToJsonElement(snapshot.actor)).toString())
            "/api/snapshot" -> Reply(200, WireJson.encodeToJsonElement(snapshot).toString())
            "/api/commands" -> {
                val command = WireJson.decodeFromJsonElement<Command>(checkNotNull(body))
                if (command.expected_revision == 0L) Reply(409, stale.toString())
                else {
                    check(command.action == "task.create") { "Unsupported documentation action." }
                    val data = JsonObject(mapOf("notes" to "".j(), "quadrant" to "do".j(), "due" to "".j(), "status" to "todo".j(), "claimant" to JsonNull) + command.data)
                    val record = Record(command.target, "task", 1, data)
                    snapshot = snapshot.copy(sequence = snapshot.sequence + 1, records = snapshot.records + record)
                    Reply(200, obj("record" to WireJson.encodeToJsonElement(record)).toString())
                }
            }
            else -> error("Unsupported documentation request: $path")
        }
    }
}
