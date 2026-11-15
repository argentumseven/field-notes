package org.fieldnotes.domain

import kotlinx.datetime.*
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds

fun Record.isMeal()=kind=="event"&&data.text("meal_kind") in setOf("dinner","prep")
/** Clone the wall-clock schedule, including overnight/all-day lengths. A clone
 * starts an independent one-off; recurring series and their exclusions stay put. */
fun eventCopyForDate(data:JsonObject,date:LocalDate,zone:TimeZone,time:LocalTime?=null):JsonObject {
    val all=data["all_day"]==JsonPrimitive(true)
    val original=if(all)LocalDate.parse(data.text("start_date"))else Instant.fromEpochSeconds(data.number("starts_at")).toLocalDateTime(zone).date
    val delta=original.daysUntil(date)
    val timeShift=if(!all&&time!=null)time.toSecondOfDay()-Instant.fromEpochSeconds(data.number("starts_at")).toLocalDateTime(zone).time.toSecondOfDay()else 0
    val copy=data.toMutableMap();(data["meal_plan"] as? JsonObject)?.let{copy["meal_plan"]=JsonObject(it+("progress" to JsonArray(emptyList())))};copy["recurrence"]=JsonNull
    if(all){copy["start_date"]=date.toString().j();copy["end_date"]=LocalDate.parse(data.text("end_date")).plus(delta,DateTimeUnit.DAY).toString().j()}
    else for(key in listOf("starts_at","ends_at")){val at=data.number(key);if(at>0){val local=Instant.fromEpochSeconds(at).toLocalDateTime(zone);val shifted=(LocalDateTime(local.date.plus(delta,DateTimeUnit.DAY),local.time).toInstant(TimeZone.UTC)+timeShift.seconds).toLocalDateTime(TimeZone.UTC);val instant=shifted.toInstant(zone);require(instant.toLocalDateTime(zone)==shifted){"A copy time is skipped by daylight saving. Choose another date."};copy[key]=instant.epochSeconds.j()}}
    data["reminders"]?.let{raw->val rules=WireJson.decodeFromJsonElement<ReminderSet>(raw);val window=rules.repeat?.let{it.copy(starts_on=LocalDate.parse(it.starts_on).plus(delta,DateTimeUnit.DAY).toString(),ends_on=it.ends_on.takeIf{it.isNotBlank()}?.let{d->LocalDate.parse(d).plus(delta,DateTimeUnit.DAY).toString()}.orEmpty())};copy["reminders"]=reminderData(rules.copy(repeat=window))}
    return JsonObject(copy)
}

/** A ready-time edit changes deadlines, never an actual cooking clock. The
 * server repeats this mapping against its current revision before accepting it. */
fun keepCookingProgress(before:JsonObject,after:JsonObject,displayZone:TimeZone):JsonObject {
    val plan=cookingPlan(Record("retime","event",0,after))?:return after
    if(plan.progress.isEmpty())return after
    val oldStart=before.number("starts_at");val newStart=after.number("starts_at")
    val oldRule=before["recurrence"]?:JsonNull;val newRule=after["recurrence"]?:JsonNull
    if(oldStart==newStart&&oldRule==newRule)return after
    val updated=Record("retime","event",1,after)
    fun matches(at:Long):Boolean {val date=Instant.fromEpochSeconds(at).toLocalDateTime(displayZone).date;return eventInstances(updated,date.minus(1,DateTimeUnit.DAY),date.plus(2,DateTimeUnit.DAY),displayZone).any{it.data.number("starts_at")==at}}
    val progress=plan.progress.map{p->
        val candidate=if(p.occurrence==oldStart&&matches(newStart))newStart
        else if(oldRule!=JsonNull&&oldRule==newRule)runCatching {
            val zone=TimeZone.of(oldRule.jsonObject.text("zone"));val first=Instant.fromEpochSeconds(oldStart).toLocalDateTime(zone);val next=Instant.fromEpochSeconds(newStart).toLocalDateTime(zone)
            val date=Instant.fromEpochSeconds(p.occurrence).toLocalDateTime(zone).date.plus(first.date.daysUntil(next.date),DateTimeUnit.DAY)
            val local=LocalDateTime(date,next.time);val at=if(date==next.date)newStart else local.toInstant(zone).epochSeconds
            at.takeIf{Instant.fromEpochSeconds(it).toLocalDateTime(zone)==local&&matches(it)}
        }.getOrNull()else null
        val anchor=candidate?:p.occurrence.takeIf(::matches)
        require(anchor!=null||p.state!="running"){"This repeat change would detach a running phase. Finish or reset that phase, or clone an independent food plan."}
        p.copy(occurrence=anchor?:p.occurrence)
    }
    require(progress.map{it.occurrence to it.stage_id}.distinct().size==progress.size){"That schedule would combine cooking progress. Choose another time or reset the affected phases."}
    return JsonObject(after+("meal_plan" to cookingData(plan.copy(progress=progress))))
}
