package org.fieldnotes.domain

import java.io.File
import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

class PlanningGoldenTest {
    @Test fun sameGoldenCasesAsTheBrowser() {
        val f=WireJson.parseToJsonElement(File("../../tests/fixtures/planning-cases.json").readText()).jsonObject
        for (row in f.getValue("cases").jsonArray) {
            val c=row.jsonObject
            val task=Record(c.text("name"),"task",1,c.getValue("data").jsonObject)
            val rank=rankTask(task,LocalDate.parse(f.text("today")),f.number("now"),f.text("user"))
            assertEquals(c.number("score").toInt(),rank.score,c.text("name"))
            assertEquals(c.getValue("eligible").jsonPrimitive.boolean,rank.eligible,c.text("name"))
        }
    }
    @Test fun calendarUsesExclusiveEndsAndRealLocalDaysAcrossDst() {
        val zone=TimeZone.of("America/New_York")
        fun event(start:String,end:String)=Record("e","event",1,obj("title" to "Event".j(),"all_day" to JsonPrimitive(false),"starts_at" to Instant.parse(start).epochSeconds.j(),"ends_at" to Instant.parse(end).epochSeconds.j()))
        val fall=event("2026-11-01T04:00:00Z","2026-11-02T05:00:00Z")
        assertEquals(1,calendarEntries(listOf(fall),LocalDate(2026,11,1),zone).size)
        assertTrue(calendarEntries(listOf(fall),LocalDate(2026,11,2),zone).isEmpty())
        val spring=event("2026-03-08T05:00:00Z","2026-03-09T04:00:00Z")
        assertEquals(1,calendarEntries(listOf(spring),LocalDate(2026,3,8),zone).size)
        assertTrue(calendarEntries(listOf(spring),LocalDate(2026,3,9),zone).isEmpty())
        val allDay=Record("a","event",1,obj("title" to "Trip".j(),"all_day" to JsonPrimitive(true),"start_date" to "2026-11-01".j(),"end_date" to "2026-11-03".j()))
        assertEquals(1,calendarEntries(listOf(allDay),LocalDate(2026,11,2),zone).size)
        assertTrue(calendarEntries(listOf(allDay),LocalDate(2026,11,3),zone).isEmpty())
        val undated=Record("t","task",1,obj("title" to "No date".j(),"priority" to 1.j()))
        assertTrue(calendarEntries(listOf(undated),LocalDate(2026,11,1),zone).isEmpty())
        assertEquals(29,monthCells(LocalDate(2028,2,1)).filterNotNull().size)
    }
    @Test fun pinsOverrideScoresAndCompletedOrOtherClaimedWorkCannotLeakIntoFocus() {
        val tasks=listOf(
            Record("urgent","task",1,obj("priority" to 1.j())),
            Record("pinned","task",1,obj("priority" to 4.j(),"pinned" to JsonPrimitive(true))),
            Record("done","task",1,obj("status" to "done".j(),"priority" to 1.j())),
            Record("someone-else","task",1,obj("claimant" to "other".j(),"priority" to 1.j())),
        )
        val cache=Cache(snapshot=Snapshot(1,"e",1,1,tasks,Actor("me","Me","member","s")))
        assertEquals(listOf("pinned","urgent"),focusTasks(cache,LocalDate(2026,10,30),1793361600).map{it.first.record.id})
    }
}
