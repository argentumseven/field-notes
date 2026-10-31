# Household workflow review

The target is two people using GrapheneOS phones and computers, often offline,
with one Linux home server and no Google dependency. A planner needs to support
ordinary household actions directly. A task should not need a project, a due
date, or an active claim just to exist. Assignment describes responsibility;
starting describes who is working now. Neither determines who may read content.

This review distinguishes implemented behavior from proposed work. A heading or
navigation item is not evidence that its underlying workflow exists.

## Functional priorities

| Household action | Current support and gap | Required behavior and edge cases |
| --- | --- | --- |
| Capture something immediately | Inbox tasks and standalone notes work; Android share-sheet capture and a capture widget are absent | Accept text/links through a reviewed share target, optional photo, and offline Inbox capture. Preserve the workspace chosen before external pickers return. Do not request broad media access. |
| Give a chore to either person | Planned assignments and active claims are separate; Tasks, Assignments, My tasks and Unassigned work | Explicit assignee, My tasks, Unassigned and a household assignment overview. Validate active writable accounts on the server. Retain the assignee on completion. Changes use revisions; an in-progress handoff requires releasing the claim first. Viewers and disabled accounts cannot receive new work. |
| Find something to do | Assignment-aware Focus, priorities, planned days and optional deadlines work | Focus should respect assignments. Add a deliberate Today selection and saved filters later. Overdue work, work blocked on someone, and work intentionally deferred need different explanations; an urgency score cannot replace these states. |
| Repeat household maintenance | No recurrence engine | Support fixed calendar schedules (rent every month) separately from completion intervals (replace a filter 90 days after doing it). The server creates one next occurrence transactionally; two offline completions cannot duplicate it. Define skip, missed occurrences, month-end, time zone/DST and editing one versus the series. Keep history. |
| Buy groceries or complete several steps | Lists contain tasks; tasks have no separate checklist steps | Inline complete/undo for shopping rows, rapid multi-item capture and independent step IDs/revisions. Two phones checking different steps must not replace an entire checklist. Define whether incomplete steps prevent or warn on parent completion. Manual list order is separate from Focus ranking. |
| Wait, defer, or hand off | Blocked is only a boolean; comments can explain it manually | A blocked/waiting reason, optional person/dependency and review date; snooze is distinct from a due date. Reject dependency cycles. Show the reason in rows and reminders. A handoff cannot silently steal an active claim. |
| Notice changes while the app is closed | Default-on private foreground connection, catch-up fallback and generic change alerts work | Foreground polling uses an explicit battery/ongoing-notification tradeoff and assignment-aware change hints. Validate long-running battery cost and profile forwarding on hardware; add notification routing/history as the product grows. Do not promise Doze wake-ups, introduce FCM, or assume another push app can borrow this tunnel. |
| Remember an undated task | Cached 09:00 due/all-day alarms, timed-event alerts and daily overdue digests work; no per-task reminder times | Reminder time must be independent of deadline, with per-person preferences, snooze/dismiss, bounded catch-up and deduplication across edits. Keep notification text private by default. Android/Doze timing remains best-effort without FCM. |
| Plan the week together | Internal shared calendar supports task dates and timed/all-day events | Add agenda/week views, filtering by person/status, and recurring events. Define shared-calendar editing so ordinary collaboration does not require server-admin rights; the current event policy is author/admin. Moving planned work must not rewrite its deadline. All-day dates float; timed events use instants and explicit zones. External CalDAV/subscriptions/import/export are not implemented and need their own access design: another calendar app cannot borrow Field Notes' private tunnel. |
| Find older information | Task and note text search exist separately; comments/attachments/history are not searched together | Global search with content-type/person/status/date filters, completed work and archived collections. Show who created, assigned and completed a task and when. Do not create an unencrypted full-text index that defeats content storage encryption. |
| Organize a growing household workspace | Inbox, Lists, Projects, Notes and smart views work | Archive a finished project without deleting its record; simple tags/contexts and saved filters, duplicate task/list and reusable templates. Distinguish archive, completion and deletion. Avoid making every grocery list into a project. |
| Fix an accidental action | Revision conflicts retain edits; deletion removes active data and backups can restore a workspace | Add bounded per-item trash/restore and undo with an explicit new command. Delayed offline writes must still be rejected; undo must not turn deletion into an automatic resurrection. Explain attachment retention and old backup copies. |
| Make several changes efficiently | Individual task commands work; no multi-select workflow | Bulk move/assign/complete/archive with per-item revision results and a clear account of partial conflicts. Keyboard shortcuts and rapid add on computers, usable touch targets on phones. No silent overwrite when one of the selected tasks changed. |
| Know whether the phones agree | Foreground sync, durable outbox, retained conflicts and endpoint fallback work | Show last successful sync, queued item count, actionable connection/enrollment errors, and an easy way to retry/review each affected item. Do not show an offline start or assignment as server-accepted. Test roaming, restart, expiry and simultaneous edits. |
| Recover and maintain the household server | Manual encrypted backup/restore, roles, device revocation and peer status work | Scheduled backup rotation, a restore drill, low-capacity/certificate/peer-agent health, documented upgrade/rollback and server-assisted distribution of a stable-signed APK. A downloadable archive is not proof that restoration works. Keep privileged operations out of a general web shell. |
| Share only the intended information | Current scope is one shared household workspace with household-wide roles | Make this scope explicit during enrollment. Assignment is not a privacy boundary. Optional personal tasks or per-list membership would require server enforcement on snapshots, commands, images, search, widgets and notifications, not just UI filtering. |

## Implementation order

1. Make existing core behavior coherent: aligned navigation, explicit Tasks and
   Assignments, minimal household directory, assignment-aware views/ranking,
   revision-safe handoff, offline persistence, tests and fresh screenshots.
2. Add household routines: recurrence with occurrence history, checklist steps,
   inline completion/undo and reminder/snooze semantics independent of deadlines.
3. Add finding and recovering work: global search, archive, per-item trash,
   activity history and safe bulk actions.
4. Complete daily deployment readiness: physical GrapheneOS/VPN/Doze/widget
   checks, backup/restore drills, signed update distribution and clear health.

Keep scheduling, assignment, recurrence, checklist steps, reminders and sync as
separate domain boundaries. A future feature should extend commands and tested
invariants rather than overload a quadrant, priority, project name or claim.

## Acceptance scenarios

- Both people assign or start the same revision offline: one server transaction
  wins; the other sees the accepted person/state and retains its rejected edit.
- A person is disabled or becomes a viewer while a phone is offline: queued new
  assignments to that person fail visibly. Existing history keeps its identity;
  it is not silently erased or reassigned.
- Two phones complete a recurring chore: one completion and one next occurrence,
  including after retry, server restart, month-end and a DST change.
- Different checklist items are completed offline: both survive synchronization.
- A task with no deadline still has high priority, an assignee and a reminder.
  Snoozing the reminder does not invent or postpone a deadline.
- A project is archived, then restored, without losing its task history or
  reviving a deleted attachment through a delayed upload.
- A server backup is restored onto a fresh host: content, roles and attachments
  are recovered, old sessions/peers are fenced, and retained phone edits remain
  reviewable during re-enrollment.

See [VALIDATION.md](VALIDATION.md) for executed checks. The proposed workflows
above are not a claim that recurrence, checklists, per-task reminders, archive,
trash, global search, bulk changes or automated upgrades already exist.
