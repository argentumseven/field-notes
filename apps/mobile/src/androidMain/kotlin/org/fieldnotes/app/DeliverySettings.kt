package org.fieldnotes.app

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.media.AudioManager
import android.provider.Settings
import android.widget.Toast
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.fieldnotes.domain.*

object DeliverySettings {
    fun summary(context: Context): String {
        Alerts.channels(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        val channel=manager.getNotificationChannel(Alerts.REMINDERS)
        val audible=channel?.sound!=null&&(channel.importance>=NotificationManager.IMPORTANCE_DEFAULT)
        val exact=Build.VERSION.SDK_INT<31||context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val unrestricted=context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        return listOf(
            if(Alerts.allowed(context,Alerts.REMINDERS))"Notifications allowed" else "Notifications blocked in Android",
            if(audible)"Reminder channel has sound" else "Reminder channel is silent",
            if(exact)"Precise alarms allowed" else "Alarms may be delayed; allow precise alarms",
            if(unrestricted)"Battery unrestricted" else "Battery restrictions can delay live updates",
            (context.applicationContext as FieldNotesApplication).deliveryError.value,
            "Alarm volume: ${context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)} / ${context.getSystemService(AudioManager::class.java).getStreamMaxVolume(AudioManager.STREAM_ALARM)}",
        ).filterNotNull().joinToString("\n")
    }
    fun open(context:Context,action:String,channel:String?=null){
        val intent=when(action){
            "notifications"->if(channel==null)Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName) else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,channel)
            "sound"->Intent(Settings.ACTION_SOUND_SETTINGS)
            "dnd"->Intent("android.settings.ZEN_MODE_SETTINGS")
            "alarms"->if(Build.VERSION.SDK_INT>=31)Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+context.packageName))else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName))
            "profiles"->Intent(Settings.ACTION_SETTINGS)
            else->Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try{context.startActivity(intent)}catch(_:ActivityNotFoundException){context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    }
    suspend fun remove(context:Context){
        val app=context.applicationContext as FieldNotesApplication
        AlarmPlaybackService.stop(context);AlarmAudio.restoreAfterStop(context);NotificationAudio.restoreAfterStop(context)
        app.model.removeInstallation()
        PrivateUpdatesService.stop(context)
        withContext(Dispatchers.IO){WorkManager.getInstance(context).cancelAllWork()}
        LocalAlarms.clear(context)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        app.vault.clearEffects()
        PlannerWidget.refresh(context);UpcomingWidget.refresh(context);CalendarWidget.refresh(context)
        open(context,"uninstall")
    }
    fun problem(context:Context):String? = when {
        !Alerts.permissionAllowed(context)->"Android notifications are blocked. Enable them so reminders and alarm controls can be shown."
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter==NotificationManager.INTERRUPTION_FILTER_NONE->"Do Not Disturb is blocking sounds. Review Android's exceptions for alarms."
        else->null
    }
    suspend fun recheck(context:Context) {
        val app=context.applicationContext as FieldNotesApplication
        app.deliveryError.value=null
        try { AlarmAudio.restore(context);NotificationAudio.restore(context);LocalAlarms.reconcile(context,true);problem(context)?.let {app.deliveryError.value=it} }
        catch(_:Exception){app.deliveryError.value="Delivery checks could not complete. Check storage and Android alarm permissions."}
    }
    fun pin(context:Context){
        if(!UpcomingWidget.pin(context))Toast.makeText(context,"Open your launcher's widget picker and choose Field Notes · Upcoming.",Toast.LENGTH_LONG).show()
    }
}
