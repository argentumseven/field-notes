@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

@Composable internal fun CollectionRow(cache:Cache,visible:VisibleRecord,writable:Boolean,open:()->Unit,edit:()->Unit) {
    val record=visible.record
    OutlinedCard(onClick=open,modifier=Modifier.fillMaxWidth().padding(bottom=10.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            RecordBadges(record)
            Text(record.data.text("title"),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(record.kind=="project") {
                val progress=cache.projectGraph().rollup(record,Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString())
                Text(if(record.data.text("status")=="archived")"Finished project · ${progress.done}/${progress.total} tasks retained" else "${progress.done}/${progress.total} tasks · ${projectStateLabel(record,progress)} · ${progress.overdue} overdue",style=MaterialTheme.typography.bodyMedium)
            }else if(record.data.text("project_id").isNotBlank())Text("In "+(cache.projectGraph().project(record)?.data?.text("title") ?: "Project"),style=MaterialTheme.typography.labelMedium)
            if(record.data.text("notes").isNotBlank())Text(record.data.text("notes"),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(recordHistory(cache,record,TimeZone.currentSystemDefault(),compact=true),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(writable)SecondaryButton(onClick=edit){Text(if(record.kind=="list")"List settings / delete" else "Project settings / delete")}
        }
    }
}

@Composable internal fun CollectionDialog(model:Workspace,state:WorkspaceState,record:Record?,kind:String,close:()->Unit,initialProject:String?=null,initialWork:Boolean=false,onCreated:((String)->Unit)?=null) {
    var title by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("title") ?: "")}
    var notes by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("notes") ?: "")}
    var template by rememberSaveable(record?.id){mutableStateOf(if(record?.flag("items_are_tasks")==true||record==null&&initialWork)"tasks" else record?.data?.text("template","general") ?: "general")}
    var customize by rememberSaveable { mutableStateOf(record?.data?.text("template")=="custom") }
    var choices by remember { mutableStateOf(record?.let(::listFields) ?: defaultListFields(template)) }
    var category by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("category","general") ?: state.cache.projectGraph().byId[initialProject]?.data?.text("category","general") ?: "general")}
    var parent by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("project_id") ?: initialProject.orEmpty())}
    var workItems by rememberSaveable(record?.id){mutableStateOf(record?.flag("items_are_tasks") ?: initialWork)}
    var coordinator by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("assignee") ?: "")}
    var targetDay by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("due") ?: "")}
    var projectStatus by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("status","active") ?: "active")}
    var notifyItems by rememberSaveable(record?.id){mutableStateOf(record?.data?.get("notify_additions")!=JsonPrimitive(false))};var itemReminders by rememberSaveable(record?.id){mutableStateOf(record?.data?.get("item_reminders")!=JsonPrimitive(false))}
    var perpetual by rememberSaveable { mutableStateOf(record?.data?.get("perpetual")!=JsonPrimitive(false)) }
    var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) };var remove by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope();val basis=remember { state.cache.snapshot!!.basis }
    val descendants=remember(record?.id,state.cache) {
        val records=projectRecords(state.cache).map{it.record}
        val pending=mutableListOf(record?.id.orEmpty());val seen=mutableSetOf(record?.id.orEmpty());val found=mutableListOf<Record>()
        while(pending.isNotEmpty()) {
            val parentId=pending.removeAt(pending.lastIndex)
            for(item in records.filter{it.data.text("project_id")==parentId||it.data.text("task_id")==parentId}) {
                if(seen.add(item.id)){found.add(item);pending.add(item.id)}
            }
        }
        found
    }
    var removalMode by remember(record?.id){mutableStateOf("detach")}
    var deleteProof by remember(record?.id){mutableStateOf("")}
    EditorDialog(onDismissRequest=close,title={Text(if(record==null)"New $kind" else "Edit $kind")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title,{title=it.take(160)},label={Text(if(kind=="list")"List name" else "Project name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(notes,{notes=it.take(16384)},label={Text("Description")},minLines=2,modifier=Modifier.fillMaxWidth())
        if(state.cache.supportsPlannerV3())CategoryPicker(category){category=it}
        if(state.cache.supportsProjects()) {
            if(kind=="project") {
                ViewChoice("Project coordinator",coordinator,listOf("" to "No coordinator")+state.cache.people.filter {it.can_assign||it.id==coordinator}.map {it.id to it.name}){coordinator=it}
                DateField("Project target date · optional",targetDay){targetDay=it}
                ViewChoice("Project state",projectStatus,listOf("active" to "Active","on_hold" to "On hold","archived" to "Finished")){projectStatus=it}
            } else {
                ViewChoice("Group under a project",parent,listOf("" to "Standalone list")+workspaceContainers(state.cache).filter{it.record.kind=="project"}.map{it.record.id to it.record.data.text("title")}){parent=it}
                Row(verticalAlignment=Alignment.CenterVertically){Switch(workItems,{workItems=it});Text("Items are work tasks",Modifier.padding(start=8.dp))}
                Text("Work tasks appear in Tasks and count toward project progress. Reference and shopping checklists keep their items in the list.",style=MaterialTheme.typography.bodySmall)
            }
        }
        if(kind=="list") {
            Text("List type",style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for((key,label) in listTemplates.filterKeys{it!="tasks"||state.cache.supportsProjects()})FilterChip(template==key,{template=key;workItems=key=="tasks";choices=defaultListFields(key);customize=key=="custom";if(category=="general"&&key=="groceries")category="food"},{Text(label)})}
            if(template=="shopping")Row(verticalAlignment=Alignment.CenterVertically){Checkbox("price" in choices,{choices=if(it)choices+"price" else choices-"price"});Text("Include a price field")}
            SecondaryButton(onClick={customize=!customize}){Text("Customize fields (${choices.size}) "+if(customize)"▴" else "▾")}
            if(customize)Text("Fields on each item",style=MaterialTheme.typography.titleSmall)
            Text("Item name and checked state are always included. Field choices change what you see, not existing values or reminders.",style=MaterialTheme.typography.bodySmall)
            if(customize)FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for((field,label) in listFieldLabels.filterKeys{it!="price"||template!="shopping"})FilterChip(field in choices,{choices=if(field in choices)choices-field else choices+field},{Text(label)})}
            Row(verticalAlignment=Alignment.CenterVertically){Switch(perpetual,{perpetual=it});Text("Rolling list · keep using it",Modifier.padding(start=8.dp))}
            Text("Check items off, add more whenever you need, and clear only the checked items.",style=MaterialTheme.typography.bodySmall)
            if(state.cache.supportsPlannerV3()){Row(verticalAlignment=Alignment.CenterVertically){Switch(notifyItems,{notifyItems=it});Text("Notify when items are added",Modifier.padding(start=8.dp))};if(template!="groceries")Row(verticalAlignment=Alignment.CenterVertically){Switch(itemReminders,{itemReminders=it});Text("Scheduled item reminders",Modifier.padding(start=8.dp))};Text("Muting keeps saved settings, list contents and sync intact.",style=MaterialTheme.typography.bodySmall)}
        }
        if(descendants.isNotEmpty())Text("Deletion offers a choice to preserve or remove this collection's contents.",style=MaterialTheme.typography.bodySmall)
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={Button(shape=ControlShape,enabled=title.isNotBlank()&&!busy,onClick={busy=true;scope.launch { try {
        if(record!=null&&kind=="project"&&state.cache.supportsProjects()) {
            val progress=state.cache.projectGraph().rollup(record,Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString())
            require(projectStatus!="archived"||progress.open==0){"Finish or move unfinished work before finishing this project."}
            require(projectStatus!="on_hold"||progress.working==0){"Release or finish work in progress before putting this project on hold."}
        }
        if(record?.flag("items_are_tasks")==true&&!workItems)require(projectRecords(state.cache).none{it.record.data.text("project_id")==record.id&&it.record.data.text("status")=="in_progress"}){"Release or finish active work before converting this task list to a reference checklist."}
        val reminderSetting=if(template=="groceries")record?.data?.get("item_reminders") ?: JsonPrimitive(false) else JsonPrimitive(itemReminders)
        val extra=if(state.cache.supportsPlannerV3())mapOf("category" to category.j())+(if(kind=="list")mapOf("notify_additions" to JsonPrimitive(notifyItems),"item_reminders" to reminderSetting) else emptyMap()) else emptyMap()
        val projectExtra=if(state.cache.supportsProjects())if(kind=="project")mapOf("status" to projectStatus.j(),"due" to targetDay.j(),"assignee" to coordinator.j()) else mapOf("project_id" to parent.j(),"items_are_tasks" to JsonPrimitive(workItems)) else emptyMap()
        val fields=JsonObject(mapOf("title" to title.j(),"notes" to notes.j())+(if(kind=="list")listConfiguration(template,choices,perpetual) else emptyMap())+extra+projectExtra)
        val id=record?.id ?: model.newId()
        model.enqueue(kind+if(record==null)".create" else ".update",id,fields,record?.revision,basis);model.requestSync();close();if(record==null)onCreated?.invoke(id)
    }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}finally{busy=false} }}){Text(if(record==null)"Create $kind" else "Save changes")}},dismissButton={Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(record!=null)SecondaryButton(onClick={remove=true;deleteProof="";removalMode=if(kind=="project")"detach" else "cascade"},enabled=!busy){Text("Delete",color=MaterialTheme.colorScheme.error)}
        SecondaryButton(onClick=close,enabled=!busy){Text("Cancel")}
    }})
    if(remove&&record!=null)EditorDialog(onDismissRequest={remove=false},title={Text("Delete ${record.data.text("title")}?")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(descendants.isEmpty())"This empty $kind will be removed from active content. It does not need to be finished first."
                else "This $kind contains ${descendants.size} linked records. Choose whether to preserve or remove its contents.")
            if(descendants.isNotEmpty()){
                if(kind=="project")ViewChoice("Contents",removalMode,listOf("detach" to "Keep tasks and lists (move to Inbox/standalone)","cascade" to "Delete project and all linked content")){removalMode=it}
                else Text("All list items and their attachments will also be removed from active content.")
            }
            Text("History, retained versions and backups are governed by separate retention settings.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(deleteProof,{deleteProof=it},label={Text("Type DELETE to confirm")},singleLine=true)
        }
    },confirmButton={Button(shape=ControlShape,enabled=deleteProof=="DELETE",onClick={
        val payload=if(descendants.isEmpty())obj() else obj("mode" to removalMode.j(),"contents" to JsonArray(descendants.map{obj("id" to it.id.j(),"revision" to it.revision.j())}))
        model.enqueueAsync("$kind.delete",record.id,payload,record.revision,basis);remove=false;close()
    }){Text("Delete $kind")}},dismissButton={SecondaryButton(onClick={remove=false}){Text("Keep it")}})
}

