@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.fieldnotes.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

@Composable internal fun DateField(label: String, value: String, enabled: Boolean = true, changed: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelMedium); Text(value.ifBlank { "No date" }, style = MaterialTheme.typography.bodyLarge) }
        SecondaryButton(onClick = { show = true }, enabled = enabled) { Text("Choose") }
        if (value.isNotEmpty()) SecondaryButton(onClick = { changed("") }, enabled = enabled) { Text("Clear") }
    }
    if (show) {
        val selected = runCatching { LocalDate.parse(value).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = selected)
        DatePickerDialog(onDismissRequest = { show = false }, confirmButton = { Button(onClick = { state.selectedDateMillis?.let { changed(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString()) }; show = false }) { Text("Set date") } }, dismissButton = { SecondaryButton(onClick = { show = false }) { Text("Cancel") } }) { DatePicker(state) }
    }
}
@Composable internal fun ColumnScope.PlannerCalendar(rows: List<VisibleRecord>, today: LocalDate, zone: TimeZone, open: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(today.toString()) }; var monthValue by rememberSaveable { mutableStateOf(LocalDate(today.year, today.monthNumber, 1).toString()) }
    val month = LocalDate.parse(monthValue); val records = rows.map { it.record }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedIconButton(onClick = { monthValue = month.minus(1, DateTimeUnit.MONTH).toString() }) { Icon(Icons.Outlined.KeyboardArrowLeft,"Previous month") }
        Text("${month.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${month.year}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        SecondaryButton(onClick = { selected = today.toString(); monthValue = LocalDate(today.year, today.monthNumber, 1).toString() }) { Text("Today") }
        OutlinedIconButton(onClick = { monthValue = month.plus(1, DateTimeUnit.MONTH).toString() }) { Icon(Icons.Outlined.KeyboardArrowRight,"Next month") }
    }
    Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Box(Modifier.weight(1f).height(24.dp), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelSmall) } } }
    monthCells(month).chunked(7).forEach { week -> Row {
        week.forEach { day ->
            val chosen = day?.toString() == selected
            Column(Modifier.weight(1f).height(40.dp).background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background).clickable(enabled = day != null) { selected = day.toString() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(day?.dayOfMonth?.toString() ?: "", style = MaterialTheme.typography.bodySmall, fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal)
                if (day != null && calendarEntries(records, day, zone).isNotEmpty()) Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    } }
    HorizontalDivider(Modifier.padding(vertical = 12.dp)); Text(selected, style = MaterialTheme.typography.titleSmall)
    val entries = calendarEntries(records, LocalDate.parse(selected), zone)
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
        if (entries.isEmpty()) item { Text("No scheduled items. Undated tasks stay in Focus and Tasks.", Modifier.padding(vertical = 18.dp), style = MaterialTheme.typography.bodySmall) }
        items(entries, key = { it.record.id }) { entry ->
            Column(Modifier.fillMaxWidth().clickable { open(entry.record.id) }.padding(vertical = 12.dp)) {
                Text(entry.record.data.text("title"), fontWeight = FontWeight.Medium)
                Text((entry.labels + listOfNotNull(entry.startsAt?.let { Instant.fromEpochSeconds(it).toLocalDateTime(zone).time.toString().take(5) })).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }; HorizontalDivider()
        }
    }
}
@Composable internal fun EventEditor(model: Workspace, state: WorkspaceState, record: Record?, day: LocalDate, close: () -> Unit) {
    val draft = record?.let { state.cache.drafts[it.id] }; val data = draft?.data ?: record?.data ?: obj()
    var title by rememberSaveable(record?.id) { mutableStateOf(data.text("title")) }; var notes by rememberSaveable(record?.id) { mutableStateOf(data.text("notes")) }
    var allDay by rememberSaveable(record?.id) { mutableStateOf(data["all_day"] != JsonPrimitive(false)) }
    var startDate by rememberSaveable(record?.id) { mutableStateOf(data.text("start_date").ifBlank { day.toString() }) }
    var endDate by rememberSaveable(record?.id) { mutableStateOf(data.text("end_date").ifBlank { day.plus(1, DateTimeUnit.DAY).toString() }) }
    var startTime by rememberSaveable(record?.id) { mutableStateOf(if (data.number("starts_at") > 0) Instant.fromEpochSeconds(data.number("starts_at")).toString() else "${day}T09:00:00Z") }
    var endTime by rememberSaveable(record?.id) { mutableStateOf(if (data.number("ends_at") > 0) Instant.fromEpochSeconds(data.number("ends_at")).toString() else "${day}T10:00:00Z") }
    var revision by rememberSaveable(record?.id) { mutableStateOf(draft?.revision ?: record?.revision ?: 0) }
    val basis = remember(record?.id) { draft?.let { Basis(it.epoch, it.generation) } ?: state.cache.snapshot!!.basis }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    val actor = state.cache.snapshot!!.actor; val writable = actor.role != "viewer" && (record == null || actor.role in setOf("owner", "admin") || record.data.text("author_id") == actor.user_id)
    fun fields(): JsonObject {
        val start = if (allDay) 0L else Instant.parse(startTime).epochSeconds; val end = if (allDay) 0L else Instant.parse(endTime).epochSeconds
        if (allDay) require(LocalDate.parse(endDate) > LocalDate.parse(startDate)) { "End date is exclusive and must follow the start date." } else require(end > start) { "End must follow start." }
        require(title.isNotBlank()) { "Enter a title." }
        return obj("title" to title.j(), "notes" to notes.j(), "all_day" to JsonPrimitive(allDay), "start_date" to (if (allDay) startDate else "").j(), "end_date" to (if (allDay) endDate else "").j(), "starts_at" to start.j(), "ends_at" to end.j())
    }
    AlertDialog(onDismissRequest = close, title = { Text(if(record == null) "New calendar event" else "Calendar event") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title, { title = it.take(240) }, label = { Text("Title") }, enabled = writable)
        OutlinedTextField(notes, { notes = it.take(16384) }, label = { Text("Notes") }, minLines = 2, enabled = writable)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allDay, { allDay = it }, enabled = writable); Text("All day") }
        if (allDay) { DateField("Start day", startDate, writable) { startDate = it }; DateField("End day (exclusive)", endDate, writable) { endDate = it } }
        else {
            Text("Use ISO time with an explicit offset, such as 2026-11-01T09:00:00-05:00. This avoids guessing during daylight-saving changes. Calendar displays ${TimeZone.currentSystemDefault().id}.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(startTime, { startTime = it }, label = { Text("Start · ISO time + offset") }, enabled = writable)
            OutlinedTextField(endTime, { endTime = it }, label = { Text("End · ISO time + offset") }, enabled = writable)
        }
        if (record != null && revision != record.revision) { Text("Changed on another device. Review server: ${record.data}", color = MaterialTheme.colorScheme.error); SecondaryButton(onClick = { revision = record.revision }) { Text("Keep these fields for a new save") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { if(writable) Button(onClick = { busy = true; scope.launch { try { val fields = fields(); if (record == null) model.enqueue("event.create", model.newId(), fields, null, basis) else { model.saveDraft(record.id, revision, fields, basis, "event.update"); model.submitDraft(record.id) }; close(); model.sync() } catch(e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message } finally { busy = false } } }, enabled = !busy) { Text("Save event") } }, dismissButton = { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { if(writable && record != null) SecondaryButton(dangerous=true,onClick = { model.enqueueAsync("event.delete", record.id, obj(), record.revision, basis); close() }) { Text("Delete") }; SecondaryButton(onClick = close) { Text("Close") } } })
}
