# Install, build and recover

Server: Linux only, Debian/Ubuntu installer. Android: arm64 GrapheneOS/Android
8+. Linux and Windows use the LAN web interface. Native desktop and iOS builds
are deferred. See [VALIDATION.md](VALIDATION.md) for checks and limits.

## Fresh home server

Install rootful Docker Engine and Compose 2.24+ first, following their verified
distribution instructions. Target Debian 13 or Ubuntu 24.04 LTS with Python 3.11+
and kernel WireGuard support. See [CONTAINERS.md](CONTAINERS.md) for prerequisites
and the isolation policy. Build on Linux with local Docker or unpack the matching
Linux server bundle; both offline images are included:

```sh
bash scripts/build/android.sh debug
bash scripts/build/server.sh
# Or extract dist/server/field-notes-0.3.3-linux-x86_64.tar.gz on the Linux host.

sudo bash scripts/install-server.sh --plan \
  --lan-ip 192.168.1.10 --lan-prefix 192.168.1.0/24 \
  --endpoint your-home.example.net
sudo bash scripts/install-server.sh --apply \
  --lan-ip 192.168.1.10 --lan-prefix 192.168.1.0/24 \
  --endpoint your-home.example.net
```

Replace example addresses/domain with yours. Reserve the server's LAN address
on your router. Select a private /24 overlay with `--overlay` if `10.82.0.0/24`
overlaps Bungee/your LAN/VPN. Supply a publicly reachable endpoint to use phones
away from home; omitting it produces a clearly labeled LAN-only installation.
The installer asks for **both ports** when run from a terminal:

```text
Management HTTPS port (LAN only) (1024–65535, required):
WireGuard UDP port (router forwarding) (1024–65535, required):
```

Enter both port numbers yourself; blank input is rejected. Port 8443 is
reserved for the inner device API and cannot be used for management HTTPS.
`--plan --interactive` opens the same no-write port wizard.
The plan reports `apply_port_options`; repeat those
`--web-port` and `--udp-port` values with `--apply` to use exactly the reviewed
choices. Flags skip their corresponding questions. For example:

```sh
sudo bash scripts/install-server.sh --apply \
  --lan-ip 192.168.1.10 --lan-prefix 192.168.1.0/24 \
  --web-port 10443 --udp-port 43127 \
  --endpoint your-home.example.net
```

That example serves LAN management at `https://192.168.1.10:10443`.
A nonterminal plan or apply call requires both explicit port options; neither
command chooses defaults.
Owner name/password prompts remain separate; no command-line password is used.
The owner password is selected and confirmed during installation, using hidden
terminal input. The server stores a salted Argon2id hash, not a recoverable
password. It is the login for the web manager as well as the household website.
Review `--plan` before applying. Supply `--endpoint your-home.example.net`
without a port to follow your WireGuard selection automatically. An explicit
`:port` selects the router's external port; if it differs from the local UDP
listener, the plan prints the exact external-to-internal NAT mapping.

There are three separate listeners:

| Listener | Port | Access |
| --- | --- | --- |
| Management HTTPS | Chosen explicitly during install | Trusted home LAN only |
| WireGuard transport | Chosen explicitly during install | The one router-forwarding candidate |
| Client-to-server HTTPS API | TCP 8443, fixed inside the overlay | Enrolled phone tunnels only; never router-forwarded |

The client API is present separately from management. Enrollment supplies its
inner address to the phones; choosing a WireGuard UDP port does not change the
inner API port.

The installer refuses existing directories, accounts, services and WireGuard
configuration. It is for fresh hosts; it is not an in-place migration from the
older proxy-based prototype. Installation failure leaves reviewable files; do
not blindly rerun over an incomplete installation or delete household data.

The installer installs host WireGuard tools, OpenSSL and kmod, checks the kernel
module, then loads the bundled app/gateway images by immutable ID. It leaves the
shared Docker daemon configuration alone. It creates:

- `/opt/field-notes`: server, constrained host helpers and the read-only `downloads/`
  APK/checksum/signer/metadata bundle. No SDK or signing key is installed on the server.
- `/var/lib/field-notes`: app-owned encrypted database, event log and private
  desired-peer manifest; `/etc/field-notes/master.key`: server recovery key.
