@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Text(record.data.text("title"),style=MaterialTheme.typography.titleMedium)
            if(record.data.text("notes").isNotBlank())Text(record.data.text("notes"),style=MaterialTheme.typography.bodySmall)
            Text(recordHistory(cache,record,TimeZone.currentSystemDefault()),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(writable)SecondaryButton(onClick=edit){Text(if(record.kind=="list")"List settings" else "Project settings")}
        }
    }
}

@Composable internal fun CollectionDialog(model:Workspace,state:WorkspaceState,record:Record?,kind:String,close:()->Unit) {
    var title by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("title") ?: "")}
    var notes by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("notes") ?: "")}
    var template by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("template","general") ?: "general")}
    var customize by rememberSaveable { mutableStateOf(record?.data?.text("template")=="custom") }
    var choices by remember { mutableStateOf(record?.let(::listFields) ?: defaultListFields(template)) }
    var category by rememberSaveable(record?.id){mutableStateOf(record?.data?.text("category","general") ?: "general")}
    var notifyItems by rememberSaveable(record?.id){mutableStateOf(record?.data?.get("notify_additions")!=JsonPrimitive(false))};var itemReminders by rememberSaveable(record?.id){mutableStateOf(record?.data?.get("item_reminders")!=JsonPrimitive(false))}
    var perpetual by rememberSaveable { mutableStateOf(record?.data?.get("perpetual")!=JsonPrimitive(false)) }
    var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) };var remove by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope();val basis=remember { state.cache.snapshot!!.basis }
    val occupied=record!=null&&projectRecords(state.cache).any { it.record.kind=="task"&&it.record.data.text("project_id")==record.id }
    EditorDialog(onDismissRequest=close,title={Text(if(record==null)"New $kind" else "Edit $kind")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title,{title=it.take(160)},label={Text(if(kind=="list")"List name" else "Project name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(notes,{notes=it.take(16384)},label={Text("Description")},minLines=2,modifier=Modifier.fillMaxWidth())
        if(state.cache.supportsPlannerV3())CategoryPicker(category){category=it}
        if(kind=="list") {
            Text("List type",style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for((key,label) in listTemplates)FilterChip(template==key,{template=key;choices=defaultListFields(key);customize=key=="custom";if(category=="general"&&key=="groceries")category="food"},{Text(label)})}
            if(template=="shopping")Row(verticalAlignment=Alignment.CenterVertically){Checkbox("price" in choices,{choices=if(it)choices+"price" else choices-"price"});Text("Include a price field")}
            SecondaryButton(onClick={customize=!customize}){Text("Customize fields (${choices.size}) "+if(customize)"▴" else "▾")}
            if(customize)Text("Fields on each item",style=MaterialTheme.typography.titleSmall)
            Text("Item name and checked state are always included. Field choices change what you see, not existing values or reminders.",style=MaterialTheme.typography.bodySmall)
            if(customize)FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for((field,label) in listFieldLabels.filterKeys{it!="price"||template!="shopping"})FilterChip(field in choices,{choices=if(field in choices)choices-field else choices+field},{Text(label)})}
            Row(verticalAlignment=Alignment.CenterVertically){Switch(perpetual,{perpetual=it});Text("Rolling list · keep using it",Modifier.padding(start=8.dp))}
            Text("Check items off, add more whenever you need, and clear only the checked items.",style=MaterialTheme.typography.bodySmall)
            if(state.cache.supportsPlannerV3()){Row(verticalAlignment=Alignment.CenterVertically){Switch(notifyItems,{notifyItems=it});Text("Notify when items are added",Modifier.padding(start=8.dp))};Row(verticalAlignment=Alignment.CenterVertically){Switch(itemReminders,{itemReminders=it});Text("Scheduled item reminders",Modifier.padding(start=8.dp))};Text("Muting keeps saved reminder settings, list contents and sync intact.",style=MaterialTheme.typography.bodySmall)}
        }
        if(occupied)Text("Clear or move this collection's items before deleting it.",style=MaterialTheme.typography.bodySmall)
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={Button(shape=ControlShape,enabled=title.isNotBlank()&&!busy,onClick={busy=true;scope.launch { try {
        val extra=if(state.cache.supportsPlannerV3())mapOf("category" to category.j())+(if(kind=="list")mapOf("notify_additions" to JsonPrimitive(notifyItems),"item_reminders" to JsonPrimitive(itemReminders)) else emptyMap()) else emptyMap()
        val fields=JsonObject(mapOf("title" to title.j(),"notes" to notes.j())+(if(kind=="list")listConfiguration(template,choices,perpetual) else emptyMap())+extra)
        model.enqueue(kind+if(record==null)".create" else ".update",record?.id ?: model.newId(),fields,record?.revision,basis);close();model.sync()
    }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}finally{busy=false} }}){Text(if(record==null)"Create $kind" else "Save changes")}},dismissButton={Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(record!=null)SecondaryButton(onClick={remove=true},enabled=!occupied&&!busy){Text("Delete",color=MaterialTheme.colorScheme.error)}
        SecondaryButton(onClick=close,enabled=!busy){Text("Cancel")}
    }})
    if(remove&&record!=null)EditorDialog(onDismissRequest={remove=false},title={Text("Delete ${record.data.text("title")}?")},text={Text("The empty $kind will be removed from your household.")},confirmButton={Button(shape=ControlShape,onClick={model.enqueueAsync("$kind.delete",record.id,obj(),record.revision,basis);remove=false;close()}){Text("Delete $kind")}},dismissButton={SecondaryButton(onClick={remove=false}){Text("Keep it")}})
}