@Composable internal fun NewNoteDialog(model:Workspace,state:WorkspaceState,initialRadar:Boolean=false,device:DeviceActions=DeviceActions(),recipe:Boolean=false,close:()->Unit) {
    var ingredients by rememberSaveable{mutableStateOf("")};var instructions by rememberSaveable{mutableStateOf("")};var extra by rememberSaveable{mutableStateOf(false)}
    var onRadar by rememberSaveable { mutableStateOf(initialRadar) };var remindAt by rememberSaveable { mutableStateOf(0L) };var category by rememberSaveable{mutableStateOf(if(recipe)"food" else "general")}
    var title by rememberSaveable { mutableStateOf("") };var body by rememberSaveable { mutableStateOf("") };var pinned by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) };var busy by remember { mutableStateOf(false) };val scope=rememberCoroutineScope();val basis=remember { state.cache.snapshot!!.basis }
    EditorDialog(onDismissRequest=close,title={Text(if(recipe)"New recipe" else if(initialRadar)"New reminder note" else "New note")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title,{title=it.take(240)},label={Text(if(recipe)"Recipe name" else "Note title")},modifier=Modifier.fillMaxWidth())
        if(recipe){
            OutlinedTextField(ingredients,{ingredients=it.take(16384)},label={Text("Ingredients")},minLines=3,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(instructions,{instructions=it.take(16384)},label={Text("Cooking instructions")},minLines=4,modifier=Modifier.fillMaxWidth())
        }
        OutlinedTextField(body,{body=it.take(16384)},label={Text(if(recipe)"Notes · optional" else "Content")},minLines=if(recipe)2 else 6,modifier=Modifier.fillMaxWidth())
        SecondaryButton(onClick={extra=!extra},modifier=Modifier.fillMaxWidth()){Text(if(extra)"Fewer options ▴" else "More options ▾")}
        if(extra){
        if(state.cache.supportsPlannerV3()){CategoryPicker(category){category=it};FollowUpControls(onRadar,remindAt,device.use24Hour){radar,at->onRadar=radar;remindAt=at}}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(pinned,{pinned=it});Text("Pin this note")}
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={Button(shape=ControlShape,enabled=title.isNotBlank()&&!busy,onClick={busy=true;scope.launch { try { model.enqueue("note.create",model.newId(),JsonObject(obj("title" to title.j(),"body" to body.j(),"pinned" to JsonPrimitive(pinned))+(if(recipe)mapOf("note_type" to "recipe".j(),"ingredients" to ingredients.j(),"instructions" to instructions.j())else emptyMap())+(if(state.cache.supportsPlannerV3())mapOf("category" to category.j(),"on_radar" to JsonPrimitive(onRadar),"remind_at" to remindAt.j()) else emptyMap())),null,basis);model.requestSync();close() }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}finally{busy=false} }}){Text(if(recipe)"Create recipe" else "Create note")}},dismissButton={SecondaryButton(onClick=close){Text("Cancel")}})
}