- `/etc/field-notes/tls`: public CA, root-only `private/ca.key` and separate
  `issued/server.crt`/`issued/server.key`; only `issued/` is mounted into the app.
- `/etc/field-notes/network.json`: public bootstrap identity and trusted outer
  endpoints; root-only `gateway/fieldnotes.conf`: private hub key. Fresh installs
  create no host `/etc/wireguard/fieldnotes.conf` or host nftables table.
- `/etc/field-notes/deployment.json`, `compose.json`, `installation.json`: root-owned
  deployment and removal inventory, including selected ports and image IDs.
- `field-notes.service`: root host supervisor; starts the nonroot, read-only app
  and separate NET_ADMIN gateway, with new outbound traffic/DNS denied.
- `field-notes-peers.timer`: constrained root reconciliation every two seconds,
  acknowledging only a successful apply through the gateway. Private host state
  is in `/var/lib/field-notes-peer-helper`, status in `/run/field-notes-peers`.
- `field-notes-certificates.timer`: daily offline leaf-renewal check; renews when
  fewer than 30 days remain. Rust loads a new valid certificate/key within 60s.
- `/opt/field-notes/uninstall-server.sh`: preview by default; normal removal
  preserves recovery data, explicit purge deletes it. [Removal guide](UNINSTALL.md).

There is no Caddy, reverse proxy or load balancer. Rust serves TLS 1.3 directly
at the LAN address and management port you chose; the separate phone API binds
only `10.82.0.1:8443`.
The installer checks login, authenticated snapshot and logout over real TLS.
It does not install a shell-execution endpoint in the management website.

Forward **only the WireGuard UDP port you selected** to the Linux server.
Reserve the server's LAN address, then create an external-to-internal UDP forwarding
rule matching the installer's `router_forward` output. No second application's
port or configuration is needed to choose this value.

This is an opportunity for remote phone access, not an automatic router change.
Never forward the chosen management HTTPS port or inner API TCP 8443.
Preserve any existing unrelated forwarding rules. Keep existing
host firewall restrictions and permit the selected UDP and management LAN as
appropriate. Check the [network access matrix](NETWORK.md) on your actual host;
CGNAT or an upstream UDP filter can prevent remote access. A port change alone
is not a security control. Existing installations retain their configured port;
changing an installation's ports requires a reviewed migration of its live
WireGuard tunnel and enrolled endpoints.

## Trust the LAN website

On the Linux host, verify the public CA fingerprint:

```sh
openssl x509 -in /etc/field-notes/tls/ca.crt -outform DER | sha256sum
```

Transfer only `ca.crt` to your administrator computer and install it in the
appropriate OS/browser trust store after comparing that fingerprint. Never
transfer `private/ca.key`, `server.key` or the server recovery key to phones.
Open the exact management URL shown by the installer. Phones receive the public
CA in their trusted-source QR invitation and need no system-wide CA installation.
See [enrollment steps](ENROLLMENT.md) for scan, code approval, provisioning and
revocation. No manual peer JSON editing is needed.

## Management accounts and passwords

Sign in with the owner name/password chosen during installation. **Management →
People → Add person** creates additional named accounts, each with a separate
initial password, confirmation and role. There is no fixed two-user limit.

Every role, including Viewer, has **Change password** in the sidebar. Enter the
current password and confirm a replacement. **Management → People → Reset
password** resets another person's password after recent administrator
reauthentication. Owners may reset any other active account, including another
owner; administrators may reset members/viewers. Administrators cannot reset
other administrators or owners. Changing your own password always requires
the current password, including for an owner.

Passwords use random per-password salts and Argon2id. The server never displays
existing passwords or password hashes. Replacements use the same hashing and
12–256 UTF-8 byte limit. Browser forms confirm the replacement before sending it.
No password is written to the install configuration, audit payloads or command
history. Authentication runs over the direct TLS connection.

A successful change/reset atomically invalidates the affected user's **browser**
sessions. Paired phones and pending invitations keep working by default. Select
**Also sign out paired phones and cancel pending enrollments** when that wider
scope is intended. Phones then need to sign in again; saved offline work remains.
Removing a phone's tunnel is a separate **Revoke device** action. Routine password
maintenance does not silently unpair phones or regenerate device keys. Phone
credentials cannot authorize the LAN website, even if copied to a LAN client.

