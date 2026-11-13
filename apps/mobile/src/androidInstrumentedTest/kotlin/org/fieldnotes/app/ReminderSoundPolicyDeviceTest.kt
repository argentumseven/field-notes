package org.fieldnotes.app

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.datetime.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real channel payloads, persisted warning dismissal and alarm controls on the disposable AVD. */
class ReminderSoundPolicyDeviceTest {
    private suspend fun app():FieldNotesApplication {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication
        app.initialized.await()
        return app
    }
    private suspend fun until(check:()->Boolean) { withTimeout(30_000) { while(!check())delay(100) } }
    private suspend fun savedIssue(app:FieldNotesApplication):String {
        val bytes=app.vault.readEffect("delivery-health") ?: return ""
        return try { bytes.decodeToString() } finally { bytes.fill(0) }
    }
    @Test fun enabledVolumeClearsOnlyItsWarningAndDismissalPersistsWithoutMutingFutureIssues()=runBlocking {
        val app=app();val old=app.model.state.value.cache.preferences;val manager=app.getSystemService(NotificationManager::class.java)
        try {
            app.model.preferences{it.copy(backgroundSync=false,liveUpdates=false,reminders=false,allowAlarmVolumeOverride=false,allowNotificationVolumeOverride=false)}
            app.deliveryError.value=ALARM_VOLUME_DISABLED
            until{manager.activeNotifications.any{it.notification.extras.getString("android.text")==ALARM_VOLUME_DISABLED}}
            app.model.preferences{it.copy(allowAlarmVolumeOverride=true)}
            until{app.deliveryError.value==null}
            withTimeout(30_000){while(savedIssue(app).isNotBlank())delay(100)}
            app.deliveryError.value=NOTIFICATION_VOLUME_DISABLED
            until{manager.activeNotifications.any{it.notification.extras.getString("android.text")==NOTIFICATION_VOLUME_DISABLED}}
            assertEquals(NOTIFICATION_VOLUME_DISABLED,app.deliveryError.value)
            app.model.preferences{it.copy(allowNotificationVolumeOverride=true)}
            until{app.deliveryError.value==null}
            val previous="Previous sound volume could not be restored. Open Android sound settings."
            val newer="Android could not play the selected sound. Choose another sound."
            app.deliveryError.value=previous
            until{manager.activeNotifications.any{it.notification.extras.getString("android.text")==previous}}
            app.deliveryError.value=newer
            app.dismissDeliveryIssue(Alerts.digest(previous))
            assertEquals(newer,app.deliveryError.value)
            until{manager.activeNotifications.any{it.notification.extras.getString("android.text")==newer}}
            val notice=manager.activeNotifications.single{it.notification.extras.getString("android.text")==newer}.notification
            assertNotNull(notice.deleteIntent)
            notice.actions.single{it.title.toString()=="Dismiss"}.actionIntent.send()
            until{app.deliveryError.value==null}
            withTimeout(30_000){while(savedIssue(app).isNotBlank())delay(100)}
            app.deliveryError.value=newer
            until{manager.activeNotifications.any{it.notification.extras.getString("android.text")==newer}}
            assertTrue(app.model.state.value.cache.preferences.allowAlarmVolumeOverride)
            assertTrue(app.model.state.value.cache.preferences.allowNotificationVolumeOverride)
            app.dismissDeliveryIssue(Alerts.digest(newer))
        } finally { app.model.preferences(old);manager.cancelAll() }
    }
    @Test fun alarmUsesSeparateAudioAndShowsTheActualItemInItsStopControls()=runBlocking {
        val app=app();val old=app.model.state.value.cache
        val manager=app.getSystemService(NotificationManager::class.java)
        val now=Clock.System.now();val zone=TimeZone.currentSystemDefault()
        val rules=ReminderSet(listOf(ReminderAlert("sound-policy",0)))
        val profile=AlertProfile(sound="beacon",duration=30,alarm=true)
        val event=Record("sound-policy-event","event",1,obj("title" to "Dentist appointment".j(),"starts_at" to (now.epochSeconds+120).j(),"location" to "123 Main Street".j(),"notes" to "Bring the insurance card and forms".j(),"reminders" to reminderData(rules),"reminder_options" to reminderOptionsData(ReminderOptions(playback=mapOf("sound-policy" to playbackOptions(profile))))))
        val seed=old.copy(config=obj("server_key" to "synthetic-audio".j()),token="synthetic-audio-token",outbox=emptyList(),drafts=emptyMap(),conflicts=emptyList(),snapshot=Snapshot(1,"synthetic-audio",1,1,listOf(event),Actor("alex","Alex","owner","session")),preferences=Preferences(connectionPaused=true,backgroundSync=false,liveUpdates=false,reminders=true))
        try {
            app.vault.save(seed);app.model.initialize()
            for(tone in reminderTones.keys) {
                val cue=ReminderSounds.sound(app,AlertProfile(sound=tone))
                val alarm=ReminderSounds.sound(app,AlertProfile(sound=tone,alarm=true))
                assertNotEquals(cue,alarm)
                app.contentResolver.openInputStream(cue)!!.use{assertTrue(it.readBytes().size<44+22050*2)}
                app.contentResolver.openInputStream(alarm)!!.use{assertTrue(it.readBytes().size>=44+22050*2*3)}
            }
            assertNull(manager.getNotificationChannel(ReminderSounds.channel(app,profile)).sound)
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).putExtra("open_page","Tasks").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                val keys=localAlerts(seed,now,zone).filter{it.target==event.id}.map{it.key}
                assertEquals(1,keys.size)
                assertTrue(AlarmPlaybackService.start(app,keys))
                until{manager.activeNotifications.any{it.id==93&&it.notification.extras.getString("android.subText")=="Alarm playing"}}
                val playing=manager.activeNotifications.single{it.id==93}.notification
                assertEquals("Dentist appointment",playing.extras.getString("android.title"))
                assertTrue(playing.extras.getString("android.text")!!.contains("123 Main Street"))
                assertTrue(playing.extras.getString("android.bigText")!!.contains("insurance card"))
                assertEquals("Field Notes alert",playing.publicVersion.extras.getString("android.title"))
                assertTrue(playing.actions.any{it.title.toString()=="Stop alarm"})
                playing.actions.single{it.title.toString()=="Stop alarm"}.actionIntent.send()
                until{manager.activeNotifications.none{it.id==93}}
            }
        } finally { AlarmPlaybackService.stop(app);LocalAlarms.clear(app);app.vault.save(old);app.model.initialize();manager.cancelAll() }
    }
}
