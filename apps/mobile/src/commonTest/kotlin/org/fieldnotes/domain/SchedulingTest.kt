package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*

class SchedulingTest {
    private val ny=TimeZone.of("America/New_York")
    @Test fun tenAmStaysTenAmInWinterAndSummer() {
        for((date,utc) in listOf("2026-11-03" to "2026-11-03T15:00:00Z","2026-07-03" to "2026-07-03T14:00:00Z")) {
            val instant=scheduledInstant(date,"10:00",ny)
            assertEquals(Instant.parse(utc).epochSeconds,instant)
            assertEquals("10:00 AM",friendlyTime(Instant.fromEpochSeconds(instant).toLocalDateTime(ny).time))
        }
    }
    @Test fun springGapIsRejectedAndExistingRepeatedAutumnTimeIsPreserved() {
        assertFailsWith<IllegalArgumentException> { scheduledInstant("2026-03-08","02:30",ny) }
        val second=Instant.parse("2026-11-01T06:30:00Z").epochSeconds
        assertEquals(second,scheduledInstant("2026-11-01","01:30",ny,second))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").epochSeconds,scheduledInstant("2026-11-01","01:30",ny))
    }
    @Test fun monthGridAndLocalMidnightIncludeTheRightDays() {
        for((month,days) in listOf("2028-02-01" to 29,"2026-02-01" to 28,"2026-11-01" to 30,"2026-12-01" to 31)) {
            val cells=monthCells(LocalDate.parse(month));assertEquals(42,cells.size)
            assertEquals(days,cells.filterNotNull().size)
            cells.forEachIndexed { i,d -> if(d!=null)assertEquals(i%7+1,d.dayOfWeek.isoDayNumber) }
        }
        val record=Record("night","event",1,obj("title" to "Late evening".j(),"starts_at" to Instant.parse("2026-11-04T03:00:00Z").epochSeconds.j(),"ends_at" to Instant.parse("2026-11-04T04:00:00Z").epochSeconds.j()))
        assertEquals(1,calendarEntries(listOf(record),LocalDate.parse("2026-11-03"),ny).size)
        assertEquals(0,calendarEntries(listOf(record),LocalDate.parse("2026-11-04"),ny).size)
    }
}
