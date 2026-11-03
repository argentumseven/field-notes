package org.fieldnotes.domain

import kotlinx.datetime.*

/** A wall-clock selection belongs to the device zone, never implicitly to UTC.
 * Reject nonexistent spring-forward times rather than silently moving an event.
 * For repeated autumn times kotlinx-datetime chooses the first occurrence;
 * an unchanged existing instant is retained by the editor, including the second. */
fun scheduledInstant(date: String, time: String, zone: TimeZone, original: Long = 0): Long {
    val local = LocalDateTime(LocalDate.parse(date), LocalTime.parse(time))
    if (original > 0 && Instant.fromEpochSeconds(original).toLocalDateTime(zone).let { it.date == local.date && it.hour == local.hour && it.minute == local.minute }) return original
    val instant = local.toInstant(zone)
    require(instant.toLocalDateTime(zone) == local) { "That time is skipped when the clocks change. Choose another time." }
    return instant.epochSeconds
}
fun friendlyDate(date: LocalDate): String = "${date.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}, ${date.year}"
fun friendlyTime(time: LocalTime, use24Hour: Boolean = false): String = if (use24Hour) "${time.hour.toString().padStart(2,'0')}:${time.minute.toString().padStart(2,'0')}" else "${if(time.hour % 12 == 0) 12 else time.hour % 12}:${time.minute.toString().padStart(2,'0')} ${if(time.hour < 12) "AM" else "PM"}"
fun calendarEntryLabel(entry: CalendarEntry, zone: TimeZone): String = entry.startsAt?.let {
    val start = Instant.fromEpochSeconds(it).toLocalDateTime(zone)
    if(entry.record.data.number("ends_at")==0L)return@let friendlyTime(start.time)+" · No end time"
    val end = Instant.fromEpochSeconds(entry.record.data.number("ends_at")).toLocalDateTime(zone)
    friendlyTime(start.time) + " – " + (if(start.date != end.date) "${end.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }} ${end.dayOfMonth}, " else "") + friendlyTime(end.time)
} ?: entry.labels.joinToString(" · ")
