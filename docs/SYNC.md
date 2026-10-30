# Synchronization contract

Current protocol 1 uses transactional commands, an event sequence, snapshots and
head polling. The database is schema 2 in 0.2.0. An event stream, resumable uploads,
comment editing and notification-job outbox are future features, not implied by
this contract. See [VALIDATION.md](VALIDATION.md) for executed checks.

## Authority and retries

The server owns accepted state. Each command includes `operation_id`, workspace
`epoch` and `generation`, action, target ID, expected revision and bounded data.
The authenticated session supplies user/device identity; JSON cannot choose a
role or author. Native sessions additionally require their active enrolled device
and actual assigned peer address. The server validates permission in the write
transaction, increments revision, appends the event and saves the encrypted
idempotent result atomically. Same ID/payload retries return the original result;
changed payloads or an old session cannot reuse that ID as a new grant.

Client clocks affect display/ranking only. Server event order/revision decides
state even if its wall clock changes. Waiting-time rank uses immutable server
creation time; it never grants ownership.

## Concurrent task starts

A start is a compare-and-set on an available, unblocked task. If both devices
start revision 12, the first valid server transaction wins revision 13. The other
receives a conflict; its timestamp cannot steal the task. A task's accepted claim
persists until release, completion or an audited administrative override with a
reason. An offline phone cannot prove it acted earlier. Offline starts are shown
as provisional until synchronization; strict global exclusivity needs an online
server acknowledgement before work begins.

Task title/notes/planning changes use the same expected revision. There is no
last-write-wins text overwrite. Images/comments have their own IDs and revisions;
comment creation appends rather than replacing another comment. Deleting a task
removes active children, and an in-flight stale upload cannot recreate them.
Calendar event updates/deletes enforce author/admin policy and revisions too.

## Organization and calendar

For protocol compatibility, a task's container field remains `project_id`; it
can reference a live List or Project, or be empty for Inbox. Creating an Inbox
task requires no container. A move updates its encrypted data and database parent
in the same transaction with the task's expected revision. Deleted or wrong-kind
containers cannot receive tasks. Deleting a List or Project is refused while any
live tasks remain, including completed tasks. Shared Note body/pin updates and
collection name/context edits are revision-checked commands too.

Calendar events are shared server records, downloaded in the same snapshot and
retried through the same outbox as tasks. They sync across Field Notes clients;
there is no CalDAV, Google/OS calendar bridge, calendar import or subscription.

## Android persistence and conflicts

Commands are immutable retry units in an atomic Keystore-encrypted cache. Their
operation ID is saved before sending. A response lost after commit therefore
retries the same operation, even after process death. Causal predecessor links
allow a later local edit to adopt only its accepted predecessor's revision; a
rejected parent leaves dependent edits retained rather than silently retagging
them against unrelated remote changes.

Synchronization first fetches current state, fences epoch/generation changes,
then replays commands and fetches a final snapshot. A failed network exchange or
session expiry retains the outbox. Rejected revisions, removed items, dependent
writes and editor drafts become reviewable conflicts. Reapply requires an
explicit choice with a fresh operation ID and current revision. Deleted items
must be recreated deliberately, using copied text if needed. The local projection
never masquerades as accepted server ownership.

Downloaded images are encrypted and scoped to server/account/image. Switching
accounts with pending edits is refused. An image picker captures the workspace
basis at launch, so picking after reset does not relabel old work as new.

Web drafts live only in the current tab, preserving their base revision during
remote changes. They are not an encrypted offline browser database. Copy needed
text before closing the tab. Expired web sessions close private detail views;
server authorization continues to guard every mutation/download.

## Enrollment and reset

The enrollment claim nonce is durable before the claim request. Approved tunnel
configuration and activation credential are durable before activation. Lost
responses reuse both. User/account/role are checked against the invitation.
The configuration is not issued merely because a peer was requested: the root
helper must acknowledge the exact desired manifest. Network fallback changes
only a trusted outer UDP endpoint and preserves request bodies and all identities.

A clear increments the workspace generation; restore creates a fresh epoch and
revokes sessions/invitations/devices. Old queued writes cannot enter the restored
workspace. Restore requires reviewed re-enrollment. The sign-in screen exposes
retained text; resetting the connection is blocked while drafts/conflicts/outbox
remain. Discarding them needs explicit confirmation, including a pending-image
warning. Re-enrollment clears downloaded cache and generates a new identity,
rather than overwriting keys silently. See [ENROLLMENT.md](ENROLLMENT.md).

## Bounds and limits

Protocol mismatches fail closed. Command/image sizes, local cache/outbox size,
roles and lifecycle transitions are bounded and tested. Snapshot/head polling is
household scale, not a multi-tenant distributed sync platform. No cursor/result
pruning policy silently forgets replay receipts. Background catch-up/reminders
are best-effort; no notification is the source of truth for a task transition.
