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

## Categories follow purpose

A list's **type** and broad **category** describe its purpose. The optional
category on an item describes the item itself. They are separate fields.

| List purpose or task area | Relevant item choices include |
| --- | --- |
| Groceries | Produce, Dairy & eggs, Pantry, Frozen, Cleaning & household, Pet supplies |
| Online shopping | Clothing & shoes, Electronics, Home & garden, Books & media, Gifts |
| Household / honey-do | Repairs, Maintenance, Cleaning, Garden & outdoors, Household admin |
| Food & cooking | Meal planning, Cooking & prep, Baking, Recipes, Kitchen upkeep |
| Errands | Pickup, Drop-off, Returns, Appointments, Service visits |
| Work | Planning, Research, Writing, Review, Meetings & follow-ups, Administration |
| Personal | Health & care, Fitness, Learning, Hobbies, Finance, Travel |
| General, Action or untagged | Any item label you type, or no label |
| Custom | Any item label, even when the list has a broad area such as Household |

Specific purposes offer **Uncategorized**, relevant choices and **Other**.
No category is mandatory. Grocery and shopping presets take precedence over
an unrelated old item/area tag; grocery items do not show Work/Action choices.
Other task/reference lists use their own area. A general/untagged list is a
boundary: a containing project's category or words in its title do not impose
an item vocabulary. Custom lists always stay open-ended.

Standalone and direct project tasks use their chosen area, initially inherited
from the parent. Choose General for unrestricted labels. This does not change
assignments, dates, priorities, the work/reference distinction or reminder policy.

Moving an item, changing a list's type/area, or updating software never clears
its saved item category. A label outside the new choices remains selected as a
**saved label** until you deliberately replace or clear it. Older broad item
tags remain stored too; a scoped list hides the unrelated area picker. General
and custom lists can still edit those broad tags freely.

Labels appear on item rows, participate in title/content search, and can be
filtered/sorted under **Item category** (Android: **List options**). Bulk cleanup
respects this filter as well as search/store and still includes hidden checked
items. Use **Clear filters** to return to all categories.

The web and native vocabularies are generated from
[shared/item-contexts.json](../shared/item-contexts.json). The server validates
new choices against the current parent within the command transaction, while
allowing unchanged saved labels and preserving imports/restores. General/custom
labels have only ordinary text/size bounds, not a forced subject vocabulary.
See [Updates](UPDATES.md) for compatible older phones and retained newer edits.

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
individual items, or choose the row's **Delete** / editor's **Delete item** to
remove an item without checking it first. Confirm with a normal button; no
uppercase phrase is required. Names and revisions are frozen when Delete is
chosen, so someone else's intervening amendment prevents a stale removal.

Choose **Delete checked (N)** to clean up in bulk (**Clear checked** in older
versions). The count includes checked rows hidden by **Show: Unchecked**. Search
and store filters still limit the selection, and the confirmation identifies
what will be removed. Only checked items are removed; unchecked items stay.
Android queues those exact
removals durably while offline. A remotely reopened/edited item causes a retained
revision conflict rather than being deleted without review. Two people checking
different item records can sync independently. Deleting a populated list offers
an explicit, confirmed remove-contents action; the server checks its full
descendant set and revisions and refuses changes that cannot be safely
authorized. It never silently cascades from a normal list edit.

Web **List history**, or Android **List options → List history**, opens the
collection's trail and retained item versions. Deletion removes active items and
their comments/images; retained versions remain recoverable until the configured
retention policy or a separate history purge removes them. Active lists do not
keep growing just because history is retained. See [Recovery](RECOVERY.md).

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
sort by the enabled fields. **Delete checked** respects search/store filters
independently of which checked state is displayed. **Clear filters** offers a
direct way out of an empty filtered view. On Android, Store stays visible while
sorting/history sit under **List options**.

List settings separately offer **Notify when items are added** and **Scheduled
item reminders**. Both preserve saved rules and contents when off. A phone also
has a global shared-additions switch. Notices name who added what and to which
list; larger batches are grouped. Initial history and your own additions do not
produce a backlog of alerts.

Grocery lists do not expose scheduled item reminder controls. Other list types
retain that separate setting. [Live updates and shopping](LIVE-UPDATES.md) explains
freshness, queued removals and why an older phone may miss new amendments.

## Phone page navigation

See [Phone lists and scrolling](MOBILE-LISTS.md). The whole list page scrolls;
filters and bulk actions are under List options. Opening an item and returning
keeps your place. Checkboxes and individual delete controls remain separate.
