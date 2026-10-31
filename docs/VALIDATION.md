# Implementation and validation record

Field Notes **0.3.0**, protocol 1, database schema 2, Android version code 3.
This is a development implementation without an independent security audit.
Server-readable encryption is intentional. Android/Kotlin Multiplatform is the
active native target; iOS and native desktop packages are deferred.

## Executed host, API and build checks

| Check | Result and practical scope |
| --- | --- |
| Kotlin shared-domain tests | 31 passed: durable outbox/drafts, ambiguous commit/restart, causal revisions, reset fences, roles, reminders, endpoint changes; strict invitation parser, lost enrollment/activation replies and restart, failed claim persistence, re-enrollment cannot erase pending work, widget opt-in and known rejected-session redaction across restart/storage failure; assignment drafts, directory caching and old-server fallback; typed workspace views and standalone note drafts across restart; ranking goldens, stable ordering and calendar/DST; upcoming agenda, cached alarm policy, offline initialization/reconnection, cheap head polling and pending-work-safe self-revocation |
| Shared ranking contract | 19 golden cases independently checked in Kotlin and JavaScript, including priorities without dates, blocked/other-claimed/future work, waiting cap and continuing own work, own/other assignments and retaining an own active claim |
| Rust tests | 5 passed: container-only listener validation rejects missing Docker context, public/source/overlay/port mismatches; authenticated-encryption/AAD tampering; enrollment approval/acknowledgement/source-bound activation/revocation; expiry, changed permissions, address reuse delayed until host acknowledgement and cancellation; source-bound session renewal, expiry refusal, per-installation self-revocation and unaffected other devices |
| Node real-process API/planning/enrollment/organization/assignment | 18 passed against both debug and optimized server: roles/CSRF, simultaneous claim winner, persistence/replay/restart, stale timestamps/revisions, normalized images, injected rollback, clear/restore, optional planning fields, calendar intervals and two-client calendar visibility/update/deletion with author enforcement; typed Inbox/List/Project/Note moves, revisions and deletion fences; minimal authenticated directory, competing assignments, recipient validation, safe handoff, completion identity and disabled-person history; LAN enrollment with mocked root acknowledgement |
| Chromium workflow | 1 passed: actual login/project/list/Inbox task/shared note/comment/claim, task moves/assignment/recipient views, aligned sidebar baselines, priority with cleared date, calendar event, board, management, responsive layout and mobile Task view filters, no third-party page requests |
| Linux installer/helper/TLS/uninstall/container policy | 47 passed: plan/destination refusal/direct-listener contract, default/explicit/adjacent-to-Bungee forwarding ports and dynamic endpoint guidance, both-port prompts/retry/default confirmation and refusal of missing noninteractive apply choices, automatic endpoint-port propagation and explicit NAT mapping; constrained peer-helper validation and PSK file descriptor, permissions/symlinks, no successful ack on failure, revocation ordering; real direct TLS, CA renewal/no unnecessary reissuance, source ACL rejects spoofed headers, TLS 1.2 rejected |
| Go transport under race detection | 3 passed: real userspace WireGuard peers and HTTPS, boundary paths/CA/stop/bounds; identical command replay after an ambiguous response through trusted alternate endpoint; pinned LAN bootstrap rejects redirects, public/escaped origins, wrong host and oversized replies |
| Actual JVM/Android JNI adapter on Linux | 1 passed through real Go WireGuard peers → pinned HTTPS → Rust API: Unicode, authenticated people directory, claim authority, stale server identity and admin-path refusal. This uses the explicit legacy-development fixture, not production enrollment/kernel provisioning |
| Android arm64 build | Signed sideloadable development APK built; strict lock/verification metadata, host tests and lint passed (0 errors, 21 warnings) |
| APK inspection | Signature/signer report and SHA-256; 16 KiB ZIP/ELF native alignment; actual manifest includes widget and excludes VPN/Calendar/location/GMS/FCM requirements; no Flutter runtime |
| Linux optimized server | Built and API-tested on x86_64; direct Rust TLS with no proxy. Fresh bundle includes offline app/gateway images, checksums, installer and uninstaller |
| Container integration | Real Docker shared namespace with production nonroot/read-only app: direct management TLS, account invitation/approval, root-owned peer acknowledgement, actual WireGuard source-bound activation, task/image writes and unavailable private admin routes; modified phone blocked from management/SSH/other peers/LAN; app-UID outbound TCP and Docker DNS hit actual nftables drops; owned containers/network removed |