@Composable internal fun NewNoteDialog(model:Workspace,state:WorkspaceState,initialRadar:Boolean=false,device:DeviceActions=DeviceActions(),close:()->Unit) {
    var onRadar by rememberSaveable { mutableStateOf(initialRadar) };var remindAt by rememberSaveable { mutableStateOf(0L) };var category by rememberSaveable{mutableStateOf("general")}
    var title by rememberSaveable { mutableStateOf("") };var body by rememberSaveable { mutableStateOf("") };var pinned by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) };var busy by remember { mutableStateOf(false) };val scope=rememberCoroutineScope();val basis=remember { state.cache.snapshot!!.basis }
    EditorDialog(onDismissRequest=close,title={Text(if(initialRadar)"New reminder note" else "New note")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title,{title=it.take(240)},label={Text("Note title")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(body,{body=it.take(16384)},label={Text("Content")},minLines=6,modifier=Modifier.fillMaxWidth())
        if(state.cache.supportsPlannerV3()){CategoryPicker(category){category=it};FollowUpControls(onRadar,remindAt,device.use24Hour){radar,at->onRadar=radar;remindAt=at}}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(pinned,{pinned=it});Text("Pin this note")}
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={Button(shape=ControlShape,enabled=title.isNotBlank()&&!busy,onClick={busy=true;scope.launch { try { model.enqueue("note.create",model.newId(),JsonObject(obj("title" to title.j(),"body" to body.j(),"pinned" to JsonPrimitive(pinned))+(if(state.cache.supportsPlannerV3())mapOf("category" to category.j(),"on_radar" to JsonPrimitive(onRadar),"remind_at" to remindAt.j()) else emptyMap())),null,basis);close();model.sync() }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}finally{busy=false} }}){Text("Create note")}},dismissButton={SecondaryButton(onClick=close){Text("Cancel")}})
}

