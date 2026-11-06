package org.fieldnotes.domain

fun widgetMayShowTitles(cache: Cache, deviceUnlocked: Boolean, authenticated: Boolean = true): Boolean = authenticated && (deviceUnlocked||cache.preferences.widgetOnLockScreen) && cache.preferences.widgetTitles && cache.token != null && cache.snapshot != null

fun backgroundUpdateMode(preferences:Preferences)=when { preferences.liveUpdates->"live";preferences.backgroundSync->"periodic";else->"manual" }
fun widgetNeedsBackgroundSync(state:WorkspaceState,now:Long)=state.ready&&!state.needsLogin&&!state.connectionPaused&&state.cache.token!=null&&backgroundUpdateMode(state.cache.preferences)!="manual"&&(state.cache.lastSyncedAt==0L||now-state.cache.lastSyncedAt>=300)
fun Preferences.withBackgroundUpdateMode(mode:String):Preferences=when(mode) {
    "live"->copy(liveUpdates=true,backgroundSync=true)
    "periodic"->copy(liveUpdates=false,backgroundSync=true)
    "manual"->copy(liveUpdates=false,backgroundSync=false)
    else->error("Choose a background update mode.")
}
fun widgetSyncLabel(state:WorkspaceState,now:Long):String {
    val cache=state.cache
    if(cache.recoveryPending!=null||cache.recoveryNotice.isNotBlank())return "Recovery needs review · open app"
    if(state.connectionPaused)return "Paused · open app"
    if(state.syncing)return "Syncing…"
    val age=(now-cache.lastSyncedAt).coerceAtLeast(0)
    val stamp=when {cache.lastSyncedAt==0L->"Not synced yet";age<60->"Updated just now";age<3600->"Updated ${age/60}m ago";age<86400->"Updated ${age/3600}h ago";else->"Updated ${age/86400}d ago"}
    val mode=when(backgroundUpdateMode(cache.preferences)){"live"->"auto";"periodic"->"periodic";else->"manual"}
    return when {cache.outbox.isNotEmpty()->"Sync ${cache.outbox.size} queued · $stamp";!state.online->"Retry sync · offline · $stamp";else->"Sync now · $stamp · $mode"}
}