The 47 Python checks include 21 scoped uninstall tests (temporary real file trees,
mocked host commands), six container policy/ownership tests and two real TLS
checks. They cover retain/purge/later purge, retries, legacy/custom paths, changed
units, mounts/symlinks, reused identities, key recovery, foreign namespace members,
Bungee preservation, no global prune, fixed local Docker selection and separation
of issued leaf files from CA signing keys. Actual privileged Debian/Ubuntu
installation/account deletion has **not** been run.

The real Docker integration uses a fixture-only userspace WireGuard/TUN image
because this cloud kernel has no WireGuard module. Its extra MKNOD/device access
and full Python test tooling are not in production images. It checks the actual
production app and firewall policy, but does not establish production kernel WG,
physical LAN source preservation, home forwarding or physical phone behavior.
The Docker hairpin test needs a test-only source CIDR; the production installer
retains the operator's exact LAN CIDR and rejects translated out-of-range sources.
See [CONTAINERS.md](CONTAINERS.md) for reproduction and boundaries. Android sources,
APK and screenshots are unchanged by this deployment update; their evidence below
is from the preceding Android build.

The lint warnings concern optional API-31 widget sizing hints, arm64-only
ChromeOS coverage, an obsolete SDK condition, layout overdraw, icon analysis,
hardcoded widget resource text and optional KTX style suggestions. They do not
disable lint or dependency verification.
The development APK retains the previous development signer for in-place updates;
operator release signing is separate and was not claimed as executed.

## Android runtime and screenshots

The baseline runtime suite passed: four Keystore tests, two Compose UI
tests, one RemoteViews widget privacy test and two native/manifest tests
(**9 total**).
Two additional delivery checks passed: standard widget pinning plus actual
launcher XML/content inspection, and real foreground service/AlarmManager
notification delivery, audible-channel metadata, encrypted receipts, duplicate
suppression and the delayed profile test. The final service check also injects
a failed preference write into the notification Stop action: the service stops
and reports the failure without crashing. Physical sound and GrapheneOS
forwarding are not established by those emulator checks. The native round trip preserves Unicode and checks claim
authority/server identity through actual Android JNI, userspace WireGuard, TLS
and the Rust API. It uses the legacy development fixture, not kernel enrollment.

A separate two-installation check passed both phases: it confirms a live cached
alarm and registered worker before normal uninstall, waits for Android's
asynchronous removal, and checks that no live jobs, alarms, provider or app
process remains. Reinstall has no old profile key/connection; explicitly
reintroducing the old synthetic ciphertext fails closed. OS history/component
caches can retain package names and are excluded from live-job checks; no claim
of forensic erasure of OS/launcher history is made. The **13 runtime tests**
include the baseline nine, two launcher/delivery checks and these two phases.

During environment setup the AVD had no connected Wi-Fi/IPv4 route.
Native checks use its connected emulated AndroidWifi. The harness
now checks for that route and waits for the hub to acknowledge the same test
peer key before the first handshake. TLS and endpoint restrictions stay enabled.

The gallery has **28 browser captures and 25 Android captures (53 total)**.
Android captures include the actual
installed launcher icon, real home-screen Upcoming widget and system notification
shade, as well as the Compose planner and settings screens. The dark/paper Compose capture runs and
targeted expanded-settings/widget recapture passed. Widget privacy checks cover
private defaults, title opt-in, locking/known invalid authentication, and exclusion of notes/credentials. Rejected sessions clear the persisted display token while retaining work; a failed credential write still redacts the running widget.

