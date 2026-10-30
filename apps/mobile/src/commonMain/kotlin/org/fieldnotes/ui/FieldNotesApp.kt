@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.fieldnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

private val Ink = Color(0xff0b1020)
private val Slate = Color(0xff151d30)
private val Blue = Color(0xff85b9ff)
private val Violet = Color(0xffbeabff)

@Composable fun FieldNotesApp(
    model: Workspace,
    copyText: (String) -> Unit,
    attachImage: (String) -> Unit,
    configureBackground: (Preferences) -> Unit,
    registerBack: ((() -> Unit)?) -> Unit,
    imageContent: @Composable (VisibleRecord) -> Unit,
) {
    val state by model.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Blue, secondary = Violet, background = Ink, surface = Slate, onSurface = Color(0xffedf2fc))
        else lightColorScheme(primary = Color(0xff2756af), secondary = Color(0xff6942b7), background = Color(0xfff6f7fb), surface = Color.White)
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            when {
                !state.ready -> Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(42.dp)); Spacer(Modifier.height(20.dp))
                    Text("A private space for your household.", style = MaterialTheme.typography.headlineSmall)
                    if (state.error == null) CircularProgressIndicator(Modifier.padding(top = 20.dp)) else {
                        Text(state.error!!, Modifier.padding(vertical = 20.dp))
                        Button(onClick = { model.launch { model.initialize() } }) { Text("Retry after unlocking your device") }
                    }
                }
                state.cache.config == null -> PairingScreen(model, state, copyText)
                state.needsLogin || state.cache.snapshot == null -> LoginScreen(model, state)
                else -> WorkspaceScreen(model, state, copyText, attachImage, configureBackground, registerBack, imageContent)
            }
        }
    }
}

@Composable private fun PairingScreen(model: Workspace, state: WorkspaceState, copy: (String) -> Unit) {
    var json by remember { mutableStateOf("") }
    var trusted by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(25.dp)); Brand(); Text("Bring your workspace home.", style = MaterialTheme.typography.headlineMedium)
        Text("1. Approve this phone's public key on your home server. Its private key stays on this phone.")
        Card { Column(Modifier.padding(16.dp)) {
            Text(state.cache.keys?.text("public_key") ?: "", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { copy(state.cache.keys?.text("public_key") ?: "") }) { Icon(Icons.Outlined.Info, null); Spacer(Modifier.width(8.dp)); Text("Copy public key") }
        } }
        Text("2. Paste the connection JSON from your trusted LAN administrator. Compare the server key and CA through that trusted channel.")
        OutlinedTextField(json, { json = it }, Modifier.fillMaxWidth(), label = { Text("Connection JSON · transfer privately") }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), minLines = 6, maxLines = 10)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(trusted, { trusted = it }); Text("I obtained this from my trusted home administrator.", Modifier.weight(1f)) }
        state.error?.let { ErrorText(it) }
        Button(onClick = { busy = true; model.launch { try { model.pair(json) } finally { busy = false } } }, enabled = trusted && json.isNotBlank() && !busy) { Text(if (busy) "Connecting…" else "Connect privately") }
        Text("Field Notes uses an app-contained tunnel. It does not take Android's VPN slot or change system routes. Your current VPN can still filter its UDP traffic.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun LoginScreen(model: Workspace, state: WorkspaceState) {
    var name by rememberSaveable { mutableStateOf(state.cache.snapshot?.actor?.name ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(30.dp), verticalArrangement = Arrangement.Center) {
        Brand(); Spacer(Modifier.height(36.dp)); Text("Welcome home.", style = MaterialTheme.typography.headlineLarge)
        Text("Your projects, plans, and everything in between.", Modifier.padding(vertical = 16.dp))
        if (state.cache.outbox.isNotEmpty()) Text("Your queued edits are kept. Sign back into the same account to reconcile them.")
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("User name") }, singleLine = true)
        Spacer(Modifier.height(12.dp)); OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), visualTransformation = PasswordVisualTransformation())
        state.error?.let { ErrorText(it) }
        Button(onClick = { busy = true; model.launch { try { model.login(name, password); model.sync() } finally { password = ""; busy = false } } }, Modifier.fillMaxWidth().padding(top = 22.dp), enabled = !busy && name.isNotBlank() && password.isNotEmpty()) { Text(if (busy) "Signing in…" else "Sign in") }
    }
}