Copy open web drafts before changing your own password because successful sign-out
clears those drafts. The app's data-encryption master key is separate and unchanged.
If a connection drops during submission, try signing in with the replacement
password before retrying. Self-service proof attempts are limited to five per
five minutes, including across server restarts.

## Install the Android APK

On your phone, open the trusted LAN management URL and choose **Download APK**
on Sign in or in Management. Trust the LAN CA in that browser first, comparing
its fingerprint, or download on your trusted administrator computer and copy the
APK to the phone. App enrollment itself needs no system-wide CA. No account
login is needed to fetch the software;
household content still requires authentication. SHA-256 and signing-certificate
links are alongside it. The installed server serves its bundled version locally,
without a GitHub or Internet connection. The private notes endpoint does not
serve downloads. See [ANDROID-DOWNLOADS.md](ANDROID-DOWNLOADS.md) for verification,
stable signing and the permanent-release workflow.

Build output:
`dist/android/field-notes-0.3.3-android-arm64-debug.apk`, `.sha256`, `.signer.txt`.
Copy the APK to the phone, verify its hash/signing-certificate fingerprint, and
open it with your file manager. Temporarily allow installation from that source.
The debug APK is signed with the development key in the external tool cache;
retain that key for in-place development updates. Do not commit signing keys.

Open **Enroll this phone** on trusted home Wi-Fi and follow [ENROLLMENT.md](ENROLLMENT.md).
Camera access is requested only for scanning. QR decoding is entirely local;
ZXing does not require Play services. Paste is available without camera access.
The app does not consume Android's system VPN slot; existing VPN/lockdown policy
still governs its outer UDP sockets. Test home and mobile-data connectivity on
each phone before relying on background catch-up.

After enrollment, allow notifications and review the default-on background mode.
For widgets, tap **Add Upcoming home-screen widget** in **Settings & widgets**,
or use the launcher's widget picker. Both Upcoming and Focus are real Android
home-screen providers; title previews are opt-in. Follow
[Android delivery and GrapheneOS profiles](ANDROID-DELIVERY.md) for battery,
alarm/sound permissions, full-user-profile forwarding, the delayed test, and
clean revocation/uninstall. The app cannot override a stopped profile or DND.

## Build from source

```sh
bash scripts/setup/cloud.sh --android
source scripts/dev-env.sh
bash scripts/check.sh
bash scripts/android.sh hostTest lintDebug
bash scripts/build/android.sh debug
bash scripts/build/server.sh
```

Pinned Kotlin 2.1.21/Compose 1.8.2/AGP 8.10.1/Gradle 8.11.1, JDK 21, Android SDK
36, NDK 28.2 and Go/Rust setup live under `scripts/setup`. Dependencies are locked
and verified. Tool/signing caches remain outside the checkout. The Android build
produces the actual arm64 APK, verifies signature, ZIP/ELF 16 KiB alignment and
manifest constraints. Server builds use fresh staging to prevent stale files
from earlier bundles. Per-target scripts do not report success without artifacts.

An operator release requires a stable separately protected signing key:

```sh
# Supply these securely in your shell/secret manager; never commit their values.
export FIELD_NOTES_KEYSTORE=/protected/path/release.jks
export FIELD_NOTES_KEY_ALIAS=field-notes
# Also set FIELD_NOTES_STORE_PASSWORD and FIELD_NOTES_KEY_PASSWORD securely.
bash scripts/build/android.sh release
```

The release script refuses missing signing inputs. Native Windows/Linux clients
and iOS binaries are not produced by these scripts.

## Development and checks

`node scripts/dev-server.mjs` runs explicit loopback-only HTTP development
listeners with synthetic local data; it is not a production deployment.
`--dev` refuses nonloopback bindings. Installed operation always uses TLS.

```sh
bash scripts/build/android-emulator.sh
node scripts/test-native-client.mjs
# On only the disposable field-notes-test AVD:
# Connect its emulated Wi-Fi first if it has no IPv4 route:
adb -s emulator-5554 shell cmd wifi connect-network AndroidWifi open
node scripts/test-android-emulator.mjs
node scripts/capture-web.mjs
node scripts/capture-android.mjs
```

