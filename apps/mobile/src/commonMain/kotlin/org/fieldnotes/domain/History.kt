package org.fieldnotes.domain

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

const val HISTORY_FEATURE="history-v1"
fun Cache.supportsHistory()=compatibility?.let { !it.blocked&&HISTORY_FEATURE in it.features }==true
@Serializable data class HistoryChange(val field:String,val label:String,val before:JsonElement=JsonNull,val after:JsonElement=JsonNull,val before_truncated:Boolean=false,val after_truncated:Boolean=false)
@Serializable data class HistoryEntry(val sequence:Long,val target:String,val action:String,val actor_id:String,val actor_name:String,val at:Long,val title:String,val parent_title:String="",val kind:String,val item_type:String=kind,val source:String="edit",val changes:List<HistoryChange> = emptyList(),val total_fields:Int=changes.size,val details:String="unavailable") {
    val group get()=when {action.endsWith(".create")->"added";action.endsWith(".delete")||action=="workspace.clear"->"removed";else->"changed"}
    val verb get():String {
        val actionLabel=when(action.substringAfter('.')) {"create"->"Added";"delete"->"Removed";"complete"->"Completed";"reopen"->"Reopened";"start"->"Started";"release"->"Released";"override"->"Overrode working claim";"clear"->"Cleared";"restore"->"Restored";else->"Changed"}
        return if(source=="import")if(action.endsWith(".create"))"Imported" else "Imported · $actionLabel" else actionLabel
    }
    val typeLabel get()=when(item_type){"grocery_item"->"Grocery item";"shopping_item"->"Shopping item";"list_item"->"List item";"image","attachment"->"Attachment";"event"->"Calendar event";"workspace"->"Workspace";else->item_type.replaceFirstChar { it.uppercase() }}
}
@Serializable data class HistoryPage(val epoch:String,val generation:Long,val anchor:Long,val entries:List<HistoryEntry>,val next_before:Long,val has_more:Boolean,val actor_id:String,val scanned:Int=0,val detail_limit:Int=10_000,val detail_bytes:Long=64*1024*1024,val history_revision:Long=0)
@Serializable data class HistoryCache(val basis:Basis,val actor:String,val entries:List<HistoryEntry>,val fetchedAt:Long,val history_revision:Long=0)
data class HistoryFilter(val query:String="",val actor:String="",val kind:String="",val action:String="",val target:String="",val from:String="",val to:String="") {
    val active get()=listOf(query,actor,kind,action,target,from,to).any { it.isNotBlank() }
    fun body(basis:Basis,zone:TimeZone,anchor:Long?=null,before:Long?=null):JsonObject {
        require(query.encodeToByteArray().size<=200){"Keep history searches under 200 bytes."}
        val fields=mutableMapOf<String,JsonElement>("epoch" to basis.epoch.j(),"generation" to basis.generation.j(),"query" to query.j(),"actor" to actor.j(),"kind" to kind.j(),"action" to action.j(),"target" to target.j())
        if(from.isNotBlank())fields["from"]=LocalDate.parse(from).atStartOfDayIn(zone).epochSeconds.j()
        if(to.isNotBlank())fields["to"]=LocalDate.parse(to).plus(1,DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds.j()
        if(from.isNotBlank()&&to.isNotBlank())require(from<=to){"Choose the first day before the last day."}
        anchor?.let { fields["anchor"]=it.j() };before?.let { fields["before"]=it.j() }
        return JsonObject(fields)
    }
}
fun Cache.currentHistory()=history?.takeIf { h->snapshot?.basis==h.basis&&snapshot?.actor?.user_id==h.actor&&snapshot?.history_revision==h.history_revision }
fun historyMatches(entry:HistoryEntry,filter:HistoryFilter,zone:TimeZone):Boolean {
    if(filter.actor.isNotEmpty()&&entry.actor_id!=filter.actor||filter.kind.isNotEmpty()&&entry.kind!=filter.kind||filter.action.isNotEmpty()&&entry.group!=filter.action||filter.target.isNotEmpty()&&entry.target!=filter.target)return false
    val day=Instant.fromEpochSeconds(entry.at).toLocalDateTime(zone).date.toString()
    if(filter.from.isNotEmpty()&&day<filter.from||filter.to.isNotEmpty()&&day>filter.to)return false
    return filter.query.isBlank()||listOf(entry.title,entry.parent_title,entry.actor_name,entry.verb,entry.typeLabel,entry.changes.joinToString { it.label+" "+it.before+" "+it.after }).joinToString(" ").contains(filter.query,true)
}
fun historyMoment(at:Long,zone:TimeZone,use24Hour:Boolean=false):String {
    val local=Instant.fromEpochSeconds(at).toLocalDateTime(zone)
    return friendlyDate(local.date)+" · "+friendlyTime(local.time,use24Hour)
}
fun historyValue(change:HistoryChange,value:JsonElement,zone:TimeZone,use24Hour:Boolean=false):String {
    if(value==JsonNull)return "Not set"
    val field=change.field.substringAfterLast('.')
    if(value is JsonArray)return if(field in setOf("weekdays","days"))value.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { n->n in 1..7 }?.let { n->DayOfWeek.entries[n-1].name.lowercase().replaceFirstChar { c->c.uppercase() } } }.joinToString(", ") else value.joinToString(", "){historyValue(change,it,zone,use24Hour)}.ifBlank { "None" }
    val primitive=value as? JsonPrimitive ?: return "Changed setting"
    primitive.booleanOrNull?.let{return if(it)"Yes" else "No"}
    primitive.longOrNull?.let { n->
        if(field in setOf("starts_at","ends_at","remind_at","completed_at"))return if(n>0)historyMoment(n,zone,use24Hour) else "Not set"
        if(field in setOf("volume","alarm_volume"))return "$n%"
        if(field=="duration_seconds")return if(n==0L)"Standard length" else "$n seconds"
        if(field in setOf("minutes_before","lead_minutes"))return if(n==0L)"At start" else "$n minutes before"
        if(field=="every_minutes")return "Every $n minutes"
        if(field=="priority"&&n in 1..4)return priorityName(n.toInt())
    }
    val text=primitive.content
    if(text.isEmpty())return "Not set"
    if(field=="fields")return listFieldLabels[text] ?: text
    if(text.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))return runCatching { friendlyDate(LocalDate.parse(text)) }.getOrDefault(text)
    if(text.matches(Regex("\\d{2}:\\d{2}")))return runCatching { friendlyTime(LocalTime.parse(text),use24Hour) }.getOrDefault(text)
    return if(field in setOf("status","frequency","mode","importance","sound","tone","template","category"))when(text){"todo"->"To do";"in_progress"->"In progress";"done"->"Done";"everyone"->"Everyone";"assigned"->"Assigned person or creator";"people"->"Selected people";"food"->"Food & cooking";"honey_do"->"Honey-do";else->text.replace('_',' ').replaceFirstChar { it.uppercase() }} else text
}
fun mergeHistory(cache:Cache,page:HistoryPage,at:Long):HistoryCache {
    val basis=Basis(page.epoch,page.generation)
    require(cache.snapshot?.basis==basis&&cache.snapshot?.actor?.user_id==page.actor_id){"The history response belongs to another workspace or account."}
    val entries=(page.entries+cache.currentHistory()?.takeIf {it.history_revision==page.history_revision}?.entries.orEmpty()).distinctBy { it.sequence }.sortedByDescending { it.sequence }.take(200).toMutableList()
    while(entries.isNotEmpty()&&WireJson.encodeToString(entries).encodeToByteArray().size>512*1024)entries.removeAt(entries.lastIndex)
    return HistoryCache(basis,page.actor_id,entries,at,page.history_revision)
}
