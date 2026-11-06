@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import org.fieldnotes.domain.*

@Composable internal fun ColumnScope.HistoryScreen(model:Workspace,state:WorkspaceState,use24Hour:Boolean,projectTarget:String?=null,projectTitle:String="",allHistory:()->Unit={},open:(String)->Unit) {
    val cache=state.cache;val snapshot=cache.snapshot ?: return
    val scope=rememberCoroutineScope();val focus=LocalFocusManager.current;val zone=TimeZone.currentSystemDefault()
    var recoveryTarget by remember {mutableStateOf<String?>(null)};var recoveryTitle by remember {mutableStateOf("")}
    recoveryTarget?.let {RecoverySheet(model,state,it.takeIf {s->s.isNotEmpty()},recoveryTitle,use24Hour){recoveryTarget=null}}
    var query by rememberSaveable {mutableStateOf("")};var actor by rememberSaveable {mutableStateOf("")};var kind by rememberSaveable {mutableStateOf("")};var action by rememberSaveable {mutableStateOf("")}
    var from by rememberSaveable {mutableStateOf("")};var to by rememberSaveable {mutableStateOf("")};var expanded by rememberSaveable {mutableStateOf(false)}
    val filter=HistoryFilter(query,actor,kind,action,target=projectTarget.orEmpty(),from=from,to=to)
    var entries by remember(snapshot.basis,snapshot.actor.user_id){mutableStateOf(cache.currentHistory()?.entries.orEmpty())}
    var page by remember(snapshot.basis,snapshot.actor.user_id){mutableStateOf<HistoryPage?>(null)}
    var loading by remember {mutableStateOf(false)};var problem by remember {mutableStateOf<String?>(null)};var offlineCopy by remember {mutableStateOf(true)}
    var reload by remember {mutableIntStateOf(0)};var olderLoaded by remember {mutableStateOf(false)}
    var requestGeneration by remember {mutableLongStateOf(0)}
    suspend fun load(more:Boolean=false) {
        val ticket=++requestGeneration
        loading=true;problem=null
        try {
            val next=model.history(filter,zone,if(more)page?.anchor else null,if(more)page?.next_before else null)
            if(ticket!=requestGeneration)return
            entries=if(more&&page?.history_revision==next.history_revision&&entries.size+next.entries.size<=300)(entries+next.entries).distinctBy {it.sequence} else next.entries
            page=next;offlineCopy=false;olderLoaded=more
        }catch(e:Exception){
            if(e is CancellationException)throw e
            if(ticket!=requestGeneration)return
            problem=e.message ?: "History could not load. Saved work is kept."
            if(!more){entries=model.state.value.cache.currentHistory()?.entries.orEmpty().filter {historyMatches(it,filter,zone)};offlineCopy=true}
        }finally{if(ticket==requestGeneration)loading=false}
    }
    LaunchedEffect(snapshot.basis,snapshot.actor.user_id,snapshot.history_revision,filter,reload) {
        requestGeneration++;loading=false
        page=null;olderLoaded=false
        entries=cache.currentHistory()?.entries.orEmpty().filter {historyMatches(it,filter,zone)};offlineCopy=true
        delay(300);load()
    }
    LaunchedEffect(snapshot.sequence,loading,cache.preferences.autoRefresh) {
        if(!loading&&!olderLoaded&&cache.preferences.autoRefresh&&page?.let {snapshot.sequence>it.anchor}==true)reload++
    }
    val itemEntries=historyItems(entries)
    VisibleLazyColumn(Modifier.weight(1f).testTag("history-timeline"),contentPadding=PaddingValues(top=8.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("One entry per item. Open its saved states to inspect or restore it.",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                    SecondaryButton(onClick={focus.clearFocus();reload++},enabled=!loading){Text("Refresh history")}
                }
                if(projectTarget!=null){Text("Project activity · $projectTitle");SecondaryButton(onClick=allHistory){Text("All household history")}}
                if(cache.supportsRecovery()){RecoveryPendingCard(model,state);SecondaryButton(onClick={recoveryTarget="";recoveryTitle="Content checkpoints"}){Text("Content checkpoints")}}
                OutlinedTextField(query,{query=it.take(100)},Modifier.fillMaxWidth().testTag("history-search").onFocusChanged {if(it.isFocused)expanded=false},label={Text("Search titles, people and change details")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus()}))
                FlowRow(Modifier.padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    SecondaryButton(onClick={focus.clearFocus();expanded=!expanded}){Text(if(expanded)"Filters ▴" else "Filters ▾")}
                    ViewChoice("Action",action,listOf("" to "All changes","added" to "Added","changed" to "Changed","removed" to "Removed")){action=it}
                    if(filter.active)SecondaryButton(onClick={query="";actor="";kind="";action="";from="";to=""}){Text("Clear filters")}
                }
                if(expanded) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        val people=(cache.people.map {it.id to it.name}+entries.map {it.actor_id to it.actor_name}).distinctBy {it.first}
                        ViewChoice("Person",actor,listOf("" to "Everyone")+people){actor=it}
                        ViewChoice("Type",kind,listOf("" to "All types","task" to "Tasks & list items","list" to "Lists","project" to "Projects","event" to "Calendar events","note" to "Notes","workspace" to "Workspace")){kind=it}
                    }
                    DateField("From day",from){from=it};DateField("Through day",to){to=it}
                }
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=6.dp))
                problem?.let { message->OutlinedCard(Modifier.fillMaxWidth().padding(vertical=8.dp)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("History needs attention",style=MaterialTheme.typography.titleSmall)
                    Text(message,style=MaterialTheme.typography.bodySmall)
                    SecondaryButton(onClick={reload++},enabled=!loading){Text("Try history again")}
                }} }
                if(offlineCopy&&entries.isNotEmpty())Text("Cached history · ${itemEntries.size} items available on this phone. Reconnect for the complete trail.",Modifier.padding(vertical=6.dp),style=MaterialTheme.typography.labelMedium)
                if(olderLoaded)Button(shape=ControlShape,onClick={reload++}){Text(if(page?.let {snapshot.sequence>it.anchor}==true)"New changes · Show latest" else "Back to latest changes")}
        } }
        if(entries.isEmpty()&&!loading)item {
            Text(when{problem!=null->"No matching cached history. Your notes and queued changes are kept.";page?.has_more==true->"No match in this part of the trail. Search older changes below.";filter.active->"No changes match these filters.";else->"No changes yet. Added, changed and removed items will appear here."},Modifier.padding(vertical=20.dp))
        }
        items(itemEntries,key={it.target}) {entry->
            val current=snapshot.records.find {it.id==entry.target}
            val target=if(current?.kind in setOf("comment","image"))current?.data?.text("task_id") else current?.id
            HistoryCard(entry,zone,use24Hour,target?.let {id->{open(id)}},if(cache.supportsRecovery()&&entry.kind!="workspace"){{recoveryTarget=entry.target;recoveryTitle=entry.title}}else null)
        }
        if(!offlineCopy&&page?.has_more==true)item {SecondaryButton(onClick={focus.clearFocus();scope.launch {load(true)}},enabled=!loading,modifier=Modifier.fillMaxWidth()){Text(if(loading)"Loading history…" else if(entries.isEmpty())"Search older changes" else "Load older changes")}}
        item {Text("Server timestamps determine order. Each saved state contains the complete item, including its comments and attachments. Unsynchronized edits appear after the server accepts them. Saved states and change details follow the server’s configurable age, count and storage limits. Account and device security activity is in web Management.",Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable private fun HistoryCard(entry:HistoryEntry,zone:TimeZone,use24Hour:Boolean,open:(()->Unit)?,versions:(()->Unit)?=null) {
    var expanded by rememberSaveable(entry.sequence){mutableStateOf(false)}
    val tint=when(entry.group){"removed"->MaterialTheme.colorScheme.errorContainer;"added"->MaterialTheme.colorScheme.secondaryContainer;else->MaterialTheme.colorScheme.surfaceVariant}
    OutlinedCard(Modifier.fillMaxWidth().testTag("history-entry-${entry.sequence}")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Surface(color=tint,shape=MaterialTheme.shapes.small){Text(entry.verb,Modifier.padding(horizontal=9.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium)}
                Text(entry.typeLabel,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(entry.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            if(entry.parent_title.isNotBlank())Text("In ${entry.parent_title}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${entry.actor_name} · ${historyMoment(entry.at,zone,use24Hour)}",style=MaterialTheme.typography.labelMedium)
            if(entry.details!="recorded")Text("Earlier change · field details were not retained. The item name is its last known title.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else if(entry.changes.isEmpty())Text("Saved without changing content fields.",style=MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(entry.changes.isNotEmpty())SecondaryButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide activity details ▴" else "Latest activity details ▾")}
                if(versions!=null)SecondaryButton(onClick=versions){Text("Item versions")}
                if(open!=null)SecondaryButton(onClick=open){Text("Open item")}
            }
            if(expanded) {
                for(change in entry.changes) {
                    HorizontalDivider()
                    Text(change.label,style=MaterialTheme.typography.labelLarge)
                    Text("Previous",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(historyValue(change,change.before,zone,use24Hour),style=MaterialTheme.typography.bodySmall)
                    Text("Now",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    Text(historyValue(change,change.after,zone,use24Hour),style=MaterialTheme.typography.bodySmall)
                    if(change.before_truncated||change.after_truncated)Text("Excerpt around the edit; full content is not retained in this history entry.",style=MaterialTheme.typography.labelSmall)
                }
                if(entry.total_fields>entry.changes.size)Text("Showing ${entry.changes.size} of ${entry.total_fields} changed fields; this large entry is summarized.",style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}