The capture scripts use synthetic data. Web captures run against a real Rust
server. Android captures use actual Compose/RemoteViews with a test-only
transport fixture; they do not prove live tunnel enrollment or widget launcher
lifecycle. The separate launcher-icon capture shows the actual installed icon.
Production screen-capture protection is not disabled. See [WALKTHROUGH.md](WALKTHROUGH.md).

## Upgrade without re-pairing

Use [UPDATES.md](UPDATES.md) for the in-app **App updates** screen and the
Linux `scripts/upgrade-server.sh --plan/--apply --bundle PATH` flow. Retain the
original Android signing key and server CA/hub/master keys. Do not purge and
reinstall for a routine upgrade.

## Operation, renewal and recovery

```sh
sudo systemctl status field-notes field-notes-peers.timer field-notes-certificates.timer
sudo journalctl -u field-notes -u field-notes-peers --since today
sudo docker --host=unix:///var/run/docker.sock exec field-notes-gateway wg show fieldnotes
sudo docker --host=unix:///var/run/docker.sock exec field-notes-gateway nft list table inet fieldnotes
```

Do not publish peer manifests, QR material, host configs or unredacted support
logs. Root-owned host configuration/keys need host disk protection. Application
AES-GCM does not encrypt every SQLite metadata field, WireGuard config or memory.

Back up the workspace with **Management → Download encrypted backup**, after
reauthentication. Store the recovery master key separately and protect host
WireGuard, CA and network configuration in a separate host backup. Existing
backups are not erased by clearing active data.

For restore, stop the app and peer timer, preserve a coherent host backup, and
restore into a **new** data directory with the same master key. Run the bundled
image offline, with the dedicated UID/GID and only the needed mounts:

```sh
sudo systemctl stop field-notes-peers.timer field-notes
sudo install -d -o fieldnotes -g fieldnotes -m 700 /var/lib/field-notes-recovery
sudo docker --host=unix:///var/run/docker.sock run --rm --network=none \
  --read-only --cap-drop=ALL --security-opt=no-new-privileges:true \
  --user "$(id -u fieldnotes):$(id -g fieldnotes)" \
  --mount type=bind,src=/var/lib/field-notes-recovery,dst=/recovery \
  --mount type=bind,src=/etc/field-notes/master.key,dst=/keys/master.key,readonly \
  --mount type=bind,src=/protected/path/field-notes.backup,dst=/backup,readonly \
  field-notes-app:0.3.3 restore --data /recovery/restored --key-file /keys/master.key --file /backup
```

The output is `/var/lib/field-notes-recovery/restored`, which must not already
exist. The backup file must be readable by that UID/GID. Preserve its private permissions;
never mount an entire home or root directory. The bundled native CLI requires a
compatible builder libc; the image includes its own runtime libraries and is the
portable Debian/Ubuntu route.

Keep the old directory untouched until recovery is verified. Switching the active
data path is a reviewed maintenance operation: update `deployment.json`, regenerate
`compose.json` from `container_deployment.compose`, update the peer-helper desired
path and the installation inventory's data/deployment paths together. Preserve
root ownership/modes. There is no automatic restore/migration wizard. A restore
changes the epoch, invalidates sessions/invitations, and marks peers for removal.
Re-enroll phones; old offline edits are retained as conflicts. Loss of the master
key cannot be repaired by a password reset. Do not replace unreadable phone vaults
containing offline work.

Manual certificate renewal, preserving the CA:

```sh
sudo python3 -B /opt/field-notes/tls.py renew --issued-subdirectory --directory /etc/field-notes/tls \
  --group fieldnotes --name 192.168.1.10 --name notes.home.arpa --name 10.82.0.1
```

Use the names selected during installation. CA/server-WireGuard identity rotation
needs reviewed re-enrollment; automatic cross-CA trust rotation is not implemented.


## Uninstall

```sh
sudo /opt/field-notes/uninstall-server.sh --plan
sudo /opt/field-notes/uninstall-server.sh --apply
# To delete all managed data and keys instead, use --apply --purge and confirm.
```

Normal removal preserves root-only recovery data. Purge explicitly deletes the
recorded data/configuration directories. Neither removes Docker, WireGuard tools,
Bungee or shared resources. See [UNINSTALL.md](UNINSTALL.md) for a later purge,
legacy installations, custom paths and external router/phone cleanup.
