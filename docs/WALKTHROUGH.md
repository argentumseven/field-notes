# Planner and management walkthrough

These screenshots contain only a synthetic household. Browser captures use the
real Rust server/database. Android captures render actual Compose/RemoteViews
with a test-only connection and encrypted local cache. Image 11 compares the
RemoteViews renderer in a test activity; image 22 is a widget actually bound to
the AOSP home-screen launcher through Android's normal pin confirmation.
Screenshots do not prove live enrollment, home firewall isolation, camera optics,
GrapheneOS VPN coexistence or background timing. The shipped app keeps screen
capture protection enabled. Test invitations expire and their fixture is deleted.

## Workspace, lists and shared notes

Capture unfiled tasks in Inbox. Lists keep groceries, routines and checklists
separate from outcome-oriented Projects. Collections brings both together, and
Notes holds shared reference material independently of tasks. Unscheduled,
Waiting and Completed are useful views of the same task data. Keep in moves a
task between Inbox, a List and a Project with revision checks.

![Inbox accepts tasks without a project](screenshots/web/20-inbox.png)
![Lists and Projects in Collections](screenshots/web/21-collections.png)
![Standalone shared Notes](screenshots/web/22-notes.png)
![Shared note editor](screenshots/web/23-shared-note.png)
![Groceries as a List](screenshots/web/24-grocery-list.png)

## Tasks, assignments and handoffs

Tasks includes all work. Assignments groups open work by responsible person;
My tasks and Unassigned are direct filters. The Task view selector keeps these
available in a small browser viewport as well as the sidebar. Management and
Change theme now share the navigation text baseline; on desktop their footer
stays visible while the workspace destinations scroll.

A task can be assigned before anyone starts it. A working claim is exclusive;
release it before handing off responsibility. Inactive people/viewers cannot
receive new work. Existing assignments keep their identity and conflicts retain
edits. Focus, the widget and due reminders respect the responsible person.

![Household assignment overview](screenshots/web/25-assignments.png)
![My tasks](screenshots/web/26-my-tasks.png)
![Unassigned work](screenshots/web/27-unassigned.png)
![Capture a task and select a responsible person](screenshots/web/28-new-assigned-task.png)

## Focus, priorities and optional dates

Focus is a suggested order, with pins first, bounded scores and stable ties.
Undated high-priority work can rank above dated routine work. Open a task to see
its score calculation. Planned day and due date are separate, optional fields;
clearing either does not clear priority. Blocked, completed, other-claimed and
future-planned work stay in All tasks (your already-started task remains in Focus).

![Charcoal Focus with undated priorities and visible scores](screenshots/web/02-focus.png)
![Paper theme](screenshots/web/03-focus-paper.png)
![All task rows](screenshots/web/04-task-list.png)
![Status board](screenshots/web/05-board.png)
![Task editor: priority, clearable dates, estimates, pins, blocked state and score](screenshots/web/06-task-planning.png)
![Notes, images and conversation](screenshots/web/07-notes-images-comments.png)

## Calendar

Task deadlines/planned days and dedicated events share a workspace calendar. Undated
tasks remain available in Focus and All tasks. All-day events use floating dates
and an exclusive end day. Timed events use explicit offsets, store UTC instants,
and render in your current time zone without a Google/OS calendar dependency.
Events sync through your Field Notes server alongside tasks. External calendar
sync (including CalDAV), import and subscription are not implemented.

![Month and selected-day agenda](screenshots/web/08-calendar.png)
![Create a calendar event](screenshots/web/09-calendar-event.png)

## Enrollment tied to tunnel provisioning

From Management, select an existing account and create its ten-minute invitation.
On the phone, scan on trusted home Wi-Fi, review the account/server and confirm
the trusted source. The phone keeps its private key. Enter its displayed code in
**Approve** on the server. **Provisioning** means the host has not acknowledged
peer installation; it is not activated access. Once acknowledged, the phone
activates through its assigned WireGuard address and synchronizes automatically.
See [ENROLLMENT.md](ENROLLMENT.md) for retries, expiry, recovery and revocation.

![Management, people, sessions, devices and maintenance](screenshots/web/10-management.png)
![Add an account with a role](screenshots/web/17-add-person.png)
![Change a role and revoke its sessions](screenshots/web/18-change-role.png)
![Single-use invitation for an existing account](screenshots/web/11-enrollment-invitation.png)
![Pending phone](screenshots/web/12-device-pending.png)
![Enter the code displayed on the actual phone](screenshots/web/13-device-confirmation.png)
![Waiting for root peer acknowledgement](screenshots/web/14-provisioning.png)
![Explicit workspace clearing](screenshots/web/15-clear-workspace.png)
![Audited claim override with a reason](screenshots/web/19-override-claim.png)

## Android

| Assignments | My tasks | Capture with assignment |
| --- | --- | --- |
| <img src="screenshots/android/18-assignments.png" width="250" alt="Android household responsibilities"> | <img src="screenshots/android/19-my-tasks.png" width="250" alt="Personal responsibility and active work"> | <img src="screenshots/android/21-new-task.png" width="250" alt="Task title, assignee and collection are independent"> |

