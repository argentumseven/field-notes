# Build and run

This is a development implementation with a compiled Kotlin Multiplatform /
Compose Android app and a tested Rust server/LAN web interface. Physical
GrapheneOS and home-network verification remain separate. See
[VALIDATION.md](VALIDATION.md) for the actual executed checks.

## Cloud development

```sh
bash scripts/setup/cloud.sh
source scripts/dev-env.sh
bash scripts/check.sh
bash scripts/build/server.sh
```

Tool caches live under `/workspace/.cache/field-notes` by default, outside the
checkout. Rust 1.90.0 and Go 1.26.9 are pinned; Cargo and Go dependencies have
lock/checksum files. The LAN UI is embedded into the server binary; rebuild after
editing its assets. Chromium is required for browser tests (`CHROMIUM_PATH` can
select its executable). Python 3 and Node 24 are used for the test tools.

For a disposable loopback-only UI development session, run
`node scripts/dev-server.mjs`. It uses synthetic test credentials from
`tests/support.mjs`, prints its assigned backend addresses, and removes its
temporary data on exit. Do not use that fixture as a persistent household server.
`node scripts/dev-server.mjs --smoke` exercises authenticated readiness and exits.

Initialize a persistent local development workspace with a password entered securely:

```sh
python3 -c 'import getpass,sys; sys.stdout.write(getpass.getpass("Owner password (12+ characters): "))' \
  | target/debug/field-notes-server init --name owner

target/debug/field-notes-server serve --dev
```

The two HTTP backends bind only loopback, on ports 8787 (web/admin) and 8788
(device API). The development flag allows a non-Secure cookie only for local
HTTP development. Installed use requires HTTPS through the supplied Caddy
configuration. The exact public/LAN web origin must match `--origin`; do not
weaken origin checks or certificate verification to solve setup errors.

The service never creates an owner or a new key over an existing installation.
Keep `.local/secrets/master.key` private. Production keys and data do not belong
in the checkout. `stats` reports counts without printing records or credentials.

## Home Linux installation

The release bundle is under `dist/server/`. The current binary is built on the
cloud host's libc; it has not been certified for older Debian/Ubuntu releases.
Build from source on the target distribution if necessary. The installer tests
that the binary executes before changing the host.

From the checkout or extracted release bundle:

```sh
bash scripts/install-server.sh --plan \
  --lan-ip 192.168.1.10 --lan-prefix 192.168.1.0/24

sudo bash scripts/install-server.sh --apply \
  --lan-ip 192.168.1.10 --lan-prefix 192.168.1.0/24
```

Replace addresses with the actual server and trusted management network. Choose
an overlay subnet that does not overlap Bungee, your LAN, or another VPN. The
plan performs no writes or network operations. Apply is a fresh-install helper:
it installs packages and dedicated systemd services, asks for the initial owner
password, generates separate storage/WireGuard keys, and refuses existing
installation paths, accounts, or conflicting Field Notes units. It does not
provide a tested automatic upgrade/rollback system yet.

The installer uses a dedicated Caddy service/config, not the global Caddy site
configuration. On a new Caddy package installation it prevents the unrelated
default service from starting. Existing services/firewall policies are not
replaced. Root/kernel/service installation has **not** been executed in this
cloud environment; run the plan and validate on the target host before use.

Configure one router UDP forward (default 51831) to the server. The native API
listens through WireGuard at the selected overlay server address, port 8443.
The website binds only the specified LAN address on HTTPS/443. Phone traffic to
admin, other peers, the LAN, host management, and Internet forwarding is denied
by a dedicated nftables policy. Other host firewall rules can still deny allowed
traffic; this installer does not override them. Verify IPv4/IPv6 and an actual
cellular/off-LAN connection, including possible CGNAT/double NAT.

Set LAN DNS for the chosen hostname (default `notes.home.arpa`). Trust the public
Caddy root certificate on administrator devices after checking its fingerprint
locally. Automatic trust installation is disabled. The server prints the public
certificate path, not its private key. Never distribute the Caddy root private
key or application master key to phones. The native client receives the public
CA certificate through its trusted pairing configuration.

## Android builds and pairing

The Android app uses Kotlin Multiplatform/Compose; Flutter is no longer needed.
Only Android is being delivered now. iOS and native desktop packages are deferred.
Shared `commonMain` UI/domain code is portable to a future iOS target, but native
Apple storage/networking/notification adapters would still be needed.

```sh
bash scripts/setup/cloud.sh --android
bash scripts/android.sh hostTest lintDebug
bash scripts/build/android.sh debug
# With an existing operator signing key supplied securely:
bash scripts/build/android.sh release
```

The build uses JDK 21, Kotlin 2.1.21, Compose 1.8.2, AGP 8.10.1 and a checksum-pinned
Gradle 8.11.1 wrapper. Google Maven artifacts are fetched directly from the
already allowed official `dl.google.com` Android repository. No Flutter downloads
or custom network additions are required. Repository dependency locks should be
reviewed when versions change, not silently regenerated during routine builds.

