# Reminders and sound

Open an event, task or a non-grocery list item and expand **Reminders**. Add up to eight alerts
relative to its start. A task date uses 9 AM locally; an event uses its chosen
start. A repeat window works independently of a deadline, including undated
tasks. Saving an explicit empty reminder set disables that item's reminders.
Older items without a reminder setting retain the previous default policy.

Choose **Custom time…** on the website, or **Add reminder → Custom time…**
on Android. Enter an **Amount before** and choose Minutes, Hours, Days, Weeks,
Months or Years. Android's **Time** button changes an existing reminder; Sound
and Remove remain separate. Several reminders may use different units.

Minutes/hours/days/weeks count elapsed time: one day is 24 hours. Months and
years use calendar dates in the saved timezone. March 31 minus one month clamps
to February 28 (29 in a leap year); February 29 minus one year clamps to February
28. Calendar leads preserve the local clock through daylight-saving changes;
a nonexistent clock time moves forward through the gap, and an ambiguous time
uses its first occurrence. The dialog shows the calendar timezone. Date-only
month/year reminders use 9 AM in that saved zone. Existing minute-based reminders
keep their original timing and receipt identities.

The maximum is ten years / 120 months, with corresponding bounds for elapsed
units. Zero means at the start. Reminders before an event need its start; tasks
need a due/planned day and notes need a follow-up time. An independent daily or
weekly reminder window can still be used without a due date. A start-only event
needs no end time.

Use the current app on every phone that should receive month/year or other
extended timing. Compatible older phones still receive their understood alerts
and can amend ordinary content. They do not receive a fake at-start substitute,
and cannot overwrite the newer reminder rules through an older editor. Newer
queued timing on an older server stays retained with a visible explanation.
See [Updates](UPDATES.md).

Choose **Who should receive these reminders?**: Only me, Everyone, an assigned
task's person, or selected household people. New tasks, events and reminder
notes start with Everyone. Existing personal selections stay unchanged. This controls
alerts, not shared calendar visibility. A recipient's own enrolled instances
can each alert; the app does not elect one of that person's phones. Existing
events keep their previous household policy until changed.
Selecting people does not itself create an alert; add a reminder or time window.
The shared web selector keeps **Choose several people** open while you check or
uncheck people, including when only one is currently selected. An empty selected
audience cannot be saved. Grocery items have no reminder editor; list-level
new-item notices remain independently configurable. Existing recipient choices
are preserved instead of silently broadening who receives alerts.
Saving submits a durable change even when the editor closes immediately. If the
phone is offline, it remains queued and visible until connectivity returns;
closing the editor does not abandon that delivery.

Each alert has Quiet, Normal or Urgent importance, vibration and 25/50/75/100%
relative sound levels. Eight original tones are available: Chime, Bell, Pulse,
Beacon, Marimba, Orbit, Sunrise and Siren. Preview uses the current volume and
does not raise it. Regular notifications use the notification stream/channel.

Enable **Play as an alarm** for 5, 10, 15, 30 or 60 seconds of local playback.
Enable **Temporarily raise alarm volume** and choose the level. On each phone,
also enable **Allow temporary alarm-volume control** in Settings, then try
**Test a loud alarm (10 s)**. This explicitly permits raising a zero/muted alarm
stream; the notification stream and DND policy are unchanged. A visible Stop
action ends playback. The previous alarm volume/mute state is restored afterward,
unless you changed it manually. The recovery record is encrypted and survives
process restart; Android cannot execute recovery while force-stopped. Reopening
the app restores an orphaned change. Use **Stop alarm and restore volume** before
directly force-stopping or uninstalling during playback. In-app removal does
that automatically. Android's DND/audio restrictions remain authoritative.
If Android refuses the original volume or mute state, the app retains recovery
and displays an error directing you to Android sound settings. It does not report
successful restoration. A later manual change takes priority over recovery.
Concurrent alarms play sequentially within a bounded queue; their detailed
notification cards remain available even if audio cannot start.

The website configures these same rules; alarm playback happens on Android.
Update the paired server and all phones to use the new controls. Version 0.4
clients retain basic supported reminders during rollout, with personal alerts
suppressed for nonrecipients; they do not implement longer alarm playback.
Earlier clients should be updated before relying on recipient filtering.
An old editor that could erase advanced rules is rejected with an explanation.

For situational reminders, enable **Repeat during a time window**. Choose its
first day, Once or weekdays, optional last day, From/Until times and an interval
in minutes or hours. The From time is included and Until is excluded. Until
before From continues into the next day; equal times mean a full day. A Monday
overnight window belongs to Monday even after midnight. The saved IANA zone
keeps the routine in the chosen home time when travelling. Nonexistent spring
clock-change occurrences are skipped; repeated autumn times fire once.

These windows repeat notifications, not task copies. Completing a task stops
its reminders; reopening allows future ones. There is no completion-based
recurring task generator, snooze history or
per-occurrence calendar-series editing yet. A future scope must preserve those
differences rather than silently create duplicate tasks.

Android schedules from the encrypted local cache, including offline edits.
AlarmManager keeps a rolling bounded schedule and refills it after delivery,
without a server round trip. Delivered progress and occurrence receipts prevent
retries from sounding again. For repeats, at most the latest missed tick within
ten minutes is caught up; an outage never plays a burst of every missed tick.
Multiple simultaneous rules with the same profile are combined; different sound
profiles remain separate notifications. Very frequent reminders can be delayed
or coalesced by Android, especially under Doze. Exact-alarm permission improves
timing but does not override operating-system limits.

**Detailed reminder notifications** shows the title, local schedule, intended
people, notes, list/project and creator/activity times. New installs show these
in the unlocked shade. Existing off choices are retained, with a one-tap option
to reveal details. **Notification details on the lock screen** is a separate,
default-off choice, also subject to Android's settings. Tapping opens the single
task/event. App credential locking is optional and does not govern these choices.
Delivery failures remain in the app's status, notification and widget footer
until corrected/rechecked; blocked notifications cannot display their own warning.

## GrapheneOS full user profiles

Leave the Field Notes profile running, enable its notification forwarding and
background permission, and use the **30-second profile test** before relying on
it. An ended session cannot deliver alarms. The visible private background
connection and WorkManager fallback use no Google services or OS VPN slot.

Forwarded alerts use the receiving user's system **Other users** notification
channel. That channel controls forwarded sound/volume; GrapheneOS does not copy
each original Field Notes tone. Configure sound and importance for Other users
in the profile you switch to. Quiet source alerts remain quiet. Per-rule tone
and relative loudness apply in the profile running Field Notes. This follows
the [GrapheneOS notification forwarding implementation](https://github.com/GrapheneOS/platform_frameworks_base/blob/785c2d1ccbfa73be98018fd7bc415923605682cc/services/core/java/com/android/server/notification/NotificationManagerService.java#L11275).
Physical cross-profile sound, prolonged Doze and battery behavior remain device
checks; emulator notification delivery does not establish those properties.

See [Android delivery and clean removal](ANDROID-DELIVERY.md).
