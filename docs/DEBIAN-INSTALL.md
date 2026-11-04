# Debian 13 server installation

Use Debian 13 x86_64 with systemd and kernel WireGuard support. The prebuilt
server bundle includes the server, both offline Docker images and the signed
ARM64 APK. It avoids building Android/Rust on a shared home server. Copy the
current bundle and its SHA-256 file to the server before starting.

These commands are for a fresh Field Notes installation. An existing or partial
installation must be inspected rather than purged: retain its database, master
key, CA, hub identity and original APK signer. An existing Docker Engine is
reused without changing its configuration or other applications. If another
service prevents Docker from starting, repair that dependency first.

Open a root shell (`sudo -i` if your account uses sudo). Then:

The wizard supports Backspace/Delete and arrow keys; Escape cancels the current
run. It suggests `admin` for the initial owner name and hides password input.
For an existing failed install or forgotten password, use the
[recovery guide](TROUBLESHOOTING.md) instead of the fresh-install commands.

```bash
(
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo 'Run this in a root shell.' >&2; exit 1; }
. /etc/os-release
[ "$ID" = debian ] && [ "${VERSION_ID%%.*}" = 13 ] || {
  echo 'These package commands target Debian 13.' >&2; exit 1;
}
[ "$(dpkg --print-architecture)" = amd64 ] || {
  echo 'This bundle requires an amd64/x86_64 server.' >&2; exit 1;
}
for fn_path in /opt/field-notes /etc/field-notes /var/lib/field-notes /var/lib/field-notes-peer-helper; do
  if [ -e "$fn_path" ]; then
    printf 'Existing Field Notes state at %s. Stop and inspect; do not delete keys or data.\n' "$fn_path" >&2
    exit 1
  fi
done
apt-get update
apt-get install -y --no-install-recommends ca-certificates python3 openssl wireguard-tools kmod
if ! command -v docker >/dev/null 2>&1; then
  apt-get install -y docker.io docker-compose
  systemctl enable --now docker
fi
fn_docker() {
  env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
    docker --host=unix:///var/run/docker.sock "$@"
}
fn_docker info --format 'Local Docker Engine: {{.ServerVersion}}'
fn_compose_version="$(fn_docker compose version --short)"
python3 -c 'import re,sys; m=re.match(r"v?(\d+)\.(\d+)",sys.argv[1]); assert m and tuple(map(int,m.groups())) >= (2,24), "Docker Compose 2.24+ is required"' "$fn_compose_version"
modprobe wireguard
read -r -p 'Full path to field-notes-0.3.6-linux-x86_64.tar.gz: ' fn_bundle
fn_bundle="$(realpath -- "$fn_bundle")"
# Keep the two downloaded files beside each other with their supplied names.
(cd "$(dirname -- "$fn_bundle")" && sha256sum --check "$(basename -- "$fn_bundle").sha256")
fn_stage="$(mktemp -d /root/field-notes-install.XXXXXX)"
install -m 600 -- "$fn_bundle" "$fn_stage/server.tar.gz"
mkdir "$fn_stage/bundle"
tar --extract --gzip --no-same-owner --file "$fn_stage/server.tar.gz" --directory "$fn_stage/bundle"
cd "$fn_stage/bundle"
bash scripts/install-server.sh --apply
systemctl is-active field-notes field-notes-peers.timer field-notes-certificates.timer
openssl x509 -in /etc/field-notes/tls/ca.crt -noout -sha256 -fingerprint
printf 'Installer bundle retained at %s for inspection/recovery.\n' "$fn_stage"
)
```

If Docker already exists but Compose is missing or too old, install the matching
Compose plugin for that Docker installation first. The block deliberately stops
instead of replacing an existing engine. Debian's `docker-compose` package is
Compose v2.26 and includes the Docker CLI plugin on Debian 13. Debian 12 and
Ubuntu need their own package instructions.

The wizard asks for the server's private LAN IPv4 bind address, the allowed
**administrator client source CIDR**, public DDNS/IP endpoint (blank means
LAN-only), a nonoverlapping WireGuard /24 overlay, and two explicit ports.
Neither port has a default. Review the printed plan, type `INSTALL`, and choose
and confirm the owner password. TCP 8443 is reserved for the private client API.

When the admin CIDR is on another routed VLAN, the host itself may correctly be
outside that allowlist. The installer checks the owned running version and
pinned TLS 1.3 inside the gateway namespace and confirms that loopback receives
HTTP 403. It does not add an ACL exception. Complete the real browser login from
an allowed administrator client. Same-subnet installs retain the authenticated
HTTP login/snapshot/download/logout check from the host.

After installation, forward only the selected WireGuard UDP port. Trust only
the public `/etc/field-notes/tls/ca.crt` in the administrator browser after
checking the printed fingerprint. Open the exact management URL, sign in, create
household users, and download the APK from Sign in or Management. Phones receive
the public CA through their enrollment QR and need no system-wide CA trust.
Never export the private CA key, TLS leaf private key or server master key.

Standard prebuilt and source server installs use the exact public GitHub APK
with the same pinned signing certificate. A new server does not create a new
Android signing identity. Existing custom-signed installations retain their
original key for upgrades; do not uninstall a paired phone or erase its queue
to work around a signing mismatch.
See [UPDATES.md](UPDATES.md) for compatible server upgrades.

For a complete source checkout instead, current `main` supports the guided
`bash scripts/install-server.sh` entry point as root. It prepares verification
tools, downloads the matching verified public APK and builds the server/images
after confirmation. No APK private signing key is needed on this host. Still
allow substantially more memory/disk than the runtime-only container footprint.
The two fix branches are already squash-merged; use current `main`.
