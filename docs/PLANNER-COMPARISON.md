# Planner refinements and comparison

Reviewed public documentation and UI strings on 2026-11-06. This is a feature
comparison, not a claim to reproduce another app. No source code or assets copied.

| Reference | Useful detail | Field Notes outcome |
| --- | --- | --- |
| [Fossify Calendar](https://github.com/FossifyOrg/Calendar), including its [strings](https://github.com/FossifyOrg/Calendar/blob/master/app/src/main/res/values/strings.xml) | Annual repeats, repeat endings, widgets and view choices | Added Every year and custom Years, inclusive Through day and skipped-date controls, next-occurrence previews spanning years. Day/week/month and actual Android widgets retained. |
| [Etar](https://github.com/Etar-Group/Etar-Calendar) | Multiple calendar views and home-screen access | Widget date links now explicitly select that day's timeline even after the app was last used in Month or another area. |
| [Grocy](https://github.com/grocy/grocy), [shopping-list view](https://github.com/grocy/grocy/blob/master/views/shoppinglist.blade.php) | Done/undone filters, store context, clearing checked shopping items | Existing store groups and filters retained. Added Reuse checked items with a frozen confirmation selection and stale-revision rejection. Clearing and reuse retain conflicting edits for review. |
| [Super Productivity](https://github.com/johannesjo/super-productivity) | Separate planning, project and task organization | Kept project/list/Inbox boundaries, optional dates and planned-day vs due-date distinction. Consolidated overlapping Home/maintenance and Honey-do choices into Household without rewriting stored categories. |
| [Tasks.org](https://github.com/tasks/tasks) | Privacy-minded task organization and independent task access | Kept independent tasks, priorities, assignments, content search and offline changes alongside the calendar. |

## Implemented details

- Every year uses the existing monthly interval of twelve, including a custom
  every-N-years interval, so compatible older clients understand it. February 29
  follows a skip-invalid-date policy, explained by actual future previews.
- Repeating events are edited in their saved timezone, with an explicit label.
  Unchanged ambiguous fall-back times preserve the original instant; nonexistent
  spring-forward clock times are rejected visibly.
- Widget status states when data last synced and whether updates are live,
  periodic, manual, paused, offline or queued. Adding a widget can request bounded
  automatic catch-up when the cache is old. Resize/midnight repaint does not
  trigger a network request. Android battery/profile/VPN policy still applies.
- Reuse checked resets the original list items rather than creating duplicate
  records. Quantities, notes, stores, images and comments are preserved.
- Buttons share an 8 dp/native or 8 px/web control radius, in both themes.
- [Indexed recovery](RECOVERY.md) adds item versions, whole-content checkpoints,
  restore previews and configurable retention in the two interfaces.

## Deliberate remaining gaps

Monthly patterns such as “last weekday” and ending after an occurrence count need
new recurrence wire semantics and explicit older-client handling; they are not
silently approximated. Current custom schedules support intervals, selected
weekdays, explicit irregular dates, Through day and skipped dates. External
Google/CalDAV calendars, retailer thumbnail fetching and other apps' cloud push
are not introduced. The selected design uses private household data, attached
images and no Google services. Physical GrapheneOS profile forwarding and sound
behavior still require testing on the actual phones.