@Composable internal fun ColumnScope.Checklist(model:Workspace,state:WorkspaceState,list:Record,device:DeviceActions,image:@Composable (VisibleRecord)->Unit,open:(String)->Unit,edit:()->Unit,storeChanged:(String)->Unit={}) {
    val rows=projectRecords(state.cache);val all=rows.filter { it.record.kind=="task"&&it.record.data.text("project_id")==list.id }
    val fields=listFields(list);val writable=state.cache.snapshot!!.actor.role!="viewer"
    var query by rememberSaveable(list.id){mutableStateOf("")};var store by rememberSaveable(list.id){mutableStateOf("*")};var sort by rememberSaveable(list.id){mutableStateOf("title")};var status by rememberSaveable(list.id){mutableStateOf("open")}
    var confirmAction by remember { mutableStateOf<String?>(null) };var changingChecked by remember { mutableStateOf(false) }
    var checkedSelection by remember { mutableStateOf<Pair<Basis,List<Record>>?>(null) }
    LaunchedEffect(fields){if("store" !in fields){store="*";storeChanged("")};if(sort !in setOf("title","added","updated")+fields.filter{it !in setOf("notes","images","store","link_url")})sort="title"}
    val stores=all.map { storeName(it.record) }.distinct().sortedWith(compareBy { it.lowercase() })
    val matching=all.filter { row->val d=row.record.data;("store" !in fields||store=="*"||storeName(row.record)==store)&&(listOf(d.text("title"),d.text("notes"),d.text("store"),d.text("quantity"),d.text("link_url"),d.text("price")).joinToString(" ").contains(query,true)) }
    val filtered=matching.filter { status=="all"||((it.record.data.text("status")=="done")==(status=="done")) }
    val comparator=Comparator<VisibleRecord> { a,b->
        val x=a.record.data;val y=b.record.data
        val order=when(sort){"added"->y.number("created_at").compareTo(x.number("created_at"));"updated"->y.number("last_activity_at",y.number("updated_at")).compareTo(x.number("last_activity_at",x.number("updated_at")));"price"->x.text("currency","USD").compareTo(y.text("currency","USD")) .takeIf { it!=0 } ?: (x.text("price").toDoubleOrNull() ?: Double.POSITIVE_INFINITY).compareTo(y.text("price").toDoubleOrNull() ?: Double.POSITIVE_INFINITY);"priority","estimate_minutes"->x.number(sort).compareTo(y.number(sort));else->x.text(sort).ifBlank { "~" }.compareTo(y.text(sort).ifBlank { "~" },true)}
        order.takeIf { it!=0 } ?: x.text("title").compareTo(y.text("title"),true).takeIf { it!=0 } ?: a.record.id.compareTo(b.record.id)
    }
    val sorted=filtered.sortedWith(comparator);val checked=matching.filter { it.record.data.text("status")=="done" }
    RecordBadges(list)
    if(list.data.text("notes").isNotBlank())Text(list.data.text("notes"),style=MaterialTheme.typography.bodyMedium)
    OutlinedTextField(query,{query=it},label={Text("Search titles and item content")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(vertical=8.dp))
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if("store" in fields)ViewChoice("Store",store,listOf("*" to "All stores")+stores.map { it to it.ifBlank { "No store yet" } }){store=it;storeChanged(if(it=="*")"" else it)}
        ViewChoice("Show",status,listOf("open" to "Unchecked","all" to "All items","done" to "Checked")){status=it}
        ViewChoice("Sort",sort,listOf("title" to "Name A–Z","added" to "Recently added","updated" to "Recently changed")+fields.filter { it !in setOf("notes","images","store","link_url") }.map { it to listFieldLabels.getValue(it) }){sort=it}
        if(writable)SecondaryButton(onClick=edit){Text("List settings")}
        if(writable&&checked.isNotEmpty()) {
            if(list.data["perpetual"]!=JsonPrimitive(false))SecondaryButton(onClick={checkedSelection=state.cache.snapshot!!.basis to checked.map{it.record};confirmAction="reuse"}){Text("Reuse checked (${checked.size})")}
            SecondaryButton(onClick={checkedSelection=state.cache.snapshot!!.basis to checked.map{it.record};confirmAction="clear"}){Text("Clear checked (${checked.size})")}
        }
    }
    Text("${sorted.size} of ${all.size} items · ${all.count { it.record.data.text("status")!="done" }} unchecked",Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(list.data["notify_additions"]==JsonPrimitive(false)||list.data["item_reminders"]==JsonPrimitive(false))Text(listOfNotNull(if(list.data["notify_additions"]==JsonPrimitive(false))"Item-addition alerts muted" else null,if(list.data["item_reminders"]==JsonPrimitive(false))"Scheduled item reminders muted" else null).joinToString(" · "),style=MaterialTheme.typography.labelSmall)
    val groups=if("store" in fields&&store=="*")sorted.groupBy { storeName(it.record) }.toList().sortedBy { it.first.lowercase() }.toMap() else mapOf("" to sorted)
    VisibleLazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=100.dp)) {
        if(sorted.isEmpty())item { Text(if(all.isEmpty())"Your list is ready. Add an item to get started." else "No items match. Clear the search or choose All stores.",Modifier.padding(vertical=28.dp),color=MaterialTheme.colorScheme.onSurfaceVariant) }
        for((name,items) in groups){
            if("store" in fields&&store=="*")item("store:$name"){Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=6.dp)){Text(name.ifBlank { "No store yet" },Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Text(items.size.toString(),style=MaterialTheme.typography.labelMedium)}}
            items(items,key={it.record.id}){row->
                val d=row.record.data;val done=d.text("status")=="done"
                OutlinedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp),verticalAlignment=Alignment.Top) {
                    Checkbox(done,{model.enqueueAsync(if(done)"task.reopen" else "task.complete",row.record.id,obj(),row.record.revision)},enabled=writable&&(d.text("claimant").isBlank()||d.text("claimant")==state.cache.snapshot.actor.user_id))
                    Column(Modifier.weight(1f).clickable { open(row.record.id) }.padding(top=8.dp,bottom=6.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        RecordBadges(row.record,list)
                        Text(d.text("title"),style=MaterialTheme.typography.titleMedium,color=if(done)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        val details=buildList { if("quantity" in fields&&d.text("quantity").isNotBlank())add(d.text("quantity"));if("store" in fields&&storeName(row.record).isNotBlank())add(storeName(row.record));if("price" in fields&&priceLabel(d).isNotBlank())add(priceLabel(d));if("link_url" in fields&&d.text("link_url").isNotBlank())add(d.text("link_url").substringAfter("://").substringBefore('/'));if(row.pending)add("Queued") }
                        if(details.isNotEmpty())Text(details.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                        if("notes" in fields&&d.text("notes").isNotBlank())Text(d.text("notes").take(150),style=MaterialTheme.typography.bodySmall)
                        Text(recordHistory(state.cache,row.record,TimeZone.currentSystemDefault()),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Open item ›",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                    }
                    if("images" in fields)rows.firstOrNull { it.record.kind=="image"&&it.record.data.text("task_id")==row.record.id }?.let { attachment ->Box(Modifier.padding(start=8.dp).size(64.dp).clip(RoundedCornerShape(10.dp))){image(attachment)} }
                } }
            }
        }
    }
    if(confirmAction!=null) {
        val reuse=confirmAction=="reuse"
        val selection=checkedSelection!!
        EditorDialog(onDismissRequest={if(!changingChecked)confirmAction=null},title={Text("${if(reuse)"Reuse" else "Clear"} ${selection.second.size} checked items?")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(reuse)"Uncheck the completed items matching your search and store to use the list again. Notes, quantities, stores, images and history are kept. Changed items stay for review." else "Only these checked items matching your search and store will be removed. Unchecked items and revision conflicts are kept.")
            Text(selection.second.take(5).joinToString(" · "){it.data.text("title")})
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }},confirmButton={Button(shape=ControlShape,enabled=!changingChecked,onClick={changingChecked=true;model.launch {try {if(reuse)model.reuseCheckedItems(selection.second,selection.first)else model.clearCheckedItems(selection.second,selection.first);confirmAction=null;model.sync()}finally{changingChecked=false}}}){Text(if(reuse)"Uncheck and reuse" else "Clear checked")}},dismissButton={SecondaryButton(enabled=!changingChecked,onClick={confirmAction=null}){Text("Keep items")}})
    }
}

