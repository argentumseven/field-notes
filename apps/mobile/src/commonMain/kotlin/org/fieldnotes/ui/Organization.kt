@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import org.fieldnotes.domain.*

@Composable internal fun ColumnScope.NoteRows(rows: List<VisibleRecord>, query: String, open: (String)->Unit) {
    val notes=rows.filter { it.record.kind=="note"&&(it.record.data.text("title")+" "+it.record.data.text("body")).contains(query,true) }.sortedWith(compareByDescending<VisibleRecord> { it.record.flag("pinned") }.thenByDescending { it.record.data.number("updated_at") }.thenBy { it.record.id })
    LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=90.dp)) {
        if(notes.isEmpty())item { Text("References, ideas and information can stand on their own. Add a note here.",Modifier.padding(vertical=24.dp)) }
        items(notes,key={it.record.id}) { note -> Column(Modifier.fillMaxWidth().clickable { open(note.record.id) }.padding(vertical=16.dp)) {
            Text(note.record.data.text("title"),fontWeight=FontWeight.Medium)
            Text(note.record.data.text("body").take(180),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(note.pending||note.record.flag("pinned"))Text(if(note.pending)"Queued" else "Pinned",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        };HorizontalDivider() }
    }
}
@Composable internal fun ContainerEditor(model:Workspace,state:WorkspaceState,record:Record,close:()->Unit) {
    var title by rememberSaveable(record.id) { mutableStateOf(record.data.text("title")) };var notes by rememberSaveable(record.id) { mutableStateOf(record.data.text("notes")) };var remove by remember { mutableStateOf(false) }
    val occupied=projectRecords(state.cache).any { it.record.kind=="task"&&it.record.data.text("project_id")==record.id }
    val basis=remember(record.id) { state.cache.snapshot!!.basis }
    AlertDialog(onDismissRequest=close,title={Text("Edit ${record.kind}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title,{title=it.take(160)},label={Text("Name")})
        OutlinedTextField(notes,{notes=it.take(16384)},label={Text("Description")},minLines=3)
        if(occupied)Text("Move or remove this collection's tasks before deleting it.",style=MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={TextButton(onClick={model.enqueueAsync(record.kind+".update",record.id,obj("title" to title.j(),"notes" to notes.j()),record.revision,basis);close()},enabled=title.isNotBlank()){Text("Save")}},dismissButton={Row { TextButton(onClick={remove=true},enabled=!occupied){Text("Delete")};TextButton(onClick=close){Text("Close")} }})
    if(remove)AlertDialog(onDismissRequest={remove=false},title={Text("Delete empty ${record.kind}?")},text={Text("Existing backups are unchanged.")},confirmButton={TextButton(onClick={model.enqueueAsync(record.kind+".delete",record.id,obj(),record.revision,basis);remove=false;close()}){Text("Delete")}},dismissButton={TextButton(onClick={remove=false}){Text("Cancel")}})
}
@Composable internal fun NoteEditor(model:Workspace,state:WorkspaceState,visible:VisibleRecord,back:()->Unit) {
    val record=visible.record;val draft=state.cache.drafts[record.id]
    var title by remember(record.id){mutableStateOf((draft?.data?:record.data).text("title"))};var body by remember(record.id){mutableStateOf((draft?.data?:record.data).text("body"))};var pinned by remember(record.id){mutableStateOf((draft?.data?:record.data)["pinned"]==JsonPrimitive(true))}
    var revision by remember(record.id){mutableStateOf(draft?.revision?:record.revision)};var dirty by remember(record.id){mutableStateOf(draft!=null)};var remove by remember { mutableStateOf(false) }
    val basis=remember(record.id){draft?.let { Basis(it.epoch,it.generation) }?:state.cache.snapshot!!.basis};val writable=state.cache.snapshot!!.actor.role!="viewer"&&basis==state.cache.snapshot.basis
    fun fields()=obj("title" to title.j(),"body" to body.j(),"pinned" to JsonPrimitive(pinned))
    fun retain(){dirty=true;val data=fields();val base=revision;model.launch { model.saveDraft(record.id,base,data,basis,"note.update") }}
    LaunchedEffect(draft?.revision){if(dirty&&draft!=null&&draft.dependsOn==null)revision=draft.revision}
    LaunchedEffect(record.revision,visible.pending){if(!dirty&&!visible.pending){title=record.data.text("title");body=record.data.text("body");pinned=record.flag("pinned");revision=record.revision}}
    Scaffold(topBar={TopAppBar(title={Text(if(visible.pending)"Note · queued" else "Shared note")},navigationIcon={IconButton(onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back")}})}){padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            OutlinedTextField(title,{title=it.take(240);retain()},Modifier.fillMaxWidth(),label={Text("Note title")},enabled=writable)
            OutlinedTextField(body,{body=it.take(16384);retain()},Modifier.fillMaxWidth(),label={Text("Content")},minLines=12,enabled=writable)
            Row { Checkbox(pinned,{pinned=it;retain()},enabled=writable);Text("Pin note",Modifier.padding(top=12.dp)) }
            if(dirty&&revision!=record.revision&&!visible.pending){Text("Changed on another device. Your draft is kept.",color=MaterialTheme.colorScheme.error);Text("Server: ${record.data.text("title")}\n${record.data.text("body")}");TextButton(onClick={title=record.data.text("title");body=record.data.text("body");pinned=record.flag("pinned");revision=record.revision;dirty=false;model.launch { model.discardDraft(record.id) }}){Text("Use server version")};TextButton(onClick={revision=record.revision;retain()}){Text("Keep draft for a new save")}}
            if(writable)Row { Button(onClick={val data=fields();val base=revision;model.launch { model.saveDraft(record.id,base,data,basis,"note.update");model.submitDraft(record.id);dirty=false;model.sync() }},enabled=title.isNotBlank()){Text("Save note")};TextButton(onClick={remove=true}){Text("Delete note")} }
        }
    }
    if(remove)AlertDialog(onDismissRequest={remove=false},title={Text("Delete note?")},text={Text("This removes the shared note from active data. Existing backups are unchanged.")},confirmButton={TextButton(onClick={model.enqueueAsync("note.delete",record.id,obj(),record.revision,basis);remove=false;back()}){Text("Delete")}},dismissButton={TextButton(onClick={remove=false}){Text("Cancel")}})
}
