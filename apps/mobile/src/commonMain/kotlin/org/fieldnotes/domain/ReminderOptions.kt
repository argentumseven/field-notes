package org.fieldnotes.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Extensions live beside the v1 reminder rules, so older paired clients can
 * still decode and deliver a basic notification during a rolling update. */
@Serializable data class ReminderAudience(val mode:String="everyone",val people:List<String> = emptyList())
@Serializable data class ReminderPlayback(val tone:String="",val duration_seconds:Int=0,val alarm:Boolean=false,val unmute:Boolean=false,val alarm_volume:Int=100)
@Serializable data class ReminderOptions(val audience:ReminderAudience=ReminderAudience(),val playback:Map<String,ReminderPlayback> = emptyMap())
val reminderTones=linkedMapOf("chime" to "Chime","bell" to "Bell","pulse" to "Pulse","beacon" to "Beacon","marimba" to "Marimba","orbit" to "Orbit","sunrise" to "Sunrise","siren" to "Siren")
private val ReminderJson=Json { ignoreUnknownKeys=true;encodeDefaults=true }
fun reminderOptions(record:Record):ReminderOptions? = record.data["reminder_options"]?.let { runCatching { ReminderJson.decodeFromJsonElement<ReminderOptions>(it) }.getOrNull() }
fun reminderOptionsData(value:ReminderOptions)=ReminderJson.encodeToJsonElement(value).jsonObject
fun reminderRecipient(record:Record,user:String):Boolean {
    val audience=reminderOptions(record)?.audience
    if(audience==null&&record.data.containsKey("reminder_options"))return false
    if(audience==null)return record.kind!="task" || record.data.text("assignee").let { it.isBlank()||it==user } || record.data.text("status")=="in_progress"&&record.data.text("claimant")==user
    return when(audience.mode) {
        "everyone"->true
        "people"->user in audience.people
        "assigned"->record.data.text("assignee").ifBlank { recordCreator(record) }.let { it.isBlank()||it==user }
        else->false // Unknown recipient policies need review, never a broadcast.
    }
}
fun validateReminderOptions(value:ReminderOptions) {
    require(value.audience.mode in setOf("everyone","people","assigned")) { "Choose who receives reminders." }
    require(value.audience.people.size<=64 && value.audience.people.distinct().size==value.audience.people.size) { "Choose distinct people." }
    require(value.audience.mode!="people"||value.audience.people.isNotEmpty()) { "Choose at least one person." }
    require(value.playback.size<=9) { "Use up to nine sound settings." }
    value.playback.values.forEach {
        require(it.tone.isEmpty()||it.tone in reminderTones) { "Choose a supported sound." }
        require(it.duration_seconds in setOf(0,5,10,15,30,60) && it.alarm_volume in 25..100) { "Choose the alarm duration and volume." }
        require(!it.unmute||it.alarm) { "Unmuting is available for alarm reminders." }
    }
}
fun effectiveAlertProfile(record:Record,id:String,base:AlertProfile):AlertProfile {
    val p=reminderOptions(record)?.playback?.get(id) ?: return base
    return base.copy(sound=p.tone.takeIf { it in reminderTones } ?: base.sound,duration=p.duration_seconds.takeIf { it in setOf(0,5,10,15,30,60) } ?: 0,alarm=p.alarm,unmute=p.unmute&&p.alarm,alarmVolume=p.alarm_volume.coerceIn(25,100))
}
fun playbackOptions(profile:AlertProfile)=ReminderPlayback(profile.sound,profile.duration,profile.alarm,profile.unmute,profile.alarmVolume)
fun legacyTone(tone:String)=tone.takeIf { it in setOf("chime","bell","pulse") } ?: "chime"
fun ReminderPlayback.applyTo(base:AlertProfile)=base.copy(sound=tone.ifBlank { base.sound },duration=duration_seconds,alarm=alarm,unmute=unmute,alarmVolume=alarm_volume)
fun prunedReminderOptions(value:ReminderOptions,rules:ReminderSet):ReminderOptions {
    val ids=rules.alerts.map { it.id }.toSet()+listOfNotNull(rules.repeat?.id)
    return value.copy(playback=value.playback.filterKeys { it in ids })
}
