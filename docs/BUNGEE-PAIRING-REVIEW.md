# Pairing and failure handling compared with Bungee

## QR follow-up, November 2

Reviewed [Bungee at a6dbb71](https://github.com/argentumseven/bungee/tree/a6dbb71f6d24df1474665a7243bc61c87e0530d1):
`web/src/main.tsx`, `web/src/style.css`, Android `MainActivity.kt` and Gradle
dependencies. Its manager uses a PNG QR with medium error correction and its
phone uses JourneyApps ZXing's Activity Result scanner. Field Notes' custom
CameraX decoder swallowed frame failures and attempted unsupported source
rotation; its dense, fractionally scaled SVG also failed an actual ZXing decode
of the displayed image. Previous browser checks established image presence only.

Field Notes now uses the same Apache-2.0 scanner dependency independently,
with a private screenshot-protected activity, autofocus/orientation lifecycle,
visible permission errors, cancellation and automatic invitation review. Its
manager renders PNG at integer module sizes with medium error correction and
provides selectable/copy text if rendering fails or QR capacity is exceeded.
Invitation insert/audit is transactional. Browser checks decode actual rendered
images at three viewport widths in all four orientations with ZXing core.

The Field Notes URI, pinned CA, explicit source confirmation, scoped account,
ten-minute single-claim proof, separate administrator code approval, host peer
acknowledgement and tunnel activation remain its own protocol. Scanning cannot
overwrite existing pairing or queued work. No Bungee source/assets were copied,
and Bungee was not built or tested. Library licenses ship in Android assets.

Reviewed [Bungee main at 67ee496](https://github.com/argentumseven/bungee/tree/67ee496)
against Field Notes during the 0.3.2/0.3.3 changes. This includes actual
`server/src/enrollment.rs`, `api.rs`, `Identity.kt`, `HomeApi.kt`,
`ConnectionRecovery.kt`, `PairingReset.kt` and recovery/DDIL guides. Bungee is
AGPL-3.0; no source or assets were copied. These are independently implemented
workflow and state-machine changes. The latest review also includes the
current-key/stale-QR fix in `fbc16fa`, native request budgets, tunnel ownership,
local safety and cancellation in `67ee496`. Bungee itself was not executed.

| Area | Finding and Field Notes change |
| --- | --- |
| Stop versus erase | Bungee distinguishes saved local stop, pairing reset and Android management repair. Field Notes previously required server contact to cancel and had no separate connection pause. Pause now persists, closes only the app peer, preserves keys/cache/outbox/reminders and cannot be cleared by boot or background work. Server cancellation remains an explicit LAN action. |
| Identity stability | Bungee preserves keys unless replacement is selected. Field Notes previously regenerated keys during cancellation/reset. Both now preserve keys by default; replacement is explicit and retained edits still block destructive setup changes. |
| Lost replies | Field Notes already saves its nonce, configuration and activation receipt before side effects and retries the same request. Pause now fences late pairing replies and retains credentials across restart. Cancellation now uses saved invitation/key/claim proof if its device-ID reply was lost, consuming an unclaimed ticket without allocating a peer and refusing to cancel an active phone. An expired short receipt after actual activation still requires account-password recovery through that enrolled tunnel. |
| Failure retries | Bungee has bounded, jittered retries and distinguishes authorization rejection. Field Notes previously repeated enrollment failures every three seconds. Successful pending checks retain that cadence; failed contacts back off to five minutes, while terminal errors pause for explicit action. |
| Broken endpoint | Bungee closes/reopens its private peer during recovery. Field Notes could keep retrying a numeric endpoint resolved at launch. Failed exchanges now close its peer and force trusted DDNS resolution on the next retry. Server identity, PSK, CA and inner endpoint remain fixed. |
| Cancellation | Kotlin formerly waited for the request gate before native Stop, delaying Stop behind a stuck request. Stop now cancels the active native exchange first, then synchronizes cleanup; generation checks prevent late configuration publication and reject queued exchanges from before Stop, including across reconnection. Already transmitted bytes cannot be recalled. LAN bootstrap is bounded but lacks native request reservations: it may finish after pause, but its result cannot advance the saved attempt. |
| Stale bindings | Bungee has guarded inactive-pairing removal. Field Notes kept public-key bindings indefinitely, forcing key churn. Management can now remove revoked/expired bindings only after their peer address is released. Active/offline phones and unconfirmed removals remain protected. Fresh enrollment gets a new ID and secrets. |
| Password maintenance | Bungee's web password is separate from device identities. Field Notes' first password-change implementation revoked all phone sessions and pending enrollment. Routine changes/resets now invalidate browsers only; phone sign-out/cancellation is explicit. Device revocation is separate. |
| Credential boundaries | Field Notes phone tokens could previously authorize the LAN API. They are now rejected there regardless of account role. A separate LAN login is required for the website; phone access stays bound to the assigned tunnel source. |
| Invitation display | Bungee now binds asynchronous QR rendering to its current bundle and warns against old phone keys after reinstall. Field Notes already gets a synchronous QR for the exact invitation and submits the phone’s current locally derived key. It now removes expired QRs, disables copying on expiry and clears QR/password dialogs on session loss; no previous-key autofill is introduced. |
| Missing keys | Bungee refuses silent key replacement over a bound identity. Field Notes now also refuses generating keys over existing configuration, enrollment or retained records when its saved key field is missing. Original data stays. Native load failures become visible errors rather than escaping as LinkageError. |

## Differences retained deliberately

Field Notes keeps account-scoped QR enrollment and phone-code approval. Bungee
first transfers the phone's public key to its dashboard and returns a tunnel
bundle. Field Notes' LAN exchange avoids that manual transfer and selects the
household role before approval. Neither safely skips human trust. Root peer
acknowledgement stays: accepting a desired file is not proof of installation or
removal.

Field Notes verifies TLS 1.3 inside WireGuard and binds revocable sessions to the
peer address. Bungee additionally uses a non-exportable P-256 signing identity
and timestamp/nonce proof; its admin sessions are shorter and restart-invalidated.
Field Notes has not adopted that protocol, its clock requirements or a second-key
migration. Durable sessions and source binding are not described as equivalent
to Bungee's signing-key protection. Further hardening needs a compatibility and
key-loss design, rather than extending invitation authority indefinitely.

Planner edits need different failure semantics from telemetry. Field Notes
retains unacknowledged edits/conflicts; it does not copy Bungee's GPS capacity
eviction or discard a task queue after erasure. Comment/image bounds also differ
from small recovery-photo payloads. Roles, stale-write fences, direct Rust TLS
and the notes-only tunnel remain enforced. Bungee’s newer GPS, maps, camera,
remote ringing/flash, device-administration repair and DNS diagnostics are not
planner requirements. Field Notes does not add those permissions, network
exceptions or metadata reports. It keeps bounded HTTPS image commands rather
than Bungee’s dedicated recovery-photo route. See [APK delivery](ANDROID-DOWNLOADS.md)
for the separate permanent-release and bundled-download comparison.

## Evidence and remaining gates

Regression checks exercise late replies after pause, persistence failure,
restart with the same nonce/keys, terminal-error pause, key replacement opt-in,
missing-identity preservation, offline reconciliation, transport reconfiguration,
password/session scope and inactive cleanup with injected rollback. The Docker
fixture covers actual WireGuard/TLS, LAN rejection of phone credentials and
explicit session invalidation. Executed counts/artifacts are in
[VALIDATION.md](VALIDATION.md).

Bungee's independent native recovery activity and per-request cancellation
reservations remain more mature. Field Notes offers OS Settings from common
opening failures and relies on independent Android app controls for native/UI
failure; no separate recovery process is claimed. Physical GrapheneOS, roaming,
Doze, multiple VPNs, real home NAT/DNS changes, disk faults and production kernel
WireGuard installation remain separate deployment checks.

## Follow-up review at 3f6158e

Fetched [Bungee main at 3f6158e](https://github.com/argentumseven/bungee/tree/3f6158eafe50f01daba4b2c44e94be914b99b5b4)
and reviewed changes since `67ee496`, including `acd548e`, `e52cfb7`, `54fa948`
and `3f6158e`. The reference working tree remains detached at the earlier
revision; this review reads the fetched `origin/main` objects. No Bungee code or
assets were copied and its tests were not executed.

- **Persistent APK signing:** Bungee now downloads the APK matching its running
  server commit, verifies it, signs it with an established per-server key held
  in encrypted host storage, and exposes preparation/download/install progress.
  Missing established keys stop preparation. Field Notes keeps its original
  builder/operator signer outside the runtime bundle, verifies the entire APK
  cryptographically on Android, and serves the exact bundled APK through its
  paired server. Preserve that original signing key; do not re-sign existing
  phones with a new household key. This follow-up does not add server-side
  re-signing or public-release downloads to Field Notes.
- **Resumable software downloads:** Bungee can resume digest-named partial APKs
  and reuse a verified staged APK. Field Notes currently removes interrupted
  partial downloads and retries; this is a remaining usability difference, not
  grounds to reset enrollment or planner data.
- **Reconnect retains history:** Bungee separates retirement of connection keys
  from deletion of stored device history and can reconnect the same logical
  device. Field Notes' tasks/comments/images belong to the household/accounts,
  so inactive phone-binding cleanup already preserves planner data. No GPS,
  MDM or recovery-photo state machine is introduced.
- **Debian boot repair:** Bungee now checks the real cryptsetup target, generator
  and unlock binary, adds `nofail` boot entries and provides guarded
  `repair-boot`. Field Notes has no Bungee LUKS mount or Docker daemon mount
  dependency to repair. On a shared VM, Bungee's existing Docker mount dependency
  can still keep Docker stopped; recover Bungee first. Field Notes never removes
  that dependency, formats its image or restarts shared Docker as a workaround.

The Field Notes branch review found both `fix/manual-port-selection` and
`fix/android-gradle-verification-cacerts` already squash-merged into `main`,
followed by the duplicated-installer/regex repair and Android memory/concurrency
limits. The original branch tips equal their corresponding squash snapshots;
use current `main` rather than checking out those older tips. Fresh checks also
caught cancellation occurring after the root check and an obsolete test passing
both `--plan` and `--apply`; these were corrected. Source-build Docker preflight
now uses the same local-engine selector protections as installation/removal.
Routed management readiness now verifies the owned runtime version and pinned
TLS/source restriction without adding the host to the administrator allowlist.
See [the Debian procedure](DEBIAN-INSTALL.md) and [validation](VALIDATION.md).
