@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.fieldnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fieldnotes.domain.*

@Composable internal fun MembershipField(cache:Cache,value:String,enabled:Boolean,workOnly:Boolean=false,onChange:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Box {
            SecondaryButton(onClick={expanded=true},enabled=enabled) {
                Text("Project or list: "+(workspaceContainers(cache).firstOrNull { it.record.id==value }?.record?.let {cache.projectGraph().path(it.id)} ?: if(value.isBlank())"None (Inbox)" else "Unavailable"))
            }
            DropdownMenu(expanded,{expanded=false}) {
                DropdownMenuItem(text={Text("No project or list (Inbox)")},onClick={expanded=false;onChange("")})
                workspaceContainers(cache).filter { !workOnly||it.record.kind=="project"||it.record.flag("items_are_tasks") }.forEach { row->DropdownMenuItem(text={Text(cache.projectGraph().path(row.record.id)+" · "+row.record.kind)},onClick={expanded=false;onChange(row.record.id)}) }
            }
        }
        Text("Choose a project, or No project or list to keep this task in Inbox.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun AddExistingTaskDialog(model:Workspace,state:WorkspaceState,project:Record,close:()->Unit) {
    val basis=remember(project.id) { state.cache.snapshot!!.basis }
    val tasks=remember(project.id) { projectRecords(state.cache).filter { it.record.kind=="task"&&it.record.data.text("project_id")!=project.id }.map { it.record }.sortedBy { it.data.text("title").lowercase() } }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    EditorDialog(onDismissRequest={if(!busy)close()},title={Text("Add existing task")},text={Column {
        Text("Move a task into ${project.data.text("title")}. Its notes, dates, comments and images stay with it.")
        OutlinedTextField(query,{query=it},label={Text("Find a task")},singleLine=true,enabled=!busy)
        LazyColumn(Modifier.weight(1f,fill=false).heightIn(max=300.dp)) {
            if(tasks.isEmpty())item { Text("No tasks outside this project. Create a new task here first.") }
            items(tasks.filter { it.data.text("title").contains(query,true) },key={it.id}) { task->
                Row(Modifier.fillMaxWidth().clickable(enabled=!busy){selected=task.id}.padding(vertical=8.dp)) {
                    RadioButton(selected==task.id,{selected=task.id},enabled=!busy)
                    Column { Text(task.data.text("title"));Text(task.data.text("project_id").takeIf {it.isNotBlank()}?.let {state.cache.projectGraph().path(it)} ?: "No project or list (Inbox)",style=MaterialTheme.typography.bodySmall) }
                }
            }
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={Button(shape=ControlShape,enabled=selected!=null&&!busy,onClick={busy=true;scope.launch {
        try { val task=tasks.first {it.id==selected};model.moveTask(task.id,project.id,task.revision,basis);close();model.launch {model.sync()} }
        catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}
        finally {busy=false}
    }}) {Text("Move task")}},dismissButton={SecondaryButton(onClick=close,enabled=!busy){Text("Cancel")}})
}
