# Updates without re-pairing

The paired server selects the phone APK version. **Settings & widgets → App
updates** shows the installed version and the version that server offers. Field
Notes checks automatically in the foreground, the private background connection
and catch-up worker, normally once per six hours. A changed recommendation can
trigger an earlier check; failures back off for fifteen minutes. **Check for
updates** checks immediately. **Automatically prepare app updates** defaults on:
the verified download is prepared in the background, and a notification offers
review/installation. Android still requires installation consent.

If the installed app predates 0.3.2, it does not yet have this screen. Download
the matching APK from the updated server and install it **over the existing app**, with the
same signing key. Do not uninstall or clear storage. Later updates use the flow
below.

Choose **Download update**, then **Install update**. The download uses the same
source-bound session, pinned TLS and app-contained WireGuard endpoint as notes.
It works away from home; the management website stays LAN-only. Android asks you
to allow installations from Field Notes if needed, and shows its normal install
confirmation. No silent installation, device administration, Google store or
Google service is involved. You can decline or revoke installation permission
and keep using the planner.

The app verifies the complete SHA-256, APK cryptographic signature using AOSP
apksig, package ID, version/code, Android requirement, ABI and the installed
signing certificate. It checks the server's current offer again immediately
before opening the installer. Missing chunks, changed offers, revocation,
connection pause, cancellation, corrupt files, wrong signers and downgrades stop
the update. They do not reset enrollment or discard edits. Downloads use bounded
256 KiB chunks with a 150 MiB artifact limit. Complete chunks resume after a
disconnect or process restart. A changed artifact/server/user starts fresh
software bytes. The complete APK is hashed and signature-verified again before
handoff. Explicit Cancel discards the partial software file after the current
bounded request returns. The update cache is private and excluded from backup, and Android removes
it on normal uninstall. A nonexported FileProvider grants the installer read
access only to the staged software file.

An in-place Android update preserves app data, the Keystore alias, phone private
key, PSK, pinned CA/server binding and pending edits. The app never calls reset,
clear-storage or uninstall to update. Controlled **0.3.1 → 0.3.2**,
**0.3.2 → 0.3.3**, **0.3.2/code 5 → 0.4.0/code 10**, and
**0.3.2/code 5 → 0.6.0/code 12**, and
**0.3.2/code 5 → 0.7.0/code 13**, **0.8.1/code 15 → 0.9.0/code 16**, and
**0.9.0/code 16 → 0.9.1/code 17**, and
**0.9.1/code 17 → 0.10.0/code 18**, and
**0.10.0/code 18 → 0.11.0/code 19** APK upgrades
were exercised on the disposable AVD with the actual Keystore-backed vault and
queued task command. Physical GrapheneOS installation/profile checks remain
separate. Android app code can be shared across full user profiles; their vaults
and keys remain separate, and Android decides which user may install an update.

## Compatible version drift

Server and APK release numbers can differ while their core protocol remains
compatible. The server reports its minimum/recommended client and supported
features. A newer app can keep syncing with an older server; unsupported editor
controls stay disabled. A retained edit requiring a newer feature is explained
in Retained changes. Incompatible protocol/minimum requirements stop uploads
with a persistent warning and preserve the cache, outbox and keys. The current
server recommends code 23 and supports the code-10 core during rollout. Update
older clients before relying on repeat-event expansion and its reminders.
Upgrade older phones to 0.9.1 for immediate/three-second foreground refresh,
automatic default-network reconnection and visible stale/queued status. Updating
only the server cannot update an older phone's refresh behavior.

0.10 adds optional item categories under the advertised `item-context-v1`
feature. Older phones can still add and amend core content without overwriting
those labels. A new phone connected to an older server disables new category
controls; a previously queued edit using that field stays in Retained changes
with its full payload. Update the server and explicitly retry it. No pairing
or signing identity changes are needed. Content files containing these new
labels need the newer server to import them; unsupported fields are rejected
instead of silently removed. Files without the field keep their prior import
identity, including an explicitly empty optional label.

