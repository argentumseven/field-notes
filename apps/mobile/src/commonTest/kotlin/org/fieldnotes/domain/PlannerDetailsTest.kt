package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

class PlannerDetailsTest {
    private val ny=TimeZone.of("America/New_York")
    private fun event(rules:ReminderSet)=Record("event","event",1,obj("title" to "Synthetic appointment".j(),"all_day" to JsonPrimitive(true),"start_date" to "2026-11-14".j(),"end_date" to "2026-11-15".j(),"reminders" to reminderData(rules)))
    @Test fun weekBeforeAllDayUsesTheChosenClockAndZone(){
        val rule=withReminderDayTime(ReminderAlert("week"),7,"10:30",ny.id)
        val alerts=customAlerts(event(ReminderSet(listOf(rule),timing_version=2)),Instant.parse("2026-11-06T17:00:00Z"),TimeZone.UTC)
        assertEquals(Instant.parse("2026-11-07T15:30:00Z").epochSeconds,alerts.single().at)
        assertEquals("1 week before at 10:30 AM",reminderTimeLabel(rule))
    }
    @Test fun explicitTimesHandleGapsAndOverlapWithoutChangingOldRules(){
        fun at(date:String,time:String)=reminderAt(withReminderDayTime(ReminderAlert("r"),0,time,ny.id),LocalDateTime(LocalDate.parse(date),LocalTime(9,0)).toInstant(ny).epochSeconds)
        assertEquals(Instant.parse("2027-03-14T07:30:00Z").epochSeconds,at("2027-03-14","02:30"))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").epochSeconds,at("2026-11-01","01:30"))
        val legacy=customAlerts(event(ReminderSet(listOf(ReminderAlert("old",0)))),Instant.parse("2026-11-13T12:00:00Z"),ny).single()
        assertEquals(Instant.parse("2026-11-14T14:00:00Z").epochSeconds,legacy.at)
        assertEquals("rule:event:old:${legacy.at}",legacy.key)
    }
    @Test fun invalidTimeAndMixedTimingsAreRejected(){
        assertFails{validateReminderDayTime(ReminderDayTime(0,"25:00",ny.id))}
        assertFails{validateReminderDayTime(ReminderDayTime(-1,"09:00",ny.id))}
        assertFails{validateReminderDayTime(ReminderDayTime(0,"9:00",ny.id))}
        assertFails{validateReminders(ReminderSet(listOf(withReminderDayTime(ReminderAlert("r"),0,"09:00",ny.id)),timing_version=1))}
        val day=withReminderDayTime(ReminderAlert("r"),0,"09:00",ny.id)
        assertFails{validateReminders(ReminderSet(listOf(day.copy(lead_time=ReminderLead(1,"weeks",ny.id))),timing_version=2))}
    }
    @Test fun legacySerializationStaysOptionalAndSwitchingModesClearsTheOther(){
        val old=ReminderAlert("old",10)
        assertFalse(reminderData(ReminderSet(listOf(old))).toString().contains("day_time"))
        assertNull(withReminderTime(withReminderDayTime(old,1,"09:00",ny.id),1,"hours",ny.id).day_time)
    }
    @Test fun holidayRulesIncludeObservedAndAdjacentYearDates(){
        assertTrue(holidaysOn(LocalDate(2021,12,31),true).contains("New Year’s Day (observed)"))
        assertEquals(listOf("Thanksgiving Day"),holidaysOn(LocalDate(2026,11,26),true))
        assertTrue(holidaysOn(LocalDate(2020,6,19),true).isEmpty())
        assertEquals(11,federalHolidays(2026).count{!it.name.endsWith("(observed)")})
        assertTrue(holidaysOn(LocalDate(2026,11,26),false).isEmpty())
    }
    @Test fun recipeSearchIncludesIngredientsAndInstructions(){
        val recipe=Record("recipe","note",1,obj("title" to "Soup".j(),"note_type" to "recipe".j(),"ingredients" to "Lentils".j(),"instructions" to "Simmer gently".j()))
        assertTrue(recipe.isRecipe());assertContains(recipe.noteSearchText(),"Lentils");assertContains(recipe.noteSearchText(),"Simmer gently")
    }
}
