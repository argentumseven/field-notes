package org.fieldnotes.domain

import kotlin.test.*

class SyncStatusTest {
    @Test fun staleOrPendingDataCannotLookUpToDate() {
        val fresh=WorkspaceState(ready=true,online=true,contactAt=100)
        assertTrue(syncStatus(fresh,101).startsWith("Up to date"))
        assertTrue(syncStatus(fresh,125).startsWith("Updates delayed"))
        assertTrue(syncStatus(fresh.copy(refreshPending=true),101).startsWith("New changes found"))
        assertTrue(syncStatus(fresh.copy(online=false),101).startsWith("Offline"))
        assertTrue(syncStatus(fresh.copy(connectionPaused=true),101).startsWith("Connection paused"))
    }
}
