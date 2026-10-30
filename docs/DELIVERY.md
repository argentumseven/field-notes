# Delivery and release contract

This is the target design/contract. See [VALIDATION.md](VALIDATION.md) for current
implementation status and [RUNNING.md](RUNNING.md) for actual commands.

The target contract below includes future work. Build scripts now exist; the
server, transport and Android APK have been built. Kotlin Multiplatform/Compose
replaces Flutter; native iOS and desktop packages are deferred. A script is not evidence that its target artifact has been produced.

## Target builds

| Active target | Build host | Entry point | Output |
| --- | --- | --- | --- |
| Linux server | Linux | `scripts/build/server.sh` | Versioned binary and deployment bundle |
| LAN web/admin | Bundled into Rust server | Server build | Original HTML/CSS/JS with no runtime CDN |
| Android arm64 | Linux, pinned JDK/SDK/NDK/Go/Gradle | `scripts/build/android.sh` | Signed debug APK or operator-signed release, checksum and signer report |

Kotlin Multiplatform common UI/domain code provides a future iOS path. No iOS,
Windows or Linux desktop app build is part of the current delivery. The web UI
works in desktop browsers. Do not claim unbuilt platform artifacts exist.

Version SDKs, toolchains, lockfiles, protocol versions, migration levels, and
artifact names. Verify installer downloads with official checksums/signatures
and valid TLS. Install into explicit development tool directories, keeping
credentials and operator files outside Git. Respect proxies and CA trust.

Each build script must run from the repository root or determine its root,
check prerequisites with actionable errors, preserve failure exit status, and
verify the expected current-run outputs. Add flags for architecture and build
mode rather than silently changing signing identity or insecurely skipping
verification. Parallelism should fit available RAM; avoid building every
unrelated target during routine server development.

## Android installation experience

For GrapheneOS phones, prioritize arm64 and publish a plainly named APK, such as
`field-notes-<version>-android-arm64.apk`, together with its hash and signing
certificate fingerprint. A universal APK is optional if size is reasonable;
an x86_64 emulator APK is a separate test artifact.

Keep one stable operator-owned release signing key securely outside source and
artifacts. A debug APK is clearly labeled as development-only. Signature changes
can require uninstalling and losing local state; upgrades must preserve keys,
pairing, database migrations, and pending edits. Do not silently sign every build
with a fresh ephemeral release key. If a release key is unavailable, report that
the release build is unsigned and supply a separately labeled test APK.

Installation instructions should be brief: download APK, verify it, temporarily
allow installation from that source, install, then pair by pasting the public
connection JSON from the trusted LAN administrator after peer approval. QR and
short-lived invitation enrollment remain future work. Provide an
optional `adb install -r <apk>` route, not a developer-mode requirement.

Enrollment shows the server identity and requested workspace/role. It must not
silently trust a certificate supplied by an arbitrary QR code. Pairing uses a
trusted HTTPS origin plus authenticated approval; native key pinning, if added,
needs an explicit out-of-band check and rotation path.

Notifications have their own setup step: grant notification permission, test
local reminders and in-app delivery, and explicitly enable best-effort background catch-up if wanted. A separate UnifiedPush distributor cannot use the app's
private tunnel; it is not a default requirement in this deployment. Explain
battery settings and delivery limits; do not request unrelated permissions. Task images should
use the OS photo picker, not broad photo/library access. The normal app does not
need MDM, device administration, location, contacts, accessibility-service access,
VPN permission, or an overlay permission.

## Linux installation

Provide a repeatable `scripts/install-server.sh` with a no-write preflight/plan
mode and distinct fresh-install/upgrade behavior. Target Debian stable and Ubuntu
LTS versions explicitly selected at implementation time, on x86_64 and arm64.
Prefer a Compose deployment with non-root application containers and a documented
systemd lifecycle. Explain prerequisites and detect unsupported OS/architecture.

The installer must:

1. Check CPU/RAM/disk, Docker/Compose/systemd support, filesystem permissions,
   TLS approach, network bind addresses, and whether an existing installation
   or data directory is present. Refuse silent replacement.
2. Configure the home WireGuard hub and per-device peers, plus separate LAN-only
   administration. Show the exact UDP forward the user must configure; do not
   alter router policy automatically, replace firewall rules blindly, disable
   TLS, or publish the database or public web listeners.