private val itemKeys=setOf("title","notes","quantity","store","category","link_url","price","currency","due","planned_for","priority","estimate_minutes","assignee","reminders","reminder_options")
private fun itemEditingData(data:JsonObject):JsonObject {
    val fields=data.filterKeys { it in itemKeys }
    return if(data.containsKey("reminder_rules"))JsonObject((fields-"reminders")+listOfNotNull(data["reminder_rules"]?.takeUnless { it==JsonNull }?.let { "reminders" to it }).toMap()) else JsonObject(fields)
}
private fun itemReminderRules(data:JsonObject,id:String)=data["reminders"]?.let { WireJson.decodeFromJsonElement<ReminderSet>(it) } ?: ReminderSet(if(data.text("due").isNotBlank())listOf(ReminderAlert(id,0)) else emptyList())
@Composable internal fun ListItemDialog(model:Workspace,state:WorkspaceState,list:Record,visible:VisibleRecord?,device:DeviceActions,attach:(String)->Unit={},image:@Composable (VisibleRecord)->Unit={},initialStore:String="",close:()->Unit) {
    val record=visible?.record;val draft=record?.let { state.cache.drafts[it.id] };val basis=remember(record?.id){draft?.let { Basis(it.epoch,it.generation) } ?: state.cache.snapshot!!.basis}
    val id=rememberSaveable(record?.id){record?.id ?: model.newId()}
    var dataJson by rememberSaveable(id){mutableStateOf(itemEditingData(draft?.data ?: record?.data ?: JsonObject(obj("title" to "".j(),"currency" to "USD".j())+(if(state.cache.supportsPlannerV3())mapOf("store" to initialStore.j(),"category" to list.data.text("category","general").j()) else emptyMap()))).toString())}
    var revision by rememberSaveable(id){mutableStateOf(draft?.revision ?: record?.revision ?: 0)}
    var dirty by rememberSaveable(id){mutableStateOf(draft!=null)};var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) };var comment by rememberSaveable(id){mutableStateOf("")}
    val data=WireJson.parseToJsonElement(dataJson).jsonObject;val fields=listFields(list);val scope=rememberCoroutineScope();val writable=state.cache.snapshot!!.actor.role!="viewer"&&basis==state.cache.snapshot.basis
    fun set(field:String,value:JsonElement) { val next=JsonObject(WireJson.parseToJsonElement(dataJson).jsonObject+(field to value));dataJson=next.toString();dirty=true;if(record!=null)model.launch { model.saveDraft(id,revision,next,basis) } }
    LaunchedEffect(record?.revision,visible?.pending){if(record!=null&&!dirty){dataJson=itemEditingData(record.data).toString();revision=record.revision}}
    EditorDialog(onDismissRequest=close,title={Text(if(record==null)"New item" else "List item")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(list.data.text("title"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
        OutlinedTextField(data.text("title"),{set("title",it.take(240).j())},label={Text("Item name")},enabled=writable,modifier=Modifier.fillMaxWidth())
        if(state.cache.supportsPlannerV3())CategoryPicker(data.text("category","general"),writable){set("category",it.j())}
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
        if(writable)ReminderEditor(rules,Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,model::newId,device.use24Hour,device.previewSound,
            reminderEditorSettings(model,state,reminderOptions(Record(id,"task",revision,data)) ?: ReminderOptions(),true){set("reminder_options",reminderOptionsData(it))}){set("reminders",reminderData(it))}
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
        close();model.sync()
    }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}finally{busy=false} }}){Text(if(record==null)"Add item" else "Save item")}},dismissButton={SecondaryButton(onClick=close){Text("Close")}})
}
