# Implementation and validation record

## Development — delete navigation and visible alarms (unreleased, version unchanged)

Branch `claude/workflow-bugs-ux-polish-remy0r`. No release, deployment or physical-phone
check is claimed.

Changes: deleting a project or list from inside it returns to Projects, its parent
project or Lists instead of a task view filtered to a missing id; a container removed
while open (including by another household member) leaves the same way with a notice;
the delete dialog explains a non-empty container where the user taps, awaits the queued
delete and shows a refusal inline. Alarms post to one high-importance silent Alarms channel
(`fn-alarm-v2`, replacing the per-importance `fn-alarm-v1-*` channels) so the card appears
on screen; opening it silences the sound; the card settles without Stop afterward; the loud
alarm test posts the same card. Alarm clips are regenerated as whole, seamless phrases;
playback eases in and out, raises volume while silent, and vibrates for the ring time.

Automated, passed: 140 Kotlin host tests including the new landing-rule test, Android
debug lint, compilation of the Android and instrumented-test sources, and the Rust sound tests,
which now also check that every alarm clip is a whole number of phrases and joins its own
start without a jump. Compiled but not run: the new instrumented test
`alarmCardUsesOneHighImportanceSilentChannelAndSettlesWithoutStop` and the changed
sound-length assertion, because no emulator is available in this environment. Not run:
emulator and physical GrapheneOS checks of heads-up display, tap-to-silence, the audible
fades and vibration, and the delete-navigation flow in the real UI. No lock-screen or
screen-waking alarm activity exists yet.

## 0.11.6 category workflows and safe portable recovery — 2026-11-11

Version 0.11.6/code 25 delivers custom household categories, revision-fenced
non-destructive work-list unbundling, explicit list conversion review and the
follow-up import/calendar fixes described below. Archive version 3 preserves
custom metadata with administrator password proof for missing definitions.
The stable public APK signer, protocol 1, schema 2 and pairing identities remain
unchanged.

