# Enroll and revoke a phone

Management is used from your PC. The phone never needs to reach the management
IP/HTTPS port. Enrollment and normal sync use the same restricted WireGuard
path to the private task API, TCP 8443 inside the tunnel.

1. Install the matching APK on the phone. On **Enroll this phone**, select
   **Copy phone public key**. Transfer that public key to your PC; it is safe
   to copy. The private key stays on the phone. No Google clipboard service is
   required or used.
2. On the PC's server website, create the person's account and role under
   **Management → People**. Roles apply across the household.
3. Under **Devices → Create invitation**, reauthenticate, choose the account,
   and paste **Phone public key**. The server reserves a unique /32 and PSK and
   asks its constrained root helper to install that peer. The invitation is
   bound to this phone key and expires in ten minutes.
4. On that phone, choose **Scan invitation QR** and allow camera access. Review
   the account and server, enter a phone name, confirm the trusted source, then
   choose **Request enrollment**. The keyboard leaves the name and confirmation
   controls in a scrollable area; **Done** dismisses it. Paste remains available.
   A QR for another phone is rejected before any claim is sent.
5. The phone contacts only the private API through its app-contained tunnel.
   On the PC, choose **Approve** for that phone and enter the eight-character
   code displayed directly on it. Do not accept a code supplied by someone else.
6. Task credentials are released only after approval, a fresh matching host peer
   acknowledgement and private-source activation. Keep enrollment open until the
   workspace appears. No account password is sent during enrollment.

Only the selected UDP port must be reachable from the phone. On home Wi-Fi this
can use the installer's LAN UDP alternative; away from home it uses your chosen
public/DDNS endpoint and router UDP forward. Neither case opens management to
the phone or requires forwarding TCP 8443. Existing VPN/lockdown and GrapheneOS
Network permission remain authoritative; no OS VPN slot or bypass is used.

The scanner runs locally without Google services and saves/uploads no camera
frames. Back cancels scanning and preserves a pasted draft. If Camera permission
is denied, allow it in Android app settings or paste the invitation instead.

The QR contains public server trust, peer connection details, a PSK and a
short-lived token, never the phone private key or a content session. Treat it as
a temporary secret. Lookup tokens are hashed; an unclaimed v2 invitation is also
stored encrypted so a failed PC request can recover the same QR. That recovery
copy is removed when claimed or expired. Retrying issuance for the same pending
phone returns the same invitation rather than allocating another peer.

The server binds claim/status/cancel requests to the assigned inner /32 and
still requires the invitation/claim proof. The peer can reach only the task API;
unapproved peers receive no task credentials. Browser cookies or Authorization
headers are rejected on enrollment routes. Phone tokens cannot manage the server.
A stolen QR alone cannot prove possession of the phone's private key or replace
PC approval. Expiry/revocation removes the bootstrap peer; addresses stay reserved
until fresh root removal acknowledgement. An unused expired reservation can then
be replaced using the same phone key. An offline active phone never qualifies.

## Interruptions and recovery

- Version 0.3.6 and later issue v2 key-bound tunnel invitations. Update both
  server and APK. An old saved LAN attempt may still show its legacy connection
  error. If it expired before any tunnel configuration/activation was received,
  choose **Use a fresh invitation · keep phone keys**. This clears only the
  expired attempt; it cannot clear queued edits or an uncertain activation.
  Copy the same public key into a new PC invitation. An expired unused reservation
  is reusable only after the host acknowledges removal. Active pairings remain
  bound and ordinary updates do not require re-enrollment or key deletion.
- If the QR is difficult to read, show the whole square without covering its
  white border and move the camera back until it fits the guide. The manager
  displays a crisp PNG with whole pixels per square. **Can’t scan? Paste the
  invitation instead** exposes selectable text; **Copy invitation** selects
  that text if the browser blocks clipboard access. A missing/unreadable image
  shows the same fallback. Expiry, closing and browser sign-out remove both
  image and text. Create a new invitation when it expires.
- Scanning opens the account/server review automatically. Check the trusted
  source box, then choose **Request enrollment**. A malformed code produces a
  readable error. Neither scanning nor opening an invitation link replaces an
  existing pairing, pending enrollment or queued work.
- Reopen the app after process death. The claim nonce, pending ID, configuration
  and activation receipt credential are saved before their respective side effects.
  Lost responses retry the same operation; they do not allocate another peer.
  Server cancellation also works before the device ID is saved: the phone sends
  its saved invitation, current public key and claim secret. An unclaimed
  invitation is consumed without allocating a device; a claimed one must match
  the original proof. An active phone still requires revocation. Older servers
  need updating for this cancellation form; failure retains the saved attempt.
