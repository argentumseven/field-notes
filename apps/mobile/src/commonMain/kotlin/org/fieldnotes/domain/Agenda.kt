package org.fieldnotes.domain

import kotlinx.datetime.*

data class AgendaItem(val id: String, val title: String, val at: Long, val label: String)
data class LocalAlert(val key: String, val at: Long, val target: String?, val kind: String)

private fun mineOrShared(record: Record, user: String): Boolean =
    record.data.text("assignee").let { it.isBlank() || it == user } ||
        (record.data.text("status") == "in_progress" && record.data.text("claimant") == user)

/** Home-screen agenda uses dates/events, independently of the Focus score. */
fun upcomingAgenda(cache: Cache, now: Instant, zone: TimeZone, days: Int = 7): List<AgendaItem> {
    val user = cache.snapshot?.actor?.user_id ?: return emptyList()
    val today = now.toLocalDateTime(zone).date
    val end = today.plus(days, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds
    return projectRecords(cache).mapNotNull { row ->
        val r = row.record; val d = r.data
        when (r.kind) {
            "task" -> {
                if (d.text("status") == "done" || !mineOrShared(r, user)) return@mapNotNull null
                val due = runCatching { LocalDate.parse(d.text("due")) }.getOrNull()
                val planned = runCatching { LocalDate.parse(d.text("planned_for")) }.getOrNull()
                val date = if(due!=null&&due<today)due else listOfNotNull(due, planned?.takeIf { it>=today }).minOrNull() ?: return@mapNotNull null
                val at = date.atStartOfDayIn(zone).epochSeconds
                if (at >= end) null else AgendaItem(r.id, d.text("title"), at,
                    if (due != null && due < today) "Overdue" else if (date == due) "Due" else "Planned")
            }
            "event" -> if (r.flag("all_day")) {
                val start = runCatching { LocalDate.parse(d.text("start_date")) }.getOrNull() ?: return@mapNotNull null
                val last = runCatching { LocalDate.parse(d.text("end_date")) }.getOrNull() ?: return@mapNotNull null
                val at = maxOf(start, today).atStartOfDayIn(zone).epochSeconds
                if (last <= today || at >= end) null else AgendaItem(r.id, d.text("title"), at, "All day")
            } else {
                val start = d.number("starts_at"); val finish = d.number("ends_at")
                if (finish <= now.epochSeconds || start >= end) null else AgendaItem(r.id, d.text("title"), start, if (start <= now.epochSeconds) "Now" else "Event")
            }
            else -> null
        }
    }.sortedWith(compareBy<AgendaItem> { it.label=="Overdue" }.thenBy { it.at }.thenBy { it.title }.thenBy { it.id })
}

/** Local alarms need no network. Civil dates follow this profile's device zone;
 * timed event alerts remain attached to their UTC instant across zone changes. */
fun localAlerts(cache: Cache, now: Instant, zone: TimeZone): List<LocalAlert> {
    if (!cache.preferences.reminders || cache.token == null) return emptyList()
    val actor = cache.snapshot?.actor?.user_id ?: return emptyList()
    val today = now.toLocalDateTime(zone).date
    val dayStart = today.atStartOfDayIn(zone).epochSeconds
    val horizon = today.plus(8, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds
    fun morning(date: LocalDate) = LocalDateTime(date, LocalTime(9, 0)).toInstant(zone).epochSeconds
    val records = projectRecords(cache).map { it.record }
    val alerts = records.mapNotNull { r ->
        val d = r.data
        when (r.kind) {
            "task" -> {
                if (d.text("status") == "done" || !mineOrShared(r, actor)) return@mapNotNull null
                val due = runCatching { LocalDate.parse(d.text("due")) }.getOrNull() ?: return@mapNotNull null
                if (due < today) null else LocalAlert("due:${r.id}:$due", morning(due), r.id, "task")
            }
            "event" -> if (r.flag("all_day")) {
                val date = runCatching { LocalDate.parse(d.text("start_date")) }.getOrNull() ?: return@mapNotNull null
                LocalAlert("day:${r.id}:$date", morning(date), r.id, "calendar")
            } else {
                val start = d.number("starts_at")
                if(d.number("ends_at")<=now.epochSeconds)return@mapNotNull null
                LocalAlert("event:${r.id}:$start", start - 600, r.id, "calendar")
            }
            else -> null
        }
    }.filter { (it.at >= dayStart || it.kind=="calendar"&&it.at>=now.epochSeconds-86400) && it.at < horizon }
    val overdue = records.any { r -> r.kind == "task" && r.data.text("status") != "done" &&
        mineOrShared(r, actor) && runCatching { LocalDate.parse(r.data.text("due")) < today }.getOrDefault(false) }
    return (alerts + if (overdue) listOf(LocalAlert("overdue:$today", morning(today), null, "overdue")) else emptyList())
        .sortedWith(compareBy<LocalAlert> { it.at }.thenBy { it.key })
}
