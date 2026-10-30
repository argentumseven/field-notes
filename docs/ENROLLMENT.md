# Enroll and revoke a phone

1. Install the APK. Join the trusted home LAN; first pairing requires LAN access.
2. On the server website, create the person's account and choose its role under
   **Management → People**. Roles are household-wide.
3. Under **Devices**, select **Create invitation**, reauthenticate, choose the
   existing account, and display its QR. It expires in ten minutes. Treat the QR
   and copyable invitation as temporary secrets.
4. On the phone choose **Scan invitation QR**, allow camera access while scanning,
   review the account/server address, name the phone, confirm the trusted source,
   and request enrollment. You can paste an invitation when no camera is available.
   Scanning alone does not authorize a server or send credentials.
5. On the website, find that phone and choose **Approve**. Enter the eight-character
   code shown directly on the phone. Do not copy a code supplied by another person.
6. The site shows **provisioning** until the constrained root helper has installed
   the public key, unique PSK and /32 peer. The phone then proves access through
   its new tunnel and receives a device-bound application session. No password
   is sent during this process. Keep enrollment open until the workspace appears.

The phone generates its private key locally and never uploads it. The invitation
is scoped to the selected account/role; a phone cannot request a different role.
Invitations are hashed at rest, single claimant, expiring, and rate-limited.
Claims include a separate random secret persisted in the encrypted phone vault
before any request. Exact retries return the same pending phone; different
claimants are rejected. QR theft cannot bypass the separate code approval.

Approval is not the same as activation: unapproved/provisioning phones get no
content credentials. Configuration is released only after a fresh matching host
acknowledgement. Activation checks the actual inner TCP address and an independent
secret. A LAN web session is not accepted as a phone session. The native transport
can dial only the paired notes endpoint; LAN bootstrap supports only claim,
status and cancellation, over pinned HTTPS with no redirects.

## Interruptions and recovery

- Reopen the app after process death. The claim nonce, pending ID, configuration
  and activation receipt credential are saved before their respective side effects.
  Lost responses retry the same operation; they do not allocate another peer.
- An unapproved invitation/pending phone expires after ten minutes. Approval
  cannot extend this indefinitely. Cancel and create a new invitation.
- If activation succeeded but its short retry receipt expired, choose **Recover
  an activated phone with password** and sign in as that phone's assigned account.
  This still requires its enrolled tunnel. **Back to enrollment** returns to
  cancellation if the peer never activated.
- If the root helper fails, the website shows an error/waiting acknowledgement.
  No ready status is invented. Inspect `field-notes-peers.service`; do not hand-edit
  the desired manifest to grant access.
- Changed permissions before activation require a fresh invitation. Existing
  active phones can sign in again after an intentional role/session change.
- Cancel requires access to the trusted LAN. If away, the administrator can
  revoke the pending phone. Canceling an already active phone requires revocation;
  it does not silently discard its identity. Missing Keystore keys fail closed.
- Losing the app vault/private key means revoking the old phone and enrolling a
  new identity. Do not reset an app that holds unsynchronized drafts without
  first recovering or copying those drafts. The sign-in screen exposes **Review
  retained changes**; re-enrollment is blocked until those are resolved. If you
  deliberately discard them, the app requires explicit confirmation (including
  pending images). **Enroll this phone again** then clears downloaded cache and
  generates a fresh identity, after you revoke the old server entry.

## Revocation

Choose **Revoke device** and reauthenticate. Its application sessions stop working
immediately. **Removal pending** means the kernel peer has not yet been confirmed
removed. The server retains that address until a fresh root acknowledgement of
removal, then marks it revoked and allows address reuse. A failed helper is never
reported as completed revocation. The pending phone cannot cancel/revoke a
long-activated phone using an old invitation alone.

Backups do not restore old phone sessions or silently reactivate peers. A restore
creates a fresh workspace epoch and marks stored devices for removal. Re-enroll
phones after reviewing retained offline drafts.

The root helper handles only the fixed `fieldnotes` interface, canonical 32-byte
keys, unique private /32s, and bounded private files. It supplies PSKs to `wg`
through a file descriptor rather than command arguments. The content process
has no shell/network administration endpoint or NET_ADMIN capability. Root spool,
WireGuard config and the host disk still contain key material and require protection.
