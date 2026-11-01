# Architecture

Field Notes is one household workspace: a Rust server on Linux, a bundled LAN
web interface, and a Kotlin Multiplatform Android client. The server can decrypt
content. iOS and native desktop packaging are deferred; Linux and Windows use
the LAN website. See [VALIDATION.md](VALIDATION.md) for executed checks.

## Boundaries and source map

| Location | Responsibility |
| --- | --- |
| `apps/server/src/main.rs`, `tls.rs` | Configuration, two direct TLS 1.3 listeners, certificate reload, peer reconciliation timer |
| `apps/server/src/http.rs` | Separate management/device routes, actual-source ACL, CSRF, bounded requests |
| `apps/server/src/store/` | Auth/roles, transactional commands, typed organization/planning/assignment, device enrollment, administration, backup |
| `apps/server/src/downloads.rs` | Fixed LAN-only APK routes, startup digest validation, bounded streaming from a read-only program mount |
| `apps/mobile/src/androidMain/.../ApkUpdates.kt` | Server-selected private APK chunks, independent cryptographic/signing/identity checks, scoped cache, Android installer handoff |
| `scripts/upgrade-server.py` | Compatible Linux upgrades, identity preservation, encrypted backup, staged programs/images, readiness and rollback |
| `scripts/android_artifact.py`, `publish-android.py`, `.github/workflows/` | Matching signed APK packaging and draft-first tagged release delivery |
| `apps/server/src/crypto.rs` | AES-GCM storage encryption, Argon2id passwords, random capabilities |
| `apps/admin/` | Local assets; compact planner, typed collections/notes, calendar, management, in-tab revision-aware drafts |
| `apps/mobile/src/commonMain/.../domain/` | Protocol, encrypted-cache model, durable outbox/drafts, enrollment state, typed organization/household directory, ranking/calendar/widget policy |
| `apps/mobile/src/commonMain/.../ui/` | Compose planner/task editor; separate organization, calendar/date/event and theme components |
| `apps/mobile/src/androidMain/` | Keystore vault, credential lock, offline camera scanner, widget, scheduler, manifests/resources |
| `apps/mobile/src/jvmMain/` | JNI adapter shared by Android and actual JVM integration checks |
| `native/transport/`, `native/bridge/`, `native/android/` | Userspace WireGuard/HTTPS, scoped LAN bootstrap, C ABI, JNI byte bridge |
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

Rust serves HTTPS itself inside the app container, published on the management LAN (default 9443) and bound to the dedicated
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

A LAN-only, ten-minute QR invitation is scoped to an existing account and role.
The phone generates its own private key and persists a claim nonce before
sending its public key. The administrator enters the code displayed by that
phone. Approval creates a desired peer; only a fresh, matching root acknowledgement
releases the connection configuration. Activation travels through WireGuard and
must originate from the assigned inner address. Native sessions are bound to
that device and account. Revocation denies application access immediately; the
website keeps removal pending until the host acknowledges peer removal.

See [NETWORK.md](NETWORK.md) and [ENROLLMENT.md](ENROLLMENT.md) for constraints,
expiry/retry/recovery, and the trust bootstrap.

## Planning and sync

Tasks can exist in Inbox without a parent. Lists and Projects are separate typed
collections, with editable names/context. Standalone Notes have title, body and
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
zone. ISO times require an explicit offset during entry, so ambiguous DST times
are not guessed. No Google or OS calendar provider is involved.

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

Separate Focus and Upcoming AppWidgetProviders read the encrypted local cache.
Upcoming orders dated/planned tasks and events; Focus uses the shared score.
Titles are hidden by default. Opt-in shares title copies with the launcher;
cached copies cannot be guaranteed erased on lock. No notes/images/credentials
enter RemoteViews. A standard requestPinAppWidget flow offers installation.
See [Android delivery](ANDROID-DELIVERY.md) for profile forwarding, alarms,
battery behavior and the uninstall boundary.

Future iOS work can share Kotlin domain/UI, but needs a native transport bridge,
Keychain, background policy and widget implementation. An in-process VPN does
not prevent iOS suspension or replace APNs wake-up capabilities.
