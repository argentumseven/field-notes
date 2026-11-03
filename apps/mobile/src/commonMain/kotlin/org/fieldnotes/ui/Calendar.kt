@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.fieldnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

@Composable internal fun DateField(label: String, value: String, enabled: Boolean = true, clearable: Boolean = true, changed: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelMedium); Text(if(value.isBlank()) "No date" else friendlyDate(LocalDate.parse(value)), style = MaterialTheme.typography.bodyLarge) }
        SecondaryButton(onClick = { show = true }, enabled = enabled) { Text("Choose") }
        if (clearable && value.isNotEmpty()) SecondaryButton(onClick = { changed("") }, enabled = enabled) { Text("Clear") }
    }
    if (show) {
        val selected = runCatching { LocalDate.parse(value).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = selected)
        DatePickerDialog(onDismissRequest = { show = false }, confirmButton = { Button(onClick = { state.selectedDateMillis?.let { changed(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString()) }; show = false }) { Text("Set date") } }, dismissButton = { SecondaryButton(onClick = { show = false }) { Text("Cancel") } }) { DatePicker(state) }
    }
}
@Composable internal fun ColumnScope.PlannerCalendar(rows: List<VisibleRecord>, today: LocalDate, zone: TimeZone, selectedDate: String, selectDate: (String) -> Unit, createAt:(String)->Unit, open: (String) -> Unit) {
    val selected=LocalDate.parse(selectedDate);val records=rows.map { it.record }
    var view by rememberSaveable { mutableStateOf("Day") }
    val month=LocalDate(selected.year,selected.monthNumber,1)
    Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)){Text("${selected.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary);Text(friendlyDate(selected),style=MaterialTheme.typography.titleLarge)}
        OutlinedIconButton(onClick={selectDate(if(view=="Month")month.minus(1,DateTimeUnit.MONTH).toString() else selected.minus(1,DateTimeUnit.DAY).toString())}){Icon(Icons.Outlined.KeyboardArrowLeft,"Previous ${if(view=="Month")"month" else "day"}")}
        OutlinedIconButton(onClick={selectDate(if(view=="Month")month.plus(1,DateTimeUnit.MONTH).toString() else selected.plus(1,DateTimeUnit.DAY).toString())}){Icon(Icons.Outlined.KeyboardArrowRight,"Next ${if(view=="Month")"month" else "day"}")}
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        for(mode in listOf("Day","Month","Agenda"))FilterChip(view==mode,{view=mode},{Text(mode)})
        Spacer(Modifier.weight(1f));TextButton(onClick={selectDate(today.toString())}){Text("Today")}
    }
    if(view=="Month") {
        Row { listOf("M","T","W","T","F","S","S").forEach { Box(Modifier.weight(1f).height(24.dp),contentAlignment=Alignment.Center){Text(it,style=MaterialTheme.typography.labelSmall)} } }
        monthCells(month).chunked(7).forEach { week ->Row {
            week.forEach { day ->
                Column(Modifier.weight(1f).height(44.dp).testTag("calendar-day-$day").background(if(day==selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable(enabled=day!=null){selectDate(day.toString())},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    Text(day?.dayOfMonth?.toString() ?: "",fontWeight=if(day==today)FontWeight.Bold else FontWeight.Normal)
                    if(day!=null&&calendarEntries(records,day,zone).isNotEmpty())Text("•",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                }
            }
        } }
    } else {
        val monday=selected.minus(selected.dayOfWeek.isoDayNumber-1,DateTimeUnit.DAY)
        Row(Modifier.padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) { for(i in 0..6) {
            val date=monday.plus(i,DateTimeUnit.DAY)
            FilledTonalButton(onClick={selectDate(date.toString())},modifier=Modifier.weight(1f).testTag("calendar-day-$date"),contentPadding=PaddingValues(horizontal=0.dp,vertical=8.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(date==selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)){
                Column(horizontalAlignment=Alignment.CenterHorizontally){Text(date.dayOfWeek.name.take(1),style=MaterialTheme.typography.labelSmall);Text(date.dayOfMonth.toString())}
            }
        } }
    }
    val entries=calendarEntries(records,selected,zone)
    if(view=="Day") {
        val floating=entries.filter { it.startsAt==null || it.windowLabel!=null }
        if(floating.isNotEmpty())Row(Modifier.fillMaxWidth().padding(vertical=4.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) { floating.forEach { entry ->AssistChip(onClick={open(entry.record.id)},label={Text(entry.record.data.text("title")+" · "+(entry.windowLabel ?: entry.labels.joinToString(" · ")))}) } }
        DayTimeline(records,selected,zone,createAt,open)
    } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=90.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(entries.isEmpty())item{Text("No scheduled items. Add an event here, or leave tasks undated in Tasks.",Modifier.padding(vertical=20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(entries,key={it.record.id}){entry->OutlinedCard(onClick={open(entry.record.id)},modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) { Text(calendarEntryLabel(entry,zone),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge);Text(entry.record.data.text("title"),style=MaterialTheme.typography.titleMedium) } } }
    }
}
@Composable private fun ColumnScope.DayTimeline(records:List<Record>,day:LocalDate,zone:TimeZone,createAt:(String)->Unit,open:(String)->Unit) {
    val start=day.atStartOfDayIn(zone);val length=dayLengthMinutes(day,zone);val blocks=dayBlocks(records,day,zone)
    val scroll=rememberScrollState();val density=androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(day){val minute=(blocks.firstOrNull()?.from?.minus(60) ?: 7*60.0).coerceAtLeast(0.0);scroll.scrollTo(with(density){(minute/60*56).dp.roundToPx()})}
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
        val gutter=56.dp;val available=maxWidth-gutter;val totalHeight=(length/60f*56).dp
        Box(Modifier.fillMaxWidth().height(totalHeight+90.dp).padding(bottom=4.dp)) {
            for(minute in 0 until length step 60) {
                val time=(start+minute.minutes).toLocalDateTime(zone).time
                Row(Modifier.offset(y=(minute/60f*56).dp).fillMaxWidth().height(56.dp).clickable { createAt(time.toString().take(5)) }) {
                    Text(friendlyTime(time).replace(":00",""),Modifier.width(gutter).padding(top=4.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.outlineVariant)
                }
            }
            for(block in blocks) {
                val width=(available-8.dp)/block.columns
                val point=block.until==null;val height=if(point)30.dp else ((block.until!!-block.from).toFloat()/60*56).dp.coerceAtLeast(22.dp)
                Surface(onClick={open(block.record.id)},modifier=Modifier.offset(x=gutter+width*block.column,y=(block.from/60*56).dp).width(width-4.dp).height(height),shape=androidx.compose.foundation.shape.RoundedCornerShape(8.dp),color=if(point)MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,border=androidx.compose.foundation.BorderStroke(1.dp,if(point)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(horizontal=7.dp,vertical=3.dp)) { Text((if(point)"◦ " else "")+block.record.data.text("title"),style=MaterialTheme.typography.labelLarge,maxLines=1);if(height>=42.dp)Text(calendarEntryLabel(CalendarEntry(block.record,emptyList(),block.record.data.number("starts_at")),zone),style=MaterialTheme.typography.labelSmall,maxLines=1) }
                }
            }
            val now=Clock.System.now()
            if(now.toLocalDateTime(zone).date==day) {
                val minute=(now-start).inWholeSeconds/60.0
                HorizontalDivider(Modifier.offset(x=gutter,y=(minute/60*56).dp).width(available),thickness=2.dp,color=MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}
@Composable internal fun EventEditor(model: Workspace, state: WorkspaceState, record: Record?, day: LocalDate, use24Hour: Boolean = false, preview:(AlertProfile)->Unit={}, initialTime:String?=null, close: () -> Unit) {
    val draft = record?.let { state.cache.drafts[it.id] }; val data = draft?.data ?: record?.data ?: obj()
    var title by rememberSaveable(record?.id) { mutableStateOf(data.text("title")) }; var notes by rememberSaveable(record?.id) { mutableStateOf(data.text("notes")) }
    val zone = TimeZone.currentSystemDefault()
    val originalStart = data.number("starts_at"); val originalEnd = data.number("ends_at")
    val initialStart = if(originalStart > 0) Instant.fromEpochSeconds(originalStart).toLocalDateTime(zone) else LocalDateTime(day, LocalTime.parse(initialTime ?: "09:00"))
    val initialEnd = if(originalEnd > 0) Instant.fromEpochSeconds(originalEnd).toLocalDateTime(zone) else (initialStart.toInstant(zone)+60.minutes).toLocalDateTime(zone)
    var hasEnd by rememberSaveable(record?.id) { mutableStateOf(record!=null && data.number("ends_at")>0) }
    var remindersJson by rememberSaveable(record?.id) { mutableStateOf(reminderData(reminderSet(Record(record?.id ?: "", "event",record?.revision ?: 0,data)) ?: ReminderSet(listOf(ReminderAlert(record?.id ?: model.newId())))).toString()) }
    var remindersDirty by rememberSaveable(record?.id) { mutableStateOf(record==null || data.containsKey("reminders")) }
    val reminders=WireJson.decodeFromString<ReminderSet>(remindersJson)
    var allDay by rememberSaveable(record?.id) { mutableStateOf(data["all_day"] == JsonPrimitive(true)) }
    var startDate by rememberSaveable(record?.id) { mutableStateOf(data.text("start_date").ifBlank { initialStart.date.toString() }) }
    var endDate by rememberSaveable(record?.id) { mutableStateOf(if(data.text("end_date").isNotBlank()) LocalDate.parse(data.text("end_date")).minus(1, DateTimeUnit.DAY).toString() else initialEnd.date.toString()) }
    var startTime by rememberSaveable(record?.id) { mutableStateOf(initialStart.time.toString().take(5)) }
    var endTime by rememberSaveable(record?.id) { mutableStateOf(initialEnd.time.toString().take(5)) }
    var revision by rememberSaveable(record?.id) { mutableStateOf(draft?.revision ?: record?.revision ?: 0) }
    val basis = remember(record?.id) { draft?.let { Basis(it.epoch, it.generation) } ?: state.cache.snapshot!!.basis }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    val actor = state.cache.snapshot!!.actor; val writable = actor.role != "viewer" && (record == null || actor.role in setOf("owner", "admin") || record.data.text("author_id") == actor.user_id)
    fun fields(): JsonObject {
        val start = if (allDay) 0L else scheduledInstant(startDate, startTime, zone, originalStart); val end = if (allDay || !hasEnd) 0L else scheduledInstant(endDate, endTime, zone, originalEnd)
        if (allDay) require(LocalDate.parse(endDate) >= LocalDate.parse(startDate)) { "The last day cannot be before the first day." } else if(hasEnd) require(end > start) { "Choose an end time after the start. For an overnight event, change the end date too." }
        require(title.isNotBlank()) { "Enter a title." }
        validateReminders(reminders)
        val fields=obj("title" to title.j(), "notes" to notes.j(), "all_day" to JsonPrimitive(allDay), "start_date" to (if (allDay) startDate else "").j(), "end_date" to (if (allDay) LocalDate.parse(endDate).plus(1, DateTimeUnit.DAY).toString() else "").j(), "starts_at" to start.j(), "ends_at" to end.j())
        return if(remindersDirty)JsonObject(fields+("reminders" to reminderData(reminders))) else fields
    }
    AlertDialog(onDismissRequest = close, title = { Text(if(record == null) "New calendar event" else "Calendar event") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title, { title = it.take(240) }, label = { Text("Title") }, enabled = writable)
        OutlinedTextField(notes, { notes = it.take(16384) }, label = { Text("Notes") }, minLines = 2, enabled = writable)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allDay, { allDay = it }, enabled = writable); Text("All day") }
        DateField(if(allDay) "First day" else "Starts", startDate, writable, clearable=false) { next ->
            val distance = LocalDate.parse(startDate).daysUntil(LocalDate.parse(endDate)).coerceAtLeast(0)
            startDate = next; endDate = LocalDate.parse(next).plus(distance, DateTimeUnit.DAY).toString()
        }
        if (!allDay) TimeField("Start time", startTime, use24Hour, writable) { next ->
            val duration = runCatching { LocalTime.parse(endTime).toSecondOfDay() - LocalTime.parse(startTime).toSecondOfDay() }.getOrDefault(3600).coerceAtLeast(60)
            startTime = next
            if (startDate == endDate) {
                val seconds = LocalTime.parse(next).toSecondOfDay() + duration
                endDate = LocalDate.parse(startDate).plus(seconds / 86400, DateTimeUnit.DAY).toString()
                endTime = LocalTime.fromSecondOfDay(seconds % 86400).toString().take(5)
            }
        }
        if(!allDay)Row(verticalAlignment=Alignment.CenterVertically) { Switch(hasEnd,{hasEnd=it},enabled=writable);Text("Set an end time",Modifier.padding(start=8.dp)) }
        if(allDay || hasEnd)DateField(if(allDay) "Last day · included" else "Ends", endDate, writable, clearable=false) { endDate = it }
        if (!allDay) {
            if(hasEnd)TimeField("End time", endTime, use24Hour, writable) { endTime = it }
            else Text("Start only · shown as a calendar marker, without reserving a block of time.",style=MaterialTheme.typography.bodySmall)
            Text("Times are local · ${zone.id.replace('_',' ')}", style = MaterialTheme.typography.bodySmall)
        }
        if(writable)ReminderEditor(reminders,LocalDate.parse(startDate),model::newId,use24Hour,preview) { remindersJson=reminderData(it).toString();remindersDirty=true }
        if(record!=null)Text(recordHistory(state.cache,record,zone),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if (record != null && revision != record.revision) { Text("Changed on another device. Your edits are kept. Close and reopen to load the latest event, or keep your edits for a new save.", color = MaterialTheme.colorScheme.error); SecondaryButton(onClick = { revision = record.revision }) { Text("Keep my edits") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { if(writable) Button(onClick = { busy = true; scope.launch { try { val fields = fields(); if (record == null) model.enqueue("event.create", model.newId(), fields, null, basis) else { model.saveDraft(record.id, revision, fields, basis, "event.update"); model.submitDraft(record.id) }; close(); model.sync() } catch(e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message } finally { busy = false } } }, enabled = !busy) { Text("Save event") } }, dismissButton = { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { if(writable && record != null) SecondaryButton(dangerous=true,onClick = { confirmDelete=true }) { Text("Delete") }; SecondaryButton(onClick = close) { Text("Close") } } })
    if(confirmDelete && record!=null) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Delete this event?")},text={Text("${record.data.text("title")} will be removed from the shared calendar when this device syncs.")},confirmButton={Button(onClick={model.enqueueAsync("event.delete",record.id,obj(),record.revision,basis);confirmDelete=false;close()}){Text("Delete event")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("Keep event")}})
}

@Composable internal fun TimeField(label: String, value: String, use24Hour: Boolean, enabled: Boolean = true, changed: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style=MaterialTheme.typography.labelMedium); Text(friendlyTime(LocalTime.parse(value), use24Hour), style=MaterialTheme.typography.bodyLarge) }
        SecondaryButton(onClick={show=true}, enabled=enabled) { Text("Choose time") }
    }
    if(show) {
        val local=LocalTime.parse(value)
        val picker=rememberTimePickerState(local.hour,local.minute,is24Hour=use24Hour)
        var keyboard by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest={show=false}, title={Text(label)}, text={Column(horizontalAlignment=Alignment.CenterHorizontally) {
            if(keyboard) TimeInput(picker,modifier=Modifier.testTag("time-input")) else TimePicker(picker,modifier=Modifier.testTag("clock-picker"))
            TextButton(onClick={keyboard=!keyboard}) { Text(if(keyboard) "Use clock" else "Type a time") }
        }}, confirmButton={Button(onClick={changed(LocalTime(picker.hour,picker.minute).toString().take(5));show=false}){Text("Set time")}}, dismissButton={TextButton(onClick={show=false}){Text("Cancel")}})
    }
}
