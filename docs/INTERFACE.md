# Interface

Compact charcoal/paper, teal accents, quiet task rows, and a plain page glyph.
Use system fonts and functional icons, with no decorative art or runtime assets.
The selected direction replaces the earlier blue/violet card-heavy prototype.

The browser sidebar and Android workspace drawer expose Inbox, Focus, Tasks,
Assignments, Calendar, Notes and Collections, followed by My tasks, Unassigned,
Unscheduled, Waiting and Completed.
Lists and Projects have separate sections. Inbox accepts tasks without a parent;
Lists suit groceries, routines and checklists, while Projects group an outcome.
Shared Notes are first-class reference documents with pinning/search, separate
from task notes and comments. The New menu creates a task, list, project or note.
The task editor and capture form offer a real person selector. Assignment is
planning responsibility; the working claim is exclusive server-accepted activity.
Handoffs require releasing active claims. Focus, widgets and due reminders
respect assignments. Old matrix buckets are retained only for protocol
compatibility, without a confusing Collection selector in the interface.
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
Every signed-in user has Change password in the sidebar, with current/new/confirm
fields. People offers permission-scoped Reset password for other accounts and
confirms initial passwords when adding users. Explain session revocation before
submission and return the changing user to sign-in; never prefill a saved password
or display its hash. Password dialogs clear secret fields on success/cancel.
Device enrollment is a short sequential flow: trusted QR review, phone code,
root provisioning acknowledgement, then tunnel activation. Explain pending and
failed steps without asking users to edit peer keys or connection JSON.

Android has a real home widget with private defaults, title opt-in and a clear
launcher disclosure. Widget taps open the credential-locked app. Camera access
is scoped to the offline QR scanner; paste and invitation links allow devices
without a camera. System bar/lock/scanner colors follow the same app theme.

Actions use visible button surfaces and borders in both themes. Primary actions
are filled teal; secondary actions are outlined; destructive actions use an error
border or fill and explicit confirmation. Do not make actions look like body text.
Android secondary buttons are at least 48 dp high, settings actions align to the
full width, and Settings keeps Close visible above its scrolling content.
Home-widget open/refresh actions also have visible button surfaces.
Web actions and APK downloads have persistent boundaries, keyboard focus,
pressed and disabled states.

Use accessible control labels, semantic status text, visible focus and enabled
states, and bounded responsive layouts. Keep destructive confirmations specific
and distinguish server deletion from discarding a retained local copy. No
Google account, VPN-slot prompt, cloud account, external font or analytics path
belongs in the product flow.

See [WALKTHROUGH.md](WALKTHROUGH.md) for actual captures, [PLANNER-RESEARCH.md](PLANNER-RESEARCH.md)
for references/scoring, and [VALIDATION.md](VALIDATION.md) for tested scope.
