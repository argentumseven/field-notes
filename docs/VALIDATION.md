# Implementation and validation record

## 0.3.6 tunnel enrollment, keyboard and disconnected work

The previous phone bootstrap depended on LAN HTTPS on the management listener.
That conflicts with PC-only management access and can be dropped before TLS,
which the old combined error failed to distinguish. New invitations bind the
phone public key on the PC, preprovision a restricted /32 peer, and perform
claim/status/cancel over WireGuard and pinned TLS to the private task API only.
PC code approval and fresh host acknowledgement remain required before task
credentials. No management ACL, listener, public TCP port, egress policy or OS
VPN slot was added or weakened. Legacy LAN routes remain for recovery only.

All 11 Rust tests/clippy, 34 Node API checks, seven Chromium workflows, 98 Python
checks, Go vet/race checks and 48 shared Kotlin tests pass. New coverage includes
wrong phone/peer rejection, proof-free header rejection, idempotent issuance and
claims, failed audit/manifest recovery, fresh-root-ack address retirement,
replacement of unused expired reservations, preservation of previously activated
bindings, lost replies/restart/activation through the same private peer, and
keeping phone keys when abandoning an expired unactivated attempt.

The full real Docker/WireGuard/TLS fixture passes. It proves preapproval peers
cannot obtain task data, v2 claims cannot use management, and modified phone
traffic cannot reach management/SSH/LAN/other peers. The actual Go C bridge also
pairs against the installer's real CA and Rust TLS, distinguishes an incorrect
pinned CA, activates and reads a snapshot without calling management. APK download
chunks, namespace isolation, key/certificate permissions and cleanup pass.

The disconnected-work regression survives an outage and restart, merges edits
and a new task into a remotely renamed/edited list, then retains a same-task
collision as a conflict without overwriting the partner's edit. It does not
claim automatic text/field merging within one record. See [OFFLINE.md](OFFLINE.md).

Enrollment and sign-in consume system/keyboard insets. The name field, trust
checkbox and request control share a scroll target; Done dismisses the keyboard,
and the raw invitation collapses after review. The real Android IME check passed,
asserting those controls remain above its visible bounds. Camera cancellation,
invalid-code handling and the phone-key start screen passed in separate targeted
checks. Early AOSP runs were obstructed by System UI ANRs/screen sleep; the fixture
wakes the dedicated AVD, enables its soft keyboard, and closes only allowlisted
System UI/Quickstep dialogs, never an app ANR. A deep-link check's assertions
completed but ActivityScenario lost its launch-intent lifecycle marker; its test
cleanup now restores that marker; its rerun passed. All five new enrollment
checks passed across targeted runs, including the real IME, phone-key start
screen, trust reset on replacement, invalid-code rejection and private camera
cancellation. Synthetic captures now wait for the compositor and reject a black
protected frame; the real keyboard and phone-key figures were refreshed. Physical GrapheneOS
camera optics, VPN coexistence and prolonged DDIL/profile behavior remain unrun.

## 0.3.5 QR enrollment

An actual ZXing decode of the old manager's displayed QR failed. The manager now
uses a larger opaque PNG, medium error correction and whole-pixel modules with a
white quiet zone. The regression decodes the exact current invitation from real
Chromium element screenshots at 1440, 390 and 360 px viewport widths in all four
orientations. Seven browser checks pass, including blocked clipboard fallback,
image failure, expiry, replacement and browser-session loss. The QR gallery image
was refreshed from a discarded synthetic fixture.

All 33 Node API/planning checks, seven Rust tests/clippy, 98 Python checks and
43 shared Kotlin tests pass. New API checks verify invitation/audit rollback and
copy enrollment when a valid certificate chain exceeds QR capacity. The complete
Docker/WireGuard/TLS fixture passes, including enrollment approval/activation,
private APK download, nonroot/read-only runtime, firewall denial and cleanup.