`reminder-timing-v1` adds amount/unit timing up to ten years. Current phones
read the complete `reminder_timing_rules`; older clients receive only the
compatible legacy alert subset in their existing fields. Unsupported leads are
never passed off as at-start alerts. An older reminder editor receives a
revision-safe rejection if it would erase those newer rules, even when it sends
recipient settings. Ordinary task/list/event edits still work. Update the phones
before relying on month/year reminders; a server update cannot add that scheduler
to an older APK. New timing queued against an older server is retained intact.

A missing/corrupt/mismatched APK bundle or failed transfer produces a visible
status; it does not take the notes server offline. A phone newer than the offered
APK keeps its current version and compatible sync. It is never downgraded to
match a server string. Metadata/signature/network failures are surfaced in
Settings, the app's persistent status, widget footer and a notification when
Android permits posting. The system cannot report a new error while force-stopped.

## History in version 0.7

Upgrade the paired server and install its matching APK to add **Library →
History** on web and phone. Existing content, users, pairing, keys and queued
changes are preserved. The additive encrypted history table starts recording
new before/after details; older event metadata remains available with an honest
label. Server backups include history. Phone caches are fenced by account,
workspace generation and server epoch. No export/reimport or new enrollment is
needed. See [HISTORY.md](HISTORY.md).

## Planner version 0.6

Upgrade the paired server, then install its offered APK. This adds Everyone
defaults, irregular repeat events, typed/filterable views, Later reminders,
store-grouped groceries, addition notifications and portable content files.
Existing personal audiences, explicit field choices, content, pending changes
and identities are preserved. Archives are additive imports, not an upgrade
step; an upgrade does not require exporting and reimporting your workspace.

## Earlier planner version 0.5

Upgrade the paired server for recipient controls and longer alarm settings.
The phone's updater follows that server's signed APK bundle. The Week widget,
optional app lock and independent visibility choices use the local cache.
Existing explicit visibility choices remain intact, with an option to reveal
unlocked reminder/widget details. New preferences have compatible defaults;
upgrades and theme changes retain the encrypted cache, phone keys and queued work.

## Keep the same signing key

**A signing mismatch is not fixed by deleting pairing keys.** It means the APK
must be rebuilt with the installation's original Android signing key. Field Notes
blocks that APK before installation and says to keep the existing app and data.

Standard source installs and prebuilt bundles now use the exact public GitHub
APK, verified against `public_android_signer_sha256` in `release.json`. Their
phone updates use the same stable certificate regardless of which server builds
or hosts the bundle. The host downloads the matching versioned release and does
not need the publisher's private key. The runtime containers remain offline.
There is no silent fallback to a freshly signed development APK when a release
cannot be downloaded or verified. Public publishing paths also enforce the
pinned certificate; the old one-off development publication workflow is retired.

Back up the stable operator keystore securely outside Git and server bundles.
Do not generate a fresh key for every builder or CI run. The existing development
signer is retained for current APKs; its private key is in the protected external
builder cache, not in this repository or on the server. With these build scripts,
that development key is `$FIELD_NOTES_TOOLS/android-user/debug.keystore`. Keep
its directory private and back up the file securely before replacing the builder
or clearing its cache. A fresh builder cannot recreate the same key.
If your first installed
APK was a development build, keep that key for its updates too. Changing from
`debug` to `release` does not itself grant permission to change signers. A newly
chosen operator key is for new installations unless it matches the existing
certificate. Lost signing keys cannot be reconstructed from an APK or phone.

The phone checks its own installed certificate, and a server upgrade refuses a
changed signer when an earlier server APK bundle is present. The release workflow
also requires a stable configured operator keystore. It never publishes a newly
generated ephemeral signer. Neither a password reset nor a server restart rotates
phone keys.

## How `main` reaches the phone

`main` contains source. An installable APK must be built and signed from it. The
phone follows **its paired server's matching APK**, rather than replacing itself
with whatever happens to be newest on GitHub. This avoids jumping ahead of that
server's API version. Metadata records the packaging commit and dirty-source
status; that field alone is not a cryptographic source attestation.

The publisher builds and signs a clean version tag, retaining the signing key
and increasing the Android version code for a new rollout. Normal server builds
reuse that version's published APK, checksum, certificate report and original
source metadata rather than compiling or signing another Android app. A newer
server packaging commit does not rewrite the APK's recorded source commit. The
isolated app/gateway containers do not contact GitHub or gain an outbound exception.
The host operator supplies the bundle.
The public APK path requires a clean source tag reachable from `origin/main`
and verifies uploaded assets through a draft before publication. A successfully
published public release is available through the README download link. Public
builds retain their pinned signing certificate; an operator's original local
build can have a different signer and must keep it. The optional workflow that
builds and signs on GitHub requires configured signing secrets; the verified
prebuilt publication path does not upload a private key.