@Composable private fun WorkspaceScreen(
    model: Workspace, state: WorkspaceState, copy: (String) -> Unit, attach: (String) -> Unit,
    background: (Preferences) -> Unit, registerBack: ((() -> Unit)?) -> Unit,
    image: @Composable (VisibleRecord) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf("matrix") }
    var menu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var addTask by remember { mutableStateOf(false) }
    var addProject by remember { mutableStateOf(false) }
    var showConflicts by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var editEndpoint by remember { mutableStateOf(false) }
    val rows = remember(state.cache) { projectRecords(state.cache) }
    val projects = rows.filter { it.record.kind == "project" }
    val writable = state.cache.snapshot!!.actor.role != "viewer"
    val current = rows.firstOrNull { it.record.id == selected }
    DisposableEffect(selected) { registerBack(if (selected != null) ({ selected = null }) else null); onDispose { registerBack(null) } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); model.clearError() } }
    if (selected != null) {
        if (current != null) TaskScreen(model, state, current, { selected = null }, attach, image)
        else Scaffold(topBar = { TopAppBar(title = { Text("Task removed") }, navigationIcon = { IconButton(onClick = { selected = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }) }) { padding ->
            Column(Modifier.padding(padding).padding(24.dp)) { Text("This task was deleted. Any retained draft is available under conflicts."); Button(onClick = { selected = null; showConflicts = true }) { Text("Review retained changes") } }
        }
    } else Scaffold(
        topBar = { TopAppBar(title = { Brand() }, actions = {
            IconButton(onClick = { model.launch { model.sync() } }, enabled = !state.syncing) { Icon(Icons.Outlined.Refresh, "Sync now") }
            Box { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Workspace menu") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Retained changes (${state.cache.conflicts.size + state.cache.drafts.size})") }, onClick = { menu = false; showConflicts = true })
                    DropdownMenuItem(text = { Text("Notifications & connection") }, onClick = { menu = false; settings = true })
                    if (writable) DropdownMenuItem(text = { Text("New project") }, onClick = { menu = false; addProject = true })
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; model.launch { model.logout() } })
                }
            }
        }) }, snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (writable) ExtendedFloatingActionButton(onClick = { if (projects.isEmpty()) addProject = true else addTask = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add task") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            Text("Make room for what matters.", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Text(when { state.syncing -> "Synchronizing…"; state.online -> "Connected · ${state.cache.outbox.size} pending"; else -> "Offline · ${state.cache.outbox.size} pending · starts are provisional" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.cache.conflicts.isNotEmpty()) TextButton(onClick = { showConflicts = true }) { Icon(Icons.Outlined.Warning, null); Text(" ${state.cache.conflicts.size} changes need attention") }
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("Find a task…") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
                Box { IconButton(onClick = { projectMenu = true }) { Icon(Icons.Outlined.Home, "Choose project") }
                    DropdownMenu(projectMenu, { projectMenu = false }) {
                        DropdownMenuItem(text = { Text("All projects") }, onClick = { project = null; projectMenu = false })
                        projects.forEach { p -> DropdownMenuItem(text = { Text(p.record.data.text("title")) }, onClick = { project = p.record.id; projectMenu = false }) }
                    }
                }
            }
            project?.let { id -> Text(projects.firstOrNull { it.record.id == id }?.record?.data?.text("title") ?: "Project removed", style = MaterialTheme.typography.labelLarge) }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                listOf("matrix", "board", "list").forEachIndexed { i, v -> SegmentedButton(selected = view == v, onClick = { view = v }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(v.replaceFirstChar { it.uppercase() }) } }
            }
            val tasks = rows.filter { it.record.kind == "task" && (project == null || it.record.data.text("project_id") == project) && (it.record.data.text("title") + it.record.data.text("notes")).contains(query, true) }
            if (projects.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("A fresh page. Create your first project to get started.") }
            else if (view == "list") LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(tasks, key = { it.record.id }) { task -> TaskCard(task) { selected = task.record.id } } }
            else {
                val groups = if (view == "matrix") linkedMapOf("do" to "Do", "decide" to "Decide", "delegate" to "Delegate", "defer" to "Defer") else linkedMapOf("todo" to "To do", "in_progress" to "In progress", "done" to "Done")
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    groups.forEach { (key, label) -> item(key) {
                        val group = tasks.filter { it.record.data.text(if (view == "matrix") "quadrant" else "status") == key }
                        OutlinedCard { Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row { Text(label.uppercase(), color = if (key == "decide") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.weight(1f)); Text(group.size.toString(), style = MaterialTheme.typography.labelMedium) }
                            if (group.isEmpty()) Text("Nothing here just yet.", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            group.forEach { task -> TaskCard(task) { selected = task.record.id } }
                        } }
                    } }
                }
            }
        }
    }
    if (addProject) TextDialog("A new project", "Project name", { addProject = false }) { title -> model.enqueue("project.create", model.newId(), obj("title" to title.j()), null, state.cache.snapshot!!.basis); addProject = false; model.launch { model.sync() } }
    if (addTask) TextDialog("Add a task", "What would you like to do?", { addTask = false }) { title ->
        val chosen = projects.firstOrNull { it.record.id == project } ?: projects.firstOrNull()
        if (chosen != null) model.enqueue("task.create", model.newId(), obj("title" to title.j(), "project_id" to chosen.record.id.j()), null, state.cache.snapshot!!.basis)
        addTask = false; model.launch { model.sync() }
    }
    if (editEndpoint) TextDialog("Update server endpoint", "Host or IP and UDP port", { editEndpoint = false }, confirmLabel = "Update", initial = state.cache.config?.text("endpoint") ?: "", maxLength = 260) { value -> model.updateEndpoint(value); editEndpoint = false; model.launch { model.sync() } }
    if (showConflicts) ChangesSheet(model, state, { showConflicts = false }, copy)
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Private, on your terms.", style = MaterialTheme.typography.titleLarge)
            Text("Server: ${state.cache.config?.text("endpoint")}")
            TextButton(onClick = { editEndpoint = true }) { Text("Edit server endpoint") }
            Text("${state.cache.snapshot!!.actor.name} · ${state.cache.snapshot.actor.role}")
            SettingSwitch("Background catch-up", "Uses Android's scheduler, at least 15-minute intervals. Delivery may be delayed by Doze, force-stop, or VPN restrictions.", state.cache.preferences.backgroundSync) { enabled ->
                val p = state.cache.preferences.copy(backgroundSync = enabled); model.launch { model.preferences(p); background(p) }
            }
            SettingSwitch("Local due-date reminders", "Checks cached tasks after 09:00 in this device's time zone. Schedule accuracy is best-effort; offline reminders can be stale.", state.cache.preferences.reminders) { enabled ->
                val p = state.cache.preferences.copy(reminders = enabled); model.launch { model.preferences(p); background(p) }
            }
            Text("No FCM, Play services, analytics, or OS VPN service. Administration and peer revocation are on your trusted LAN server.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { settings = false }) { Text("Close") }; Spacer(Modifier.height(24.dp))
        }
    }
}
@Composable private fun TaskCard(task: VisibleRecord, open: () -> Unit) {
    Card(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(when (task.record.data.text("status")) { "done" -> Icons.Outlined.Done; "in_progress" -> Icons.Outlined.PlayArrow; else -> Icons.Outlined.Info }, null, Modifier.size(21.dp), tint = if (task.record.data.text("status") == "in_progress") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(task.record.data.text("title"), fontWeight = FontWeight.Medium)
                Text(when { task.pending -> "Pending confirmation"; task.record.data.text("claimant").isNotBlank() -> "Claimed · ${task.record.data.text("status")}"; task.record.data.text("due").isNotBlank() -> "Due ${task.record.data.text("due")}"; else -> task.record.data.text("status").replace('_', ' ') }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun TaskScreen(model: Workspace, state: WorkspaceState, visible: VisibleRecord, back: () -> Unit, attach: (String) -> Unit, image: @Composable (VisibleRecord) -> Unit) {
    val task = visible.record
    val draft = state.cache.drafts[task.id]
    var title by remember(task.id) { mutableStateOf(draft?.data?.text("title") ?: task.data.text("title")) }
    var notes by remember(task.id) { mutableStateOf(draft?.data?.text("notes") ?: task.data.text("notes")) }
    var due by remember(task.id) { mutableStateOf(draft?.data?.text("due") ?: task.data.text("due")) }
    var quadrant by remember(task.id) { mutableStateOf(draft?.data?.text("quadrant") ?: task.data.text("quadrant")) }
    var revision by remember(task.id) { mutableStateOf(draft?.revision ?: task.revision) }
    val basis = remember(task.id) { draft?.let { Basis(it.epoch, it.generation) } ?: state.cache.snapshot!!.basis }
    val staleWorkspace = basis != state.cache.snapshot!!.basis
    var saving by remember { mutableStateOf(false) }
    var dirty by remember(task.id) { mutableStateOf(draft != null) }
    var comment by rememberSaveable(task.id) { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    val writable = state.cache.snapshot!!.actor.role != "viewer"
    fun fields() = obj("title" to title.j(), "notes" to notes.j(), "due" to due.j(), "quadrant" to quadrant.j())
    fun retain() { dirty = true; val captured = fields(); val base = revision; model.launch { model.saveDraft(task.id, base, captured, basis) } }
    LaunchedEffect(draft?.revision) { if (dirty && draft != null && draft.dependsOn == null) revision = draft.revision }
    LaunchedEffect(task.revision, visible.pending) {
        if (!dirty && !visible.pending && !staleWorkspace) { title = task.data.text("title"); notes = task.data.text("notes"); due = task.data.text("due"); quadrant = task.data.text("quadrant"); revision = task.revision }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(if (visible.pending) "Pending confirmation" else "Task details") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (staleWorkspace) item { ErrorText("The server was reset or restored. Reopen this task before making new changes; your old drafts are retained.") }
            item { OutlinedTextField(title, { if (it.length <= 240) { title = it; retain() } }, Modifier.fillMaxWidth(), label = { Text("Task title") }, enabled = writable && !saving && !staleWorkspace) }
            item { OutlinedTextField(notes, { if (it.length <= 16384) { notes = it; retain() } }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 5, enabled = writable && !saving && !staleWorkspace) }
            item { OutlinedTextField(due, { if (it.length <= 10) { due = it; retain() } }, Modifier.fillMaxWidth(), label = { Text("Due date · YYYY-MM-DD") }, singleLine = true, enabled = writable && !saving && !staleWorkspace) }
            item { Box {
                OutlinedButton(onClick = { groupMenu = true }, enabled = writable && !saving && !staleWorkspace) { Text("Collection: $quadrant"); Icon(Icons.Outlined.KeyboardArrowDown, null) }
                DropdownMenu(groupMenu, { groupMenu = false }) { listOf("do", "decide", "delegate", "defer").forEach { group -> DropdownMenuItem(text = { Text(group) }, onClick = { quadrant = group; groupMenu = false; retain() }) } }
            } }
            if (dirty && revision != task.revision && !visible.pending) item {
                OutlinedCard { Column(Modifier.padding(16.dp)) {
                    Text("This task changed on another device. Your draft is kept.", fontWeight = FontWeight.Medium)
                    Text("Server title: ${task.data.text("title")}\nServer notes: ${task.data.text("notes")}", Modifier.padding(vertical = 12.dp))
                    TextButton(onClick = { title = task.data.text("title"); notes = task.data.text("notes"); due = task.data.text("due"); quadrant = task.data.text("quadrant"); revision = task.revision; dirty = false; model.launch { model.discardDraft(task.id) } }) { Text("Use server version") }
                    TextButton(onClick = { revision = task.revision; retain() }) { Text("Keep draft for a new save") }
                } }
            }
            if (writable) item { Button(onClick = { val data = fields(); val base = revision; saving = true; model.launch { try { model.saveDraft(task.id, base, data, basis); model.submitDraft(task.id); dirty = false } finally { saving = false }; model.sync() } }, enabled = title.isNotBlank() && !saving && !staleWorkspace) { Icon(Icons.Outlined.Done, null); Spacer(Modifier.width(8.dp)); Text("Save details") } }
            item { Text(when { visible.pending -> "This change is provisional until the server accepts it."; task.data.text("claimant").isNotBlank() -> "This task is claimed. Server authorization decides who can release or finish it."; else -> "Status: ${task.data.text("status").replace('_', ' ')}" }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (writable && !staleWorkspace) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val status = task.data.text("status")
                if (status == "todo") OutlinedButton(onClick = { model.enqueueAsync("task.start", task.id, obj(), task.revision, basis) }) { Text("Start task") }
                if (status == "in_progress") OutlinedButton(onClick = { model.enqueueAsync("task.release", task.id, obj(), task.revision, basis) }) { Text("Release") }
                OutlinedButton(onClick = { model.enqueueAsync(if (status == "done") "task.reopen" else "task.complete", task.id, obj(), task.revision, basis) }) { Text(if (status == "done") "Reopen" else "Complete") }
                TextButton(onClick = { delete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            } }
            item { HorizontalDivider(); Text("Images", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp)) }
            val images = projectRecords(state.cache).filter { it.record.kind == "image" && it.record.data.text("task_id") == task.id }
            items(images, key = { it.record.id }) { row -> image(row) }
            if (writable) item { TextButton(onClick = { attach(task.id) }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Attach image") } }
            item { HorizontalDivider(); Text("Conversation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp)) }
            val comments = projectRecords(state.cache).filter { it.record.kind == "comment" && it.record.data.text("task_id") == task.id }
            items(comments, key = { it.record.id }) { c -> Card { Column(Modifier.fillMaxWidth().padding(15.dp)) {
                Text(c.record.data.text("author") + if (c.pending) " · pending" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(c.record.data.text("body"), Modifier.padding(top = 7.dp))
                if (!c.pending && writable && (state.cache.snapshot.actor.role in setOf("owner", "admin") || c.record.data.text("author_id") == state.cache.snapshot.actor.user_id)) TextButton(onClick = { model.enqueueAsync("comment.delete", c.record.id, obj(), c.record.revision, basis) }) { Text("Remove") }
            } } }
            if (writable) item { Column { OutlinedTextField(comment, { if (it.length <= 8192) comment = it }, Modifier.fillMaxWidth(), label = { Text("Add a comment") }, minLines = 2)
                TextButton(onClick = { val text = comment; model.launch { model.enqueue("comment.create", model.newId(), obj("task_id" to task.id.j(), "body" to text.j()), null, basis); comment = ""; model.sync() } }, enabled = comment.isNotBlank()) { Text("Post comment") }
            } }
            item { Spacer(Modifier.height(25.dp)) }
        }
    }
    if (delete) ConfirmDialog("Delete this task?", "Comments and images will also be removed from active data. Existing backups are unchanged.", "DELETE", { delete = false }) {
        model.enqueueAsync("task.delete", task.id, obj(), task.revision, basis); delete = false; back()
    }
}

@Composable private fun ChangesSheet(model: Workspace, state: WorkspaceState, close: () -> Unit, copy: (String) -> Unit) {
    var discard by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = close) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.85f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Retained changes", style = MaterialTheme.typography.headlineSmall); Text("These drafts stay encrypted on this device. Reapply only after reviewing the current server state.") }
            items(state.cache.conflicts.size) { index -> val conflict = state.cache.conflicts[index]
                Card { Column(Modifier.padding(16.dp)) {
                    Text(conflict.reason, fontWeight = FontWeight.Medium)
                    val printable = JsonObject(conflict.command.data - "bytes").toString()
                    Text(printable, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton(onClick = { copy(printable) }) { Text("Copy text") }
                        TextButton(onClick = { model.launch { model.resolve(conflict.command.operation_id, true); model.sync() } }) { Text("Reapply") }
                        TextButton(onClick = { discard = conflict.command.operation_id }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    }
                } }
            }
            state.cache.drafts.values.forEach { draft -> item(draft.target) { Card { Column(Modifier.padding(16.dp)) {
                Text("Unsaved task details", fontWeight = FontWeight.Medium); Text(draft.data.toString(), Modifier.padding(vertical = 10.dp))
                TextButton(onClick = { copy(draft.data.toString()) }) { Text("Copy text") }
                TextButton(onClick = { model.launch { model.submitDraft(draft.target); model.sync() } }) { Text("Submit draft") }
                TextButton(onClick = { model.launch { model.discardDraft(draft.target) } }) { Text("Discard local draft") }
            } } } }
            if (state.cache.conflicts.isEmpty() && state.cache.drafts.isEmpty()) item { Text("Everything is in a good place. No retained changes.") }
            item { TextButton(onClick = close) { Text("Close") } }
        }
    }
    discard?.let { id -> ConfirmDialog("Discard this retained change?", "Only this local copy will be removed. It cannot be recovered from the server if it was never accepted.", "DISCARD", { discard = null }) { model.launch { model.resolve(id, false) }; discard = null } }
}
@Composable private fun Brand() { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Star, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text("Field Notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) } }
@Composable private fun ErrorText(text: String) { Text(text, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error) }
@Composable private fun SettingSwitch(title: String, detail: String, enabled: Boolean, change: (Boolean) -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(detail, style = MaterialTheme.typography.bodySmall) }; Switch(enabled, change) } }
@Composable private fun TextDialog(title: String, label: String, close: () -> Unit, confirmLabel: String = "Create", initial: String = "", maxLength: Int = 160, submit: suspend (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(title) }, text = {
        Column { OutlinedTextField(text, { if (it.length <= maxLength) text = it }, label = { Text(label) }, enabled = !busy); error?.let { ErrorText(it) } }
    }, confirmButton = {
        TextButton(onClick = { busy = true; error = null; scope.launch { try { submit(text.trim()) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "The change could not be saved." } finally { busy = false } } }, enabled = text.isNotBlank() && !busy) { Text(confirmLabel) }
    }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("Cancel") } })
}
@Composable private fun ConfirmDialog(title: String, detail: String, expected: String, close: () -> Unit, submit: () -> Unit) { var text by rememberSaveable { mutableStateOf("") }; AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Column { Text(detail); OutlinedTextField(text, { text = it }, label = { Text("Type $expected") }) } }, confirmButton = { TextButton(onClick = submit, enabled = text == expected) { Text("Confirm", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } }) }
