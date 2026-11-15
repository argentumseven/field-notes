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
    val copy=data.toMutableMap();copy["recurrence"]=JsonNull
    if(all){copy["start_date"]=date.toString().j();copy["end_date"]=LocalDate.parse(data.text("end_date")).plus(delta,DateTimeUnit.DAY).toString().j()}
    else for(key in listOf("starts_at","ends_at")){val at=data.number(key);if(at>0){val local=Instant.fromEpochSeconds(at).toLocalDateTime(zone);val shifted=(LocalDateTime(local.date.plus(delta,DateTimeUnit.DAY),local.time).toInstant(TimeZone.UTC)+timeShift.seconds).toLocalDateTime(TimeZone.UTC);val instant=shifted.toInstant(zone);require(instant.toLocalDateTime(zone)==shifted){"A copy time is skipped by daylight saving. Choose another date."};copy[key]=instant.epochSeconds.j()}}
    data["reminders"]?.let{raw->val rules=WireJson.decodeFromJsonElement<ReminderSet>(raw);val window=rules.repeat?.let{it.copy(starts_on=LocalDate.parse(it.starts_on).plus(delta,DateTimeUnit.DAY).toString(),ends_on=it.ends_on.takeIf{it.isNotBlank()}?.let{d->LocalDate.parse(d).plus(delta,DateTimeUnit.DAY).toString()}.orEmpty())};copy["reminders"]=reminderData(rules.copy(repeat=window))}
    return JsonObject(copy)
}
