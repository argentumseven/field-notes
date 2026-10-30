# Field Notes: screenshots and walkthrough

These are captures of the running Android app and Rust-backed web interface,
using a disposable household with synthetic names, tasks, comments, and a shelf
sketch. They show the implemented interface. Click a picture to see it full size.
Android captures are from an AOSP Android 15 emulator with a test-only fixture
connection and the real encrypted cache, not a physical GrapheneOS phone or a
live home tunnel. The fixture loads an authorized snapshot and image from the
disposable Rust server. The responsive phone browser is shown separately from
the native app.

[Android setup](#connect-a-phone) · [Daily use](#use-the-android-app) ·
[Sync and conflicts](#connection-offline-work-and-conflicts) ·
[Computer interface](#use-it-on-a-computer) ·
[Server management](#manage-the-server) · [Reproduce](#reproduce-the-captures)

## Connect a phone

Approve the phone's public key on the home server, paste the connection JSON
obtained through your trusted LAN administrator, confirm that source, and sign
in. Peer approval currently uses the server's command-line helper; the web UI
does not yet enroll WireGuard peers or issue QR invitations. Private keys stay
on the phone. See [the pairing commands](RUNNING.md#android-builds-and-pairing).

| 1. Pair the phone | 2. Sign in to the household |
| --- | --- |
| <a href="screenshots/android/01-pairing.png"><img src="screenshots/android/01-pairing.png" width="300" alt="Native Android pairing screen with device public key, connection JSON and explicit trust checkbox"></a> | <a href="screenshots/android/02-sign-in.png"><img src="screenshots/android/02-sign-in.png" width="300" alt="Native Android household sign-in screen"></a> |

## Use the Android app

Matrix groups tasks into Do, Decide, Delegate, and Defer. Board groups the same
tasks by To do, In progress, and Done. List is the compact alternative. Search
narrows titles and notes; the project button filters to one project. A start
remains provisional until the server accepts it; simultaneous claims are decided
by the first valid server transaction, not a phone's clock.

| Matrix | More collections |
| --- | --- |
| <a href="screenshots/android/03-matrix.png"><img src="screenshots/android/03-matrix.png" width="300" alt="Android Matrix task view"></a> | <a href="screenshots/android/04-matrix-collections.png"><img src="screenshots/android/04-matrix-collections.png" width="300" alt="Android Matrix scrolled to later collections"></a> |

| Board | Claims and completed tasks |
| --- | --- |
| <a href="screenshots/android/05-board.png"><img src="screenshots/android/05-board.png" width="300" alt="Android Board view showing tasks by status"></a> | <a href="screenshots/android/06-board-claims.png"><img src="screenshots/android/06-board-claims.png" width="300" alt="Android Board showing In progress and Done sections"></a> |

| List | Light appearance |
| --- | --- |
| <a href="screenshots/android/07-list.png"><img src="screenshots/android/07-list.png" width="300" alt="Android List task view"></a> | <a href="screenshots/android/16-matrix-light.png"><img src="screenshots/android/16-matrix-light.png" width="300" alt="The Android Matrix in the system light appearance"></a> |

Open a task to edit its title, notes, due date, and collection. **Save details**
queues the change. Below the fields are the start/release/complete controls,
image attachments, and conversation. Images use the system photo picker.

| Task notes and due date | Attached image |
| --- | --- |
| <a href="screenshots/android/08-task-notes.png"><img src="screenshots/android/08-task-notes.png" width="300" alt="Task title, notes and due date in the Android detail editor"></a> | <a href="screenshots/android/09-task-image.png"><img src="screenshots/android/09-task-image.png" width="300" alt="An actual shelf sketch attachment displayed in Android"></a> |

| Conversation | Delete confirmation |
| --- | --- |
| <a href="screenshots/android/10-task-conversation.png"><img src="screenshots/android/10-task-conversation.png" width="300" alt="Comments and comment composer on an Android task"></a> | <a href="screenshots/android/11-delete-confirmation.png"><img src="screenshots/android/11-delete-confirmation.png" width="300" alt="Android requires typing DELETE before confirming task deletion"></a> |

Use **Add task** for a quick title within the current project. **Workspace menu →
New project** creates another project. Task deletion requires typing `DELETE`;
the screenshot shows the confirmation without performing the deletion.

<a href="screenshots/android/12-new-task.png"><img src="screenshots/android/12-new-task.png" width="300" alt="Android quick-add task dialog"></a>

## Connection, offline work, and conflicts

**Workspace menu → Notifications & connection** controls optional background
catch-up and local due-date reminders. Background work is best-effort, at least
15-minute scheduling; this is not instant push. Editing the endpoint changes the
route while retaining the same server identity, certificate trust, and pending
work. This is useful when the home router lacks hairpin NAT.

| Background settings | Change the endpoint |
| --- | --- |
| <a href="screenshots/android/13-notifications-connection.png"><img src="screenshots/android/13-notifications-connection.png" width="300" alt="Android opt-in background catch-up and local reminder settings"></a> | <a href="screenshots/android/14-server-endpoint.png"><img src="screenshots/android/14-server-endpoint.png" width="300" alt="Android server endpoint editor"></a> |

Queued edits are kept in the encrypted phone cache. A disconnected phone marks
them **Pending confirmation**. A rejected stale write appears under **Retained
changes**, with copy, reapply, and discard actions. Review the current server
version before reapplying. Discarding a retained change requires confirmation.

The conflict fixture replays the rejection returned by a deliberately stale
command sent to the disposable Rust server. For the offline capture, it pauses
the test connection and queues a task in the real encrypted outbox. The simulated
connection resumes after the picture is taken. These pictures demonstrate the
interface and do not establish delivery or tunnel behavior.

| Retained stale edit | Queued while disconnected |
| --- | --- |
| <a href="screenshots/android/15-retained-conflict.png"><img src="screenshots/android/15-retained-conflict.png" width="300" alt="Android retained-change interface with a stale-edit fixture and recovery actions"></a> | <a href="screenshots/android/17-offline-queue.png"><img src="screenshots/android/17-offline-queue.png" width="300" alt="Android offline fixture with a pending task in the encrypted outbox"></a> |

## Use it on a computer

Open the server's LAN HTTPS address in a Linux or Windows browser and sign in.
The project sidebar, Matrix/Board/List switch, search, and quick-add field work
on the same server data. These are browser screenshots; a native desktop package
is not part of the current delivery.

### Matrix

![Web Matrix with project navigation and tasks in four collections](screenshots/web/02-matrix.png)

### Board

![Web Board showing To do, In progress and Done](screenshots/web/03-board.png)

### List and light appearance

![Web List in the light appearance](screenshots/web/04-list-light.png)

### Task editor

![Web task editor with notes, due date, collection and task actions](screenshots/web/05-task-details.png)

### Image attachments and conversation

![Web task attachment and household conversation](screenshots/web/06-images-comments.png)

### New project

![Web project creation dialog](screenshots/web/07-new-project.png)

<details>
<summary>Browser sign-in and responsive phone layout</summary>

![Browser sign-in](screenshots/web/01-sign-in.png)

This last image is the website in a narrow phone browser, distinct from the
native Android screenshots above. The website remains available only through
the trusted management LAN or a separately configured management route.

<a href="screenshots/web/14-phone-browser.png"><img src="screenshots/web/14-phone-browser.png" width="390" alt="Responsive website displayed at phone width"></a>

</details>

## Manage the server

Owners and administrators use **Administration** on the trusted LAN. Members and
viewers cannot open these admin routes. Household-wide roles are implemented;
per-project permissions remain future work.

The overview contains **People**, **Devices & sessions**, **Recovery &
maintenance**, server version/workspace counters, and **Administrative activity**.
Use **Revoke** to invalidate an application session. WireGuard peer revocation
is a separate host-side operation using the peer helper.

![Server management overview with people, sessions, backups, server status and audit history](screenshots/web/08-administration.png)

### Confirm administrator identity

Administrative changes ask for your password again and authorize changes for
five minutes. The capture leaves the password blank.

![Administrator reauthentication prompt](screenshots/web/09-confirm-identity.png)

### Add a person and assign a role

Owners can add a member, viewer, or administrator. Share the initial password
securely. Viewers can read content but cannot mutate it. Administrators can
manage content and sessions; owners additionally control ownership and clearing.

![Add household member dialog with name, initial password and role](screenshots/web/10-add-person.png)

### Change a role

Changing a person's role invalidates that person's existing sessions. Disabling
a person also revokes their sessions and retains their existing task content.
The screenshot shows a proposed role change; it is canceled for the gallery.

![Owner changing Sam's role](screenshots/web/11-change-role.png)

### Release a claim with an audit reason

Open the claimed task, choose **Override claim**, reauthenticate, and explain
why. The server records the action in the administrative audit history. The
gallery stops at the reason prompt and leaves Sam's claim intact.

![Audited administrative task claim override dialog](screenshots/web/13-override-claim.png)

### Back up or clear the workspace

**Download encrypted backup** is on the overview. Keep the server recovery key
separately; downloading the backup does not download that key. Restoration is a
host-side procedure documented in [RUNNING.md](RUNNING.md), not a web upload or
shell console.

**Clear workspace…** is owner-only. It requires reauthentication and typing
`CLEAR WORKSPACE`. It removes active projects/tasks/comments/images and changes
the workspace generation so old offline writes cannot recreate cleared content.
Users and existing backups remain. The gallery shows the confirmation only.

![Owner-only clear workspace confirmation](screenshots/web/12-clear-workspace.png)

## Reproduce the captures

Web: `source scripts/dev-env.sh && node scripts/capture-web.mjs`. This starts a
temporary real Rust server and Chromium, captures the interface, and removes its
synthetic database afterward. Build the server and install npm dependencies first
using the normal [setup instructions](RUNNING.md#cloud-development).

Android additionally needs an AOSP API-35 x86_64 emulator named
`field-notes-test`, the Android SDK/NDK, and the Go toolchain:

```sh
bash scripts/build/android-emulator.sh
source scripts/dev-env.sh
export ANDROID_AVD_HOME="$FIELD_NOTES_TOOLS/avd"
export ANDROID_EMULATOR_HOME="$FIELD_NOTES_TOOLS/emulator-home"
# If this disposable AVD has not been created yet:
"$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" \
  'emulator' 'system-images;android-35;default;x86_64'
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/19.0/bin/avdmanager" create avd \
  --name field-notes-test --package 'system-images;android-35;default;x86_64'
# Start in another terminal; omit -accel off if hardware acceleration is available.
"$ANDROID_HOME/emulator/emulator" -avd field-notes-test -no-window -no-audio \
  -no-snapshot -gpu swiftshader -feature -Vulkan -accel off -cores 2
# Wait for sys.boot_completed=1, then capture:
node scripts/capture-android.mjs
```

The Android capture command checks the dedicated emulator's identity before
clearing its demo app data. It changes only that AVD's screenshot dimensions and
appearance, renders the real MainActivity/Compose UI with fixture responses and
a disposable Rust server's data, and restores display size/density afterward.
The fixture responses live only in the instrumentation APK. The real Keystore,
encrypted cache, image display, and outbox are used. It never operates on a
physical phone. Rendering can be slow without KVM.

Production screenshot protection remains enabled. An opt-in class in the
**instrumentation test APK only** temporarily permits captures of its synthetic
activity, asserts the normal secure-window flag first, and restores it afterward.
The documentation process does not add a screenshot switch to the shipped app.
The capture test keeps its screen awake, waits for committed frames, and dismisses
only known AOSP System UI/Quickstep crash dialogs; a Field Notes ANR fails capture.

Live tunnel login could not be completed in this software-emulated capture run,
so the gallery uses fixture networking explicitly. Separate transport integration
results and physical-device limits are recorded in [VALIDATION.md](VALIDATION.md).
