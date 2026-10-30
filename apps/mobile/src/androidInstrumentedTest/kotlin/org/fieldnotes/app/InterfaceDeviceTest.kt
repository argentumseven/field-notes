package org.fieldnotes.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import org.junit.Rule
import org.junit.Test

class InterfaceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nativeFirstLaunchShowsPairingAndRequiresExplicitTrust() {
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Enroll this phone").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Enroll this phone").assertIsDisplayed()
        compose.onNodeWithText("Scan invitation QR").assertExists()
        compose.onNodeWithText("Review invitation").assertIsNotEnabled()
    }
}
