# Architecture

0.10 item context separates the parent list's purpose/broad area from optional
`task.item_category`. `shared/item-contexts.json` is the canonical vocabulary;
`tools/sync-item-contexts.py` generates web/native data, checked by all build
entry points. Rust includes the same JSON in its binary. `ItemContext.kt` and
the server/web policies treat custom and untagged lists as explicit boundaries.
Typed lists constrain newly selected labels; unchanged saved labels, moves,
imports and restores preserve existing content. Text bounds still apply to
unrestricted labels. The server checks the current parent inside the write
transaction. Shared inheritance cases cover every broad area and legacy aliases.

Reminder timing adds optional `lead_time` to an alert and a versioned writer
signal to its reminder set. Small elapsed leads keep the original minute wire
shape. Months/years retain amount/unit/zone rather than an approximate duration.
`ReminderTiming.kt` inverts each lead around the eight-day delivery horizon,
with bounded margin for calendar clamping, so a ten-year lead does not enumerate
ten years of recurrence. Extended rules include occurrence anchors in receipt
and progress identities to distinguish clamped reminders at the same instant;
legacy receipt keys are unchanged.

Canonical rules remain encrypted in the store and in content exports. Legacy
notification fields omit extended alerts and format markers; current clients
read `reminder_timing_rules` and apply the existing recipient policy. A separate
write guard rejects an older editor's attempt to replace extended rules. The
writer signal is excluded from content comparison/history, while actual timing
changes, restore previews and imports retain their full meaning. New commands
are feature-gated in the phone outbox when connected to an older server.

Foreground freshness and list removal (0.9.1): `ForegroundSync.kt` owns a
three-second head check inside Android's resumed lifecycle, with an immediate
check on return. It waits for overlapping connection work and honors durable
Pause independently of older Auto refresh preferences. `DefaultNetworkMonitor`
invalidates/reopens the userspace transport with the existing identity; no OS
routes or VPN slot are changed. With all background modes off, a background
network change invalidates it without traffic. Freshness/pending timestamps are
ephemeral; head polling does not rewrite the encrypted cache or regenerate widgets.

Individual removal uses the existing revision-checked `task.delete` command.
The phone commits its outbox entry and draft removal together before closing the
editor; workspace-owned sync survives editor cancellation. Web retries preserve
the same operation ID. Both retain the confirmation's expected item revision and
workspace, so concurrent amendments need review. Web redraws preserve viewport
and keyed nested scroll positions; asynchronous management renders are assembled
before replacing visible content.

Field Notes is one household workspace: a Rust server on Linux, a bundled LAN
web interface, and a Kotlin Multiplatform Android client. The server can decrypt
content. iOS and native desktop packaging are deferred; Linux and Windows use
the LAN website. See [VALIDATION.md](VALIDATION.md) for executed checks.

## Household history boundary

`store/history.rs` records encrypted, bounded field differences alongside the
existing authoritative event sequence. A content command, its cascaded removals,
receipt and history commit in one transaction. Read-only `/api/history` checks
the active session, epoch/generation, bounded filters and sequence cursor. It is
a shared-content route on both listeners; administrative audit payloads remain
behind the existing LAN management boundary. History cannot be imported as an
authoritative event stream. Clear removes prior content details; backup/restore
preserves the encrypted table with the rest of the store.

`apps/admin/history.js` owns its view lifecycle, request fences, filters and
expanded differences without persisting content in browser storage. Shared
`domain/History.kt` defines the wire/cache types, local formatting and cache
scope; `ui/History.kt` owns the Compose timeline. Android uses the existing
restricted transport, encrypted vault and sync/account fences. Optional history
cache entries cannot displace pending commands or drafts.

## Boundaries and source map

