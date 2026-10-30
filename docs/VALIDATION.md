# Implementation and validation record

Field Notes 0.1.0 is a development implementation, not an independently audited
production release. Android now uses Kotlin Multiplatform/Compose; Flutter has
been removed from the active source/build. iOS and native desktop targets are
deferred at the user's request.

## Android implementation

- Shared Kotlin models, Compose matrix/board/list UI, task notes/comments/images,
  durable offline outbox, provisional state, and retained conflicts.
- Immutable operation IDs across retries; causal revision updates only through
  accepted local predecessors. Old editor/photo-picker workspace versions are
  preserved across reset/restore instead of silently retagging old work.
- Android Keystore/AES-GCM local encryption and atomic files. Missing keys or
  failed authentication never create empty replacement workspaces. Image cache
  encryption is scoped to server/account and image identity.
- Original Go userspace WireGuard/HTTPS stack and UTF-8-safe JNI adapter. No
  VpnService, OS routes, Google Play services, FCM, analytics, or public fallback.
  The adapter verifies the expected server identity before sending credentials.
- Device-credential UI lock when the phone has a secure screen lock, screenshot
  protection, disabled OS backups, scoped photo picker, sensitive clipboard hint.
- Optional WorkManager background catch-up and generic notifications, plus local
  due-date reminders after 09:00 device-local time with persisted deduplication.
  These are scheduled/best-effort, not instant push or exact alarms. Idle peers
  do not send a permanent 25-second NAT keepalive.
- Editable endpoint for switching between trusted home-LAN and remote routes,
  preserving server key/CA, device identity, queued commands and local content.

## Executed checks

| Check | Result / practical limit |
| --- | --- |
| Kotlin shared-domain tests | 14 passed: failed persistence, process restart, ambiguous commit/replay, dependent edits, rejected predecessors, reset/restore fences, expired sessions, viewer role, atomic drafts, reminders, captured editor versions, owned acknowledgements, endpoint changes |
| Actual Android/JVM JNI adapter integration | Passed against real Go WireGuard peers, HTTPS and a Rust server; Unicode, server claim authority, wrong-server and admin-path rejection exercised |
| Android emulator Keystore tests | 4 passed: encrypted reopen/no plaintext, tampering, missing key without replacement, account/image AAD binding |
| Android emulator Compose UI | 2 passed: first launch/public key/explicit trust, and a task title remains visible after a failed durable save |
| Android emulator native/manifest integration | 2 passed: real Android JNI/WireGuard/TLS/Rust round trip and installed no-VPN/no-location/no-device-admin manifest contract |
| Android build and lint | APK assembled; lint has no errors (one arm64-only/ChromeOS support warning) |
| APK inspection | Signature and SHA-256 produced; ZIP and ELF native LOAD alignment verified at 16 KiB; merged manifest and native libraries inspected; no Flutter runtime |
| Rust cryptography unit test | 1 passed; ciphertext tampering and record/AAD substitution rejected |
| API integration suite | 13 passed with real processes/database: concurrent claims, restart/replay, roles, images, rollback injection, calendar validation, reset, backup/restore |
| Chromium workflow | 1 passed; projects/tasks/notes/comments/claim/admin, desktop/mobile layout, no third-party page requests |
| Installer plan/validation | 6 passed; no privileged production-host installation performed |
| Native Go transport | 2 passed under race detection; real peers/TLS, request bounds, wrong CA, stopped/invalid transport |
| Linux server and HTTPS template | Optimized x86_64 binary built and API-tested; official-checksum-verified Caddy validates template |

The emulator is AOSP Android 15/API 35 x86_64, without Play services. This host
has no KVM. An initial software-emulation run aborted before tests during app
startup while Android system services were also crashing. The final run with
software GLES rendering and AOT-prepared APKs passed all eight device checks.
Assertions, app permissions, TLS verification and encryption checks stayed
active. Emulator results do not establish physical GrapheneOS, ARM execution,
hardware-backed Keystore, battery use or VPN coexistence.

Device results are in `dist/android-test-results/vault-ui.txt` and `native.txt`;
JNI host integration XML is `jni-integration.xml`. Host test XML is under
`apps/mobile/build/test-results/hostTest/`. The phone artifact is the separate
arm64 APK in `dist/android/`, with checksum and public signer report.

## Build and test commands

```sh
bash scripts/setup/cloud.sh --android
bash scripts/check.sh
bash scripts/android.sh hostTest lintDebug
bash scripts/build/android.sh debug
```

Native JNI integration additionally builds the Linux Go library, its JNI C
wrapper and `native/cmd/testhub`, then runs `node scripts/test-native-client.mjs`.
The dedicated AOSP emulator fixture is `scripts/test-android-emulator.mjs`; it
requires the x86_64 companion APK/test APK and the `field-notes-test` AVD. It
verifies that AVD before clearing app data, AOT-prepares only the fixture's APKs,
and never operates on a physical phone. Neither test hub nor its synthetic
credentials are shipped in the installed server or phone APK.

## Remaining checks and scope

- Physical GrapheneOS install/upgrade, Bungee plus a system VPN/lockdown, real
  Wi-Fi/cellular roaming, reboot/force-stop behavior and battery consumption.
- Background delivery/Doze timing and local reminders on a real phone. The
  reminder policy is unit-tested; no instant-delivery guarantee is claimed.
- Home installer `--apply`, real interface/firewall isolation, certificate trust
  and renewal, router forwarding, CGNAT and IPv6 deployment behavior.
- Operator-managed production APK signing; the provided APK uses a persistent
  development signer. Changing signers can require uninstalling/re-pairing.
- Web-managed/QR peer enrollment, per-project ACLs, passkeys/MFA, automatic server
  upgrades/rollback, scheduled server backups, key rotation, trash restore,
  standalone notes/canvas/calendar/AI/plugins remain future work.
- iOS/native desktop packages and their platform adapters are not implemented.

Native sync uses full authorized snapshots rather than an incremental event
stream. Bounds remain 10,000 server records/tombstones, 8 MiB metadata, 128 MiB
images and 32 MiB retained receipts. Client state is capped at 32 MiB with bounded
pending/conflict queues. Receipt history is not pruned in a way that allows replay.

Server-readable at-rest encryption uses a versioned master key with fresh nonces
and record-bound AAD. Per-object wrapping/key rotation from the original proposal
is future work. Usernames, roles, object IDs/kinds/relationships, revisions,
event metadata, counts and sizes remain plaintext database metadata. Protect the
host/key source, WAL, logs and backups. The web UI retains drafts in the current
tab; it does not implement an encrypted offline browser vault.