```sh
git switch main
git pull --ff-only origin main
# Build the server and verify the matching published APK; no signing key:
bash scripts/setup/cloud.sh --server
bash scripts/build/server.sh
```

For a custom development installation, explicitly set `FIELD_NOTES_ANDROID_APK`
to an APK built with its retained original builder key. Never silently replace
that signer. See
[ANDROID-DOWNLOADS.md](ANDROID-DOWNLOADS.md) for initial sideloading and releases.

## Upgrade the Linux server while keeping identities

Extract the new server bundle on the Debian/Ubuntu server, then from its root:

```sh
sudo bash scripts/upgrade-server.sh --plan --bundle "$PWD"
sudo bash scripts/upgrade-server.sh --apply --bundle "$PWD"
```

Use `--config /your/recorded/config` for a custom installation. An installed
`/opt/field-notes/upgrade-server.sh` wrapper also accepts `--bundle` and keeps the
installation's selected config path. This is distinct from the fresh installer,
which deliberately refuses overwriting existing state.

The upgrader supports inventoried container installations with compatible
**schema 2/protocol 1**. Native/partial installations, same-version replacements,
downgrades and unreviewed schema changes are refused. It validates artifact
checksums/version/signers, ownership, service hashes and Docker namespace scope;
loads only local immutable images; and stages the new program/download files.
Normal upgrades verify both installed image IDs before a source build and again
before stopping services. If either pinned image is missing, the upgrade stops
without stopping the existing service or touching household data. A stopped
runtime with missing images needs explicit
[runtime recovery](RUNTIME-RECOVERY.md), which can reuse the verified bundle
already built. Rebuilding or resetting pairing is unnecessary when that bundle
is usable. Backup containers never automatically pull an image.
It stops the owned timers and active peer/certificate helpers before replacing
the namespace, avoiding an in-flight helper acknowledging the old gateway.

A root-private `upgrade-<id>` checkpoint under the recorded config directory
contains the old program/settings inventory and an encrypted pre-upgrade data
backup. The previous CA/master key, hub private key, network config, ports,
users/password hashes, device rows, PSKs, sessions, workspace epoch and generation
are retained. The older master-key ownership/mode bug is repaired without
replacing its bytes. Peers are reapplied to the new namespace and receive fresh
host acknowledgements. Phones reconnect using their saved identities.

The new service must pass direct TLS and matching APK/version readiness.
When the host is outside its routed administrator CIDR, the probe runs in the
owned gateway namespace and verifies the expected loopback HTTP 403; the
operator completes browser login from an allowed client. No ACL exception is
introduced. A
normal backup/start/readiness failure attempts to restore the previous programs/
settings and resumes the old service only after verifying its pinned images.
It resets systemd's failed-start limit and checks the owned container health.
The result distinguishes restored files from a confirmed healthy runtime; a
rollback failure is reported explicitly. It never restores an older database
over accepted edits. Old images remain available for recovery; the current scoped uninstaller
uses validated upgrade history to remove the installation's historical images
as well, without global prune or removal of shared packages or other applications.

After a failure in these steps, the root-private checkpoint includes
`failure.json`: the failing step, a bounded reason, whether programs/settings
were restored, whether the previous runtime passed its health check, and any
rollback failure. It excludes raw command output, passwords, keys and content.
The terminal points to this file. The `upgrade-<id>` path itself is a directory;
use `ls -l` on it, or `cat /etc/field-notes/upgrade-<id>/failure.json` with the
actual ID. Older failed upgrades may have no failure report or completed backup.

