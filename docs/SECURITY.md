# Security model

This is a development implementation with automated security checks, not an
independent audit. [VALIDATION.md](VALIDATION.md) distinguishes executed tests
from physical deployment requirements.

## Trusted endpoints and encryption

The server and authorized administrators can decrypt content, as selected by
the user. A compromised server or unlocked authorized device can expose data.
The Android app encrypts cache, drafts, pending commands, images, credentials and
WireGuard keys with AES-GCM under a nonexportable Android Keystore key. Atomic
writes prevent publishing unpersisted edits. Missing keys/tampered ciphertext
fail closed and do not create an empty replacement workspace. The device
credential locks the UI; the key permits scheduled background reads, so it is
not configured for a biometric prompt on every cryptographic operation.

The server encrypts record payloads, normalized attachments, device secret
payloads and backups with authenticated AES-GCM and associated-data binding.
The master key is outside the data directory. Passwords use Argon2id; random
session/invitation/activation capabilities are revocable and bounded. Database
identifiers, roles, public peer keys, IP assignments, timestamps, indexes and
sizes are not all encrypted. Root desired-peer files, host WireGuard config,
private CA/leaf keys and process memory also contain sensitive material. Protect
host permissions, disk, swap, coherent backups and the recovery key. No claim of
end-to-end encryption excluding the server is made.

WireGuard uses per-device keys and unique PSKs. Direct Rust TLS requires TLS 1.3
and validates trusted certificates. Native clients pin the explicitly trusted
public CA and verify name/expiry. There is no plaintext, redirect, proxy or public
relay fallback. Transport metadata and DDNS lookups remain observable.

## Enrollment and network isolation

See [ENROLLMENT.md](ENROLLMENT.md) for account-scoped short-lived invitations,
nonce-bound retries, human code approval, root provisioning acknowledgement and
source-bound activation. A malicious QR cannot be made safe by cryptography
alone: trust must originate from the real LAN administrator. The phone explicitly
reviews and confirms that source before sending its public key/claim.

The root helper cannot accept arbitrary commands/routes/interfaces. The content
service has no NET_ADMIN and cannot read the private CA key. The helper validates
bounded canonical keys, unique /32 assignments, owner/mode/symlink constraints,
then acknowledges only after apply/save succeeds. Modified clients are confined
by host nftables; client AllowedIPs alone are not the boundary. Actual source
addresses bind phone sessions. Forwarded headers never grant identity.

Revocation denies application access immediately and shows peer removal pending
until acknowledged. Addresses cannot be reused before removal acknowledgement.
A failed reconciliation does not produce a successful acknowledgement. Restore
invalidates sessions/invitations and schedules stored peers for removal.

## Roles and mutations

| Role | Current household scope |
| --- | --- |
| Owner | Content, all roles/users, device/session management, override with reason, clear workspace, backup |
| Admin | Content, create/manage member/viewer devices/users within policy, sessions, audited override and backup |
| Member | Shared content, task transitions, notes, comments/images; own calendar events |
| Viewer | Read-only content; login/local preferences |

Roles are household-wide, not per-project ACLs. Event/comment/image edits and
deletions enforce author/admin policy. The server prevents last-owner removal,
revokes sessions on role change/disable, and rechecks permission at operation
commit and download. Android is a content client; management stays on the LAN.

Mutations use immutable operation IDs, revisions, workspace epoch/generation and
server transactions. A replay cannot duplicate a committed change. Stale task
starts/edits, reset/restore writes and uploads crossing a deletion boundary are
rejected. Server commit order decides competing claims; client clocks do not.
Rejected/causally dependent offline edits remain retained for explicit review.

## Browser, images and widget

Web sessions use HttpOnly/SameSite cookies (Secure in production), exact-origin
checks and CSRF tokens. Admin operations require recent password reauthentication.
The page has a restrictive CSP, no third-party assets/analytics/fonts, and uses
textContent for user text. Web drafts exist only in the tab; copy them before
closing it. Browsers and OS trust stores are outside the native Keystore boundary.

Images are bounded, decoded/normalized, dimension-limited and re-encoded; metadata
is stripped. Native image caches bind ciphertext to server/account/image. OS
photo picker avoids broad storage permission. Production windows set FLAG_SECURE,
backups are disabled, clipboard copies are marked sensitive. These controls do
not prevent every physical screenshot or compromised-device capture.

Widget titles are hidden by default. Opt-in discloses title copies to the home
launcher. Android/launchers may retain old RemoteViews, so lock/redaction is
best-effort and cannot erase cached copies. Widgets never contain notes, images,
tokens or private keys. Tap opens the credential-locked app; no unauthenticated
widget action mutates tasks. Offline QR decoding uses CameraX/ZXing with camera
permission requested only for scanning; frames are not saved or uploaded.

## Remaining gates

Physical GrapheneOS/VPN/lockdown/roaming, actual Debian/Ubuntu root installation,
firewall negative-access tests, hardware-backed Keystore behavior, camera optics,
launcher lifecycle, battery/Doze timing and independent review remain deployment
gates. No reliable instant background push is claimed. Per-project ACLs, passkeys,
trusted-device key transfer and automatic CA rotation remain future work.

## Background delivery and removal

The special-use foreground service is a normal app service with a visible,
silent status notification, not VpnService or device administration. It uses the
same restricted app-contained transport. Reminder/update notifications use
generic text and private visibility, enabling GrapheneOS's opt-in system
forwarding without task contents. OS permissions, DND and user-profile lifecycle
remain authoritative. Alarm occurrence receipts are AES-GCM encrypted with
purpose-specific AAD; pending intents contain no titles or credentials.

Only authenticated active device sessions from the enrolled TCP peer can renew
their session lifetime (30 days, renewed when fewer than seven remain). Expired,
revoked, removed or disabled-user sessions cannot renew. LAN sessions cannot
self-revoke as a phone. The private self-revocation route targets the calling
session's device, immediately removes its sessions and queues host peer removal;
it never accepts an arbitrary target from the client. Normal Android uninstall
removes profile-private data/keys, not shared server records or audits. Direct
uninstall cannot notify the server. See [Android delivery](ANDROID-DELIVERY.md)
for the exact cleanup and profile limits.