The dedicated emulator is AOSP Android 15/API 35 x86_64 without Play services,
on a host without KVM. Software emulation can cause slow boots and Android system
watchdog/launcher timeouts; this does not establish physical-phone reliability.
The test APK is separate from the arm64 distribution artifact.

[WALKTHROUGH.md](WALKTHROUGH.md) describes the screenshot evidence. Browser
captures run the actual Rust server. Android captures use actual MainActivity,
Compose, Keystore/cache and the production RemoteViews renderer with an explicit
synthetic transport fixture. Their enrollment status is simulated for display;
backend enrollment/source authorization is tested separately. Image 11 compares
RemoteViews privacy states inside the test activity. Image 22 is hosted by the
actual AOSP launcher after normal OS pin confirmation; its visible content is
checked in the launcher accessibility tree before capture. Image 23 shows real
Android notifications from the alarm/service fixture. Production screenshot
protection stays enabled; synthetic Compose captures alone relax their test
window flag. The launcher capture never clears MainActivity screenshot protection. No active household invites/data/keys are published.

Reports are generated under `dist/android-test-results/` and
`apps/mobile/build/test-results/`. Host build/check logs and private tool caches
are outside Git. See build commands in [RUNNING.md](RUNNING.md).

## Implemented behavior and remaining deployment gates

Implemented: Inbox tasks without a project, typed Lists/Projects with safe moves,
shared standalone Notes, Tasks/Assignments/My tasks/Unassigned and other smart views,
assignment-aware Focus/reminders/widgets, explicit active-work handoffs, optional dates and
priority/plan/effort/pin/block fields, explained
Focus ordering, shared calendar, author/admin event edits, notes/comments/images,
encrypted offline work/conflicts, scoped QR plus phone-code approval, constrained
host peer provisioning with acknowledgement, source-bound phone sessions,
immediate application revocation and tracked peer removal, local certificate
renewal, LAN administration, redacted-by-default Focus/Upcoming home widgets,
default-on private foreground updates, cached task/calendar alarms and device
self-revocation followed by Android uninstall settings.

Not executed on a real home server/phone: privileged Debian/Ubuntu installation,
actual kernel WireGuard/nftables isolation against a hostile peer, off-LAN/DDNS/
hairpin routing, physical GrapheneOS and simultaneous VPN/lockdown behavior,
camera optics/permission lifecycle on that hardware, launcher widget lifecycle,
hardware-backed Keystore, Doze/battery/roaming/reboot behavior, and independent
security review. Helper/API mocks and userspace test peers do not replace these.
Certificate issuance/renewal is tested; the periodic live TLS reload is implemented
but was not separately exercised over its full production reload interval.

See [HOUSEHOLD-WORKFLOWS.md](HOUSEHOLD-WORKFLOWS.md) for the functionality review
and prioritized gaps: recurrence, checklist steps, per-task reminders/snooze,
waiting reasons, archive/trash/history, global search, bulk actions, personal
visibility rules, scheduled backup drills and signed update distribution.

Background delivery defaults on after enrollment and notification permission:
a user-visible private foreground connection with head polling, WorkManager
catch-up fallback and cached AlarmManager reminders. No FCM/Google services are
used. These are subject to Android exact-alarm access, battery policy, DND and
profile lifecycle. GrapheneOS cross-profile forwarding and physical sound remain
hardware checks. See [delivery setup and uninstall](ANDROID-DELIVERY.md).
Unconditional instant/exact delivery, per-project ACLs, automatic upgrades,
automatic CA rotation and native iOS/desktop builds remain outside this delivery.
Widget titles disclose cached copies to the launcher only after opt-in; instant
lock redaction and erasure of launcher copies cannot be guaranteed.