| Location | Responsibility |
| --- | --- |
| `apps/server/src/main.rs`, `tls.rs` | Configuration, two direct TLS 1.3 listeners, certificate reload, peer reconciliation timer |
| `apps/server/src/http.rs` | Separate management/device routes, actual-source ACL, CSRF, bounded requests |
| `apps/server/src/store/` | Auth/roles, transactional commands, typed organization/planning/assignment, device enrollment, administration, backup |
| `apps/server/src/downloads.rs` | Fixed LAN-only APK routes, startup digest validation, bounded streaming from a read-only program mount |
| `apps/mobile/src/androidMain/.../ApkUpdates.kt`, `UpdateTransfer.kt`, `ApkUpdateVerifier.kt` | Server-selected updates, resumable bounded chunks, independent cryptographic/signing/identity checks, Android installer handoff |
| `scripts/upgrade-server.py` | Compatible Linux upgrades, identity preservation, encrypted backup, staged programs/images, readiness and rollback |
| `scripts/android_artifact.py`, `publish-android.py`, `.github/workflows/` | Matching signed APK packaging and draft-first tagged release delivery |
| `apps/server/src/crypto.rs` | AES-GCM storage encryption, Argon2id passwords, random capabilities |
| `apps/admin/` | Local assets; compact planner, typed collections/notes, calendar, management, in-tab revision-aware drafts |
| `apps/mobile/src/commonMain/.../domain/` | Protocol, encrypted-cache model, durable outbox/drafts, enrollment state, typed organization/household directory, ranking/calendar/widget policy |
| `apps/mobile/src/commonMain/.../ui/` | Compose planner/task editor; separate organization, calendar/date/event and theme components |
| `apps/mobile/src/commonMain/.../domain/Scheduling.kt` | Local wall-clock validation, DST round trips and calendar display labels |
| `apps/mobile/src/commonMain/.../domain/ReminderOptions.kt`, `ReminderNotice.kt`, `Compatibility.kt` | Recipient/playback extensions, detailed notice composition and feature/protocol bounds |
| `apps/mobile/src/androidMain/.../CalendarWidget.kt` | Launcher month grid, private previews, coalesced background rendering, date navigation and owned midnight refresh |
| `apps/mobile/src/androidMain/.../WeekCalendarWidget.kt` | Real seven-day launcher agenda and occupied-time bars, sharing the calendar lifecycle |
| `apps/mobile/src/androidMain/.../AlarmPlaybackService.kt`, `AlarmAudio.kt` | Bounded local playback, visible Stop, durable temporary-volume restoration and recovery |
| `apps/mobile/src/androidMain/` | Keystore vault, credential lock, offline camera scanner, widget, scheduler, manifests/resources |
| `apps/mobile/src/jvmMain/` | JNI adapter and bounded portable cipher/ZIP reader shared by Android and host checks |
| `native/transport/`, `native/bridge/`, `native/android/` | Userspace WireGuard/HTTPS, key-bound tunnel enrollment, C ABI, JNI byte bridge |
| `scripts/peer-agent.py` | Constrained root-side desired-peer reconciliation and acknowledgement |
| `scripts/tls.py` | Host-only local CA/leaf issuance and renewal |
| `scripts/container_deployment.py`, `container-runtime.py`, `deploy/containers/` | Immutable offline images, isolated shared network namespace, egress denial, paired lifecycle |
| `scripts/server_installation.py`, `uninstall-server.py` | Root-owned installation inventory, scoped preview/removal/purge and legacy cleanup |
| `scripts/install-server.py`, `deploy/` | Reviewed fresh-host installation, systemd services/timers, isolated WireGuard and nftables |
| `tests/fixtures/` | Shared ranking cases and a public test CA; no private fixture keys |

Domain code does not import a UI or transport implementation. UI submits commands;
only the server store grants ownership and commits changes. Platform effects sit
behind Transport/Vault interfaces. Server auth, planning and enrollment are
separate store modules so new features need not bypass command authorization.
Keep one application process and one SQLite WAL database. Add modules when there
is an actual boundary; no broker, load balancer, reverse proxy, or empty package
hierarchy is required.

## Network and enrollment

Rust serves HTTPS itself inside the app container, published on the explicitly chosen management LAN port and bound to the dedicated
WireGuard server address (8443). There is no Caddy or forwarded-header trust.
Only one encrypted UDP port is forwarded by the router. The nonroot content
service has no capabilities. The separate gateway holds NET_ADMIN, the hub key
and namespace-local nftables; it denies new outbound connections, DNS and all
forwarding. Only the app's data mount is writable. CA signing keys and the Docker
socket remain on the host. The root host supervisor restarts the pair together
and discards old peer acknowledgements after a namespace replacement. A root timer applies only
validated peer keys, unique PSKs and /32 addresses to `fieldnotes`; it accepts no
commands, route tables or interface names from phone requests.

See [CONTAINERS.md](CONTAINERS.md) for mounts, trust boundaries, offline image
packaging and host requirements. [UNINSTALL.md](UNINSTALL.md) defines scoped
cleanup, retained recovery data and explicit purge. There is no automatic
migration from a native installation and no shared Docker daemon reconfiguration.

