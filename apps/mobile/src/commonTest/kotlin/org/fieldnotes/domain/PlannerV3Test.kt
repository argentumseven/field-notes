package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

class PlannerV3Test {
    private val zone=TimeZone.of("America/New_York")
    private fun event(date:String,time:String="10:00",repeat:EventRepeat=EventRepeat("daily",zone=zone.id),end:String?="11:00"):Record {
        val at=scheduledInstant(date,time,zone);val until=end?.let { scheduledInstant(date,it,zone) } ?: 0
        return Record("event","event",1,obj("title" to "Household check-in".j(),"all_day" to JsonPrimitive(false),"starts_at" to at.j(),"ends_at" to until.j(),"recurrence" to repeatData(repeat),"reminders" to reminderData(ReminderSet(listOf(ReminderAlert("rule",10))))))
    }
    private fun cache(records:List<Record>,sequence:Long=100)=Cache(token="token",snapshot=Snapshot(1,"workspace",1,sequence,records,Actor("me","Alex","owner","session")))
    @Test fun repeatDaysKeepWallTimeAcrossDstAndSkipNonexistentClockTimes() {
        val e=event("2026-03-07");val instances=eventInstances(e,LocalDate.parse("2026-03-07"),LocalDate.parse("2026-03-10"),zone)
        val visible=instances.filter { it.data.text("occurrence_date")>="2026-03-07" }
        assertEquals(listOf("2026-03-07T15:00:00Z","2026-03-08T14:00:00Z","2026-03-09T14:00:00Z"),visible.map { Instant.fromEpochSeconds(it.data.number("starts_at")).toString() })
        val skipped=event("2026-03-07","02:30",end=null)
        assertFalse(eventInstances(skipped,LocalDate.parse("2026-03-08"),LocalDate.parse("2026-03-09"),zone).any { it.data.text("occurrence_date")=="2026-03-08" })
    }
    @Test fun theSeriesAnchorKeepsAnExistingSecondAutumnClockOccurrence(){
        val start=Instant.parse("2026-11-01T06:30:00Z").epochSeconds
        val source=Record("fold","event",1,obj("title" to "Keep this exact time".j(),"starts_at" to start.j(),"ends_at" to JsonNull,"recurrence" to repeatData(EventRepeat("daily",zone=zone.id))))
        assertEquals(start,eventInstances(source,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-02"),zone).single().data.number("starts_at"))
    }
    @Test fun alternateWeeksUseTheAnchorWeekAndKeepMultipleSelectedDays() {
        val rule=EventRepeat("weekly",2,listOf(1,3,5),until="2026-11-20",zone=zone.id)
        val e=event("2026-11-02",repeat=rule)
        assertEquals(listOf("2026-11-02","2026-11-04","2026-11-06","2026-11-16","2026-11-18","2026-11-20"),eventInstances(e,LocalDate.parse("2026-11-02"),LocalDate.parse("2026-11-23"),zone).map { it.data.text("occurrence_date") })
    }
    @Test fun individualDatesExclusionsAndMissingMonthDaysAreExplicit() {
        val custom=EventRepeat("dates",dates=listOf("2026-11-05","2026-11-09","2026-11-21"),excluded=listOf("2026-11-09"),zone=zone.id)
        assertEquals(listOf("2026-11-05","2026-11-21"),eventInstances(event("2026-11-05",repeat=custom),LocalDate.parse("2026-11-05"),LocalDate.parse("2026-11-30"),zone).map { it.data.text("occurrence_date") })
        val monthly=event("2026-01-31",repeat=EventRepeat("monthly",zone=zone.id))
        assertEquals(listOf("2026-01-31","2026-03-31"),eventInstances(monthly,LocalDate.parse("2026-01-31"),LocalDate.parse("2026-04-01"),zone).map { it.data.text("occurrence_date") })
    }
    @Test fun repeatsReachCalendarBusyBarsOfflineAlertsAndActualNotificationDate() {
        val e=event("2026-11-05");val day=LocalDate.parse("2026-11-12");val at=scheduledInstant(day.toString(),"10:00",zone);val now=Instant.fromEpochSeconds(at-600)
        assertEquals(at,calendarEntries(listOf(e),day,zone).single().startsAt)
        assertEquals(1.0/24,occupiedFraction(listOf(e),day.atStartOfDayIn(zone).epochSeconds,day.plus(1,DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds,zone),0.00001)
        val c=cache(listOf(e));val alert=localAlerts(c,now,zone).first { it.at==at-600 }
        assertEquals(at,alert.sourceAt);assertEquals("event",alert.target)
        val notice=reminderNotice(c,listOf(alert),zone,now)
        assertTrue(notice.details.contains("November 12, 2026"),notice.details)
        assertFalse(notice.details.contains("November 5, 2026"),notice.details)
    }
    @Test fun checklistItemsStayInListsAndMutedRemindersRemainStored() {
        val list=Record("list","list",1,obj("title" to "Costco Shopping List".j(),"template" to "groceries".j(),"item_reminders" to JsonPrimitive(false)))
        val item=Record("item","task",1,obj("title" to "Bananas".j(),"project_id" to list.id.j(),"due" to "2026-11-05".j(),"reminders" to reminderData(ReminderSet(listOf(ReminderAlert("rule",0))))))
        val task=Record("task","task",1,obj("title" to "Cook dinner".j()))
        val c=cache(listOf(list,item,task));val now=Instant.parse("2026-11-05T12:00:00Z")
        assertEquals(listOf("task"),plannerTasks(c).map { it.record.id })
        assertTrue(localAlerts(c,now,zone).none { it.target=="item" })
        assertNotNull(reminderSet(item));assertEquals(listOf("item"),workspaceTasks(c,"Tasks","list","",LocalDate.parse("2026-11-05"),now.epochSeconds).map { it.record.id })
        val fieldsHidden=list.copy(data=JsonObject(list.data+("fields" to JsonArray(listOf("notes".j())))))
        assertFalse("store" in listFields(fieldsHidden),"Explicit field choices must override presets")
    }
    @Test fun additionsAreGranularRespectListMuteAndGroupBatchesWithoutSelfAlerts() {
        val list=Record("list","list",1,obj("title" to "Costco Shopping List".j(),"template" to "groceries".j()))
        fun item(id:String,seq:Long,creator:String="sophia")=Record(id,"task",1,obj("title" to (if(id=="banana")"Bananas" else id).j(),"project_id" to list.id.j(),"created_by" to creator.j(),"created_by_name" to "Sophia".j(),"created_sequence" to seq.j()))
        val c=cache(listOf(list,item("banana",51),item("self",52,"me")))
        val single=sharedAdditionNotices(c,50).single();assertEquals("Sophia added grocery item ‘Bananas’ to ‘Costco Shopping List’",single.title);assertEquals("banana",single.target)
        assertTrue(sharedAdditionNotices(c,52).isEmpty())
        val muted=list.copy(data=JsonObject(list.data+("notify_additions" to JsonPrimitive(false))))
        assertTrue(sharedAdditionNotices(cache(listOf(muted,item("banana",51))),50).isEmpty())
        val bulk=sharedAdditionNotices(cache(listOf(list)+(1..8).map { item("Item $it",50+it.toLong()) }),50).single()
        assertTrue(bulk.title.contains("8 items"));assertTrue(bulk.details.contains("Item 8"));assertEquals(list.id,bulk.target)
    }
    @Test fun reminderNotesNeedNoCalendarBlockAndKeepFullContentSearchable() {
        val at=scheduledInstant("2026-11-05","10:00",zone)
        val note=Record("note","note",1,obj("title" to "Maybe build a greenhouse".j(),"body" to "Compare winter heating options".j(),"on_radar" to JsonPrimitive(true),"remind_at" to at.j()))
        val c=cache(listOf(note));val now=Instant.fromEpochSeconds(at-60)
        assertEquals(at,upcomingAgenda(c,now,zone).single().at)
        assertEquals(at,localAlerts(c,now,zone).single().at)
        assertTrue(dayBlocks(listOf(note),LocalDate.parse("2026-11-05"),zone).isEmpty())
    }
}
