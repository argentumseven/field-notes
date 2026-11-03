# Reminders and sound

Open an event, task or list item and expand **Reminders**. Add up to eight alerts
relative to its start. A task date uses 9 AM locally; an event uses its chosen
start. A repeat window works independently of a deadline, including undated
tasks. Saving an explicit empty reminder set disables that item's reminders.
Older items without a reminder setting retain the previous default policy.

Each alert can have a different importance: Quiet, Normal or Urgent. Android
offers original Chime, Bell and Pulse tones, vibration, and 25/50/75/100% relative
sound levels. Preview the tone before saving. These levels scale the tone itself;
the phone's notification volume, channel settings and DND remain authoritative.
Urgent uses a high-importance Android channel; it cannot guarantee a heads-up
display or bypass a silent phone. Field Notes does not change system volume.
The web form configures the same rules; audio playback happens on Android.

For situational reminders, enable **Repeat during a time window**. Choose its
first day, Once or weekdays, optional last day, From/Until times and an interval
in minutes or hours. The From time is included and Until is excluded. Until
before From continues into the next day; equal times mean a full day. A Monday
overnight window belongs to Monday even after midnight. The saved IANA zone
keeps the routine in the chosen home time when travelling. Nonexistent spring
clock-change occurrences are skipped; repeated autumn times fire once.

These windows repeat notifications, not task copies. Completing a task stops
its reminders; reopening allows future ones. There is no completion-based
recurring task generator, snooze history, per-person reminder subscription or
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

Titles remain private by default. **Notification titles** opts into unlocked
notification details; lock-screen public content remains generic. Tapping an
alert opens its task or event when a single record is represented.

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
