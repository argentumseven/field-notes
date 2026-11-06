@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

@Composable internal fun RecoveryPendingCard(model:Workspace,state:WorkspaceState) {
    val pending=state.cache.recoveryPending ?: return
    val scope=rememberCoroutineScope();var busy by remember {mutableStateOf(false)};var message by remember {mutableStateOf("")};var dismiss by remember {mutableStateOf(false)}
    fun run(action:suspend()->Unit){scope.launch {busy=true;message="";try{action()}catch(e:Exception){if(e is CancellationException)throw e;message=e.message ?: "Recovery status could not be checked."}finally{busy=false}}}
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Recovery result needs review",style=MaterialTheme.typography.titleSmall)
        Text("A request may have reached the server. The exact request is saved on this phone; it is never retried automatically.",style=MaterialTheme.typography.bodySmall)
        if(pending.error.isNotBlank())Text(pending.error,style=MaterialTheme.typography.bodySmall)
        if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            SecondaryButton(onClick={run{if(model.checkRecoveryResult()==null)message="No saved result was found. It may not have committed, or its receipt expired. Sync and review current content before dismissing this status."}},enabled=!busy){Text("Check result")}
            SecondaryButton(onClick={run{model.retryRecovery()}},enabled=!busy){Text("Retry same request")}
            SecondaryButton(onClick={dismiss=true},enabled=!busy){Text("Dismiss after review")}
        }
    }}
    if(dismiss)EditorDialog(onDismissRequest={dismiss=false},title={Text("Dismiss recovery status?")},text={Text("This does not undo a restore. Sync and inspect current content first. A new restore requires a new preview.")},confirmButton={SecondaryButton(onClick={dismiss=false;run{model.dismissRecoveryStatus()}}){Text("Dismiss status")}},dismissButton={SecondaryButton(onClick={dismiss=false}){Text("Keep status")}})
}

