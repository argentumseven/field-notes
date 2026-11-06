package org.fieldnotes.domain

import kotlinx.serialization.json.*
import kotlinx.datetime.toLocalDateTime

val listFieldLabels = linkedMapOf("quantity" to "Quantity", "store" to "Store", "notes" to "Notes", "link_url" to "Web link", "price" to "Price", "images" to "Attached images", "assignee" to "Assigned to", "due" to "Due date", "planned_for" to "Planned day", "priority" to "Priority", "estimate_minutes" to "Effort")
val listTemplates = linkedMapOf("general" to "General list", "tasks" to "Task list", "groceries" to "Groceries", "shopping" to "Online shopping", "custom" to "Custom list")
fun defaultListFields(template:String):Set<String> = when(template) { "tasks"->setOf("notes","assignee","due","planned_for","priority","estimate_minutes","images"); "groceries" -> setOf("quantity","store","notes"); "shopping" -> setOf("quantity","link_url","images"); "custom" -> emptySet(); else -> setOf("notes") }
fun listFields(record:Record):Set<String> = (record.data["fields"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: defaultListFields(record.data.text("template","general"))
fun listConfiguration(template:String, fields:Set<String>, perpetual:Boolean)=obj("template" to (if(template=="tasks")"general" else template).j(),"fields" to JsonArray(listFieldLabels.keys.filter { it in fields }.map { it.j() }),"perpetual" to JsonPrimitive(perpetual))
fun priceLabel(data:JsonObject):String = data.text("price").takeIf { it.isNotBlank() }?.let { "${data.text("currency","USD")} $it" } ?: ""
fun recordCreator(record:Record)=record.data.text("created_by").ifBlank { record.data.text("author_id") }
fun recordHistory(cache:Cache,record:Record,zone:kotlinx.datetime.TimeZone):String {
    if(record.revision==0L)return "Created by ${cache.snapshot?.actor?.name ?: "You"} · Waiting for server timestamps"
    fun person(id:String)=if(id.isBlank())"unavailable" else personName(cache,id)
    fun stamp(key:String):String = (if(key=="updated_at")record.data.number("last_activity_at",record.data.number(key)) else record.data.number(key)).takeIf { it>0 }?.let { seconds ->
        val local=kotlinx.datetime.Instant.fromEpochSeconds(seconds).toLocalDateTime(zone)
        "${local.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${local.dayOfMonth}, ${local.year} · ${friendlyTime(local.time)}"
    } ?: "time unavailable"
    return "Created by ${record.data.text("created_by_name").ifBlank { person(recordCreator(record)) }} · ${stamp("created_at")}\nUpdated by ${record.data.text("last_activity_by_name").ifBlank { record.data.text("updated_by_name").ifBlank { person(record.data.text("last_activity_by").ifBlank { record.data.text("updated_by").ifBlank { recordCreator(record) } }) } }} · ${stamp("updated_at")}"
}
