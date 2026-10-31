package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.JsonPrimitive

class AgendaTest {
    private fun cache(records:List<Record>)=Cache(token="session",snapshot=Snapshot(1,"e",1,1,records,Actor("me","Alex","owner","s")))
    @Test fun homeAgendaUsesUpcomingDatesAndEventsRatherThanFocusOrCompletedWork(){
        val now=Instant.parse("2026-10-30T16:00:00Z");val zone=TimeZone.of("America/New_York")
        val records=listOf(
            Record("event","event",1,obj("title" to "Household check-in".j(),"starts_at" to (now.epochSeconds+3600).j(),"ends_at" to (now.epochSeconds+7200).j())),
            Record("due","task",1,obj("title" to "Filter".j(),"due" to "2026-10-31".j(),"assignee" to "me".j())),
            Record("done","task",1,obj("title" to "Done".j(),"due" to "2026-10-30".j(),"status" to "done".j())),
            Record("theirs","task",1,obj("title" to "Their work".j(),"due" to "2026-10-30".j(),"assignee" to "sam".j())),
            Record("undated","task",1,obj("title" to "Important".j(),"priority" to 1.j())),
            Record("oldplan","task",1,obj("title" to "Old plan".j(),"planned_for" to "2026-10-01".j())),
            Record("overdue","task",1,obj("title" to "Old due".j(),"due" to "2026-10-01".j())),
        )
        assertEquals(listOf("event","due","overdue"),upcomingAgenda(cache(records),now,zone).map { it.id })
    }
    @Test fun alertsAreStableAcrossDstAndSkipFinishedEventsWithoutNetworkAccess(){
        val zone=TimeZone.of("America/New_York");val now=Instant.parse("2026-11-01T05:00:00Z")
        val start=Instant.parse("2026-11-01T06:30:00Z").epochSeconds
        val c=cache(listOf(
            Record("e","event",1,obj("title" to "Later 1:30".j(),"starts_at" to start.j(),"ends_at" to (start+3600).j())),
            Record("ended","event",1,obj("starts_at" to (now.epochSeconds-3600).j(),"ends_at" to (now.epochSeconds-60).j())),
            Record("day","event",1,obj("all_day" to JsonPrimitive(true),"start_date" to "2026-11-01".j(),"end_date" to "2026-11-02".j())),
            Record("due","task",1,obj("due" to "2026-11-01".j())),
            Record("old","task",1,obj("due" to "2026-10-30".j()))))
        val alerts=localAlerts(c,now,zone)
        assertEquals(start-600,alerts.single { it.target=="e" }.at)
        assertEquals(Instant.parse("2026-11-01T14:00:00Z").epochSeconds,alerts.single { it.target=="due" }.at)
        assertFalse(alerts.any { it.target=="ended" });assertEquals(1,alerts.count { it.kind=="overdue" })
        assertEquals(alerts.map { it.key },localAlerts(c,now.plus(60,DateTimeUnit.SECOND),zone).map { it.key })
        assertTrue(localAlerts(c.copy(token=null),now,zone).isEmpty())
        assertTrue(localAlerts(c.copy(preferences=c.preferences.copy(reminders=false)),now,zone).isEmpty())
    }
}