@Composable internal fun RecoverySheet(model:Workspace,state:WorkspaceState,target:String?,title:String,use24Hour:Boolean,close:()->Unit) {
    val scope=rememberCoroutineScope();val zone=TimeZone.currentSystemDefault();val basis=state.cache.snapshot?.basis;val revision=state.cache.snapshot?.history_revision;val actor=state.cache.snapshot?.actor
    var entries by remember(target,basis,revision){mutableStateOf<List<RecoveryVersion>>(emptyList())};var page by remember(target,basis,revision){mutableStateOf<RecoveryPage?>(null)}
    var preview by remember(target,basis,revision){mutableStateOf<RecoveryPreview?>(null)};var busy by remember {mutableStateOf(false)};var problem by remember {mutableStateOf("")};var loaded by remember {mutableStateOf(false)}
    var name by rememberSaveable {mutableStateOf("")};var password by remember {mutableStateOf("")};var done by remember {mutableStateOf("")}
    suspend fun load(more:Boolean=false) {
        val fields=buildMap<String,JsonElement>{target?.let{put("target",it.j())};if(more)page?.next_before?.let{put("before",it.j())}}
        val next=SyncJson.decodeFromJsonElement<RecoveryPage>(model.recovery(if(target==null)"checkpoints" else "versions",JsonObject(fields)))
        entries=if(more) (entries+next.entries).distinctBy {it.id} else next.entries;page=next;loaded=true
    }
    fun run(action:suspend()->Unit){scope.launch {busy=true;problem="";try{action()}catch(e:Exception){if(e is CancellationException)throw e;problem=e.message ?: "Recovery could not complete. Current content is kept."}finally{busy=false}}}
    LaunchedEffect(target,basis,revision){busy=true;problem="";try{load()}catch(e:Exception){if(e is CancellationException)throw e;problem=e.message ?: "Saved states could not load."}finally{busy=false}}
    ModalBottomSheet(onDismissRequest={if(!busy)close()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=760.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(if(preview!=null)"Restore preview" else if(target==null)"Content checkpoints" else "Item versions",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                SecondaryButton(onClick=close,enabled=!busy){Text("Close")}
            }
            HorizontalDivider()
            VisibleLazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(problem.isNotBlank())Text(problem,color=MaterialTheme.colorScheme.error)
                    if(done.isNotBlank())Text(done)
                    RecoveryPendingCard(model,state)
                }
                val plan=preview
                if(plan!=null) {
                    item {Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("${plan.restored} restored · ${plan.changed} changed · ${plan.removed} removed",style=MaterialTheme.typography.titleMedium)
                        Text("Current state is saved first. This creates new versions, so you can return to a newer retained version. Working claims are cleared.")
                        if(plan.checkpoint)Text("Replaces household content. Accounts, pairing and security settings stay current. Unsynchronized edits from the earlier state are retained for review instead of uploaded automatically.")
                        if(!plan.checkpoint)Text(title,style=MaterialTheme.typography.titleMedium)
                        if(plan.reauth_required)OutlinedTextField(password,{password=it},label={Text("Your account password")},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation())
                        Button(shape=ControlShape,onClick={run {
                            if(plan.reauth_required){model.reauthenticate(password);password=""}
                            model.applyRecovery(plan);done="Recovery completed. Current content has been refreshed.";preview=null;load()
                        }},enabled=!busy&&state.cache.recoveryPending==null&&(!plan.reauth_required||password.isNotBlank()),modifier=Modifier.fillMaxWidth()){Text(if(plan.checkpoint)"Restore content checkpoint" else "Restore item version")}
                        SecondaryButton(onClick={preview=null;password=""},enabled=!busy){Text("Back to saved states")}
                    }}
                    items(plan.changes,key={it.id}) {change->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text("${change.action.replaceFirstChar {it.uppercase()}} · ${change.title}",style=MaterialTheme.typography.titleMedium)
                        Text(change.kind.replaceFirstChar {it.uppercase()},style=MaterialTheme.typography.labelMedium)
                        for(field in change.changes){
                            HorizontalDivider();Text(field.label.ifBlank {field.field},style=MaterialTheme.typography.labelLarge)
                            val diff=HistoryChange(field.field,field.label,field.before,field.after)
                            Text("Current: ${historyValue(diff,field.before,zone,use24Hour)}",style=MaterialTheme.typography.bodySmall)
                            Text("Saved: ${historyValue(diff,field.after,zone,use24Hour)}",style=MaterialTheme.typography.bodySmall)
                            if(field.truncated)Text("Excerpt; full saved value is restored.",style=MaterialTheme.typography.labelSmall)
                        }
                        if(change.total_fields>change.changes.size)Text("${change.changes.size} of ${change.total_fields} changed fields shown.",style=MaterialTheme.typography.labelSmall)
                    }}}
                    if(plan.preview_limited)item {Text("Showing the first 50 of ${plan.change_count} affected items. The entire checkpoint is applied atomically.")}
                }else {
                    item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(target==null)"Save household content, comments and attached images together. Checkpoints do not contain accounts or security settings." else "Version indices never go backward. Preview any retained state, then restore it as a new version. Full versions begin with changes made after this feature was installed.")
                        if(target==null&&actor?.role in setOf("owner","admin")){
                            OutlinedTextField(name,{name=it.take(120)},label={Text("Checkpoint name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                            Button(shape=ControlShape,onClick={run{model.createCheckpoint(name);name="";done="Content checkpoint saved.";load()}},enabled=!busy&&name.isNotBlank()&&state.cache.recoveryPending==null){Text("Save content checkpoint")}
                        }
                        SecondaryButton(onClick={run{load()}},enabled=!busy){Text("Refresh saved states")}
                    }}
                    if(entries.isEmpty()&&loaded&&!busy)item {Text("No retained states yet. Older excerpt-only history cannot reconstruct full versions.")}
                    items(entries,key={it.id}) {entry->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("#${entry.index} · ${if(target==null)entry.title else recoveryVersionLabel(entry)}",style=MaterialTheme.typography.titleMedium)
                        Text("${entry.actor_name} · ${historyMoment(entry.at,zone,use24Hour)}",style=MaterialTheme.typography.bodySmall)
                        Text("${entry.records} records · ${entry.images} images",style=MaterialTheme.typography.labelMedium)
                        if(entry.problem.isNotBlank())Text(entry.problem,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        if(entry.can_restore)SecondaryButton(onClick={run{preview=SyncJson.decodeFromJsonElement<RecoveryPreview>(model.recovery("preview",obj("source" to entry.id.j())))}},enabled=!busy&&state.cache.recoveryPending==null){Text("Preview restore")}
                    }}}
                    if(page?.more==true)item {SecondaryButton(onClick={run{load(true)}},enabled=!busy){Text("Load older versions")}}
                    item {Text("Saved states expire under the server's age, count and storage limits. Administrators manage retention and purging in web Management.",style=MaterialTheme.typography.bodySmall)}
                }
            }
        }
    }
}
