# Household planning in 0.6

New tasks, calendar events and reminder notes notify **Everyone** by default.
Choose specific people when an appointment should ring only their phones.
Existing personal choices stay personal through updates.

Use **Tasks** for work, **Lists** for rolling/checkable items, and **Notes** for
reference material. Grocery and other list entries stay inside their collection
instead of filling Tasks, Focus or Assignments. Clickable rows show their type
and category: Food & cooking, Honey-do, Action, Home, Errands, Work or Personal.
Title/content searches, category/person/status/priority filters and sort controls
make larger collections navigable.

Grocery lists include **Store**. The list groups items by store; select a store
to filter it and prefill the next item. Search also checks notes, quantities,
links and prices. **Clear checked** affects only checked records in the current
filtered view and still checks their revisions before deleting.

**Later & reminders** holds ideas that are not tasks or appointments yet. A
note may stay undated, or bring itself back on a chosen date/time without
occupying calendar time. Tomorrow, In a week and Someday are quick choices.
The full note content is searchable. Use its reminder controls for sound and
additional reminder rules.

An event's **Repeat** controls offer daily, every other day, selected weekdays,
alternating weeks, monthly and individual dates, with custom intervals and
skipped dates. Repeat timing follows the original wall-clock timezone across
daylight-saving changes. Missing month dates and nonexistent clock-change times
are skipped. Start-only events remain valid. An edit applies to the stored series;
use skipped dates for exceptions. Repeating event spans are bounded to 31 days.
Update phones to 0.6 before relying on their repeat expansion and reminders;
older compatible phones can still sync but recommend the update.

Calendars and the actual Android launcher widgets start on **Sunday**. Month
cells form a continuous grid. Web week/day headings, all-day cells and hours
share one scrollbar viewport, so a wider OS scrollbar cannot offset the columns.
Editors keep their actions above the software keyboard and expose scroll rails.
The top-left website and phone branding uses the APK's calendar artwork and
colors, with a separate navigation button. `tools/sync-brand-icons.py` derives
both header assets from the launcher resources; build checks prevent drift.

Shared additions can say **“Sophia added grocery item ‘Bananas’ to ‘Costco
Shopping List’”**, with notes, store and quantity in expanded details. Larger
batches are grouped into one notice. Your own additions and initial history
downloads do not notify you. Each phone can disable shared-addition notices.
Each list separately controls **Notify when items are added** and **Scheduled
item reminders**. Muting preserves its data and saved reminder rules.

[Export & import](CONTENT-FILES.md) provides encrypted, duplicate-safe content
files on both clients. [Updates](UPDATES.md) preserve identities and queued work.

Workflow references reviewed: [Tasks.org](https://github.com/tasks/tasks),
[Vikunja](https://github.com/go-vikunja/vikunja), and
[Super Productivity](https://github.com/johannesjo/super-productivity).
Ideas informed views, recurrence presets, filters and timeline controls; no
source code, icons or artwork was copied.
