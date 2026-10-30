package org.fieldnotes.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
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
        val fixtureFile = File(app.filesDir, "gallery-fixture.json")
        check(fixtureFile.length() in 1..8*1024*1024)
        val fixture = WireJson.parseToJsonElement(fixtureFile.readText()).jsonObject
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
            fun widgetPreview() {
                scenario.onActivity { activity -> activity.setContent { androidx.compose.material3.MaterialTheme { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xff171b1b)).padding(start=24.dp,end=24.dp,top=56.dp,bottom=24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
                    androidx.compose.material3.Text("Home widget · private default", color=androidx.compose.ui.graphics.Color.White)
                    androidx.compose.ui.viewinterop.AndroidView(factory = { context -> PlannerWidget.views(context, model.state.value.cache, true).apply(context, android.widget.FrameLayout(context)) }, modifier=androidx.compose.ui.Modifier.fillMaxWidth().height(250.dp))
                    androidx.compose.material3.Text("Home widget · titles enabled", color=androidx.compose.ui.graphics.Color.White)
                    androidx.compose.ui.viewinterop.AndroidView(factory = { context -> PlannerWidget.views(context, model.state.value.cache.copy(preferences=Preferences(widgetTitles=true)), true).apply(context, android.widget.FrameLayout(context)) }, modifier=androidx.compose.ui.Modifier.fillMaxWidth().height(280.dp))
                } } } }
                shot("11-widget")
            }
            try {
                fun widgetSettings() {
                    compose.onNodeWithContentDescription("Workspace menu").performClick(); text("Settings & widgets").performClick(); awaitText("Settings")
                    text("Settings").performTouchInput { swipe(center, center.copy(y=center.y-600f),600) }
                    text("Add the Field Notes widget from your launcher's widget picker. It opens this locked app and refreshes from the encrypted local cache.").performScrollTo()
                    shot("09-settings-widgets"); text("Close").performClick()
                }
                if (args.getString("galleryMode") == "details") { compose.onNodeWithTag("nav-focus").assertExists(); widgetSettings(); widgetPreview(); return }
                if (args.getString("galleryMode") == "light") { compose.onNodeWithTag("nav-focus").assertExists(); runBlocking { model.sync() }; shot("10-focus-paper"); return }
                awaitText("Enroll this phone"); shot("01-enroll")
                val config = WireJson.parseToJsonElement(Base64.decode(checkNotNull(args.getString("fieldNotesConfig")), Base64.NO_WRAP).decodeToString()).jsonObject
                runBlocking { model.beginEnrollment(Invitation(1,"https://192.168.1.10:9443/api/enrollment",config.text("ca_pem"),"a".repeat(64),"owner","owner",kotlinx.datetime.Clock.System.now().epochSeconds+600), "My GrapheneOS phone"); model.advanceEnrollment() }
                awaitText("1234 ABCD"); shot("02-phone-confirmation")
                runBlocking { model.cancelEnrollment(); model.pair(config.toString()); model.login("owner", "household-test-password-only"); model.sync() }
                val image = seed.records.single { it.kind == "image" }
                val imageScope = MessageDigest.getInstance("SHA-256").digest("${config.text("server_key")}:${seed.actor.user_id}".toByteArray()).joinToString("") { "%02x".format(it) }
                runBlocking { app.vault.saveImage(imageScope, image.id, Base64.decode(fixture.text("image"), Base64.NO_WRAP)) }
                compose.onNodeWithTag("nav-focus").assertExists(); shot("03-focus")
                compose.onNodeWithTag("nav-tasks").performClick(); shot("04-tasks")
                compose.onNodeWithTag("nav-calendar").performClick(); shot("05-calendar")
                compose.onNodeWithContentDescription("Add").performClick(); awaitText("New calendar event"); shot("06-new-event"); text("Close").performClick()
                compose.onNodeWithTag("nav-tasks").performClick(); search().performTextInput("shelves"); text("Plan the kitchen shelves").performClick(); awaitText("Task details"); shot("07-task-planning")
                scrollTo("Post comment"); shot("08-comments-attachments")
                compose.onNodeWithContentDescription("Back").performClick(); widgetSettings()
                compose.onNodeWithContentDescription("Browse workspace").performClick(); shot("12-workspace-navigation")
                compose.onNodeWithTag("drawer-notes").performClick(); awaitText("Household reference"); shot("13-notes")
                text("Household reference").performClick(); awaitText("Shared note"); shot("14-shared-note"); compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Browse workspace").performClick(); compose.onNodeWithTag("drawer-inbox").performClick(); awaitText("Check parcel delivery"); shot("15-inbox")
                compose.onNodeWithContentDescription("Browse workspace").performClick(); compose.onNodeWithTag("drawer-collections").performClick(); awaitText("Groceries"); shot("16-collections")
                compose.onNodeWithTag("nav-focus").performClick()
                widgetPreview()
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
    override suspend fun bootstrap(url: String, ca: String, path: String, body: JsonObject): Reply = when(path) {
        "/claim" -> Reply(200, """{"id":"11111111-1111-1111-1111-111111111111","state":"pending_approval","verification":"1234ABCD"}""")
        "/status" -> Reply(200, """{"id":"11111111-1111-1111-1111-111111111111","state":"pending_approval","verification":"1234ABCD","user":"owner","role":"owner"}""")
        else -> Reply(200, "{}")
    }
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
