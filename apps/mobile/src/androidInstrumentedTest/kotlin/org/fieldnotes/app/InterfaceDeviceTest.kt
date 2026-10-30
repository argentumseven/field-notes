package org.fieldnotes.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import org.junit.Rule
import org.junit.Test

class InterfaceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nativeFirstLaunchShowsPairingAndRequiresExplicitTrust() {
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Bring your workspace home.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bring your workspace home.").assertIsDisplayed()
        compose.onNodeWithText("Copy public key").assertExists()
        compose.onNodeWithText("Connect privately").assertIsNotEnabled()
    }
}
