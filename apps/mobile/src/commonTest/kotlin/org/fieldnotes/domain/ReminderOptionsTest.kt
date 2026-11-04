package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

class ReminderOptionsTest {
    private val now=Instant.parse("2026-11-03T14:55:00Z")
    private val zone=TimeZone.of("America/New_York")
    private val rules=ReminderSet(listOf(ReminderAlert("r",0,importance="urgent")))
    private val personal=ReminderOptions(ReminderAudience("people",listOf("alex")),mapOf("r" to ReminderPlayback("sunrise",30,true,true,100)))
    private fun event(options:ReminderOptions=personal)=Record("event","event",1,obj("title" to "Dentist appointment".j(),"starts_at" to Instant.parse("2026-11-03T15:00:00Z").epochSeconds.j(),"ends_at" to JsonNull,"notes" to "Bring the insurance card and forms".j(),"author_id" to "alex".j(),"created_by" to "alex".j(),"created_by_name" to "Alex".j(),"created_at" to 1793710000L.j(),"updated_at" to 1793710000L.j(),"reminders" to reminderData(rules),"reminder_options" to reminderOptionsData(options)))
    private fun cache(user:String,record:Record=event())=Cache(token="test",snapshot=Snapshot(1,"epoch",1,1,listOf(record),Actor(user,if(user=="alex")"Alex" else "Sam","member","s")),people=listOf(HouseholdPerson("alex","Alex",true),HouseholdPerson("sam","Sam",true)))
    @Test fun recipientsControlAlertsAndWidgetsWhileCalendarContentStaysShared() {
        assertEquals(1,localAlerts(cache("alex"),now,zone).size)
        assertTrue(localAlerts(cache("sam"),now,zone).isEmpty())
        assertTrue(upcomingAgenda(cache("sam"),now,zone).isEmpty())
        assertEquals(1,upcomingAgenda(cache("sam").copy(preferences=Preferences(widgetHousehold=true)),now,zone).size)
        assertEquals(1,calendarEntries(cache("sam").snapshot!!.records,now.toLocalDateTime(zone).date,zone).size)
        assertEquals(1,localAlerts(cache("sam",event(personal.copy(audience=ReminderAudience("everyone")))),now,zone).size)
        val assignedEvent=event(personal.copy(audience=ReminderAudience("assigned")))
        val assigned=assignedEvent.copy(kind="task",data=JsonObject(assignedEvent.data+mapOf("assignee" to "sam".j())))
        assertFalse(reminderRecipient(assigned,"alex"));assertTrue(reminderRecipient(assigned,"sam"))
        assertTrue(reminderRecipient(assigned.copy(data=JsonObject(assigned.data-"assignee")),"alex"))
    }
    @Test fun extensionsKeepLegacyRulesDecodableAndNewProfilesDistinct() {
        val record=event();assertEquals(rules,WireJson.decodeFromJsonElement<ReminderSet>(record.data.getValue("reminders")))
        val p=customAlerts(record,now,zone).single().profile!!
        assertEquals("sunrise",p.sound);assertEquals(30,p.duration);assertTrue(p.alarm);assertTrue(p.unmute)
        val projected=record.copy(data=JsonObject(record.data+mapOf("reminder_rules" to reminderData(rules),"reminders" to reminderData(ReminderSet()))))
        assertEquals(rules,reminderSet(projected));assertTrue(localAlerts(cache("sam",projected),now,zone).isEmpty())
        assertTrue(prunedReminderOptions(personal,ReminderSet()).playback.isEmpty())
    }
    @Test fun detailedNoticeIncludesScheduleRecipientNotesAndIndependentPrivacy() {
        val c=cache("alex");val alerts=localAlerts(c,now,zone);val notice=reminderNotice(c,alerts,zone,now)
        assertEquals("Dentist appointment",notice.title);assertContains(notice.summary,"10:00");assertContains(notice.summary,"Alex");assertContains(notice.details,"insurance card");assertContains(notice.details,"start only")
        assertTrue(notice.summary.startsWith("For Alex · 10:00 AM · Today"))
        assertEquals(notice,reminderNotice(c.copy(preferences=c.preferences.copy(appLock=true)),alerts,zone,now))
        val hidden=reminderNotice(c.copy(preferences=c.preferences.copy(notificationTitles=false)),alerts,zone)
        assertFalse(hidden.toString().contains("Dentist"));assertContains(hidden.summary,"hidden")
        assertFalse(c.preferences.notificationOnLockScreen);assertFalse(c.preferences.widgetOnLockScreen)
    }
    @Test fun unsupportedOptionsAreVisibleAndNeverBroadcast() {
        val broken=event().copy(data=JsonObject(event().data+("reminder_options" to obj("audience" to obj("mode" to "future-policy".j())))))
        assertFalse(reminderRecipient(broken,"alex"));assertNotNull(reminderCompatibilityWarning(cache("alex",broken)))
        assertFails {validateReminderOptions(personal.copy(audience=ReminderAudience("people")))}
        assertFails {validateReminderOptions(personal.copy(playback=mapOf("r" to ReminderPlayback("siren",3600,true,true,100))))}
    }
}
