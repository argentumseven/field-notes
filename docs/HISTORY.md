# Household change history

Open **Library → History** in either the web GUI or the Android app. The same
server-recorded trail shows who added, changed, completed, reopened, started,
released or removed an item, and when the server accepted that change.

Each entry shows the item type, title, person, local date/time and its list or
task context. **Show changes** expands readable Previous/Now values. **Open
item** opens content that still exists. Removed items stay identifiable in the
trail; deleting a task also records its removed comments and attachments.
History does not retain image pixels or offer an undo that can revive deleted
work.

Search includes titles, people, collection names and both sides of recorded
field changes. Filter by Added/Changed/Removed, person, item type or date range.
Date ranges include the whole selected local day, including clock changes.
**Load older changes** uses a fixed server sequence so new activity cannot
duplicate or skip entries while paging. **Back to latest changes** returns to
the newest activity. A very long trail loads in bounded sections.

The normal auto-refresh preference applies. Browsing older entries does not
pull you back to the top; new changes have an explicit Show latest action.
Errors stay visible, with a retry action. The browser retains loaded rows in the
current tab when its connection fails; it does not save this content in browser
storage.

## Offline phones and saved work

Android keeps up to 200 loaded history entries, bounded to 512 KiB, inside its
existing Keystore-encrypted vault. Offline filters can search those cached
entries. The screen labels the cached subset and asks you to reconnect for the
complete trail. Queued edits and drafts take precedence over this optional read
cache if local storage capacity is reached.

Offline edits appear in shared history after the server accepts them. Rejected
or conflicting edits remain in Retained changes; they are not described as
successful changes. Replaying an accepted operation does not add another
history entry. Changing accounts, clearing a workspace or adopting a restored
server epoch invalidates the previous scope's history cache.

## Existing changes, retention and privacy

Detailed field changes start with the **0.7 server**. Earlier events show their
existing action/person/timestamp and last-known item name where available. They
are explicitly marked as lacking retained field details; current values are
never presented as a fabricated earlier version.

The server retains detailed entries for the latest 10,000 events, up to 64 MiB.
Older actions and timestamps remain for the current workspace. Individual
entries are bounded to 32 KiB and 64 changed fields. Long values show marked
excerpts around the edit; large arrays and exceptionally large changes are
summarized. History is for reviewing changes, rather than replacing a backup.

Deleting an ordinary item retains its bounded history details. **Clear
workspace** removes prior content details and begins the new workspace's trail
with a summary of the clear. Encrypted server backups include history; a restore
preserves the restored trail under a fresh epoch. Portable content files do not
replace or forge authoritative history: accepted imports generate new entries
marked Imported.

History is read-only and uses the existing household permissions and private
phone API. Detail payloads are encrypted on the server and contain allowed
content fields only. Passwords, sessions, pairing secrets, private keys and
image bytes are excluded. Account/device/security activity remains in the web
manager's **Administrative activity** area; that management API is not added to
the phone tunnel.

Update the server and phone to use this screen. Older compatible clients can
continue core sync; a newer phone connected to an older server explains that
History requires a server update. Ports, credentials and pairing stay intact.
