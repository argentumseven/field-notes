package org.fieldnotes.domain

/** Time is supplied by the OS adapter; the shared policy is testable on a JVM.
 * Reminders are once per task/due-date, after 09:00 local time, never exact alarms. */
fun dueReminderKeys(cache: Cache, localDate: String, hour: Int): Set<String> {
    if (!cache.preferences.reminders || hour < 9 || cache.snapshot == null) return emptySet()
    return projectRecords(cache).filter { row ->
        row.record.kind == "task" && row.record.data.text("status") != "done" &&
            row.record.data.text("due").let { it.isNotBlank() && it <= localDate }
    }.map { "${it.record.id}:${it.record.data.text("due")}" }.filterNot { it in cache.reminded }.toSet()
}