A ten-minute v2 invitation is bound to the phone public key and an existing
account/role. The PC administrator pastes that key before issuance. The server
reserves a PSK and /32 and publishes a restricted preapproval peer. The phone
claims/status-polls only over WireGuard and pinned TLS to the private task API;
it never contacts management. These three routes require actual-source/claim
proof, grant no content session and reject browser credentials. Administrator
phone-code approval, fresh matching host acknowledgement and activation are
required before content access. Expired/revoked peers retain their address until
root removal acknowledgement. Never-activated reservations may then be replaced;
offline active phones are preserved. Older LAN bootstrap routes remain only for
legacy recovery and are not used for new invitations. Native sessions are bound
to that device/account; revocation denies content immediately.

See [NETWORK.md](NETWORK.md) and [ENROLLMENT.md](ENROLLMENT.md) for constraints,
expiry/retry/recovery, and the trust bootstrap.

## Planning and sync

Tasks can exist in Inbox without a parent, directly under a project or under a
list. Lists can belong to a project, and `items_are_tasks` distinguishes work
lists from reference checklists (legacy lists remain reference). `projects.rs`
validates the resulting hierarchy and project lifecycle inside command, import
and recovery transactions. Finished projects cannot contain unfinished work;
on-hold projects cannot hold active claims. No parent action silently completes,
reassigns or redates children. JS/Kotlin project graphs derive progress from
records, with shared golden fixtures; no mutable progress counter is stored.
Category is independent of hierarchy and copied only as a new-record default.
An additive `history_projects` table captures before/after project scopes for
moves; its foreign key follows history-detail retention. Collection deletion is
refused while child tasks or linked lists remain. See [PROJECTS.md](PROJECTS.md).
Standalone Notes have title, body and
pinning; they never become tasks to appear in a workspace. The browser sidebar
and Android drawer expose these plus Unscheduled, Waiting and Completed views.
Assignment responsibility uses the same task revision; a minimal authenticated
people directory is separate from administrative account data. Its revision
participates in head polling, and the Android cache retains names for offline
work. Active handoffs cannot bypass a working claim. Collection deletion is refused while any tasks remain; task movement and note
edits use the same revision and workspace fences as other commands.

Due dates are optional. Priority, planned day, estimate, pin and blocked state are
separate fields. Immutable server creation time supplies a bounded waiting-time
bonus. The same versioned ranking formula runs in Kotlin and JavaScript, checked
against shared golden cases. Ranking suggests next steps; it cannot grant a claim.

Task due/planned dates and all-day events are floating civil dates. All-day end
is exclusive; timed events store UTC instants and render in the device's time
zone. Human local date/time controls convert to instants, reject spring gaps and
preserve an unchanged repeated-time instant. New repeated times choose the first
occurrence. End time is nullable; start-only events do not contribute busy time.
No Google or OS calendar provider is involved.

Lists carry a versioned-by-record field selection and template; task children
retain independent IDs/revisions. Hiding a field preserves its data. Bulk checked
removal captures exact revisions; it cannot delete a concurrently reopened item.
The server owns creator/update/activity identity and timestamps. Comment/image
transactions update parent activity without bumping its content revision. Current
clients fetch full snapshots after a changed head, so this metadata is visible;
future delta clients must explicitly propagate parent activity too.

Reminder rules are ordinary encrypted record data validated by a separate store
module. Platform-independent code calculates local occurrences using saved IANA
zones; Android effects create channels and maintain a bounded AlarmManager window
with purpose-separated encrypted receipts/high-water progress. Repeat windows
never reserve calendar time. Native sound assets are original generated PCM.
Retailer links are data only: the server has no preview fetcher and clients use
attached images exclusively. See [lists](LISTS.md) and [reminders](REMINDERS.md).

The server resolves competing task starts transactionally using revisions and
server commit order. Client timestamps cannot steal a claim. Immutable operation
IDs survive retries and process death. Workspace epoch/generation fences reject
old writes after reset/restore. Local rejected/dependent edits remain available
for review. See [SYNC.md](SYNC.md).

## Notifications and widget

Default-on background mode uses a user-visible special-use foreground service,
a bounded wake lock, a 15-second authenticated head check and offline backoff.
It shares the app-contained WireGuard transport, never VpnService. WorkManager
catch-up remains a scheduling-constrained fallback. Cached AlarmManager alerts
are independent of network initialization; exact access is optional and Android
Doze/DND/profile controls remain authoritative. Encrypted effect receipts dedupe
occurrences; simultaneous due reminders produce one digest. Device sessions
renew only after valid, active, source-bound authorization. The private-only
self-revocation route kills that installation's sessions before peer removal.

