package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

class ReminderIntervalTest {
    private fun at(text:String)=Instant.parse(text).epochSeconds
    private val anchor=at("2026-01-31T10:00:00Z")
    private fun rule(unit:String="hours",amount:Int=2,start:Long=anchor)=ReminderInterval("cycle",amount,unit,start,"UTC")
    private fun record(rule:ReminderInterval,status:String="todo")=Record("task","task",1,obj("title" to "Undated nudge".j(),"status" to status.j(),"reminders" to reminderData(ReminderSet(timing_version=3,interval=rule))))
    private fun cache(record:Record)=Cache(keys=obj("private_key" to "test".j()),token="token",snapshot=Snapshot(1,"epoch",1,1,listOf(record),Actor("owner","Owner","owner","session")))
    @Test fun intervalsUseElapsedUnitsAndCalendarMonthsWithoutMonthEndDrift() {
        for((unit,seconds) in listOf("minutes" to 60L,"hours" to 3600L,"days" to 86400L,"weeks" to 604800L))assertEquals(anchor+2*seconds,intervalOccurrence(rule(unit),1))
        val monthly=rule("months",1)
        assertEquals(at("2026-02-28T10:00:00Z"),intervalOccurrence(monthly,1))
        assertEquals(at("2026-03-31T10:00:00Z"),intervalOccurrence(monthly,2))
        val yearly=rule("years",1,at("2024-02-29T10:00:00Z"))
        assertEquals(at("2025-02-28T10:00:00Z"),intervalOccurrence(yearly,1));assertEquals(at("2028-02-29T10:00:00Z"),intervalOccurrence(yearly,4))
    }
    @Test fun monthlyIntervalsKeepLocalClockAcrossDaylightSaving() {
        val rule=rule("months",1,at("2026-02-15T14:00:00Z")).copy(zone="America/New_York")
        assertEquals(at("2026-03-15T13:00:00Z"),intervalOccurrence(rule,1))
    }
    @Test fun noDateIsRequiredAndLongIntervalsAreScheduledBeyondTheCalendarHorizon() {
        val r=record(rule("years",1));val alerts=localAlerts(cache(r),Instant.fromEpochSeconds(anchor),TimeZone.UTC)
        assertEquals(2,alerts.size);assertEquals(at("2027-01-31T10:00:00Z"),alerts.first().at)
        assertFalse(hasReminderAnchor(r));assertTrue(alerts.all{it.kind=="interval"})
    }
    @Test fun missedYearsJumpToOneCatchUpAndTwoFutureOccurrencesWithoutABacklog() {
        val r=record(rule("minutes",1,at("2000-01-01T00:00:00Z")));val now=Instant.parse("2026-11-15T10:00:30Z")
        val alerts=localAlerts(cache(r),now,TimeZone.UTC)
        assertEquals(3,alerts.size);assertEquals(1,alerts.count{it.at<=now.epochSeconds})
        assertEquals(2,localAlerts(cache(r),now,TimeZone.UTC,delivered=mapOf("task:cycle" to alerts.first().at)).size)
    }
    @Test fun completionRecipientsAndReminderOptOutStopNudges() {
        val r=record(rule());val now=Instant.fromEpochSeconds(anchor+7200)
        assertTrue(localAlerts(cache(record(rule(),"done")),now,TimeZone.UTC).isEmpty())
        assertTrue(localAlerts(cache(r).copy(preferences=Preferences(reminders=false)),now,TimeZone.UTC).isEmpty())
        val personal=r.copy(data=JsonObject(r.data+("reminder_options" to reminderOptionsData(ReminderOptions(ReminderAudience("people",listOf("someone-else")))))))
        assertTrue(localAlerts(cache(personal),now,TimeZone.UTC).isEmpty())
    }
    @Test fun editsResetOnlyTheCadenceAndSerializationKeepsUrgency() {
        val old=ReminderSet(timing_version=3,interval=rule())
        val louder=old.copy(interval=rule().copy(importance="urgent"))
        val saved=prepareReminderSave(louder,old,anchor+10_000)
        assertEquals(anchor,saved.interval!!.anchor_at)
        assertEquals("urgent",WireJson.decodeFromJsonElement<ReminderSet>(reminderData(saved)).interval!!.importance)
        assertEquals(anchor+10_000,prepareReminderSave(old.copy(interval=rule(amount=3)),old,anchor+10_000).interval!!.anchor_at)
    }
    @Test fun invalidIntervalsAndConflictingRepeatModesAreRejected() {
        for(r in listOf(rule(amount=0),rule(amount=-1),rule("years",11),rule("invalid"),rule().copy(zone="not/a-zone"),rule(start=0)))assertFails{validateReminders(ReminderSet(timing_version=3,interval=r))}
        assertFails{validateReminders(ReminderSet(timing_version=2,interval=rule()))}
        assertFails{validateReminders(ReminderSet(repeat=ReminderWindow("window",starts_on="2026-01-31",zone="UTC"),timing_version=3,interval=rule()))}
    }
    @Test fun onlyRecoveredSchedulingWarningsAreEligibleToClear() {
        assertTrue(isSchedulingWarning("Reminder scheduling needs attention."));assertTrue(isSchedulingWarning(SCHEDULING_FAILURE))
        assertFalse(isSchedulingWarning(ALARM_VOLUME_DISABLED));assertFalse(isSchedulingWarning("Some reminders are blocked"));assertFalse(isSchedulingWarning(null))
    }
}
