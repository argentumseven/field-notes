package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

class MealsAfterTest {
    private val zone=TimeZone.of("America/New_York")
    @Test fun beforeAfterAndCalendarClampingWorkWithoutChangingLegacyClockRules(){
        val at=Instant.parse("2026-01-31T15:00:00Z").epochSeconds
        val basic=ReminderAlert("before",30)
        assertEquals(at-1800,reminderAt(basic,at))
        assertEquals(at+1800,reminderAt(withReminderAfter(basic,30,"minutes",zone.id),at))
        assertEquals(Instant.parse("2026-02-28T15:00:00Z").epochSeconds,reminderAt(withReminderAfter(basic,1,"months",zone.id),at))
        val monthly=withReminderAfter(basic,1,"months",zone.id)
        val task=Record("task","task",1,obj("due" to "2026-01-31".j(),"reminders" to reminderData(ReminderSet(listOf(monthly),timing_version=4))))
        assertEquals(Instant.parse("2026-02-28T14:00:00Z").epochSeconds,customAlerts(task,Instant.parse("2026-02-28T14:00:00Z"),TimeZone.UTC).single().at)
        val clock=withReminderDayTime(basic,0,"17:00",zone.id)
        assertEquals(Instant.parse("2026-01-31T22:00:00Z").epochSeconds,reminderAt(clock,at))
    }
    @Test fun anAfterReminderOfARecurringDinnerFindsTheEarlierAnchor(){
        val now=Instant.parse("2026-11-15T19:00:00Z");val start=Instant.parse("2026-11-15T18:00:00Z").epochSeconds
        val alert=withReminderAfter(ReminderAlert("pack",label="Pack leftovers"),1,"hours","UTC")
        val dinner=Record("dinner","event",1,obj("title" to "Dinner".j(),"meal_kind" to "dinner".j(),"starts_at" to start.j(),"recurrence" to repeatData(EventRepeat("daily",zone="UTC")),"reminders" to reminderData(ReminderSet(listOf(alert),timing_version=4))))
        val alerts=customAlerts(dinner,now,TimeZone.UTC);assertTrue(alerts.any{it.at==now.epochSeconds&&it.sourceAt==start&&it.label=="Pack leftovers"})
        val cache=Cache(snapshot=Snapshot(1,"epoch",1,1,listOf(dinner),Actor("owner","Owner","owner","s")))
        assertTrue(reminderNotice(cache,listOf(alerts.first()),TimeZone.UTC,now).title.startsWith("Pack leftovers"))
    }
    @Test fun copiesKeepWallClockAndOvernightLengthAcrossDstAndDoNotRepeat(){
        val data=obj("title" to "Prep".j(),"meal_kind" to "prep".j(),"all_day" to JsonPrimitive(false),"starts_at" to Instant.parse("2026-03-07T23:00:00Z").epochSeconds.j(),"ends_at" to Instant.parse("2026-03-08T05:30:00Z").epochSeconds.j(),"recurrence" to repeatData(EventRepeat("daily",zone=zone.id)))
        val copy=eventCopyForDate(data,LocalDate(2026,3,8),zone)
        assertEquals(Instant.parse("2026-03-08T22:00:00Z").epochSeconds,copy.number("starts_at"))
        assertEquals(Instant.parse("2026-03-09T04:30:00Z").epochSeconds,copy.number("ends_at"));assertEquals(JsonNull,copy["recurrence"])
        assertNotEquals(JsonNull,data["recurrence"]);assertEquals("prep",copy.text("meal_kind"))
        val later=eventCopyForDate(data,LocalDate(2026,3,8),zone,LocalTime(19,0));assertEquals(Instant.parse("2026-03-08T23:00:00Z").epochSeconds,later.number("starts_at"))
    }
    @Test fun copiesRejectSkippedClockTimesAndKeepAllDayEndExclusive(){
        val at=Instant.parse("2026-03-07T07:30:00Z").epochSeconds
        assertFails{eventCopyForDate(obj("starts_at" to at.j()),LocalDate(2026,3,8),zone)}
        val copy=eventCopyForDate(obj("all_day" to JsonPrimitive(true),"start_date" to "2026-11-01".j(),"end_date" to "2026-11-03".j()),LocalDate(2026,12,1),zone)
        assertEquals("2026-12-03",copy.text("end_date"))
    }
}