Focus, Upcoming, month Calendar and seven-day Week AppWidgetProviders read the
encrypted local cache. Upcoming orders dated/planned tasks and events; Focus
uses the shared score. Month/Week show occupied time and date links. Titles
default on while unlocked, retaining existing explicit preferences. Separate
lock-screen switches and optional app credential locking are independent;
cached copies cannot be guaranteed erased on lock. No notes/images/credentials
enter RemoteViews. A standard requestPinAppWidget flow offers installation.
See [Android delivery](ANDROID-DELIVERY.md) for profile forwarding, alarms,
battery behavior and the uninstall boundary.

Recipient/playback extensions live in record `reminder_options` beside the
strict v1 `reminders` object. Server responses project empty alert rules for
nonrecipients while exposing the original as read-only `reminder_rules` to
current editors. Stored rules remain canonical. Commands and replays use the
same projection, and old editors cannot blindly overwrite advanced schedules.
Cached scheduling and household-change notifications filter by the recipient
policy. This is an alert subscription, not a content ACL.

Long alarm audio runs in a bounded media-playback foreground service. An
encrypted volume lease is durable before any explicit per-phone/per-rule
override, and restoration respects a changed manual volume. Recovery is local
and does not wait for networking. DND is never changed.

Head/update metadata advertises protocol/client bounds and feature support.
Additive snapshot fields are tolerated; command/enrollment schemas stay strict.
Incompatible sync retains edits and surfaces a persistent problem. Verified APK
chunks checkpoint independently of the planner vault and resume after restart;
whole-file hash, installed signer and Android consent remain required.

Future iOS work can share Kotlin domain/UI, but needs a native transport bridge,
Keychain, background policy and widget implementation. An in-process VPN does
not prevent iOS suspension or replace APNs wake-up capabilities.

## Household planning and portable content

`Recurrence.kt` and `apps/admin/recurrence.js` expand one stored event series
inside a bounded display/reminder range, in its stored IANA wall-clock zone.
Server validation and old-editor fences protect series schedules. List membership
filters keep checklist entries outside standalone task views; category/store
metadata is separate from priority, responsibility and working claims.

`SharedActivity.kt` uses server-created sequence numbers, not phone time.
Android persists an encrypted delivery intent before posting, reconciles mute
choices and acknowledges its cursor afterward. Initial history and own writes
are not replayed as new shared additions.
After a successful local write, editors hand delivery to workspace-owned
`requestSync`, rather than awaiting network work in their disposable Compose
scope. That request waits for an overlapping poll/sync to release the connection
mutex. Closing the editor cannot cancel it or drop the kick during another poll;
offline/error results retain the queue and visible connection status.

Portable content uses an explicit capability-free manifest and framed AES-GCM.
The web controller is `archive.js`; shared JVM cipher/ZIP parsing isolates format
logic from Android document-picker/vault operations in `PortableFiles.kt`.
Server `transfer.rs` handles previews, encrypted chunks, identity/content maps and
atomic commit; `transfer/recovery.rs` handles explicit, idempotent recovery.
[Format and transaction details](PORTABLE-FORMAT.md) and [user workflow](CONTENT-FILES.md).

## Content recovery boundaries

`store/recovery` owns encrypted full states, reference-counted retained images,
monotonic per-item indices, frozen restore plans/receipts and retention. The
`snapshots`, `restore` and `retention` modules keep capture, mutation and cleanup
separate while sharing the canonical SQLite transaction. `/api/recovery` is a
bounded authenticated content API on both listeners; retention configuration and
purging stay behind LAN-only admin authorization/reauthentication. Accounts,
network configuration and device identity are outside content checkpoints.

Native `domain/Recovery.kt` defines the version/preview contract; Workspace
serializes actions with sync/account changes and durably retains uncertain
results. Its UI is separate from the immutable history trail. Web `recovery.js`
uses the same explicit preview/apply protocol without storing content in browser
storage. Exportable content excludes live restore-write fences.

`backup.rs` owns the independently versioned full backup format and staged
restore publication. New files authenticate fixed bounded frames, including a
random stream identifier, total length and frame index in each frame's AAD.
SQLite snapshots and encrypted downloads use private temporary files, not a full
in-memory database serialization. Old single-record archives remain readable.
The server's schema/protocol versions are independent of this file format.
