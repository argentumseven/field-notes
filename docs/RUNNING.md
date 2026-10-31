# Install, build and recover

Server: Linux only, Debian/Ubuntu installer. Android: arm64 GrapheneOS/Android
8+. Linux and Windows use the LAN web interface. Native desktop and iOS builds
are deferred. See [VALIDATION.md](VALIDATION.md) for checks and limits.

## Fresh home server

Build on Linux or unpack the matching Linux server bundle:

```sh
bash scripts/build/server.sh
# Or extract dist/server/field-notes-0.3.0-linux-x86_64.tar.gz on the Linux host.

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
Management HTTPS port (LAN only) [9443]:
WireGuard UDP port (router forwarding) [49272]:
```

Enter your own values or press Enter to choose each suggestion. Port 8443 is
reserved for the inner device API. `--plan --interactive` explicitly opens the
same no-write port wizard. The plan reports `apply_port_options`; repeat those
`--web-port` and `--udp-port` values with `--apply` to use exactly the reviewed
choices. Flags skip their corresponding questions. For example:

```sh
sudo bash scripts/install-server.sh --apply \
  --lan-ip 192.168.1.10 --lan-prefix 192.168.1.0/24 \
  --web-port 10443 --udp-port 49272 \
  --endpoint your-home.example.net
```

That example serves LAN management at `https://192.168.1.10:10443`. A nonterminal
`--plan` previews defaults and labels them as a preview. A noninteractive
`--apply` refuses missing port choices, rather than silently selecting ports.
Owner name/password prompts remain separate; no command-line password is used.
Review `--plan` before applying. Supply `--endpoint your-home.example.net`
without a port to follow your WireGuard selection automatically. An explicit
`:port` selects the router's external port; if it differs from the local UDP
listener, the plan prints the exact external-to-internal NAT mapping.

There are three separate listeners:

| Listener | Port | Access |
| --- | --- | --- |
| Management HTTPS | Chosen during install; suggested TCP 9443 | Trusted home LAN only |
| WireGuard transport | Chosen during install; suggested UDP 49272 | The one router-forwarding candidate |
| Client-to-server HTTPS API | TCP 8443, fixed inside the overlay | Enrolled phone tunnels only; never router-forwarded |

The client API is present separately from management. Enrollment supplies its
inner address to the phones; choosing a WireGuard UDP port does not change the
inner API port.

The installer refuses existing directories, accounts, services and WireGuard
configuration. It is for fresh hosts; it is not an in-place migration from the
older proxy-based prototype. Installation failure leaves reviewable files; do
not blindly rerun over an incomplete installation or delete household data.

The installer installs WireGuard tools, nftables and OpenSSL. It creates:

- `/opt/field-notes`: server and constrained host helpers.
- `/var/lib/field-notes`: app-owned encrypted database, event log and private
  desired-peer manifest; `/etc/field-notes/master.key`: server recovery key.
- `/etc/field-notes/tls`: public CA and leaf files; root-only `private/ca.key`.
- `/etc/field-notes/network.json`: public bootstrap identity and trusted outer
  endpoints; `/etc/wireguard/fieldnotes.conf`: host private key and peer PSKs.
- `field-notes.service`: nonroot Rust TLS server, no NET_ADMIN.
- `wg-quick@fieldnotes`, `field-notes-firewall`: host hub and peer isolation.
- `field-notes-peers.timer`: constrained root reconciliation every two seconds,
  acknowledging only a successful apply/save. Private host state is separate in
  `/var/lib/field-notes-peer-helper`, status in `/run/field-notes-peers`.
- `field-notes-certificates.timer`: daily offline leaf-renewal check; renews when
  fewer than 30 days remain. Rust loads a new valid certificate/key within 60s.

