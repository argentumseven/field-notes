package org.fieldnotes.domain
import kotlin.test.*
import kotlinx.serialization.json.*
class PlannerSoundClockTest {
 @Test fun oldOptionsDoNotEmitNewDefaultsAndSavedDurationsRemainIntact(){
  val options=ReminderOptions(playback=mapOf("r" to ReminderPlayback(duration_seconds=30,alarm=true)))
  assertFalse(reminderOptionsData(options).containsKey("playback_version"))
  assertFalse(reminderOptionsData(options).toString().contains("notification_unmute"))
  val record=Record("event","event",1,obj("reminder_options" to reminderOptionsData(options)))
  assertEquals(30,effectiveAlertProfile(record,"r",AlertProfile()).duration)
  assertEquals(0,ReminderPlayback(duration_seconds=0,alarm=true).applyTo(AlertProfile()).duration)
 }
 @Test fun notificationVolumeRequiresNewFormatAndOneDeliveryMode(){
  assertFails{validateReminderOptions(ReminderOptions(playback=mapOf("r" to ReminderPlayback(notification_unmute=true))))}
  assertFails{validateReminderOptions(ReminderOptions(playback=mapOf("r" to ReminderPlayback(alarm=true,notification_unmute=true)),playback_version=2))}
  validateReminderOptions(ReminderOptions(playback=mapOf("r" to ReminderPlayback(notification_unmute=true,notification_volume=75)),playback_version=2))
 }
 @Test fun timeComparisonUsesResponseMidpointAndKeepsOldServersCompatible(){
  assertNull(clockWarning(obj(),1000,2000,1000))
  assertNull(clockWarning(obj("server_time_ms" to 1_001_000L.j(),"clock_health" to obj("status" to "synchronized".j())),1_000_000,1_002_000,2000))
  assertContains(clockWarning(obj("server_time_ms" to 1_100_000L.j()),1_000_000,1_002_000,2000)!!,"differ")
 }
 @Test fun localClockJumpsAndUnsyncedHostAreExplained(){
  assertContains(clockWarning(obj("server_time_ms" to 1_001_000L.j()),1_000_000,1_050_000,1000)!!,"interrupted")
  assertContains(clockWarning(obj("server_time_ms" to 1_001_000L.j(),"clock_health" to obj("status" to "unsynchronized".j())),1_000_000,1_002_000,2000)!!,"not synchronized")
 }
}