@Composable internal fun ColumnScope.Checklist(model:Workspace,state:WorkspaceState,list:Record,device:DeviceActions,image:@Composable (VisibleRecord)->Unit,open:(String)->Unit,edit:()->Unit,history:(()->Unit)?=null,storeChanged:(String)->Unit={},header:@Composable ()->Unit={}) {
    val rows=projectRecords(state.cache);val all=rows.filter { it.record.kind=="task"&&it.record.data.text("project_id")==list.id }
    val fields=listFields(list);val writable=state.cache.snapshot!!.actor.role!="viewer"
    val finished=list.flag("items_are_tasks")&&state.cache.projectGraph().project(list)?.data?.text("status")=="archived"
    var query by rememberSaveable(list.id){mutableStateOf("")};var store by rememberSaveable(list.id){mutableStateOf("*")};var itemCategoryFilter by rememberSaveable(list.id){mutableStateOf("all")};var sort by rememberSaveable(list.id){mutableStateOf("title")};var status by rememberSaveable(list.id){mutableStateOf("open")}
    var confirmAction by remember { mutableStateOf<String?>(null) };var changingChecked by remember { mutableStateOf(false) }
    var filtersOpen by rememberSaveable(list.id){mutableStateOf(false)}
    var descriptionOpen by rememberSaveable(list.id){mutableStateOf(false)}
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var removing by remember {mutableStateOf<Pair<Basis,Record>?>(null)}
    var checkedSelection by remember { mutableStateOf<Pair<Basis,List<Record>>?>(null) }
    LaunchedEffect(fields,store){storeChanged(if("store" in fields&&store!="*")store else "");if("store" !in fields){store="*"};if(sort !in setOf("title","added","updated","item_category")+fields.filter{it !in setOf("notes","images","store","link_url")})sort="title"}
    val stores=all.map { storeName(it.record) }.distinct().sortedWith(compareBy { it.lowercase() })
    val matching=all.filter { row->val d=row.record.data;itemCategoryMatches(d.text("item_category"),itemCategoryFilter)&&("store" !in fields||store=="*"||storeName(row.record)==store)&&(listOf(d.text("title"),d.text("notes"),d.text("store"),d.text("quantity"),d.text("link_url"),d.text("price"),d.text("item_category")).joinToString(" ").contains(query,true)) }
    val filtered=matching.filter { status=="all"||((it.record.data.text("status")=="done")==(status=="done")) }
    val comparator=Comparator<VisibleRecord> { a,b->
        val x=a.record.data;val y=b.record.data
        val order=when(sort){"added"->y.number("created_at").compareTo(x.number("created_at"));"updated"->y.number("last_activity_at",y.number("updated_at")).compareTo(x.number("last_activity_at",x.number("updated_at")));"price"->x.text("currency","USD").compareTo(y.text("currency","USD")) .takeIf { it!=0 } ?: (x.text("price").toDoubleOrNull() ?: Double.POSITIVE_INFINITY).compareTo(y.text("price").toDoubleOrNull() ?: Double.POSITIVE_INFINITY);"priority","estimate_minutes"->x.number(sort).compareTo(y.number(sort));else->x.text(sort).ifBlank { "~" }.compareTo(y.text(sort).ifBlank { "~" },true)}
        order.takeIf { it!=0 } ?: x.text("title").compareTo(y.text("title"),true).takeIf { it!=0 } ?: a.record.id.compareTo(b.record.id)
    }
    val sorted=filtered.sortedWith(comparator);val checked=matching.filter { it.record.data.text("status")=="done" }
    val groups=if("store" in fields&&store=="*")sorted.groupBy { storeName(it.record) }.toList().sortedBy { it.first.lowercase() }.toMap() else mapOf("" to sorted)
    VisibleLazyColumn(Modifier.weight(1f).testTag("checklist-items"),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=100.dp),hideKeyboardOnDrag=true) {
        item("workspace-header"){Column {header()}}
        item("list-controls"){Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            RecordBadges(list)
            if(finished)Text("Project finished. Reopen it before adding or reopening work.",style=MaterialTheme.typography.bodyMedium)
            if(list.data.text("notes").isNotBlank()) {
                Text(list.data.text("notes"),style=MaterialTheme.typography.bodyMedium,maxLines=if(descriptionOpen)Int.MAX_VALUE else 2,overflow=TextOverflow.Ellipsis)
                if(list.data.text("notes").length>80||list.data.text("notes").contains('\n'))SecondaryButton(onClick={descriptionOpen=!descriptionOpen}){Text(if(descriptionOpen)"Less description ▴" else "Full description ▾")}
            }
            OutlinedTextField(query,{query=it},label={Text("Search titles and item content")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus();keyboard?.hide()}),modifier=Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if("store" in fields)ViewChoice("Store",store,listOf("*" to "All stores")+stores.map { it to it.ifBlank { "No store yet" } }){store=it;storeChanged(if(it=="*")"" else it)}
                SecondaryButton(onClick={filtersOpen=!filtersOpen}){Text("List options"+if(filtersOpen)" ▴" else " ▾")}
            }
            if(filtersOpen)FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                ViewChoice("Show",status,listOf("open" to "Unchecked","all" to "All items","done" to "Checked")){status=it}
                ViewChoice("Item category",itemCategoryFilter,itemCategoryFilters(all.map{it.record},itemCategoryFilter)){itemCategoryFilter=it}
                ViewChoice("Sort",sort,listOf("title" to "Name A–Z","added" to "Recently added","updated" to "Recently changed","item_category" to "Item category")+fields.filter { it !in setOf("notes","images","store","link_url") }.map { it to listFieldLabels.getValue(it) }){sort=it}
                if(writable)SecondaryButton(onClick=edit){Text("List settings")}
                if(history!=null)SecondaryButton(onClick=history){Text("List history")}
                if(writable) {
                    if(!finished&&list.data["perpetual"]!=JsonPrimitive(false))SecondaryButton(onClick={checkedSelection=state.cache.snapshot!!.basis to checked.map{it.record};confirmAction="reuse"},enabled=checked.isNotEmpty()){Text("Reuse checked (${checked.size})")}
                    SecondaryButton(onClick={checkedSelection=state.cache.snapshot!!.basis to checked.map{it.record};confirmAction="clear"},enabled=checked.isNotEmpty(),dangerous=true){Text("Delete checked (${checked.size})")}
                }
            }
            if(query.isNotBlank()||store!="*"||status!="open"||itemCategoryFilter!="all")SecondaryButton(onClick={query="";store="*";status="open";itemCategoryFilter="all";storeChanged("")}){Text("Clear filters")}
            Text("${sorted.size} of ${all.size} items · "+when(status){"all"->"All items";"done"->"Checked";else->"${all.count { it.record.data.text("status")!="done" }} unchecked"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(filtersOpen&&(store!="*"||query.isNotBlank()))Text("Cleanup applies to this search and ${store.ifBlank{"No store yet"}.takeIf{it!="*"} ?: "all stores"}, including hidden checked items.",style=MaterialTheme.typography.labelSmall)
            if(list.data["notify_additions"]==JsonPrimitive(false)||list.data.text("template")!="groceries"&&list.data["item_reminders"]==JsonPrimitive(false))Text(listOfNotNull(if(list.data["notify_additions"]==JsonPrimitive(false))"Item-addition alerts muted" else null,if(list.data.text("template")!="groceries"&&list.data["item_reminders"]==JsonPrimitive(false))"Scheduled item reminders muted" else null).joinToString(" · "),style=MaterialTheme.typography.labelSmall)
            if(!filtersOpen&&itemCategoryFilter!="all")Text("Showing category: "+(if(itemCategoryFilter=="none")"Uncategorized" else itemCategoryFilter.removePrefix("value:")),style=MaterialTheme.typography.labelSmall)
        }}
        if(sorted.isEmpty())item { Text(when{all.isEmpty()->"Your list is ready. Add an item to get started.";matching.isEmpty()->"No items match these search, store or item category filters. Use Clear filters to return to the list.";status=="open"->"Everything in this view is checked. Show checked items or use Delete checked to clean up.";else->"No checked items in this view. Choose Unchecked to continue."},Modifier.padding(vertical=28.dp),color=MaterialTheme.colorScheme.onSurfaceVariant) }
        for((name,items) in groups){
            if("store" in fields&&store=="*")item("store:$name"){Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=2.dp)){Text(name.ifBlank { "No store yet" },Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Text(items.size.toString(),style=MaterialTheme.typography.labelMedium)}}
            items(items,key={it.record.id}){row->
                val d=row.record.data;val done=d.text("status")=="done"
                OutlinedCard(onClick={focus.clearFocus();open(row.record.id)},modifier=Modifier.fillMaxWidth().testTag("checklist-row-"+row.record.id)) { Row(Modifier.padding(horizontal=6.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(done,{model.enqueueAsync(if(done)"task.reopen" else "task.complete",row.record.id,obj(),row.record.revision)},enabled=writable&&!finished&&(d.text("claimant").isBlank()||d.text("claimant")==state.cache.snapshot.actor.user_id))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(d.text("title"),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis,color=if(done)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        val details=buildList { itemCategory(row.record).takeIf{it.isNotBlank()}?.let{add(it)};if("quantity" in fields&&d.text("quantity").isNotBlank())add(d.text("quantity"));if("store" in fields&&storeName(row.record).isNotBlank())add(storeName(row.record));if("price" in fields&&priceLabel(d).isNotBlank())add(priceLabel(d));if("link_url" in fields&&d.text("link_url").isNotBlank())add(d.text("link_url").substringAfter("://").substringBefore('/'));if(row.pending)add("Queued") }
                        if(details.isNotEmpty())Text(details.joinToString(" · "),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.primary)
                        if("notes" in fields&&d.text("notes").isNotBlank())Text(d.text("notes"),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(recordHistory(state.cache,row.record,TimeZone.currentSystemDefault(),compact=true,use24Hour=device.use24Hour),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if("images" in fields)rows.firstOrNull { it.record.kind=="image"&&it.record.data.text("task_id")==row.record.id }?.let { attachment ->Box(Modifier.padding(start=6.dp).size(48.dp).clip(RoundedCornerShape(8.dp))){image(attachment)} }
                    if(writable)OutlinedIconButton(shape=ControlShape,onClick={removing=state.cache.snapshot!!.basis to row.record}){Icon(Icons.Outlined.Delete,"Delete item "+d.text("title"),tint=MaterialTheme.colorScheme.error)}
                } }
            }
        }
    }
    if(confirmAction!=null) {
        val reuse=confirmAction=="reuse"
        val selection=checkedSelection!!
        EditorDialog(onDismissRequest={if(!changingChecked)confirmAction=null},title={Text("${if(reuse)"Reuse" else "Delete"} ${selection.second.size} checked item${if(selection.second.size==1)"" else "s"}?")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(reuse)"Uncheck the completed items matching your search, store and item category to use the list again. Notes, quantities, stores, images and history are kept. Changed items stay for review." else "Only these checked items matching your search, store and item category will be removed. Unchecked items and revision conflicts are kept.")
            Text(selection.second.take(5).joinToString(" · "){it.data.text("title")})
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }},confirmButton={Button(shape=ControlShape,enabled=!changingChecked,onClick={changingChecked=true;model.launch {try {if(reuse)model.reuseCheckedItems(selection.second,selection.first)else model.clearCheckedItems(selection.second,selection.first);confirmAction=null;model.sync()}finally{changingChecked=false}}}){Text(if(reuse)"Uncheck and reuse" else "Delete checked")}},dismissButton={SecondaryButton(enabled=!changingChecked,onClick={confirmAction=null}){Text("Keep items")}})
    }
    removing?.let{selection->RemoveItemDialog(model,selection,{removing=null},{removing=null})}
}

