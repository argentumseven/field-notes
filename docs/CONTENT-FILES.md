# Export and import content

On the website, choose **Export & import** near the bottom of the sidebar.
On Android, open **Settings & widgets → Export & import → Manage content files**.
The phone also offers export beside sign-in when it retains a downloaded workspace.

Choose **Export content**, enter and confirm a passphrase, then save the
`.fieldnotes` file. Spaces, punctuation and case are kept exactly. Keep the
passphrase separately: neither the server nor a password reset can unlock this
file for you. Exports include planner records, comments, images and retained
alternatives. They exclude account passwords/hashes, login sessions, device
pairing material, CA/master keys and APK signing keys.
Project/task-list hierarchy, task/reference list choices and project state are
included. New exports require Field Notes 0.9 or newer to import (content manifest
version 2); existing version 1 files remain supported.
0.10 includes optional item categories and complete custom reminder timing.
Files using those fields need the newer server; unsupported fields are rejected
instead of discarded. Server exports and phone exports preserve the complete
timing rules, including when legacy notification projections omit advanced leads.

The website exports the current server workspace. Android exports its cached
workspace, projected offline changes and retained drafts/conflicts. Changes hidden
by a queued deletion remain recoverable, including their cached attachments.
An uncached image must be retrieved through the paired private tunnel; otherwise
export stops with a visible error rather than producing a purported complete
copy. The phone uses Android's document picker and needs no broad storage
permission. Exports you deliberately save remain your files after uninstalling.

To import, select the file and enter its passphrase. **Unlock and preview**
verifies the complete encrypted archive and all image checksums before preparing
an import. Review the counts and map archived people to current household members.
This maps content responsibility; it never creates users or grants roles.
Personal reminder recipients follow that mapping, including reminders for an
unassigned item's original creator; importing does not turn those into Everyone.
Then choose **Import items**. Viewers can export but cannot import or restore.

Imports are additive. Matching source identities and exact matching content are
reused; repeated imports do not create more active copies. A different version
of a current item is retained separately. Current items, pairings and accounts
are never overwritten. Deleted originals remain deleted. If someone changes
the workspace after preview, choose **Review again**; uploaded images are kept
for that preview. An interrupted commit can be retried using the same preview.
Unfinished server previews expire after 20 minutes.

Open **Recovered drafts & alternatives** to read retained changes. **Restore as
a separate item** creates a validated copy, or reuses an identical current item.
Repeated taps return that same result. A task whose former collection is missing
goes to Inbox; choose an existing task when restoring a comment or image. Claims
to be actively working on something are not resurrected by an archive.

After review, **Remove recovered copy** frees its retained server storage. Keep
an export first if you still need it. Current planner items and existing backups
are unchanged. Importing an old file again can bring back a discarded alternative.

Portable files currently support 10,000 active records, 2,000 retained changes,
a 24 MiB manifest and 192 MiB of images, with each image at most 8 MiB and the
encrypted file at most 256 MiB. Recovered server copies are bounded to 8 MiB of text and 64 MiB including images;
reviewed copies can be removed independently. Server content quotas also apply. Errors retain
current data and the original file. The owner’s existing encrypted server backup
remains the route for a complete installation recovery, including accounts;
portable imports are for content, not server identity restoration.

The [portable format](PORTABLE-FORMAT.md) describes its encryption and bounds.
