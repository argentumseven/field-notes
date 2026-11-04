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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
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
    deviceActions:DeviceActions=DeviceActions(), requestedPage:String?=null, requestedDate:String?=null,requestedRecord:String?=null,navigationSequence:Int=0,
    invitationCode: String? = null, enrollmentPolling: Boolean = true, scanInvitation: () -> Unit = {}, chooseInvitationImage: () -> Unit = {}, invitationScanError:String?=null, invitationSequence:Int=0,
) {
    val state by model.state.collectAsState()
    FieldNotesTheme(state.cache.preferences.theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                !state.ready -> Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.Center) { Text("Opening encrypted workspace"); state.error?.let { ErrorText(it); Button(onClick = { model.launch { model.initialize() } }) { Text("Retry") }; if(deviceActions.androidDelivery)SecondaryButton(onClick=deviceActions.appSettings){Text("Android app settings and removal")} } ?: CircularProgressIndicator() }
                (state.cache.enrollment?.stage?.let { it != "sign_in" } == true) || state.cache.config == null -> EnrollmentScreen(model, state, invitationCode, scanInvitation, enrollmentPolling, invitationScanError, invitationSequence, copyText, chooseInvitationImage)
                state.needsLogin -> LoginScreen(model, state, copyText)
                state.cache.snapshot == null -> Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.Center) { Text("Connected. Download your workspace."); state.error?.let { ErrorText(it) }; Button(onClick = { model.launch { model.sync() } }) { Text("Sync workspace") };if(deviceActions.androidDelivery)AppUpdatesCard(deviceActions) }
                else -> WorkspaceScreen(model, state, copyText, attachImage, configureBackground, registerBack, imageContent,deviceActions,requestedPage,requestedDate,navigationSequence,requestedRecord)
            }
        }
    }
}
@Composable private fun EnrollmentScreen(model: Workspace, state: WorkspaceState, incoming: String?, scan: () -> Unit, polling: Boolean, scanError:String?, invitationSequence:Int, copy:(String)->Unit, chooseImage:()->Unit) {
    var raw by rememberSaveable { mutableStateOf("") }; var name by rememberSaveable { mutableStateOf("My phone") }
    var invitation by remember { mutableStateOf<Invitation?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    var trusted by rememberSaveable { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    val focus=LocalFocusManager.current
    val phoneField=remember { BringIntoViewRequester() }
    var nameFocused by remember { mutableStateOf(false) }
    val keyboardBottom=WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(nameFocused,keyboardBottom) { if(nameFocused) { withFrameNanos { };phoneField.bringIntoView() } }
    LaunchedEffect(incoming, invitationSequence) { incoming?.let {
        raw = it; trusted = false
        try { invitation = readInvitation(it); error = null }
        catch (failure:Exception) { invitation = null; error = failure.message ?: "This is not a valid Field Notes invitation." }
    } }
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
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Brand(); Text("Enroll this phone", style = MaterialTheme.typography.headlineSmall)
        (error ?: state.error)?.let { ErrorText(it) }
        if (pending == null) {
          if(invitation==null) {
            Text("On your PC, open Management → Devices. Paste this phone’s public key and choose your account to create its invitation. Then scan the QR here. The phone connects only through its restricted tunnel; management stays on the PC.")
            val public=state.cache.keys?.text("public_key").orEmpty()
            Text("This phone’s public key",style=MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(public,style=MaterialTheme.typography.bodySmall) }
            SecondaryButton(onClick={copy(public)},enabled=public.isNotEmpty()) { Text("Copy phone public key") }
            Button(onClick = { focus.clearFocus();scan() }, modifier = Modifier.fillMaxWidth().testTag("scan-invitation")) { Text("Scan invitation QR") }
            SecondaryButton(onClick={focus.clearFocus();chooseImage()},modifier=Modifier.fillMaxWidth()) { Text("Choose a QR image") }
            scanError?.let { ErrorText(it) }
            OutlinedTextField(raw, { raw = it; invitation = null; trusted = false }, Modifier.fillMaxWidth(), label = { Text("Or paste invitation") }, minLines = 2, maxLines = 4, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            SecondaryButton(onClick = { try { invitation = readInvitation(raw); error = null;focus.clearFocus() } catch (e: Exception) { error = e.message } }, enabled = raw.isNotBlank()) { Text("Review invitation") }
          }
            invitation?.let { invite ->
                Text("${invite.user} · ${invite.role}", style = MaterialTheme.typography.titleMedium)
                Text(if(invite.version==2)"Restricted tunnel · "+invite.tunnel!!.text("endpoint") else invite.url.substringBefore("/api/"), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.fillMaxWidth().bringIntoViewRequester(phoneField),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth().testTag("phone-name").onFocusChanged { nameFocused=it.isFocused }, label = { Text("Phone name") },singleLine=true,keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus()}))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(trusted, { trusted = it }); Text("This invitation came from my trusted home server.", Modifier.weight(1f)) }
                Button(onClick = { focus.clearFocus();busy = true; model.launch { try { model.beginEnrollment(invite, name); model.advanceEnrollment() } finally { busy = false } } }, enabled = trusted && name.isNotBlank() && !busy) { Text("Request enrollment") }
                }
                SecondaryButton(onClick={focus.clearFocus();invitation=null;trusted=false;error=null}) { Text("Use a different invitation") }
            }
        } else {
            Text("${pending.name} · ${pending.invitation.user}")
            Text(if(pending.invitation.version==2)"Connecting only to the private task API through WireGuard." else "This saved attempt uses legacy LAN enrollment. Management access is not required by new tunnel invitations.",style=MaterialTheme.typography.bodySmall)
            Text(if(state.connectionPaused)"Pairing paused on this phone. Saved credentials are kept; the server invitation can still expire." else when(pending.stage) { "claiming" -> "Contacting your home server…"; "pending_approval" -> "Ask your administrator to enter this phone's code."; "provisioning" -> "Approved. Waiting for the server to install your tunnel peer."; "ready", "activating" -> "Verifying access through your private tunnel…"; else -> pending.stage })
            if (pending.verification.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) { Text(pending.verification.chunked(4).joinToString(" "), Modifier.padding(24.dp).testTag("enrollment-verification"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold) }
            Text("Keep this screen open. Approval expires after 10 minutes. The code proves which phone you are approving; never enter a code supplied by someone else.", style = MaterialTheme.typography.bodySmall)
            SecondaryButton(onClick = { model.launch { model.resumeConnection(); model.advanceEnrollment(); if(model.state.value.cache.enrollment==null)model.sync() } }) { Text("Retry saved pairing") }
            if(!state.connectionPaused)SecondaryButton(onClick={model.launch { model.pauseConnection() }}){Text("Pause pairing")}
            if (pending.activationSecret != null) SecondaryButton(onClick = { model.launch { model.resumeConnection(); model.enrollmentSignIn(true) } }) { Text("Recover an activated phone with password") }
            SecondaryButton(onClick = { model.launch { model.cancelEnrollment() } }) { Text("Cancel invitation on server") }
            if(pending.invitation.expires<=Clock.System.now().epochSeconds && pending.activationSecret==null && state.cache.config==null)SecondaryButton(onClick={model.launch { model.forgetExpiredEnrollment() }}) { Text("Use a fresh invitation · keep phone keys") }
        }
        HorizontalDivider(); Text("Your private key stays here. Field Notes uses its own WireGuard connection to the notes endpoint and keeps Android's VPN slot available.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun LoginScreen(model: Workspace, state: WorkspaceState, copy: (String) -> Unit) {
    var review by remember { mutableStateOf(false) }; var reset by remember { mutableStateOf(false) }; var newKeys by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(state.cache.snapshot?.actor?.name ?: "") }; var password by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(26.dp), verticalArrangement = Arrangement.Center) {
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
@Composable private fun WorkspaceScreen(model: Workspace, state: WorkspaceState, copy: (String) -> Unit, attach: (String) -> Unit, background: (Preferences) -> Unit, registerBack: ((() -> Unit)?) -> Unit, image: @Composable (VisibleRecord) -> Unit,device:DeviceActions,requestedPage:String?,requestedDate:String?,navigationSequence:Int,requestedRecord:String?) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }; var page by rememberSaveable { mutableStateOf("Calendar") }
    var calendarDay by rememberSaveable { mutableStateOf(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()) }
    LaunchedEffect(requestedDate,navigationSequence) { requestedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull()?.let { day -> calendarDay=day.toString();page="Calendar" } } }
    var query by rememberSaveable { mutableStateOf("") }; var project by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }; var projectsMenu by remember { mutableStateOf(false) }
    var addTask by remember { mutableStateOf(false) }; var addProject by remember { mutableStateOf(false) }; var addList by remember { mutableStateOf(false) }; var addNote by remember { mutableStateOf(false) }; var editingContainer by remember { mutableStateOf<Record?>(null) }; var addEvent by remember { mutableStateOf(false) };var eventStartClock by remember { mutableStateOf<String?>(null) }
    var changes by remember { mutableStateOf(false) }; var settings by remember { mutableStateOf(false) }; var endpoint by remember { mutableStateOf(false) }
    LaunchedEffect(requestedPage,navigationSequence) {
        if(requestedPage=="Settings"){settings=true;selected=null}
        if(requestedPage in listOf("Focus","Tasks","Calendar","Assignments","Notes")) {
            page=requestedPage!!;project=null;query=""
            // Retain an open event form; task/note drafts already persist independently.
            if(state.cache.snapshot?.records?.find { it.id==selected }?.kind!="event")selected=null
        }
    }
    LaunchedEffect(requestedRecord,navigationSequence){if(requestedRecord!=null&&projectRecords(state.cache).any { it.record.id==requestedRecord })selected=requestedRecord}
    var removeInstallation by remember { mutableStateOf(false) };var profileHelp by remember { mutableStateOf(false) }
    val rows = remember(state.cache) { projectRecords(state.cache) }; val projects = workspaceContainers(state.cache)
    val drawer=rememberDrawerState(DrawerValue.Closed);val uiScope=rememberCoroutineScope()
    fun navigate(view:String,container:String?=null){page=view;project=container;query="";selected=null;uiScope.launch { drawer.close() }}
    val actor = state.cache.snapshot!!.actor; val writable = actor.role != "viewer"
    val current = rows.firstOrNull { it.record.id == selected }
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) { while(true) { delay(60_000);now=Clock.System.now() } }
    val zone = TimeZone.currentSystemDefault()
    val today = now.toLocalDateTime(zone).date
    var previousToday by rememberSaveable { mutableStateOf(today.toString()) }
    LaunchedEffect(today){if(calendarDay==previousToday)calendarDay=today.toString();previousToday=today.toString()}
    DisposableEffect(selected) { registerBack(if (selected != null) ({ selected = null }) else null); onDispose { registerBack(null) } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); model.clearError() } }
    if (selected != null && current?.record?.kind == "event") EventEditor(model, state, current.record, today, device.use24Hour,device.previewSound) { selected = null }
    if (selected != null && current?.record?.kind == "note") NoteEditor(model,state,current,{selected=null})
    else if (selected != null && current?.record?.kind == "task") {
        val list=projects.find { it.record.id==current.record.data.text("project_id")&&it.record.kind=="list" }?.record
        if(list!=null)ListItemDialog(model,state,list,current,device,attach,image){selected=null}
        else TaskScreen(model,state,current,{selected=null},attach,image,device)
    }
    else ModalNavigationDrawer(drawerState=drawer,drawerContent={ModalDrawerSheet { Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("Workspace",Modifier.padding(14.dp),style=MaterialTheme.typography.titleLarge)
        DrawerGroup("Plan",listOf("Calendar","Tasks","Focus"),page,navigate={navigate(it)})
        DrawerGroup("Library",listOf("Lists","Projects","Notes"),page,navigate={navigate(it)})
        DrawerGroup("Task views",listOf("Inbox","My tasks","Assignments","Unassigned","Unscheduled","Waiting","Completed"),page,initiallyOpen=false,navigate={navigate(it)})

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
        listOf("Calendar" to Icons.Outlined.DateRange, "Tasks" to Icons.Outlined.List, "Lists" to Icons.Outlined.List).forEach { (label, icon) -> NavigationBarItem(modifier=Modifier.testTag("nav-"+label.lowercase()),selected = page == label, onClick = { navigate(label) }, icon = { Icon(if(label == "Focus") Icons.Outlined.PlayArrow else icon, null) }, label = { Text(label) }) }
    } }, floatingActionButton = { if (writable) ExtendedFloatingActionButton(onClick = { if (page == "Calendar") {eventStartClock=null;addEvent = true} else if(page=="Notes")addNote=true else if(page=="Lists"||page=="Collections")addList=true else if(page=="Projects")addProject=true else addTask = true }, icon={ Icon(Icons.Outlined.Add, "Add") }, text={ Text(when(page) { "Calendar" -> "New event"; "Notes" -> "New note"; "Collections","Lists" -> "New list"; "Projects" -> "New project"; else -> if(projects.find { it.record.id==project }?.record?.kind=="list")"Add item" else "New task" }) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            if(page!="Calendar")Text(projects.firstOrNull { it.record.id==project }?.record?.data?.text("title")?:page, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Text(when { state.connectionPaused -> "Connection paused · cached work and reminders remain available"; state.syncing -> "Synchronizing…"; state.online -> "Synced · ${state.cache.outbox.size} queued"; else -> "Offline · ${state.cache.outbox.size} queued · starts provisional" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(!state.cache.preferences.visibilityChoiceSeen&&(!state.cache.preferences.notificationTitles||!state.cache.preferences.widgetTitles))OutlinedCard(Modifier.fillMaxWidth().padding(vertical=6.dp)) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("Make your schedule visible at a glance",style=MaterialTheme.typography.titleSmall)
                Text("Show full reminder and widget details while unlocked. Lock-screen visibility and the optional app lock remain separate.",style=MaterialTheme.typography.bodySmall)
                Button(onClick={model.launch{model.preferences(state.cache.preferences.copy(notificationTitles=true,widgetTitles=true,visibilityChoiceSeen=true))}}){Text("Show reminder and widget details")}
                TextButton(onClick={model.launch{model.preferences(state.cache.preferences.copy(visibilityChoiceSeen=true))}}){Text("Keep current visibility")}
            }}
            val issue=state.problem ?: device.warning.takeIf{it.isNotBlank()} ?: device.update.message.takeIf{device.update.problem} ?: reminderCompatibilityWarning(state.cache)
            val compat=state.cache.compatibility
            if(issue!=null||compat?.let{it.blocked||it.warning.isNotBlank()||it.version=="legacy"||it.recommendedClient>CURRENT_CLIENT_CODE}==true)OutlinedCard(Modifier.fillMaxWidth().padding(vertical=6.dp)) {Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(if(issue!=null)"Needs attention" else "Version compatibility",style=MaterialTheme.typography.titleSmall)
                Text(issue ?: compat!!.message,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={settings=true}){Text("Review settings and updates")}
            }}
            if (state.cache.conflicts.isNotEmpty()) SecondaryButton(onClick = { changes = true }) { Text("${state.cache.conflicts.size} changes need review") }
            if(device.update.available)SecondaryButton(onClick={settings=true}){Text("App update ${device.update.offered} available")}
            if (page == "Calendar") PlannerCalendar(rows, today, zone, calendarDay, { calendarDay=it }, {clock->eventStartClock=clock;addEvent=true}, { selected = it })
            else if(page=="Assignments") {
                OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(top=12.dp),label={Text("Find assigned work")},singleLine=true)
                AssignmentRows(state.cache,query){selected=it}
            }
            else if(page in setOf("Collections","Lists","Projects")) {
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=90.dp)) { for(kind in (if(page=="Lists")listOf("list") else if(page=="Projects")listOf("project") else listOf("list","project"))){item { Text(if(kind=="list")"Lists" else "Projects",Modifier.padding(vertical=16.dp),style=MaterialTheme.typography.titleMedium) };items(projects.filter { it.record.kind==kind },key={it.record.id}) { c->CollectionRow(state.cache,c,writable,{navigate("Tasks",c.record.id)},{editingContainer=c.record}) } } }
            }
            else if(projects.any { it.record.id==project&&it.record.kind=="list" }) {
                val list=projects.first { it.record.id==project }.record
                Checklist(model,state,list,device,image,{selected=it},{editingContainer=list})
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
                if(page=="Notes")NoteRows(state.cache,rows,query){selected=it}
                else                 LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                    if (filtered.isEmpty()) item { Text("No tasks in this view. Capture a task without creating a project first.", Modifier.padding(vertical = 24.dp)) }
                    items(filtered, key = { it.record.id }) { task -> TaskCard(task,state.cache) { selected = task.record.id } }
                }
            }
        }
    }
    }
    if (selected != null && current == null) AlertDialog(onDismissRequest = { selected = null }, title = { Text("Item removed") }, text = { Text("Retained edits are available under Retained changes.") }, confirmButton = { SecondaryButton(onClick = { selected = null; changes = true }) { Text("Review") } })
    if (addProject) CollectionDialog(model,state,null,"project",{addProject=false})
    if(addList)CollectionDialog(model,state,null,"list",{addList=false})
    if(addNote)NewNoteDialog(model,state){addNote=false;page="Notes";query=""}
    if(addTask) {
        val list=projects.find { it.record.id==project&&it.record.kind=="list" }?.record
        if(list!=null)ListItemDialog(model,state,list,null,device,close={addTask=false}) else NewTaskDialog(model,state,project,page=="My tasks",device){addTask=false}
    }
    editingContainer?.let { c->CollectionDialog(model,state,c,c.kind){editingContainer=null} }
    if (addEvent) EventEditor(model, state, null, LocalDate.parse(calendarDay), device.use24Hour,device.previewSound,eventStartClock) { addEvent = false }
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
            if(device.androidDelivery)SettingSwitch("Automatically prepare app updates","Download and verify updates from this paired server in the background. Android still asks before installation; interrupted transfers resume.",state.cache.preferences.autoDownloadUpdates){val p=state.cache.preferences.copy(autoDownloadUpdates=it);model.launch{model.preferences(p)}}
            if(device.androidDelivery){
                Text("Alerts and home screen",style=MaterialTheme.typography.titleMedium)
                Text(device.status,style=MaterialTheme.typography.bodySmall)
                state.error?.let { ErrorText(it) }
                SettingSwitch("Live background updates", "On by default. Keeps a private connection with an ongoing Android notification and can use more battery.",state.cache.preferences.liveUpdates){val p=state.cache.preferences.copy(liveUpdates=it);model.launch { model.preferences(p);background(p) }}
                SecondaryButton(onClick=device.testAlert,modifier=Modifier.fillMaxWidth()){Text("Play a sound test now")}
                SecondaryButton(onClick=device.profileTest,modifier=Modifier.fillMaxWidth()){Text("Test in another profile (30 s)")}
                SecondaryButton(onClick=device.notificationSettings,modifier=Modifier.fillMaxWidth()){Text("Notification sound settings")}
                SecondaryButton(onClick=device.recheckDelivery,modifier=Modifier.fillMaxWidth()){Text("Check reminder delivery again")}
                Text("Loud alarm reminders",style=MaterialTheme.typography.titleMedium)
                SettingSwitch("Allow temporary alarm-volume control","For alarm reminders, raise and restore this phone's alarm volume. Manual changes take priority. Stop the alarm before force-stop or direct uninstall; Android cannot run restoration afterward.",state.cache.preferences.allowAlarmVolumeOverride){val p=state.cache.preferences.copy(allowAlarmVolumeOverride=it);model.launch{model.preferences(p)}}
                SecondaryButton(onClick=device.testLoudAlarm,modifier=Modifier.fillMaxWidth(),enabled=state.cache.preferences.allowAlarmVolumeOverride){Text("Test a loud alarm (10 s)")}
                SecondaryButton(onClick=device.stopAlarm,modifier=Modifier.fillMaxWidth()){Text("Stop alarm and restore volume")}
                SecondaryButton(onClick=device.soundSettings,modifier=Modifier.fillMaxWidth()){Text("Android alarm volume")}
                SecondaryButton(onClick=device.dndSettings,modifier=Modifier.fillMaxWidth()){Text("Do Not Disturb exceptions")}
                SecondaryButton(onClick=device.alarmSettings,modifier=Modifier.fillMaxWidth()){Text("Allow precise reminders")}
                SecondaryButton(onClick=device.batterySettings,modifier=Modifier.fillMaxWidth()){Text("Background battery settings")}
                SecondaryButton(onClick={profileHelp=true},modifier=Modifier.fillMaxWidth()){Text("Alerts in other GrapheneOS profiles")}
            }
            Text("Appearance",style=MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(theme in listOf("system","light","dark"))FilterChip(state.cache.preferences.theme==theme,{model.launch { model.preferences(state.cache.preferences.copy(theme=theme)) }},{Text(theme.replaceFirstChar { it.uppercase() })})}
            SettingSwitch("Auto-refresh while open","Check for household changes without pressing refresh.",state.cache.preferences.autoRefresh){val p=state.cache.preferences.copy(autoRefresh=it);model.launch{model.preferences(p)}}
            Text("Visibility and app lock",style=MaterialTheme.typography.titleMedium)
            Text("App locking, notification details and home-screen details are independent.",style=MaterialTheme.typography.bodySmall)
            SettingSwitch("Require a credential to open the app","Optional device-credential check. Reminders and widgets keep their own visibility choices.",state.cache.preferences.appLock){val p=state.cache.preferences.copy(appLock=it);model.launch{model.preferences(p)}}
            if(state.cache.preferences.appLock)FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(minutes in listOf(1,5,15,30))FilterChip(state.cache.preferences.lockMinutes==minutes,{model.launch{model.preferences(state.cache.preferences.copy(lockMinutes=minutes))}},{Text("$minutes min")})}
            SettingSwitch("Detailed reminder notifications","Show the title, schedule, person, notes and activity in the notification shade.",state.cache.preferences.notificationTitles){val p=state.cache.preferences.copy(notificationTitles=it,visibilityChoiceSeen=true);model.launch{model.preferences(p)}}
            SettingSwitch("Notification details on the lock screen","Show full reminder contents while this profile's device is locked. Android's lock-screen settings also apply.",state.cache.preferences.notificationOnLockScreen){val p=state.cache.preferences.copy(notificationOnLockScreen=it,notificationTitles=it||state.cache.preferences.notificationTitles);model.launch{model.preferences(p)}}
            SettingSwitch("Background catch-up", "Android schedules checks at 15-minute minimum intervals. Doze, force-stop and VPN policy can delay them.", state.cache.preferences.backgroundSync) { val p = state.cache.preferences.copy(backgroundSync = it); model.launch { model.preferences(p); background(p) } }
            SettingSwitch("Task and calendar reminders", "Use each item’s Reminders controls for multiple alerts, recurring windows and sound. Cached reminders also work offline.", state.cache.preferences.reminders) { val p = state.cache.preferences.copy(reminders = it); model.launch { model.preferences(p); background(p) } }
            SettingSwitch("Show schedules in home-screen widgets", "Display event and task titles without opening Field Notes. Your launcher receives those title copies.", state.cache.preferences.widgetTitles) { val p = state.cache.preferences.copy(widgetTitles = it,visibilityChoiceSeen=true); model.launch { model.preferences(p); background(p) } }
            SettingSwitch("Widget details while the device is locked","Keep schedule details visible on a locked device; launchers can retain previously displayed copies.",state.cache.preferences.widgetOnLockScreen){val p=state.cache.preferences.copy(widgetOnLockScreen=it,widgetTitles=it||state.cache.preferences.widgetTitles);model.launch{model.preferences(p)}}
            SettingSwitch("Include everyone's appointments in widgets","Off shows your reminders. On shows the shared household calendar, without sending you their alarms.",state.cache.preferences.widgetHousehold){val p=state.cache.preferences.copy(widgetHousehold=it);model.launch{model.preferences(p)}}
            Text("Add a Field Notes widget from your launcher's picker. It refreshes from the encrypted local cache; an optional app lock does not hide its schedule.", style = MaterialTheme.typography.bodySmall)
            if(device.androidDelivery){SecondaryButton(onClick=device.addWeekWidget,modifier=Modifier.fillMaxWidth()){Text("Add Week to home screen")};SecondaryButton(onClick=device.addCalendarWidget,modifier=Modifier.fillMaxWidth()){Text("Add Calendar to home screen")};SecondaryButton(onClick=device.addUpcomingWidget,modifier=Modifier.fillMaxWidth()){Text("Add Upcoming to home screen")};SecondaryButton(onClick={removeInstallation=true},modifier=Modifier.fillMaxWidth(),dangerous=true){Text("Revoke this installation and uninstall")}}
            Spacer(Modifier.height(24.dp))
        }
        }
    }
    if(profileHelp)AlertDialog(onDismissRequest={profileHelp=false},title={Text("GrapheneOS profile alerts")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Leave the Field Notes profile running and allow it to run in the background. In that profile, enable GrapheneOS notification forwarding to the active profile.")
        Text("Use the 30-second profile test, switch profiles before it fires, then check the receiving profile's forwarded-notification sound and Do Not Disturb settings. Forwarding hides task contents.")
        Text("Forwarded alerts use the receiving profile’s Other users system channel, which controls their sound and volume. Per-event tones apply in the profile running Field Notes.")
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
            Text(buildList { add(priorityName(d.number("priority", 3).toInt())); if (d.text("due").isNotBlank()) add("Due ${d.text("due")}"); if (task.record.flag("blocked")) add("Blocked"); if (task.record.flag("pinned")) add("Pinned"); if (task.pending) add("Queued"); if(d.text("assignee").isNotBlank())add("For "+personName(cache,d.text("assignee"))); if (d.text("claimant").isNotBlank()) add("Working: "+personName(cache,d.text("claimant"))) }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(recordHistory(cache,task.record,TimeZone.currentSystemDefault()),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.KeyboardArrowRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }; HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
@Composable private fun TaskScreen(model: Workspace, state: WorkspaceState, visible: VisibleRecord, back: () -> Unit, attach: (String) -> Unit, image: @Composable (VisibleRecord) -> Unit,device:DeviceActions=DeviceActions()) {
    var reminderDialog by remember { mutableStateOf(false) }
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
            item { Text(recordHistory(state.cache,task,TimeZone.currentSystemDefault()),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);SecondaryButton(onClick={reminderDialog=true}){Text("Reminders & sound")} }
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
    if(reminderDialog)TaskReminderDialog(model,state,task,device){reminderDialog=false}
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
@Composable private fun Brand() { Text("Field Notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
@Composable private fun ErrorText(text: String) { Text(text, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error) }
@Composable private fun SettingSwitch(title: String, detail: String, enabled: Boolean, change: (Boolean) -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(detail, style = MaterialTheme.typography.bodySmall) }; Switch(enabled, change,modifier=Modifier.semantics{contentDescription=title}) } }
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
@Composable private fun ConfirmDialog(title: String, detail: String, expected: String, close: () -> Unit, extra:@Composable ()->Unit = {}, submit: () -> Unit) {
    AlertDialog(onDismissRequest=close,title={Text(title)},text={Column { Text(detail);extra() }},confirmButton={Button(onClick=submit,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError)){Text(when(expected){"DELETE"->"Delete";"REVOKE"->"Revoke and remove";"REENROLL"->"Set up again";else->"Discard"})}},dismissButton={SecondaryButton(onClick=close){Text("Keep it")}})
}

@Composable private fun ConnectionControls(model:Workspace,state:WorkspaceState){
    Text(if(state.connectionPaused)"Private connection paused" else "Private connection",style=MaterialTheme.typography.titleMedium)
    Text("Pausing keeps pairing keys, queued edits and cached reminders. Already transmitted requests may have reached home.",style=MaterialTheme.typography.bodySmall)
    SecondaryButton(onClick={model.launch { if(state.connectionPaused)model.resumeConnection() else model.pauseConnection() }}){Text(if(state.connectionPaused)"Resume saved connection" else "Pause private connection")}
}
