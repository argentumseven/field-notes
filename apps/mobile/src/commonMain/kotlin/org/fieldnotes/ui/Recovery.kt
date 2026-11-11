@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

@Composable internal fun RecoveryPendingCard(model:Workspace,state:WorkspaceState) {
    val pending=state.cache.recoveryPending ?: return
    val scope=rememberCoroutineScope();var busy by remember {mutableStateOf(false)};var message by remember {mutableStateOf("")};var dismiss by remember {mutableStateOf(false)}
    fun run(action:suspend()->Unit){if(busy)return;scope.launch {busy=true;message="";try{action()}catch(e:Exception){if(e is CancellationException)throw e;message=e.message ?: "Recovery status could not be checked."}finally{busy=false}}}
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
    var name by rememberSaveable {mutableStateOf("")};var confirming by remember(preview?.preview_id){mutableStateOf(false)};var deleting by remember(target,basis,revision){mutableStateOf<RecoveryVersion?>(null)};var done by remember {mutableStateOf("")}
    suspend fun load(more:Boolean=false) {
        val fields=buildMap<String,JsonElement>{target?.let{put("target",it.j())};if(more)page?.next_before?.let{put("before",it.j())}}
        val next=SyncJson.decodeFromJsonElement<RecoveryPage>(model.recovery(if(target==null)"checkpoints" else "versions",JsonObject(fields)))
        entries=if(more) (entries+next.entries).distinctBy {it.id} else next.entries;page=next;loaded=true
    }
    fun run(action:suspend()->Unit){if(busy)return;scope.launch {busy=true;problem="";try{action()}catch(e:Exception){if(e is CancellationException)throw e;problem=e.message ?: "Recovery could not complete. Current content is kept."}finally{busy=false}}}
    LaunchedEffect(target,basis,revision){busy=true;problem="";try{load()}catch(e:Exception){if(e is CancellationException)throw e;problem=e.message ?: "Saved states could not load."}finally{busy=false}}
    ModalBottomSheet(onDismissRequest={if(!busy)close()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=760.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(if(preview!=null)"Restore preview" else if(target==null)"Content checkpoints" else "Item versions",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                SecondaryButton(onClick=close,enabled=!busy){Text("Close")}
            }
            HorizontalDivider()
            VisibleLazyColumn(Modifier.weight(1f).testTag("recovery-content"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(problem.isNotBlank())Text(problem,color=MaterialTheme.colorScheme.error)
                    if(done.isNotBlank())Text(done)
                    RecoveryPendingCard(model,state)
                }
                val plan=preview
                if(plan!=null) {
                    item {Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(if(plan.checkpoint)"${plan.restored} restored · ${plan.changed} changed · ${plan.removed} removed" else "Complete item version #${plan.source_index}",style=MaterialTheme.typography.titleMedium)
                        Text("Current state is saved first. Later versions remain available. Working claims are cleared.",style=MaterialTheme.typography.bodySmall)
                        plan.hierarchy?.let {h->
                            Text("Linked content: ${h.linked_existing} existing records retained in place, ${h.missing} deleted records.",style=MaterialTheme.typography.bodyMedium)
                            Text(if(h.mode=="respawn")"${h.respawned} deleted linked records will be restored with their original IDs. Live descendants and their edits are not replaced." else "Deleted linked contents remain deleted. Use the separate option to restore missing contents.",style=MaterialTheme.typography.bodySmall)
                        }
                        if(plan.checkpoint)Text("Replaces household content. Accounts, pairing and security settings stay current. Unsynchronized edits from the earlier state are retained for review instead of uploaded automatically.")
                    }}
                    plan.item_state?.let { pair->item {ItemStatePanel(pair,plan.source_index,zone,use24Hour)} }
                    item {
                        var compare by remember(plan.preview_id){mutableStateOf(false)}
                        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            if(plan.item_state!=null)SecondaryButton(onClick={compare=!compare}){Text(if(compare)"Hide comparison" else "Compare with current state")}
                            if(plan.item_state==null||compare)for(change in plan.changes)RecoveryChangeCard(change,zone,use24Hour)
                        }
                    }
                    if(plan.preview_limited)item {Text("Showing the first 50 of ${plan.change_count} affected items. The entire checkpoint is applied atomically.")}
                    item {Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Selected: #${plan.source_index} · ${plan.title}")
                        Text("Preview expires ${historyMoment(plan.expires_at,zone,use24Hour)}. Any intervening changes require a fresh review.",style=MaterialTheme.typography.bodySmall)
                        if(plan.confirmation_phrase.isBlank())Text("Update the paired server to use the required restore confirmation safeguards.",color=MaterialTheme.colorScheme.error)
                        if(plan.change_count==0)Text("Content already matches this saved state.")
                        Button(shape=ControlShape,onClick={problem="";confirming=true},enabled=!busy&&state.cache.recoveryPending==null&&plan.change_count>0&&plan.confirmation_phrase.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Continue to restore confirmation")}
                        SecondaryButton(onClick={preview=null},enabled=!busy){Text("Back to saved states")}
                    }}
                }else {
                    item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(target==null)"Save household content, comments and attached images together. Checkpoints do not contain accounts or security settings." else "Task versions include comments and attachments. Project and list versions keep existing linked records. When supported, you can explicitly restore missing descendants without duplicating live records. A content checkpoint restores the entire household snapshot. Later states stay available.")
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
                        Text(if(target==null)"${entry.records} items · ${entry.images} images" else "Complete item state",style=MaterialTheme.typography.labelMedium)
                        if(entry.problem.isNotBlank())Text(entry.problem,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        if(entry.can_restore){
                            val hierarchy=target!=null&&entry.kind in setOf("project","list")&&!entry.deleted&&state.cache.supportsHierarchyRecovery()
                            SecondaryButton(onClick={run{
                                val request=JsonObject(obj("source" to entry.id.j())+(if(hierarchy)mapOf("hierarchy_mode" to "bindings".j())else emptyMap()))
                                preview=SyncJson.decodeFromJsonElement<RecoveryPreview>(model.recovery("preview",request))
                            }},enabled=!busy&&state.cache.recoveryPending==null){Text(if(hierarchy)"Preview restore · keep existing links" else "Preview restore")}
                            if(hierarchy&&actor?.role in setOf("owner","admin"))
                                SecondaryButton(onClick={run{preview=SyncJson.decodeFromJsonElement<RecoveryPreview>(model.recovery("preview",obj("source" to entry.id.j(),"hierarchy_mode" to "respawn".j())))}},enabled=!busy&&state.cache.recoveryPending==null){Text("Preview restore + missing contents")}
                        }
                        if(target==null&&entry.can_delete)SecondaryButton(onClick={problem="";deleting=entry},enabled=!busy&&state.cache.recoveryPending==null){Text("Delete checkpoint")}
                    }}}
                    if(page?.more==true)item {SecondaryButton(onClick={run{load(true)}},enabled=!busy){Text("Load older versions")}}
                    item {Text("Saved states expire under the server's age, count and storage limits. Administrators manage retention and purging in web Management.",style=MaterialTheme.typography.bodySmall)}
                }
            }
        }
    }
    val plan=preview
    if(confirming&&plan!=null)RecoveryConfirmDialog(
        title=if(plan.checkpoint)"Restore content checkpoint" else "Restore item version",
        description="Restore #${plan.source_index} · ${plan.title}: ${plan.restored} restored, ${plan.changed} changed, ${plan.removed} removed. "+(if(plan.checkpoint)"This replaces household content and affects everyone. " else plan.hierarchy?.let{if(it.mode=="respawn")"Missing linked records are restored with the original IDs; current linked records are kept. " else "Current linked records stay unchanged; missing contents remain deleted. "} ?: "This replaces the complete item state, comments and attachments. ")+"Current state is saved first. Working claims are cleared.",
        phrase=plan.confirmation_phrase,acknowledgement="I reviewed the selected state and restore impact, including removals.",busy=busy,problem=problem,
        enabled=state.cache.recoveryPending==null,onDismiss={if(!busy)confirming=false},
        onConfirm={confirmation,password->run {
            model.reauthenticate(password)
            model.applyRecovery(plan,confirmation,true)
            confirming=false;preview=null;done="Recovery completed. Current content has been refreshed.";load()
        }})
    deleting?.let {entry->RecoveryConfirmDialog(
        title="Delete content checkpoint",
        description="Permanently delete #${entry.index} · ${entry.title}, saved by ${entry.actor_name} on ${historyMoment(entry.at,zone,use24Hour)}? This recovery copy cannot be restored after deletion. Current household content and other saved states are kept.",
        phrase="DELETE CHECKPOINT #${entry.index}",acknowledgement="I understand this permanently removes this saved checkpoint.",busy=busy,problem=problem,
        enabled=state.cache.recoveryPending==null,onDismiss={if(!busy)deleting=null},
        onConfirm={confirmation,password->val expectedPage=page;run {
            checkNotNull(expectedPage){"Refresh saved states first."}
            model.reauthenticate(password)
            model.deleteCheckpoint(entry,expectedPage,confirmation)
            deleting=null;done="Content checkpoint deleted. Current content is unchanged.";load()
        }})}

}