Android replaces its custom CameraX decoder with JourneyApps ZXing 4.3.0 and
core 3.5.3, pinned with locks and SHA-256 verification. Release lint, APK signature,
manifest, ARM64 native and 16 KiB checks pass for 0.3.5/code 8 using the existing
public signing certificate. Scanned/deep-linked invitations open review with
trust unchecked; malformed data gets a bounded readable error. Camera permission
denial offers paste; cancellation preserves the draft. The capture activity is
unexported, screenshot-protected and does not save barcode images or upload frames.

The first new emulator camera-return check queried Compose before its root had
reattached and the suite timed out. Its polling now waits through that transition;
the disposable AVD was rebooted without wiping. The rerun was blocked by missing
emulated Wi-Fi, failed Android service calls and a subsequent APK-install timeout;
the three new camera/review runtime checks are **not recorded as passing**. The
prior four vault checks and first-launch UI check completed before that failure.
Physical GrapheneOS camera optics/permissions remain unverified. Existing
single-use, approval, TLS pinning and tunnel restrictions are not bypassed.

The clean `v0.3.5` source is `6519e66227e6df9f7283f9a8e1f654af9bc16cd6`.
[Public APK publication run 41460013187](https://github.com/argentumseven/field-notes/actions/runs/41460013187)
succeeded. All four downloaded release assets matched their verified local
counterparts byte for byte; the README's latest-download URL returned the same
APK. Its SHA-256 is
`e223190adda1134e9130d60c749f00f7b21ab4e0e76ccc91b724c780cd103890`,
with the retained public signer, release variant, code 8 and clean source metadata.
The downloaded package independently passed signature and native/manifest checks.
The offline server bundle carries matching APK metadata. Signing keys are absent
from both payload branch and release assets. Existing operators with a different
original signer must use their paired server's same-signer source build.

## 0.3.4 browser sessions and public APK delivery

A production TLS 1.3/HTTP2 test reproduced the login loop: a valid Field Notes
session in a later Cookie header returned 401, while the same joined header
returned 200. The parser now reads every Cookie field and rejects conflicting
session values. The test now passes for session, snapshot, directory and
CSRF-protected reauthentication, without weakening TLS or using an insecure
certificate exception. The fixture trusts its generated CA explicitly.

The browser confirms its cookie session before entering the workspace. A
controlled post-login 401 now leaves a visible error and the username while
clearing the accepted password; a subsequent retry succeeds. Five Chromium
workflows and all 32 Node checks pass. Plain HTTP sent to the management TLS
port gets a source-restricted, readable HTTPS-required error with no application
route, cookie, Host reflection or alternate listener. The phone API stays TLS.

The 98 Python checks include refusal to build an upgrade without the original
APK key and refusing to overwrite an existing public release-payload branch.
The 0.3.4 ARM64 APK (code 7) has a release variant signed with the same protected
cloud certificate as prior builds. It passed release lint/shared host checks and
native/manifest/16 KiB inspection; the public package is not debuggable.
The offline server bundle and full Docker/WireGuard/TLS fixture pass with the
new code. Web screenshots were refreshed against that bundle.

Public publication uses a dedicated payload branch over an existing clean tag.
GitHub Actions re-verifies APK signature/package/source and the pinned public
certificate, then verifies uploaded bytes before marking the release latest.
The private signing key is not part of that branch or its release assets.
Public certificate pinning identifies the public build; a self-hosted operator
may have a different signer and must preserve it for existing phones.

## Owner-name installation and recovery regression (November 2)

The user's real reset output rejected both `admin` and `owner`. A new test
executing the installer through its real Rust account initialization reproduced
that entered/default/custom usernames all became `field-notes`: the Docker
resource loop overwrote the name. This test failed for all three cases before
the fix and passes with a dedicated `owner_name` used for initialization and
readiness. Host/Docker operations are simulated and all destinations are real
temporary trees; this does not claim a complete privileged Debian install.

The 93 Python checks now also cover read-only account listing, wrong-name
rejection before prompting/hashing/service interruption, atomic legacy-account
rename plus password reset, retained account ID/role/phone session/master key,
new-name HTTP login and old-name refusal, active/disabled name collisions, and
rollback of both name and password after injected transaction failure. Account
inspection runs as the app UID rather than granting SQLite root access to its
writable data directory. The Docker fixture checks the credential drop against
the real store beneath a root-private ancestor, without a Docker socket mount.

## Installer startup and local recovery follow-up (November 2)

The Debian startup report identified an actual permissions bug: the TLS CLI's
`077` umask reduced `mkdir(mode=0750)` to `0700`. Explicit chmod now ensures
root/group traversal on the issued directory, including no-op renewal, while
keeping the signing CA private. The real Docker fixture now uses a root-owned
issued directory and a distinct app UID/GID, verifies successful HTTPS/login and
retained encrypted records/master key/CA, and removes its owned resources. Its
ownership assertions run inside Docker because the cloud executor maps Docker
root to an unmapped executor UID. This is separate from privileged host systemd
installation, which is still untested here.

The 93 Python checks include real Rust Argon2id recovery with a literal `!`
password followed by a successful HTTP login, browser-only session invalidation,
unknown-account refusal, transactional rollback/restart, retained master key and
account role/identity, and explicit completion of a retained installation only
after readiness succeeds. Four pseudo-terminal cases verify both Backspace
encodings, navigation/Delete/UTF-8, hidden editing, bare Escape cancellation and
terminal restoration. All seven Rust and 31 Node API/planning checks pass. Four
Chromium workflows pass, including a persistent APK action when metadata fails
or the bundle is missing. The web gallery was recaptured against the current
Rust-backed fixture. Android code and the existing signed APK are unchanged.

The server bundle was rebuilt with the recovery/editor helpers and verified
matching APK. The local root recovery helper uses the existing offline image;
real host service stop/restart remains covered through simulated host commands,
not a privileged Debian installation. Password recovery writes a host journal
event rather than adding an encrypted in-app audit entry.

Field Notes **0.3.6**, protocol 1, database schema 2, Android version code 9.
This is a development implementation without an independent security audit.
Server-readable encryption is intentional. Android/Kotlin Multiplatform is the
active native target; iOS and native desktop packages are deferred.

## Executed host, API and build checks

| Check | Result and practical scope |
| --- | --- |
| Kotlin shared-domain tests | 48 passed: key-bound private enrollment/retry/activation, wrong-phone rejection and expired-attempt key retention; disconnected changed-list merge and retained same-record conflicts; durable outbox/drafts, ambiguous commit/restart, causal revisions, reset fences, roles, reminders, endpoint changes; strict invitation parser, lost enrollment/activation replies and restart, failed claim persistence, re-enrollment cannot erase pending work, widget opt-in and known rejected-session redaction across restart/storage failure; assignment drafts, directory caching and old-server fallback; typed workspace views and standalone note drafts across restart; ranking goldens, stable ordering and calendar/DST; upcoming agenda, cached alarm policy, offline initialization/reconnection, cheap head polling and pending-work-safe self-revocation; saved pause, late-reply fencing, same-nonce restart, terminal failures, write failure veto, key-preserving/explicit-key reset, missing-identity preservation failed-exchange reconfiguration and cancellation before/lost claim replies without re-claiming; bounded update-offer parsing, software route/pause/revocation fences and pending-edit preservation |
| Shared ranking contract | 19 golden cases independently checked in Kotlin and JavaScript, including priorities without dates, blocked/other-claimed/future work, waiting cap and continuing own work, own/other assignments and retaining an own active claim |
| Rust tests | 11 passed: key-bound issuance, actual-source gates, retry/rollback, fresh-ack expiry/replacement and protection of activated bindings; container-only listener validation rejects missing Docker context, public/source/overlay/port mismatches; authenticated-encryption/AAD tampering; enrollment approval/acknowledgement/source-bound activation/revocation; expiry, changed permissions, address reuse delayed until host acknowledgement and cancellation; source-bound session renewal, expiry refusal, per-installation self-revocation and unaffected other devices; APK tampering rejection and streaming permits retained until response-body drop |
| Node real-process API/planning/enrollment/organization/assignment | 31 passed against both debug and optimized server: roles/CSRF, simultaneous claim winner, persistence/replay/restart, stale timestamps/revisions, normalized images, injected rollback, clear/restore, optional planning fields, calendar intervals and two-client calendar visibility/update/deletion with author enforcement; typed Inbox/List/Project/Note moves, revisions and deletion fences; minimal authenticated directory, competing assignments, recipient validation, safe handoff, completion identity and disabled-person history; LAN enrollment with mocked root acknowledgement; seven password/hash/role/session/CSRF/rollback/rate/race checks, three inactive-pairing cleanup/uncertain cancellation checks with stale root acknowledgements/key reuse/authorization, and three LAN/private APK/fallback/digest/chunk/route-boundary checks |
| Chromium workflow | 5 passed: actual login/project/list/Inbox task/shared note/comment/claim, task moves/assignment/recipient views, aligned sidebar baselines, priority with cleared date, calendar event, board, management, responsive layout and mobile Task view filters, no third-party page requests; real account/password forms, confirmation, role controls, no secret fields after success/cancel/session loss, in-flight dismissal veto, invitation expiry/replacement and QR removal on session loss |
| Linux installer/helper/TLS/uninstall/container policy | 98 passed: no-argument source installer, complete interactive network wizard, explicit manual ports with retries/no derived defaults, no-write cancellation before root/build work, missing build prerequisites, routed administrator CIDRs, namespace TLS/source-policy readiness and routed upgrade rollback; constrained peer-helper validation and PSK file descriptor, permissions/symlinks, no successful ack on failure, revocation ordering; real direct TLS, CA renewal/no unnecessary reissuance, source ACL rejects spoofed headers, TLS 1.2 rejected; APK metadata/checksum/signer/file-set validation, draft release resumption/source mismatch/dirty preview checks and scoped download-directory removal/refusal; eight server upgrade/backup/rollback/signer/identity/history tests in temporary trees |
| Go transport under race detection | 3 passed: real userspace WireGuard peers and HTTPS, boundary paths/CA/stop/bounds; identical command replay after an ambiguous response through trusted alternate endpoint; pinned LAN bootstrap rejects redirects, public/escaped origins, wrong host and oversized replies |
| Actual JVM/Android JNI adapter on Linux | 1 passed through real Go WireGuard peers → pinned HTTPS → Rust API: Unicode, authenticated people directory, claim authority, stale server identity and admin-path refusal; controlled blocked-request cancellation completes within three seconds, and a queued exchange fails before sending after Stop. This uses the explicit legacy-development fixture, not production enrollment/kernel provisioning |
| Android arm64 build | Signed sideloadable development APK built; strict lock/verification metadata, host tests and lint passed (0 errors, 22 warnings) |
| APK inspection | Signature/signer report and SHA-256; 16 KiB ZIP/ELF native alignment; actual manifest includes widget and excludes VPN/Calendar/location/GMS/FCM requirements; no Flutter runtime |
| Linux optimized server | Built and API-tested on x86_64; direct Rust TLS with no proxy. Fresh bundle includes offline app/gateway images, matching signed ARM64 APK/checksum/signer/metadata, installer and uninstaller |
| Container integration | Real Docker shared namespace with production nonroot/read-only app: direct management TLS, account invitation/approval, root-owned peer acknowledgement, actual WireGuard source-bound activation, task/image writes and unavailable private admin routes; modified phone blocked from management/SSH/other peers/LAN; app-UID outbound TCP and Docker DNS hit actual nftables drops; phone tokens rejected on LAN; routine password change preserves the phone while explicit device sign-out invalidates it; anonymous LAN APK bytes match the read-only mount and downloads are absent privately; complete APK metadata/chunk transfer verified over actual private WireGuard/TLS; owned containers/network removed |

The current 93 Python checks include 23 scoped uninstall tests, eight base
upgrade tests, revised guided installer/container policy tests, three actual TLS
checks, and four local/routed readiness and rollback checks. Temporary install
trees and controlled host commands cover retain/purge/later purge, retries,
legacy/custom paths, changed units, mounts/symlinks, reused identities, key
recovery, foreign namespace members and preservation of unrelated services. Actual privileged
Debian/Ubuntu systemd installation/upgrade/account deletion has **not** been run.

Follow-up validation pulled current `main`, checked both fix branches against
their squash-merge snapshots, and parsed all Python helper scripts. The first
run found three regression failures (two cancellation/root-order cases and an
obsolete mutually exclusive CLI invocation); the corrected suite passes.
A fresh `--offline --rerun-tasks assembleDebug lintDebug hostTest` completed all
55 tasks under the new one-worker/in-process compiler/1.5 GiB heap limits with
43 shared tests passing. Gradle reported a metaspace pressure warning but the
build succeeded; this does not establish that a 2 GiB home VM can build Android.
Use a prebuilt bundle to avoid those toolchain/resource requirements.

The real Docker fixture also executes the new namespace-local probe against the
production Rust listener: runtime version matches, pinned TLS 1.3 validates, and
loopback gets the required HTTP 403. It adds no admin exception or egress route.
Routed upgrade unit tests verify that a forbidden host-originated admin request
is never attempted, and failed namespace readiness rolls back programs/settings.
The exact Debian 13 `docker-compose` package was checked: v2.26.1 includes both
the Docker CLI plugin and its standalone symlink.

The image builder records each exact release tag even when an unchanged gateway
image also has older tags. This was checked against the real multi-tag Docker
cache; choosing the first arbitrary tag could otherwise make upgrade validation
reject the bundle.

The real Docker integration uses a fixture-only userspace WireGuard/TUN image
because this cloud kernel has no WireGuard module. Its extra MKNOD/device access
and full Python test tooling are not in production images. It checks the actual
production app and firewall policy, but does not establish production kernel WG,
physical LAN source preservation, home forwarding or physical phone behavior.
The Docker hairpin test needs a test-only source CIDR; the production installer
retains the operator's exact LAN CIDR and rejects translated out-of-range sources.
See [CONTAINERS.md](CONTAINERS.md) for reproduction and boundaries. Android
sources, version-code-6 APK and gallery were rebuilt for these changes.

The lint warnings concern optional API-31 widget sizing hints, arm64-only
ChromeOS coverage, an obsolete SDK condition, layout overdraw, icon analysis,
hardcoded widget resource text and optional KTX style suggestions. They do not
disable lint or dependency verification.
The development APK retains the previous development signer for in-place updates;
operator release signing is separate and was not claimed as executed.

## Android runtime and screenshots

The baseline runtime suite passed: four Keystore tests, two Compose UI
tests, one RemoteViews widget privacy test, three APK verifier/provider checks
and two native/manifest tests (**12 total**).
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
asynchronous removal, and checks that no live jobs, alarms, widget provider or app
process remains. Reinstall has no old profile key/connection; explicitly
reintroducing the old synthetic ciphertext fails closed. OS history/component
caches can retain package names and are excluded from live-job checks; no claim
of forensic erasure of OS/launcher history is made. The baseline twelve, two
launcher/delivery checks, these two uninstall phases and the two in-place-upgrade
phases total **18 runtime checks** across separate guarded runs.

During environment setup the AVD had no connected Wi-Fi/IPv4 route.
Native checks use its connected emulated AndroidWifi. The harness
now checks for that route and waits for the hub to acknowledge the same test
peer key before the first handshake. A cold-boot native attempt failed while the
emulated network was unstable; its warm retry passed. A delivery run also lost
the instrumentation process during emulator instability; its isolated retry
passed. TLS and endpoint restrictions stay enabled.

The gallery has **33 browser captures and 29 Android captures (62 total)**.
Android captures include the actual
installed launcher icon, real home-screen Upcoming widget and system notification
shade, as well as the Compose planner and settings screens. The dark/paper Compose capture runs and
targeted widget recapture passed. The settings Close action stays visible while
its content scrolls, and the harness verifies dismissal before navigating.
Secondary actions and home-widget open/refresh controls have visible boundaries. Current captures also cover
password changes/reset, inactive binding removal, APK download controls and paused
pairing. The capture harness now collapses the notification shade and checks that
the app owns the focused system window before taking planner screenshots.
It copies the actual focused app window after UI settling, including dialog
windows; launcher/widget and notification captures use Android’s system screen.
Identical capture frames are rejected before publishing the gallery. Widget privacy checks cover
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
immediate application revocation and tracked peer removal, durable local pause,
key-preserving reset, guarded inactive pairing removal, salted password management,
local APK distribution, local certificate
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
visibility rules and scheduled backup drills. Server-authoritative in-app APK
updates and compatible container upgrades are implemented. Permanent APK release tooling is implemented,
while hosted publication remains a separate gate.

Background delivery defaults on after enrollment and notification permission:
a user-visible private foreground connection with head polling, WorkManager
catch-up fallback and cached AlarmManager reminders. No FCM/Google services are
used. These are subject to Android exact-alarm access, battery policy, DND and
profile lifecycle. GrapheneOS cross-profile forwarding and physical sound remain
hardware checks. See [delivery setup and uninstall](ANDROID-DELIVERY.md).
Unconditional instant/exact delivery, per-project ACLs, unattended installation,
automatic CA rotation and native iOS/desktop builds remain outside this delivery.
Widget titles disclose cached copies to the launcher only after opt-in; instant
lock redaction and erasure of launcher copies cannot be guaranteed.

## Bungee review and APK publication

Current Bungee source was reviewed through `67ee496`, including the newer
`fbc16fa` key/QR change. No upstream code/assets were copied or Bungee tests run.
The independently implemented changes and deliberate differences are recorded in
[BUNGEE-PAIRING-REVIEW.md](BUNGEE-PAIRING-REVIEW.md).

The server bundle offers a verified signed development APK on Sign in and in
Management. Host and real Docker checks cover the actual LAN download, checksum,
private-route exclusion and invalid-artifact fallback. The APK retains the prior
development signer. No signing key is included in the bundle.

The pinned GitHub Actions workflow and draft-first publisher were prepared and
locally checked; publisher tests use a controlled fake GitHub runner. A hosted
workflow, repository release secrets and a permanent GitHub Release were **not**
verified or created here: GitHub API access returned `Forbidden` in this cloud
session. No tag was pushed to claim an unexecuted release. Use the local server
download; [ANDROID-DOWNLOADS.md](ANDROID-DOWNLOADS.md) documents release setup.

## In-place software update checks

Real same-signer 0.3.1/code-4 → 0.3.2/code-5 and
0.3.2/code-5 → 0.3.3/code-6 APK installations passed both phases on the guarded
disposable AVD: the complete encrypted cache
reopened under the existing Keystore alias with identical phone keys, saved
server/PSK/CA fields and queued task command. No clear, uninstall, re-enrollment or
binding purge occurred between the seed and verification phases.
The first 0.3.3 seed attempt lost its instrumentation process without a crash
record; the same guarded check passed after explicit AOT preparation of the
disposable emulator packages. No production certificate, key or timeout check
was relaxed.

Three Android verifier/provider checks exercise actual APK signatures, checksum,
package/version identity, same-code/downgrade rejection, private provider and
read-only handoff to a system installer, and a broken software cache preserving
the planner vault. The user-confirmed installer UI and physical GrapheneOS
profile upgrade remain separate hardware checks.

The container fixture downloads the complete offered APK through authenticated
bounded chunks over real WireGuard and pinned TLS and verifies its complete hash.
The real Android JNI fixture also fetches update metadata and a bounded APK chunk.
The update path adds no public endpoint, system VPN slot or egress exception.

Compatible Linux upgrade tests use real temporary install trees, controlled host
commands and a fake Docker engine. They cover retained data/master/CA/hub/network
bytes, stable installation identity/ports, encrypted-backup admission, readiness
rollback, corrupt/incompatible bundles, signer changes and scoped historical
image ownership. They do **not** establish privileged Debian/Ubuntu/systemd/kernel
upgrade behavior. See [UPDATES.md](UPDATES.md) for the operator path and limits.
