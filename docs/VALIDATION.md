# Implementation and validation record

Field Notes **0.2.0**, protocol 1, database schema 2, Android version code 2.
This is a development implementation without an independent security audit.
Server-readable encryption is intentional. Android/Kotlin Multiplatform is the
active native target; iOS and native desktop packages are deferred.

## Executed host, API and build checks

| Check | Result and practical scope |
| --- | --- |
| Kotlin shared-domain tests | 23 passed: durable outbox/drafts, ambiguous commit/restart, causal revisions, reset fences, roles, reminders, endpoint changes; strict invitation parser, lost enrollment/activation replies and restart, failed claim persistence, re-enrollment cannot erase pending work, widget opt-in; typed workspace views and standalone note drafts across restart; ranking goldens, stable ordering and calendar/DST |
| Shared ranking contract | 16 golden cases independently checked in Kotlin and JavaScript, including priorities without dates, blocked/other-claimed/future work, waiting cap and continuing own work |
| Rust tests | 3 passed: authenticated-encryption/AAD tampering; enrollment approval/acknowledgement/source-bound activation/revocation; expiry, changed permissions, address reuse delayed until host acknowledgement and cancellation |
| Node real-process API/planning/enrollment/organization | 17 passed against both debug and optimized server: roles/CSRF, simultaneous claim winner, persistence/replay/restart, stale timestamps/revisions, normalized images, injected rollback, clear/restore, optional planning fields, calendar intervals and two-client calendar visibility/update/deletion with author enforcement; typed Inbox/List/Project/Note moves, revisions and deletion fences; LAN enrollment with mocked root acknowledgement |
| Chromium workflow | 1 passed: actual login/project/list/Inbox task/shared note/comment/claim, task moves, priority with cleared date, calendar event, board, management, responsive layout, no third-party page requests |
| Linux installer/helper/TLS | 12 passed: plan/destination refusal/direct-listener contract; constrained peer-helper validation and PSK file descriptor, permissions/symlinks, no successful ack on failure, revocation ordering; real direct TLS, CA renewal/no unnecessary reissuance, source ACL rejects spoofed headers, TLS 1.2 rejected |
| Go transport under race detection | 3 passed: real userspace WireGuard peers and HTTPS, boundary paths/CA/stop/bounds; identical command replay after an ambiguous response through trusted alternate endpoint; pinned LAN bootstrap rejects redirects, public/escaped origins, wrong host and oversized replies |
| Actual JVM/Android JNI adapter on Linux | 1 passed through real Go WireGuard peers → pinned HTTPS → Rust API: Unicode, claim authority, stale server identity and admin-path refusal. This uses the explicit legacy-development fixture, not production enrollment/kernel provisioning |
| Android arm64 build | Signed sideloadable development APK built; strict lock/verification metadata, host tests and lint passed (0 errors, 9 warnings) |
| APK inspection | Signature/signer report and SHA-256; 16 KiB ZIP/ELF native alignment; actual manifest includes widget and excludes VPN/Calendar/location/GMS/FCM requirements; no Flutter runtime |
| Linux optimized server | Built and API-tested on x86_64; direct Rust TLS with no proxy. Fresh bundle staging prevents stale proxy files from earlier builds |

The nine lint warnings concern optional API-31 widget sizing hints, arm64-only
ChromeOS coverage, an obsolete SDK condition, layout overdraw, icon analysis and
hardcoded demo/widget resource text. They do not disable lint or verification.
The development APK retains the previous development signer for in-place updates;
operator release signing is separate and was not claimed as executed.

## Android runtime and screenshots

Current 0.2.0 runtime checks passed in two batches: four Keystore tests, two
Compose UI tests, one RemoteViews widget privacy test, and two native/manifest
tests (**9 total**). The native round trip preserves Unicode and checks claim
authority/server identity through actual Android JNI, userspace WireGuard, TLS
and the Rust API. It uses the legacy development fixture, not kernel enrollment.

Initial native attempts failed while the AVD had no connected Wi-Fi/IPv4 route.
After connecting its emulated AndroidWifi, both native tests passed. The harness
now checks for that route and waits for the hub to acknowledge the same test
peer key before the first handshake. TLS and endpoint restrictions stay enabled.

The gallery contains **24 browser captures and 17 Android captures**, including
the actual installed launcher icon. The dark/paper Compose capture runs and
targeted expanded-settings/widget recapture passed. Widget privacy checks cover
private defaults, title opt-in, locking, and exclusion of notes/credentials.

The dedicated emulator is AOSP Android 15/API 35 x86_64 without Play services,
on a host without KVM. Software emulation can cause slow boots and Android system
watchdog/launcher timeouts; this does not establish physical-phone reliability.
The test APK is separate from the arm64 distribution artifact.

[WALKTHROUGH.md](WALKTHROUGH.md) describes the screenshot evidence. Browser
captures run the actual Rust server. Android captures use actual MainActivity,
Compose, Keystore/cache and the production RemoteViews renderer with an explicit
synthetic transport fixture. Their enrollment status is simulated for display;
backend enrollment/source authorization is tested separately. Widget captures
render the real RemoteViews in the test activity, not a third-party launcher.
Production screenshot protection stays enabled; only the synthetic test window
is permitted to capture. No active household invites/data/keys are published.

Reports are generated under `dist/android-test-results/` and
`apps/mobile/build/test-results/`. Host build/check logs and private tool caches
are outside Git. See build commands in [RUNNING.md](RUNNING.md).

## Implemented behavior and remaining deployment gates

Implemented: Inbox tasks without a project, typed Lists/Projects with safe moves,
shared standalone Notes, Unscheduled/Waiting/Completed views, optional dates and
priority/plan/effort/pin/block fields, explained
Focus ordering, shared calendar, author/admin event edits, notes/comments/images,
encrypted offline work/conflicts, scoped QR plus phone-code approval, constrained
host peer provisioning with acknowledgement, source-bound phone sessions,
immediate application revocation and tracked peer removal, local certificate
renewal, LAN administration and redacted-by-default Android home widget.

Not executed on a real home server/phone: privileged Debian/Ubuntu installation,
actual kernel WireGuard/nftables isolation against a hostile peer, off-LAN/DDNS/
hairpin routing, physical GrapheneOS and simultaneous VPN/lockdown behavior,
camera optics/permission lifecycle on that hardware, launcher widget lifecycle,
hardware-backed Keystore, Doze/battery/roaming/reboot behavior, and independent
security review. Helper/API mocks and userspace test peers do not replace these.
Certificate issuance/renewal is tested; the periodic live TLS reload is implemented
but was not separately exercised over its full production reload interval.

Notifications are optional scheduled catch-up and cached local reminders,
without FCM. Instant background push, exact delivery, per-project ACLs, automatic
upgrades, automatic CA rotation and native iOS/desktop builds remain future work.
Widget titles disclose cached copies to the launcher only after opt-in; instant
lock redaction and erasure of launcher copies cannot be guaranteed.
