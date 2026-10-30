# Working on Field Notes

This is a development implementation. Read README.md, docs/RUNNING.md and
docs/VALIDATION.md before making changes. The user selected server-readable
encryption. The Android client uses Kotlin Multiplatform/Compose; Flutter has been retired.
Focus native development on Android. iOS and desktop packages are deferred.
Distinguish compiled/tested behavior from physical GrapheneOS verification.

## Persistent product constraints

- Server: Linux only, with Debian/Ubuntu the installation target.
- Android/GrapheneOS must work without Play services or FCM. No hidden Google
  push fallback, public push service, analytics, external fonts, or runtime CDN.
- Android may not consume the OS VPN slot. The selected app-contained WireGuard
  must not create a VpnService, OS routes, or DNS changes. Existing VPN and
  lockdown policies remain authoritative. Never silently bypass them.
- The user revised the initial no-port-forwarding preference: the selected
  deployment is a home WireGuard hub, one forwarded UDP endpoint, and per-phone
  app-contained spokes. Web administration is LAN-only. Do not expose it through
  the phone tunnel or assume a client allowlist replaces server firewall policy.
- Do not claim reliable background delivery on iOS without APNs, or exact
  background timing on Android. Keep platform limitations visible.
- TLS certificate verification stays enabled. Keep secrets, signing keys,
  enrollment material, and plaintext user data out of Git and logs.
- The server authorizes every operation. Client clocks do not determine claim
  ownership. Preserve offline edits and explain conflicts; no silent data loss.
- Separate admin operations from content mutation. Reject stale writes after
  deletion, revocation, restore, or workspace reset, including uploads in flight.
- Do not describe server-readable storage encryption as end-to-end encryption.
- Do not reuse upstream code/assets without recording their licenses and
  satisfying their terms. Visual inspiration does not require copying code.

## Engineering expectations

Use the existing checkout: each cloud task already has an isolated environment.
Do not create a Git worktree unless the user requests one.

Follow the dependency boundaries and release gates in docs/ARCHITECTURE.md and
docs/DELIVERY.md. Update the feature/validation status as implementation lands.
Do not create placeholder build scripts that report success without producing
and verifying their named artifact. Preserve underlying exit statuses.

Test transaction races, real persistence and restart behavior, authorization,
replay handling, retention fences, attachment bounds, and backup restoration.
UI-only checks cannot establish sync or security correctness. Mark unrun,
skipped, automated, emulator, and physical-device checks separately.

No source pushes, releases, deployments, or messages to others are implied by
local development. Preserve unrelated files and user changes.

## Android build

Read docs/adr/0002-android-kotlin-multiplatform.md. Use scripts/android.sh for
Gradle proxy/CA/tool activation and scripts/build/android.sh debug for a verified
arm64 APK. Share UI/domain code in commonMain; isolate OS dependencies in
androidMain. Do not reintroduce Flutter or require Apple tooling for Android.
The emulator test fixture may clear only the dedicated field-notes-test AVD;
never run its destructive reset against a physical/user phone.
