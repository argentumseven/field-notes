package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
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
    val tasks = projectRecords(cache).filter {
        it.record.kind == "task" && it.record.data.text("status") != "done" &&
            (it.record.data.text("title") + " " + it.record.data.text("notes")).contains(query, true)
    }.sortedWith(compareBy<VisibleRecord> { it.record.data.number("priority", 3) }.thenBy { it.record.id })
    val people = (listOf("") + cache.people.filter { it.can_assign }.map { it.id } +
        tasks.map { it.record.data.text("assignee") }.filter { it.isNotBlank() }).distinct()
    Text("Assigned means responsible; working means an accepted claim. This workspace is shared.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
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

@Composable internal fun NewTaskDialog(model: Workspace, state: WorkspaceState, container: String?, mine: Boolean, close: () -> Unit) {
    val basis=remember { state.cache.snapshot!!.basis }
    val stale=basis!=state.cache.snapshot!!.basis
    var title by rememberSaveable { mutableStateOf("") }
    var keepIn by rememberSaveable { mutableStateOf(container ?: "") }
    var assignee by rememberSaveable { mutableStateOf(if (mine && state.cache.people.isNotEmpty()) state.cache.snapshot!!.actor.user_id else "") }
    var personMenu by remember { mutableStateOf(false) }
    var collectionMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("Add task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(title, { title = it.take(240) }, label = { Text("Task title · dates optional") }, enabled = !busy)
                Box {
                    OutlinedButton(onClick = { personMenu = true }, enabled = !busy && state.cache.people.isNotEmpty()) {
                        Text("Assigned to: " + if (assignee.isBlank()) "Unassigned" else personName(state.cache, assignee))
                    }
                    DropdownMenu(personMenu, { personMenu = false }) {
                        DropdownMenuItem(text = { Text("Unassigned") }, onClick = { assignee = ""; personMenu = false })
                        state.cache.people.filter { it.can_assign }.forEach { person ->
                            DropdownMenuItem(text = { Text(person.name) }, onClick = { assignee = person.id; personMenu = false })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { collectionMenu = true }, enabled = !busy) {
                        Text("Keep in: " + (workspaceContainers(state.cache).firstOrNull { it.record.id == keepIn }?.record?.data?.text("title") ?: "Inbox"))
                    }
                    DropdownMenu(collectionMenu, { collectionMenu = false }) {
                        DropdownMenuItem(text = { Text("Inbox") }, onClick = { keepIn = ""; collectionMenu = false })
                        workspaceContainers(state.cache).forEach { row ->
                            DropdownMenuItem(text = { Text(row.record.data.text("title")) }, onClick = { keepIn = row.record.id; collectionMenu = false })
                        }
                    }
                }
                if(stale)Text("The workspace was reset. Copy this title before reopening the form.",color=MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && !busy && !stale, onClick = {
                busy = true
                scope.launch {
                    try {
                        val base = obj("title" to title.trim().j(), "project_id" to keepIn.j())
                        val data = if (state.cache.people.isEmpty()) base else JsonObject(base + ("assignee" to if (assignee.isBlank()) JsonNull else assignee.j()))
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
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("Cancel") } },
    )
}