@Composable private fun RecoveryConfirmDialog(title:String,description:String,phrase:String,acknowledgement:String,busy:Boolean,problem:String,enabled:Boolean,onDismiss:()->Unit,onConfirm:(String,String)->Unit) {
    var confirmation by remember(phrase){mutableStateOf("")};var reviewed by remember(phrase){mutableStateOf(false)};var password by remember(phrase){mutableStateOf("")}
    EditorDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(description)
        OutlinedTextField(confirmation,{confirmation=it},label={Text("Type $phrase to confirm")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        Row(Modifier.toggleable(value=reviewed,enabled=!busy,role=Role.Checkbox,onValueChange={reviewed=it}),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {Checkbox(reviewed,null,enabled=!busy);Text(acknowledgement,Modifier.weight(1f))}
        OutlinedTextField(password,{password=it},label={Text("Your account password")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        if(problem.isNotBlank())Text(problem,color=MaterialTheme.colorScheme.error)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }},confirmButton={Button(shape=ControlShape,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),enabled=enabled&&!busy&&phrase.isNotBlank()&&confirmation==phrase&&reviewed&&password.isNotBlank(),onClick={val secret=password;password="";onConfirm(confirmation,secret)}){Text(title)}},dismissButton={SecondaryButton(onClick=onDismiss,enabled=!busy){Text("Cancel")}})
}

@Composable private fun ItemStatePanel(pair:ItemStatePair,index:Long,zone:TimeZone,use24Hour:Boolean) {
    var saved by remember(index){mutableStateOf(true)}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=saved,onClick={saved=true},label={Text("Selected state #$index")},shape=ControlShape)
            FilterChip(selected=!saved,onClick={saved=false},label={Text("Current state")},shape=ControlShape)
        }
        val state=if(saved)pair.saved else pair.current
        OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(state==null)Text("Item is not present in this state.") else {
                Text(state.title,style=MaterialTheme.typography.titleLarge)
                val advanced=setOf("recurrence","reminders","reminder_options","fields","notify_additions","item_reminders","perpetual","template")
                for(field in state.fields.filter {it.field !in advanced&&it.field !in setOf("title","name")&&it.value!=JsonPrimitive(false)&&it.value!=0.j()}) {
                    if(field.field !in setOf("body","notes"))Text(field.label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(itemStateValue(field.field,field.value,zone,use24Hour),style=MaterialTheme.typography.bodyMedium)
                }
                val settings=state.fields.filter {it.field in advanced}
                if(settings.isNotEmpty()) {
                    var details by remember {mutableStateOf(false)}
                    SecondaryButton(onClick={details=!details}){Text(if(details)"Hide settings" else "Scheduling, reminders & list settings")}
                    if(details)for(field in settings){Text(field.label,style=MaterialTheme.typography.labelMedium);Text(itemStateValue(field.field,field.value,zone,use24Hour),style=MaterialTheme.typography.bodySmall)}
                }
                if(state.comments>0||state.images>0) {
                    HorizontalDivider();Text("${state.comments} comments · ${state.images} attachments",style=MaterialTheme.typography.titleSmall)
                    for(child in state.children) {
                        Text(if(child.kind=="comment")child.author.jsonPrimitive.contentOrNull ?: "Comment" else child.title,style=MaterialTheme.typography.labelMedium)
                        child.body.jsonPrimitive.contentOrNull?.let {Text(it,style=MaterialTheme.typography.bodyMedium)}
                    }
                    if(state.children_limited)Text("First 20 comments and attachments shown. The complete state is restored together.",style=MaterialTheme.typography.bodySmall)
                }
            }
        }}
        Text("Restores the entire selected item state, including comments and attachments. Individual attributes are not restored separately.",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun RecoveryChangeCard(change:RecoveryChange,zone:TimeZone,use24Hour:Boolean) {
    var details by remember(change.id){mutableStateOf(false)}
    OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text("${change.action.replaceFirstChar {it.uppercase()}} · ${change.title}",style=MaterialTheme.typography.titleMedium)
        SecondaryButton(onClick={details=!details}){Text(if(details)"Hide field comparison" else "Field comparison")}
        if(details)for(field in change.changes){
            HorizontalDivider();Text(field.label.ifBlank {field.field},style=MaterialTheme.typography.labelLarge)
            val diff=HistoryChange(field.field,field.label,field.before,field.after)
            Text("Current: ${historyValue(diff,field.before,zone,use24Hour)}",style=MaterialTheme.typography.bodySmall)
            Text("Saved: ${historyValue(diff,field.after,zone,use24Hour)}",style=MaterialTheme.typography.bodySmall)
            if(field.truncated)Text("Excerpt; full saved value is restored.",style=MaterialTheme.typography.labelSmall)
        }
        if(details&&change.total_fields>change.changes.size)Text("${change.changes.size} of ${change.total_fields} changed fields shown.",style=MaterialTheme.typography.labelSmall)
    }}
}