Release signing requires `FIELD_NOTES_KEYSTORE`, `FIELD_NOTES_KEY_ALIAS`,
`FIELD_NOTES_STORE_PASSWORD`, and `FIELD_NOTES_KEY_PASSWORD` through a secure local
build environment. Never paste values into chat or Git. The debug keystore is a
separate persistent development identity under the configured tool user's home;
retain it to update the debug app without uninstalling. Debug signing is not a
substitute for an operator-controlled production release key.

Output: `dist/android/field-notes-0.1.0-android-arm64-debug.apk` (or release), plus
`.sha256` and `.signer.txt`. Verify both, copy the APK to your phone, open it,
temporarily allow installation from that source, install, and restore that
source permission. Optional developer route: `adb install -r <apk>`.

The phone app runs on Android 8/API 26 or later, targets Android 16/API 36, and is
built for arm64 GrapheneOS phones. It does not require Google Play services,
Firebase, an Android VPN service, device administration, or broad photo access.
The OS photo picker grants access only to selected images. A secure device lock
prompts for the device credential before opening the UI, with a five-minute
foreground grace period. Keystore cache keys remain usable for explicitly enabled
background catch-up after first device unlock; UI locking is not per-access
biometric encryption of every packet.

Background catch-up is opt-in through **Notifications & connection**, uses
WorkManager at minimum 15-minute intervals, and can be delayed by Android Doze,
force-stop, reboot-before-unlock, missing network, or VPN restrictions. Generic
notifications need Android notification permission. Optional local reminders
check cached due dates after 09:00 in the device's time zone and deduplicate by
task/due-date. They can be stale while offline and are not exact-time alarms.
No permanent foreground service, FCM, APNs, or public push fallback is used.

If the public home endpoint does not work from your LAN because the router lacks
hairpin NAT, use **Notifications & connection → Edit server endpoint** to choose
the server’s trusted LAN address/UDP port. Change back to the public endpoint
when remote, or configure router/DNS routing appropriately. Endpoint edits keep
the same server key and CA and preserve pending edits; they do not bypass the
system VPN or re-pair the device.

The shared-domain JVM tests run through `bash scripts/android.sh hostTest`.
Android instrumented tests require a disposable AOSP emulator. The integration
fixture `scripts/test-android-emulator.mjs` verifies the dedicated AVD name
`field-notes-test` before clearing app data; it never runs against a physical
phone. Build the x86_64 test ABI first and use the test hub/fixture instructions
in the validation record. Emulator results do not establish GrapheneOS behavior,
hardware Keystore properties, battery use, or another system VPN's policy.

Pairing is manual in this first implementation; QR invitations and a web-driven
peer admission worker are not implemented. The native Android app generates and shows
its own public key. On the home server, allocate one unused overlay address per
device and approve its public key:

```sh
sudo python3 /opt/field-notes/peer.py add \
  --public-key '<phone-public-key>' --address 10.82.0.2

sudo python3 /opt/field-notes/peer.py connection \
  --public-key '<phone-public-key>' --address 10.82.0.2 \
  --endpoint '<reachable-home-IP-or-DNS>:51831' \
  --ca-file /var/lib/field-notes-tls/caddy/pki/authorities/local/root.crt
```

For a custom subnet pass the same `--overlay` used at installation. The connection
JSON contains public trust/endpoint information, never the phone's private key.
Transfer it from the trusted LAN administrator, compare server identity, and
paste it into the app. Then sign in with a household account created in the web
admin interface. The app's private stack carries only its HTTPS device API.
A browser or another push app cannot use that in-process tunnel.

To revoke a device, revoke its application session in the web UI **and** remove
its peer with `peer.py remove --public-key ... --address ...`. Session revocation
alone does not remove network access or prevent a user with a known password
from signing in again. Peer helper success requires runtime and persisted
WireGuard updates; failures are not reported as complete revocation.

## Backup and restoration

An owner can reauthenticate and download an encrypted backup in the web UI.
Host-side equivalent:

```sh
field-notes-server backup --data /var/lib/field-notes \
  --key-file /etc/field-notes/master.key --file /protected/field-notes.backup
```

Keep a protected copy of the master key separately; login/password recovery
cannot replace a lost encryption key. Backups include account/authentication
metadata as well as encrypted content, and the entire backup is encrypted again.
They do not include host WireGuard/Caddy configuration or their private keys;
back those up separately with appropriate protection.

Restore into a **nonexistent** directory, inspect it, then switch the stopped
service to it. Never restore over a live database:

```sh
field-notes-server restore --data /var/lib/field-notes-restored \
  --key-file /etc/field-notes/master.key --file /protected/field-notes.backup
```

Restoration rotates the server epoch and revokes sessions/old command receipts.
Native clients must sign in again; old-generation/epoch edits become retained
conflicts, not automatically uploaded records. Workspace clear similarly fences
old queues but preserves users. Deletion/clear does not erase historic backups
or guarantee physical overwriting of flash blocks.
