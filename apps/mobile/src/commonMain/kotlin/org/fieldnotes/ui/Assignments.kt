package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.datetime.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

@Composable internal fun ColumnScope.AssignmentRows(cache: Cache, query: String, open: (String) -> Unit) {
    val tasks = plannerTasks(cache).filter {
        it.record.kind == "task" && it.record.data.text("status") != "done" &&
            (it.record.data.text("title") + " " + it.record.data.text("notes")).contains(query, true)
    }.sortedWith(compareBy<VisibleRecord> { it.record.data.number("priority", 3) }.thenBy { it.record.id })
    val people = (listOf("") + cache.people.filter { it.can_assign }.map { it.id } +
        tasks.map { it.record.data.text("assignee") }.filter { it.isNotBlank() }).distinct()
    Text("Assigned means responsible; working means an accepted claim. This workspace is shared.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
    VisibleLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
        for (id in people) {
            val rows = tasks.filter { it.record.data.text("assignee") == id }
            item("person:$id") {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp)) {
                    Text(if (id.isEmpty()) "Unassigned" else personName(cache, id), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(rows.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (rows.isEmpty()) Text("No open tasks.", style = MaterialTheme.typography.bodySmall)
            }
            items(rows, key = { it.record.id }) { task -> TaskCard(task, cache) { open(task.record.id) } }
        }
    }
}

@Composable internal fun NewTaskDialog(model: Workspace, state: WorkspaceState, container: String?, mine: Boolean, device:DeviceActions=DeviceActions(), close: () -> Unit) {
    val basis=remember { state.cache.snapshot!!.basis }
    val stale=basis!=state.cache.snapshot!!.basis
    var category by rememberSaveable { mutableStateOf("general") }
    var title by rememberSaveable { mutableStateOf("") }
    var keepIn by rememberSaveable { mutableStateOf(container ?: "") }
    var assignee by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") };var due by rememberSaveable { mutableStateOf("") };var planned by rememberSaveable { mutableStateOf("") };var priority by rememberSaveable { mutableStateOf(3) }
    val reminderId=rememberSaveable { model.newId() };var remindersJson by rememberSaveable { mutableStateOf<String?>(null) }
    val rules=remindersJson?.let { WireJson.decodeFromString<ReminderSet>(it) } ?: ReminderSet(if(due.isNotBlank())listOf(ReminderAlert(reminderId,0)) else emptyList())
    var personMenu by remember { mutableStateOf(false) }
    var collectionMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    EditorDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("Add task") },
        text = {
            ScrollableColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(title, { title = it.take(240) }, label = { Text("Task title · dates optional") }, enabled = !busy)
                Box {
                    SecondaryButton(onClick = { personMenu = true }, enabled = !busy && state.cache.people.isNotEmpty()) {
                        Text("Assigned to: " + if (assignee.isBlank()) "Everyone" else personName(state.cache, assignee))
                    }
                    DropdownMenu(personMenu, { personMenu = false }) {
                        DropdownMenuItem(text = { Text("Everyone · unassigned") }, onClick = { assignee = ""; personMenu = false })
                        state.cache.people.filter { it.can_assign }.forEach { person ->
                            DropdownMenuItem(text = { Text(person.name) }, onClick = { assignee = person.id; personMenu = false })
                        }
                    }
                }
                Box {
                    SecondaryButton(onClick = { collectionMenu = true }, enabled = !busy) {
                        Text("Keep in: " + (workspaceContainers(state.cache).firstOrNull { it.record.id == keepIn }?.record?.data?.text("title") ?: "Inbox"))
                    }
                    DropdownMenu(collectionMenu, { collectionMenu = false }) {
                        DropdownMenuItem(text = { Text("Inbox") }, onClick = { keepIn = ""; collectionMenu = false })
                        workspaceContainers(state.cache).forEach { row ->
                            DropdownMenuItem(text = { Text(row.record.data.text("title")) }, onClick = { keepIn = row.record.id; collectionMenu = false })
                        }
                    }
                }
                if(state.cache.supportsPlannerV3())CategoryPicker(category,!busy){category=it}
                OutlinedTextField(notes,{notes=it.take(16384)},label={Text("Notes")},minLines=2,enabled=!busy,modifier=Modifier.fillMaxWidth())
                Text("Priority",style=MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)){for(p in 1..4)FilterChip(priority==p,{priority=p},{Text(priorityName(p))},enabled=!busy)}
                DateField("Due date · optional",due,!busy){due=it}
                DateField("Planned day · optional",planned,!busy){planned=it}
                ReminderEditor(rules,Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,model::newId,device.use24Hour,device.previewSound){remindersJson=reminderData(it).toString()}
                if(stale)Text("The workspace was reset. Copy this title before reopening the form.",color=MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(shape=ControlShape,enabled = title.isNotBlank() && !busy && !stale, onClick = {
                busy = true
                scope.launch {
                    try {
                        val base = JsonObject(obj("title" to title.trim().j(), "project_id" to keepIn.j(),"notes" to notes.j(),"due" to due.j(),"planned_for" to planned.j(),"priority" to priority.j())+(if(remindersJson!=null){validateReminders(rules);mapOf("reminders" to reminderData(rules))}else emptyMap()))
                        val assigned = if (state.cache.people.isEmpty()) base else JsonObject(base + ("assignee" to if (assignee.isBlank()) JsonNull else assignee.j()))
                        val data=if(state.cache.supportsPlannerV3())JsonObject(assigned+("category" to category.j())) else assigned
                        model.enqueue("task.create", model.newId(), data, null,basis)
                        close()
                        model.launch { model.sync() }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        error = e.message ?: "The task could not be saved."
                    } finally { busy = false }
                }
            }) { Text("Create") }
        },
        dismissButton = { SecondaryButton(onClick = close, enabled = !busy) { Text("Cancel") } },
    )
}
