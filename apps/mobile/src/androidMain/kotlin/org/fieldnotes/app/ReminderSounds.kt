package org.fieldnotes.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import org.fieldnotes.domain.AlertProfile

/** Notification channels keep their user's settings. Longer alarm playback is
 * owned by AlarmPlaybackService, independently of notification volume. */
object ReminderSounds {
    private var preview:MediaPlayer?=null
    /** One high-importance, silent channel for every alarm. Its audio and vibration come from
     * AlarmPlaybackService, so the card can pop up on screen without a second sound. The earlier
     * per-importance alarm channels defaulted to a level that never appeared on screen. */
    internal const val ALARM_CHANNEL="fn-alarm-v2"
    private const val OBSOLETE_ALARM_PREFIX="fn-alarm-v1-"
    fun channel(context:Context,profile:AlertProfile):String {
        val manager=context.getSystemService(NotificationManager::class.java)
        val quiet=profile.importance=="quiet"
        val id=if(quiet)"fn-reminder-v1-quiet" else if(profile.alarm)ALARM_CHANNEL else "fn-reminder-v1-${profile.importance}-${profile.sound}-${profile.volume}-${profile.vibrate}"
        if(manager.getNotificationChannel(id)==null) {
            manager.createNotificationChannelGroup(NotificationChannelGroup("custom-reminders","Event and task reminders"))
            val name=if(quiet)"Quiet reminders" else if(profile.alarm)"Alarms" else "${profile.importance.replaceFirstChar { it.uppercase() }} · ${profile.sound.replaceFirstChar { it.uppercase() }} · ${profile.volume}%"
            val importance=if(quiet)NotificationManager.IMPORTANCE_LOW else if(profile.alarm||profile.importance=="urgent")NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            manager.createNotificationChannel(NotificationChannel(id,name,importance).apply {
                group="custom-reminders";description=if(profile.alarm)"Alarms appear on screen with Stop and Open while Field Notes plays their chosen sound. Android alarm volume, DND and profile settings apply." else "Sound level is relative to this phone's notification volume. Android and profile settings remain in control."
                setSound(if(quiet||profile.alarm)null else sound(context,profile),AudioAttributes.Builder().setUsage(if(profile.alarm)AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
                enableVibration(profile.vibrate&&!quiet&&!profile.alarm);lockscreenVisibility=Notification.VISIBILITY_PRIVATE
            })
            // Old alarm channels cannot be raised in place; remove them so Settings shows one Alarms entry.
            if(profile.alarm)manager.notificationChannels.filter { it.id.startsWith(OBSOLETE_ALARM_PREFIX) }.forEach { manager.deleteNotificationChannel(it.id) }
        }
        return id
    }
    internal fun sound(context:Context,p:AlertProfile):Uri {
        val prefix=if(p.alarm)"alarm"else"reminder"
        val id=context.resources.getIdentifier("${prefix}_${p.sound}_${p.volume}","raw",context.packageName)
        check(id!=0) { "Reminder sound is unavailable" }
        return Uri.parse("android.resource://${context.packageName}/raw/${prefix}_${p.sound}_${p.volume}")
    }
    @Synchronized fun preview(context:Context,p:AlertProfile) {
        preview?.release();preview=null
        if(p.importance=="quiet")return
        val player=MediaPlayer()
        try { player.apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(if(p.alarm)AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            setDataSource(context,sound(context,p));setOnCompletionListener { it.release();if(preview===it)preview=null }
            setOnErrorListener { failed,_,_->failed.release();if(preview===failed)preview=null;true }
            prepare();start()
        };preview=player } catch(error:Exception) { player.release();throw error }
    }
}
