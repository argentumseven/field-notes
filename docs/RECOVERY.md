# Versions and content checkpoints

In web or Android **Library → History**, open **Item versions** on an item's
history entry. History lists each item once. Each retained state has an increasing **# index**,
actor and server timestamp. Choose **Preview restore** to open the whole item,
then switch between its selected and current state. Attribute comparisons are
optional. Restore applies the complete selected state in one transaction. Restoring creates new canonical revisions and a new version index;
it does not erase later retained states. Select a later version to roll forward.
The index and the record's sync revision are different: adding a comment can
create another saved task state without editing the task's own fields.

Tasks include their comments and attached images. Missing list/project parents
are recovered when needed, without overwriting an existing parent. A collection's
own version covers its fields; use a content checkpoint to restore its whole
membership and the rest of the household together. Restores clear active working
claims; a previously in-progress task returns to To do. An unavailable assignee
is cleared. The preview shows those changes.

**Content checkpoints** saves a named copy of tasks, lists, projects, notes,
events, comments and images. Only administrators can create, restore or delete
checkpoints. Each checkpoint has **Delete checkpoint** on web and Android. Deletion
shows its name, index, author and saved time, requires its exact
`DELETE CHECKPOINT #…` phrase, an acknowledgement and your password. It permanently
removes that recovery copy; current content and other saved states are preserved.
Unreadable checkpoints can also be deleted. Deletion invalidates open restore
previews and frees image bytes only after their final retained reference is gone.

Every item or checkpoint restore requires a separate confirmation step after
preview: review the affected counts (including removals), acknowledge the impact,
type the exact `RESTORE ITEM #…` or `RESTORE CHECKPOINT #…` phrase, and verify your
account password. The final action stays disabled until all fields are complete;
Enter in the website's fields does not submit a restore. Nothing is prefilled.
The server enforces the phrase, review acknowledgement and recent password proof.
Older clients with automatic confirmation cannot restore until updated. Normal
sync and pairing are preserved. A preview from an older server without these
safeguards cannot be applied by the updated client.

A full restore automatically saves a **Before restoring** checkpoint, so you
can recover the content you replaced, subject
to retention limits. Accounts, password hashes, roles, device pairing, master/CA
keys, ports and other security/host settings are never rolled back by content
checkpoints. Use encrypted server backups for disaster recovery.

## Concurrent work and interruptions

Previews expire after ten minutes and are bound to the authenticated session,
workspace, source state, current content, people directory and retention revision.
If any relevant state changes, the server rejects the restore and asks for a new
preview. It applies the entire change in one SQLite transaction, including image
integrity/capacity checks, version capture, the audit trail and replay receipt.
A failed transaction does not partially restore household content. Saved image
bytes can also repair a damaged live attachment, even when its metadata matches.

A whole-content restore advances the workspace generation; old offline writes
are retained for review instead of being uploaded into the restored state. Item
restores advance record revisions. Task restores also change a child-write fence,
so old comments and image uploads cannot silently cross a restore. Older clients
can keep syncing core data, but must update before adding children to a restored
task if they cannot send this fence. No pairing reset is required.

The phone durably saves a recovery request before transmitting it. If a reply is
lost, **Recovery result needs review** offers Check result, Retry same request,
and explicit dismissal after reviewing current content. A restore is never
retried automatically. Browser tabs retain the opaque result identifier across
reloads; they never write household content/passwords to browser storage. The
same user can check a committed result after signing in again. An old session's
mutation envelope cannot be reused by the new session.

## Trash and the limits of deletion

**Library → History → Trash · deleted items** on the website and on Android
(after the paired server advertises the Trash feature) lists the server's
existing deleted-record tombstones, without reactivating them. Newly deleted
records include a server timestamp; older tombstones may lack that timestamp.
The action **Review saved versions** opens the existing version preview and
password-confirmed restore process. A saved live project/list version offers
two explicit modes: **Restore with existing bindings** restores its own details
and retains currently linked tasks, lists, comments and attachments as they
exist; deleted descendants remain deleted. **Restore missing contents** (admin
only) additionally revives missing linked descendants under their *original IDs*,
using complete retained versions and original parent bindings. Living descendants
are never overwritten or duplicated, and no names are used as identities.
Missing saved versions, different parent identities, unavailable images, stale
preview revisions or invalid hierarchy structures fail visibly instead of
silently rebuilding only part of the collection. A deliberately moved, now
standalone item is not silently moved back into a project. Versions never
promise to reconstruct information already expired under retention rules.
For coordinated recovery of an entire household beyond the original links,
use a suitable content checkpoint or a verified encrypted backup after
reviewing the impact.

