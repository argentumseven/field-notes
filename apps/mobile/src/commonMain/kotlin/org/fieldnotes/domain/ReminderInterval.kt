package org.fieldnotes.domain

import kotlinx.datetime.*
import kotlinx.serialization.Serializable

const val REMINDER_INTERVAL_FEATURE="reminder-interval-v1"
fun Cache.supportsReminderInterval()=compatibility?.let{!it.blocked&&REMINDER_INTERVAL_FEATURE in it.features}==true
@Serializable data class ReminderInterval(val id:String,val amount:Int=1,val unit:String="hours",val anchor_at:Long,val zone:String,val importance:String="normal",val sound:String="chime",val volume:Int=75,val vibrate:Boolean=true) {
    val profile get()=AlertProfile(importance,sound,volume,vibrate)
}
fun validateReminderInterval(rule:ReminderInterval) {
    require(rule.amount in 1..(reminderTimeLimits[rule.unit] ?: 0)){"Choose a positive repeat interval, from minutes up to ten years."}
    require(rule.anchor_at in 1..4_102_444_800L){"Choose a valid repeat start time."}
    TimeZone.of(rule.zone)
}
fun hasReminderAnchor(record:Record)=when(record.kind) {
    "task"->record.data.text("due").isNotBlank()||record.data.text("planned_for").isNotBlank()
    "note"->record.data.number("remind_at")>0
    "event"->record.data.number("starts_at")>0||record.data.text("start_date").isNotBlank()
    else->false
}
/** A sound/importance edit preserves the phase. New or changed cadences start at save,
 * including offline saves; replay and sync retain that same immutable anchor. */
fun prepareReminderSave(value:ReminderSet,previous:ReminderSet?,savedAt:Long):ReminderSet {
    val rule=value.interval ?: return value
    val old=previous?.interval
    val reset=old==null||old.id!=rule.id||old.amount!=rule.amount||old.unit!=rule.unit||old.zone!=rule.zone
    return value.copy(interval=if(reset)rule.copy(anchor_at=savedAt)else rule,timing_version=3)
}
fun intervalOccurrence(rule:ReminderInterval,index:Long):Long {
    require(index>=1)
    val elapsed=mapOf("minutes" to 60L,"hours" to 3600L,"days" to 86400L,"weeks" to 604800L)[rule.unit]
    if(elapsed!=null)return rule.anchor_at+index*rule.amount*elapsed
    val zone=TimeZone.of(rule.zone);val anchor=Instant.fromEpochSeconds(rule.anchor_at).toLocalDateTime(zone)
    val count=(index*rule.amount).toInt()
    val date=anchor.date.plus(count,if(rule.unit=="months")DateTimeUnit.MONTH else DateTimeUnit.YEAR)
    return LocalDateTime(date,anchor.time).toInstant(zone).epochSeconds
}
/** Jump directly to the current phase; no minute-by-minute enumeration, even after years.
 * Keep one missed and two future occurrences, including long intervals beyond eight days. */
fun intervalAlerts(record:Record,rule:ReminderInterval,now:Instant):List<LocalAlert> {
    validateReminderInterval(rule)
    val step=mapOf("minutes" to 60L,"hours" to 3600L,"days" to 86400L,"weeks" to 604800L)[rule.unit]?.times(rule.amount)
    val estimate=if(step!=null)((now.epochSeconds-rule.anchor_at)/step).coerceAtLeast(0) else {
        val zone=TimeZone.of(rule.zone);val current=now.toLocalDateTime(zone).date;val anchor=Instant.fromEpochSeconds(rule.anchor_at).toLocalDateTime(zone).date
        ((current.year-anchor.year)*12L+current.monthNumber-anchor.monthNumber).coerceAtLeast(0)/(rule.amount*(if(rule.unit=="years")12L else 1L))
    }
    var missed:LocalAlert?=null;val future=mutableListOf<LocalAlert>()
    for(index in (estimate-2).coerceAtLeast(1)..estimate.coerceAtLeast(1)+4) {
        val at=intervalOccurrence(rule,index)
        val alert=LocalAlert("interval:${record.id}:${rule.id}:$at",at,record.id,"interval",effectiveAlertProfile(record,rule.id,rule.profile),"${record.id}:${rule.id}",sourceAt=at)
        if(at<=now.epochSeconds)missed=alert else if(future.size<2)future+=alert
    }
    return listOfNotNull(missed)+future
}
