@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.fieldnotes.domain.*

internal enum class SettingsFacet(val title:String,val detail:String) {
    REMINDERS("Reminders", "Scheduling, permissions, delivery checks and repair"),
    SOUND("Sound & alarms", "Alarm and notification volume, tests and Do Not Disturb"),
    NOTIFICATIONS("Notifications", "Shared additions and notification details"),
    WIDGETS("Home-screen widgets", "Add widgets and choose what they show"),
    APPEARANCE("Appearance", "Light, dark and system theme"),
    PRIVACY("Privacy & app lock", "Device credentials and lock-screen details"),
    CONNECTION("Connection & background", "Server, private connection and background refresh"),
    UPDATES("App updates", "Check, download and install app updates"),
    DATA("Data & recovery", "Export, import and pending recovery actions"),
    ACCOUNT("Account & installation", "Account details and removal of this installation")
}
internal fun settingsFacetForIssue(message:String):SettingsFacet=when {
    message.contains("volume",true)||message.contains("Do Not Disturb",true)->SettingsFacet.SOUND
    message.contains("newer",true)||message.contains("update",true)->SettingsFacet.UPDATES
    message.contains("reminder",true)||message.contains("notification",true)||message.contains("alarm",true)->SettingsFacet.REMINDERS
    message.contains("storage",true)->SettingsFacet.DATA
    else->SettingsFacet.CONNECTION
}

