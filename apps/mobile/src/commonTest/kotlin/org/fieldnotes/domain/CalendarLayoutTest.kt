package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*

class CalendarLayoutTest {
    private val zone=TimeZone.of("America/New_York")
    private fun event(id:String,from:String,until:String?=null)=Record(id,"event",1,obj("title" to id.j(),"starts_at" to Instant.parse(from).epochSeconds.j(),"ends_at" to (until?.let { Instant.parse(it).epochSeconds } ?: 0L).j()))
    @Test fun clockChangesKeepRealHoursAndCrossMidnightEventsAreClipped() {
        assertEquals(1380,dayLengthMinutes(LocalDate.parse("2026-03-08"),zone))
        assertEquals(1500,dayLengthMinutes(LocalDate.parse("2026-11-01"),zone))
        val overnight=event("overnight","2026-11-01T03:00:00Z","2026-11-01T08:00:00Z")
        val block=dayBlocks(listOf(overnight),LocalDate.parse("2026-11-01"),zone).single()
        assertEquals(0.0,block.from);assertEquals(240.0,block.until)
    }
    @Test fun overlappingEventsShareColumnsButBusyTimeIsAUnionAndPointsDoNotOccupyTime() {
        val records=listOf(event("a","2026-11-03T15:00:00Z","2026-11-03T17:00:00Z"),event("b","2026-11-03T16:00:00Z","2026-11-03T18:00:00Z"),event("point","2026-11-03T16:30:00Z"),event("later","2026-11-03T19:00:00Z","2026-11-03T20:00:00Z"))
        val blocks=dayBlocks(records,LocalDate.parse("2026-11-03"),zone)
        assertEquals(3,blocks.first().columns)
        assertEquals(setOf(0,1,2),blocks.take(3).map { it.column }.toSet())
        assertNull(blocks.single { it.record.id=="point" }.until)
        assertEquals(1,blocks.last().columns)
        assertEquals(4.0/24,occupiedFraction(records,Instant.parse("2026-11-03T05:00:00Z").epochSeconds,Instant.parse("2026-11-04T05:00:00Z").epochSeconds),0.000001)
    }
}