- **Pause pairing** stops the private connection locally and saves the pause
  across restarts. It keeps the invitation, nonce, keys and uncertain receipt;
  no server response is needed. **Retry saved pairing** resumes that same attempt.
  Queued native exchanges from before Stop cannot send after resume. Already
  transmitted requests may have reached home before the pause completed.
- Healthy approval/provisioning checks run every three seconds. Failed contacts
  back off with 80–100% jitter from 15 seconds to five minutes. Permanent
  authorization/expiry/protocol failures pause for explicit action. No public
  connectivity probe, Google time/network service or trust bypass is used.
- An unapproved invitation/pending phone expires after ten minutes. Approval
  cannot extend this indefinitely. Cancel its server invitation, remove the old
  inactive binding when eligible, then create a fresh invitation for the same key.
- If activation succeeded but its short retry receipt expired, choose **Recover
  an activated phone with password** and sign in as that phone's assigned account.
  This still requires its enrolled tunnel. **Back to enrollment** returns to
  cancellation if the peer never activated.
- If the root helper fails, the website shows an error/waiting acknowledgement.
  No ready status is invented. Inspect `field-notes-peers.service`; do not hand-edit
  the desired manifest to grant access.
- Changed permissions before activation require a fresh invitation. Existing
  active phones can sign in again after an intentional role/session change.
- **Cancel invitation on server** needs the trusted LAN. **Pause pairing** does
  not. If away, the administrator can
  revoke the pending phone. Canceling an already active phone requires revocation;
  it does not silently discard its identity. Missing Keystore keys fail closed.
- Losing the app vault/private key means revoking the old phone and enrolling a
  new identity. Do not reset an app that holds unsynchronized drafts without
  first recovering or copying those drafts. The sign-in screen exposes **Review
  retained changes**; re-enrollment is blocked until those are resolved. If you
  deliberately discard them, the app requires explicit confirmation (including
  pending images). **Enroll this phone again** clears downloaded cache but keeps
  phone keys by default. First revoke the old server entry and use **Remove
  inactive pairing** after peer removal is acknowledged. A new invitation gives
  that key a new device ID and PSK. Select **Generate new phone keys too** only
  when key replacement is intended; it is not a retry.
- **Pause private connection** in Settings or on the sign-in screen keeps
  downloaded work, encrypted queues, keys and cached reminders. Resume explicitly.
  Background work and boot cannot clear the saved pause. If storage refuses the
  pause write, the running process still vetoes sends and reports that it cannot
  promise persistence; use Android app settings to force-stop. Native-load failure
  is reported without clearing the vault, with an Android settings/removal action
  on the opening-error screen. OS Settings remains the independent escape path;
  a separate native recovery activity is not implemented.
- Failed exchanges close only Field Notes' peer. The next retry configures it
  again and resolves trusted DDNS afresh. Keys, PSK, pinned CA and inner destination
  stay fixed. Existing system VPN and lockdown policy remain authoritative.

## Revocation

Choose **Revoke device** and reauthenticate. Its application sessions stop working
immediately. **Removal pending** means the kernel peer has not yet been confirmed
removed. The server retains that address until a fresh root acknowledgement of
removal, then marks it revoked and allows address reuse. A failed helper is never
reported as completed revocation. The pending phone cannot cancel/revoke a
long-activated phone using an old invitation alone.

**Remove inactive pairing** is available only for revoked or expired bindings
with no remaining assigned peer address. A merely offline, active or pending
phone cannot be purged. Eligibility and role permissions are rechecked in the
removal transaction. This removes enrollment/session/key records, not household
tasks or phone-local data. Old invitations, device IDs and PSKs never revive.
Failed cleanup is safely retryable. Owner/administrator bindings require an
owner.

Backups do not restore old phone sessions or silently reactivate peers. A restore
creates a fresh workspace epoch and marks stored devices for removal. Re-enroll
phones after reviewing retained offline drafts.

The root helper handles only the fixed `fieldnotes` interface, canonical 32-byte
keys, unique private /32s, and bounded private files. It supplies PSKs to `wg`
through a file descriptor rather than command arguments. The content process
has no shell/network administration endpoint or NET_ADMIN capability. Root spool,
WireGuard config and the host disk still contain key material and require protection.
