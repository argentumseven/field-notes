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
    fun channel(context:Context,profile:AlertProfile):String {
        val manager=context.getSystemService(NotificationManager::class.java)
        val id=if(profile.importance=="quiet")"fn-reminder-v1-quiet" else if(profile.alarm)"fn-alarm-v1-${profile.importance}-${profile.vibrate}" else "fn-reminder-v1-${profile.importance}-${profile.sound}-${profile.volume}-${profile.vibrate}"
        if(manager.getNotificationChannel(id)==null) {
            manager.createNotificationChannelGroup(NotificationChannelGroup("custom-reminders","Event and task reminders"))
            val name=if(profile.importance=="quiet")"Quiet reminders" else if(profile.alarm)"${profile.importance.replaceFirstChar { it.uppercase() }} alarm reminders" else "${profile.importance.replaceFirstChar { it.uppercase() }} · ${profile.sound.replaceFirstChar { it.uppercase() }} · ${profile.volume}%"
            manager.createNotificationChannel(NotificationChannel(id,name,when(profile.importance){"urgent"->NotificationManager.IMPORTANCE_HIGH;"quiet"->NotificationManager.IMPORTANCE_LOW;else->NotificationManager.IMPORTANCE_DEFAULT}).apply {
                group="custom-reminders";description=if(profile.alarm)"Local alarm playback uses each reminder's chosen sound and duration. Android alarm volume, DND and profile settings apply." else "Sound level is relative to this phone's notification volume. Android and profile settings remain in control."
                setSound(if(profile.importance=="quiet"||profile.alarm)null else sound(context,profile),AudioAttributes.Builder().setUsage(if(profile.alarm)AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
                enableVibration(profile.vibrate&&profile.importance!="quiet");lockscreenVisibility=Notification.VISIBILITY_PRIVATE
            })
        }
        return id
    }
    internal fun sound(context:Context,p:AlertProfile):Uri {
        val id=context.resources.getIdentifier("reminder_${p.sound}_${p.volume}","raw",context.packageName)
        check(id!=0) { "Reminder sound is unavailable" }
        return Uri.parse("android.resource://${context.packageName}/raw/reminder_${p.sound}_${p.volume}")
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
