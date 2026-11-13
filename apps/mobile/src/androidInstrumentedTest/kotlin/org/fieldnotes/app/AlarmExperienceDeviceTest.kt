package org.fieldnotes.app

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.datetime.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AlarmExperienceDeviceTest {
    @Test fun refusedRestorationRetainsRecoveryAndRespectsTheNextManualChoice()=runBlocking {
        assumeTrue("Disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication;app.initialized.await()
        val audio=app.getSystemService(AudioManager::class.java);val initial=audio.getStreamVolume(AudioManager.STREAM_ALARM);val initialMute=audio.isStreamMute(AudioManager.STREAM_ALARM)
        val original=app.vault.readEffect("alarm-volume");val token=java.util.UUID.randomUUID().toString()
        fun json()=kotlinx.serialization.json.Json.parseToJsonElement(runBlocking { app.vault.readEffect("alarm-volume")!! }.decodeToString()).let {it as kotlinx.serialization.json.JsonObject}
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM,audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),0)
            val lease=AlarmVolumeLease(token,0,true,audio.getStreamVolume(AudioManager.STREAM_ALARM),android.os.SystemClock.elapsedRealtime()+30_000)
            val bytes=("{\"lease\":"+WireJson.encodeToString(AlarmVolumeLease.serializer(),lease)+"}").encodeToByteArray();app.vault.writeEffect("alarm-volume",bytes);bytes.fill(0)
            val restored=runCatching {AlarmAudio.restore(app,token)}
            if(audio.getStreamVolume(AudioManager.STREAM_ALARM)!=0||!audio.isStreamMute(AudioManager.STREAM_ALARM)) {
                assertTrue("Refused restoration must fail visibly",restored.isFailure)
                assertNotEquals(kotlinx.serialization.json.JsonNull,json()["lease"])
                audio.setStreamVolume(AudioManager.STREAM_ALARM,audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),0)
                val manual=audio.getStreamVolume(AudioManager.STREAM_ALARM)
                AlarmAudio.restore(app,token);assertEquals(manual,audio.getStreamVolume(AudioManager.STREAM_ALARM))
            } else assertTrue(restored.isSuccess)
            assertEquals(kotlinx.serialization.json.JsonNull,json()["lease"])
        }finally {
            audio.setStreamVolume(AudioManager.STREAM_ALARM,initial,0);if(initialMute)audio.adjustStreamVolume(AudioManager.STREAM_ALARM,AudioManager.ADJUST_MUTE,0)
            val bytes=original ?: "{\"lease\":null}".encodeToByteArray();app.vault.writeEffect("alarm-volume",bytes);bytes.fill(0)
        }
    }
    @Test fun temporaryVolumeRestoresLowestSupportedStateAndRespectsAManualChange()=runBlocking {
        assumeTrue("Disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication;app.initialized.await()
        val audio=app.getSystemService(AudioManager::class.java);val initial=audio.getStreamVolume(AudioManager.STREAM_ALARM);val muted=audio.isStreamMute(AudioManager.STREAM_ALARM)
        val profile=AlertProfile("urgent","beacon",100,true,10,true,true,100)
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM,0,0)
            audio.adjustStreamVolume(AudioManager.STREAM_ALARM,AudioManager.ADJUST_MUTE,0)
            val before=audio.getStreamVolume(AudioManager.STREAM_ALARM);val beforeMuted=audio.isStreamMute(AudioManager.STREAM_ALARM)
            // Some Android builds disallow muting alarms and clamp zero to 1.
            // Restore the actual accepted state; do not claim this emulates a
            // device whose alarm stream can really be zero/muted.
            val lease=AlarmAudio.raise(app,profile,true);assertNotNull(lease)
            assertTrue(audio.getStreamVolume(AudioManager.STREAM_ALARM)>0);assertFalse(audio.isStreamMute(AudioManager.STREAM_ALARM))
            AlarmAudio.restore(app,lease);assertEquals(before,audio.getStreamVolume(AudioManager.STREAM_ALARM));assertEquals(beforeMuted,audio.isStreamMute(AudioManager.STREAM_ALARM))
            val next=AlarmAudio.raise(app,profile,true);assertNotNull(next)
            audio.setStreamVolume(AudioManager.STREAM_ALARM,1,0)
            AlarmAudio.restore(app,next);assertEquals(1,audio.getStreamVolume(AudioManager.STREAM_ALARM))
            audio.setStreamVolume(AudioManager.STREAM_ALARM,0,0)
            audio.adjustStreamVolume(AudioManager.STREAM_ALARM,AudioManager.ADJUST_MUTE,0)
            val denied=audio.getStreamVolume(AudioManager.STREAM_ALARM);val deniedMute=audio.isStreamMute(AudioManager.STREAM_ALARM)
            assertNull(AlarmAudio.raise(app,profile,false));assertEquals(denied,audio.getStreamVolume(AudioManager.STREAM_ALARM));assertEquals(deniedMute,audio.isStreamMute(AudioManager.STREAM_ALARM));assertNotNull(app.deliveryError.value)
        } finally {AlarmAudio.restoreAfterStop(app);audio.setStreamVolume(AudioManager.STREAM_ALARM,initial,0);if(muted)audio.adjustStreamVolume(AudioManager.STREAM_ALARM,AudioManager.ADJUST_MUTE,0)}
    }
    @Test fun notificationLeaseReplacementRestoresOriginalVolumeAndLeavesRingerModeAlone()=runBlocking {
        assumeTrue("Disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication;app.initialized.await()
        val audio=app.getSystemService(AudioManager::class.java);val stream=AudioManager.STREAM_NOTIFICATION
        val initial=audio.getStreamVolume(stream);val initialMute=audio.isStreamMute(stream);val ringer=audio.ringerMode
        try {
            // Never switch ringer mode to make this pass; the normal-volume branch is tested only when Android allows it.
            assumeTrue(audio.ringerMode==AudioManager.RINGER_MODE_NORMAL)
            audio.setStreamVolume(stream,2,0);val before=audio.getStreamVolume(stream)
            val p=AlertProfile(notificationUnmute=true,notificationVolume=100)
            assertNull(NotificationAudio.raise(app,p,false));assertEquals(before,audio.getStreamVolume(stream))
            val first=NotificationAudio.raise(app,p,true);assertNotNull(first)
            val second=NotificationAudio.raise(app,p,true);assertNotNull(second);assertNotEquals(first,second)
            NotificationAudio.restore(app,first);assertTrue(audio.getStreamVolume(stream)>before)
            NotificationAudio.restore(app,second);assertEquals(before,audio.getStreamVolume(stream));assertEquals(ringer,audio.ringerMode)
            val third=NotificationAudio.raise(app,p,true);audio.setStreamVolume(stream,3,0);val manual=audio.getStreamVolume(stream)
            NotificationAudio.restore(app,third);assertEquals(manual,audio.getStreamVolume(stream));assertEquals(ringer,audio.ringerMode)
        }finally{NotificationAudio.restoreAfterStop(app);audio.setStreamVolume(stream,initial,0);if(initialMute)audio.adjustStreamVolume(stream,AudioManager.ADJUST_MUTE,0)}
    }
    @Test fun alarmServicePlaysWithAVisibleStopAndRestoresAfterStopping()=runBlocking {
        assumeTrue("Disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val instrumentation=InstrumentationRegistry.getInstrumentation();val app=instrumentation.targetContext.applicationContext as FieldNotesApplication;app.initialized.await()
        val old=app.vault.load();val audio=app.getSystemService(AudioManager::class.java);val initial=audio.getStreamVolume(AudioManager.STREAM_ALARM);val initialMuted=audio.isStreamMute(AudioManager.STREAM_ALARM)
        val activity=instrumentation.startActivitySync(Intent(app,MainActivity::class.java).putExtra("open_page","Tasks").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            app.model.preferences(old.preferences.copy(allowAlarmVolumeOverride=true))
            audio.setStreamVolume(AudioManager.STREAM_ALARM,0,0)
            audio.adjustStreamVolume(AudioManager.STREAM_ALARM,AudioManager.ADJUST_MUTE,0)
            val before=audio.getStreamVolume(AudioManager.STREAM_ALARM);val beforeMuted=audio.isStreamMute(AudioManager.STREAM_ALARM)
            instrumentation.runOnMainSync {assertTrue(AlarmPlaybackService.test(app))}
            val manager=app.getSystemService(NotificationManager::class.java)
            val until=System.currentTimeMillis()+45_000
            while(System.currentTimeMillis()<until&&(audio.getStreamVolume(AudioManager.STREAM_ALARM)==0||manager.activeNotifications.none{it.id==93&&it.notification.extras.getString("android.subText")=="Alarm playing"}))delay(100)
            assertTrue(audio.getStreamVolume(AudioManager.STREAM_ALARM)>0)
            val playing=manager.activeNotifications.single {it.id==93}.notification
            assertEquals("Alarm playing",playing.extras.getString("android.subText"))
            assertTrue(playing.actions.any{it.title.toString()=="Stop alarm"})
            AlarmPlaybackService.stop(app)
            val stopUntil=System.currentTimeMillis()+10_000
            while(System.currentTimeMillis()<stopUntil&&(audio.getStreamVolume(AudioManager.STREAM_ALARM)!=before||audio.isStreamMute(AudioManager.STREAM_ALARM)!=beforeMuted))delay(100)
            assertEquals(before,audio.getStreamVolume(AudioManager.STREAM_ALARM));assertEquals(beforeMuted,audio.isStreamMute(AudioManager.STREAM_ALARM))
        }finally{AlarmPlaybackService.stop(app);AlarmAudio.restoreAfterStop(app);audio.setStreamVolume(AudioManager.STREAM_ALARM,initial,0);if(initialMuted)audio.adjustStreamVolume(AudioManager.STREAM_ALARM,AudioManager.ADJUST_MUTE,0);app.model.preferences(old.preferences);instrumentation.runOnMainSync{activity.finish()}}
    }
    @Test fun alarmCardUsesOneHighImportanceSilentChannelAndSettlesWithoutStop()=runBlocking {
        assumeTrue("Disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val instrumentation=InstrumentationRegistry.getInstrumentation();val app=instrumentation.targetContext.applicationContext as FieldNotesApplication;app.initialized.await()
        val old=app.vault.load();val audio=app.getSystemService(AudioManager::class.java);val initial=audio.getStreamVolume(AudioManager.STREAM_ALARM);val initialMuted=audio.isStreamMute(AudioManager.STREAM_ALARM)
        val manager=app.getSystemService(NotificationManager::class.java)
        val channel=manager.getNotificationChannel(ReminderSounds.channel(app,AlertProfile(alarm=true,duration=5)))
        assertEquals(ReminderSounds.ALARM_CHANNEL,channel.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH,channel.importance)
        assertNull(channel.sound);assertFalse(channel.shouldVibrate())
        assertTrue(manager.notificationChannels.none{it.id.startsWith("fn-alarm-v1-")})
        try {
            app.model.preferences(old.preferences.copy(allowAlarmVolumeOverride=true))
            instrumentation.runOnMainSync {assertTrue(AlarmPlaybackService.test(app))}
            fun card()=manager.activeNotifications.firstOrNull {it.tag?.startsWith("field-notes:")==true&&it.notification.channelId==channel.id}
            val until=System.currentTimeMillis()+45_000
            while(System.currentTimeMillis()<until&&(card()==null||manager.activeNotifications.none {it.id==93}))delay(100)
            val shown=checkNotNull(card()).notification
            assertTrue("The on-screen card must offer Stop while the sound plays",shown.actions.any {it.title.toString()=="Stop alarm"})
            assertTrue(shown.actions.any {it.title.toString()=="Open item"}||shown.contentIntent!=null)
            shown.actions.single {it.title.toString()=="Stop alarm"}.actionIntent.send()
            val settled=System.currentTimeMillis()+15_000
            while(System.currentTimeMillis()<settled&&(manager.activeNotifications.any {it.id==93}||card()?.notification?.actions?.any {it.title.toString()=="Stop alarm"}!=false))delay(100)
            assertTrue("Playback controls end with the sound",manager.activeNotifications.none {it.id==93})
            val after=checkNotNull(card()) {"The card stays after Stop"}.notification
            assertTrue("A finished alarm no longer offers Stop",after.actions.orEmpty().none {it.title.toString()=="Stop alarm"})
        }finally{AlarmPlaybackService.stop(app);AlarmAudio.restoreAfterStop(app);audio.setStreamVolume(AudioManager.STREAM_ALARM,initial,0);if(initialMuted)audio.adjustStreamVolume(AudioManager.STREAM_ALARM,AudioManager.ADJUST_MUTE,0);app.model.preferences(old.preferences);manager.cancelAll()}
    }
}
