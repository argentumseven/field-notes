package org.fieldnotes.domain

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val PLANNER_FEATURE="household-planner-v3"
fun Cache.supportsPlannerV3()=compatibility?.let { !it.blocked&&PLANNER_FEATURE in it.features }==true
@Serializable data class EventRepeat(val frequency:String="weekly",val interval:Int=1,val weekdays:List<Int> = emptyList(),val dates:List<String> = emptyList(),val until:String="",val excluded:List<String> = emptyList(),val zone:String)
fun eventRepeat(record:Record):EventRepeat?=record.data["recurrence"]?.takeUnless { it==JsonNull }?.let { runCatching { WireJson.decodeFromJsonElement<EventRepeat>(it) }.getOrNull() }
fun repeatData(value:EventRepeat?)=value?.let { WireJson.encodeToJsonElement(it) } ?: JsonNull
fun validateRepeat(value:EventRepeat) {
    require(value.frequency in setOf("daily","weekly","monthly","dates")&&value.interval in 1..365){"Choose a repeat pattern and interval from 1 to 365."}
    TimeZone.of(value.zone)
    require(value.weekdays.size<=7&&value.weekdays.distinct().size==value.weekdays.size&&value.weekdays.all { it in 1..7 }&&(value.frequency!="weekly"||value.weekdays.isNotEmpty())){"Choose the days of the week."}
    for(dates in listOf(value.dates,value.excluded)){require(dates.size<=128&&dates.distinct().size==dates.size){"Choose up to 128 distinct dates."};dates.forEach { LocalDate.parse(it) }}
    require(value.frequency!="dates"||value.dates.isNotEmpty()){"Choose at least one occurrence date."}
    if(value.until.isNotBlank())LocalDate.parse(value.until)
}
fun repeatMatches(value:EventRepeat,first:LocalDate,date:LocalDate):Boolean {
    if(date<first||value.until.isNotBlank()&&date>LocalDate.parse(value.until)||date.toString() in value.excluded)return false
    val days=first.daysUntil(date)
    return when(value.frequency){
        "daily"->days%value.interval==0
        "weekly"->((days+first.dayOfWeek.isoDayNumber%7)/7)%value.interval==0&&date.dayOfWeek.isoDayNumber in value.weekdays
        "monthly"->((date.year-first.year)*12+date.monthNumber-first.monthNumber)%value.interval==0&&date.dayOfMonth==first.dayOfMonth
        "dates"->date.toString() in value.dates
        else->false
    }
}
/** Expand only the requested range, retaining the original series identity.
 * Recurrence follows its stored wall-clock zone, including DST, never 86400s
 * arithmetic. A skipped spring-forward clock time skips that occurrence. */
fun eventInstances(record:Record,from:LocalDate,until:LocalDate,displayZone:TimeZone):List<Record> {
    if(record.kind!="event")return listOf(record)
    val rule=eventRepeat(record) ?: return if(record.data["recurrence"]!=null&&record.data["recurrence"]!=JsonNull)emptyList() else listOf(record)
    if(runCatching { validateRepeat(rule) }.isFailure)return emptyList()
    val zone=TimeZone.of(rule.zone);val allDay=record.flag("all_day");val d=record.data
    val start=if(allDay)null else Instant.fromEpochSeconds(d.number("starts_at")).toLocalDateTime(zone)
    val finish=if(allDay||d.number("ends_at")==0L)null else Instant.fromEpochSeconds(d.number("ends_at")).toLocalDateTime(zone)
    val first=if(allDay)LocalDate.parse(d.text("start_date")) else start!!.date
    val days=if(allDay)first.daysUntil(LocalDate.parse(d.text("end_date"))) else finish?.let { start!!.date.daysUntil(it.date) } ?: 0
    require(days in 0..31){"Event duration exceeds the repeat expansion bound."}
    val rangeStart=if(allDay)from else from.atStartOfDayIn(displayZone).toLocalDateTime(zone).date
    val rangeEnd=if(allDay)until else until.atStartOfDayIn(displayZone).toLocalDateTime(zone).date.plus(1,DateTimeUnit.DAY)
    require(rangeStart.daysUntil(rangeEnd) in 0..400){"Choose a calendar range up to one year."}
    val result=mutableListOf<Record>()
    var day=maxOf(first,rangeStart.minus(days+1,DateTimeUnit.DAY))
    while(day<rangeEnd) {
        if(repeatMatches(rule,first,day)) {
            val next=if(allDay)JsonObject(d+mapOf("start_date" to day.toString().j(),"end_date" to day.plus(days,DateTimeUnit.DAY).toString().j(),"occurrence_date" to day.toString().j()))
            else runCatching {
                val at=scheduledInstant(day.toString(),start!!.time.toString(),zone,if(day==first)d.number("starts_at") else 0)
                val end=finish?.let { scheduledInstant(day.plus(days,DateTimeUnit.DAY).toString(),it.time.toString(),zone,if(day==first)d.number("ends_at") else 0) } ?: 0
                require(end==0L||end>at)
                JsonObject(d+mapOf("starts_at" to at.j(),"ends_at" to if(end==0L)JsonNull else end.j(),"occurrence_date" to day.toString().j()))
            }.getOrNull()
            if(next!=null) {
                val fromAt=from.atStartOfDayIn(displayZone).epochSeconds;val untilAt=until.atStartOfDayIn(displayZone).epochSeconds
                val intersects=if(allDay)next.text("start_date")<until.toString()&&next.text("end_date")>from.toString()
                    else next.number("starts_at")<untilAt&&(if(next.number("ends_at")==0L)next.number("starts_at")>=fromAt else next.number("ends_at")>fromAt)
                if(intersects)result+=record.copy(data=next)
            }
        }
        day=day.plus(1,DateTimeUnit.DAY)
    }
    return result
}
fun repeatLabel(rule:EventRepeat):String = when(rule.frequency) {
    "daily"->if(rule.interval==1)"Every day" else if(rule.interval==2)"Every other day" else "Every ${rule.interval} days"
    "weekly"->(if(rule.interval==1)"Weekly" else if(rule.interval==2)"Alternate weeks" else "Every ${rule.interval} weeks")+" · "+rule.weekdays.sortedBy { it%7 }.joinToString(", "){DayOfWeek.entries[it-1].name.take(3).lowercase().replaceFirstChar { c->c.uppercase() }}
    "monthly"->if(rule.interval==1)"Monthly" else "Every ${rule.interval} months"
    else->"${rule.dates.size} chosen dates"
}+(if(rule.until.isNotBlank())" · through ${friendlyDate(LocalDate.parse(rule.until))}" else "")
