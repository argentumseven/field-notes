# Architecture proposal

This is the target design/contract. See [VALIDATION.md](VALIDATION.md) for current
implementation status and [RUNNING.md](RUNNING.md) for actual commands.

Status: design, pending implementation and validation. The server can decrypt
content. The selected deployment is a home Linux WireGuard hub with per-phone
spokes and a forwarded UDP endpoint. Administration stays LAN-only. See
[the network contract](NETWORK.md).

## What is being built

One household workspace with projects, shared tasks, rich details, comments,
images, assignments, due dates, reminders, and administrative controls. Provide
list, board, and Eisenhower views over the same task data. Native Android is the
first mobile delivery target; Linux, Windows, and web use the same product model.
iOS is a supported design target with explicitly limited background behavior.

Talaris is a Hyprland desktop shell, using Qt/Wayland surfaces and local sockets.
It is not a portable, multi-user sync server to deploy unchanged. Reuse the
interaction ideas: project navigation, task collections, quiet cards, quick add,
and an inspector. Build a separate cross-platform product around those ideas.

## Recommended components

| Component | Proposed implementation | Reason |
| --- | --- | --- |
| Linux server | Rust, Axum, SQLite in WAL mode | Typed API boundaries, transactional household-scale coordination, one persistent service |
| Shared client | Kotlin Multiplatform + Compose; Android target now | Shared UI/domain with Android platform adapters; iOS deferred |
| Administration | Bundled LAN web UI and separate server admin handlers | Bundled LAN HTML/CSS/JS surface with independent server authorization |
| Local client storage | Atomic encrypted native cache/outbox; in-tab web drafts | Keystore-backed Android encryption and platform-independent sync model |
| Live updates | Authenticated event stream; bounded catch-up HTTP API | Live delivery accelerates synchronization; durable cursors supply correctness |
| Android notifications | App-owned authenticated event connection through the private tunnel; local reminders and best-effort catch-up | Only WireGuard is publicly reachable; a separate push distributor cannot use this private stack |
| Native remote transport | Bungee-style app-contained WireGuard/netstack adapter carrying HTTPS | Dedicated encrypted app transport without OS VPN routes; only the home UDP endpoint is exposed |
| Browser transport | HTTPS on the home LAN | Local administration without a public web endpoint |
| HTTPS edge | Caddy or equivalent, trusted certificates, strict reverse-proxy boundary | TLS, request limits, same-origin web serving |

Do not add a message broker, Kubernetes, separate search cluster, distributed
database, or microservices for a two-person household. A single-writer database
is sufficient initially. Keep domain boundaries modular so a measured future
capacity problem can justify a change.

The Android toolchain is now pinned and installed. See the actual dependency
versions and current tests in VALIDATION.md and the Kotlin decision in
[ADR 0002](adr/0002-android-kotlin-multiplatform.md). The larger module layout below
remains a target design, not a claim that every planned package exists.

## Intended repository layout

The directories below are an implementation plan, not existing source modules.

```text
apps/
  server/                  Rust binary, composition root, HTTP/admin handlers
  mobile/                  Kotlin Multiplatform UI/domain, Android adapters
packages/
  server-domain/           Task transitions, policy, roles; no HTTP dependencies
  server-storage/          Migrations, transactions, event log, durable jobs
  client-domain/           Models, offline commands, reconciliation, conflicts
  client-storage/          SQLite/IndexedDB adapters and outbox
  protocol/                Versioned schemas, error codes, shared fixtures
  design-system/           Tokens, controls, accessibility and UI components
  platform/                Keystore/Keychain, images, notifications, background work
  transport/               HTTPS over native WireGuard; LAN HTTPS web adapter
deploy/
  compose/                 Linux deployment, explicit image versions/digests
  systemd/                 Service/tunnel lifecycle and timer definitions
  wireguard/               Hub configuration, peer admission and firewall policy
scripts/
  setup/                   Toolchain setup and preflight checks
  build/                   Per-target artifact-producing commands
  release/                 Version checks, manifests, checksums and signatures
tests/
  contracts/               Client/server protocol and migration compatibility
  integration/             Real DB, processes, races, restart and fault injection
  e2e/                     Browser and device workflows
docs/
  adr/                     Short decisions recording context and tradeoffs
```

Domain code must not import UI, HTTP, notification providers, or WireGuard.
Server handlers delegate to use cases; only storage owns SQL and transaction
mechanics. Notifications consume committed events rather than making task writes.
Clients render cached data and submit domain commands, not direct SQL mirrors.
One protocol definition generates or verifies client/server boundary types.
Do not force identical schemas on server and local caches.