There is no Caddy, reverse proxy or load balancer. Rust serves TLS 1.3 directly
at the LAN address and management port you chose (`https://192.168.1.10:9443`
with the suggestion); the separate phone API binds only `10.82.0.1:8443`.
The installer checks login, authenticated snapshot and logout over real TLS.
It does not install a shell-execution endpoint in the management website.

Forward **one UDP port only**, **49272** by default, to the Linux server.
For example, reserve `192.168.1.10` for the server, then add this router rule:

| Purpose | External port/protocol | Internal destination |
| --- | --- | --- |
| Field Notes phone tunnel | **49272 / UDP** | **192.168.1.10:49272 / UDP** |
| Bungee, if using its guide's example | 49271 / UDP | Your Bungee host:49271 / UDP |

The adjacent pair comes from [Bungee's installation example](https://github.com/argentumseven/bungee/blob/ec7c089debf12ddb3541a7e34f993b5e1354f0ac/docs/INSTALL.md).
Bungee itself has no fixed default. If your actual Bungee listener is different,
pass `--bungee-port YOUR_BUNGEE_PORT` to choose one higher, and set `--endpoint`
to your DDNS/IP with that resulting port. For example, Bungee `47937` means
`--bungee-port 47937 --endpoint your-home.example.net:47938`. Or choose an unused
port with `--udp-port`. The plan prints the exact forwarding rule. Keep external
and internal port numbers the same for these examples.

This is an opportunity for remote phone access, not an automatic router change.
Never forward the chosen management HTTPS port (9443 by suggestion, or your
custom value) or inner API TCP 8443. Preserve Bungee's own separate rule. Keep existing
host firewall restrictions and permit the selected UDP and management LAN as
appropriate. Check the [network access matrix](NETWORK.md) on your actual host;
CGNAT or an upstream UDP filter can prevent remote access. A port change alone
is not a security control. Existing installations retain their configured port;
changing a default does not migrate a live tunnel or its enrolled endpoints.

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

## Install the Android APK

Build output:
`dist/android/field-notes-0.3.0-android-arm64-debug.apk`, `.sha256`, `.signer.txt`.
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

## Operation, renewal and recovery

```sh
sudo systemctl status field-notes field-notes-peers.timer field-notes-certificates.timer
sudo journalctl -u field-notes -u field-notes-peers --since today
sudo wg show fieldnotes
sudo nft list table inet fieldnotes
```

Do not publish peer manifests, QR material, host configs or unredacted support
logs. Root-owned host configuration/keys need host disk protection. Application
AES-GCM does not encrypt every SQLite metadata field, WireGuard config or memory.

Back up the workspace with **Management → Download encrypted backup**, after
reauthentication. Store the recovery master key separately and protect host
WireGuard, CA and network configuration in a separate host backup. Existing
backups are not erased by clearing active data.

For restore, stop the app and peer timer, preserve a coherent backup, and restore
into a **new** data directory using the same master key:

```sh
sudo systemctl stop field-notes field-notes-peers.timer
sudo /opt/field-notes/field-notes-server restore \
  --data /var/lib/field-notes-restored --key-file /etc/field-notes/master.key \
  --file /protected/path/field-notes.backup
```

Verify and set service ownership/permissions, then deliberately switch the unit's
data path and peer-helper desired path together. Review before restarting. A
restore changes the epoch, invalidates sessions/invitations, and marks peers for
removal. Keep the old directory untouched until recovery is verified. Re-enroll
phones; old offline edits are retained as conflicts, not silently uploaded into
the restored workspace. Loss of the master key cannot be repaired by a password
reset. Do not delete or replace unreadable phone vaults containing offline work.

Manual certificate renewal, preserving the CA:

```sh
sudo python3 /opt/field-notes/tls.py renew --directory /etc/field-notes/tls \
  --group fieldnotes --name 192.168.1.10 --name notes.home.arpa --name 10.82.0.1
```

Use the names selected during installation. CA/server-WireGuard identity rotation
needs reviewed re-enrollment; automatic cross-CA trust rotation is not implemented.
