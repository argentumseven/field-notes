package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

class ReminderRulesTest {
    private val zone=TimeZone.of("America/New_York")
    private val window=ReminderWindow("11111111-1111-4111-8111-111111111111",starts_on="2026-11-03",from="09:00",until="12:00",every_minutes=30,zone=zone.id)
    private fun record(rules:ReminderSet)=Record("item","task",1,obj("title" to "A routine".j(),"status" to "todo".j(),"reminders" to reminderData(rules)))
    @Test fun recurringWindowWorksWithoutDatesAndOnlyCatchesUpLatestMissedInterval(){
        val now=Instant.parse("2026-11-03T15:35:00Z")
        val alerts=customAlerts(record(ReminderSet(repeat=window)),now,zone)
        assertEquals(listOf("2026-11-03T15:30:00Z","2026-11-03T16:00:00Z","2026-11-03T16:30:00Z"),alerts.map{Instant.fromEpochSeconds(it.at).toString()})
        assertTrue(customAlerts(record(ReminderSet(repeat=window)),Instant.parse("2026-11-03T17:00:00Z"),zone).none{it.at>=Instant.parse("2026-11-03T17:00:00Z").epochSeconds})
        assertTrue(customAlerts(record(ReminderSet(repeat=window)),Instant.parse("2026-11-04T14:00:00Z"),zone).isEmpty())
    }
    @Test fun weeklyOvernightWindowsRespectChosenDayAndLastDay(){
        val rule=window.copy(weekdays=listOf(2),from="22:00",until="02:00",every_minutes=60,ends_on="2026-11-03")
        val alerts=customAlerts(record(ReminderSet(repeat=rule)),Instant.parse("2026-11-04T05:05:00Z"),zone)
        assertEquals(listOf("2026-11-04T05:00:00Z","2026-11-04T06:00:00Z"),alerts.map{Instant.fromEpochSeconds(it.at).toString()})
        assertTrue(customAlerts(record(ReminderSet(repeat=rule)),Instant.parse("2026-11-10T23:00:00Z"),zone).isEmpty())
    }
    @Test fun multipleAlertsKeepIndependentProfilesAndStartOnlyNeverReservesTime(){
        val start=Instant.parse("2026-11-03T16:00:00Z").epochSeconds
        val rules=ReminderSet(listOf(ReminderAlert("one",30,importance="quiet"),ReminderAlert("two",0,importance="urgent",sound="bell",volume=100)))
        val event=Record("event","event",1,obj("title" to "Start only".j(),"starts_at" to start.j(),"ends_at" to JsonNull,"reminders" to reminderData(rules)))
        val alerts=customAlerts(event,Instant.parse("2026-11-03T15:25:00Z"),zone)
        assertEquals(2,alerts.size);assertEquals(listOf("quiet","urgent"),alerts.map{it.profile!!.importance})
        assertEquals(1,calendarEntries(listOf(event),LocalDate.parse("2026-11-03"),zone).size)
        assertNull(dayBlocks(listOf(event),LocalDate.parse("2026-11-03"),zone).single().until)
        assertEquals(0.0,occupiedFraction(listOf(event),start,start+3600))
    }
    @Test fun invalidDensityAndProfilesAreRejectedAndDstGapIsSkipped(){
        assertFails{validateReminders(ReminderSet(repeat=window.copy(from="00:00",until="00:00",every_minutes=0)))}
        assertFails{validateReminders(ReminderSet(listOf(ReminderAlert("x",sound="https://example.invalid"))))}
        val spring=window.copy(starts_on="2026-03-08",from="01:00",until="04:00",every_minutes=30)
        val alerts=customAlerts(record(ReminderSet(repeat=spring)),Instant.parse("2026-03-08T06:55:00Z"),zone)
        assertTrue(alerts.all{Instant.fromEpochSeconds(it.at).toLocalDateTime(zone).hour!=2})
        assertEquals("2026-03-08T07:00:00Z",Instant.fromEpochSeconds(alerts.first().at).toString())
    }
    @Test fun minuteWindowsStayBoundedAndDeliveredProgressPreventsReceiptPruningReplay(){
        val now=Instant.parse("2026-11-03T15:35:00Z")
        val rules=ReminderSet(repeat=window.copy(from="00:00",until="00:00",every_minutes=1))
        val r=record(rules)
        validateReminders(rules)
        val cache=Cache(token="token",snapshot=Snapshot(1,"epoch",1,1,listOf(r),Actor("u","User","owner","s")),preferences=Preferences(reminders=true))
        val alerts=localAlerts(cache,now,zone)
        assertEquals(3,alerts.size)
        assertEquals(now.epochSeconds,alerts.first().at)
        val highWater=mapOf(alerts.first().progressKey to alerts.first().at)
        assertEquals(alerts.drop(1),localAlerts(cache,now,zone,delivered=highWater))
        assertEquals(alerts.drop(1),localAlerts(cache,now,zone,seen=setOf(alerts.first().key)))
    }
}
