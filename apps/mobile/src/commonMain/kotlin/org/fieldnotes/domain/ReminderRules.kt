package org.fieldnotes.domain

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class AlertProfile(val importance:String="normal",val sound:String="chime",val volume:Int=75,val vibrate:Boolean=true,val duration:Int=0,val alarm:Boolean=false,val unmute:Boolean=false,val alarmVolume:Int=100)
@Serializable data class ReminderAlert(val id:String,val minutes_before:Int=10,val importance:String="normal",val sound:String="chime",val volume:Int=75,val vibrate:Boolean=true) {
    val profile get()=AlertProfile(importance,sound,volume,vibrate)
}
@Serializable data class ReminderWindow(val id:String,val weekdays:List<Int> = emptyList(),val starts_on:String,val ends_on:String="",val from:String="09:00",val until:String="18:00",val every_minutes:Int=60,val zone:String,
    val importance:String="normal",val sound:String="chime",val volume:Int=75,val vibrate:Boolean=true) { val profile get()=AlertProfile(importance,sound,volume,vibrate) }
@Serializable data class ReminderSet(val alerts:List<ReminderAlert> = emptyList(),val repeat:ReminderWindow?=null)
fun reminderSet(record:Record):ReminderSet? = (if(record.data.containsKey("reminder_rules"))record.data["reminder_rules"] else record.data["reminders"])?.takeUnless { it==JsonNull }?.let { runCatching { WireJson.decodeFromJsonElement<ReminderSet>(it) }.getOrNull() }
fun reminderData(value:ReminderSet)=WireJson.encodeToJsonElement(value).jsonObject
fun validateReminders(value:ReminderSet) {
    require(value.alerts.size<=8) { "Use up to eight reminders." }
    fun profile(p:AlertProfile) { require(p.importance in setOf("quiet","normal","urgent") && p.sound in setOf("chime","bell","pulse") && p.volume in setOf(25,50,75,100)) { "Choose a sound and importance." } }
    value.alerts.forEach { require(it.minutes_before in 0..43200) { "Choose a reminder from the start to 30 days before." };profile(it.profile) }
    value.repeat?.let { r ->
        val start=LocalDate.parse(r.starts_on);r.ends_on.takeIf { it.isNotBlank() }?.let { require(LocalDate.parse(it)>=start) { "Last day must follow first day." } }
        TimeZone.of(r.zone);LocalTime.parse(r.until);LocalTime.parse(r.from)
        require(r.every_minutes in 1..1440) { "Choose an interval from 1 to 1440 minutes." }
        require(r.weekdays.size<=7&&r.weekdays.toSet().size==r.weekdays.size&&r.weekdays.all { it in 1..7 })
        profile(r.profile)
    }
    val ids=value.alerts.map { it.id }+listOfNotNull(value.repeat?.id)
    require(ids.distinct().size==ids.size) { "Reminder IDs must be distinct." }
}
/** Only the latest missed repeat occurrence is caught up; a long disconnection
 * never plays every missed interval. Two future occurrences refill after firing. */
fun customAlerts(record:Record, now:Instant, deviceZone:TimeZone):List<LocalAlert> {
    val rules=reminderSet(record) ?: return emptyList()
    if(runCatching { validateReminders(rules) }.isFailure)return emptyList()
    val d=record.data;val today=now.toLocalDateTime(deviceZone).date
    val anchor=if(record.kind=="event"&&!record.flag("all_day")) d.number("starts_at").takeIf { it>0 }
        else runCatching { LocalDate.parse(if(record.kind=="event")d.text("start_date") else d.text("due").ifBlank { d.text("planned_for") }) }.getOrNull()?.let { LocalDateTime(it,LocalTime(9,0)).toInstant(deviceZone).epochSeconds }
    val horizon=today.plus(8,DateTimeUnit.DAY).atStartOfDayIn(deviceZone).epochSeconds
    val result=rules.alerts.mapNotNull { r -> anchor?.minus(r.minutes_before*60L)?.takeIf { it>=now.epochSeconds-600 && it<horizon }?.let {
        LocalAlert("rule:${record.id}:${r.id}:$it",it,record.id,if(record.kind=="event")"calendar" else "task",effectiveAlertProfile(record,r.id,r.profile),"${record.id}:${r.id}")
    }}.toMutableList()
    rules.repeat?.let { r ->
        val zone=TimeZone.of(r.zone);val localToday=now.toLocalDateTime(zone).date;val first=LocalDate.parse(r.starts_on);val last=r.ends_on.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        val from=LocalTime.parse(r.from).toSecondOfDay()/60;val until=LocalTime.parse(r.until).toSecondOfDay()/60
        val span=((until-from+1440)%1440).takeIf { it>0 } ?: 1440
        var missed:LocalAlert?=null;val future=mutableListOf<LocalAlert>()
        days@ for(offset in -1..8) {
            val day=localToday.plus(offset,DateTimeUnit.DAY)
            if(day<first||(last!=null&&day>last)||(r.weekdays.isEmpty()&&day!=first)||(r.weekdays.isNotEmpty()&&day.dayOfWeek.isoDayNumber !in r.weekdays))continue
            for(delta in 0 until span step r.every_minutes) {
                val minutes=from+delta;val date=day.plus(minutes/1440,DateTimeUnit.DAY);val time=LocalTime.fromSecondOfDay(minutes%1440*60)
                val at=runCatching { scheduledInstant(date.toString(),time.toString().take(5),zone) }.getOrNull() ?: continue
                if(at>=horizon)continue
                val item=LocalAlert("window:${record.id}:${r.id}:$at",at,record.id,"repeat",effectiveAlertProfile(record,r.id,r.profile),"${record.id}:${r.id}")
                if(at<=now.epochSeconds&&at>=now.epochSeconds-600&&(missed==null||at>missed!!.at))missed=item
                else if(at>now.epochSeconds&&future.size<2){future+=item;if(future.size==2)break@days}
            }
        }
        missed?.let { result+=it };result+=future
    }
    return result
}

/** Recurring reminder windows are visible context, never occupied time blocks. */
fun reminderWindowLabel(record:Record,day:LocalDate,displayZone:TimeZone):String? {
    if(record.kind=="task"&&record.data.text("status")=="done")return null
    val rule=reminderSet(record)?.repeat ?: return null
    return runCatching {
        val zone=TimeZone.of(rule.zone);val selectedStart=day.atStartOfDayIn(displayZone);val selectedEnd=day.plus(1,DateTimeUnit.DAY).atStartOfDayIn(displayZone)
        val reference=selectedStart.toLocalDateTime(zone).date;val first=LocalDate.parse(rule.starts_on);val last=rule.ends_on.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        val from=LocalTime.parse(rule.from);val until=LocalTime.parse(rule.until)
        val overlaps=(-1..1).any { offset ->
            val date=reference.plus(offset,DateTimeUnit.DAY)
            date>=first&&(last==null||date<=last)&&(if(rule.weekdays.isEmpty())date==first else date.dayOfWeek.isoDayNumber in rule.weekdays)&&
                LocalDateTime(date,from).toInstant(zone)<selectedEnd&&LocalDateTime(date.plus(if(until<=from)1 else 0,DateTimeUnit.DAY),until).toInstant(zone)>selectedStart
        }
        if(!overlaps)null else "Every ${rule.every_minutes} min · ${friendlyTime(from)}–${friendlyTime(until)}"+(if(zone!=displayZone)" · ${zone.id.substringAfterLast('/').replace('_',' ')}" else "")
    }.getOrNull()
}
