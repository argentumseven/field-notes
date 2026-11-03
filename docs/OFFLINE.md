# Offline work and reconnection

An enrolled Android phone keeps an encrypted snapshot of its workspace and a
durable queue of edits. Cached lists, tasks, notes and downloaded images can be
used without a connection. Changes are saved before the UI reports them as queued
and remain available across app restarts. Offline task starts are provisional;
they cannot reserve a shared task while the server is unreachable.

On reconnection the phone downloads current server state and submits queued
operations using their original unique IDs. A response lost after the server
commits can be retried without duplicating that operation. Dependent edits use
the accepted revision of their own predecessor. Device clocks cannot overwrite
a server decision or take another person's claim.

Changes to different records can join the same list. For example, while your
phone is away you add Eggs and change the Milk task's notes; your partner renames
the list and changes Bread. Those operations can all survive reconnection. A
list rename does not make its separate tasks' revisions stale.

If both people edit the same task, note or list record, the current implementation
uses record revisions and retains a stale edit as a conflict for review. It does
not automatically perform a three-way text merge or merge different fields of
the same record. The server's current version stays intact and the phone retains
the rejected command. Deletion, workspace reset/restore and revoked permissions
also prevent stale operations from silently recreating or overwriting data.

Queued work and conflicts are bounded to 500 combined operations; the serialized
encrypted cache is bounded to 32 MiB. A missing image cannot be downloaded during
an outage. Long outages may require signing in again; credentials, cached work
and queued edits are retained. Revocation cannot be bypassed by reconnecting with
old credentials. Uninstalling or clearing Android storage removes the local vault,
so synchronize or review retained changes first.

The Kotlin regression covers an outage, restart, reconnection into a remotely
renamed/edited list, addition of another task, and a later same-task collision
retained as a conflict. API tests independently cover server revisions,
concurrent task claims, replay and restore/deletion fences. Physical prolonged
GrapheneOS/Doze/roaming behavior remains a separate deployment check.