3. Create private directories and separate secret/key mounts; prompt securely
   for bootstrap and recovery setup without putting values in shell history.
4. Configure trusted HTTPS, bounded upload sizes, backup destination/retention,
   notification delivery settings, and the isolated phone/client tunnel.
5. Start services, inspect readiness, then perform an authenticated functional
   create/read/update smoke workflow. An open port alone is not success.
6. Print exact install locations, service commands, recovery/backup instructions,
   server identity, and the actual artifact paths needed for phone installation.

Upgrades stage versioned artifacts, verify provenance, create a coherent backup,
check migration compatibility, close mutation admission when needed, migrate,
and run a functional health check. Do not roll an old binary back against a
new incompatible schema. Restore a matching coherent backup when rollback
requires it, rotating the server epoch and reconciling clients afterward.

Persist data and installed dependencies, but never assume live processes survive
cloud environment publication. Save tested install steps as `install_script`
and startup/readiness steps as `start_skill` when actual services exist. Use the
existing isolated checkout rather than creating extra Git worktrees.

## Working delivery slices

1. **Protocol and server foundation:** domain commands, real migrations,
   authentication/roles, audited transactions, sync/claim/idempotency tests.
2. **Usable desktop/web workflow:** original theme, task views, detail editor,
   comments, bounded images, admin screen, online and offline reconciliation.
3. **Android household release:** secure local storage, enrollment, sync,
   private WireGuard transport, validated Google-free notification behavior,
   signed installable APK, physical GrapheneOS checks. Current delivery uses
   scheduled catch-up/local reminders; permanent notification connections are
   not implemented.
4. **Deployment and recovery:** Linux installer, TLS/private hub-and-spoke path,
   backup/restore/reset fencing, updates and artifact verification.
5. **Deferred clients:** future iOS or desktop work needs its own platform
   adapters/builds and acceptance record; it is outside current Android scope.
6. **Networking hardening:** client streaming/cancellation, endpoint changes,
   WireGuard coexistence and interface isolation tests. The selected remote
   native workflow requires its private tunnel; LAN web uses HTTPS directly.

Each slice should result in an exercised workflow, not a collection of stubs.
The complete household release includes slices 1–4 and the requested desktop
targets; iOS device artifacts require the external Apple build prerequisites.

## Acceptance gates

| Area | Required evidence before claiming support |
| --- | --- |
| Claims | Concurrent independent clients produce one accepted claim; loser sees the authoritative state and retains pending edits |
| Offline | Durable outbox survives process kill, retries safely after server restart, resolves conflicts without discarding text |
| Roles | Cross-user/project object access, old sessions, viewer writes, role races, and last-owner removal are rejected |
| Erasure | Deleted/reset/revoked data cannot return from offline queues, delayed uploads, notifications, or stale snapshots |
| Images | Invalid MIME, active content, traversal, EXIF/GPS, oversized bytes/dimensions and incomplete uploads are tested |
| Encryption | No sensitive plaintext in durable blobs/fields covered by policy; corrupt ciphertext fails closed; missing-key restore is handled safely |
| Recovery | Fresh-instance backup restore checks tasks/images/comments, roles/keys, epoch change and old-client reconciliation |
| Administration | Override/reset requires appropriate role and recent reauthentication; scope preview and audited result agree |
| Notifications | App-owned event delivery, local reminders, revocation, duplicate hints, opt-out and real background/Doze limits are exercised; no FCM |
| VPN coexistence | Physical GrapheneOS phone with Bungee and a system VPN, including lockdown, allowed/blocked UDP, Wi-Fi/cellular changes |
| Desktop | Actual Linux and Windows installation, secure storage, upgrade, offline behavior and keyboard/accessibility flows |
| Android APK | Signature/checksum verified, clean install and in-place upgrade on a physical phone, permissions/manifest inspected |
| iOS | Native build/signing evidence, foreground sync, local reminders and accurately documented suspended/force-quit limits |
| Deployment | TLS verification/renewal, real off-LAN UDP reachability, LAN-only admin, no phone-to-LAN or phone-to-phone forwarding, non-root content process |
| Accessibility | Keyboard focus, screen-reader names, contrast, reduced motion, large text and mobile layouts |

Maintain a current validation record with run commands, versions, counts, and
actual artifacts. Distinguish pass/fail/skip/unrun and automated versus physical
results. No security/production-ready label based solely on a successful build.
