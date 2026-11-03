package org.fieldnotes.app

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.Manifest
import android.view.KeyEvent
import android.view.WindowManager
import android.graphics.Bitmap
import java.io.File
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic invitations only. No server contact or changes to the real vault. */
class EnrollmentDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun dismissFixtureSystemDialog():Boolean {
        val root=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        val titles=root.findAccessibilityNodeInfosByText("isn't responding")
        if(titles.isEmpty())return false
        assertTrue("An application ANR must fail the test",titles.all { it.text.toString().let { s->s.contains("System UI")||s.contains("Quickstep") } })
        val close=root.findAccessibilityNodeInfosByText("Close app").firstOrNull() ?: throw AssertionError("No close action for the emulator system dialog")
        assertTrue(close.performAction(AccessibilityNodeInfo.ACTION_CLICK));return true
    }
    private fun visibleText(text: String) = try {
        if(dismissFixtureSystemDialog())false
        else {
            var resumed=false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity } }
            resumed && compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (_: IllegalStateException) { false } // Camera return briefly has no Compose root.
    private fun capture(activity:ActivityScenario<MainActivity>,name:String) {
        if(InstrumentationRegistry.getArguments().getString("fieldNotesEnrollmentCapture")!="synthetic-only")return
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        activity.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);it.window.decorView.invalidate() }
        try {
            instrumentation.waitForIdleSync()
            // Let SurfaceFlinger apply the synthetic window's protection change.
            // Capturing immediately can return a valid PNG containing a black layer.
            Thread.sleep(1500)
            instrumentation.uiAutomation.takeScreenshot().let { bitmap->
                assertTrue("Capture is still protected or blank",(1..5).count { bitmap.getPixel(bitmap.width*it/6,bitmap.height/3) and 0x00ffffff != 0 }>=3)
                File(instrumentation.targetContext.filesDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        }
        finally { activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
    }

    private fun invitation(user: String = "Alex"): String {
        fun key(n:Int)=Base64.encodeToString(ByteArray(32){n.toByte()},Base64.NO_WRAP)
        val value = Invitation(2, "https://10.82.0.1:8443/api/enrollment",
            "-----BEGIN CERTIFICATE-----\n" + "A".repeat(128) + "\n-----END CERTIFICATE-----",
            "a".repeat(64), user, "member", System.currentTimeMillis() / 1000 + 600,
            obj("public_key" to key(1).j(),"server_key" to key(2).j(),"preshared_key" to key(3).j(),"address" to "10.82.0.2/32".j(),"api" to "https://10.82.0.1:8443".j(),"endpoint" to "home.example:49272".j(),"alternate_endpoints" to kotlinx.serialization.json.JsonArray(emptyList())))
        return "fieldnotes://enroll#" + Base64.encodeToString(
            WireJson.encodeToString(value).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun unpaired(block: (Workspace) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication
        runBlocking { app.initialized.await() }
        val original = app.model
        dismissFixtureSystemDialog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var saved = Cache(preferences = Preferences(backgroundSync=false, reminders=false, liveUpdates=false))
        val vault = object : Vault {
            override suspend fun load() = saved
            override suspend fun save(cache: Cache) { saved = cache }
            override suspend fun clearImages() {}
        }
        val transport = object : Transport {
            override suspend fun keys() = obj("public_key" to Base64.encodeToString(ByteArray(32){1},Base64.NO_WRAP).j(),"private_key" to Base64.encodeToString(ByteArray(32){9},Base64.NO_WRAP).j())
            override suspend fun configure(configuration: kotlinx.serialization.json.JsonObject) {}
            override suspend fun stop() {}
            override suspend fun exchange(path: String, token: String?, body: kotlinx.serialization.json.JsonObject?, serverKey: String?) = error("No contact before explicit trust")
        }
        val fixture = Workspace(transport, vault, { "synthetic-operation" }, scope)
        runBlocking { fixture.initialize() }
        app.model = fixture
        try { block(fixture) } finally { app.model = original; scope.cancel() }
    }

    @Test fun incomingQrIsReviewedButRequiresTrustAndReplacementResetsTrust() = unpaired { model ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(invitation()))
        ActivityScenario.launch<MainActivity>(intent).use { activity ->
            compose.waitUntil(60_000) { visibleText("Alex · member") }
            compose.onNodeWithText("Request enrollment").performScrollTo().assertIsNotEnabled()
            compose.onNode(isToggleable()).assertIsOff().performClick()
            compose.onNodeWithText("Request enrollment").assertIsEnabled()
            val replacement = invitation("Sam")
            activity.onActivity { it.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(replacement)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            compose.waitUntil(30_000) { visibleText("Sam · member") }
            compose.onNodeWithText("Request enrollment").performScrollTo().assertIsNotEnabled()
            compose.onNode(isToggleable()).assertIsOff()
            compose.onNode(isToggleable()).performClick()
            activity.onActivity { it.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(replacement)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            compose.waitUntil(30_000) { compose.onNodeWithText("Request enrollment").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
            compose.onNodeWithText("Request enrollment").assertIsNotEnabled()
            assertNull(model.state.value.cache.enrollment)
            assertNull(model.state.value.cache.config)
            // ActivityScenario identifies lifecycle events by the launch intent.
            // Production correctly updates it for each deep link; restore only
            // this test's ownership marker after verifying both replacements.
            activity.onActivity { it.intent=intent }
        }
    }

    @Test fun firstScreenOffersPhonePublicKeyBeforeScanning() = unpaired { model ->
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.waitUntil(60_000) { visibleText("Copy phone public key") }
            compose.onNodeWithText("Copy phone public key").assertIsEnabled()
            compose.onNodeWithText("Scan invitation QR").assertExists()
            compose.onNodeWithText("Review invitation").assertIsNotEnabled()
            capture(activity,"enrollment-start.png")
            assertNull(model.state.value.cache.enrollment)
        }
    }

    @Test fun invalidIncomingQrExplainsFailureAndCannotEnroll() = unpaired { model ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bad = "fieldnotes://enroll#" + "a".repeat(90)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setData(Uri.parse(bad))).use {
            compose.waitUntil(60_000) { visibleText("Or paste invitation") }
            compose.onNodeWithText("Request enrollment").assertDoesNotExist()
            compose.onNodeWithText("This QR is not a valid Field Notes invitation. Ask for a fresh invitation or paste it instead.").performScrollTo().assertIsDisplayed()
            assertNull(model.state.value.cache.enrollment)
        }
    }

    @Test fun cameraScannerIsPrivateAndCancellationPreservesPastedText() = unpaired { model ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(60_000) { visibleText("Or paste invitation") }
            compose.onNodeWithText("Or paste invitation").performTextInput("Keep this pasted draft")
            compose.onNodeWithText("Scan invitation QR").performClick()
            var scanner: EnrollmentCaptureActivity? = null
            compose.waitUntil(30_000) {
                dismissFixtureSystemDialog()
                instrumentation.runOnMainSync {
                    scanner = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<EnrollmentCaptureActivity>().firstOrNull()
                }
                scanner != null
            }
            instrumentation.runOnMainSync {
                assertTrue(scanner!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                @Suppress("DEPRECATION")
                assertFalse(context.packageManager.getActivityInfo(scanner!!.componentName, 0).exported)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(30_000) { visibleText("Keep this pasted draft") }
            compose.onNodeWithText("Request enrollment").assertDoesNotExist()
            assertNull(model.state.value.cache.enrollment)
        }
    }

    @Test fun phoneNameTrustAndRequestStayAboveTheRealKeyboard() = unpaired { model ->
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).setData(Uri.parse(invitation()))).use { activity ->
            compose.waitUntil(60_000) { visibleText("Alex · member") }
            // Synthetic capture only, before the IME inherits its target's
            // screenshot policy. The normal APK always keeps protection on.
            if(InstrumentationRegistry.getArguments().getString("fieldNotesEnrollmentCapture")=="synthetic-only")activity.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            val name=compose.onNodeWithTag("phone-name")
            name.performClick().performTextClearance();name.performTextInput("Alex’s GrapheneOS phone")
            var keyboardTop=0
            compose.waitUntil(30_000) {
                if(dismissFixtureSystemDialog())name.performClick()
                var shown=false
                activity.onActivity { a->val insets=ViewCompat.getRootWindowInsets(a.window.decorView);shown=insets?.isVisible(WindowInsetsCompat.Type.ime())==true;keyboardTop=a.window.decorView.height-(insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?:0) }
                shown && compose.onNodeWithText("Request enrollment").fetchSemanticsNode().boundsInRoot.bottom<=keyboardTop
            }
            name.assertIsDisplayed();compose.onNode(isToggleable()).assertIsDisplayed()
            compose.onNodeWithText("Request enrollment").assertIsDisplayed().assertIsNotEnabled()
            assertTrue(name.fetchSemanticsNode().boundsInRoot.bottom<=keyboardTop)
            capture(activity,"enrollment-keyboard.png")
            name.performImeAction()
            compose.onNodeWithText("Request enrollment").performScrollTo().assertIsDisplayed()
            assertNull(model.state.value.cache.enrollment)
        }
    }
}
