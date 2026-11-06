# Lists, fields and shared activity

Open **Library → Lists → New list**. One form chooses the name, description,
type, rolling behavior and fields. Creation is inside the library, not scattered
through the navigation. Projects and shared notes also use a single full form.
The sidebar/drawer groups Plan, Library and expandable Task views; Preferences
contains theme/account controls. Android follows the system theme or a saved
Light/Dark preference.

| Type | Initially visible fields |
| --- | --- |
| General | Notes |
| Task list | Notes, assignment, optional due/planned day, priority, effort and images |
| Groceries | Quantity, store and notes; price off |
| Online shopping | Quantity, web link and attached images; price optional |
| Custom | Choose the fields yourself |

Task lists contain work that also appears in Tasks and Assignments. Other lists
are reference checklists whose items stay in that list. A list can be standalone
or grouped under a project; its work contributes to that project's progress.
See [Tasks and projects](PROJECTS.md) for Household/honey-do examples and safe moves.

Grocery items do not offer scheduled reminders or recipient/sound controls.
Existing saved grocery reminder values are retained but do not schedule alerts.
**Notify when items are added** is still available on the list, independently.

Expand **Customize fields** to add/remove quantity, store, notes, web link, price,
images, assignee, due date, planned day, priority or effort. Name and checked
state are always present. Changing the visible fields preserves existing item
values and reminder rules; hidden prices/links are not silently erased. Prices
use a decimal amount and a three-letter currency; no exchange rate or total
across currencies is inferred. Tasks retain the shared task/claim model.

Keep **Rolling list** enabled for ongoing groceries or purchases. Check/uncheck
individual items and choose **Clear checked items** when ready. Only currently
checked items are removed; unchecked items stay. Android queues those exact
removals durably while offline. A remotely reopened/edited item causes a retained
revision conflict rather than being deleted without review. Two people checking
different item records can sync independently. Deleting a list still requires
removing or moving its items; it cannot cascade-delete another person's work.

Turn rolling behavior off for a finite checklist; its progress remains visible.
There is no automatic reset of checked items at midnight. A repeatable routine
uses [reminders](REMINDERS.md); it does not silently uncheck completed work.

Web links accept HTTP(S) without embedded credentials. **Open link in browser**
is an explicit action. Thumbnails use only images you attach after creating the
item; there are no retailer requests, page scraping, external favicons or remote
image URLs. The isolated server retains its outbound deny policy. Attachments
use the existing bounded image validation, metadata removal and authenticated
download path. Comments and notes expand an item without replacing other list
items. Android confirmation dialogs use clear action buttons without requiring
an uppercase phrase.

Task, list-item and note rows show who created them and who last changed them,
with server timestamps. Comments and attachments update the parent task's last
activity without changing its content revision. Old records created before this
metadata existed may lack a creator/time; those show unavailable values instead
of inventing history. Queued local changes remain visibly queued until accepted.

## Store groups, filters and notices

Existing field choices stay unchanged across updates; enable Store in List settings
for an older grocery list. Hidden fields keep their stored values.

List items stay in their list rather than Tasks/Focus/Assignments. Store is a
first-class grocery field: group all stores or select one, then add an item with
that store prefilled. Search titles and all item text; filter checked state and
sort by the enabled fields. Clear checked affects the filtered view only.

List settings separately offer **Notify when items are added** and **Scheduled
item reminders**. Both preserve saved rules and contents when off. A phone also
has a global shared-additions switch. Notices name who added what and to which
list; larger batches are grouped. Initial history and your own additions do not
produce a backlog of alerts.
