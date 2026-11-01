package org.fieldnotes.ui

/** OS-owned actions stay outside the shared planner and its transport. */
data class DeviceActions(
    val update:org.fieldnotes.domain.AppUpdateUi=org.fieldnotes.domain.AppUpdateUi(),
    val checkUpdate:()->Unit={},val downloadUpdate:()->Unit={},val cancelUpdate:()->Unit={},val installUpdate:()->Unit={},
    val androidDelivery: Boolean = false,
    val status: String = "",
    val addUpcomingWidget: () -> Unit = {},
    val profileTest: () -> Unit = {},
    val testAlert: () -> Unit = {},
    val notificationSettings: () -> Unit = {},
    val alarmSettings: () -> Unit = {},
    val batterySettings: () -> Unit = {},
    val profileSettings: () -> Unit = {},
    val appSettings: () -> Unit = {},
    val removeInstallation: suspend () -> Unit = {},
)
