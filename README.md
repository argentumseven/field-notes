# Field Notes

A self-hosted household planner: Inbox, lists, projects, standalone notes, comments,
images, optional dates, priorities and a private calendar. Compact charcoal/paper, teal accents, clean rows.
Home Linux server, Android app, and a LAN website for Linux/Windows browsers.

**Development version 0.2.0.** Rust serves HTTPS directly. **No Caddy, reverse
proxy or load balancer.** Android uses Kotlin Multiplatform/Compose with an
app-contained WireGuard stack, without taking Android's VPN slot or using Google
services. iOS/native desktop packages are deferred.

[Installation](docs/RUNNING.md) · [Phone enrollment](docs/ENROLLMENT.md) ·
[Screenshot walkthrough](docs/WALKTHROUGH.md) · [Validation](docs/VALIDATION.md)

![Compact Focus view with ranked and undated tasks](docs/screenshots/web/02-focus.png)

## Planner and administration

- Capture tasks in Inbox without creating a project. Lists hold shopping, routines
  and checklists; Projects group an outcome. Move tasks between either or Inbox.
  Standalone Notes hold shared reference material with pinning and search.
  Unscheduled, Waiting and Completed views surface different kinds of work.
- Focus ranks tasks using priority, optional due date, planned day, waiting time
  and estimate. Pins come first; blocked/completed/other-claimed/future-planned
  tasks stay out of Focus. Each task explains its score. No artificial due dates.
- Calendar shows task due/planned days and all-day/timed events. Dates and time
  zones have distinct semantics; no Google or OS Calendar integration.
- Task notes, image attachments and comments; encrypted offline edits/outbox,
  replay-safe retries, server-arbitrated claims and retained conflicts.
- QR enrollment: select an account, scan on home Wi-Fi, enter the phone's code
  on the server, then wait for peer installation and tunnel activation. Private
  keys stay on phones. Revocation stops content access immediately and tracks
  confirmed peer removal separately.
- LAN management: users/roles, devices/sessions, audited claim overrides, backup,
  workspace clearing and server state. Owner/admin/member/viewer household roles.
- Android home widget, redacted by default, opt-in title previews, no Google ties.
  Optional scheduled catch-up and local reminders are best-effort, not instant push.
- Keystore/AES-GCM local vault, device credential UI lock, screenshot protection,
  scoped image picker, offline QR scanner, no analytics/runtime CDN/FCM/Play services.

The server can decrypt content, as requested. Protect its master key, disk and
host configuration. This is not end-to-end encryption excluding the server.
[Security](docs/SECURITY.md) describes exact boundaries.

## Install or build

The signed arm64 development APK, checksum and signer report are under
`dist/android/field-notes-0.2.0-android-arm64-debug.apk`. Copy it to your phone,
verify hash/signer, and open it to sideload. Preserve the development signer for
in-place updates. Operator release builds require your separately protected key.

```sh
bash scripts/setup/cloud.sh --android
source scripts/dev-env.sh
bash scripts/check.sh
bash scripts/build/android.sh debug
bash scripts/build/server.sh
```

On your Debian/Ubuntu host, review then apply the installer, using your own LAN
and reachable home DDNS/IP endpoint:

```sh
sudo bash scripts/install-server.sh --plan \
  --lan-ip 192.168.1.10 --lan-prefix 192.168.1.0/24 \
  --endpoint your-home.example.net:51831
# Repeat with --apply after reviewing the plan.
```

Forward one UDP port only. LAN management is direct TLS on 9443, and the isolated
WireGuard notes endpoint is 8443. The installer includes local certificate
issuance/renewal, a nonroot app service, peer isolation and constrained root peer
provisioning. No manual phone peer JSON is needed. See [RUNNING.md](docs/RUNNING.md)
for trust, enrollment, exact artifact paths and recovery.

## Organization and evidence

- `apps/server/src/store/`: transactional auth, commands/planning, enrollment, admin and backup.
- `apps/admin/`: bundled planner/calendar/management, no external page dependencies.
- `apps/mobile/`: shared Kotlin domain/UI, Android adapters/widget/scanner and tests.
- `native/`: endpoint-only WireGuard/HTTPS, scoped LAN enrollment, C/JNI bridge.
- `scripts/`, `deploy/`, `tests/`: reproducible builds/install, constrained helpers,
  cross-language planning fixtures and real-process/transport checks.

[Architecture](docs/ARCHITECTURE.md) · [Network](docs/NETWORK.md) ·
[Sync](docs/SYNC.md) · [Planner research and scoring](docs/PLANNER-RESEARCH.md)

Screenshots use synthetic data. Browser captures run against the real Rust server;
Android captures render actual Compose/RemoteViews using a test-only connection.
Physical GrapheneOS/VPN coexistence, home routing/isolation and independent review
remain separate deployment checks. Automatic upgrades and per-project ACLs remain
future work. Check the validation record before treating any feature as verified
on your own hardware.
