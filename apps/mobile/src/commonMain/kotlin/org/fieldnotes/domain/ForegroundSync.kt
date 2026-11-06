package org.fieldnotes.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

const val FOREGROUND_REFRESH_MILLIS=3_000L
const val FOREGROUND_STALE_SECONDS=20L

/** Owned by the visible activity's lifecycle, independently of background
 * delivery preferences. Every return to the foreground checks immediately. */
suspend fun Workspace.followForegroundUpdates() {
    while(currentCoroutineContext().isActive) {
        refreshWhileVisible()
        delay(FOREGROUND_REFRESH_MILLIS)
    }
}

fun syncStatus(state:WorkspaceState,now:Long):String {
    val queued=state.cache.outbox.size
    val review=state.cache.conflicts.size
    val drafts=state.cache.drafts.size
    val ago=(now-state.contactAt).coerceAtLeast(0)
    val checked=if(state.contactAt==0L)"not checked yet" else if(ago<5)"checked just now" else if(ago<60)"checked ${ago}s ago" else "checked ${ago/60}m ago"
    return when {
        state.connectionPaused->"Connection paused · $queued queued"
        state.needsLogin->"Sign in to sync · $queued queued"
        state.cache.compatibility?.blocked==true->"App update required · saved work retained"
        state.syncing->"Synchronizing · $queued queued"
        !state.online->"Offline · $queued queued · $checked"
        state.refreshPending->"New changes found · downloading…"
        state.contactAt==0L||ago>FOREGROUND_STALE_SECONDS->"Updates delayed · $checked · $queued queued"
        review>0->"$review ${if(review==1)"change needs" else "changes need"} review · $queued queued · $checked"
        queued>0->"$queued queued · $checked"
        drafts>0->"$drafts local draft${if(drafts==1)"" else "s"} · not uploaded · $checked"
        else->"Up to date · $checked"
    }
}
