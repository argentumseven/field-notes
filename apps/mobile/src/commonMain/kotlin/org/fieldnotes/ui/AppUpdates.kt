package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.fieldnotes.domain.AppUpdateUi

@Composable fun AppUpdatesCard(device:DeviceActions){
    val update=device.update
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("App updates",style=MaterialTheme.typography.titleMedium)
        Text("Installed ${update.installed.ifBlank { "development build" }}"+(update.offered?.let{" · Server offers $it"}?:""),style=MaterialTheme.typography.bodySmall)
        Text(update.message,style=MaterialTheme.typography.bodySmall)
        update.progress?.let{LinearProgressIndicator(progress={it},modifier=Modifier.fillMaxWidth())}
        Text("The paired server selects the version. Updates keep your pairing keys and queued edits. Android asks before installing.",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            SecondaryButton(onClick=device.checkUpdate,enabled=!update.busy){Text("Check for updates")}
            if(update.busy)SecondaryButton(onClick=device.cancelUpdate){Text("Cancel update request")}
            else if(update.ready)Button(onClick=device.installUpdate){Text("Install update")}
            else if(update.available)Button(onClick=device.downloadUpdate){Text("Download update")}
        }
    }
}
