package org.fieldnotes.app

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.Manifest
import android.view.KeyEvent
import android.view.WindowManager
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
    private fun visibleText(text: String) = try {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    } catch (_: IllegalStateException) { false } // Camera return briefly has no Compose root.

    private fun invitation(user: String = "Alex"): String {
        val value = Invitation(1, "https://192.168.1.10:9443/api/enrollment",
            "-----BEGIN CERTIFICATE-----\n" + "A".repeat(128) + "\n-----END CERTIFICATE-----",
            "a".repeat(64), user, "member", System.currentTimeMillis() / 1000 + 600)
        return "fieldnotes://enroll#" + Base64.encodeToString(
            WireJson.encodeToString(value).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun unpaired(block: (Workspace) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication
        runBlocking { app.initialized.await() }
        val original = app.model
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var saved = Cache(preferences = Preferences(backgroundSync=false, reminders=false, liveUpdates=false))
        val vault = object : Vault {
            override suspend fun load() = saved
            override suspend fun save(cache: Cache) { saved = cache }
            override suspend fun clearImages() {}
        }
        val transport = object : Transport {
            override suspend fun keys() = obj()
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
}
