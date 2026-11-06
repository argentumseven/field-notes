package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*

class PlannerRefinementsTest {
    @Test fun annualRepeatsKeepTheExistingWireFormatAndReachCalendarAndAlerts() {
        val zone=TimeZone.of("America/New_York");val rule=EventRepeat("monthly",12,zone=zone.id)
        assertEquals("yearly",repeatPreset(rule));assertEquals("Every year",repeatLabel(rule))
        assertEquals("Every 3 years",repeatLabel(rule.copy(interval=36)))
        val at=scheduledInstant("2026-11-06","10:00",zone)
        val record=Record("annual","event",1,obj("title" to "Annual appointment".j(),"starts_at" to at.j(),"ends_at" to (at+3600).j(),"recurrence" to repeatData(rule),"reminders" to reminderData(ReminderSet(listOf(ReminderAlert("annual-alert",10))))))
        val day=LocalDate.parse("2027-11-06");val instance=calendarEntries(listOf(record),day,zone).single()
        val actual=Instant.fromEpochSeconds(instance.startsAt!!).toLocalDateTime(zone)
        assertEquals("10:00",actual.time.toString());assertEquals(day,actual.date)
        val cache=Cache(token="session",snapshot=Snapshot(1,"epoch",1,1,listOf(record),Actor("me","Alex","owner","session")))
        val notice=localAlerts(cache,Instant.fromEpochSeconds(instance.startsAt!!-600),zone).single { it.target==record.id }
        assertEquals(instance.startsAt,notice.sourceAt)
    }
    @Test fun householdIsOneFilterAndLegacyCategoryValuesStayEditable() {
        val home=Record("home","task",1,obj("title" to "Fix shelf".j(),"category" to "home".j()))
        val honey=home.copy(id="honey",data=obj("title" to "Paint gate".j(),"category" to "honey_do".j()))
        assertEquals("Household",categoryLabel(home));assertEquals("Household",categoryLabel(honey))
        assertFalse(recordCategories.containsKey("honey_do"))
        assertEquals(listOf("home","honey"),filterTasks(listOf(VisibleRecord(home),VisibleRecord(honey)),TaskFilters(category="home")).map { it.record.id })
        assertEquals("honey_do",categoryChoices("honey_do").single { it.second=="Household" }.first)
        assertEquals("honey_do",honey.data.text("category"))
    }
    @Test fun backgroundModeSelectionPreservesPrivacyAndAlarmPreferences() {
        val original=Preferences(connectionPaused=true,widgetTitles=false,reminders=false,notificationTitles=false,allowAlarmVolumeOverride=true)
        for(mode in listOf("live","periodic","manual")) {
            val next=original.withBackgroundUpdateMode(mode)
            assertEquals(mode,backgroundUpdateMode(next));assertTrue(next.connectionPaused)
            assertFalse(next.widgetTitles);assertFalse(next.reminders);assertFalse(next.notificationTitles);assertTrue(next.allowAlarmVolumeOverride)
        }
        assertFails { original.withBackgroundUpdateMode("invalid") }
    }
    @Test fun widgetStatusDistinguishesCachedOfflinePausedAndAutomaticUpdates() {
        val state=WorkspaceState(cache=Cache(lastSyncedAt=1000),online=true)
        assertContains(widgetSyncLabel(state,1005),"Updated just now")
        assertContains(widgetSyncLabel(state,1120),"2m ago")
        assertContains(widgetSyncLabel(state.copy(online=false),1120),"offline")
        assertEquals("Paused · open app",widgetSyncLabel(state.copy(connectionPaused=true),1120))
        assertContains(widgetSyncLabel(state.copy(cache=state.cache.copy(preferences=Preferences().withBackgroundUpdateMode("periodic"))),1120),"periodic")
    }
    @Test fun widgetBackgroundCatchupHonorsPauseManualModeAndRecentData() {
        val state=WorkspaceState(cache=Cache(token="session",lastSyncedAt=1000),ready=true)
        assertTrue(widgetNeedsBackgroundSync(state,1400));assertFalse(widgetNeedsBackgroundSync(state,1100))
        assertFalse(widgetNeedsBackgroundSync(state.copy(connectionPaused=true),1400))
        assertFalse(widgetNeedsBackgroundSync(state.copy(needsLogin=true),1400))
        assertFalse(widgetNeedsBackgroundSync(state.copy(cache=state.cache.copy(preferences=Preferences().withBackgroundUpdateMode("manual"))),1400))
    }
}
