package org.fieldnotes.domain

/** Small, named household categories. Labels never replace the record's kind. */
val recordCategories = linkedMapOf("general" to "General", "action" to "Action", "food" to "Food & cooking", "home" to "Household", "errands" to "Errands", "work" to "Work", "personal" to "Personal")
fun categoryKey(value:String)=if(value=="honey_do")"home" else value.ifBlank { "general" }
fun categoryChoices(value:String)=recordCategories.map { (key,label)->(if(key=="home"&&value=="honey_do")value else key) to label }
const val HOUSEHOLD_CATEGORIES_FEATURE="household-categories-v1"
fun Cache.supportsHouseholdCategories()=compatibility?.let{!it.blocked&&HOUSEHOLD_CATEGORIES_FEATURE in it.features}==true
fun categoryChoices(cache:Cache,value:String):List<Pair<String,String>> {
    val builtins=categoryChoices(value)
    if(!cache.supportsHouseholdCategories())return builtins+(if(value.startsWith("custom:"))listOf(value to "Saved custom category (update server)")else emptyList())
    val custom=cache.snapshot?.categories.orEmpty()
        .filter{!it.archived||"custom:"+it.id==value}
        .map{("custom:"+it.id) to (it.name+if(it.archived)" · archived" else "")}
    return builtins+custom+(if(value.startsWith("custom:")&&custom.none{it.first==value})listOf(value to "Unavailable saved category")else emptyList())
}
fun categoryLabel(cache:Cache,record:Record):String=if(record.data.text("category").startsWith("custom:"))
    cache.snapshot?.categories.orEmpty().firstOrNull{"custom:"+it.id==record.data.text("category")}?.name ?: "Saved custom category"
    else categoryLabel(record)
fun recordTypeLabel(record:Record):String = when {
    record.isRecipe()->"Recipe"
    record.kind=="list"&&record.flag("items_are_tasks")->"Task list"
    record.kind=="list" -> listTemplates[record.data.text("template","general")] ?: "List"
    record.kind=="note"&&record.flag("on_radar") -> "Later & reminders"
    record.kind=="note" -> "Note"
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
