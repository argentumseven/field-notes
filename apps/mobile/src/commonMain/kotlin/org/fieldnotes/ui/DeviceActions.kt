package org.fieldnotes.ui

/** OS-owned actions stay outside the shared planner and its transport. */
data class DeviceActions(
    val androidDelivery: Boolean = false,
    val status: String = "",
    val addUpcomingWidget: () -> Unit = {},
    val profileTest: () -> Unit = {},
    val testAlert: () -> Unit = {},
    val notificationSettings: () -> Unit = {},
    val alarmSettings: () -> Unit = {},
    val batterySettings: () -> Unit = {},
    val profileSettings: () -> Unit = {},
    val removeInstallation: suspend () -> Unit = {},
)