@Composable internal fun WorkspaceSettings(model:Workspace,state:WorkspaceState,device:DeviceActions,facet:SettingsFacet?,select:(SettingsFacet?)->Unit,close:()->Unit,background:(Preferences)->Unit,endpoint:()->Unit,profileHelp:()->Unit,remove:()->Unit) {
    val prefs=state.cache.preferences
    ModalBottomSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=760.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(facet!=null)SecondaryButton(onClick={select(null)}){Text("‹ Settings")}
                Text(facet?.title ?: "Settings",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                SecondaryButton(onClick=close){Text("Close")}
            }
            HorizontalDivider()
            if(facet==null)VisibleLazyColumn(Modifier.weight(1f).testTag("settings-categories"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(device.warning.isNotBlank())item {OutlinedCard(onClick={select(settingsFacetForIssue(device.warning))},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text("Review reminder setup",style=MaterialTheme.typography.titleMedium);Text(device.warning,style=MaterialTheme.typography.bodySmall);Text("Open the relevant settings ›",color=MaterialTheme.colorScheme.primary)}}}
                items(SettingsFacet.entries,key={it.name}) {section->OutlinedCard(onClick={select(section)},modifier=Modifier.fillMaxWidth().testTag("settings-"+section.name.lowercase())) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(section.title+" ›",style=MaterialTheme.typography.titleMedium);Text(section.detail,style=MaterialTheme.typography.bodySmall)}}}
            } else key(facet) {Column(Modifier.fillMaxWidth().weight(1f).plannerScroll().testTag("settings-section").padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                when(facet) {
                    SettingsFacet.REMINDERS->{
                        Text("These settings control whether this phone can schedule and show your saved reminders. They do not change task dates or send reminders to other people.")
                        SettingSwitch("Task and calendar reminders","Turn on reminders saved on your items, including repeating nudges without a due date. Cached reminders also work offline.",prefs.reminders){val p=prefs.copy(reminders=it);model.launch{model.preferences(p);background(p)}}
                        if(device.androidDelivery){
                            if(device.warning.isNotBlank())OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("What needs attention",style=MaterialTheme.typography.titleSmall);Text(device.warning)}}
                            Text("Reminder setup checklist",style=MaterialTheme.typography.titleMedium)
                            Text("1. Allow notifications so Android can show reminders and Stop controls.\n2. Allow precise reminders for scheduled times; otherwise Android may delay them.\n3. Retry scheduling after changing a permission.\n4. Run a test notification to check this profile.")
                            Text(device.status,style=MaterialTheme.typography.bodySmall)
                            SecondaryButton(onClick=device.notificationSettings,modifier=Modifier.fillMaxWidth()){Text("1 · Notification permissions & channels")}
                            SecondaryButton(onClick=device.alarmSettings,modifier=Modifier.fillMaxWidth()){Text("2 · Allow precise reminders")}
                            SecondaryButton(onClick=device.recheckDelivery,modifier=Modifier.fillMaxWidth()){Text("3 · Retry reminder scheduling")}
                            SecondaryButton(onClick=device.testAlert,modifier=Modifier.fillMaxWidth()){Text("4 · Test notification")}
                            Text("Battery restrictions and stopped GrapheneOS profiles can delay refresh. Saved local reminders do not require a working server connection.",style=MaterialTheme.typography.bodySmall)
                            SecondaryButton(onClick=device.batterySettings){Text("Background battery settings")}
                            SecondaryButton(onClick={select(SettingsFacet.CONNECTION)}){Text("Connection & background refresh ›")}
                            SecondaryButton(onClick=profileHelp){Text("Alerts in other GrapheneOS profiles")}
                            SecondaryButton(onClick=device.profileTest){Text("Test in another profile (30 s)")}
                            device.dismissDeliveryIssue?.let{dismiss->SecondaryButton(onClick=dismiss){Text("Dismiss reviewed delivery alert")}}
                        }
                    }
                    SettingsFacet.SOUND->{
                        Text("Per-reminder importance, sound and repetition are chosen on the item. These controls apply only to this phone.")
                        SettingSwitch("Allow temporary alarm-volume control","Only for alarm reminders that request it. Restores the previous level and preserves later manual changes. Stop playback before force-stop or uninstall.",prefs.allowAlarmVolumeOverride){val enabled=it;model.launch{model.preferences{it.copy(allowAlarmVolumeOverride=enabled)}}}
                        SettingSwitch("Allow temporary notification-volume control","Only for reminders that request it. Restores the previous level and respects Silent, Vibrate and DND.",prefs.allowNotificationVolumeOverride){val enabled=it;model.launch{model.preferences{it.copy(allowNotificationVolumeOverride=enabled)}}}
                        if(device.androidDelivery){
                            SecondaryButton(onClick=device.testLoudAlarm,enabled=prefs.allowAlarmVolumeOverride){Text("Test a loud alarm (5 s)")}
                            SecondaryButton(onClick=device.stopAlarm){Text("Stop sound and restore volume")}
                            SecondaryButton(onClick=device.soundSettings){Text("Android sound & alarm volume")}
                            SecondaryButton(onClick=device.notificationSettings){Text("Notification sound settings")}
                            SecondaryButton(onClick=device.dndSettings){Text("Do Not Disturb exceptions")}
                        }
                    }
                    SettingsFacet.NOTIFICATIONS->{
                        SettingSwitch("Shared additions","Name the person and item when someone adds shared content. Bulk additions use one expandable alert; lists can mute their own additions.",prefs.sharedAdditions){val enabled=it;model.launch{model.preferences{it.copy(sharedAdditions=enabled)}}}
                        SettingSwitch("Detailed reminder notifications","Show the title, schedule, person, notes and activity in the notification shade.",prefs.notificationTitles){val enabled=it;model.launch{model.preferences{it.copy(notificationTitles=enabled,visibilityChoiceSeen=true)}}}
                        SecondaryButton(onClick={select(SettingsFacet.PRIVACY)}){Text("Lock-screen privacy ›")}
                        if(device.androidDelivery)SecondaryButton(onClick=device.notificationSettings){Text("Android notification settings")}
                    }
                    SettingsFacet.WIDGETS->{
                        SettingSwitch("Show schedules in home-screen widgets","Your launcher receives copies of the displayed titles.",prefs.widgetTitles){val p=prefs.copy(widgetTitles=it,visibilityChoiceSeen=true);model.launch{model.preferences(p);background(p)}}
                        SettingSwitch("Widget details while the device is locked","Launchers may retain previously displayed copies.",prefs.widgetOnLockScreen){val enabled=it;model.launch{model.preferences{it.copy(widgetOnLockScreen=enabled,widgetTitles=enabled||it.widgetTitles)}}}
                        SettingSwitch("Include everyone's appointments in widgets","Show the household calendar without sending you other people's alarms.",prefs.widgetHousehold){val enabled=it;model.launch{model.preferences{it.copy(widgetHousehold=enabled)}}}
                        Text("Widgets refresh when private sync receives changes. Tap a day to open its timeline, or the update status to sync now. Paused connections and stopped profiles stay stopped.")
                        if(device.androidDelivery){SecondaryButton(onClick=device.addWeekWidget){Text("Add Week to home screen")};SecondaryButton(onClick=device.addCalendarWidget){Text("Add Calendar to home screen")};SecondaryButton(onClick=device.addUpcomingWidget){Text("Add Upcoming to home screen")}}
                    }
                    SettingsFacet.APPEARANCE->{
                        Text("Theme",style=MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(theme in listOf("system","light","dark"))FilterChip(prefs.theme==theme,{model.launch{model.preferences{it.copy(theme=theme)}}},{Text(theme.replaceFirstChar{it.uppercase()})})}
                        Text("Calendar views and holiday display are available under Calendar options.")
                    }
                    SettingsFacet.PRIVACY->{
                        Text("App locking, reminder details and widget details are independent.")
                        SettingSwitch("Require a credential to open the app","Optional device-credential check. Reminders and widgets keep their own visibility choices.",prefs.appLock){val enabled=it;model.launch{model.preferences{it.copy(appLock=enabled)}}}
                        if(prefs.appLock)FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(minutes in listOf(1,5,15,30))FilterChip(prefs.lockMinutes==minutes,{model.launch{model.preferences{it.copy(lockMinutes=minutes)}}},{Text("$minutes min")})}
                        SettingSwitch("Notification details on the lock screen","Android's lock-screen settings also apply.",prefs.notificationOnLockScreen){val enabled=it;model.launch{model.preferences{it.copy(notificationOnLockScreen=enabled,notificationTitles=enabled||it.notificationTitles)}}}
                        SecondaryButton(onClick={select(SettingsFacet.WIDGETS)}){Text("Widget visibility ›")}
                    }
                    SettingsFacet.CONNECTION->{
                        ConnectionControls(model,state)
                        Text("Server: ${state.cache.config?.text("endpoint")}")
                        SecondaryButton(onClick=endpoint){Text("Edit server endpoint")}
                        if(device.androidDelivery){
                            ViewChoice("Background updates",backgroundUpdateMode(prefs),listOf("live" to "Live · fast updates","periodic" to "Periodic · save battery","manual" to "Manual")){mode->val p=prefs.withBackgroundUpdateMode(mode);model.launch{model.preferences(p);background(p)}}
                            Text(when(backgroundUpdateMode(prefs)){"live"->"The private background connection keeps notes, reminders and widgets fresh. Android shows an ongoing notification.";"periodic"->"Android schedules catch-up checks at 15-minute minimum intervals and may delay them to save battery.";else->"Open Field Notes or tap a widget's update status to sync. Saved reminders remain scheduled."})
                            SecondaryButton(onClick=device.batterySettings){Text("Background battery settings")}
                        }
                        Text("While open, Field Notes checks every 3 seconds and checks immediately when you return. Pause to work offline.",style=MaterialTheme.typography.bodySmall)
                    }
                    SettingsFacet.UPDATES->{
                        if(device.androidDelivery)AppUpdatesCard(device)
                        SettingSwitch("Automatically prepare app updates","Download and verify updates from this paired server. Android still asks before installation; interrupted transfers resume.",prefs.autoDownloadUpdates){val enabled=it;model.launch{model.preferences{it.copy(autoDownloadUpdates=enabled)}}}
                    }
                    SettingsFacet.DATA->{RecoveryPendingCard(model,state);if(device.androidDelivery)DataTransferCard(state.cache,device)}
                    SettingsFacet.ACCOUNT->{
                        val actor=state.cache.snapshot?.actor
                        Text("${actor?.name.orEmpty()} · ${actor?.role.orEmpty()}")
                        Text("Removing this installation is different from updating the app. Updates keep your pairing and saved edits.")
                        if(device.androidDelivery)SecondaryButton(onClick=remove,dangerous=true){Text("Revoke this installation and uninstall")}
                    }
                }
                Spacer(Modifier.height(20.dp))
            }}
        }
    }
}
