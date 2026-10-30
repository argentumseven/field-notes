# Security and administration requirements

This is the target design/contract. See [VALIDATION.md](VALIDATION.md) for current
implementation status and [RUNNING.md](RUNNING.md) for actual commands.

Status: threat model and implementation requirements. No security audit or
runtime validation has been performed for Field Notes.

## Trust boundaries

The user selected **server-readable content**. The server process and authorized
administrators can access tasks, notes, comments, and images. Server compromise
can expose all of that data while its keys are available. Disk encryption,
WireGuard, and HTTPS cannot prevent a compromised authorized endpoint from
reading plaintext. Do not promise that “nothing leaks”: traffic metadata,
operating-system behavior, screenshots, exports, and compromised devices have
their own limits.

Protect against remote unauthenticated access, malicious/compromised ordinary
members, replayed and stale operations, accidental deletion, stolen locked
devices, offline storage theft, untrusted uploaded files, and hostile network peers.
Treat denial of service, dependency compromise, operator error, and lost recovery
material as explicit risks. A transport peer key is not a substitute for user
authorization.

## Encryption and keys

- HTTPS everywhere, including LAN installation and the inner device API.
  TLS 1.3 preferred with a supported
  secure compatibility policy. Validate names, certificate chains, and expiry;
  certificate rotation and device re-pairing have documented procedures.
- Encrypt sensitive database fields, comments/notes, object names where practical,
  normalized attachments, thumbnails, and backup contents using maintained
  cryptographic libraries and authenticated encryption. Do not design a new
  cipher, signature scheme, or password exchange.
- Use random per-object data keys, a server wrapping key, fresh nonces with
  appropriate library-enforced uniqueness, key versions, and associated data
  binding workspace/object/type/version. Fail closed on authentication errors;
  never replace unreadable data with an empty database.
- Keep the server wrapping key outside the database/data-volume backups. Choose
  a root-controlled secret mount or systemd credential mechanism, and document
  how it is unlocked after a restart. An unattended server must have a usable
  key source; colocating its unprotected key with stolen data limits protection.
- Add full-disk encryption on the Linux host where practical. App encryption
  does not hide all SQLite structure, identifiers, timing, sizes, or indexes;
  define the exact plaintext metadata before implementation. Avoid plaintext
  full-text indexes by default. Protect WAL, temporary files, swap, and dumps.
- Native clients use OS secure storage (Android Keystore, iOS Keychain, Windows
  protected credentials, Linux Secret Service) to wrap local cache keys. Store
  offline content and outboxes encrypted; handle missing/locked keyrings as a
  locked state. Background-access requirements must be reconciled with biometric
  key policies rather than promising both unrestricted background reads and
  per-access biometric confirmation.
- Browser storage is not equivalent to a hardware-backed native vault. Default
  web sessions to minimal persistent sensitive caching; an optional offline web
  cache needs a separate unlock/expiry design and an explicit shared-computer
  warning. Account/server switching and logout clear active caches and object URLs.
- Recovery includes an offline protected key export and a tested restore path.
  Password reset can restore login access but cannot reconstruct a lost wrapping
  key. Never store recovery secrets in logs, QR screenshots, or the repository.

## Authentication and roles

One-time local bootstrap creates the initial owner. No default password or open
public setup page. Use a current, well-reviewed authentication implementation:
Argon2id for passwords, passkeys/WebAuthn where platform support is verified,
bounded login work, per-source and per-account rate limits, explicit session
expiry/revocation, and recovery-code handling. Admin destructive actions require
recent reauthentication, even in an existing session.

For web: HttpOnly, Secure, SameSite session cookies; CSRF tokens and exact-origin
checks on mutations; strict CORS if any cross-origin native web transport exists.
Do not put bearer tokens in browser localStorage. Native sessions use revocable
per-device credentials in secure storage; protect refresh rotation against
concurrent refresh/replay and bind device enrollment to an approved user.
Invitations are high-entropy, hashed at rest, single-use, short-lived, and scoped
to a specific role/workspace. A QR code must not grant owner privileges by itself.

