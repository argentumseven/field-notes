package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

class ReminderTimingTest {
    private val zone=TimeZone.of("America/New_York")
    private fun rule(amount:Int,unit:String)=ReminderAlert("rule",0,lead_time=ReminderLead(amount,unit,zone.id))
    @Test fun monthYearArithmeticClampsLeapDaysAndUsesTheSavedCalendarZone() {
        val cases=listOf(
            Triple("2027-03-31T14:00:00Z",rule(1,"months"),"2027-02-28T15:00:00Z"),
            Triple("2028-02-29T15:00:00Z",rule(1,"years"),"2027-02-28T15:00:00Z"),
            Triple("2026-03-08T14:00:00Z",rule(1,"months"),"2026-02-08T15:00:00Z"),
            Triple("2026-04-08T06:30:00Z",rule(1,"months"),"2026-03-08T07:30:00Z"),
            Triple("2026-12-01T06:30:00Z",rule(1,"months"),"2026-11-01T05:30:00Z"),
            Triple("2026-11-01T06:30:00Z",rule(0,"years"),"2026-11-01T06:30:00Z"),
            Triple("2026-03-08T14:00:00Z",rule(1,"days"),"2026-03-07T14:00:00Z")
        )
        for((anchor,lead,expected)in cases)assertEquals(Instant.parse(expected).epochSeconds,reminderAt(lead,Instant.parse(anchor).epochSeconds),"$anchor ${lead.lead_time}")
    }
    @Test fun legacyDurationsKeepTheirWireShapeAndSoundWhileNewUnitsAreExplicit() {
        val old=ReminderAlert("a",90,sound="bell",volume=50,vibrate=false)
        val duration=withReminderTime(old,2,"hours",zone.id)
        assertEquals(120,duration.minutes_before);assertNull(duration.lead_time)
        assertEquals("bell",duration.sound);assertEquals(50,duration.volume);assertFalse(duration.vibrate)
        val legacy=reminderData(ReminderSet(listOf(old)))
        assertFalse("timing_version" in legacy);assertFalse("lead_time" in legacy.getValue("alerts").jsonArray.single().jsonObject)
        assertEquals(43200,withReminderTime(old,30,"days",zone.id).minutes_before)
        assertEquals("days",withReminderTime(old,31,"days",zone.id).lead_time!!.unit)
        assertEquals("1 month before",reminderTimeLabel(withReminderTime(old,1,"months",zone.id)))
        for((unit,max)in reminderTimeLimits){assertFails{validateReminderLead(ReminderLead(max+1,unit,zone.id))};assertFails{validateReminderLead(ReminderLead(-1,unit,zone.id))}}
        assertFails{validateReminderLead(ReminderLead(1,"months","invalid-zone"))}
    }
    @Test fun longLeadsFindFutureRepeatingOccurrencesWithoutExpandingYears() {
        val lead=rule(10,"years")
        val now=Instant.parse("2026-11-07T13:00:00Z")
        val record=Record("annual","event",1,obj("title" to "Annual review".j(),"all_day" to JsonPrimitive(false),"starts_at" to Instant.parse("2026-11-07T15:00:00Z").epochSeconds.j(),"ends_at" to JsonNull,
            "recurrence" to WireJson.encodeToJsonElement(EventRepeat("monthly",12,zone=zone.id)),"reminders" to reminderData(ReminderSet(listOf(lead),timing_version=1))))
        val range=reminderAnchorRange(lead,now,now.epochSeconds+8*86400,zone)
        assertTrue(range.first.daysUntil(range.second)<90)
        val found=customAlerts(record,now,zone)
        assertEquals(1,found.size)
        assertEquals(Instant.parse("2036-11-07T15:00:00Z").epochSeconds,found.single().sourceAt)
        assertEquals(Instant.parse("2026-11-07T15:00:00Z").epochSeconds,found.single().at)
    }
    @Test fun clampedRecurringDatesKeepDistinctReceiptIdentitiesAndLegacyKeysStayStable() {
        val now=Instant.parse("2027-02-28T13:00:00Z")
        val lead=rule(1,"months")
        val record=Record("daily","event",1,obj("title" to "Daily review".j(),"all_day" to JsonPrimitive(false),"starts_at" to Instant.parse("2027-03-29T13:00:00Z").epochSeconds.j(),"ends_at" to JsonNull,
            "recurrence" to WireJson.encodeToJsonElement(EventRepeat("daily",1,until="2027-03-31",zone=zone.id)),"reminders" to reminderData(ReminderSet(listOf(lead),timing_version=1))))
        val found=customAlerts(record,now,zone)
        assertEquals(3,found.size);assertEquals(1,found.map{it.at}.distinct().size);assertEquals(3,found.map{it.key}.distinct().size);assertEquals(3,found.map{it.progressKey}.distinct().size)
        val old=record.copy(data=JsonObject(record.data+mapOf("recurrence" to JsonNull,"starts_at" to Instant.parse("2027-02-28T15:00:00Z").epochSeconds.j(),"reminders" to reminderData(ReminderSet(listOf(ReminderAlert("legacy",60)))))))
        val delivery=customAlerts(old,now,zone).single()
        assertEquals("rule:daily:legacy:${delivery.at}",delivery.key)
    }
}
