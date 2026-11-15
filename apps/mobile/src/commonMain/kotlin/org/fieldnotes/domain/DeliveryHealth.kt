package org.fieldnotes.domain

const val SCHEDULING_FAILURE="Android could not schedule reminders. Your tasks and edits are saved. Open Settings → Reminders, check notification and precise-reminder permissions, then tap Retry reminder scheduling."
const val SCHEDULING_STORAGE_FAILURE="Phone storage prevented reminder scheduling. Your saved entries are kept. Free some storage, then open Settings → Reminders and tap Retry reminder scheduling."
const val SCHEDULING_STATE_FAILURE="Saved reminder scheduling state could not be read. Your entries are kept. Open Settings → Reminders and retry; do not clear app data or reset pairing."
fun isSchedulingWarning(message:String?)=message in setOf(SCHEDULING_FAILURE,SCHEDULING_STORAGE_FAILURE,SCHEDULING_STATE_FAILURE,
    "Reminder scheduling needs attention.","Reminder scheduling needs attention. Open Field Notes.","Open Field Notes to restore reminder scheduling.","Delivery checks could not complete. Check storage and Android alarm permissions.")