| Role | Content access | Administrative access |
| --- | --- | --- |
| Owner | All household content | Owners/admins, recovery policy, clear workspace, exports, restore coordination |
| Admin | Explicit household/project scope | Invites, device/session revocation, roles below owner, retention and task overrides within scope |
| Member | Create/edit/claim/comment/upload within assigned projects | Own devices, profile, local notification preferences |
| Viewer | Read only within assigned projects | Own sessions and preferences; no task/comment/upload mutation |

Decide exact per-project membership inheritance before handlers are written.
Keep permissions in one server policy layer and re-check at commit, download,
export, and job delivery. Never rely on hidden admin buttons. Prevent removal
or downgrade of the last owner. Record privilege changes and invalidate affected
credentials/access caches promptly. A device cannot self-upgrade by claiming an
owner role in its request.

## Web administration

Provide an authenticated dashboard with:

- Users, roles, invitations, project membership, devices, sessions, and revocation.
- Task inspection, conflict resolution, explicit reassignment/override with reason,
  archive, trash, restore, and bounded bulk actions with a preview/count.
- Separate deletion of active content, expired uploads, push registrations, and
  workspace reset. Reset increments a generation to fence old phones and uploads.
- Storage use, backup status, restore/import validation results, notification
  health, client/server compatibility, peer/tunnel connectivity, and audit events.
- Retention settings and authenticated exports with time/size bounds. Sensitive
  downloads are not placed at publicly guessable object URLs.
- A typed confirmation and recent reauthentication for destructive operations.
  Present the scope and backup implications before executing them.

Restoring a database, rotating master keys, and updating binaries may need
restricted host-side tooling or a separate maintenance helper. An internet-facing
web process should not receive root, Docker socket access, arbitrary shell
commands, or unrestricted filesystem access to make administration convenient.
Allow only explicit validated maintenance jobs, with status and audit records.
Do not run untrusted uploaded “backup scripts.”

Keep an append-only logical audit trail with actor, operation, scope, revision,
reason, and server time/sequence. Do not include task bodies, passwords, keys,
tokens, private push endpoints, or raw attachment data. A database owner/root can
alter a local audit log; it is not tamper-proof evidence against the operator.

## Operational hardening

Use a non-root server, minimal read-only container filesystem, dropped
capabilities, no-new-privileges, bounded request/decode work, restricted
administrative exposure, private data mounts, and explicit ingress/egress rules.
Separate the NET_ADMIN WireGuard gateway from the content process. Keep the
admin listener on the management LAN, deny phone-to-LAN/peer forwarding, and
test interface isolation independently of client route configuration. Do not
require a fully privileged container for the deployment.

Serve bundled fonts/icons/assets. No telemetry, crash uploads, external image
fetches, or link previews by default. Untrusted Markdown must be sanitized; raw
HTML, active SVG, arbitrary URI schemes, SSRF-capable previews, and path-derived
file access are prohibited. Use a restrictive CSP, no framing, nosniff, bounded
uploads, and authenticated downloads. Redact both application and proxy logs.

If an independent UnifiedPush adapter is added, endpoint registration must not
become an SSRF primitive: restrict
notification dispatch to the operator-configured push origin/paths, validate DNS
and resolved destinations, disallow credentials in URLs, and reject redirects to
other destinations. A deliberately internal push origin is an explicit operator
configuration, not permission to access any private IP or cloud metadata URL.

Run backups on a defined schedule with authenticated encryption, checksums,
retention, and tested restoration to a separate directory/instance. Include
coherent DB and object state plus migrations/version metadata. Keep encrypted
recovery material separately available. Deleting a task does not erase old
backups or flash blocks. Explain that precisely in the UI and retention policy.

Use signed/verified release artifacts, dependency lockfiles, checksums, a
dependency inventory/SBOM, and a stable APK signing identity. Do not auto-update
the server from an unpinned image tag or accept arbitrary web-supplied binaries.
Run an independent security review before describing the product as audited or
recommending exposure of an unfinished server to the public Internet.
