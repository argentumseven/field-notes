@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlinx.datetime.toLocalDateTime
import org.fieldnotes.domain.*

@Composable internal fun ColumnScope.NoteRows(cache:Cache,rows:List<VisibleRecord>,query:String,later:Boolean=false,recipes:Boolean=false,open:(String)->Unit) {
    var category by rememberSaveable(later){mutableStateOf("")};var sort by rememberSaveable(later){mutableStateOf("updated")};var whenFilter by rememberSaveable(later){mutableStateOf("all")}
    val now=kotlinx.datetime.Clock.System.now().epochSeconds
    val filtered=rows.filter { val r=it.record;val at=r.data.number("remind_at");r.kind=="note"&&(!later||r.flag("on_radar"))&&(if(later)true else r.isRecipe()==recipes)&&r.noteSearchText().contains(query,true)&&(category.isBlank()||categoryKey(r.data.text("category","general"))==categoryKey(category))&&(!later||whenFilter=="all"||whenFilter=="undated"&&at==0L||whenFilter=="upcoming"&&at>now||whenFilter=="overdue"&&at in 1..now) }
    val notes=when(sort){"title"->filtered.sortedBy { it.record.data.text("title").lowercase() };"reminder"->filtered.sortedBy { it.record.data.number("remind_at").takeIf { it>0 } ?: Long.MAX_VALUE };"created"->filtered.sortedByDescending { it.record.data.number("created_at") };else->filtered.sortedWith(compareByDescending<VisibleRecord>{it.record.flag("pinned")}.thenByDescending{it.record.data.number("last_activity_at",it.record.data.number("updated_at"))}.thenBy{it.record.id})}
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        ViewChoice("Category",category,listOf("" to "All categories")+categoryFilterChoices(cache)){category=it}
        ViewChoice("Sort",sort,listOf("updated" to "Recently changed","title" to "Name A–Z","reminder" to "Reminder time","created" to "Recently added")){sort=it}
        if(later)ViewChoice("Show",whenFilter,listOf("all" to "All ideas & reminders","undated" to "Undated ideas","upcoming" to "Upcoming reminders","overdue" to "Needs follow-up")){whenFilter=it}
    }
    VisibleLazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=90.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(notes.isEmpty())item { Text(if(recipes&&!cache.supportsPlannerDetails())"Update your server to add recipes. Existing notes and lists continue to sync." else if(recipes)"No recipes match. Search names, ingredients and instructions, or add a recipe." else if(later)"Keep an idea here for later. Add an optional reminder when you want it brought back." else "No notes match. Search titles and full content, or add a note.",Modifier.padding(vertical=24.dp)) }
        items(notes,key={it.record.id}){note->OutlinedCard(onClick={open(note.record.id)},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            RecordBadges(note.record,cache=cache)
            if(note.record.flag("on_radar"))Text(note.record.data.number("remind_at").takeIf { it>0 }?.let{val local=kotlinx.datetime.Instant.fromEpochSeconds(it).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault());"Remind ${friendlyDate(local.date)} · ${friendlyTime(local.time)}"} ?: "Someday · no reminder",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            Text(note.record.data.text("title"),style=MaterialTheme.typography.titleMedium)
            Text(note.record.data.text("ingredients").ifBlank{note.record.data.text("body")}.take(180),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(recordHistory(cache,note.record,kotlinx.datetime.TimeZone.currentSystemDefault()),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if(note.record.isRecipe())"Open recipe ›" else "Open note ›",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        }}}
    }
}
@Composable internal fun NoteEditor(model:Workspace,state:WorkspaceState,visible:VisibleRecord,back:()->Unit,device:DeviceActions=DeviceActions()) {
    val record=visible.record;val draft=state.cache.drafts[record.id]
    val recipe=(draft?.data?:record.data).text("note_type")=="recipe"
    var ingredients by remember(record.id){mutableStateOf((draft?.data?:record.data).text("ingredients"))};var instructions by remember(record.id){mutableStateOf((draft?.data?:record.data).text("instructions"))};var extra by rememberSaveable(record.id){mutableStateOf(false)}
    var title by remember(record.id){mutableStateOf((draft?.data?:record.data).text("title"))};var body by remember(record.id){mutableStateOf((draft?.data?:record.data).text("body"))};var pinned by remember(record.id){mutableStateOf((draft?.data?:record.data)["pinned"]==JsonPrimitive(true))}
    var category by remember(record.id){mutableStateOf((draft?.data?:record.data).text("category","general"))};var radar by remember(record.id){mutableStateOf((draft?.data?:record.data)["on_radar"]==JsonPrimitive(true))};var remindAt by remember(record.id){mutableStateOf((draft?.data?:record.data).number("remind_at"))};var reminderDialog by remember{mutableStateOf(false)}
    var revision by remember(record.id){mutableStateOf(draft?.revision?:record.revision)};var dirty by remember(record.id){mutableStateOf(draft!=null)};var remove by remember { mutableStateOf(false) }
    val basis=remember(record.id){draft?.let { Basis(it.epoch,it.generation) }?:state.cache.snapshot!!.basis};val writable=state.cache.snapshot!!.actor.role!="viewer"&&basis==state.cache.snapshot.basis
    fun fields()=JsonObject(obj("title" to title.j(),"body" to body.j(),"pinned" to JsonPrimitive(pinned))+(if(recipe&&state.cache.supportsPlannerDetails())mapOf("note_type" to "recipe".j(),"ingredients" to ingredients.j(),"instructions" to instructions.j())else emptyMap())+(if(state.cache.supportsPlannerV3())mapOf("category" to category.j(),"on_radar" to JsonPrimitive(radar),"remind_at" to remindAt.j())+(if(record.data.text("category").startsWith("custom:")&&category!=record.data.text("category"))mapOf("category_override" to JsonPrimitive(true))else emptyMap()) else emptyMap()))
    fun retain(){dirty=true;val data=fields();val base=revision;model.launch { model.saveDraft(record.id,base,data,basis,"note.update") }}
    LaunchedEffect(draft?.revision){if(dirty&&draft!=null&&draft.dependsOn==null)revision=draft.revision}
    LaunchedEffect(record.revision,visible.pending){if(!dirty&&!visible.pending){ingredients=record.data.text("ingredients");instructions=record.data.text("instructions");category=record.data.text("category","general");radar=record.flag("on_radar");remindAt=record.data.number("remind_at");title=record.data.text("title");body=record.data.text("body");pinned=record.flag("pinned");revision=record.revision}}
    Scaffold(topBar={TopAppBar(title={Text(if(recipe)"Recipe" else if(visible.pending)"Note · queued" else "Shared note")},navigationIcon={OutlinedIconButton(shape=ControlShape,onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back")}})}){padding->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().plannerScroll().padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            OutlinedTextField(title,{title=it.take(240);retain()},Modifier.fillMaxWidth(),label={Text(if(recipe)"Recipe name" else "Note title")},enabled=writable)
            if(recipe){
                OutlinedTextField(ingredients,{ingredients=it.take(16384);retain()},Modifier.fillMaxWidth(),label={Text("Ingredients")},minLines=3,enabled=writable&&state.cache.supportsPlannerDetails())
                OutlinedTextField(instructions,{instructions=it.take(16384);retain()},Modifier.fillMaxWidth(),label={Text("Cooking instructions")},minLines=5,enabled=writable&&state.cache.supportsPlannerDetails())
            }
            OutlinedTextField(body,{body=it.take(16384);retain()},Modifier.fillMaxWidth(),label={Text(if(recipe)"Notes · optional" else "Content")},minLines=if(recipe)2 else 8,enabled=writable)
            Text(recordHistory(state.cache,record,kotlinx.datetime.TimeZone.currentSystemDefault()),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryButton(onClick={extra=!extra},modifier=Modifier.fillMaxWidth()){Text(if(extra)"Fewer options ▴" else "More options ▾")}
            if(extra){
            if(state.cache.supportsPlannerV3()){CategoryPicker(category,writable,cache=state.cache){category=it;retain()};FollowUpControls(radar,remindAt,device.use24Hour,writable){next,at->radar=next;remindAt=at;retain()};if(radar&&writable)SecondaryButton(onClick={reminderDialog=true}){Text("Reminders & sound")}}
            Row { Checkbox(pinned,{pinned=it;retain()},enabled=writable);Text("Pin note",Modifier.padding(top=12.dp)) }
            }
            if(dirty&&revision!=record.revision&&!visible.pending){Text("Changed on another device. Your draft is kept.",color=MaterialTheme.colorScheme.error);Text("Server: ${record.data.text("title")}\n${record.data.text("body")}");SecondaryButton(onClick={ingredients=record.data.text("ingredients");instructions=record.data.text("instructions");category=record.data.text("category","general");radar=record.flag("on_radar");remindAt=record.data.number("remind_at");title=record.data.text("title");body=record.data.text("body");pinned=record.flag("pinned");revision=record.revision;dirty=false;model.launch { model.discardDraft(record.id) }}){Text("Use server version")};SecondaryButton(onClick={revision=record.revision;retain()}){Text("Keep draft for a new save")}}
            if(writable)FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(shape=ControlShape,onClick={val data=fields();val base=revision;model.launch { model.saveDraft(record.id,base,data,basis,"note.update");model.submitDraft(record.id);dirty=false;model.sync() }},enabled=title.isNotBlank()){Text(if(recipe)"Save recipe" else "Save note")};SecondaryButton(onClick={remove=true},dangerous=true){Text("Delete note")} }
        }
    }
    if(reminderDialog)TaskReminderDialog(model,state,record,device){reminderDialog=false}
    if(remove)EditorDialog(onDismissRequest={remove=false},title={Text("Delete note?")},text={Text("This removes the shared note from active data. Existing backups are unchanged.")},confirmButton={Button(shape=ControlShape,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError),onClick={model.enqueueAsync("note.delete",record.id,obj(),record.revision,basis);remove=false;back()}){Text("Delete")}},dismissButton={SecondaryButton(onClick={remove=false}){Text("Cancel")}})
}
