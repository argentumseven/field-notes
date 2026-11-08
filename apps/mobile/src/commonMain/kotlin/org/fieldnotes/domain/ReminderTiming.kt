package org.fieldnotes.domain

import kotlinx.datetime.*
import kotlinx.serialization.Serializable

const val REMINDER_TIMING_FEATURE="reminder-timing-v1"
fun Cache.supportsReminderTiming()=compatibility?.let{!it.blocked&&REMINDER_TIMING_FEATURE in it.features}==true
val reminderTimeUnits=linkedMapOf("minutes" to "Minutes","hours" to "Hours","days" to "Days","weeks" to "Weeks","months" to "Months","years" to "Years")
val reminderTimeLimits=mapOf("minutes" to 5_270_400,"hours" to 87_840,"days" to 3660,"weeks" to 522,"months" to 120,"years" to 10)
@Serializable data class ReminderLead(val amount:Int,val unit:String,val zone:String)
data class ReminderTimeParts(val amount:Int,val unit:String)
fun reminderTimeParts(rule:ReminderAlert):ReminderTimeParts {
    rule.lead_time?.let{return ReminderTimeParts(it.amount,it.unit)}
    val minutes=rule.minutes_before
    for((unit,factor)in listOf("weeks" to 10080,"days" to 1440,"hours" to 60))if(minutes>0&&minutes%factor==0)return ReminderTimeParts(minutes/factor,unit)
    return ReminderTimeParts(minutes,"minutes")
}
fun reminderTimeLabel(rule:ReminderAlert,use24Hour:Boolean=false):String {
    rule.day_time?.let{day->val n=day.days_before;return (if(n==0)"Day of" else if(n%7==0)"${n/7} ${if(n==7)"week"else"weeks"} before" else "$n ${if(n==1)"day"else"days"} before")+" at "+friendlyTime(LocalTime.parse(day.time),use24Hour)}
    val parts=reminderTimeParts(rule)
    return if(parts.amount==0)"At the start" else "${parts.amount} ${if(parts.amount==1)parts.unit.removeSuffix("s") else parts.unit} before"
}
fun validateReminderLead(lead:ReminderLead) {
    require(lead.unit in reminderTimeUnits&&lead.amount in 0..reminderTimeLimits.getValue(lead.unit)){"Choose a valid reminder amount and unit, up to ten years."}
    TimeZone.of(lead.zone)
}
fun withReminderTime(rule:ReminderAlert,amount:Int,unit:String,zone:String):ReminderAlert {
    val lead=ReminderLead(amount,unit,zone);validateReminderLead(lead)
    val factor=mapOf("minutes" to 1,"hours" to 60,"days" to 1440,"weeks" to 10080)[unit]
    val minutes=factor?.let{amount.toLong()*it}
    return if(amount==0||minutes!=null&&minutes<=43200)rule.copy(minutes_before=minutes?.toInt() ?: 0,lead_time=null,day_time=null)
        else rule.copy(minutes_before=0,lead_time=lead,day_time=null)
}
private fun ReminderLead.elapsedSeconds():Long?=mapOf("minutes" to 60L,"hours" to 3600L,"days" to 86400L,"weeks" to 604800L)[unit]?.times(amount)
private fun shiftCalendar(at:Instant,lead:ReminderLead,direction:Int):Instant {
    if(lead.amount==0)return at
    val zone=TimeZone.of(lead.zone);val local=at.toLocalDateTime(zone)
    val date=local.date.plus(direction*lead.amount,if(lead.unit=="months")DateTimeUnit.MONTH else DateTimeUnit.YEAR)
    // kotlinx.datetime moves nonexistent local clock times forward through a DST
    // gap and selects the first offset for an overlap. Month ends clamp normally.
    return LocalDateTime(date,local.time).toInstant(zone)
}
fun reminderAt(rule:ReminderAlert,anchor:Long):Long {
    rule.day_time?.let{day->val zone=TimeZone.of(day.zone);val date=Instant.fromEpochSeconds(anchor).toLocalDateTime(zone).date.minus(day.days_before,DateTimeUnit.DAY);return LocalDateTime(date,LocalTime.parse(day.time)).toInstant(zone).epochSeconds}
    val lead=rule.lead_time ?: return anchor-rule.minutes_before*60L
    return lead.elapsedSeconds()?.let{anchor-it} ?: shiftCalendar(Instant.fromEpochSeconds(anchor),lead,-1).epochSeconds
}
/** Invert the lead near the current delivery window rather than expanding a
 * daily series over ten years. Month-end clamping needs a bounded extra margin. */
fun reminderAnchorRange(rule:ReminderAlert,now:Instant,horizon:Long,zone:TimeZone):Pair<LocalDate,LocalDate> {
    rule.day_time?.let{day->
        val saved=TimeZone.of(day.zone)
        return now.toLocalDateTime(saved).date.plus(day.days_before,DateTimeUnit.DAY).minus(2,DateTimeUnit.DAY) to Instant.fromEpochSeconds(horizon).toLocalDateTime(saved).date.plus(day.days_before+3,DateTimeUnit.DAY)
    }
    val lead=rule.lead_time
    val seconds=lead?.elapsedSeconds() ?: if(lead==null)rule.minutes_before*60L else null
    val from=Instant.fromEpochSeconds(now.epochSeconds-600)
    val until=Instant.fromEpochSeconds(horizon)
    val start=if(seconds!=null)Instant.fromEpochSeconds(from.epochSeconds+seconds) else shiftCalendar(from,lead!!,1)
    val end=if(seconds!=null)Instant.fromEpochSeconds(until.epochSeconds+seconds) else shiftCalendar(until,lead!!,1)
    val margin=if(seconds==null)35 else 1
    return start.toLocalDateTime(zone).date.minus(margin,DateTimeUnit.DAY) to end.toLocalDateTime(zone).date.plus(margin+1,DateTimeUnit.DAY)
}

@Serializable data class ReminderDayTime(val days_before:Int,val time:String,val zone:String)
fun Cache.supportsReminderSchedule()=compatibility?.let{!it.blocked&&"reminder-schedule-v2" in it.features}==true
fun validateReminderDayTime(day:ReminderDayTime){
    require(day.days_before in 0..3660&&Regex("[0-9]{2}:[0-9]{2}").matches(day.time)){"Choose a valid reminder day and time."}
    LocalTime.parse(day.time);TimeZone.of(day.zone)
}
fun withReminderDayTime(rule:ReminderAlert,days:Int,time:String,zone:String):ReminderAlert {
    val day=ReminderDayTime(days,time,zone);validateReminderDayTime(day)
    return rule.copy(minutes_before=0,lead_time=null,day_time=day)
}
