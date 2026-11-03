package org.fieldnotes.app

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual Android channels, bundled resources and durable replay protection. */
class ReminderDeliveryDeviceTest {
    @Test fun independentProfilesPostOnceAndTheOfflineScheduleRefills()=runBlocking {
        assumeTrue("Disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication
        app.initialized.await()
        val manager=app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val now=Clock.System.now();val zone=TimeZone.currentSystemDefault();val day=now.toLocalDateTime(zone).date
        val rules=ReminderSet(listOf(ReminderAlert("bell",0,importance="urgent",sound="bell",volume=100),ReminderAlert("quiet",1,importance="quiet")),
            ReminderWindow("window",weekdays=(1..7).toList(),starts_on=day.toString(),from="00:00",until="00:00",every_minutes=1,zone=zone.id))
        val event=Record("reminder-fixture","event",1,obj("title" to "Private reminder fixture".j(),"starts_at" to (now.epochSeconds-2).j(),"reminders" to reminderData(rules)))
        val seed=app.model.state.value.cache.copy(config=obj("server_key" to "synthetic-reminders".j()),token="synthetic-token",outbox=emptyList(),drafts=emptyMap(),conflicts=emptyList(),
            snapshot=Snapshot(1,"reminder-fixture",1,1,listOf(event),Actor("owner","Owner","owner","session")),
            preferences=Preferences(connectionPaused=true,backgroundSync=false,liveUpdates=false,reminders=true))
        app.vault.save(seed);app.model.initialize();LocalAlarms.clear(app);LocalAlarms.reconcile(app,true)
        fun receipts()=app.vault.let { vault -> runBlocking { val bytes=vault.readEffect("alerts")!!;try { WireJson.parseToJsonElement(bytes.decodeToString()).jsonObject } finally { bytes.fill(0) } } }
        val key=customAlerts(event,now,zone).first { it.profile?.sound=="bell" }.key
        val deadline=System.currentTimeMillis()+90_000
        while(System.currentTimeMillis()<deadline&&manager.activeNotifications.none { it.notification.channelId=="fn-reminder-v1-urgent-bell-100-true" }) Thread.sleep(250)
        val posted=manager.activeNotifications.filter { it.tag?.startsWith("field-notes:")==true }
        assertTrue(posted.any { it.notification.channelId=="fn-reminder-v1-urgent-bell-100-true" })
        assertTrue(posted.any { it.notification.channelId=="fn-reminder-v1-quiet" })
        assertTrue(posted.all { it.notification.extras.getString("android.text")=="Open Field Notes for details." })
        val urgent=manager.getNotificationChannel("fn-reminder-v1-urgent-bell-100-true")
        assertEquals(NotificationManager.IMPORTANCE_HIGH,urgent.importance)
        assertTrue(urgent.sound.toString().endsWith("/raw/reminder_bell_100"))
        assertNull(manager.getNotificationChannel("fn-reminder-v1-quiet").sound)
        for(tone in listOf("chime","bell","pulse"))for(level in listOf(25,50,75,100)) {
            val id=app.resources.getIdentifier("reminder_${tone}_${level}","raw",app.packageName)
            app.resources.openRawResource(id).use { assertTrue(it.available()>44) }
        }
        val after=receipts()
        assertTrue(after.getValue("delivered").jsonObject.isNotEmpty())
        assertTrue(after.getValue("scheduled").jsonObject.values.any { it.jsonPrimitive.long>now.epochSeconds })
        val originalTags=posted.map { it.tag }.toSet()
        manager.cancelAll();LocalAlarms.fire(app,key)
        assertTrue("Retry must not replay a delivered occurrence",manager.activeNotifications.none { it.tag in originalTags })
        LocalAlarms.clear(app)
        app.model.preferences(seed.preferences.copy(reminders=false))
    }
}
