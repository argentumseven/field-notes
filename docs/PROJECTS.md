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
to be done; open reference/checklist entries do not block it. **Reopen project**
lets you add or reopen work again without reopening completed tasks automatically.
Concurrent edits are checked together by the server. A rejected offline action
stays available for review rather than silently changing the grouping.

## Keep continuity

Moving a task or list updates its parent, not its identity. Comments, attachments
and task history follow the original record. Deleting a nonempty collection is
refused; move or explicitly delete its contents first. A task cannot steal
another person's working claim by moving it.

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
