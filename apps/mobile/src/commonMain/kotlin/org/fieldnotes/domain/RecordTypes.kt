package org.fieldnotes.domain

/** Small, named household categories. Labels never replace the record's kind. */
val recordCategories = linkedMapOf("general" to "General", "action" to "Action", "food" to "Food & cooking", "honey_do" to "Honey-do", "home" to "Home & maintenance", "errands" to "Errands", "work" to "Work", "personal" to "Personal")
fun recordTypeLabel(record:Record):String = when {
    record.kind=="list" -> listTemplates[record.data.text("template","general")] ?: "List"
    record.kind=="note"&&record.flag("on_radar") -> "Later & reminders"
    record.kind=="event" -> "Calendar event"
    record.kind=="project" -> "Project"
    else -> "Task"
}
fun categoryLabel(record:Record)=recordCategories[record.data.text("category","general")] ?: "General"
fun isChecklistItem(record:Record,records:List<Record>)=record.kind=="task"&&records.any { it.id==record.data.text("project_id")&&it.kind=="list" }
fun plannerTasks(cache:Cache):List<VisibleRecord> {
    val rows=projectRecords(cache);val lists=rows.filter { it.record.kind=="list" }.map { it.record.id }.toSet()
    return rows.filter { it.record.kind=="task"&&it.record.data.text("project_id") !in lists }
}
fun storeName(record:Record)=record.data.text("store").trim()
fun allowsItemReminders(cache:Cache,record:Record)=projectRecords(cache).firstOrNull { it.record.id==record.data.text("project_id")&&it.record.kind=="list" }?.record?.data?.get("item_reminders")!=kotlinx.serialization.json.JsonPrimitive(false)
