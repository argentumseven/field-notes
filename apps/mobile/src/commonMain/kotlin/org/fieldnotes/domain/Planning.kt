package org.fieldnotes.domain

import kotlinx.datetime.*
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

data class TaskRank(val score: Int, val reasons: List<String>, val eligible: Boolean)
fun Record.flag(name: String) = data[name]?.jsonPrimitive?.booleanOrNull == true
fun priorityName(priority: Int) = listOf("Urgent", "High", "Normal", "Low")[(priority - 1).coerceIn(0, 3)]

/** Version 1, a transparent bounded heuristic. Missing dates contribute zero:
 * they are never invented or treated as overdue. Clock and local day are inputs. */
fun rankTask(task: Record, today: LocalDate, nowSeconds: Long, userId: String): TaskRank {
    val d = task.data
    val priority = d.number("priority", 3).toInt().coerceIn(1, 4)
    var score = listOf(70, 45, 25, 10)[priority - 1]
    val reasons = mutableListOf("${priorityName(priority)} priority +$score")
    val due = runCatching { LocalDate.parse(d.text("due")) }.getOrNull()
    val delta = due?.let { today.daysUntil(it) }
    val urgency = when {
        delta == null -> 0
        delta < 0 -> 30 + (-delta).coerceAtMost(20)
        delta == 0 -> 30
        delta == 1 -> 22
        delta <= 3 -> 16
        delta <= 7 -> 8
        delta <= 14 -> 3
        else -> 0
    }
    if (urgency > 0) { score += urgency; reasons += "${if (delta!! < 0) "Overdue" else "Due soon"} +$urgency" }
    val created = d.number("created_at")
    val age = if (created > 0) ((nowSeconds - created).coerceAtLeast(0) / 86400 / 3).coerceAtMost(18).toInt() else 0
    if (age > 0) { score += age; reasons += "Waiting time +$age" }
    val planned = runCatching { LocalDate.parse(d.text("planned_for")) }.getOrNull()
    if (planned != null && planned <= today) { score += 12; reasons += "Planned day +12" }
    val estimate = d.number("estimate_minutes").toInt()
    val effort = when (estimate) { in 1..15 -> 6; in 16..30 -> 3; in 31..60 -> 1; else -> 0 }
    if (effort > 0) { score += effort; reasons += "Small next step +$effort" }
    if (d.text("status") == "in_progress" && d.text("claimant") == userId) { score += 12; reasons += "Already started +12" }
    if (task.flag("pinned")) reasons.add(0, "Pinned")
    val otherClaim = d.text("claimant").let { it.isNotBlank() && it != userId }
    val futurePlan = planned != null && planned > today && (due == null || due > today) && !(d.text("status") == "in_progress" && d.text("claimant") == userId)
    return TaskRank(score, reasons, d.text("status") != "done" && !task.flag("blocked") && !otherClaim && !futurePlan)
}

fun focusTasks(cache: Cache, today: LocalDate, nowSeconds: Long): List<Pair<VisibleRecord, TaskRank>> {
    val user = cache.snapshot?.actor?.user_id ?: return emptyList()
    return projectRecords(cache).filter { it.record.kind == "task" }.map { it to rankTask(it.record, today, nowSeconds, user) }
        .filter { it.second.eligible }
        .sortedWith(compareByDescending<Pair<VisibleRecord, TaskRank>> { it.first.record.flag("pinned") }
            .thenByDescending { it.second.score }.thenBy { it.first.record.data.number("created_at", Long.MAX_VALUE) }.thenBy { it.first.record.id })
}

data class CalendarEntry(val record: Record, val labels: List<String>, val startsAt: Long? = null)
fun calendarEntries(records: List<Record>, day: LocalDate, zone: TimeZone): List<CalendarEntry> {
    val key = day.toString()
    val start = day.atStartOfDayIn(zone).epochSeconds
    val end = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds
    return records.mapNotNull { record ->
        val d = record.data
        when (record.kind) {
            "task" -> {
                val labels = buildList { if (d.text("planned_for") == key) add("Planned"); if (d.text("due") == key) add("Due") }
                if (labels.isEmpty()) null else CalendarEntry(record, labels)
            }
            "event" -> {
                if (record.flag("all_day")) {
                    if (key >= d.text("start_date") && key < d.text("end_date")) CalendarEntry(record, listOf("All day")) else null
                } else if (d.number("starts_at") < end && d.number("ends_at") > start) CalendarEntry(record, emptyList(), d.number("starts_at")) else null
            }
            else -> null
        }
    }.sortedWith(compareBy<CalendarEntry> { it.startsAt ?: Long.MIN_VALUE }.thenBy { it.record.data.text("title") }.thenBy { it.record.id })
}

fun monthCells(month: LocalDate): List<LocalDate?> {
    val first = LocalDate(month.year, month.monthNumber, 1)
    val offset = first.dayOfWeek.isoDayNumber - 1
    val length = first.plus(1, DateTimeUnit.MONTH).toEpochDays() - first.toEpochDays()
    return List(42) { i -> if (i in offset until offset + length) first.plus(i - offset, DateTimeUnit.DAY) else null }
}
