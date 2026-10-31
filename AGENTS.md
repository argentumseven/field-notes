# Working on Field Notes

This is a development implementation. Read README.md, docs/RUNNING.md and
docs/VALIDATION.md before making changes. The user selected server-readable
encryption. The Android client uses Kotlin Multiplatform/Compose; Flutter has been retired.
Focus native development on Android. iOS and desktop packages are deferred.
Distinguish compiled/tested behavior from physical GrapheneOS verification.

## Persistent product constraints

- Server: Linux only, with Debian/Ubuntu the installation target.
- Serve HTTPS directly in Rust. No Caddy, reverse proxy or load balancer. Keep
  LAN administration separate from the WireGuard-only notes endpoint and check
  actual connection addresses; forwarded identity headers are not trusted.
- Compact charcoal/paper with teal accents, clean rows, no decorative art.
  Priority and planned day are distinct from an optional due date. Calendar and
  Android widgets use our own data without Google services or Calendar access.
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

## Delivery and install verification

Android uses a visible special-use foreground service, cached AlarmManager
reminders and WorkManager fallback, without Google services or VpnService.
Cross-user forwarding/sound requires GrapheneOS settings and physical testing.
Use `scripts/capture-home-widget.mjs` for actual launcher hosting and delivery;
`11-widget.png` alone is only an in-activity renderer comparison. The guarded
`test-android-uninstall.mjs` destroys only the disposable field-notes-test AVD's
app installation; never use it against a user's phone.

Server installation prompts for management HTTPS and WireGuard UDP ports when
missing. Noninteractive apply requires explicit choices. WireGuard suggests
49272 (one above Bungee's guide example); `--bungee-port` derives one higher from
an operator's actual Bungee listener. The inner phone API stays TCP 8443, overlay
only, and must not be router-forwarded.