Public delivery verified: [v0.11.6](https://github.com/argentumseven/field-notes/releases/tag/v0.11.6),
source `83ed751cb2cc9b222f73151c6880fd65ef09635d`, Android code 25.
[Prebuilt publication run 43113774560](https://github.com/argentumseven/field-notes/actions/runs/43113774560)
succeeded. The four public assets match the clean tagged build byte for byte;
actual APK signature/package/version/ARM64 and 16 KiB alignment checks pass.
The latest-download URL returns this same version/code/hash. APK SHA-256:
`deceecd83677350e6aa39676420613651f09dba308b222554af59e1ec3a3b2cb`.
The actual APK contains the category, list-unbundle, conversion and import
password-confirmation code. Release lint, 139 Kotlin host tests, all 124 Node
checks and 25 upgrade/recovery tests passed for this version.

The matching offline x86_64 Linux bundle is available from the README download
link, on the dedicated `server-bundles/v0.11.6` artifact branch. The downloaded
100,142,156-byte public bundle was verified independently, including its APK
hash/version metadata and preserved pinned signer. Bundle SHA-256:
`aafe7e8bb559b4d55a3cf3250d10c461e725426681793e743aed07908e5bb9aa`.
Both Docker image IDs were built/resolved locally and the nonroot production
app ran with a read-only root, no capabilities and no network. Recompressing
the offline image archive reduced download size without changing its images;
embedded and outer checksums were recalculated and verified. No private key is
in the bundle. Physical phone/home-server installation remains an operator
upgrade; this publication did not change the user's running household.

## Custom categories and organization workflows — separate development branch

Branch: `feature/category-and-organization-workflows-2026-11-11`
(base: `main`). Separate from the earlier unmerged project deletion/recovery
branch; no changes deployed.

Scope: additive encrypted category table; snapshot category metadata;
stable custom IDs; web create/rename/archive/unarchive/merge/delete-unused;
Android selection and filters; old-client overwrite fences; transactional
work-list unbundle; list-role conversion previews and typed confirmations.
The database schema's user version remains 2. Existing records and category
keys are unchanged at startup.

Added integration cases cover stable category IDs, merge rollback on stale
revisions, archive rules, delete-only-when-unused, old-client overwrite
protection, list unbundle without deleting comments, and work/reference
conversion restrictions. Follow-up local review reproduced two defects:
custom-category exports omitted definitions, and calendar field copying let
new archived assignments bypass validation. Both have regression tests and
implementation fixes on `fix/category-merge-readiness`; the category suite is
now part of `npm test`. Local verification results are recorded below.

Local follow-up checks passed: Rust build, formatting, Clippy with warnings as
errors and 18 Rust tests; all 123 tests in the full `npm test` run, plus the
updated three-case archive-codec suite; 139 Kotlin host tests; Android debug
Kotlin compilation; and five Chromium project/transfer workflows. The new web
workflow cancels password proof without importing anything, then confirms and
imports the original category ID and label. API regressions cover archived
calendar reassignment, version-3 metadata, category naming/capacity conflicts,
administrator/recent-proof checks, preview rollback, injected commit failure,
retry, restart and destination metadata preservation. Version 1/2 archive
regressions still pass. JavaScript syntax/formatting and version consistency
checks pass. No emulator/physical-device run, APK release, push or deployment
was performed for these fixes.

Remaining gates: Android APK/emulator acceptance; old/new server/client feature negotiation; copied
encrypted database upgrade; offline conflicting category edits; backup and
checkpoint recovery of custom definitions; portable export/import with
custom category IDs on physical Android; and integration with the earlier
project lifecycle branch, which has six textual conflicts with the fixed
branch. A merge simulation against the fetched `origin/main` is clean. Never
run destructive fixtures against the live environment.


## 0.11.5 checkpoint deletion and deliberate restores — 2026-11-11

Web and Android expose **Delete checkpoint** per saved household checkpoint,
including damaged copies. Only administrators with recent password proof may
delete. The request binds the selected ID/index, workspace basis and history
revision, requires the exact deletion phrase, commits atomically with an audit
entry and durable receipt, and invalidates earlier restore previews. Shared
retained image bytes are freed only after their final reference; live images,
content and other retained states are preserved. Indices are never reused.

Every restore now has a separate confirmation screen with source name/index,
restored/changed/removed counts, an explicit review acknowledgement, an empty
phrase field specific to that source, and a password field. The final button
stays disabled until complete. Enter in website fields cannot submit the restore.
The server requires recent password proof, the exact phrase and reviewed=true;
older automatic-confirmation clients fail closed while core sync remains usable.
The existing ten-minute session-bound preview, content/people/retention fences,
automatic safety checkpoint, attachment checks and idempotent retry path remain.
The updated Android client also refuses unsafe restore previews from older
servers and keeps deleted-checkpoint requests durable across interrupted replies.

Automated checks passed: 115 API/integration tests, 135 Kotlin host tests,
18 Rust tests, four Chromium recovery workflows (including a narrow viewport),
and 53 Python delivery checks (25 upgrade, seven publication, ten APK-artifact,
11 container tests). Rust formatting/clippy, JavaScript formatting, Android
release lint, signed ARM64 packaging, actual APK manifest inspection and 16 KiB
alignment checks passed. Tests include source-specific phrase and review checks,
password rejection, cancellation, concurrent deletion, rollback on audit failure,
receipt persistence across restart, and preservation of shared/live attachments.
Public delivery was verified from clean tag **v0.11.5**, source `1876afc`,
with Android code **24** and the unchanged pinned signing certificate. GitHub
[publication run 43023644420](https://github.com/argentumseven/field-notes/actions/runs/43023644420)
succeeded. All four downloaded public assets match the local tagged build byte
for byte; the actual APK signature, package/version/code and ARM64 ABI passed
verification. APK SHA-256:
`e23522e475021ebaa3690a32eebc5d64d7276bd52d6efd94ba2e8eeaef58a8c5`.
The latest-download URL also offers this exact 0.11.5/code-24 metadata.

The default server build downloaded that public APK and built the matching offline
app/gateway Docker images and Linux bundle. The packaged APK matches the verified
public bytes; image IDs match the local built tags, and executable/image archive
checksums passed. No signing key was put in the server bundle. The 25 upgrade
checks include recovery from a running previous release with missing rollback
images, preserving content, accounts, pairings, keys and network configuration.

Physical GrapheneOS interaction and the user's home server are not exercised by
these automated checks.

## 0.11.4 project membership and upgrade delivery — 2026-11-11

The project workspace now exposes **Add existing task** on web and Android.
Task and existing-list-item editors use **Project or list**, including an
explicit **No project or list (Inbox)** choice. New tasks retain the selected
project; the main Tasks view defaults to no parent. Membership-only moves keep
the task's content, identity, comments, attachments, category and assignment.
Captured revisions and workspace fences reject stale moves, and the picker
refuses to bypass unsaved task drafts or pending Android task changes.

The audio-start failure path now updates the original notification identity and
channel instead of posting a second reminder. A null Android service-start
result is treated as a delivery failure. Current main's persisted
`SharedAdditions` delivery remains in place; an older generic change observer
is not reintroduced alongside it.

Ported onto the 0.11.3 source with its project workspaces, reference/task-list
distinction, category inheritance and notification/alarm sound policy retained.
**23 focused API/browser checks and 132 Kotlin host checks passed**, including
the task membership workflow, parentless Tasks creation, stale-edit rejection,
project rollups/claims, list editor/refresh behavior, recipient projection and
offline persistence/restart. Android application/test compilation and lint also
passed. The new notification runtime fixture compiles but
has not run on a device: the earlier software AVD attempt failed during system
boot without KVM. Physical GrapheneOS checks remain unrun.

Version **0.11.4 / Android code 23** completes delivery of these changes with
matching server/client versions and the unchanged pinned public certificate.
Protocol 1, schema 2 and existing signing/identity/version fences are retained.
The recovery-upgrade regression covers a retained active 0.11.3 installation
with missing rollback image IDs, target-image encrypted backup, preserved
workspace/key bytes and unrelated services, and transition to the newer release.
Normal upgrade still refuses missing rollback images; the explicit recovery
path is documented for both active and stopped retained installations.

Release gates passed: **110 API checks, 132 Kotlin host tests, 18 Rust tests,
3 focused browser cases and 53 focused Python checks** (upgrade, publication,
APK artifact and container boundaries). Rust formatting/clippy and Android
release lint passed. The signed ARM64 release passes actual signature,
package/version, native-library and 16 KiB alignment verification with the
existing public certificate. The older calendar browser fixture now chooses
alarm mode before selecting an alarm-only tone; the original schedule, sound
and recipient assertions remain in place.

The actual 0.11.4 offline app/gateway images were built, exported, reimported
and resolved by both recorded immutable IDs. A real nonroot, networkless,
read-only-root app container produced and restored an encrypted backup of
synthetic project/task/comment/note data. Login password, content and revisions
were retained; fresh-directory restore changed epoch and revoked the old
session. This validates the target backup image, not privileged recovery on a
physical Debian host. Existing field-notes data, phone pairing and signing keys
were not reset.

Delivery verified: [v0.11.4](https://github.com/argentumseven/field-notes/releases/tag/v0.11.4)
publishes source `aecbdf52e6eb23f404dab73503b03a01d30e7f05`, Android code 23,
with the unchanged public signer. The actual APK SHA-256 is
`8fc49283589fc95e40c48cfef2c1cecf80a9a9ba10b4427078200d67e4fc8a7f`.
All four public download files match the clean tagged build byte-for-byte;
actual APK signature/package/version/ARM64 verification passed after download.
The latest-release URL resolves to the same 0.11.4/code-23 metadata.
[Publication run 42964587162](https://github.com/argentumseven/field-notes/actions/runs/42964587162)
succeeded. The default Linux server bundle build downloaded those public assets,
retained them unchanged, and passed both immutable image-ID and archive-checksum
verification. The home server and physical phone were not remotely modified
or physically validated here; the documented recovery-upgrade command applies
the verified newer release on the retained installation.

## 0.11.3 reminder audio and issue handling — 2026-11-09

Notification cues and sustained alarm phrases use distinct bundled audio and
separate mode-specific choices on phone and web. Existing tone identifiers and
durations remain readable. Notifications play once on their normal stream;
alarms retain a bounded duration, visible Stop and durable volume restoration.
Collapsed notices add item type, location and notes; playback controls carry the
actual item title/details, matching privacy policy and an item deep link.
Resolved volume-permission warnings clear automatically; issue dismissal is
persisted and matched to its displayed warning so it cannot hide a newer failure.
Volume preference changes use an atomic update of the current saved preferences.
No protocol/schema/content format or pairing change is introduced.

Current checks pass: **130 Kotlin host tests**, Android debug/release builds,
lint and ARM64 signature/alignment/package checks; **one focused Rust test**
checks all 64 WAV assets and rejects unknown paths. The **focused web picker
case** passes mode-specific choices/defaults, distinct served WAVs and returning
from Quiet without stale alarm controls. The **two new Android runtime cases**
pass actual resource/channel separation, alarm payload/Stop, automatic stale-warning
clearance, persisted notification dismissal and fencing of a newer failure.
All **four existing Android audio cases** pass volume restoration/refusal,
replacement leases, later manual changes, notification-stream isolation and
service Stop. The **native notification-shade case** passes populated title,
person, schedule, location, notes, Open item, hidden lock-screen contents,
independent channels, durable receipts/refill and non-replay. Its fresh capture
is [43-detailed-notifications.png](screenshots/android/43-detailed-notifications.png).
The capture driver opens Tasks directly to avoid the software emulator's cold
calendar render; the initial failure occurred in capture setup after payload
assertions had passed. There are **seven passing focused Android runtime cases**.
Release delivery is recorded separately below. These use the dedicated AOSP AVD;
physical GrapheneOS speaker loudness, DND and full-profile forwarding remain
device checks. No unrelated full API, browser or container suite was rerun.

Delivery verified: [v0.11.3](https://github.com/argentumseven/field-notes/releases/tag/v0.11.3)
publishes source `da624136af1dc9cfe9085385e527e8e2a7329ea3`, Android code 22,
with the unchanged signer
`e5d93fbb7b98f02f94a3ed6c35ce35e64d07d4adadcc698fa7b0099c8e0fb488`.
All four public assets match the verified local release byte-for-byte; actual
signature/package/ARM64 checks pass, and latest-download metadata matches.
Public APK SHA-256:
`dd3d8538c122e91e62dc4b7281cae08a7940ab2983a131c95a72a52482c4f2e9`.

The ordinary Linux x86_64 build fetched that public APK without artifact-directory
overrides. All **15** host helpers match source; both pinned Docker images exist;
binary/image/archive checksums pass and the outer archive has no private-key
entries. The isolated packaged-server check serves the distinct WAV assets and
offers the exact public APK through the authenticated phone update/chunk routes.
Complete local Linux bundle SHA-256:
`e7d11e0947427d0c079140c2e627149d76e990064ff3f41b37b95a6dd81cfaef`.
The GitHub release contains the four Android assets; the Linux bundle is locally
built and verified. No home-server deployment was performed.

## 0.11.2 phone list viewport — 2026-11-08

List indexes, opened lists, task views and project headers now share their page's
scrolling body. Only navigation and compact sync status stay fixed. List options
group filters, settings, history and checked-item operations. Compact item rows
retain both author/time labels and independent check/delete controls. Reference
item dialogs keep their list composed; per-page saved state retains scroll and
filters across navigation. Lists is selected while viewing a list, and Back goes
to its index or parent project. User drags dismiss the search keyboard without
discarding the query. No protocol/schema or stored-content format is changed.

Current checks: **128 Kotlin host tests**, **133 Python helper tests**, debug
and ARM64 release compilation, Android lint and packaging checks pass. The native
**360×640 viewport/navigation/IME check passes**: dragging the page heading moves
it, at least three item rows remain visible, item/index return preserves position,
and a user drag dismisses the real keyboard without dropping the search text.
The actual encrypted cache retains all 18 items, unchanged keys and no unintended
queued edits. Five fresh captures are linked in [WALKTHROUGH.md](WALKTHROUGH.md).
The native live shopping/removal regression also passes: Sophia's incoming Bread
and amended Bananas arrive without pressing refresh despite legacy auto-refresh
being off; an individual offline deletion remains queued and is acknowledged on
reconnection; store-scoped checked cleanup retains the other store's checked item.
Its four screenshots are refreshed. The same-signer **0.11.1/code 20 →
0.11.2/code 21** update passes both seed/verify phases, retaining the actual
Keystore-backed keys, CA/PSK/server binding, privacy choices and an unacknowledged
task command without clearing, uninstalling or re-pairing between versions.
Delivery verified: [v0.11.2](https://github.com/argentumseven/field-notes/releases/tag/v0.11.2)
publishes clean tagged source `8a4fc8921ba866c3ccf39b442301eaaeb4928b91`, Android
code 21 and the unchanged public signer
`e5d93fbb7b98f02f94a3ed6c35ce35e64d07d4adadcc698fa7b0099c8e0fb488`.
All four public assets match the locally verified release byte-for-byte; actual
APK signature/package/ARM64 checks pass and latest-download metadata resolves to
this release. Public APK SHA-256:
`0cf594cb46bb4e2ac126c8782650fe69d2d9c8d2449be2e85a09a232ed33eba9`.

The ordinary Linux x86_64 build fetched the same public assets without APK or
download-directory overrides. All **15** host helpers match source, both pinned
images exist, archive/image/binary checksums pass, and the outer archive contains
no private-key entries. Its executable reports 0.11.2. The isolated packaged-server
check passes capabilities, clock diagnostics, compatible partial recipe editing,
reminder/location persistence across restart and the exact paired APK offer/chunk.
Complete local Linux bundle SHA-256:
`c627cc44b3e8faa3ecc248faa7deeb487a9979188efb0184c5448b2b250a2ee9`.

The GitHub release contains the four Android assets; the Linux bundle is locally
built and verified. The prior API/browser/container evidence applies to the
unchanged server/transport behavior. Android checks use the dedicated disposable
AOSP emulator and synthetic data. Home deployment and physical GrapheneOS
behavior were not tested in this cloud environment.

## 0.11.1 widget unlock follow-up — 2026-11-08

The actual launcher check found a stale hidden calendar view even though Android
reported the user unlocked. Visibility now queries UserManager, PowerManager and
KeyguardManager directly, without a cached screen-off flag. Provider binding and
resize start fresh visibility checks; repeated checks rebuild even when the last
observation agrees. Late keyguard changes have bounded retries. A renderer skips
publication when its disclosure decision became obsolete while building the view.
Protected system broadcasts admit privileged system senders, with disclosure still
determined by Android's current state. Focus/Upcoming receiver work runs on IO.
Encrypted background sync and stored privacy choices remain independent.

Patch checks passed: **128 Kotlin host tests**, **133 Python helper tests** and
**17 Rust tests**. Android debug/release builds, lint and packaging checks pass. Both actual month and week
launcher tests pass pinning, automatic offline repaint, screen-off privacy
state, reveal after wake without a new sync, and opening the exact selected
date. All four fresh launcher/day-link captures are published in the walkthrough.
The same-signer **0.11/code 19 → 0.11.1/code 20** update passes both phases,
preserving actual Keystore-backed keys, CA/PSK/server binding, original privacy
preferences and an unacknowledged task command. No clear, uninstall or re-pair
occurs between versions. The actual WorkManager rerun also passes with widget details hidden and the
app lock enabled, retaining the incoming appointment in the encrypted vault.
Clean-tag publication and the ordinary matching server bundle are verified
separately below.
The 0.11 planner API/browser/container evidence remains applicable; this patch
changes Android widget handling and release/version packaging.

Delivery verified: [v0.11.1](https://github.com/argentumseven/field-notes/releases/tag/v0.11.1)
publishes clean tagged source `1dc12b83bc19b044aa849df58ef32163626b167f`, Android
code 20, using the unchanged public signer
`e5d93fbb7b98f02f94a3ed6c35ce35e64d07d4adadcc698fa7b0099c8e0fb488`.
All four public assets match the built release byte-for-byte; actual APK
signature/package/ARM64 verification passes. Latest-download metadata resolves
to this release. Public APK SHA-256:
`5254c013d93e0cd215f542a27861cadf00eff722be47ae7b39f9498e18dd6b7b`.

The ordinary Linux x86_64 build fetched those same public bytes without APK or
download-directory overrides. All **15** host helpers match source; both pinned
images exist; archive/image/binary checksums pass; no private-key entries appear
in the outer archive. Its executable reports 0.11.1. The isolated packaged-server
check verifies current capabilities, clock diagnostics, partial recipe editing,
reminder/location persistence across restart and the exact paired APK offer/chunk.
Complete local bundle SHA-256:
`5f4f186bd55b7f5bc0735dd64d70bfe66dbf6eee7dd585561210f9d8a96c4968`.
The GitHub release contains the four Android assets; the Linux bundle is locally
built and verified. Screenshots use synthetic data. Home-server deployment and
physical GrapheneOS full-profile sound/forwarding remain separate device checks.

## 0.11.0 planner and widget continuity — 2026-11-08

Implemented explicit day/time reminders, week/day presets, optional federal
holidays, recipes and appointment locations, focused event sections, separate
notification/alarm volume consent, five-second new alarms, host clock diagnostics
and widget/unlock continuity. Existing reminders and keys are retained.

Current automated evidence: **128 Kotlin host tests**, **133 Python helper tests**,
**17 Rust tests** and warning-free Clippy. The API suite passes **109 cases** plus
a new whole-content rollback/roll-forward regression (**110 total**). Browser
coverage passes **37 cases across the full run and focused corrections**: the
full run passed 35; the two failing scroll/sound cases and five related list
cases passed after correction. The two new editor/recipe cases also pass with
the refreshed dark/light gallery. Both ARM64 release and x86_64 test APKs build;
release/debug lint and packaging checks pass.

Android runtime checks already passed: **one real WorkManager widget-sync case**
with schedule details hidden and app locking enabled; **four audio cases** for
volume restoration/refusal, later manual changes, replacement leases, separate
notification control and service Stop. These use the named disposable AVD and
actual Keystore-backed storage, not a household server or physical phone.

The native planner gallery passed: explicit week/day time, five-second alarm
selection, recipe entry with the actual keyboard, Done typing, offline queue
projection, acknowledged sync and Keystore-backed vault retention. The same-signer
**0.10/code 18 → 0.11/code 19** APK upgrade also passed both seed and verify phases.
The actual container integration suite also passes all **10 cases**, including
pinned direct TLS, private WireGuard enrollment, bounded authenticated APK
updates, source-bound phone access, denial of management/LAN/peer routes, real
app-UID egress/DNS drop counters, retained keys and scoped cleanup. Launcher
lifecycle correctness was not established by 0.11.0; the verified 0.11.1
follow-up above fixes the stale hidden view. Publication and ordinary bundle
verification for 0.11.0 are recorded below. The native JNI → WireGuard → TLS → Rust check passes
both runtime/manifest cases, including stop/reconnect with the same identity.
A cold
software-emulated Android System UI stalled during one discarded gallery run;
its keyboard/system UI were warmed before the repeat. Another discarded run
revealed a fixture race: sync() intentionally yields to an existing sync, and
the test paused before it finished. The recipe remained queued; the corrected
fixture waits for acknowledgement before pausing. No failed gallery is published. Repeated launcher attempts hit AOSP
job-stop timeouts (`No response to onStopJob`) during cold software-emulated
startup; their lifecycle assertions have not been counted as passing. The
0.11.1 launcher passes above use the documented phone viewport, cleared fixture
jobs and a restarted framework with an emulator-only timeout multiplier of 10.
Production timeout policy and test assertions are unchanged.

The local queue persists and publishes a write as one cancellation-safe unit.
A pending reminder replacement removes obsolete server projections immediately;
unrelated edits preserve them. Both regressions failed before the foundation
fix and pass afterward. New-format validation and older-client edit fences,
restart persistence, duplicate-safe transfer and checkpoint rollback/forward
are covered against the actual Rust server.

Delivery verified: [v0.11.0](https://github.com/argentumseven/field-notes/releases/tag/v0.11.0)
publishes clean tagged source `e3f272241a6332cfc32e59cdcffc88452a982dac`, Android
code 19, using the unchanged public signer
`e5d93fbb7b98f02f94a3ed6c35ce35e64d07d4adadcc698fa7b0099c8e0fb488`.
All four public assets match the local build byte-for-byte. Actual APK signature,
package, ARM64 ABI and source checks pass, and latest-download metadata points
to this release. Public APK SHA-256:
`b905af9757f60e57e1d58aafebbf6634468668230fe6fc6a01270c35d22a0826`.

The ordinary Linux x86_64 build fetched these same public bytes without APK or
download-directory overrides. All **15** packaged host helpers, including the
clock helper, match source; both pinned images exist; archive/image/binary
checksums pass; the outer archive contains no private-key files. Its executable
reports 0.11.0. An isolated synthetic runtime check verifies all three new
capabilities, clock diagnostics, recipe partial edits, explicit day/time and
appointment location persistence across restart, and an authenticated update
offer/chunk containing the exact public APK. Complete local bundle SHA-256:
`f8f2bb314a7473baf1906c657c3a65e4bc350726ad736e277e6b0cce439f0c23`.
The GitHub release publishes four Android assets; the Linux bundle is built and
verified locally. Nothing here establishes deployment to the home Debian VM or
physical GrapheneOS profile forwarding, background delivery or camera behavior.

## 0.10.0 contextual categories and reminder time units — 2026-11-07

Optional item categories follow the containing list's template and subject.
Groceries and shopping have their own choices; household, food, errands, work
and personal lists each have relevant choices. General, untagged and custom
lists accept free-form categories. A shared generated vocabulary keeps Rust,
web and Kotlin consistent. Changing a list or moving an item preserves its
saved category until someone deliberately changes it. Category filters also
scope checked-item cleanup. Search, whole-item history, version restore and
content export/import preserve these labels.

Custom reminder timing uses an amount and minutes, hours, days, weeks, months
or years. Months/years use the saved calendar timezone, clamp month ends and
leap days, move DST gaps forward and choose the first overlapping time. Other
units count elapsed time. The native scheduler inverts each offset into a
bounded occurrence window; ten-year leads do not enumerate ten years of events.
Clamped occurrences retain distinct delivery/progress identities. Existing
minute-based reminders keep their wire representation and receipt identities.

Extended timing has an additive capability and full-rule projection. Older
clients receive their compatible alert subset, with no newer rule misread as
an at-start alert. Older reminder editors cannot erase newer timing; ordinary
content edits continue to work. New-feature edits queued against an older
server remain intact with a visible explanation. No protocol/schema minimum,
pairing identity or public APK signing-key change is required.

Executed here: **102 API/codec/planning cases**, **35 browser workflows**,
**116 Kotlin host tests**, **126 Python helper tests**, **17 Rust tests** and
clean clippy. Coverage includes transactional stale-parent/revision checks,
Unicode/bounds, unrestricted custom categories, retained labels after moves,
filtered deletion, import deduplication, legacy reminder projection/editor
fencing, month-end/leap/DST arithmetic, bounded long-lead recurring lookup,
and separate receipt identities for clamped occurrences.

The actual JNI → WireGuard → TLS → Rust integration check passed. On the
guarded disposable AOSP AVD, ItemContextDeviceTest exercised real grocery and
household category controls and unrestricted custom labels;
ReminderTimingDeviceTest exercised month/year controls, IME dismissal and
distinct saved rules. Both verified the durable queue and encrypted vault.
The same-signer **0.9.1/code 17 → 0.10.0/code 18** in-place APK update passed
with actual Keystore-backed keys, saved server/CA/PSK binding and an
unacknowledged task command retained; no uninstall, data clear or re-enrollment
occurred between versions.

Nine fresh synthetic web/native captures are indexed in
[WALKTHROUGH.md](WALKTHROUGH.md). These automated/emulator checks do not imply
access or deployment to the home Debian server, or physical GrapheneOS profile,
alarm-volume, background delivery or camera testing. Older phones need the
current APK before delivering the new calendar-based reminder timings.

Delivery verified: [v0.10.0](https://github.com/argentumseven/field-notes/releases/tag/v0.10.0)
publishes clean tagged source `d8163cf939c550ae808106cb5010327978fa30f4`,
Android code 18, using the unchanged public signer
`e5d93fbb7b98f02f94a3ed6c35ce35e64d07d4adadcc698fa7b0099c8e0fb488`.
All four public assets match the built release byte-for-byte; actual APK
signature/package/ARM64 verification passed. The latest-download metadata
matches this release. Public APK SHA-256:
`1d5002f8e1c403bfc24d262304088c1646275063595ededc53fdd0461977509e`.

The ordinary Linux x86_64 build fetched those same public bytes without APK or
download-directory overrides. Its packaged executable reports 0.10.0, advertises
both new capabilities through the authenticated head endpoint, serves the new
UI assets, and successfully persists a grocery category and year reminder in an
isolated synthetic runtime check. Its matching APK offer is available. All 14
packaged host helpers match source; both pinned image IDs exist; full bundle,
binary and image checksums pass. No private-key files enter the archive.
Local complete bundle SHA-256:
`81860ce70848da4f4c7839d28b8bc73b6975c9899ca46345d07f29ad4fb558d6`.
The GitHub release publishes the four Android assets; the Linux bundle was
built and verified locally, not uploaded as a public release asset.

## 0.9.1 live shopping, removal and scroll continuity — 2026-11-06

Open Android screens check immediately on resume and every three seconds,
independently of legacy Auto refresh preferences. A changed default network
reopens the app-contained transport with the same identity; background-off and
Pause preferences remain effective. Known pending changes, stale checks, offline
work, local drafts and retained conflicts have distinct visible status. Ephemeral
head-check timestamps do not cause encrypted-cache writes or widget regeneration.

Both clients expose individual item deletion and scoped Delete checked counts
independently of whether checked rows are visible. The phone durably queues
offline removals. Frozen item revisions prevent a concurrent amendment being
removed by an older confirmation. An ambiguously acknowledged web deletion
retries the same operation ID. List history is accessible nearby.

Web search keeps updating while focused, preserving cursor and text. Open forms
keep their drafts and show that newer changes are waiting. Automatic/manual
redraws preserve the page, sidebar and calendar timeline scroll positions;
management assembles asynchronous content before replacing visible content.

Executed here: **92 API/codec/planning cases**, **109 Kotlin host tests**,
**126 Python helper tests**, **15 Rust tests**, clean clippy, and the actual
JNI → WireGuard → TLS → Rust integration check, extended to stop/reconfigure
using the same private key and session. All **31 browser checks** passed,
including long-page automatic/manual refresh, calendar/management scroll,
search-time incoming amendments, lost-ack deletion retry and stale bulk cleanup.
The guarded AOSP AVD's ListExperienceDeviceTest passed incoming amendments
without manual sync, durable offline removal/reconnection, and filtered bulk
cleanup retaining other stores and unchecked items.

The same-signer 0.9.0/code 16 → 0.9.1/code 17 AVD update passed its seed and
verify phases with `adb install -r`, preserving the actual Keystore-backed phone
keys, saved server/CA/PSK binding and unacknowledged task command. No data clear,
uninstall or re-enrollment occurred between the two versions.

Fresh synthetic captures and their fixture limitations are documented in
[WALKTHROUGH.md](WALKTHROUGH.md). These checks do not establish physical
GrapheneOS roaming/background delivery, or deployment to the home Debian VM.
The remaining list UX findings are explicitly separated in
[LIST-UX-REVIEW.md](LIST-UX-REVIEW.md).

Delivery verified: [v0.9.1](https://github.com/argentumseven/field-notes/releases/tag/v0.9.1)
publishes clean source `b3ca0865bfb8844afd388abcad5e1501b4b13b04`, Android
code 17, with the unchanged public signer
`e5d93fbb7b98f02f94a3ed6c35ce35e64d07d4adadcc698fa7b0099c8e0fb488`.
All four downloaded public assets match the built release byte-for-byte.
Actual signature/package/ARM64 verification passed, and the latest link points
to this release. Public APK SHA-256:
`25c64c58cfe19c2a4a9e9774d17b170d94751e1f5dff040895cf955863d1a81c`.

The ordinary Linux x86_64 build, without APK/download overrides, fetched those
same public bytes. The packaged executable contains the current scroll/live-list
features and reports 0.9.1; host helpers match their source, the Debian guide uses
a version-independent bundle prompt, both pinned image IDs are present, and all
archive checksums pass. No private-key files are packaged. Local complete bundle
SHA-256:
`9c8dfa10f31e433b7e8a4279a31c9b62933183702e7e8b1fc7c6aedf050f19be`.

## 0.9.0 project continuity and reminder editing — 2026-11-06

Household/honey-do is independent of hierarchy: standalone tasks, standalone
task lists, direct project tasks and project-linked work lists all retain their
own identities. Reference/shopping lists do not count toward work progress.
Web and Android derive matching rollups from the complete project, independently
of visible filters. Project targets/coordinators are optional and do not rewrite
task dates or assignments. Holds/finish/reopen, parent moves, claims, import and
restore use server transaction guards; no cascade silently completes children.
Project history captures both sides of new moves. Manifest v2 preserves the
hierarchy while readers continue to accept v1, with repeated-import deduplication.

The shared web recipient selector no longer collapses multi-select into Only me.
Task, event and note choices survive save/reopen and exclude nonrecipients from
projected alert rules. Android editors now hand durable changes to workspace-owned
sync, which survives editor cancellation and waits for an overlapping poll.
This covers reminder settings, events, list items, notes and collections.
Grocery editors have no reminder/recipient/sound controls; legacy grocery reminder
rules remain stored but do not deliver alerts. List addition notices remain separate.

Verified: 92 API/codec/planning cases; all 26 browser cases (including a focused
workflow rerun for automatic opening of newly created task details); 103 Kotlin
host tests; 15 Rust tests plus clean clippy; 126 Python host-helper tests; and an
actual JNI → WireGuard → TLS → Rust integration case. Shared JS/Kotlin project
golden cases cover work/reference classification, counts, dates and group moves.
The dedicated AOSP API-35 AVD's ProjectsDeviceTest also passed real project/task
navigation, retained search context, recipient-chip save through the outbox and
Keystore-backed cache, and the absence of grocery reminder controls. The first
device run exposed the editor-cancellation bug; the corrected run passed.
Rolling-list reuse is checked for both reference and work lists.

Fresh synthetic web/Android captures are indexed in [WALKTHROUGH.md](WALKTHROUGH.md).
These are automated/emulator checks, not a deployment to the home Debian VM or
physical GrapheneOS profile, camera, VPN, background-delivery or alarm-volume tests.

Delivery verified: [v0.9.0](https://github.com/argentumseven/field-notes/releases/tag/v0.9.0)
publishes the clean tag `a73e0ee8a0acfb77b8f15df8b76831eba77f2542`, Android code 16,
using the existing public signer
`e5d93fbb7b98f02f94a3ed6c35ce35e64d07d4adadcc698fa7b0099c8e0fb488`.
The four public assets were compared byte-for-byte with the packaged release;
actual APK signature/package/ARM64 checks and the versioned/latest links passed.
Public APK SHA-256:
`334222c6ef71b683260a53d7587a2a940b99330ebc73b144570081b1b063d868`.

The guarded AVD installed code 15, seeded actual Keystore-backed phone keys,
server/CA/PSK binding and an unacknowledged command, then installed code 16 with
`adb install -r`. Both instrumentation phases passed without clearing data or
re-enrolling between versions. The ordinary Linux x86_64 server build, with no
APK override, fetched that exact public APK. Its bundle version, executable
project feature, current host-helper bytes and both archive checksums were
verified; no private-key files are packaged. Local complete bundle SHA-256:
`35188e1d82289f00d16d2c32c706d717c1366f5ccc0e99a2a9671d821334acc4`.

## 0.8.1 whole-item history — 2026-11-06

Web and Android History show one entry per item, grouping task comments and
attachments with their task. The server ranks each item's latest matching
activity before applying the stable page cursor. Attribute edits cannot repeat
an item across pages. Task/type/action filters include child activity as a task
change. The raw encrypted audit trail remains available underneath; existing
versions, indices and retention settings are preserved.

A selected version opens one whole item state, including unchanged content,
properties, comments and attachment names. Current/selected state switches and
optional settings/comparison disclosures replace the default field-by-field
wall. Only populated or active basic properties are displayed. A long list of
children has an explicit 20-entry preview boundary; every child is restored.
Whole-item restoration remains one fenced transaction. No per-attribute restore
operation was added. The state response uses the existing recovery authorization
and excludes internal restore identities, keys and image bytes.

All 85 API/codec and 23 Chromium workflows passed. After the final grouped-filter
and presentation refinements, all 24 history/recovery storage workflows and six
focused Chromium workflows passed again. Tests cover repeated attribute edits,
child grouping, stable pagination while new edits arrive, actor/type/action
filters, unchanged saved fields, child content, switching selected/current states,
whole-item restoration and the existing failure/replay/permission paths. All 15
Rust tests and Clippy passed. All 97 shared Kotlin tests and release lint passed.
The actual dedicated Android AVD check passed through history filtering, offline
Keystore-backed cache, the real keyboard, whole-state preview, scrolling to the
restore action and applying the restored snapshot without changing phone keys.
It uses Rust-generated synthetic responses through the documented test transport;
the real API workflows separately verify server transactions. Two focused recovery
checks also verify that a missing parent's restored name comes from the selected
state rather than its newer tombstone. Screenshots use only the synthetic
household. No physical GrapheneOS or home-server deployment is implied.

The public [v0.8.1 APK](https://github.com/argentumseven/field-notes/releases/tag/v0.8.1)
uses clean tagged source `f930f0eab04b8609e7429f37d72f598bff8cc34a`, code 15,
and the unchanged public signing certificate. The [verified publication workflow](https://github.com/argentumseven/field-notes/actions/runs/42267193394)
succeeded. Fresh versioned/latest downloads and all four public assets match
byte for byte after actual signature/package inspection. APK SHA-256:
`ecde58c5e3dfefb97847b379354bcb5b4dbeec188ec44f4a0f7aff2c253f8e90`.
The ordinary Linux x86_64 build fetched those exact public assets; archive,
program/helper and download checks passed, followed by three whole-item API
workflows against the packaged release executable.

Packaging also exposed a repair-helper admission list still limited to 0.8.0.
Main includes the 0.8.1 correction, preserving identical-executable/Android-code
requirements for explicit same-version runtime recovery and refusing ordinary
same-version replacements or downgrades. Its regression now reads the actual
release version instead of hardcoding 0.8.0. All 126 Python checks passed. The
Android APK is unchanged by this host-helper correction.


## 0.8 planner refinements and content recovery — 2026-11-06

Item versions and named whole-content checkpoints are available from History on
web and Android. Full before/after states and normalized attached images are
encrypted in SQLite; image bytes are shared by retained digest. Version indices
remain monotonic through restores and manual purges. Restores preview readable
field differences, append new canonical revisions, and preserve newer retained
states for rolling forward. Checkpoints preserve current accounts, roles, keys,
pairings and security settings. Explicit full backup restoration still uses its
existing fresh-epoch/session-revocation policy.

Preview fences cover source, current scope including child changes, session,
people directory, generation and retention revision. Missing parents can be
recovered without replacing an existing parent. Revived task ancestors receive
the same child-write fence as explicit task restores. Queued stale comments and
uploads are rejected for review. The phone saves the exact recovery request before
sending it; lost replies and failed local confirmation writes remain visible,
with result lookup and exact-request retry. No restore is automatically retried.
Corrupt saved states remain labeled unreadable while other versions can load.

LAN Management controls automatic versions, age/count/size bounds and manual
purging. Limits protect newly captured before/after states until commit; capacity
failure rolls back the edit. Retention revisions invalidate connected history
caches; disconnected copies require reconnecting. Recovery copies do not replace
canonical content or provide forensic disk erasure. Full versions begin with 0.8
changes, capturing an older item's baseline before its first new edit.

Every year and custom Years retain the existing monthly interval wire format.
Cross-language previews cover leap days, skipped dates, inclusive end dates,
long intervals and the supported upper year. Existing series edit in their saved
timezone. Rolling lists can reuse checked original items with frozen selections,
revision arbitration and preserved images/comments. Household consolidates the
stored Home/Honey-do choices without rewriting them. Controls share an 8 px/dp
radius. Widget day links explicitly select Day; statuses show cache age and the
Live/Periodic/Manual update choice. Bounded automatic catch-up respects pause and
manual settings. Successful rendering clears only the widget's own stale error.

New full backups use authenticated 256 KiB frames, compact SQLite snapshots and
streamed downloads/files. Header length, stream identifier, frame order and full
ending are authenticated/checked; truncation, corruption, wrong keys and appended
data fail. Private restore staging validates the database before publishing a new
destination; existing directories are refused. Older backup files remain readable.
Large legacy single-record restores retain their original memory characteristics.
Upgrade disk preflight includes temporary snapshot/encrypted copies and separate
data filesystems; compatible 0.8 runtime recovery is supported.

Automated checks include **83 API/codec workflows**, **23 Chromium workflows**,
**95 shared Kotlin tests**, **126 Python checks**, **15 Rust tests**, Clippy and
Go vet/race. Recovery cases exercise long untruncated notes, deleted task/parent/
comment/image restoration, image sharing/garbage collection, backward and forward
checkpoints, author/viewer permissions, child/directory/session fences, replay,
expired previews, concurrent restore winners, SQL transaction failure, actual
storage exhaustion, age-out, restart, encrypted backup restoration and portable
export/import after restore, including damaged live-image repair and retention-only
background invalidation of open web recovery dialogs. Backup tests include old-format compatibility and
reject corrupt or truncated archives before destination publication.

A real 0.7 executable/data directory upgraded to 0.8 with its existing session,
epoch, content, recovery key and network configuration intact; the first new edit
captured a usable baseline. The immutable app image backed up and restored an
actual **200 MiB database of bounded records on disk under a 128 MiB memory
limit**, nonroot, with a read-only root, no network/capabilities, preserving full
recovery rows and key bytes. No home-server deployment is implied.

Actual dedicated-AVD checks passed for month/week launcher hosting, automatic
offline repaint and day links from a previous Month view; native history,
restore preview/apply, real keyboard and Keystore-backed cache; and real Android
JNI → WireGuard → TLS → Rust recovery with replay and stale-child rejection.
A same-signer **0.7/code 13 → 0.8/code 14** install-over update preserved actual
Keystore-backed keys, binding and queued work without re-enrollment. The capture
harness keeps the disposable AVD awake during long launcher checks; the shipped
app keeps screenshot protection. Physical GrapheneOS profile forwarding, DND,
VPN coexistence and battery timing remain separate device checks.

The public [v0.8.0 release](https://github.com/argentumseven/field-notes/releases/tag/v0.8.0)
was built from clean tagged source `bb11687a59c2e6a1b159052b2bd6fa67618c9b21`
and published through the successful [verified prebuilt workflow](https://github.com/argentumseven/field-notes/actions/runs/42227027112).
Its ARM64 release passed lint, all 95 shared checks, package/signature inspection
and 16 KiB alignment. All four freshly downloaded public assets match the build
byte for byte; the README's latest link resolves to this version. The public
signing certificate is unchanged from 0.7. APK SHA-256:
`b48e0e15e0af1dd76ce688edbebe8de4441975791623bf6e2cad69c6df183095`.

The ordinary Linux x86_64 server build fetched that public release without APK
overrides and produced its offline app/gateway archive. Packaged program/helpers,
all four APK assets, source metadata and archive checksums were verified.
The 0.7 persistence migration and 200 MiB/128 MiB container backup/restore checks
were repeated successfully against the final release programs/image. Signing
and server identity keys are absent from the bundle. Updating the home server
and physical phone still requires their normal operator-controlled update steps.

[Recovery guide](RECOVERY.md) · [Planner comparison](PLANNER-COMPARISON.md) ·
[Updated screenshots](WALKTHROUGH.md).

## Upgrade rollback images and failure reports — 2026-11-06

Normal upgrades check both installed immutable image IDs before a source build
and again after staging, before stopping services. Missing or invalid images
produce recovery guidance without changing service state or household data.
Explicit recovery still uses the verified target image and retains a raw
database/WAL snapshot. Backup containers disable image pulls. Compatible 0.6.0
and 0.7.0 installations are recognized; same-version executable replacement,
downgrades and unreviewed schema/protocol changes remain refused.

Failures record a root-private `failure.json` with the failing step, bounded
reason, program/settings restoration status and actual owned-container health
result. The old service's failed-start limit is reset before restart. A failed
rollback is reported without claiming successful restoration. Arbitrary command
output, arguments, keys and content are excluded. Accepted database writes are
never replaced with the pre-upgrade backup.

All **125 Python checks** passed, including **23 upgrade checks** covering either
missing old image, pre-build refusal, image loss during preparation, backup and
readiness failure, command-output redaction, incomplete rollback, retained data
and identities, explicit recovery and recent versions. A real local Docker check
confirmed both missing-image guards, then created an encrypted backup in the
immutable app image as a nonroot UID with networking disabled, a read-only root
and no capabilities. Original logical rows and master-key bytes were unchanged;
a separate fresh-directory restore retained content, password hashes and history
and performed its expected epoch/session reset. The upgrade itself never restores
over the live data directory. No privileged systemd upgrade was executed in this
cloud environment. Android and the public signed APK are unchanged.
The Linux x86_64 offline server archive rebuilt successfully; its checksum,
packaged upgrade helper and unchanged public APK bytes were verified.

## Web sidebar expansion follow-up

Expanded navigation now flows above its footer in one desktop sidebar scroll
area, rather than squeezing navigation into a separate area above a fixed
footer. Sections do not shrink; collapsing releases their space. A stable
scrollbar gutter avoids width changes. Navigation and refresh retain the sidebar
scroll position and open Preferences. The narrow web layout stays in page flow.
This is a web-only change; Android code, APK version/signature, stored household
data and the protocol are unchanged.

The new real-server Chromium regression fails on the previous desktop layout
and passes on the fix. It measures group/content/footer bounds at 1440×1080,
1280×800, 1024×600 and 800×420, checks reachable controls without moving the
planner, exercises keyboard expand/collapse/navigation and refresh, and checks
390 px and 600 px web layouts for overlap and horizontal overflow. New
[sidebar captures](WALKTHROUGH.md#expanding-sidebar-sections) include the actual
browser scrollbar. All **18 Chromium workflows** passed with
`source scripts/dev-env.sh; npm run test:browser`; the focused sidebar checks
also passed with native scrollbars enabled. The optimized Linux server build
passed. No home-server deployment or physical-phone check is implied.

## 0.7.0 household change history — 2026-11-05

The active implementation is **0.7.0 / Android code 13**, protocol 1 and schema 2.
Library → History is implemented on web and Android, with actor/server time,
item/list context, readable Previous/Now differences, action/person/type/date
filters, search across recorded values, stable sequence paging and read-only
links to current items. Imports are distinguished from ordinary changes.

Encrypted details commit in the content transaction, including cascaded comment
and image removals. Replayed receipts create no duplicate events; failed history
writes roll back content. Older metadata is labeled without fabricated field
values. Clear removes prior content details and fences caches/cursors; encrypted
backup/restore retains the restored trail under its new epoch. Detail retention
is bounded and surfaced in the UI. Android caches up to 200 loaded entries in
its encrypted vault; history cannot displace queued edits or cross account,
workspace-generation or epoch boundaries.

The full checks passed: **66 API/codec**, **16 Chromium workflows**,
**83 shared Kotlin**, **117 Python**, **12 Rust**, Clippy and Go vet/race.
New coverage includes history persistence/encryption, actor attribution,
before/after values, deletion/cascades, receipt replay, fixed-watermark paging,
bounded searches through empty pages, date filters, permission failures,
transaction rollback, long-note excerpts, backup/restore, cache/account fences,
keyboard focus, stale browser replies, XSS handling and visible connection errors.
The native keyboard check exposed fixed controls exhausting the remaining
viewport when a warning and the IME were visible. History now puts its controls,
notices and trail in a single scrollable viewport.

The actual Android History check passed with a real Rust-generated synthetic
seed, native Compose interactions and Keystore-backed cache persistence. It
exercised before/after details, pagination, filtering, cached reads after a
connection failure, both themes, and the real keyboard with an offline warning.
The complete history footer remained reachable above the IME. Scaffold content
now consumes its supplied insets before applying IME padding, avoiding a second
reservation for bottom navigation space. The import check also confirms that
receipt retries add no extra audit entries and that the authenticated importer
is the recorded actor, rather than an identity supplied by the archive.

Both actual Android native/manifest checks passed, including a History request
through JNI → WireGuard → pinned TLS → Rust. The same-signer
**0.3.2/code 5 → 0.7.0/code 13** upgrade retained its actual Keystore-backed
identity, server/CA/PSK binding, original preferences and pending task command.
No pairing purge, data copy or re-enrollment occurs between its upgrade phases.
The public [v0.7.0 release](https://github.com/argentumseven/field-notes/releases/tag/v0.7.0)
was built from clean tagged source `62a3f8219bb9196ab53f645efded97ef3e2b1fa5`
and published through the [verified prebuilt workflow](https://github.com/argentumseven/field-notes/actions/runs/42137186584).
The final ARM64 release passed lint, all 83 shared checks, actual signature/
package verification and 16 KiB page alignment. A fresh public download passed
independent certificate/package verification. All four public files match the
clean-tag build and offline Linux server archive byte for byte; no signing key
is bundled. The public certificate remains unchanged. APK SHA-256:
`293dbd44284f765e3ba6b4ffa7538aec3b57c9943d0c9ba024ef71a15d962b7e`.
No home-VM deployment or physical GrapheneOS test is implied by these checks.
See [HISTORY.md](HISTORY.md) and the [walkthrough](WALKTHROUGH.md).

## 0.6.0 household planner and portable content — 2026-11-05

This historical baseline is **0.6.0 / Android code 12**, protocol 1 and schema 2.
New task/event/note reminders default to Everyone without changing existing
personal choices. Typed rows, task filters, store-grouped list subviews, Later
notes, irregular recurrence, granular household-addition notices and separate
list notification switches are implemented on the shared client/server model.
Phone and website headers reuse the APK launcher artwork from one source.

Sunday-first web headings, all-day rows and hourly columns share one native
scroll viewport. Chromium measures every column with 12 px and 28 px scrollbars
at three scroll positions; corresponding edges differ by less than one pixel.
Month cells form a continuous grid. The shared recurrence checks cover selected
weekdays, alternating weeks/days, individual/excluded dates, month-end skips,
spring clock gaps and the exact second autumn clock occurrence of a series anchor.

Portable `.fieldnotes` files use bounded ZIP content inside framed AES-256-GCM
encryption with PBKDF2-HMAC-SHA256 (600,000 iterations). Android and WebCrypto
interoperate, including Unicode passphrases. Export includes cached offline
images, queued changes and retained alternatives; connection credentials and
keys are excluded. Imports validate complete files before staging, preserve
current records, reuse source/content matches and retain differing versions.
Receipt retries, recovered-copy restoration and repeated imports do not multiply
active items. Normalized images retain a server-computed original digest to
avoid false conflicts on re-import. Claims are not resurrected.

Executed here: **60 real-process API/codec checks**, **13 Chromium workflows**,
**79 shared Kotlin checks**,
**117 Python checks**, **11 Rust checks**, Clippy, and Go vet/race. Coverage
includes encrypted persistence/restart, simultaneous claims, revision and reset
fences, HTTP/2 split cookies, permission failures, corrupt/truncated archives,
image decoding limits, stale import previews, mapping/deduplication, transaction
rollback, viewer restrictions and original-signer update selection. A queued
phone import is rejected before transmitting content if its account/workspace
changes while waiting for the connection fence.
Archive person mapping also preserves creator-only and legacy assigned-task
audiences instead of changing them to the importing person or Everyone.

Actual Android checks exercised Keystore, enrollment/editor/IME layout, widgets,
update verification and document export. An Android document export decrypted
with WebCrypto and imported twice into the real Rust server with its offline
image and retained command, without duplicate records or image conflicts.
Two actual JNI → WireGuard → TLS → Rust checks include fixed portable routes.
A same-signer code-5 → code-12 APK upgrade preserved the actual Keystore-backed
identity, server binding and unacknowledged command without re-enrollment.
The refreshed general gallery contains 30 distinct Android captures, including
both themes, the APK/header icon, typed rows, store groups and the real launcher.
Its configured-phone fixture uses a genuine bundled-APK offer and a notification
permission grant. Focused calendar, open-keyboard and notification-shade captures
are separate from that general gallery.
The refreshed enrollment capture uses the real keyboard: the phone name, trust
checkbox and request action remain visible above it. Its fixture closes the
system notification shade before touching the app.
Android startup traces also exposed WorkManager's automatic provider doing
scheduler initialization on the main thread. The app now supplies its existing
default configuration on demand; catch-up scheduling, cancellation and widget
work requests initialize on IO. Worker/database names and saved jobs stay intact.
The packaged-manifest check rejects the automatic initializer, and real Android
delivery checks exercise persisted periodic work through the on-demand path.
The private connection service posts a minimal native foreground notification
in `onCreate`, before its full controls and other setup. Automatic UI startup
waits through the first frame before starting Android's foreground deadline.

The real offline container stack passed direct TLS 1.3, private enrollment and
APK download, nonroot/read-only/capability/memory restrictions, separated private
key mounts, denied management/LAN/peer access and outbound/DNS drop-counter
checks. Password changes retained pairing, explicit phone sign-out revoked its
session, and scoped removal left no owned deployment resources. The public
signing certificate remains unchanged; compatibility APKs use only an explicitly
provided original household key and never replace public release bytes.

The public [v0.6.0 release](https://github.com/argentumseven/field-notes/releases/tag/v0.6.0)
was built from clean tagged source `a60005676fc0b8454768e20e704b845518338f3a`.
Its [hosted publication check](https://github.com/argentumseven/field-notes/actions/runs/42101105472)
passed. The final ARM64 release passed lint, all 79 shared checks, actual APK
signature/package verification and 16 KiB alignment. A fresh public download
independently passed the pinned-certificate check; all four published files match
the tagged build and offline Linux bundle byte for byte. APK SHA-256:
`047d4f14e002c44dbc2c91a4f43d49f90ebc8ed357f618f7eac748e5e7a5547f`.

Evidence logs are under `dist/planner-v3-*`; these are local build artifacts,
not committed household data. Current screenshots are linked in
[WALKTHROUGH.md](WALKTHROUGH.md). No deployment to the home VM or physical
GrapheneOS profile/sound/lockdown test was performed here. Emulator tests do not
establish exact background timing or cross-user sound forwarding. Native iOS
and desktop packages remain deferred.

## Shared public APK signing identity — 2026-11-04

Standard source installation and server packaging now download the version-pinned
public GitHub APK instead of generating a local Android signing identity. AOSP
`apksigner` verifies the actual APK, `aapt` checks package/version/code/ARM64, and
the public certificate must match `release.json`. All four published download
files, including the original source metadata, are preserved byte for byte.
`--server` setup prepares Java/build-tools verification without building Android
or requiring an NDK/signing key. Public-signed source upgrades use that path;
custom development upgrades retain their original key and still reject signer
changes before stopping services. Custom release builds continue to use explicit
matching bundles. Public publication also checks the pinned identity, and the
ephemeral one-off release workflow has been removed.

Executed here: **116 Python checks passed**, including interrupted/oversized/
corrupt downloads, forged signer metadata, preserved custom keys, a public source
upgrade with no local key, metadata-preserving rebundling, and refusal to publish
another signer. The real v0.5.0 release downloaded successfully and independently
passed signature, package and pin verification. Its APK SHA-256 remains
`f6730915bbc5195fcb450947cdd76b8007d17eb6cc3507c15e2890092ad18962`.
A tampered APK with a recomputed checksum failed actual signature verification;
a valid APK re-signed with a disposable test key failed the public certificate
pin. The test key was removed. Verifier-only SDK setup and the complete default
Linux x86_64 offline container/server bundle build passed. The resulting bundle
directory and tarball contain the exact published assets with the original
`23b0b4cba30360e36bfd5772d52c7d676d0849bb` source metadata and no private
signing key. Both inventoried Docker images exist, and the compatible bundle
upgrade validation passed. Shell syntax and release-version checks passed.

Logs: `dist/signing-distribution-python-tests.txt`,
`dist/signing-distribution-real-apk-tests.txt`,
`dist/signing-distribution-verifier-setup.txt`,
`dist/signing-distribution-server-build.txt`, and
`dist/signing-distribution-bundle-tests.txt`.
These are packaging/signing changes using the existing 0.5.0/code-11 APK.
Deployment to the home server and a physical phone upgrade were not run here.
An already installed APK with a different certificate still requires its
original signing key; no pairing reset, uninstall or storage clearing is used
to conceal that mismatch.

## 0.5.0 personal reminders, loud alarms and week widgets

This historical baseline is **0.5.0 / Android code 11**, protocol 1 and schema 2.
App credential locking is optional, independent of notification and widget
details. New installations show details while unlocked; existing explicit off
choices are preserved with a visible migration choice. Lock-screen disclosure
has separate default-off controls. Server authentication and vault encryption
are unchanged.

Reminder extensions add person/everyone/assignment recipients, eight original
tones, longer local alarm playback, and per-phone/per-rule temporary alarm-volume
override. An encrypted restoration lease precedes volume changes, supports
process recovery and respects a later manual choice. Stop/completion/deletion/
recipient or authorization changes cancel active playback. DND stays with
Android. A real seven-day launcher widget adds agenda rows and occupied-time
bars; all widget visibility settings are independent of app locking.

Head/update metadata advertises protocol/client bounds and features. Compatible
drift keeps core sync; unsupported options retain edits with an explanation.
APK preparation runs in foreground/background, checkpoints complete chunks and
rechecks whole-file/package/hash/signer before Android consent. Missing bundles
do not stop core notes; transfer and compatibility failures remain visible.

Executed automated checks: **11 Rust**, clippy, **40 real-process API**, **10
Chromium**, **66 shared Kotlin**, **106 Python**, and Go vet/race. These include
recipient projection/replay/legacy-editor fences, invalid recipients/playback,
older APK bundles, protocol bounds, additive metadata, retained offline edits
and independent visibility. Android and final artifact verification follow.

Nine Android feature checks passed on the guarded disposable AVD: actual local
MediaPlayer start/Stop, temporary-volume restoration/manual precedence, Android
refusing a restoration with the recovery record retained, full notification
extras and lock-screen redaction, all bundled sounds, encrypted alarm receipts
and schedule refill, signed APK rejection cases, interrupted download/controller
restart resumption, and widget visibility independent of app locking. A real
same-signer **0.3.2/code 5 → 0.5.0/code 11** installation retained the actual
Keystore-backed phone keys, server/CA/PSK binding and queued task command without
clear, uninstall or re-enrollment between its seed and verification phases.

The actual seven-day launcher widget passed offline automatic repaint and date
navigation. The native local-clock/reminder/visibility editor check passed and
captured the focused controls. The software emulator exceeded the runner's fixed
45-second synchronous activity-launch budget; the host fixture now observes the
actual resumed task instead. The sound-picker fixture was selecting two sibling
switches; explicit accessible labels now identify the alarm controls. Product
timing, authentication and scheduling behavior were not relaxed for those checks.
The actual System UI notification-shade check also passed with its card expanded:
person, full local schedule, notes and trusted creator/editor timestamps are
visible. The compact summary puts the person and time ahead of a long date.
The capture stops the synthetic minute-by-minute schedule after verifying
delivery/refill/receipts and expands one delivered card; it does not draw a
notification imitation. Slow System UI and collapsed automatic grouping had
otherwise produced a launcher/summary capture. All walkthrough image links resolve.

The ARM64 release variant passed lint, shared host checks, signature/package,
manifest/native and 16 KiB alignment inspection. The matching offline Linux
x86_64 bundle built successfully. The real Docker/WireGuard/pinned-TLS fixture
passed the complete APK transfer, enrollment/approval, actual Go C bridge,
task/image writes, source restrictions, denied management/SSH/peer/LAN access,
app-UID outbound/Docker-DNS drop counters, preserved password/pairing/master/CA
material, root recovery through a private ancestor and owned resource removal.

The public **v0.5.0 / code 11** release was built from clean source
`23b0b4cba30360e36bfd5772d52c7d676d0849bb` with the retained public certificate.
[Publication run 41771551083](https://github.com/argentumseven/field-notes/actions/runs/41771551083)
succeeded. All four public assets were downloaded and matched the locally
verified package byte for byte. The README's latest-APK link matched as well;
the downloaded APK passed signature, manifest/native and alignment checks.
APK SHA-256: `f6730915bbc5195fcb450947cdd76b8007d17eb6cc3507c15e2890092ad18962`.
The matching server bundle SHA-256 is
`b4ec1c768c590e8ab8c870069ab4fb77eae6fd8afaaddd15994c5ac04d780f4b`.

Physical GrapheneOS cross-user acoustics, Doze/battery delivery and Android
installer consent remain hardware checks. The AOSP fixture clamps an unmuted
alarm stream to a minimum of 1 and refuses alarm-stream muting; its checks
validate restoration of the lowest state it actually accepts. A genuinely
zero/muted alarm stream therefore remains a physical-device check. No cloud check deploys the user's
home Debian instance. Prior release records follow for comparison.

## 0.4.0 calendar, reminders, rolling lists and recovery

The previous implementation is version **0.4.0**, Android code **10**, protocol 1
and database schema 2. Calendar opens first. Local date/clock controls replace
ISO/offset entry; start-only events have no invented duration. Desktop day/week
and native day timelines arrange overlaps. The real Android Calendar launcher
widget has a month grid, busy-time strip, date links and automatic cache updates.
Its default fits four launcher rows; a five-row request was rejected by the
four-row fixture launcher. Private previews/redaction remain the default.

Lists now have general, grocery, online shopping and custom field presets,
optional prices/links, attached-image previews, rolling checked-item removal,
and single full creation forms. Hidden fields retain their data. Navigation is
grouped; task/list/note rows expose trusted creator/activity names and times.
There are **no retailer preview requests**. The app and website have new light
and dark themes, clear creation controls and an auto-refresh option.

Per-record reminders support independent sound/importance/relative level,
multiple alerts and daily/weekly time windows, including undated tasks.
Offline scheduling keeps a bounded rolling alarm set, encrypted delivery
receipts and progress to avoid replay or a catch-up burst. Original generated
tones use stable resource-name URIs across APK updates. Android volume/DND stay
authoritative. GrapheneOS full-user forwarding uses the receiving profile's
**Other users** system channel, which does not preserve each source tone.
Physical cross-profile sound, prolonged Doze and camera optics remain unrun.

Compact QR payloads preserve the same pinned CA, keys, expiry and approval
requirements while avoiding double encoding. Both camera and scoped offline
image import use ZXing's normal detector plus a multi-finder retry for dense
patterns. Browser element captures decode exactly at desktop/narrow widths and
all four rotations across three independently issued invitation fixtures.
Neither camera frames nor imported QR images are retained or uploaded.

Executed checks:

- 11 Rust tests and clippy; 37 real-process Node API/planning/security checks;
  10 Chromium workflows; 60 shared Kotlin host checks; Go vet/race checks.
- 106 Python checks, including runtime-recovery refusal/backup/rollback,
  same-version executable/code guards, retained accepted writes, moved image
  tags, dangling image cleanup and primary-error preservation. Those host
  lifecycle cases use temporary trees/controlled commands, not real systemd.
- Actual AOSP device checks for compact QR image decoding, real Android
  reminder channels/AlarmManager delivery and receipts/refill, local ten-AM
  editing, repeat-window/sound controls, and private RemoteViews. The actual
  launcher month repaints an offline edit/new day without a refresh tap and
  opens the chosen date. Launcher tests use a normal app task; ActivityScenario
  could not reliably own the external launcher's lifecycle during teardown.
- A real same-signer **0.3.2/code 5 → 0.4.0/code 10** APK installation preserves
  the existing Keystore-backed phone keys, saved server/CA/PSK binding and queued
  command. No clear, uninstall or re-enrollment occurs between those phases.
- ARM64 release variant: lint, signature, manifest, JNI/native and 16 KiB checks
  pass using the retained public signing certificate. The matching Linux
  offline bundle builds successfully.
- The complete real Docker/WireGuard/TLS fixture passes with the matching APK:
  source-bound enrollment/approval, the actual Go C bridge and installer CA,
  full private APK transfer, normalized image/task writes, nonroot/read-only
  runtime, blocked app-UID outbound/Docker DNS, denied management/SSH/LAN/peer
  access, retained password/CA/master material and scoped resource removal.
- Browser gallery and 29 Android gallery captures now show the current design.
  Android's completed first-half captures were retained while the guarded
  library/light continuation passed, then all 28 distinct app views and the
  actual launcher icon were collected. Ambiguous name/preset and price/switch
  selectors in the fixture were narrowed; no product behavior was relaxed.
  The local-clock, reminder editor and actual dynamic month-widget captures
  come from the separate passing device checks above.
- Refreshed the actual Upcoming launcher widget, Android notification shade
  and real open-keyboard enrollment examples. All three targeted checks pass.
  Cold software-emulated Compose layout exceeded the runner's fixed 45-second
  synchronous launch budget, including an input timeout in the older fixture.
  The external-host checks now launch normal app tasks and wait for the actual
  visible window before requesting interaction. Product timing/protection is
  unchanged. The notification check exercises real cached alarms, audible/private
  channels, receipt deduplication, the delayed profile-test alert and stopping
  the foreground service despite a failed preference write.

During this work the user's Debian manager failed because its pinned image ID
was missing. Recovery with its newer saved bundle was initially blocked by the
old-image backup requirement. The explicit recovery path now uses the verified
target for the encrypted backup, first retains quiesced encrypted database/WAL
bytes, preserves identity/network settings, and finalizes a partial installation
only after readiness. The user confirmed the home web manager became reachable
again. That restored its older built UI; it did not deploy this redesign.
See [runtime recovery](RUNTIME-RECOVERY.md), [calendar](CALENDAR.md),
[reminders](REMINDERS.md) and [lists](LISTS.md).

The **v0.4.0** public release was built from clean tagged source
`9afc5eba5c5fccb0b635bf8f833f976d2029e783`, ARM64 release variant/code 10,
with the retained public signing certificate.
[Publication run 41647411814](https://github.com/argentumseven/field-notes/actions/runs/41647411814)
succeeded. All four public assets were downloaded and compared byte for byte
with the locally verified package. The README's latest-download APK also
matched; the downloaded APK passed signature, manifest/native and alignment
inspection. SHA-256:
`8f6c7c3cf71a48d1c95f6326eef8e2e499061dfbb491f2d734710621a7adae3a`.
The final matching offline server bundle passed the complete real Docker,
WireGuard and actual-Go TLS fixture again with these published APK bytes.
These are published artifact checks, not physical GrapheneOS results. The
previous delivery records follow for comparison.

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

The `v0.3.6` release was built from clean source
`7adfcf9c76223a03ae95803d30d3374374fd2970` (ARM64 release variant, code 9,
retained public signing certificate). [Publication run 41553197199](https://github.com/argentumseven/field-notes/actions/runs/41553197199)
succeeded. The four public release assets and latest-download APK matched the
locally verified bytes; the downloaded APK passed signature and manifest/native
inspection. SHA-256:
`edf9a50e40ba63536a6688ec65484bcdda77bcd5a2ab45869264fdf47a168f29`.
The final matching server bundle passed the complete Docker/WireGuard/real-Go-TLS
fixture again, including private APK transfer and scoped resource removal.
No signing key is present in the payload or release assets. Operators with a
different original APK signer must preserve their paired server's build identity.

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
