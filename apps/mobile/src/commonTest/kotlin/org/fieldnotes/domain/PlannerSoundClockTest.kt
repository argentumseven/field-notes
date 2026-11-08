package org.fieldnotes.domain
import kotlin.test.*
import kotlinx.serialization.json.*
class PlannerSoundClockTest {
 @Test fun deliveryModesOfferDistinctSoundsAndNeverCarryTheOtherOverride(){
  assertTrue(notificationTones.keys.intersect(alarmTones.keys).isEmpty())
  val notification=AlertProfile(notificationUnmute=true,notificationVolume=50)
  val alarm=notification.withDeliveryMode(true)
  assertEquals("beacon",alarm.sound);assertEquals(5,alarm.duration);assertTrue(alarm.alarm);assertFalse(alarm.notificationUnmute)
  val saved=alarm.copy(duration=30,unmute=true)
  assertEquals(saved,saved.withDeliveryMode(true))
  val back=saved.withDeliveryMode(false)
  assertEquals("chime",back.sound);assertEquals(0,back.duration);assertFalse(back.unmute)
 }
 @Test fun resolvedVolumeWarningsUseTheCorrectPermissionAndNeverHideOtherFailures(){
  val alarm=Preferences(allowAlarmVolumeOverride=true)
  assertTrue(resolvedVolumeWarning(ALARM_VOLUME_DISABLED,alarm))
  assertFalse(resolvedVolumeWarning(NOTIFICATION_VOLUME_DISABLED,alarm))
  assertFalse(resolvedVolumeWarning(ALARM_VOLUME_DISABLED,Preferences()))
  assertTrue(resolvedVolumeWarning(NOTIFICATION_VOLUME_DISABLED,alarm.copy(allowNotificationVolumeOverride=true)))
  assertTrue(resolvedVolumeWarning("This reminder requested temporary volume control, but it is off on this phone. Review Sound settings.",alarm))
  assertFalse(resolvedVolumeWarning("Previous alarm volume could not be restored. Open Android sound settings.",alarm))
 }
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
