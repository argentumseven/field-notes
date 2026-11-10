package org.fieldnotes.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.datetime.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NotificationDeduplicationDeviceTest {
    @Test fun failedAlarmServiceUpdatesOneCardAndDeliveredOccurrenceStaysDeduplicated()=runBlocking {
        assumeTrue("Disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication
        app.initialized.await()
        val old=app.vault.load();val receipts=app.vault.readEffect("alerts");val previousError=app.deliveryError.value
        val manager=app.getSystemService(NotificationManager::class.java)
        val component=ComponentName(app,AlarmPlaybackService::class.java)
        val enabled=app.packageManager.getComponentEnabledSetting(component)
        try {
            val now=Clock.System.now();val zone=TimeZone.currentSystemDefault()
            val rule=ReminderAlert("alarm",0)
            val event=Record("failed-alarm","event",1,obj("title" to "Alarm failure fixture".j(),"starts_at" to (now.epochSeconds-1).j(),"reminders" to reminderData(ReminderSet(listOf(rule))),
                "reminder_options" to reminderOptionsData(ReminderOptions(playback=mapOf(rule.id to ReminderPlayback(alarm=true))))))
            val cache=old.copy(config=obj("server_key" to "alarm-failure-fixture".j()),token="synthetic-token",outbox=emptyList(),drafts=emptyMap(),
                snapshot=Snapshot(1,"failure-${System.nanoTime()}",1,1,listOf(event),Actor("alex","Alex","owner","session")),preferences=Preferences(connectionPaused=true,liveUpdates=false,backgroundSync=false,reminders=true))
            app.vault.save(cache);app.model.initialize();LocalAlarms.clear(app);manager.cancelAll()
            // Android refuses to start the actual disabled component. Exercise
            // the production fallback, rather than simulating notification IDs.
            app.packageManager.setComponentEnabledSetting(component,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)
            val key=localAlerts(cache,now,zone).single().key
            LocalAlarms.reconcile(app,true);LocalAlarms.fire(app,key)
            fun reminders()=manager.activeNotifications.filter {it.notification.extras.getString("android.title")=="Alarm failure fixture"}
            withTimeout(5_000){while(reminders().none {it.notification.extras.getString("android.text")?.contains("playback needs attention")==true})delay(50)}
            val posted=reminders()
            assertEquals(1,posted.size)
            assertTrue(posted.single().notification.extras.getString("android.text")!!.contains("playback needs attention"))
            manager.cancelAll();LocalAlarms.reconcile(app,true);LocalAlarms.fire(app,key)
            assertTrue(reminders().isEmpty())
        }finally {
            app.packageManager.setComponentEnabledSetting(component,enabled,PackageManager.DONT_KILL_APP)
            LocalAlarms.clear(app);manager.cancelAll();app.deliveryError.value=previousError;app.vault.save(old);app.model.initialize()
            val bytes=receipts ?: "{}".encodeToByteArray();app.vault.writeEffect("alerts",bytes);bytes.fill(0)
        }
    }
}