Trash is **not yet a new 30-day guaranteed recovery bin** and does not silently
turn on automatic cleanup. Recovery still depends on the configured age, count
and capacity limits; a deleted record can outlive its recoverable versions.
Trash browsing does not trigger version trimming or image garbage collection.
Read-only Trash browsing does not permanently erase content. Existing encrypted
server backups and independently exported files are unaffected by deletion
or a purge of online recovery data. Do not assume permanent erasure of all copies
when a record disappears from active views.

**Non-destructive:** Finish, Reopen, Move, Unbundle, and restoring a confirmed
saved state. **Destructive:** Delete active content, delete a named checkpoint,
purge saved versions/history, and clear the workspace. Ordinary deletion is
not a substitute for permanent purging. Selective permanent erasure across
checkpoints, task attachments, shared images, offline devices and backups
requires a separate reviewed design; this branch does not implement that action.

The recovery management screen now reports distinct *metadata* costs for
item versions and checkpoints, shared recovery image bytes counted only once,
existing Trash counts, and database reusable pages. These numbers are not
a forensic disk-erase report. Web history purge now requires the administrator
to type PURGE HISTORY and acknowledge its impact; the existing admin API
continues to accept the legacy phrase for older compatible clients.

## Retention and storage

Defaults are automatic item versions on, **90 days**, **50 versions per item**,
**10 checkpoints**, and **512 MiB** of recovery metadata plus retained image
bytes. Normalized image content is encrypted and deduplicated across retained
states. Change details have separate default limits of 90 days, 10,000 entries
and 64 MiB. Age 0 disables that age limit, while count/size bounds remain.

LAN **Management → History & recovery storage** shows actual retained usage and
lets an administrator with recent password proof adjust limits or manually purge
item versions, checkpoints, the change trail, or all content recovery copies.
Reducing limits immediately trims older states. Purging copies preserves current
content and the separate administrative security audit. Indices are never reused.
A pending preview becomes invalid after a purge. Connected history caches discard
old retention revisions; a disconnected device cannot receive a purge until it
reconnects. This is not a remote forensic erase feature.

New edits protect their complete before/after states until commit. If those
states cannot fit the recovery limit, the edit is rejected visibly rather than
silently losing its recovery copy. Raise the limit or purge older copies in
Management. A large task with many images may need more than the minimum 16 MiB.
Disabling automatic versions keeps existing copies and explicit restore safety
copies/checkpoints; changes made while disabled have no automatic recovery copy.

Deleting a saved state's final image reference releases its recovery image bytes;
current attachments remain. SQLite reuses freed pages; the database file need
not shrink immediately. This controls content growth, not filesystem overwriting.
The server still enforces its existing live content and image limits.

Full versions start with **0.8** changes. On the first edit to older content, a
baseline is captured before the edit. Earlier 0.7 excerpts cannot reconstruct
full historical bodies or deleted images; the interface says when no versions
exist. Encrypted server backups include versions, checkpoints and their images.
A full backup restore still changes the server epoch and invalidates sessions.
Portable content exports contain current content and retained local work, not
forged authoritative history or the server's complete recovery archive.

New full server backups use authenticated 256 KiB chunks and stream to the browser
or CLI file, so a large recovery archive does not need to fit in the app's RAM.
They need temporary disk room for a compact database snapshot and encrypted copy.
The upgrade preflight accounts for those copies, including a separate data disk.
The 0.8 server restores both this format and older single-record backups; restoring
a large legacy archive still needs RAM proportional to its old file size.
Restore validates a private staging database before publishing a fresh destination;
corrupt files and wrong keys do not replace an existing installation.
