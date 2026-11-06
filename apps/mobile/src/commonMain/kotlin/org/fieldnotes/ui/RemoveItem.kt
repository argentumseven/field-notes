package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fieldnotes.domain.*

/** The item revision and workspace are frozen when Delete is chosen, not when
 * the confirmation is eventually pressed after another household member edits. */
@Composable internal fun RemoveItemDialog(model:Workspace,selection:Pair<Basis,Record>,dismiss:()->Unit,removed:()->Unit) {
    val scope=rememberCoroutineScope();var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
    val item=selection.second
    EditorDialog(onDismissRequest={if(!busy)dismiss()},title={Text("Delete ${item.data.text("title")}?")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Removes this item, its comments and attached images from active content. Other items stay as they are.")
        if(model.state.value.cache.supportsRecovery())Text("Saved versions can be restored from History while retained.")
        Text("A changed item is kept for review. Offline removals remain queued until accepted by the server.",style=MaterialTheme.typography.bodySmall)
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={Button(shape=ControlShape,enabled=!busy,onClick={busy=true;scope.launch {try{
        model.removeTask(item,selection.first);model.requestSync();removed()
    }catch(e:Exception){if(e is CancellationException)throw e;error=e.message ?: "The item could not be queued for deletion."}finally{busy=false}}}){Text("Delete item")}},dismissButton={SecondaryButton(enabled=!busy,onClick=dismiss){Text("Keep item")}})
}
