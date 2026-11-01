# Linux container deployment

Fresh installs use two local Docker containers, with Rust serving TLS directly.
There is no Caddy, reverse proxy, load balancer, public web service or runtime
fetch from outside the server. The build bundle contains both images, their immutable IDs and SHA-256
checksums. Installation loads them locally; startup uses `--pull never`.

## Host requirements

Target Debian 13 or Ubuntu 24.04 LTS with systemd, Python 3.11+, OpenSSL, rootful
Docker Engine and Compose 2.24+. Install Docker/Compose from their verified
distribution instructions first. The installer does not replace or reconfigure
an existing Docker daemon. Rootless Docker and user-namespace UID remapping are
refused because bind ownership must match the dedicated nonroot service account.
The production gateway requires host kernel WireGuard and namespace nftables
support; the installer loads the module before writing the installation.

A VM alongside Bungee is suitable. Reserve its LAN address, choose unused
management and UDP ports, and choose an overlay distinct from Bungee, LAN, VPN
and Docker bridge ranges. Start with 2 vCPU and 2 GiB RAM plus storage/backups;
this is sizing guidance, not a measured household capacity guarantee. Current
artifacts are Linux x86_64; building on arm64 needs separate verification.

## Runtime boundaries

| Component | Access |
| --- | --- |
| App | Dedicated UID/GID, all capabilities dropped, read-only root, no-new-privileges, 512 MiB memory, one CPU, 64 processes |
| App writable storage | Only `/state` for encrypted database/attachments and a bounded temporary directory |
| App read-only inputs | Master key, public network configuration, issued TLS leaf certificate/key, root-written peer status, bundled APK downloads |
| App excluded inputs | Docker socket, host root, CA signing key, WireGuard hub private key, writable host configuration |
| Gateway | Separate read-only container, only NET_ADMIN, 128 MiB memory; root-only hub key/listener mount |
| Host | Root supervisor talks to the local Docker socket; constrained root peer timer and offline CA renewal timer |

The app shares the gateway's network namespace, not its files or capabilities.
The private Docker bridge has no other members. The supervisor checks ownership
labels and refuses unrelated members or containers sharing the namespace. It
recreates both containers together after a failure. Private peer credentials
travel to `wg` through stdin, never command-line arguments or environment.

The namespace's complete nftables policy loads before WireGuard starts:

- Incoming management TCP is accepted only from the configured LAN CIDR. Docker
  publishes that port only on the selected LAN address. Rust also checks actual
  connection sources; forwarded headers never grant access.
- Incoming WireGuard UDP uses the selected transport port. Decrypted phone traffic
  can reach only overlay server `.1:8443`. It cannot reach management, SSH,
  another peer, the host LAN or the Internet, even with modified AllowedIPs.
- Forwarding is disabled and dropped. New outbound connections are dropped.
  The Docker DNS resolver at `127.0.0.11` is explicitly dropped before loopback
  acceptance; no DNS exception or proxy route is available.
- Established replies to allowed incoming connections work. This permits task
  responses and WireGuard handshakes without permitting initiated connections.

The bridge retains a default route for replies to phones on the Internet.
`internal: true` alone would prevent those replies and is not the security
boundary; the namespace firewall is. The app cannot change that firewall.
Docker manages its own host NAT rules; Field Notes does not flush or replace
host firewall/Bungee tables. Check source preservation and restrictions on your
actual Docker firewall backend; published ports can bypass ordinary UFW input
rules, so review the Docker forwarding/DOCKER-USER policy as well. Check the
actual LAN: Docker hairpin traffic from the server itself may have a bridge
source and must be refused rather than trusted as a LAN browser. The installer's
same-host authenticated readiness check fails if the chosen host translates it
outside the allowed CIDR; use a reachable physical LAN address and investigate
that routing before use, rather than expanding the management trust range.

## Lifecycle and limits

`systemctl stop field-notes` removes its app, gateway and private bridge. Data,
configuration and image cache remain. Start recreates the pair, discards the
old acknowledgement, and the host peer timer reapplies the durable desired
peers. See [UNINSTALL.md](UNINSTALL.md) for permanent removal and purge.

An application exploit remains able to read authorized content and respond on
allowed connections. Containers share the kernel; protect the VM and Docker
socket as root-equivalent administration. No automatic image updates occur:
patch/rebuild images in a reviewed maintenance window. [Compatible container upgrades](UPDATES.md) stage matching server/APK artifacts,
back up data and preserve identities. Automatic migration from the older native
service is not implemented.

## Reproduce the integration check

```sh
bash scripts/build/android.sh debug
bash scripts/build/containers.sh
source scripts/dev-env.sh
mkdir -p dist/container-test
(cd native && CGO_ENABLED=0 go build -trimpath \
  -o ../dist/container-test/wireguard-go golang.zx2c4.com/wireguard)
python3 -B scripts/test-container-runtime.py
```

The disposable test uses a uniquely named project and synthetic credentials. A
fixture-only image adds userspace WireGuard, full Python test tooling, a private
TUN device and MKNOD permission because this cloud kernel lacks WireGuard. Those
additions are excluded from production. The test checks real enrollment/TLS,
task/image writes, LAN APK/checksum delivery from a read-only mount with no
private download route, phone-token rejection on management, routine versus
explicit phone sign-out, unauthorized tunnel destinations, actual outbound/DNS drop
counters and resource removal. Its Docker-hairpin fixture uses a test-only source
CIDR; production retains the operator's exact LAN CIDR. This does not establish
kernel WireGuard installation, physical LAN source preservation, home router
forwarding or GrapheneOS behavior. [VALIDATION.md](VALIDATION.md) records the result.