<img src="screenshots/android/20-unassigned.png" width="300" alt="Android unassigned work">

The installed page glyph in the actual AOSP launcher:

<img src="screenshots/android/17-launcher-icon.png" width="300" alt="Current Field Notes icon in the actual Android launcher">

| Workspace navigation | Notes | Note editor |
| --- | --- | --- |
| <img src="screenshots/android/12-workspace-navigation.png" width="250" alt="Android drawer with views and collection types"> | <img src="screenshots/android/13-notes.png" width="250" alt="Shared standalone notes"> | <img src="screenshots/android/14-shared-note.png" width="250" alt="Shared note editor"> |

| Inbox | Collections |
| --- | --- |
| <img src="screenshots/android/15-inbox.png" width="250" alt="Tasks without a project"> | <img src="screenshots/android/16-collections.png" width="250" alt="Lists and Projects"> |

The Android app has Focus / Tasks / Calendar navigation, clearable date pickers,
priority chips, planning fields, notes/comments/images and a private home widget.
The first screen supports QR scanning and paste; no public key copying or manual
peer JSON editing is needed in the normal enrollment flow.

| Enroll on home Wi-Fi | Confirm the phone code |
| --- | --- |
| <img src="screenshots/android/01-enroll.png" width="280" alt="Android enrollment with scan and paste"> | <img src="screenshots/android/02-phone-confirmation.png" width="280" alt="Phone verification code awaiting administrator approval"> |

| Focus | All tasks | Calendar |
| --- | --- | --- |
| <img src="screenshots/android/03-focus.png" width="250" alt="Compact Android Focus"> | <img src="screenshots/android/04-tasks.png" width="250" alt="Android task rows"> | <img src="screenshots/android/05-calendar.png" width="250" alt="Android local calendar"> |

| Calendar event | Task planning | Conversation |
| --- | --- | --- |
| <img src="screenshots/android/06-new-event.png" width="250" alt="Android event editor"> | <img src="screenshots/android/07-task-planning.png" width="250" alt="Task notes and optional planning fields"> | <img src="screenshots/android/08-comments-attachments.png" width="250" alt="Task attachments and comments"> |

| Widget privacy settings | Actual RemoteViews renderer | Paper theme |
| --- | --- | --- |
| <img src="screenshots/android/09-settings-widgets.png" width="250" alt="Settings including widget title opt-in"> | <img src="screenshots/android/11-widget.png" width="250" alt="Private default and title-enabled widget rendering"> | <img src="screenshots/android/10-focus-paper.png" width="250" alt="Android paper theme"> |

| Actual home-screen Upcoming widget | Android notification shade |
| --- | --- |
| <img src="screenshots/android/22-home-upcoming-widget.png" width="280" alt="Upcoming widget hosted on the actual AOSP home screen"> | <img src="screenshots/android/23-android-notifications.png" width="280" alt="Actual Android reminder and foreground connection status"> |

| Background delivery settings | Full-profile guidance |
| --- | --- |
| <img src="screenshots/android/24-background-settings.png" width="280" alt="Background delivery, permissions and test controls"> | <img src="screenshots/android/25-profile-help.png" width="280" alt="GrapheneOS forwarding and full user profile limitations"> |

Upcoming shows dates and internal calendar events; Focus shows ranked work.
Widgets hide titles by default. Opting in shares title copies with the launcher,
which can retain them. Notes/images/credentials are never included. Tap opens
the credential-locked app; the Upcoming footer requests a refresh. The screenshots
opt into synthetic titles. The documentation fixture disables live mode so its
fake server identity never starts a real network connection; new installations
default it on after enrollment and notification permission.

The notification capture uses actual Android alarms, receivers, foreground
service and channels with a synthetic encrypted calendar cache. It proves
posting and channel configuration, not physical sound or GrapheneOS forwarding.
Follow [profile/sound setup and clean uninstall](ANDROID-DELIVERY.md) on the real
phones. No unconditional timing or cross-profile home-screen sharing is claimed.

## Browser on a small screen

The responsive LAN website also works in a phone browser. A browser cannot borrow
the Android app-contained tunnel; the website remains a home-LAN interface.

<img src="screenshots/web/16-phone-browser.png" width="390" alt="Responsive LAN planner">

## Reproduce the captures

```sh
source scripts/dev-env.sh
cargo build --locked
node scripts/capture-web.mjs
bash scripts/build/android-emulator.sh
# Only on the disposable field-notes-test AVD:
node scripts/capture-android.mjs
node scripts/capture-home-widget.mjs
# Refresh only settings/help/renderer after a settings change:
node scripts/capture-android.mjs --details-only
```

The gallery fixture and capture permission exist only in the instrumentation APK.
The emulator capture script refuses physical phones or another AVD and clears
only the dedicated disposable application's state. Unit/API/native integration
checks are separate; see [VALIDATION.md](VALIDATION.md).
