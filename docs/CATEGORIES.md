# Household category management

Categories are classifications, not collections. Renaming, archiving or merging
one does **not** delete a task, list, project, note, event, attachment or history.

## Current model

Built-in category keys (General, Action, Food & cooking, Household, Errands,
Work and Personal) remain unchanged. The legacy `honey_do` value still
identifies Household. Existing saved records keep their original category key.

Administrators use **Management → Household categories** on the web to create,
rename, archive, unarchive, merge or delete unused custom categories.
An encrypted category definition has a permanent UUID, a display name,
an archive flag and a revision. Custom record values use `custom:<uuid>` and
are synced as additive metadata in `/api/snapshot`; the protocol version
and the existing database user version are unchanged. The server advertises
`household-categories-v1`. Compatible Android clients can select custom
categories and filter/label records using the synced definitions; management
operations are currently performed in the web Management screen.

Archive removes a custom category from new selections but retains assignments,
history and names. Merge explicitly previews and checks **every directly
assigned** record ID and revision, then moves category assignments in a single
SQLite transaction. It leaves every record, its original identity, parenting,
dates, attachments and other fields untouched. The source category is
archived afterward. Concurrent edits or protected records abort the whole
merge instead of creating a partial result. Merge cannot choose an inactive
destination. There is a limit of 64 custom category definitions.

Delete is allowed only for custom categories with no active assignments and
requires typing `DELETE CATEGORY`. The encrypted definition is tombstoned, not
immediately destroyed; a record later restored from an older checkpoint can
retain the historical category ID and remains editable, but the deleted name
is not offered as a new choice. Built-in categories cannot be deleted.
Category merges and deletes never cascade to the records.

Existing older clients may still edit other fields while preserving a custom
category. If they attempt to replace an unfamiliar custom category implicitly,
the server rejects the edit rather than silently reclassifying the record.
New clients explicitly acknowledge reclassification. A client connected to an
older server keeps the old built-in category controls and retains unsupported
queued changes for review.

Specialized **item categories** (e.g. Produce or Repairs) are a separate field
from the broad category. They are not renamed or cleared by changing a broad
category, and the list's context-specific vocabulary remains enforced. For
custom broad categories, free-form item labels remain available.

## List organization

A work-task list can be **Unbundled** separately from deletion. Its direct
tasks are released to Inbox; comments/images stay attached to their task IDs.
The now-empty list remains. Reference/checklist lists are not unbundled
because that would turn reference items into planner tasks. Instead, their
whole list can be moved to another project or made standalone.

Unbundle verifies the exact linked task, comment and image IDs and revisions,
honors task-claim permissions, and commits atomically. Old Android clients
do not submit this action: the server advertises `list-unbundle-v1`.
Incompatible offline requests are retained for conflict review.

Changing a list between work tasks and reference/checklist items has a
UI impact preview and requires typing `CONVERT LIST` when the list has
existing items. No item data is erased. The server continues to reject
a work-to-reference conversion with any in-progress task.

## Deployment gates

Neither this branch nor the earlier project deletion/recovery branch changes
the user's live installation until explicitly merged and deployed. They are
independent branches. This branch adds one `CREATE TABLE IF NOT EXISTS`
encrypted metadata table, but does not rewrite current objects or bump
`PRAGMA user_version`. Before merging, compile Rust and Android, run the
full test suites, check snapshots and imports/exports with custom categories,
and test an upgrade **on a copy** of an existing encrypted workspace.
Use verified backups and an isolated restore. No GitHub Actions run or
live deployment was performed here.
