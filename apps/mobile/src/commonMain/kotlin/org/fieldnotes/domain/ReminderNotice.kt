package org.fieldnotes.domain

import kotlinx.datetime.*

data class ReminderNotice(val title:String,val summary:String,val details:String,val target:String?,val whenAt:Long)
fun reminderNotice(cache:Cache,alerts:List<LocalAlert>,zone:TimeZone,now:Instant=Clock.System.now()):ReminderNotice {
    val records=projectRecords(cache).associate { it.record.id to it.record }
    fun occurrence(alert:LocalAlert):Record? {
        val r=records[alert.target] ?: return null
        if(r.kind!="event"||eventRepeat(r)==null||alert.sourceAt==null)return r
        val date=Instant.fromEpochSeconds(alert.sourceAt).toLocalDateTime(zone).date
        return eventInstances(r,date.minus(1,DateTimeUnit.DAY),date.plus(2,DateTimeUnit.DAY),zone).firstOrNull { if(it.flag("all_day"))it.data.text("start_date")==date.toString() else it.data.number("starts_at")==alert.sourceAt } ?: r
    }
    val occurrences=alerts.mapNotNull(::occurrence).distinctBy { it.id+":"+it.data.text("occurrence_date") }
    val row=occurrences.firstOrNull()
    val targets=alerts.mapNotNull { it.target }.distinct()
    if(!cache.preferences.notificationTitles)return ReminderNotice("Field Notes reminder","Reminder details are hidden in your visibility settings.","Open Field Notes → Settings → Detailed reminder notifications to show the title, schedule, person and notes.",targets.singleOrNull(),alerts.minOfOrNull { it.at } ?: 0)
    fun people(record:Record):String {
        val a=reminderOptions(record)?.audience
        if(a?.mode=="everyone")return "Everyone"
        val ids=if(a?.mode=="people")a.people else listOf(record.data.text("assignee").ifBlank { recordCreator(record) }).filter { it.isNotBlank() }
        return ids.joinToString(", "){ id->cache.people.find { it.id==id }?.name ?: cache.snapshot?.actor?.takeIf { it.user_id==id }?.name ?: record.data.text("created_by_name").ifBlank { "Household member" } }.ifBlank { "Household" }
    }
    fun schedule(record:Record):String {
        val d=record.data
        return when {
            record.kind=="note"&&d.number("remind_at")>0->Instant.fromEpochSeconds(d.number("remind_at")).toLocalDateTime(zone).let { "Follow up · ${friendlyDate(it.date)} · ${friendlyTime(it.time)}" }
            record.kind !in setOf("task","event")->"Shared ${record.kind} · updated"
            record.kind=="event"&&record.flag("all_day")->"All day · ${d.text("start_date")}"+(d.text("end_date").takeIf { it.isNotBlank() }?.let { " to ${LocalDate.parse(it).minus(1,DateTimeUnit.DAY)}" } ?: "")
            record.kind=="event"->Instant.fromEpochSeconds(d.number("starts_at")).toLocalDateTime(zone).let { "${friendlyDate(it.date)} · ${friendlyTime(it.time)}" }+(d.number("ends_at").takeIf { it>0 }?.let { " – "+friendlyTime(Instant.fromEpochSeconds(it).toLocalDateTime(zone).time) } ?: " · start only")
            d.text("due").isNotBlank()->"Due ${d.text("due")}"+(d.text("planned_for").takeIf { it.isNotBlank() }?.let{" · Planned $it"} ?: "")
            d.text("planned_for").isNotBlank()->"Planned ${d.text("planned_for")}"
            else->"Recurring reminder · no due date"
        }
    }
    val today=now.toLocalDateTime(zone).date
    fun dayLabel(day:LocalDate)=when(day){today->"Today";today.plus(1,DateTimeUnit.DAY)->"Tomorrow";else->"${day.month.name.take(3).lowercase().replaceFirstChar{it.uppercase()}} ${day.dayOfMonth}"+(if(day.year!=today.year)" ${day.year}" else "")}
    fun compact(record:Record):String {
        val d=record.data
        return when {
            record.kind=="event"&&record.flag("all_day")->"All day · "+dayLabel(LocalDate.parse(d.text("start_date")))
            record.kind=="event"->Instant.fromEpochSeconds(d.number("starts_at")).toLocalDateTime(zone).let {"${friendlyTime(it.time)} · ${dayLabel(it.date)}"}
            record.kind=="task"&&d.text("due").isNotBlank()->"Due "+dayLabel(LocalDate.parse(d.text("due"))).lowercase()
            record.kind=="task"&&d.text("planned_for").isNotBlank()->"Planned "+dayLabel(LocalDate.parse(d.text("planned_for"))).lowercase()
            record.kind=="task"->"Recurring reminder"
            record.kind=="note"&&d.number("remind_at")>0->Instant.fromEpochSeconds(d.number("remind_at")).toLocalDateTime(zone).let { "Follow up · ${friendlyTime(it.time)} · ${dayLabel(it.date)}" }
            else->"Shared ${record.kind} updated"
        }
    }
    val title=if(targets.size==1&&row!=null)row.data.text("title").ifBlank { "Field Notes reminder" } else if(alerts.all { it.kind=="overdue" })"Overdue tasks" else "${targets.size.coerceAtLeast(1)} reminders"
    val shown=if(targets.isEmpty())records.values.filter { it.kind=="task"&&it.data.text("status")!="done"&&reminderRecipient(it,cache.snapshot?.actor?.user_id.orEmpty())&&allowsItemReminders(cache,it)&&it.data.text("due").isNotBlank()&&it.data.text("due")<Clock.System.now().toLocalDateTime(zone).date.toString() }.take(8) else occurrences.take(8)
    val details=shown.joinToString("\n\n") { r -> buildString {
        append(r.data.text("title"));append('\n');append(schedule(r));append("\nFor ");append(people(r))
        r.data.text("project_id").takeIf { it.isNotBlank() }?.let { id->records[id]?.data?.text("title")?.let { append("\nList / project: ");append(it) } }
        r.data.text("location").trim().takeIf{it.isNotBlank()}?.let {append("\nLocation: ");append(it.take(2048))}
        r.data.text("notes").ifBlank {r.data.text("body")}.trim().takeIf { it.isNotBlank() }?.let { append("\n\n");append(it.take(1800)) }
        append("\n\n");append(recordHistory(cache,r,zone))
    } }.take(7000)
    val summary=if(row!=null)"For ${people(row)} · ${compact(row)}" else shown.joinToString(" · "){it.data.text("title")}.take(240)
    return ReminderNotice(title.take(240),summary.take(400),details,targets.singleOrNull(),alerts.minOfOrNull { it.at } ?: 0)
}

fun reminderCompatibilityWarning(cache:Cache):String? {
    for(row in projectRecords(cache)) {
        val r=row.record
        if(r.kind !in setOf("task","event","note"))continue
        val raw=if(r.data.containsKey("reminder_rules"))r.data["reminder_rules"] else r.data["reminders"]
        if(raw!=null&&raw!=kotlinx.serialization.json.JsonNull) {
            val rules=reminderSet(r) ?: return "A reminder uses a newer format. Update the app; cached tasks and edits are kept."
            if(runCatching { validateReminders(rules) }.isFailure)return "A reminder schedule needs review. Open its Reminders settings."
        }
        if(r.data.containsKey("reminder_options")) {
            val options=reminderOptions(r) ?: return "A reminder option needs a newer app. Update without resetting pairing."
            if(runCatching { validateReminderOptions(options) }.isFailure)return "Some reminder options are newer than this app. Basic compatible alerts remain available; review App updates."
        }
    }
    return null
}
