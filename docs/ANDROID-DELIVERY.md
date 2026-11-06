# Home-screen widgets, alerts and GrapheneOS profiles

Field Notes does not require Play services, FCM, a push relay, an Android VPN
service, device administration, root, or a system CA installation. Background
mode uses the same app-contained WireGuard connection and restricted notes API
as foreground sync. The LAN management website is never exposed through it.

While the app is open, 0.9.1 checks immediately and every three seconds, with
freshness/queue status and default-network reconnection. That foreground loop is
separate from the background service below. See [Live updates](LIVE-UPDATES.md).

## Enable delivery once in the profile containing Field Notes

1. Enroll and activate the phone on home Wi-Fi. **Live background updates**, cached
   reminders and scheduled catch-up default on. Allow Android's notification
   prompt. Existing explicit off preferences remain off.
2. Open **Workspace menu → Settings & widgets**. Check the delivery status.
   Set Field Notes battery use to **Unrestricted** in its Android app settings
   if you want screen-off updates. This costs battery: a visible foreground
   service polls the small authenticated workspace head every 15 seconds while
   healthy, maintains a bounded wake lock, and backs off to five minutes while
   offline. It downloads content only when changed or work is queued.
3. Allow **Alarms & reminders** for closer scheduled timing. Without that access
   the app still schedules alarms, but Android may delay them. Timed calendar
   events alert ten minutes before start; all-day events and due tasks alert at
   09:00 device-local time. A single daily overdue digest avoids one alarm per
   missed day. Simultaneous alerts are combined. Undated priorities remain in
   Focus. Per-item Reminders supports multiple alerts and repeat windows;
   snooze remains future work. Choose Only me, household people, an assigned
   task's person or Everyone so another person's appointment need not alert you.
4. Use **Play a sound test now**. Check the **Upcoming tasks and calendar**
   channel has sound, notification volume is audible and DND permits it.
   **Household updates** is a separate audible channel; Android lets you adjust
   it independently. Existing user channel choices are never overwritten.
   For longer/louder playback, choose Play as an alarm on the item's reminder
   and enable Allow temporary alarm-volume control on this phone. Test a loud
   alarm verifies the visible Stop action and volume restoration. DND exceptions
   still belong to Android. See [Reminders](REMINDERS.md).
5. Keep **Live background updates** enabled for changes from the other person while
   the UI is closed. The ongoing connection status is silent and excluded from
   profile forwarding. Its Stop action switches live mode off. WorkManager
   catch-up is a 15-minute-minimum fallback, subject to Android scheduling.

Local alarms read the encrypted cache even when DNS, the server or the tunnel
is unavailable. Boot, app update, clock/time-zone changes, permission changes
and cache changes reconcile scheduled alarms. Occurrence receipts are encrypted;
alarm intents contain opaque identifiers, never titles or credentials. Known
rejected authorization stops delivery and redacts widgets. A disconnected phone
cannot learn a remote revocation or edit until it reconnects.

## Full GrapheneOS user profiles

In the **source profile** containing Field Notes, enable GrapheneOS notification
forwarding to the active user and permit that profile to run in the background.
GrapheneOS controls this privileged setting; an ordinary app cannot enable it.
Also permit notifications on the source lock screen. Switch to the receiving
profile and check its forwarded-notification channel, volume and DND settings.
Source silent/low-importance or DND-suppressed alerts may be forwarded silently.
The normal reminder channels use high importance and private visibility.

Use **Test in another profile (30 s)** in Field Notes, then switch profiles. It
posts a fresh generic test after 30 seconds (subject to alarm permission and OS
timing). GrapheneOS forwards the app name, source profile and time, with an
action to switch back; it does not expose task text. Forwarded alerts can sound
when both profiles' settings permit. A successful test in one configuration is
not evidence for every DND, battery or lock-screen configuration.

Leave the source profile running. **End session**, Android force-stop, the
system's Stop action for a running app, power-off, and disabled notifications
can prevent delivery. The app cannot override these. After reboot, unlock the
source profile once so its encrypted cache is available. Reopen Field Notes
following a force-stop. No Android app can promise unconditional alerts across
stopped users. Verify this on both actual phones before relying on reminders.

Official references, inspected 2026-10-30:

