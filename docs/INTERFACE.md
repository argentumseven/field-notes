# Interface direction

The LAN web interface now implements this direction; generated screenshots are
in dist/screenshots/. Native Kotlin/Compose UI is now compiled for Android. See
[VALIDATION.md](VALIDATION.md) for the distinction between implemented and planned
behavior. Talaris's task screenshot was inspected as a reference.

Keep the restrained project sidebar, collection switcher, quick-add row, compact
task cards, and contextual detail panel. Translate the desktop shell's useful
work surfaces into an ordinary app. Do not copy its system bar, launcher hooks,
wallpaper, fonts, icons, CSS, or screenshots into the shipped product.

## Visual language

Use an ink/slate base, cool blue primary accent, restrained violet secondary
accent, and warm neutral light mode. Avoid tinting the entire surface of every
card by status. A narrow marker, icon, label, and subtle border communicate state
without relying on color alone. Use rounded corners sparingly, consistent type
scales, locally bundled assets, and restrained animation.

Candidate starting tokens (contrast must be measured before release):

| Token | Dark | Light |
| --- | --- | --- |
| Background | `#0B1020` | `#F6F7FB` |
| Surface | `#151D30` | `#FFFFFF` |
| Raised surface | `#1C2840` | `#EAEFF7` |
| Main text | `#EDF2FC` | `#17243A` |
| Muted text | `#A7B6D0` | `#526078` |
| Primary accent | `#85B9FF` | `#2756AF` |
| Secondary accent | `#BEABFF` | `#6942B7` |
| Destructive action | `#FFB3AE` | `#AD2635` |

Status, focus, disabled, hover, chart, and high-contrast tokens belong in the
design system. Do not scatter literal colors across widgets. Theme settings are
local user preferences, not sync conflicts on household content.

## Main views

- Projects and collections in the left rail; list/board/matrix controls share
  one filter state. Saved views store typed filters, not arbitrary executable
  queries. Keyboard quick add and search keep common work one action away.
- A task opens a detail inspector: title, status, claimant, assignment, due date,
  notes, comments, attachments, and activity. Mobile uses a full-height detail
  page and a compact navigation pattern instead of shrinking desktop columns.
- Comments show author and server-accepted order. Local drafts remain editable
  during an outage. Image uploads expose progress, retry/cancel, and validation
  failures without pretending an unfinished upload is attached.
- Claim state says who is working and whether a local change is pending. A
  conflict compares the server version with the retained draft and explains the
  action needed. “Synced” requires a committed server acknowledgement.
- Administration has its own navigation entry for permitted roles. Health is
  understandable: storage, backup age, device last sync, push test results, and
  connection status. Hide internal traces/stack dumps from normal workflows.
- Destructive bulk actions preview scope/count, state backup consequences, and
  require recent authentication. “Override” is a named audited operation, not a
  secret switch that bypasses revisions and generations.

Support keyboard navigation and visible focus, screen-reader labels, minimum
mobile target sizes, text scaling, reduced motion, and WCAG AA contrast. A board
must have an accessible non-drag alternative. Do not expose private task text in
lock-screen notifications unless that device's user explicitly enables previews.
