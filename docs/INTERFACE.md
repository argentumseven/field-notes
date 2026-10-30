# Interface

Compact charcoal/paper, teal accents, quiet task rows, and a plain page glyph.
Use system fonts and functional icons, with no decorative art or runtime assets.
The selected direction replaces the earlier blue/violet card-heavy prototype.

The browser sidebar and Android workspace drawer expose Inbox, Focus, Tasks,
Calendar, Notes and Collections, followed by Unscheduled, Waiting and Completed.
Lists and Projects have separate sections. Inbox accepts tasks without a parent;
Lists suit groceries, routines and checklists, while Projects group an outcome.
Shared Notes are first-class reference documents with pinning/search, separate
from task notes and comments. The New menu creates a task, list, project or note.
Tasks can move to Inbox or either kind of collection using Keep in. Nonempty
collections cannot be deleted. Android keeps Focus / Tasks / Calendar in its
bottom bar for frequent access; Browse workspace opens the full navigation.

Focus explains its ranking;
Tasks keeps every state visible; Calendar separates planned/due days from events.
Quick add creates an undated normal-priority task in Inbox or the selected collection. Its inspector offers optional
clearable dates, four priorities, estimate, pin, blocked state, notes, images and
conversation. Starts remain provisional offline. Rejected changes retain text
for explicit review; do not turn a suggestion score into an ownership grant.

The LAN website adds Management for people/roles, enrolled devices, sessions,
audited overrides, encrypted backup, server/peer-agent status and explicit clearing.
Device enrollment is a short sequential flow: trusted QR review, phone code,
root provisioning acknowledgement, then tunnel activation. Explain pending and
failed steps without asking users to edit peer keys or connection JSON.

Android has a real home widget with private defaults, title opt-in and a clear
launcher disclosure. Widget taps open the credential-locked app. Camera access
is scoped to the offline QR scanner; paste and invitation links allow devices
without a camera. System bar/lock/scanner colors follow the same app theme.

Use accessible control labels, semantic status text, visible focus and enabled
states, and bounded responsive layouts. Keep destructive confirmations specific
and distinguish server deletion from discarding a retained local copy. No
Google account, VPN-slot prompt, cloud account, external font or analytics path
belongs in the product flow.

See [WALKTHROUGH.md](WALKTHROUGH.md) for actual captures, [PLANNER-RESEARCH.md](PLANNER-RESEARCH.md)
for references/scoring, and [VALIDATION.md](VALIDATION.md) for tested scope.
