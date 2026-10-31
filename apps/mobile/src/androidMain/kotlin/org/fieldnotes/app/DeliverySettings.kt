package org.fieldnotes.app

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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
        ).filterNotNull().joinToString("\n")
    }
    fun open(context:Context,action:String,channel:String?=null){
        val intent=when(action){
            "notifications"->Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,channel?:Alerts.REMINDERS)
            "alarms"->if(Build.VERSION.SDK_INT>=31)Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+context.packageName))else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName))
            "profiles"->Intent(Settings.ACTION_SETTINGS)
            else->Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try{context.startActivity(intent)}catch(_:ActivityNotFoundException){context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    }
    suspend fun remove(context:Context){
        val app=context.applicationContext as FieldNotesApplication
        app.model.removeInstallation()
        PrivateUpdatesService.stop(context)
        WorkManager.getInstance(context).cancelAllWork()
        LocalAlarms.clear(context)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        app.vault.clearEffects()
        PlannerWidget.refresh(context);UpcomingWidget.refresh(context)
        open(context,"uninstall")
    }
    fun pin(context:Context){
        if(!UpcomingWidget.pin(context))Toast.makeText(context,"Open your launcher's widget picker and choose Field Notes · Upcoming.",Toast.LENGTH_LONG).show()
    }
}