- [GrapheneOS notification forwarding](https://grapheneos.org/features#notification-forwarding).
- [GrapheneOS user-profile improvements](https://grapheneos.org/features#improved-user-profiles).
- [Published website source](https://github.com/GrapheneOS/grapheneos.org/blob/beb4104e0eddf78fcbc71e2754d5b5102838dceb/static/features.html), including the End session behavior.
- [GrapheneOS forwarding implementation](https://github.com/GrapheneOS/platform_frameworks_base/blob/785c2d1ccbfa73be98018fd7bc415923605682cc/services/core/java/com/android/server/notification/NotificationManagerService.java), `getCensoredSendState` and `EnqueueCensoredNotificationRunnable`.

## Actual launcher widgets

In Field Notes settings, tap **Add Week to home screen**, **Add Calendar to home screen** or **Add Upcoming to home screen** and accept the
launcher's standard Add confirmation. Alternatively long-press the home screen,
open Widgets and choose **Field Notes · Week**, **Field Notes · Calendar**, **Field Notes · Upcoming** or **Field Notes · Focus**.
No special widget-binding permission is required.

[Calendar](CALENDAR.md) shows a navigable month grid, scheduled days and today’s agenda. Its rendering runs off the app’s UI thread and coalesces repeated refreshes. Upcoming shows upcoming tasks/planned days and internal calendar events, with
an undated Focus fallback when the agenda is empty. Focus uses priority ranking.
Tap the agenda to open Calendar; tap its footer to request refresh. Both read
the encrypted cache and refresh on changes, unlock/lock broadcasts and Android's
periodic widget update. Offline cached items are deliberately labeled cached.

New installs show schedules while unlocked; existing explicit off choices stay
off. **Show schedules in home-screen widgets** controls title copies in the
launcher. **Widget details while the device is locked** separately allows
locked-device disclosure and defaults off. Optional app credential locking does
not govern either setting. The launcher may retain old RemoteViews. Widgets
never include task notes, images or credentials. Screen lock/known invalid
sessions request redaction; immediate erasure of launcher-held copies cannot be
guaranteed. Widgets are local to the profile hosting them. Notification
forwarding does not copy a widget into another profile. To use a widget there,
install and separately enroll Field Notes there, with independent keys. Choose
one instance to sound reminders if duplicate notifications are undesirable.

## Revoke and uninstall

While connected, review/sync drafts, queued changes and conflicts, then use
**Revoke this installation and uninstall** in settings and confirm the action. The
server revokes only this enrolled device's sessions, records an audit event and
queues peer removal. Application authorization stops immediately; the server
management screen tracks the host's acknowledgement of actual peer removal.
Other enrolled devices continue working.

The app then stops its service, cancels its workers/alarms/notifications, clears
its private cache/effects/images, redacts its widgets, and opens Android's app
information screen. Tap **Uninstall**, without retaining app data. Normal
Android uninstall removes this profile's package-private data, Keystore keys,
permissions, jobs, alarms and widget provider. Field Notes writes no task data
to shared storage and installs no OS VPN, routes, CA, device-admin policy or
external helper to clean up on the phone.

In-app removal also stops a ringing alarm and restores its temporary system
volume change before clearing the recovery record. Before directly force-stopping
or uninstalling during an alarm, use **Stop alarm and restore volume**. Android
cannot run cleanup after force-stop or restore a global sound setting after the
app has been removed. If force-stopped during playback, reopening recovers the
saved volume lease. This is a limit of explicitly changing the system stream.

Direct uninstall also removes local app state, but Android cannot let an already
removed app phone home to revoke a server identity. If offline or already
removed, revoke that device from LAN management. Household tasks, comments,
attachments, server audit history and backups belong to the shared server and
are deliberately not erased by uninstalling a phone. Delete those separately
with the appropriate role. Other profile installations, the downloaded APK and
OS/launcher retained history are outside this installation's ownership; no claim
of forensic erasure of those copies is made.

## Verification boundary

The disposable AOSP emulator tests actual launcher hosting, scheduled broadcast
posting, foreground-service status, notification channel sound/importance,
privacy and encrypted receipts. A separate normal-uninstall/reinstall harness
checks live-work removal and destruction of the old profile encryption key.
It has no Google services. It cannot verify
GrapheneOS forwarding, physical speaker output, long-running battery cost,
reboot/Doze reliability on your phones or behavior beside other app-contained tunnels. Those remain
physical-device release checks; see [VALIDATION.md](VALIDATION.md).

Run the guarded development checks only on the disposable `field-notes-test` AVD:

```sh
bash scripts/build/android-emulator.sh
node scripts/test-android-emulator.mjs
node scripts/capture-home-widget.mjs
node scripts/test-android-reminders.mjs
node scripts/test-android-uninstall.mjs
```

The uninstall harness intentionally removes/reinstalls the test application; it
refuses physical phones and other AVD names. Runtime reports are under
`dist/android-test-results/` and contain synthetic fixture state only.

## Per-record reminder controls

Version 0.4 adds multiple independent reminder profiles, original tones, relative
loudness and daily/weekly time windows, with a rolling offline alarm schedule.
See [REMINDERS.md](REMINDERS.md) for setup, catch-up and DST behavior. GrapheneOS
forwards full-user alerts through the receiving profile's **Other users** system
channel; that channel controls forwarded sound/volume rather than retaining the
source app's chosen tone. Test that channel while the source profile is running.
The Android Calendar launcher widget now has a busy-time strip in addition to
its month grid; [CALENDAR.md](CALENDAR.md) covers sizing and privacy.
