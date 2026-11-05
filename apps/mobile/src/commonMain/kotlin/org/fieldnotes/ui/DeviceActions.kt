package org.fieldnotes.ui

/** OS-owned actions stay outside the shared planner and its transport. */
data class DeviceActions(
    val transfer:org.fieldnotes.domain.TransferUi=org.fieldnotes.domain.TransferUi(),
    val exportData:(String)->Unit={},val chooseImport:()->Unit={},val previewImport:(String)->Unit={},val reviewImport:()->Unit={},val commitImport:()->Unit={},val cancelImport:()->Unit={},val mapImportPerson:(String,String)->Unit={_,_->},val refreshRecovered:()->Unit={},val restoreRecovered:(String,String?)->Unit={_,_->},val discardRecovered:(String)->Unit={},
    val update:org.fieldnotes.domain.AppUpdateUi=org.fieldnotes.domain.AppUpdateUi(),
    val checkUpdate:()->Unit={},val downloadUpdate:()->Unit={},val cancelUpdate:()->Unit={},val installUpdate:()->Unit={},
    val previewSound:(org.fieldnotes.domain.AlertProfile)->Unit={},
    val openLink:(String)->Unit={},
    val androidDelivery: Boolean = false,
    val status: String = "",
    val warning:String="",
    val use24Hour: Boolean = false,
    val addCalendarWidget: () -> Unit = {},
    val addUpcomingWidget: () -> Unit = {},
    val addWeekWidget:()->Unit={},
    val testLoudAlarm:()->Unit={},val stopAlarm:()->Unit={},val soundSettings:()->Unit={},val dndSettings:()->Unit={},val recheckDelivery:()->Unit={},
    val profileTest: () -> Unit = {},
    val testAlert: () -> Unit = {},
    val notificationSettings: () -> Unit = {},
    val alarmSettings: () -> Unit = {},
    val batterySettings: () -> Unit = {},
    val profileSettings: () -> Unit = {},
    val appSettings: () -> Unit = {},
    val removeInstallation: suspend () -> Unit = {},
)