private val itemKeys=setOf("project_id","title","notes","quantity","store","category","item_category","link_url","price","currency","due","planned_for","priority","estimate_minutes","assignee","reminders","reminder_options")
private fun itemEditingData(data:JsonObject):JsonObject {
    val fields=data.filterKeys { it in itemKeys }
    return if(data.containsKey("reminder_timing_rules")||data.containsKey("reminder_rules"))JsonObject((fields-"reminders")+listOfNotNull((data["reminder_timing_rules"] ?: data["reminder_rules"])?.takeUnless { it==JsonNull }?.let { "reminders" to it }).toMap()) else JsonObject(fields)
}
private fun itemReminderRules(data:JsonObject,id:String)=data["reminders"]?.let { WireJson.decodeFromJsonElement<ReminderSet>(it) } ?: ReminderSet(if(data.text("due").isNotBlank())listOf(ReminderAlert(id,0)) else emptyList())
@Composable internal fun ListItemDialog(model:Workspace,state:WorkspaceState,list:Record,visible:VisibleRecord?,device:DeviceActions,attach:(String)->Unit={},image:@Composable (VisibleRecord)->Unit={},initialStore:String="",close:()->Unit) {
    val record=visible?.record;val draft=record?.let { state.cache.drafts[it.id] };val basis=remember(record?.id){draft?.let { Basis(it.epoch,it.generation) } ?: state.cache.snapshot!!.basis}
    val id=rememberSaveable(record?.id){record?.id ?: model.newId()}
    var dataJson by rememberSaveable(id){mutableStateOf(itemEditingData(draft?.data ?: record?.data ?: JsonObject(obj("title" to "".j(),"currency" to "USD".j())+(if(state.cache.supportsPlannerV3())mapOf("store" to initialStore.j(),"category" to list.data.text("category","general").j()) else emptyMap()))).toString())}
    var revision by rememberSaveable(id){mutableStateOf(draft?.revision ?: record?.revision ?: 0)}
    var dirty by rememberSaveable(id){mutableStateOf(draft!=null)};var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) };var comment by rememberSaveable(id){mutableStateOf("")}
    var removing by remember {mutableStateOf<Pair<Basis,Record>?>(null)}
    val data=WireJson.parseToJsonElement(dataJson).jsonObject;val fields=listFields(list);val scope=rememberCoroutineScope();val writable=state.cache.snapshot!!.actor.role!="viewer"&&basis==state.cache.snapshot.basis
    fun set(field:String,value:JsonElement) { val next=JsonObject(WireJson.parseToJsonElement(dataJson).jsonObject+(field to value));dataJson=next.toString();dirty=true;if(record!=null)model.launch { model.saveDraft(id,revision,next,basis) } }
    LaunchedEffect(record?.revision,visible?.pending){if(record!=null&&!dirty){dataJson=itemEditingData(record.data).toString();revision=record.revision}}
    EditorDialog(onDismissRequest=close,title={Text(if(record==null)"New item" else "List item")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(list.data.text("title"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
        SyncStatusLine(state)
        OutlinedTextField(data.text("title"),{set("title",it.take(240).j())},label={Text("Item name")},enabled=writable,modifier=Modifier.fillMaxWidth())
        if(record!=null)MembershipField(state.cache,data.text("project_id",record.data.text("project_id")),writable&&!busy){set("project_id",it.j())}
        val context=itemContext(data,list)
        if(state.cache.supportsPlannerV3()&&!context.scoped)CategoryPicker(data.text("category","general"),writable){set("category",it.j())}
        if(state.cache.supportsItemContexts()||data.containsKey("item_category"))ItemCategoryPicker(context,data.text("item_category"),writable&&state.cache.supportsItemContexts()){set("item_category",it.j())}
        else Text("Update the server to add item categories. Existing labels and content are kept.",style=MaterialTheme.typography.bodySmall)
        for((field,label) in listOf("quantity" to "Quantity","store" to "Store","link_url" to "Web link","price" to "Price"))if(field in fields) {
            OutlinedTextField(data.text(field),{set(field,it.take(if(field=="link_url")2048 else 80).j())},label={Text(label)},enabled=writable,singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=if(field=="price")KeyboardType.Decimal else if(field=="link_url")KeyboardType.Uri else KeyboardType.Text))
            if(field=="price")OutlinedTextField(data.text("currency","USD"),{set("currency",it.uppercase().take(3).j())},label={Text("Currency")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=writable)
            if(field=="link_url"&&data.text(field).startsWith("http"))SecondaryButton(onClick={device.openLink(data.text(field))}){Text("Open link in browser")}
        }
        if("notes" in fields)OutlinedTextField(data.text("notes"),{set("notes",it.take(16384).j())},label={Text("Notes")},minLines=3,enabled=writable,modifier=Modifier.fillMaxWidth())
        if("due" in fields)DateField("Due date · optional",data.text("due"),writable){set("due",it.j())}
        if("planned_for" in fields)DateField("Planned day · optional",data.text("planned_for"),writable){set("planned_for",it.j())}
        if("priority" in fields)FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){for(p in 1..4)FilterChip(data.number("priority",3).toInt()==p,{set("priority",p.j())},{Text(priorityName(p))},enabled=writable)}
        if("estimate_minutes" in fields)OutlinedTextField(data.number("estimate_minutes").takeIf { it>0 }?.toString() ?: "",{set("estimate_minutes",(it.toIntOrNull() ?: 0).j())},label={Text("Effort · minutes")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=writable)
        if("assignee" in fields) {
            var menu by remember { mutableStateOf(false) }
            Box { SecondaryButton(onClick={menu=true},enabled=writable){Text("Assigned to: "+data.text("assignee").takeIf { it.isNotBlank() }?.let { personName(state.cache,it) }.orEmpty().ifBlank { "Anyone" })};DropdownMenu(menu,{menu=false}){
                DropdownMenuItem(text={Text("Anyone")},onClick={set("assignee","".j());menu=false})
                state.cache.people.filter { it.can_assign }.forEach { p ->DropdownMenuItem(text={Text(p.name)},onClick={set("assignee",p.id.j());menu=false}) }
            } }
        }
        if("images" in fields) {
            if(record==null)Text("Create this item to attach an image. Link previews use only your attached images.",style=MaterialTheme.typography.bodySmall)
            else { projectRecords(state.cache).filter { it.record.kind=="image"&&it.record.data.text("task_id")==id }.forEach { image(it) };if(writable)SecondaryButton(onClick={attach(id)}){Text("Attach image")}}
        }
        val rules=itemReminderRules(data,id)
        if(writable&&list.data.text("template")!="groceries")ReminderEditor(rules,Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,model::newId,device.use24Hour,device.previewSound,
            reminderEditorSettings(model,state,reminderOptions(Record(id,"task",revision,data)) ?: ReminderOptions(),true,device){set("reminder_options",reminderOptionsData(it))}){set("reminders",reminderData(it))}
        if(record!=null) {
            Text(recordHistory(state.cache,record,TimeZone.currentSystemDefault()),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(writable){OutlinedTextField(comment,{comment=it.take(8192)},label={Text("Add a comment")},minLines=2);SecondaryButton(onClick={model.enqueueAsync("comment.create",model.newId(),obj("task_id" to id.j(),"body" to comment.j()),null,basis);comment=""},enabled=comment.isNotBlank()){Text("Post comment")}}
            projectRecords(state.cache).filter { it.record.kind=="comment"&&it.record.data.text("task_id")==id }.sortedBy { it.record.data.number("created_at") }.forEach { Text("${it.record.data.text("author")} · ${it.record.data.text("body")}",style=MaterialTheme.typography.bodySmall) }
            if(revision!=record.revision&&visible?.pending!=true) { Text("Changed elsewhere. Your draft is kept.",color=MaterialTheme.colorScheme.error);SecondaryButton(onClick={revision=record.revision}){Text("Keep my edits for a new save")};SecondaryButton(onClick={dataJson=itemEditingData(record.data).toString();revision=record.revision;dirty=false;model.launch{model.discardDraft(id)}}){Text("Use latest version")} }
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={if(writable)Button(shape=ControlShape,enabled=data.text("title").isNotBlank()&&!busy,onClick={busy=true;scope.launch { try {
        data["reminders"]?.let { validateReminders(WireJson.decodeFromJsonElement(it)) }
        val payload=if(state.cache.supportsRemindersV2())data["reminder_options"]?.let {
            val options=prunedReminderOptions(SyncJson.decodeFromJsonElement<ReminderOptions>(it),itemReminderRules(data,id));validateReminderOptions(options)
            JsonObject(data+("reminder_options" to reminderOptionsData(options)))
        } ?: data else JsonObject(data-"reminder_options")
        if(record==null)model.enqueue("task.create",id,JsonObject(payload+("project_id" to list.id.j())),null,basis)
        else { model.saveDraft(id,revision,payload,basis);model.submitDraft(id) }
        model.requestSync();close()
    }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}finally{busy=false} }}){Text(if(record==null)"Add item" else "Save item")}},dismissButton={Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        if(record!=null&&writable)SecondaryButton(onClick={removing=basis to record.copy(revision=revision)},enabled=!busy,dangerous=true){Text("Delete item")}
        SecondaryButton(onClick=close,enabled=!busy){Text("Close")}
    }})
    removing?.let{selection->RemoveItemDialog(model,selection,{removing=null},{removing=null;close()})}
}
