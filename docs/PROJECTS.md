# Tasks, task lists and projects

**Household** describes the work, including honey-do and home maintenance. It
does not force a particular structure. Choose the smallest grouping that helps:

| Situation | Keep it as |
| --- | --- |
| Replace the hallway bulb | A standalone Household task in Inbox |
| Several independent jobs around the house | A standalone Household task list |
| Repair the bathroom, with a result to finish | A Household project with its own tasks |
| A larger repair with preparation and installation jobs | A project with task lists for those work groups, plus any direct tasks |
| Parts, measurements or a shopping checklist for the repair | A reference list linked to the project |

Categories such as Food & cooking, Errands or Work work the same way. New tasks
start with their collection's area; standalone and direct project tasks can
choose another. Item categories follow a specific list's purpose, while General
and Custom lists remain unrestricted. [Category inheritance](LISTS.md#categories-follow-purpose)
explains the choices. Moving existing work never changes its saved category,
assignment, dates, notes, comments or images.

## Track an outcome

Open **Projects → New project**. Give it a name and optional goal, coordinator
and target day. Coordination is informational: it does not assign every task to
that person. Task assignment still defaults to Everyone/unassigned, and each
task keeps its own optional due date, planned day, priority and estimate. A
project target is not copied into its tasks.

Inside a project, **New task** adds direct work. **New task list** organizes a
group of work. **New reference list** holds supporting information or shopping.
Open **Lists in this project** to visit any of those lists and return to its
project. Existing lists can be linked, moved to another project or made standalone
in **List settings**. **Items are work tasks** determines whether their items
appear in Tasks, Assignments and planning, and count toward project progress.
Shopping/reference items stay out of standalone task views and work totals. Changing this setting preserves
the items; active claims must be released or finished before converting a task
list into a reference checklist.

Progress includes direct tasks and all linked task-list items, once each. The
summary shows completed, ready, in progress, waiting, planned later and overdue
work, remaining known effort, unassigned/undated work and dates beyond the
project target. Unknown effort stays unknown. Search, person/category/priority
filters and work-group selection change the visible rows, not these totals.
The library shows project progress before you open one.

**Put on hold** keeps all work and dates but prevents new working claims and
removes its work from Focus. Release or finish active work first. Tasks can
still be edited, moved or completed. **Finish project** requires all work tasks
to be done; open reference/checklist entries do not block it. The server records
when the project was finished and who finished it. **Reopen project**
lets you add or reopen work again without reopening completed tasks automatically.
Concurrent edits are checked together by the server. A rejected offline action
stays available for review rather than silently changing the grouping.

## Keep continuity

Moving a task or list updates its parent, not its identity. Comments, attachments
and task history follow the original record. **Finish project** preserves a
completed project in the Finished and All projects views; it does not delete
anything. A newly created empty project may be deleted without first finishing
it. The **Delete** action is available in project/list settings regardless of
the status. For a populated project, select either **Keep tasks and lists** to
move direct tasks into Inbox and detach lists with their content intact, or
**Delete project and contents** to remove nested lists, tasks, comments and
images from active content. Lists offer explicit removal of their contents too.
Deletion requires typing DELETE and verifies every descendant revision so work
added or edited after confirmation is never removed silently. Saved history,
recovery versions and backups have separate retention/purge rules. A task cannot
steal another person's working claim by moving it.

Inside an open project, **Add existing task** moves an existing task or list item
into that project as direct work. The picker shows its current location; Android
also offers title search. A new task created there starts with that project
selected. From the main Tasks view, new tasks start without a parent.

Task and existing-list-item editors expose **Project or list**. Select another
destination and save to move the item, or select **No project or list (Inbox)**
and save to remove its parent completely. To move a whole list and its children,
use **List settings** instead. The existing-task picker changes membership only;
save or discard unsaved task drafts first. Android asks you to synchronize any
pending changes to that task before using the picker. Moves keep captured
revisions and workspace fences, so a concurrent edit is retained for review
rather than overwritten.

**Project history** scopes the unified item history to its work, reference lists
and their changes. New history records preserve both sides of moves. Older
history can use current membership but cannot reconstruct project moves that
predate this feature.

Item versions restore that item's complete state. A project/list version restores
its own settings and description; it does not rewind every child. Task versions
include their comments and attachments and can recover missing parent collections.
Use a named **Content checkpoint** for an atomic restore of all household content.
Restoring open work into a finished project requires reopening it first. Accounts,
device pairing and security settings remain separate.

Android can create a project, its lists and their tasks offline. The durable queue
sends parents before children; a rejected parent leaves its dependent work
retained for review. Update the paired server and app for project workspaces.
Encrypted exports preserve the hierarchy and skip duplicates on repeat import;
new files use content manifest version 2, readable by Field Notes 0.9 or newer.
Version 1 files remain importable.

[Screenshots](WALKTHROUGH.md) · [Lists and fields](LISTS.md) ·
[Offline work](OFFLINE.md) · [History and restore](HISTORY.md)
