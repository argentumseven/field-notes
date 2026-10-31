# Review evidence and unresolved choices

The following repositories were read through existing HTTPS Git access. The
reference clones are outside the project checkout and are not dependencies.

## Inspected sources

- [Talaris](https://github.com/jpmoo/talaris/tree/fccf4578ee7b0e371e1d85d077784ced8ef4e3f7),
  commit `fccf4578ee7b0e371e1d85d077784ced8ef4e3f7`:
  README, package license, design/screenshot documentation, and the actual
  `tasks-eisenhower.webp` image. It is a Hyprland/PySide6/Node desktop product,
  GPL-3.0-only. This review did not install or run it.
- [Bungee](https://github.com/argentumseven/bungee/tree/ec7c089debf12ddb3541a7e34f993b5e1354f0ac),
  commit `ec7c089debf12ddb3541a7e34f993b5e1354f0ac`:
  README, `native/transport/transport.go`, Android manifest and `HomeTunnel.kt`,
  platform/network/security/validation documents, and Android setup/build helpers.
  Its license is AGPL-3.0. Its transport creates a userspace WireGuard/netstack
  peer with an ordinary UDP socket and fixed inner API; it does not create an OS
  VPN route. Its README explicitly says direct remote use needs a reachable UDP
  endpoint and includes no relay/NAT traversal. This review did not run Bungee's
  tests or verify its previously reported validation results independently.
  The installer/network source was revisited for port selection: Bungee has no
  default port; its `docs/INSTALL.md` example is 49271. Field Notes uses 49272,
  with an adjacent-port installer option for a different actual Bungee listener.
- [AOSP VpnService source](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/net/VpnService.java):
  read the documented one-active-VPN constraint and socket/underlay behavior.
- [UnifiedPush documentation](https://github.com/UnifiedPush/documentation/tree/8c61ba91f4a80231e205f6e0b2b712b591a7ca4b):
  Android connector/distributor documentation and architecture discussion.
  Android requires a distributor; some distributors/fallbacks use FCM, so
  UnifiedPush alone is not proof of a Google-free deployment.

Additional platform documentation to follow during implementation:

- [Apple background execution strategies](https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app)
- [Apple remote notifications](https://developer.apple.com/documentation/usernotifications)
- [Kotlin Multiplatform supported platforms](https://www.jetbrains.com/help/kotlin-multiplatform-dev/supported-platforms.html)
- [Android background work](https://developer.android.com/develop/background-work/background-tasks)

These links are implementation references, not evidence of completed Field
Notes builds or physical-device behavior.

## Licensing boundary

The proposal uses original implementation and visual tokens inspired by the
reference's layout, not copied code or assets. If upstream code is reused, record
exact source, copyright, license, modifications, and corresponding-source
obligations. The user owning Bungee's repository does not remove the need to
check third-party licenses inside it. Choose Field Notes' license deliberately
before distributing code; this design review does not invent ownership or
relicense upstream material.

## Decisions

Confirmed by user:

- The server may decrypt content; simpler administration and recovery take
  priority over excluding the server from the trust boundary.
- Server target is Linux, likely Debian/Ubuntu; phones are GrapheneOS; Linux and
  Windows computers are required; iOS is a secondary interest.
- No Google notification services. The initial no-port-forwarding preference
  was explicitly revised: home hub-and-spoke WireGuard with UDP port forwarding
  is the selected deployment. The web interface is LAN-only.

Defaults proposed for implementation:

- HTTPS over app-contained WireGuard for remote native clients, separate LAN
  HTTPS for web/admin. No requirement for another Android OS VPN.
- One Rust service/SQLite store and one Kotlin Multiplatform/Compose Android client, with
  role-gated web administration and native platform adapters.
- First valid server transaction wins claims. Offline claims are provisional;
  client timestamps cannot steal ownership. Keep conflict drafts.
- App-owned notification delivery inside the private tunnel, local reminders,
  and best-effort catch-up; persistent background delivery needs an early
  platform feasibility test. A separate UnifiedPush app cannot inherit the
  private stack. Provider-independent iOS mode has limited background sync.
- Tasks/projects/notes/comments/images first. Full desktop-shell replacement,
  AI, plugins, and calendar federation are separate scope.

Selected topology and remaining feasibility checks:

- The user will configure inbound WireGuard UDP forwarding to the home server.
  Verify no upstream CGNAT/blocked inbound route and test from outside the LAN.
- Use app-contained phone peers to preserve Android's VPN slot. A standalone
  WireGuard Android VPN would consume that slot and is a different deployment.
- The admin website remains LAN-only and unreachable from phone peer traffic.
- Notification latency while suspended/Dozing cannot be promised before testing
  the chosen supported Android service design on the user's platform.

External build/release prerequisites, to resolve when needed:

- macOS/Xcode and Apple provisioning for installable iOS device builds.
- Windows builder/toolchain for a tested Windows installer.
- Stable Android release signing key held outside the repository.
- Actual server address, public WireGuard endpoint/DDNS, LAN management bind,
  non-overlapping overlay subnet, and TLS trust/renewal approach for deployment.

None of those credentials or signing-key values should be sent in chat.

Current scope correction: Flutter was replaced by Kotlin Multiplatform/Compose
at the user’s request. Android is the active native target; iOS and desktop
packages are deferred. See ADR 0002 and VALIDATION.md.


## Container deployment reference

[Bungee at ec7c089](https://github.com/argentumseven/bungee/tree/ec7c089debf12ddb3541a7e34f993b5e1354f0ac)
was inspected for its app/gateway network namespace and install/uninstall
boundaries. Its server is containerized and constrains egress, with an optional
DNS resolver exception. Field Notes uses its own original implementation and no
runtime DNS exception. No Bungee code or assets were copied; its AGPL license
still applies to that separate project. Field Notes images include distribution
runtime dependency notices; the Debian gateway retains installed package notices.
