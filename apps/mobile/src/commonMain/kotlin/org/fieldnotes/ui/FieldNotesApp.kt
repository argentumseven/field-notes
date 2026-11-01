@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.fieldnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

@Composable fun FieldNotesApp(
    model: Workspace, copyText: (String) -> Unit, attachImage: (String) -> Unit,
    configureBackground: (Preferences) -> Unit, registerBack: ((() -> Unit)?) -> Unit,
    imageContent: @Composable (VisibleRecord) -> Unit,
    deviceActions:DeviceActions=DeviceActions(), requestedPage:String?=null,
    invitationCode: String? = null, enrollmentPolling: Boolean = true, scanInvitation: () -> Unit = {},
) {
    val state by model.state.collectAsState()
    FieldNotesTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                !state.ready -> Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.Center) { Text("Opening encrypted workspace"); state.error?.let { ErrorText(it); Button(onClick = { model.launch { model.initialize() } }) { Text("Retry") }; if(deviceActions.androidDelivery)SecondaryButton(onClick=deviceActions.appSettings){Text("Android app settings and removal")} } ?: CircularProgressIndicator() }
                (state.cache.enrollment?.stage?.let { it != "sign_in" } == true) || state.cache.config == null -> EnrollmentScreen(model, state, invitationCode, scanInvitation, enrollmentPolling)
                state.needsLogin -> LoginScreen(model, state, copyText)
                state.cache.snapshot == null -> Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.Center) { Text("Connected. Download your workspace."); state.error?.let { ErrorText(it) }; Button(onClick = { model.launch { model.sync() } }) { Text("Sync workspace") };if(deviceActions.androidDelivery)AppUpdatesCard(deviceActions) }
                else -> WorkspaceScreen(model, state, copyText, attachImage, configureBackground, registerBack, imageContent,deviceActions,requestedPage)
            }
        }
    }
}
@Composable private fun EnrollmentScreen(model: Workspace, state: WorkspaceState, incoming: String?, scan: () -> Unit, polling: Boolean) {
    var raw by rememberSaveable { mutableStateOf("") }; var name by rememberSaveable { mutableStateOf("My phone") }
    var invitation by remember { mutableStateOf<Invitation?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    var trusted by rememberSaveable { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    LaunchedEffect(incoming) { incoming?.let { raw = it; invitation = null; trusted = false } }
    val pending = state.cache.enrollment
    LaunchedEffect(pending?.id, pending?.activationSecret, polling, state.connectionPaused) {
        if (pending != null && polling && !state.connectionPaused) {
            var failures=0
            while (true) {
                model.launch { model.advanceEnrollment(); if (model.state.value.cache.enrollment == null) model.sync() }.join()
                if(model.state.value.connectionPaused)break
                failures=if(model.state.value.error==null)0 else (failures+1).coerceAtMost(6)
                delay(pairingRetryDelay(failures))
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(28.dp)); Brand(); Text("Enroll this phone", style = MaterialTheme.typography.headlineSmall)
        if (pending == null) {
            Text("On home Wi-Fi, open Management → Devices on your server. Create an invitation for your account, then scan it here.")
            Button(onClick = scan, modifier = Modifier.fillMaxWidth().testTag("scan-invitation")) { Text("Scan invitation QR") }
            OutlinedTextField(raw, { raw = it; invitation = null; trusted = false }, Modifier.fillMaxWidth(), label = { Text("Or paste invitation") }, minLines = 2, maxLines = 4, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            SecondaryButton(onClick = { try { invitation = readInvitation(raw); error = null } catch (e: Exception) { error = e.message } }, enabled = raw.isNotBlank()) { Text("Review invitation") }
            invitation?.let { invite ->
                Text("${invite.user} · ${invite.role}", style = MaterialTheme.typography.titleMedium)
                Text(invite.url.substringBefore("/api/"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Phone name") })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(trusted, { trusted = it }); Text("This invitation came from my trusted home server.", Modifier.weight(1f)) }
                Button(onClick = { busy = true; model.launch { try { model.beginEnrollment(invite, name); model.advanceEnrollment() } finally { busy = false } } }, enabled = trusted && name.isNotBlank() && !busy) { Text("Request enrollment") }
            }
        } else {
            Text("${pending.name} · ${pending.invitation.user}")
            Text(if(state.connectionPaused)"Pairing paused on this phone. Saved credentials are kept; the server invitation can still expire." else when(pending.stage) { "claiming" -> "Contacting your home server…"; "pending_approval" -> "Ask your administrator to enter this phone's code."; "provisioning" -> "Approved. Waiting for the server to install your tunnel peer."; "ready", "activating" -> "Verifying access through your private tunnel…"; else -> pending.stage })
            if (pending.verification.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) { Text(pending.verification.chunked(4).joinToString(" "), Modifier.padding(24.dp).testTag("enrollment-verification"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold) }
            Text("Keep this screen open. Approval expires after 10 minutes. The code proves which phone you are approving; never enter a code supplied by someone else.", style = MaterialTheme.typography.bodySmall)
            SecondaryButton(onClick = { model.launch { model.resumeConnection(); model.advanceEnrollment(); if(model.state.value.cache.enrollment==null)model.sync() } }) { Text("Retry saved pairing") }
            if(!state.connectionPaused)SecondaryButton(onClick={model.launch { model.pauseConnection() }}){Text("Pause pairing")}
            if (pending.activationSecret != null) SecondaryButton(onClick = { model.launch { model.resumeConnection(); model.enrollmentSignIn(true) } }) { Text("Recover an activated phone with password") }
            SecondaryButton(onClick = { model.launch { model.cancelEnrollment() } }) { Text("Cancel invitation on server") }
        }
        (error ?: state.error)?.let { ErrorText(it) }
        HorizontalDivider(); Text("Your private key stays here. Field Notes uses its own WireGuard connection to the notes endpoint and keeps Android's VPN slot available.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun LoginScreen(model: Workspace, state: WorkspaceState, copy: (String) -> Unit) {
    var review by remember { mutableStateOf(false) }; var reset by remember { mutableStateOf(false) }; var newKeys by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(state.cache.snapshot?.actor?.name ?: "") }; var password by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp), verticalArrangement = Arrangement.Center) {
        Brand(); Spacer(Modifier.height(28.dp)); Text("Sign in", style = MaterialTheme.typography.headlineMedium)
        Text("Use the account assigned to this enrolled phone.", Modifier.padding(vertical = 16.dp))
        if (state.cache.outbox.isNotEmpty()) Text("Your queued edits are kept. Sign in to the same account to reconcile them.")
        ConnectionControls(model,state)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("User name") }, singleLine = true)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Password") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), visualTransformation = PasswordVisualTransformation())
        state.error?.let { ErrorText(it) }
        if (state.cache.enrollment != null) SecondaryButton(onClick = { model.launch { model.enrollmentSignIn(false) } }) { Text("Back to enrollment") }
        Button(onClick = { busy = true; model.launch { try { model.login(name, password); model.sync() } finally { password = ""; busy = false } } }, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = !busy && name.isNotBlank() && password.isNotEmpty()) { Text(if (busy) "Signing in…" else "Sign in") }
        SecondaryButton(onClick = { review = true }) { Text("Review retained changes") }
        SecondaryButton(onClick = { reset = true }, enabled = state.cache.outbox.isEmpty() && state.cache.conflicts.isEmpty() && state.cache.drafts.isEmpty()) { Text("Enroll this phone again") }
    }
    if (review) ChangesSheet(model, state, { review = false }, copy)
    if (reset) ConfirmDialog("Set up pairing again?", "This clears downloaded cache and keeps your phone keys by default. Revoke the old server entry and remove its inactive pairing before creating a fresh invitation. Household content stays on the server.", "REENROLL", { reset = false }, extra={Row(verticalAlignment=Alignment.CenterVertically){Checkbox(newKeys,{newKeys=it});Text("Generate new phone keys too (only if needed)",Modifier.weight(1f))}}) { model.launch { model.resetConnection(newKeys) }; reset = false }
}
@Composable private fun WorkspaceScreen(model: Workspace, state: WorkspaceState, copy: (String) -> Unit, attach: (String) -> Unit, background: (Preferences) -> Unit, registerBack: ((() -> Unit)?) -> Unit, image: @Composable (VisibleRecord) -> Unit,device:DeviceActions,requestedPage:String?) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }; var page by rememberSaveable { mutableStateOf("Focus") }
    var query by rememberSaveable { mutableStateOf("") }; var project by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }; var projectsMenu by remember { mutableStateOf(false) }
    var addTask by remember { mutableStateOf(false) }; var addProject by remember { mutableStateOf(false) }; var addList by remember { mutableStateOf(false) }; var addNote by remember { mutableStateOf(false) }; var editingContainer by remember { mutableStateOf<Record?>(null) }; var addEvent by remember { mutableStateOf(false) }
    var changes by remember { mutableStateOf(false) }; var settings by remember { mutableStateOf(false) }; var endpoint by remember { mutableStateOf(false) }
    LaunchedEffect(requestedPage){if(requestedPage in listOf("Focus","Tasks","Calendar","Assignments","Notes"))page=requestedPage!!}
    var removeInstallation by remember { mutableStateOf(false) };var profileHelp by remember { mutableStateOf(false) }
    val rows = remember(state.cache) { projectRecords(state.cache) }; val projects = workspaceContainers(state.cache)
    val drawer=rememberDrawerState(DrawerValue.Closed);val uiScope=rememberCoroutineScope()
    fun navigate(view:String,container:String?=null){page=view;project=container;query="";selected=null;uiScope.launch { drawer.close() }}
    val actor = state.cache.snapshot!!.actor; val writable = actor.role != "viewer"
    val current = rows.firstOrNull { it.record.id == selected }
    val zone = TimeZone.currentSystemDefault(); val now = Clock.System.now() // Foreground sync refreshes this view; no UI-owned perpetual timer.
    val today = now.toLocalDateTime(zone).date
    DisposableEffect(selected) { registerBack(if (selected != null) ({ selected = null }) else null); onDispose { registerBack(null) } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); model.clearError() } }
    if (selected != null && current?.record?.kind == "event") EventEditor(model, state, current.record, today, { selected = null })
    if (selected != null && current?.record?.kind == "note") NoteEditor(model,state,current,{selected=null})
    else if (selected != null && current?.record?.kind == "task") TaskScreen(model, state, current, { selected = null }, attach, image)
    else ModalNavigationDrawer(drawerState=drawer,drawerContent={ModalDrawerSheet { Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("Workspace",Modifier.padding(14.dp),style=MaterialTheme.typography.titleLarge)
        for(view in listOf("Inbox","Focus","Tasks","Assignments","Calendar","Notes","Collections","My tasks","Unassigned","Unscheduled","Waiting","Completed"))NavigationDrawerItem(modifier=Modifier.testTag("drawer-"+view.lowercase().replace(" ","-")),label={Text(view)},selected=page==view&&project==null,onClick={navigate(view)})
        for(kind in listOf("list","project")){HorizontalDivider(Modifier.padding(vertical=12.dp));Row { Text(if(kind=="list")"Lists" else "Projects",Modifier.weight(1f).padding(12.dp),style=MaterialTheme.typography.labelLarge);if(writable)SecondaryButton(onClick={if(kind=="list")addList=true else addProject=true}){Text(if(kind=="list")"Add list" else "Add project")} };projects.filter { it.record.kind==kind }.forEach { c->NavigationDrawerItem(label={Text(c.record.data.text("title"))},selected=project==c.record.id,onClick={navigate("Tasks",c.record.id)}) }}
    } }}) { Scaffold(topBar = { TopAppBar(title = { Brand() }, navigationIcon={OutlinedIconButton(onClick={uiScope.launch { drawer.open() }}){Icon(Icons.Outlined.Menu,"Browse workspace")}},actions = {
        OutlinedIconButton(onClick = { model.launch { model.sync() } }, enabled = !state.syncing) { Icon(Icons.Outlined.Refresh, "Sync now") }
        Box { OutlinedIconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Workspace menu") }; DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Retained changes (${state.cache.conflicts.size + state.cache.drafts.size})") }, onClick = { menu = false; changes = true })
            DropdownMenuItem(text = { Text("Settings & widgets") }, onClick = { menu = false; settings = true })
            if (writable) {
                DropdownMenuItem(text={Text("New task")},onClick={menu=false;addTask=true})
                DropdownMenuItem(text={Text("New list")},onClick={menu=false;addList=true})
                DropdownMenuItem(text={Text("New project")},onClick={menu=false;addProject=true})
                DropdownMenuItem(text={Text("New note")},onClick={menu=false;addNote=true})
                projects.firstOrNull { it.record.id==project }?.let { c->DropdownMenuItem(text={Text("Edit ${c.record.kind}")},onClick={menu=false;editingContainer=c.record}) }
            }
            DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; model.launch { model.logout() } })
        } }
    }) }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = { NavigationBar(tonalElevation = 0.dp) {
        listOf("Focus" to Icons.Outlined.PlayArrow, "Tasks" to Icons.Outlined.List, "Calendar" to Icons.Outlined.DateRange).forEach { (label, icon) -> NavigationBarItem(modifier=Modifier.testTag("nav-"+label.lowercase()),selected = page == label, onClick = { navigate(label) }, icon = { Icon(if(label == "Focus") Icons.Outlined.PlayArrow else icon, null) }, label = { Text(label) }) }
    } }, floatingActionButton = { if (writable) FloatingActionButton(onClick = { if (page == "Calendar") addEvent = true else if(page=="Notes")addNote=true else if(page=="Collections")addList=true else addTask = true }) { Icon(Icons.Outlined.Add, "Add") } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            Text(projects.firstOrNull { it.record.id==project }?.record?.data?.text("title")?:page, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Text(when { state.connectionPaused -> "Connection paused · cached work and reminders remain available"; state.syncing -> "Synchronizing…"; state.online -> "Synced · ${state.cache.outbox.size} queued"; else -> "Offline · ${state.cache.outbox.size} queued · starts provisional" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.cache.conflicts.isNotEmpty()) SecondaryButton(onClick = { changes = true }) { Text("${state.cache.conflicts.size} changes need review") }
            if(device.update.available)SecondaryButton(onClick={settings=true}){Text("App update ${device.update.offered} available")}
            if (page == "Calendar") PlannerCalendar(rows, today, zone, { selected = it })
            else if(page=="Assignments") {
                OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(top=12.dp),label={Text("Find assigned work")},singleLine=true)
                AssignmentRows(state.cache,query){selected=it}
            }
            else if(page=="Collections") {
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=90.dp)) { for(kind in listOf("list","project")){item { Text(if(kind=="list")"Lists" else "Projects",Modifier.padding(vertical=16.dp),style=MaterialTheme.typography.titleMedium) };items(projects.filter { it.record.kind==kind },key={it.record.id}) { c->Column(Modifier.fillMaxWidth().clickable { navigate("Tasks",c.record.id) }.padding(vertical=12.dp)){Text(c.record.data.text("title"),fontWeight=FontWeight.Medium);Text(c.record.data.text("notes"),style=MaterialTheme.typography.bodySmall);if(writable)SecondaryButton(onClick={editingContainer=c.record}){Text("Edit ${kind}")}};HorizontalDivider() } } }
            }
            else {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text(if(page=="Notes")"Search notes" else "Search tasks") }, singleLine = true)
                    if(page!="Notes") Box { OutlinedIconButton(onClick = { projectsMenu = true }) { Icon(Icons.Outlined.List, "Filter lists and projects") }; DropdownMenu(projectsMenu, { projectsMenu = false }) {
                        DropdownMenuItem(text = { Text("All lists & projects") }, onClick = { project = null; projectsMenu = false })
                        projects.forEach { p -> DropdownMenuItem(text = { Text(p.record.data.text("title")) }, onClick = { project = p.record.id; projectsMenu = false }) }
                    } }
                }
                if (page == "Focus") Text("Ranked by priority, time and effort. Tap a task for the calculation.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp))
                val filtered=workspaceTasks(state.cache,page,project,query,today,now.epochSeconds)
                if(page=="Notes")NoteRows(rows,query){selected=it}
                else                 LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                    if (filtered.isEmpty()) item { Text("No tasks in this view. Capture a task without creating a project first.", Modifier.padding(vertical = 24.dp)) }
                    items(filtered, key = { it.record.id }) { task -> TaskCard(task,state.cache) { selected = task.record.id } }
                }
            }
        }
    }
    }
    if (selected != null && current == null) AlertDialog(onDismissRequest = { selected = null }, title = { Text("Item removed") }, text = { Text("Retained edits are available under Retained changes.") }, confirmButton = { SecondaryButton(onClick = { selected = null; changes = true }) { Text("Review") } })
    if (addProject) TextDialog("New project", "Project name", { addProject = false }) { title -> model.enqueue("project.create", model.newId(), obj("title" to title.j()), null); addProject = false; model.launch { model.sync() } }
    if (addList) TextDialog("New list","List name",{addList=false}) { title->model.enqueue("list.create",model.newId(),obj("title" to title.j()),null);addList=false;model.launch { model.sync() } }
    if (addNote) TextDialog("New note","Note title",{addNote=false},maxLength=240) { title->val id=model.newId();model.enqueue("note.create",id,obj("title" to title.j()),null);addNote=false;selected=id;page="Notes";model.launch { model.sync() } }
    if(addTask)NewTaskDialog(model,state,project,page=="My tasks"){addTask=false}
    editingContainer?.let { c->ContainerEditor(model,state,c){editingContainer=null} }
    if (addEvent) EventEditor(model, state, null, today, { addEvent = false })
    if (endpoint) TextDialog("Server endpoint", "Host:UDP port", { endpoint = false }, confirmLabel = "Update", initial = state.cache.config?.text("endpoint") ?: "", maxLength = 260) { model.updateEndpoint(it); endpoint = false; model.launch { model.sync() } }
    if (changes) ChangesSheet(model, state, { changes = false }, copy)
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max=720.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Settings",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            SecondaryButton(onClick={settings=false}){Text("Close")}
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ConnectionControls(model,state); Text("${actor.name} · ${actor.role}"); Text("Server: ${state.cache.config?.text("endpoint")}")
            SecondaryButton(onClick = { endpoint = true },modifier=Modifier.fillMaxWidth()) { Text("Edit server endpoint") }
            if(device.androidDelivery)AppUpdatesCard(device)
            if(device.androidDelivery){
                Text("Alerts and home screen",style=MaterialTheme.typography.titleMedium)
                Text(device.status,style=MaterialTheme.typography.bodySmall)
                state.error?.let { ErrorText(it) }
                SettingSwitch("Live background updates", "On by default. Keeps a private connection with an ongoing Android notification and can use more battery.",state.cache.preferences.liveUpdates){val p=state.cache.preferences.copy(liveUpdates=it);model.launch { model.preferences(p);background(p) }}
                SecondaryButton(onClick=device.testAlert,modifier=Modifier.fillMaxWidth()){Text("Play a sound test now")}
                SecondaryButton(onClick=device.profileTest,modifier=Modifier.fillMaxWidth()){Text("Test in another profile (30 s)")}
                SecondaryButton(onClick=device.notificationSettings,modifier=Modifier.fillMaxWidth()){Text("Notification sound settings")}
                SecondaryButton(onClick=device.alarmSettings,modifier=Modifier.fillMaxWidth()){Text("Allow precise reminders")}
                SecondaryButton(onClick=device.batterySettings,modifier=Modifier.fillMaxWidth()){Text("Background battery settings")}
                SecondaryButton(onClick={profileHelp=true},modifier=Modifier.fillMaxWidth()){Text("Alerts in other GrapheneOS profiles")}
            }
            SettingSwitch("Background catch-up", "Android schedules checks at 15-minute minimum intervals. Doze, force-stop and VPN policy can delay them.", state.cache.preferences.backgroundSync) { val p = state.cache.preferences.copy(backgroundSync = it); model.launch { model.preferences(p); background(p) } }
            SettingSwitch("Task and calendar reminders", "Cached due/all-day alerts at 09:00 and timed events 10 minutes before. Offline reminders may be stale.", state.cache.preferences.reminders) { val p = state.cache.preferences.copy(reminders = it); model.launch { model.preferences(p); background(p) } }
            SettingSwitch("Titles in home-screen widgets", "Off by default. Enabling shares title copies with your launcher; a lock cannot erase copies it has cached. Notes and images stay private.", state.cache.preferences.widgetTitles) { val p = state.cache.preferences.copy(widgetTitles = it); model.launch { model.preferences(p); background(p) } }
            Text("Add the Field Notes widget from your launcher's widget picker. It opens this locked app and refreshes from the encrypted local cache.", style = MaterialTheme.typography.bodySmall)
            if(device.androidDelivery){SecondaryButton(onClick=device.addUpcomingWidget,modifier=Modifier.fillMaxWidth()){Text("Add Upcoming to home screen")};SecondaryButton(onClick={removeInstallation=true},modifier=Modifier.fillMaxWidth(),dangerous=true){Text("Revoke this installation and uninstall")}}
            Spacer(Modifier.height(24.dp))
        }
        }
    }
    if(profileHelp)AlertDialog(onDismissRequest={profileHelp=false},title={Text("GrapheneOS profile alerts")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Leave the Field Notes profile running and allow it to run in the background. In that profile, enable GrapheneOS notification forwarding to the active profile.")
        Text("Use the 30-second profile test, switch profiles before it fires, then check the receiving profile's forwarded-notification sound and Do Not Disturb settings. Forwarding hides task contents.")
        Text("End session, force-stop and a powered-off phone stop delivery. Home-screen widgets belong to the profile where they are installed.")
    }},confirmButton={SecondaryButton(onClick=device.profileSettings){Text("Open Android settings")}},dismissButton={SecondaryButton(onClick={profileHelp=false}){Text("Close")}})
    if(removeInstallation)ConfirmDialog("Revoke this installation?","Sync or review retained work first. This revokes this device on your server, clears its local connection, stops background work and opens Android's uninstall screen. Shared household records remain on the server. Direct Android uninstall cannot contact the server; revoke its entry in Management if you uninstall that way.","REVOKE",{removeInstallation=false}){model.launch { device.removeInstallation() };removeInstallation=false}
}
@Composable internal fun TaskCard(task: VisibleRecord, cache: Cache, open: () -> Unit) {
    val d = task.record.data
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        if(d.text("status") !in setOf("done","in_progress")) Box(Modifier.size(18.dp).border(1.5.dp,MaterialTheme.colorScheme.primary,CircleShape))
        else Icon(if(d.text("status")=="done")Icons.Outlined.Done else Icons.Outlined.PlayArrow,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
            Text(d.text("title"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(buildList { add(priorityName(d.number("priority", 3).toInt())); if (d.text("due").isNotBlank()) add("Due ${d.text("due")}") else add("No due date"); if (task.record.flag("blocked")) add("Blocked"); if (task.record.flag("pinned")) add("Pinned"); if (task.pending) add("Queued"); add(if(d.text("assignee").isBlank())"Unassigned" else "For "+personName(cache,d.text("assignee"))); if (d.text("claimant").isNotBlank()) add("Working: "+personName(cache,d.text("claimant"))) }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.KeyboardArrowRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }; HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
@Composable private fun TaskScreen(model: Workspace, state: WorkspaceState, visible: VisibleRecord, back: () -> Unit, attach: (String) -> Unit, image: @Composable (VisibleRecord) -> Unit) {
    val task = visible.record
    val draft = state.cache.drafts[task.id]
    var title by remember(task.id) { mutableStateOf(draft?.data?.text("title") ?: task.data.text("title")) }
    var notes by remember(task.id) { mutableStateOf(draft?.data?.text("notes") ?: task.data.text("notes")) }
    var due by remember(task.id) { mutableStateOf(draft?.data?.text("due") ?: task.data.text("due")) }
    var container by remember(task.id) { mutableStateOf((draft?.data?:task.data).text("project_id")) };var containerMenu by remember { mutableStateOf(false) }
    var priority by remember(task.id) { mutableStateOf((draft?.data ?: task.data).number("priority", 3).toInt()) }
    var planned by remember(task.id) { mutableStateOf((draft?.data ?: task.data).text("planned_for")) }
    var effort by remember(task.id) { mutableStateOf((draft?.data ?: task.data).number("estimate_minutes").toString()) }
    var pinned by remember(task.id) { mutableStateOf((draft?.data ?: task.data)["pinned"] == JsonPrimitive(true)) }
    var blocked by remember(task.id) { mutableStateOf((draft?.data ?: task.data)["blocked"] == JsonPrimitive(true)) }
    var assignee by remember(task.id) { mutableStateOf((draft?.data?:task.data).text("assignee")) };var assigneeMenu by remember { mutableStateOf(false) }
    var revision by remember(task.id) { mutableStateOf(draft?.revision ?: task.revision) }
    val basis = remember(task.id) { draft?.let { Basis(it.epoch, it.generation) } ?: state.cache.snapshot!!.basis }
    val staleWorkspace = basis != state.cache.snapshot!!.basis
    var saving by remember { mutableStateOf(false) }
    var dirty by remember(task.id) { mutableStateOf(draft != null) }
    var comment by rememberSaveable(task.id) { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    val writable = state.cache.snapshot!!.actor.role != "viewer"
    fun fields():JsonObject {
        val data=obj("title" to title.j(),"notes" to notes.j(),"due" to due.j(),"project_id" to container.j(),"priority" to priority.j(),"planned_for" to planned.j(),"estimate_minutes" to (effort.toIntOrNull()?:0).j(),"pinned" to JsonPrimitive(pinned),"blocked" to JsonPrimitive(blocked))
        return if(state.cache.people.isEmpty())data else JsonObject(data+("assignee" to if(assignee.isBlank())JsonNull else assignee.j()))
    }
    fun retain() { dirty = true; val captured = fields(); val base = revision; model.launch { model.saveDraft(task.id, base, captured, basis) } }
    LaunchedEffect(draft?.revision) { if (dirty && draft != null && draft.dependsOn == null) revision = draft.revision }
    LaunchedEffect(task.revision, visible.pending) {
        if (!dirty && !visible.pending && !staleWorkspace) { container=task.data.text("project_id"); title = task.data.text("title"); notes = task.data.text("notes"); due = task.data.text("due"); assignee = task.data.text("assignee"); priority = task.data.number("priority", 3).toInt(); planned = task.data.text("planned_for"); effort = task.data.number("estimate_minutes").toString(); pinned = task.flag("pinned"); blocked = task.flag("blocked"); revision = task.revision }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(if (visible.pending) "Pending confirmation" else "Task details") }, navigationIcon = { OutlinedIconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.error?.let { message->item { ErrorText(message) } }
            if (staleWorkspace) item { ErrorText("The server was reset or restored. Reopen this task before making new changes; your old drafts are retained.") }
            item { OutlinedTextField(title, { if (it.length <= 240) { title = it; retain() } }, Modifier.fillMaxWidth(), label = { Text("Task title") }, enabled = writable && !saving && !staleWorkspace) }
            item { Box {
                SecondaryButton(onClick={assigneeMenu=true},enabled=writable&&!saving&&!staleWorkspace&&state.cache.people.isNotEmpty()&&task.data.text("status")!="in_progress"){Text("Assigned to: "+if(assignee.isBlank())"Unassigned" else personName(state.cache,assignee))}
                DropdownMenu(assigneeMenu,{assigneeMenu=false}){
                    DropdownMenuItem(text={Text("Unassigned")},onClick={assignee="";assigneeMenu=false;retain()})
                    state.cache.people.filter { it.can_assign }.forEach { person->DropdownMenuItem(text={Text(person.name)},onClick={assignee=person.id;assigneeMenu=false;retain()}) }
                }
            };if(task.data.text("status")=="in_progress")Text("Release the task before changing its assignment.",style=MaterialTheme.typography.bodySmall)
              if(state.cache.people.isEmpty())Text("Update the server to enable assignments.",style=MaterialTheme.typography.bodySmall) }
            item { OutlinedTextField(notes, { if (it.length <= 16384) { notes = it; retain() } }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 5, enabled = writable && !saving && !staleWorkspace) }
            item { Box { SecondaryButton(onClick={containerMenu=true},enabled=writable&&!staleWorkspace){Text("Keep in: "+(workspaceContainers(state.cache).firstOrNull { it.record.id==container }?.record?.data?.text("title")?:"Inbox"))};DropdownMenu(containerMenu,{containerMenu=false}){DropdownMenuItem(text={Text("Inbox")},onClick={container="";containerMenu=false;retain()});workspaceContainers(state.cache).forEach { c->DropdownMenuItem(text={Text(c.record.data.text("title")+" · "+c.record.kind)},onClick={container=c.record.id;containerMenu=false;retain()})}} } }
            item { DateField("Due date", due, writable && !saving && !staleWorkspace) { due = it; retain() } }
            item { DateField("Planned day", planned, writable && !saving && !staleWorkspace) { planned = it; retain() } }
            item { Text("Priority", style = MaterialTheme.typography.labelLarge); FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..4).forEach { p -> FilterChip(selected = priority == p, onClick = { priority = p; retain() }, label = { Text(priorityName(p)) }, enabled = writable && !saving && !staleWorkspace) } } }
            item { OutlinedTextField(if (effort == "0") "" else effort, { if (it.length <= 3 && (it.isEmpty() || it.toIntOrNull() in 0..480)) { effort = it; retain() } }, Modifier.fillMaxWidth(), label = { Text("Estimate · minutes (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = writable && !saving && !staleWorkspace) }
            item { SettingSwitch("Pin in Focus", "Pinned tasks come before the score order.", pinned) { if (writable && !staleWorkspace) { pinned = it; retain() } }; SettingSwitch("Blocked", "Hide from Focus until unblocked.", blocked) { if (writable && !staleWorkspace) { blocked = it; retain() } } }
            item { val rank = rankTask(task, Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date, Clock.System.now().epochSeconds, state.cache.snapshot.actor.user_id); Text("Saved Focus score ${rank.score}" + if (!rank.eligible) " · excluded from Focus" else "", style = MaterialTheme.typography.titleSmall); Text(rank.reasons.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
            if (dirty && revision != task.revision && !visible.pending) item {
                OutlinedCard { Column(Modifier.padding(16.dp)) {
                    Text("This task changed on another device. Your draft is kept.", fontWeight = FontWeight.Medium)
                    Text("Server title: ${task.data.text("title")}\nAssigned to: ${task.data.text("assignee").let { if(it.isBlank())"Unassigned" else personName(state.cache,it) }}\nServer notes: ${task.data.text("notes")}", Modifier.padding(vertical = 12.dp))
                    SecondaryButton(onClick = { container=task.data.text("project_id"); title = task.data.text("title"); notes = task.data.text("notes"); due = task.data.text("due"); assignee = task.data.text("assignee"); priority = task.data.number("priority", 3).toInt(); planned = task.data.text("planned_for"); effort = task.data.number("estimate_minutes").toString(); pinned = task.flag("pinned"); blocked = task.flag("blocked"); revision = task.revision; dirty = false; model.launch { model.discardDraft(task.id) } }) { Text("Use server version") }
                    SecondaryButton(onClick = { revision = task.revision; retain() }) { Text("Keep draft for a new save") }
                } }
            }
            if (writable) item { Button(onClick = { val data = fields(); val base = revision; saving = true; model.launch { try { model.saveDraft(task.id, base, data, basis); model.submitDraft(task.id); dirty = false } finally { saving = false }; model.sync() } }, enabled = title.isNotBlank() && !saving && !staleWorkspace) { Icon(Icons.Outlined.Done, null); Spacer(Modifier.width(8.dp)); Text("Save details") } }
            item { Text(when { visible.pending -> "This change is provisional until the server accepts it."; task.data.text("claimant").isNotBlank() -> "This task is claimed. Server authorization decides who can release or finish it."; else -> "Status: ${task.data.text("status").replace('_', ' ')}" }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if(task.data.text("assignee").isNotBlank()&&task.data.text("assignee")!=state.cache.snapshot.actor.user_id&&task.data.text("status")=="todo")item { Text("Reassign this task to yourself before starting.",style=MaterialTheme.typography.bodySmall) }
            if(task.data.text("status")=="done"&&task.data.text("completed_by").isNotBlank())item { Text("Completed by "+personName(state.cache,task.data.text("completed_by")),style=MaterialTheme.typography.bodySmall) }
            if (writable && !staleWorkspace) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val status = task.data.text("status")
                if (status == "todo" && !task.flag("blocked") && (task.data.text("assignee").isBlank() || task.data.text("assignee")==state.cache.snapshot.actor.user_id)) SecondaryButton(onClick = { model.enqueueAsync("task.start", task.id, obj(), task.revision, basis) }) { Text("Start task") }
                if (status == "in_progress") SecondaryButton(onClick = { model.enqueueAsync("task.release", task.id, obj(), task.revision, basis) }) { Text("Release") }
                SecondaryButton(onClick = { model.enqueueAsync(if (status == "done") "task.reopen" else "task.complete", task.id, obj(), task.revision, basis) }) { Text(if (status == "done") "Reopen" else "Complete") }
                SecondaryButton(onClick = { delete = true },dangerous=true) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            } }
            item { HorizontalDivider(); Text("Images", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp)) }
            val images = projectRecords(state.cache).filter { it.record.kind == "image" && it.record.data.text("task_id") == task.id }
            items(images, key = { it.record.id }) { row -> image(row) }
            if (writable) item { SecondaryButton(onClick = { attach(task.id) }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Attach image") } }
            item { HorizontalDivider(); Text("Conversation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp)) }
            val comments = projectRecords(state.cache).filter { it.record.kind == "comment" && it.record.data.text("task_id") == task.id }
            items(comments, key = { it.record.id }) { c -> Card { Column(Modifier.fillMaxWidth().padding(15.dp)) {
                Text(c.record.data.text("author") + if (c.pending) " · pending" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(c.record.data.text("body"), Modifier.padding(top = 7.dp))
                if (!c.pending && writable && (state.cache.snapshot.actor.role in setOf("owner", "admin") || c.record.data.text("author_id") == state.cache.snapshot.actor.user_id)) SecondaryButton(dangerous=true,onClick = { model.enqueueAsync("comment.delete", c.record.id, obj(), c.record.revision, basis) }) { Text("Remove") }
            } } }
            if (writable) item { Column { OutlinedTextField(comment, { if (it.length <= 8192) comment = it }, Modifier.fillMaxWidth(), label = { Text("Add a comment") }, minLines = 2)
                SecondaryButton(onClick = { val text = comment; model.launch { model.enqueue("comment.create", model.newId(), obj("task_id" to task.id.j(), "body" to text.j()), null, basis); comment = ""; model.sync() } }, enabled = comment.isNotBlank()) { Text("Post comment") }
            } }
            item { Spacer(Modifier.height(25.dp)) }
        }
    }
    if (delete) ConfirmDialog("Delete this task?", "Comments and images will also be removed from active data. Existing backups are unchanged.", "DELETE", { delete = false }) {
        model.enqueueAsync("task.delete", task.id, obj(), task.revision, basis); delete = false; back()
    }
}

@Composable private fun ChangesSheet(model: Workspace, state: WorkspaceState, close: () -> Unit, copy: (String) -> Unit) {
    var discard by remember { mutableStateOf<String?>(null) }; var discardAll by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = close) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.85f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Retained changes", style = MaterialTheme.typography.headlineSmall); Text("These drafts stay encrypted on this device. Reapply only after reviewing the current server state.") }
            if (state.cache.outbox.isNotEmpty()) item {
                Text("${state.cache.outbox.size} queued changes", fontWeight = FontWeight.Medium)
                Text("After restore or revocation, copy retained text before enrolling again. Pending image bytes remain in the encrypted cache and are not included in text copies.", style = MaterialTheme.typography.bodySmall)
                SecondaryButton(onClick = { copy(state.cache.outbox.joinToString("\n\n") { p -> p.command.action + " · " + p.command.target + "\n" + JsonObject(p.command.data - "bytes") }) }) { Text("Copy queued text") }
            }
            items(state.cache.conflicts.size) { index -> val conflict = state.cache.conflicts[index]
                Card { Column(Modifier.padding(16.dp)) {
                    Text(conflict.reason, fontWeight = FontWeight.Medium)
                    val printable = JsonObject(conflict.command.data - "bytes").toString()
                    Text(printable, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        SecondaryButton(onClick = { copy(printable) }) { Text("Copy text") }
                        SecondaryButton(onClick = { model.launch { model.resolve(conflict.command.operation_id, true); model.sync() } }) { Text("Reapply") }
                        SecondaryButton(onClick = { discard = conflict.command.operation_id }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    }
                } }
            }
            state.cache.drafts.values.forEach { draft -> item(draft.target) { Card { Column(Modifier.padding(16.dp)) {
                Text("Unsaved details", fontWeight = FontWeight.Medium); Text(draft.data.toString(), Modifier.padding(vertical = 10.dp))
                SecondaryButton(onClick = { copy(draft.data.toString()) }) { Text("Copy text") }
                SecondaryButton(onClick = { model.launch { model.submitDraft(draft.target); model.sync() } }) { Text("Submit draft") }
                SecondaryButton(onClick = { model.launch { model.discardDraft(draft.target) } }) { Text("Discard local draft") }
            } } } }
            if (state.cache.conflicts.isEmpty() && state.cache.drafts.isEmpty()) item { Text("Everything is in a good place. No retained changes.") }
            if (state.cache.outbox.isNotEmpty() || state.cache.conflicts.isNotEmpty() || state.cache.drafts.isNotEmpty()) item { SecondaryButton(onClick = { discardAll = true }) { Text("Discard all retained local changes", color = MaterialTheme.colorScheme.error) } }
            item { SecondaryButton(onClick = close) { Text("Close") } }
        }
    }
    if (discardAll) ConfirmDialog("Discard all retained changes?", "Copy unsynchronized text first. Pending image uploads will also be lost. An attempted command may already exist on the server; this only discards local retry/draft copies.", "DISCARD", { discardAll = false }) { model.launch { model.discardRetainedChanges() }; discardAll = false }
    discard?.let { id -> ConfirmDialog("Discard this retained change?", "Only this local copy will be removed. It cannot be recovered from the server if it was never accepted.", "DISCARD", { discard = null }) { model.launch { model.resolve(id, false) }; discard = null } }
}
@Composable private fun Brand() { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.List, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text("Field Notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) } }
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
        Button(onClick = { busy = true; error = null; scope.launch { try { submit(text.trim()) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "The change could not be saved." } finally { busy = false } } }, enabled = text.isNotBlank() && !busy) { Text(confirmLabel) }
    }, dismissButton = { SecondaryButton(onClick = close, enabled = !busy) { Text("Cancel") } })
}
@Composable private fun ConfirmDialog(title: String, detail: String, expected: String, close: () -> Unit, extra:@Composable ()->Unit = {}, submit: () -> Unit) { var text by rememberSaveable { mutableStateOf("") }; AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Column { Text(detail); OutlinedTextField(text, { text = it }, label = { Text("Type $expected") });extra() } }, confirmButton = { Button(onClick = submit, enabled = text == expected,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError)) { Text("Confirm") } }, dismissButton = { SecondaryButton(onClick = close) { Text("Cancel") } }) }

@Composable private fun ConnectionControls(model:Workspace,state:WorkspaceState){
    Text(if(state.connectionPaused)"Private connection paused" else "Private connection",style=MaterialTheme.typography.titleMedium)
    Text("Pausing keeps pairing keys, queued edits and cached reminders. Already transmitted requests may have reached home.",style=MaterialTheme.typography.bodySmall)
    SecondaryButton(onClick={model.launch { if(state.connectionPaused)model.resumeConnection() else model.pauseConnection() }}){Text(if(state.connectionPaused)"Resume saved connection" else "Pause private connection")}
}
