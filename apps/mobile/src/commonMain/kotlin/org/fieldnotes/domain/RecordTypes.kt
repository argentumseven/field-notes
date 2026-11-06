package org.fieldnotes.domain

/** Small, named household categories. Labels never replace the record's kind. */
val recordCategories = linkedMapOf("general" to "General", "action" to "Action", "food" to "Food & cooking", "home" to "Household", "errands" to "Errands", "work" to "Work", "personal" to "Personal")
fun categoryKey(value:String)=if(value=="honey_do")"home" else value.ifBlank { "general" }
fun categoryChoices(value:String)=recordCategories.map { (key,label)->(if(key=="home"&&value=="honey_do")value else key) to label }
fun recordTypeLabel(record:Record):String = when {
    record.kind=="list"&&record.flag("items_are_tasks")->"Task list"
    record.kind=="list" -> listTemplates[record.data.text("template","general")] ?: "List"
    record.kind=="note"&&record.flag("on_radar") -> "Later & reminders"
    record.kind=="event" -> "Calendar event"
    record.kind=="project" -> "Project"
    else -> "Task"
}
fun categoryLabel(record:Record)=recordCategories[categoryKey(record.data.text("category","general"))] ?: "General"
fun isChecklistItem(record:Record,records:List<Record>)=record.kind=="task"&&records.any { it.id==record.data.text("project_id")&&it.kind=="list"&&!it.flag("items_are_tasks") }
fun plannerTasks(cache:Cache):List<VisibleRecord> {
    val rows=projectRecords(cache);val lists=rows.filter { it.record.kind=="list"&&!it.record.flag("items_are_tasks") }.map { it.record.id }.toSet()
    return rows.filter { it.record.kind=="task"&&it.record.data.text("project_id") !in lists }
}
fun storeName(record:Record)=record.data.text("store").trim()
fun supportsReminderEditor(cache:Cache,record:Record)=projectRecords(cache).none { it.record.id==record.data.text("project_id")&&it.record.kind=="list"&&it.record.data.text("template")=="groceries" }
fun allowsItemReminders(cache:Cache,record:Record)=supportsReminderEditor(cache,record)&&projectRecords(cache).firstOrNull { it.record.id==record.data.text("project_id")&&it.record.kind=="list" }?.record?.data?.get("item_reminders")!=kotlinx.serialization.json.JsonPrimitive(false)