Power loss, disk failure during rollback or a changed foreign namespace can
require host recovery. Keep the checkpoint and do not purge/reinstall. Stop the
Field Notes timers/services, review `checkpoint.json`, and restore the saved
`displaced-programs`, `deployment.json`, `compose.json` and `installation.json`
from that exact installation before restarting. Do not replace the database or
keys merely to recover code. After a rollback to an older helper, use the new
bundle's uninstaller if removal is needed so upgrade image history is inspected.
Actual privileged Debian/Ubuntu upgrade and kernel WireGuard checks are still
unrun here; temporary-tree failure tests and Docker transport tests are recorded
in [VALIDATION.md](VALIDATION.md).

Future breaking API/schema releases need an explicit migration and a compatible
software-update control route before rollout. Do not make the old client's
update route depend on a new content protocol it cannot yet speak.

## Upgrade an existing source installation

Version **0.11.4 / Android code 23** delivers the explicit project task picker,
parentless task choice and duplicate audio-failure notification fix. Pulling Git
source alone does not replace an installed server or APK; the matching verified
release must be built and applied, then accepted on each phone.

If normal upgrade reports a missing pinned rollback image, the running service
may still be active. Do not delete its containers or reset pairing. The explicit
recovery-upgrade path uses the verified newer target image for its encrypted
backup and preserves the retained database and identity inventory:

```bash
cd /usr/local/src/field-notes &&
git pull --ff-only origin main &&
python3 scripts/upgrade-server.py --apply --build-from-source --recover-runtime
```

This path can upgrade the retained active installation as well as a stopped
one. It briefly stops the owned stack, retains a raw encrypted database snapshot
and logical backup, and verifies the new runtime before completing. Same-version
recovery still requires the identical server executable; it does not install
unreleased source changes over an existing release number.

For the standard Debian/Ubuntu installation, run as root from the existing
checkout. Build and verify the next release, then back up and upgrade:

```bash
cd /usr/local/src/field-notes &&
git pull --ff-only &&
python3 scripts/upgrade-server.py --apply --build-from-source
```

Do not gate recovery on an old readiness check. If the installed runtime is
already broken, add `--recover-runtime` to that final command. This uses the
existing installation inventory, identities and content; it is not a fresh install.

No fresh-install wizard or new password is needed. A public-signed installation
uses the matching GitHub release and requires no local Android signing key.
Existing custom development installations still require their original
`FIELD_NOTES_TOOLS/android-user/debug.keystore`; the default build-cache root is
the build user's `.cache/field-notes`. A missing custom key stops before building
or stopping services. The final preview refuses a changed signer in either path.
The standard public APK stays unchanged. When needed, the original key signs a
separate household-only compatibility APK. Installed phones select by certificate
from 0.6 onward. Custom release keys can be supplied with
`FIELD_NOTES_ANDROID_LEGACY_KEYSTORE`, `_SIGNER` and `_ALIAS`, with their passwords
in `FIELD_NOTES_LEGACY_STORE_PASSWORD` / `FIELD_NOTES_LEGACY_KEY_PASSWORD`, or use
an explicitly verified `--bundle`. These rules
preserve existing phones; they cannot retroactively change an installed APK's
certificate. Keep local unsynchronized work and pairing intact if a mismatch
needs investigation.

## Content safety in 0.6

New portable tables are additive; routine upgrades retain the vault, cache,
queues, account hashes, CA, hub/phone keys and chosen ports. Imports do not replace
any of those. Both phone and website offer [encrypted content files](CONTENT-FILES.md),
with duplicate suppression, retained alternatives, preview fences and transactional
commits. Export before deliberately clearing retained work. Keep regular encrypted
server backups as well.

## 0.11 planner extensions

Core sync remains protocol 1 / schema 2. Recipes and locations are additive
fields; older clients' partial edits preserve them. Explicit day/time reminders
and notification-volume control use new feature markers. Older schedulers get
compatible projections rather than misfiring an unfamiliar rule. An old editor
cannot replace a newer schedule or audio configuration; the server returns an
explanation and the phone retains the edit for review. Update both phones to
use and deliver the new controls. Existing reminder times, alert identities,
phone keys and queued work are not reset.

### Widget continuity in 0.11.1

Android code 20 retains the public signing certificate. The actual 19 → 20
in-place upgrade preserved Keystore-backed keys, server/CA/PSK binding, privacy
preferences and queued work. The widget fix changes disclosure refresh, without
requiring a new pairing, cache reset or privacy preference reset. Both real
launcher calendar views passed offline repaint, wake and exact-day navigation.