## Reachability and the VPN question

Android's system VPN API ordinarily provides one active VPN per user/profile.
Bungee avoids taking that slot by using wireguard-go and a gVisor network stack
inside the application. Its outer packets are ordinary UDP sockets. The reviewed
transport dials one fixed inner API and does not create a VpnService.

Field Notes will use the same pattern, with its own keys, peer identity, allowed
destinations, and lifecycle. Two app-contained peers need not compete for the
system VPN slot. Their private stacks are independent, but outer sockets still
follow the OS network and any active VPN. UDP filters, VPN lockdown, captive
portals, roaming, MTU, battery restrictions, and blocked destinations can break
connectivity. Coexistence is a physical-device test requirement, not a blanket
guarantee. Do not copy Bungee's application-specific address/port restrictions.

A browser cannot open a raw WireGuard UDP socket. The admin interface is served
over LAN HTTPS. Native clients use the dedicated phone/client API through the
private stack. A browser on a phone cannot automatically inherit that stack.

The user's revised plan accepts one home UDP port forward. It works if the home
endpoint has reachable public IPv4/IPv6 and the upstream permits inbound UDP.
CGNAT/double NAT can still prevent that; verify actual off-LAN reachability.
No VPS relay is required or selected. Do not silently add one if UDP is blocked.

Use separate device and administration listeners plus server-side interface and
firewall restrictions. Client AllowedIPs restrict app intent but are not the
security boundary against a modified client. No phone-to-LAN, phone-to-phone,
admin, SSH, or general internet routing is admitted through the phone interface.
Use an independent subnet from Bungee and any existing home/system VPN routes.

Home HTTPS uses a private CA with explicit trust/enrollment, or a valid domain
certificate issued through DNS-01 without exposing the web service publicly.
Plan certificate renewal and native trust/pinning rotation. Pinning must be
bootstrapped from the trusted LAN administrator, not arbitrary QR content.

Native Android/desktop transport needs a stable FFI boundary to the userspace
network stack; iOS needs a compatible native framework. Requests, image streams,
cancellation, body limits, redirects, connection cleanup, IPv4/IPv6 endpoints,
and key zeroization need tests. Bungee's fixed small JSON exchange API must not
be copied unchanged for potentially large image downloads and uploads.

## Notification model

The only public listener in the selected topology is WireGuard UDP. A separate
UnifiedPush distributor cannot access Field Notes' process-private network stack.
Do not claim that installing ntfy alone supplies notifications through this
tunnel. Options are an app-owned event connection, a deliberately integrated
embedded distributor, or an independently reachable push service. The last
option changes exposure and requires an explicit user decision.

Default to app-owned event delivery through the tunnel while the app is active,
locally scheduled reminders, and best-effort background catch-up. An opt-in
continuous notification connection requires a legitimate supported Android
foreground-service design, visible notification, battery settings, and physical
Doze/roaming/reboot tests before claiming background push. Do not misuse a
dataSync service indefinitely: modern Android versions impose time limits.
Treat this feasibility test as an early implementation gate. If it cannot meet
the desired latency, report the tradeoff rather than disguising polling as
guaranteed push. No FCM or public push fallback is allowed.

Push is a hint to fetch durable changes, not a command or the source of truth.
If a separate push adapter is added, use random event identifiers or generic
wake-ups, not task text, attachment
names, invitation tokens, or credentials. Endpoint capability URLs are secrets.
Re-check the current user/device permissions before fetching and displaying data.
Lock-screen notifications are generic by default; detail previews are opt-in.

Task state and notification outbox commit in one DB transaction. Delivery is
at-least-once with retry, deduplication, expiration, and a visible last-success
status. A due-date scheduler records job revision and timezone handling; stale
jobs after edits, completion, revocation, or deletion must not send reminders.

Android Doze, force-stop, reboot before first unlock, notification permission,
connection health, and battery policies affect delivery. WorkManager is a
best-effort catch-up mechanism, not an exact-time or always-connected service.
A permanent foreground connection, if offered later, is explicit and visible,
and still cannot guarantee delivery. No safety-critical reminder guarantees.

iOS does not give ordinary apps unlimited background sockets. For the initial
Google-free, provider-independent iOS mode, support foreground synchronization,
OS-scheduled best-effort refresh, and locally scheduled notifications for already
downloaded tasks. These can become stale during outages. Reliable remote wake-up
requires a separate opt-in APNs design; APNs is Apple infrastructure even when
the application server is self-hosted. Never imply a custom tunnel solves this.
Browser push can also involve browser-vendor infrastructure; keep it disabled
by default in the privacy-preserving web mode.
