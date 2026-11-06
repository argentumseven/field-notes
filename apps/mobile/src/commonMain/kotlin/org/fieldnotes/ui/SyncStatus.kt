package org.fieldnotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import org.fieldnotes.domain.*

@Composable internal fun SyncStatusLine(state:WorkspaceState) {
    var now by remember {mutableStateOf(Clock.System.now().epochSeconds)}
    LaunchedEffect(Unit){while(true){delay(1_000);now=Clock.System.now().epochSeconds}}
    val delayed=state.online&&!state.syncing&&(state.contactAt==0L||now-state.contactAt>FOREGROUND_STALE_SECONDS)
    Text(syncStatus(state,now),modifier=Modifier.testTag("sync-status"),style=MaterialTheme.typography.labelSmall,
        color=if((!state.online||delayed||state.refreshPending)&&!state.connectionPaused)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
