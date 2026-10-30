# Synchronization contract

This is the target design/contract. See [VALIDATION.md](VALIDATION.md) for current
implementation status and [RUNNING.md](RUNNING.md) for actual commands.

Status: requirements for implementation, not a tested protocol.

## Authority and identity

The server owns the accepted state and assigns a monotonically increasing event
sequence in the same database transaction as each accepted mutation. Display a
server receipt timestamp, but use the sequence/revision for ordering, including
when the server clock changes. Client wall clocks are informational only.

Each operation carries a random operation ID, workspace ID, workspace generation,
server epoch, target ID, expected revision, command type, and bounded payload.
The authenticated session supplies user/device identity; never trust identity or
role fields from JSON. Protocol major version is explicit. Reject unknown
security-sensitive fields and incompatible versions rather than guessing.

Store a durable idempotency record scoped to workspace/device/operation ID with
a canonical request digest and result. An identical retry returns its original
result, but only after checking the caller is still authorized. Reuse with a
different digest is an error. Commit the result with the mutation; a lost HTTP
response cannot make a second task or comment. Bound dedupe storage without
forgetting replay safety: expire upload/command envelopes and maintain a device
operation high-water/replay fence before pruning old results.

## Starting a task

`startTask(taskId, expectedRevision, operationId)` is an atomic compare-and-set:

1. Begin a write transaction; validate the current workspace generation, device,
   membership, project permission, task lifecycle, and expected revision.
2. Transition an available task to in-progress with its claiming user/device.
3. Increment its revision; append its event, command result, audit metadata, and
   relevant notification jobs; commit as one unit.

If two devices both submit against revision 12, one can commit revision 13. The
other receives an explicit conflict with the current authorized state. It must
not silently become the owner. SQLite's write serialization plus conditional
updates or equivalent transactional guards enforce this; HTTP arrival ordering
and in-memory locks alone do not.

The winner is the **first valid transaction accepted by the server**, not the
earliest phone timestamp. A slow or offline phone cannot prove that its user
acted earlier. If both phones say “start” while offline, both show a provisional
local state until reconciliation. Global exclusivity while disconnected is
impossible; the UI must say “pending confirmation.” Strict exclusivity requires
being online before work starts.

Initial claims are persistent until release/completion/explicit reassignment.
Do not introduce expiring leases or silently steal a task because a phone went
offline. Separate “assignee” from “currently claimed by” if future workflows need
both. A second start by the same user from another device follows an explicit
already-started result rather than resetting progress.

## Other conflicts

| Operation or collision | Required behavior |
| --- | --- |
| Different tasks edited | Independent revisions; changes should both succeed |
| Same task edited from an old base | Return current revision and preserve the local draft for explicit reconciliation |
| Title/notes edited concurrently | Never silently discard text; offer keep mine, keep server, or edited merge with a new expected revision |
| Concurrent comments | Append uniquely identified comments; stable server event ordering |
| Comment edited/deleted concurrently | Revision check; retain the author's local edit as a conflict draft; tombstone accepted deletion |
| Complete versus start/reopen | Explicit lifecycle transitions; stale start cannot reopen a completed task |
| Owner reassigns an in-progress task | Privileged command, reason, current revision, transactional audit event; notify affected people |
| Same user's two devices | Same concurrency rules; shared login is not a reason to overwrite |
| Role downgraded during a request | Re-check in the committing transaction; an earlier authorization check is insufficient |
| Delete versus stale edit | Tombstone rejects stale writes; restoration is a distinct authorized action |
| Workspace cleared versus queued edits | Increment generation and reject all old-generation writes/uploads |
| Network drops after commit | Retry identical operation ID; return the committed result |
| Duplicate or reordered push | Fetch by cursor; ignore duplicate hints; never apply notification payloads as state |

Start with object-level revision conflicts. Field-level automatic merging or
CRDT text editing may be added later with explicit semantics and migration tests.
Do not turn every entity into a generic untyped blob to anticipate scope creep.

## Offline client and catch-up

Persist the local optimistic change and outbox command atomically. Render
pending, synced, conflict, rejected, and retryable states distinctly. Preserve
drafts on authentication expiry and server errors without replaying them under
a different account. Partition caches by server, user, and workspace.

Use bounded batches, exponential backoff with jitter, cancellation, and network
change notifications. Retry transient errors; do not loop permission failures,
invalid data, or revision conflicts unchanged. Never acknowledge an outbox item
before its result and local state are durably recorded. Observe causal ordering
for operations on the same object; different objects may proceed independently.

The changes API is paginated by committed sequence, scoped to current access,
and returns a stable high-water mark. Record the cursor only with the applied
batch. Scope changes need a membership/visibility revision so data removed from
access is evicted without leaking objects in other projects. A client with an
expired cursor downloads a consistent snapshot and resumes after its watermark;
it does not assume the missing history was empty. Snapshot/event retention must
not cause resurrection of old deletions.

After a server backup restore, rotate a server epoch before admitting client
writes. Cursors, old command results, and outboxes belong to the previous epoch.
Require a full reconciliation and preserve old local drafts separately; never
auto-upload an entire cached database over the restored server. Include a fresh
epoch even when restoring the old epoch field from a backup.

## Images and attachments

Uploads have their own IDs, workspace generation, parent revision/identity,
authorization, byte/dimension limits, and expiration. Upload to quarantine;
validate actual file type, decode safely with resource bounds, remove EXIF/GPS,
and generate bounded thumbnails. Reject SVG, HTML, path traversal, malformed
images, and decompression bombs. Encrypt normalized images and thumbnails before
durable storage. If original-file preservation is added, it is a separate
explicit privacy choice; never silently retain GPS-bearing originals.

Publish the attachment metadata only after the complete blob is durable. Use
random opaque storage names, not user-supplied paths or cross-user deduplication
keys. Verify integrity at finalization and authorize every read. Upload retries
must not duplicate attachments; interrupted uploads expire and are garbage
collected. An orphan sweep must not delete a live in-flight attachment.

Re-check authorization and generation at final commit, not just upload start.
Clearing a workspace, deleting the parent, or revoking a device must invalidate
an in-flight finalization. Deletion removes active blob references and schedules
bounded garbage collection; backup retention and filesystem blocks are separate
from active-data deletion.

## Required fault tests

Use two independent clients against the real server/database to race claims,
roles, edits, deletes, uploads, and resets. Kill the server between commit and
response; restart it before retrying. Exercise clock jumps, long offline periods,
full disks, duplicate IDs with different payloads, cursor expiry, backup restore,
new epochs, mismatched protocol versions, and revoked devices with old queues.
Assert the final state and durable event/results, not just HTTP status codes.