/** Read-only view over server tombstones. This does not promise that every
 * deleted item has a retained restorable version. All recovery goes through
 * the existing fenced preview/password-confirmed restore workflow. */
@Composable internal fun TrashSheet(model:Workspace,state:WorkspaceState,use24Hour:Boolean,onReview:(String,String)->Unit,close:()->Unit) {
    val scope=rememberCoroutineScope()
    val zone=TimeZone.currentSystemDefault()
    val basis=state.cache.snapshot?.basis
    var entries by remember(basis){mutableStateOf<List<JsonObject>>(emptyList())}
    var before by remember(basis){mutableStateOf("")}
    var more by remember(basis){mutableStateOf(false)}
    var loaded by remember(basis){mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var problem by remember{mutableStateOf("")}
    suspend fun load(older:Boolean=false){
        val response=model.recovery("trash",if(older&&before.isNotBlank())obj("before" to before.j()) else obj())
        val items=(response["entries"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}
        entries=if(older)(entries+items).distinctBy{it.text("id")}else items
        before=response.text("next_before")
        more=(response["more"] as? JsonPrimitive)?.booleanOrNull==true
        loaded=true
    }
    fun run(older:Boolean=false){
        if(busy)return
        scope.launch{busy=true;problem=""
            try{load(older)}catch(e:Exception){if(e is CancellationException)throw e;problem=e.message?:"Trash could not load. Saved data is unchanged."}finally{busy=false}
        }
    }
    LaunchedEffect(basis){run()}
    ModalBottomSheet(onDismissRequest={if(!busy)close()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().heightIn(max=760.dp).imePadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Text("Trash · deleted items",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                SecondaryButton(onClick=close,enabled=!busy){Text("Close")}
            }
            HorizontalDivider()
            VisibleLazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                item{Text("Deleted items are outside active views. Recoverability depends on saved versions, server limits and independent backups; no fixed Trash lifetime is promised.",style=MaterialTheme.typography.bodySmall)}
                if(busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
                if(problem.isNotBlank())item{Text(problem,color=MaterialTheme.colorScheme.error)}
                if(loaded&&entries.isEmpty())item{Text("No deleted items recorded.")}
                items(entries,key={it.text("id")}){entry->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(entry.text("title","Item"),style=MaterialTheme.typography.titleMedium)
                    val removed=entry.number("deleted_at")
                    Text(entry.text("kind")+" · "+(if(removed>0)historyMoment(removed,zone,use24Hour) else "Deletion date unavailable"),style=MaterialTheme.typography.bodySmall)
                    if((entry["recoverable"] as? JsonPrimitive)?.booleanOrNull==true)
                        SecondaryButton(onClick={onReview(entry.text("id"),entry.text("title","Item"))}){Text("Review saved versions")}
                    else Text("No retained item version is available. A checkpoint or independent backup may still contain this item.",style=MaterialTheme.typography.bodySmall)
                }}}
                if(more)item{SecondaryButton(onClick={run(true)},enabled=!busy){Text("Load more deleted items")}}
                item{SecondaryButton(onClick={run()},enabled=!busy){Text("Refresh Trash")}}
            }
        }
    }
}
