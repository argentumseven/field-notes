# Updates without re-pairing

The paired server selects the phone APK version. **Settings & widgets → App
updates** shows the installed version and the version that server offers. Field
Notes checks automatically while the unlocked app is in the foreground, at most
once per six hours, and **Check for updates** checks immediately.

If the installed app predates 0.3.2, it does not yet have this screen. Download
0.3.3 from the updated server and install it **over the existing app**, with the
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
256 KiB chunks with a 150 MiB artifact limit; an interrupted partial download is
removed and can be retried. Cancel finishes when the current bounded request
returns. The update cache is private and excluded from backup, and Android removes
it on normal uninstall. A nonexported FileProvider grants the installer read
access only to the staged software file.

An in-place Android update preserves app data, the Keystore alias, phone private
key, PSK, pinned CA/server binding and pending edits. The app never calls reset,
clear-storage or uninstall to update. Controlled **0.3.1 → 0.3.2** and **0.3.2 → 0.3.3** APK upgrades
were exercised on the disposable AVD with the actual Keystore-backed vault and
queued task command. Physical GrapheneOS installation/profile checks remain
separate. Android app code can be shared across full user profiles; their vaults
and keys remain separate, and Android decides which user may install an update.

## Keep the same signing key

**A signing mismatch is not fixed by deleting pairing keys.** It means the APK
must be rebuilt with the installation's original Android signing key. Field Notes
blocks that APK before installation and says to keep the existing app and data.

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

Build the server and APK from the same checked-out `main` revision, retaining
the signing key. Increment the Android version code and release version for a
new rollout. The server bundle carries that verified APK, checksum, certificate
report and public metadata. The isolated app/gateway containers do not contact
GitHub or gain an outbound exception. The host operator supplies the bundle.
The optional permanent-release workflow requires a clean source tag reachable
from `origin/main`, verifies assets through a draft, and requires configured
signing secrets. Hosted publication has not been executed in this cloud session.

```sh
git switch main
git pull --ff-only origin main
# On the builder, with the original stable signing key configured:
bash scripts/build/android.sh release
FIELD_NOTES_ANDROID_APK=dist/android/field-notes-0.3.3-android-arm64-release.apk \
  bash scripts/build/server.sh
```

For continued development updates, use `debug` with the retained original
builder key instead. Never silently replace that signer. See
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
It stops the owned timers and active peer/certificate helpers before replacing
the namespace, avoiding an in-flight helper acknowledging the old gateway.

A root-private `upgrade-<id>` checkpoint under the recorded config directory
contains the old program/settings inventory and an encrypted pre-upgrade data
backup. The previous CA/master key, hub private key, network config, ports,
users/password hashes, device rows, PSKs, sessions, workspace epoch and generation
are retained. The older master-key ownership/mode bug is repaired without
replacing its bytes. Peers are reapplied to the new namespace and receive fresh
host acknowledgements. Phones reconnect using their saved identities.

The new service must pass direct TLS and matching APK/version readiness. A
normal backup/start/readiness failure restores the previous programs/settings
and resumes the old service. It never restores an older database over accepted
edits. Old images remain available for recovery; the current scoped uninstaller
uses validated upgrade history to remove the installation's historical images
as well, without global prune or removal of shared packages/Bungee.

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
