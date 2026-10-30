package org.fieldnotes.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Immutable persisted commands are the retry unit. Local display is a projection,
 * never an authoritative grant of ownership. Platform effects stay behind interfaces. */
class Workspace(
    private val transport: Transport, private val vault: Vault,
    private val idFactory: () -> String, private val scope: CoroutineScope,
) {
    private val writes = Mutex()
    private val connection = Mutex()
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state
    private val cache get() = _state.value.cache

    private suspend fun update(transform: (Cache) -> Cache) = writes.withLock {
        val next = transform(cache)
        require(WireJson.encodeToString(next).encodeToByteArray().size <= 32 * 1024 * 1024) { "Local encrypted cache is full. Sync or resolve pending images before adding more." }
        vault.save(next) // Failure never publishes an unpersisted command as saved.
        _state.update { it.copy(cache = next) }
    }
    suspend fun initialize() = connection.withLock {
        try {
            val loaded = vault.load()
            _state.update { it.copy(cache = loaded) }
            if (loaded.keys == null) { val keys = transport.keys(); update { it.copy(keys = keys) } }
            cache.config?.let { transport.configure(it) }
            _state.update { it.copy(ready = true, needsLogin = cache.token == null || cache.snapshot == null, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "The encrypted local vault is unavailable.") }
        }
    }
    suspend fun pair(raw: String) = connection.withLock {
        check(cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty()) { "Resolve retained changes before changing servers." }
        val public = WireJson.parseToJsonElement(raw).jsonObject
        check(public.keys.all { it in setOf("address", "endpoint", "server_key", "preshared_key", "api", "ca_pem") }) { "Unexpected pairing fields. Private keys must stay on this device." }
        val private = cache.keys?.get("private_key") ?: error("Generate this device's key first.")
        val configuration = JsonObject(public + ("private_key" to private))
        transport.configure(configuration)
        vault.clearImages()
        update { it.copy(config = configuration, token = null, snapshot = null, reminded = emptySet()) }
        _state.update { it.copy(needsLogin = true, online = false, error = null) }
    }
    suspend fun updateEndpoint(endpoint: String) = connection.withLock {
        val old = cache.config ?: error("Pair this device first.")
        val next = JsonObject(old + ("endpoint" to endpoint.trim().j()))
        // Changing a route never changes the paired server key, CA or identity.
        transport.configure(next)
        try { update { it.copy(config = next) } }
        catch (e: Exception) { transport.configure(old); throw e }
        _state.update { it.copy(online = false, error = null) }
    }
    private suspend fun request(path: String, body: JsonObject? = null, token: String? = cache.token): JsonObject {
        val r = transport.exchange(path, token, body, cache.config?.text("server_key"))
        val json = try { WireJson.parseToJsonElement(r.body).jsonObject } catch (_: Exception) { throw ApiError(r.status, "The server returned an invalid response.") }
        if (r.status == 401) _state.update { it.copy(needsLogin = true) }
        if (r.status >= 400) throw ApiError(r.status, json.text("error", "Request failed"))
        return json
    }
    suspend fun login(name: String, password: String) = connection.withLock {
        val r = request("/api/login", obj("name" to name.j(), "password" to password.j(), "device" to "Field Notes Android".j()), token = null)
        val actor = WireJson.decodeFromJsonElement<Actor>(r.getValue("actor"))
        val old = cache.snapshot?.actor?.user_id
        check(old == null || old == actor.user_id || (cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty())) { "Retained edits belong to another account. Sign in to that account before switching." }
        if (old != null && old != actor.user_id) vault.clearImages()
        update { it.copy(token = r.text("token"), snapshot = if (old == actor.user_id) it.snapshot else null) }
        _state.update { it.copy(needsLogin = false, error = null) }
    }
    suspend fun logout() = connection.withLock {
        check(cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty()) { "Resolve queued changes, drafts and conflicts before signing out." }
        request("/api/logout", obj())
        vault.clearImages()
        update { it.copy(token = null, snapshot = null, reminded = emptySet()) }
        _state.update { it.copy(needsLogin = true, online = false) }
    }
    fun enqueueAsync(action: String, target: String, data: JsonObject, revision: Long?, basis: Basis? = null) = scope.launch {
        try { enqueue(action, target, data, revision, basis); sync() } catch (e: Exception) { report(e) }
    }
    suspend fun enqueue(action: String, target: String, data: JsonObject, revision: Long?, basis: Basis? = null) {
        val snap = cache.snapshot ?: error("Sign in and synchronize first.")
        check(snap.actor.role != "viewer") { "Your role is read-only." }
        update { c ->
            check(c.outbox.size + c.conflicts.size < 500) { "Resolve pending changes before adding more." }
            val stamp = basis ?: snap.basis
            val command = Command(newId(), stamp.epoch, stamp.generation, action, target, revision, data)
            val predecessor = c.outbox.lastOrNull { it.command.target == target }?.command?.operation_id
            c.copy(outbox = c.outbox + Pending(command, predecessor))
        }
    }
    suspend fun saveDraft(target: String, revision: Long, data: JsonObject, basis: Basis? = null) {
        val snap = cache.snapshot ?: return
        val stamp = basis ?: snap.basis
        update { c ->
            val prior = c.drafts[target]?.takeIf { it.epoch == stamp.epoch && it.generation == stamp.generation }
            val parent = c.outbox.lastOrNull { it.command.target == target && it.command.epoch == stamp.epoch && it.command.generation == stamp.generation }?.command?.operation_id
            c.copy(drafts = c.drafts + (target to Draft(target, stamp.epoch, stamp.generation, maxOf(revision, prior?.revision ?: revision), data, parent ?: prior?.dependsOn)))
        }
    }
    suspend fun submitDraft(target: String) {
        // Add the command and remove its source draft in one durable write.
        update { c ->
            val draft = c.drafts[target] ?: error("No draft to save.")
            check(c.snapshot?.actor?.role != "viewer") { "Your role is read-only." }
            check(c.outbox.size + c.conflicts.size < 500) { "Resolve retained changes first." }
            val command = Command(newId(), draft.epoch, draft.generation, "task.update", target, draft.revision, draft.data)
            c.copy(outbox = c.outbox + Pending(command, c.outbox.lastOrNull { it.command.target == target }?.command?.operation_id), drafts = c.drafts - target)
        }
    }
    suspend fun discardDraft(target: String) { update { it.copy(drafts = it.drafts - target) } }
    private fun report(e: Exception) { if (e is CancellationException) throw e; _state.update { it.copy(error = e.message ?: "Operation failed.") } }
    fun clearError() { _state.update { it.copy(error = null) } }
    fun launch(block: suspend () -> Unit) = scope.launch { try { block() } catch (e: Exception) { report(e) } }

    suspend fun sync() {
        if (!connection.tryLock()) return
        if (cache.config == null || cache.token == null || !_state.value.ready) { connection.unlock(); return }
        _state.update { it.copy(syncing = true) }
        try {
            val fresh = WireJson.decodeFromJsonElement<Snapshot>(request("/api/snapshot"))
            check(fresh.protocol == 1) { "Update this client before using the server's new protocol." }
            update { c ->
                val ids = fresh.records.map { it.id }.toSet()
                val orphaned = c.drafts.values.filter { d ->
                    d.epoch != fresh.epoch || d.generation != fresh.generation ||
                        (d.target !in ids && c.outbox.none { it.command.target == d.target && it.command.action == "task.create" && it.command.epoch == fresh.epoch && it.command.generation == fresh.generation })
                }
                c.copy(snapshot = fresh, drafts = c.drafts - orphaned.map { it.target }.toSet(), conflicts = c.conflicts + orphaned.map { d ->
                    Conflict(Command(newId(), d.epoch, d.generation, "task.update", d.target, d.revision, d.data), "The server item was removed or restored. Copy this draft to a new task if needed.")
                })
            }
            while (true) {
                val item = cache.outbox.firstOrNull() ?: break
                val c = item.command
                val fence = when {
                    c.epoch != fresh.epoch || c.generation != fresh.generation -> "Workspace reset or restored. The old draft was not uploaded."
                    item.dependsOn != null -> "An earlier queued edit was rejected. Review this dependent draft."
                    else -> null
                }
                if (fence != null) { reject(c.operation_id, fence); continue }
                try {
                    update { it.copy(outbox = it.outbox.map { p -> if (p.command.operation_id == c.operation_id) p.copy(attempted = true) else p }) }
                    val result = request("/api/commands", WireJson.encodeToJsonElement(c).jsonObject)
                    val record = WireJson.decodeFromJsonElement<Record>(result.getValue("record"))
                    update { state ->
                        val snap = state.snapshot!!
                        val deleted = record.data["deleted"] == JsonPrimitive(true)
                        val rows = snap.records.filterNot { it.id == record.id || (deleted && it.data.text("task_id") == record.id) } + if (deleted) emptyList() else listOf(record)
                        state.copy(snapshot = snap.copy(records = rows, sequence = result.number("sequence")), drafts = state.drafts.mapValues { (_, draft) -> if (draft.dependsOn == c.operation_id) draft.copy(revision = record.revision, dependsOn = null) else draft }, outbox = state.outbox.filterNot { it.command.operation_id == c.operation_id }.map { next ->
                            if (next.dependsOn == c.operation_id) next.copy(dependsOn = null, command = next.command.copy(expected_revision = record.revision)) else next
                        })
                    }
                } catch (e: ApiError) {
                    if (e.status == 401 || e.status == 429 || e.status >= 500) throw e
                    reject(c.operation_id, e.message)
                }
            }
            val final = WireJson.decodeFromJsonElement<Snapshot>(request("/api/snapshot"))
            update { it.copy(snapshot = final) }
            _state.update { it.copy(online = true, error = null) }
        } catch (e: Exception) {
            report(e); _state.update { it.copy(online = false) }
        } finally { _state.update { it.copy(syncing = false) }; connection.unlock() }
    }
    private suspend fun reject(id: String, reason: String) = update { c ->
        val pending = c.outbox.first { it.command.operation_id == id }
        val dependentDrafts = c.drafts.values.filter { it.dependsOn == id }
        c.copy(outbox = c.outbox.filterNot { it.command.operation_id == id }, drafts = c.drafts - dependentDrafts.map { it.target }.toSet(), conflicts = c.conflicts + Conflict(pending.command, reason) + dependentDrafts.map { d ->
            Conflict(Command(newId(), d.epoch, d.generation, "task.update", d.target, d.revision, d.data), "An earlier local edit was rejected. This unsaved dependent draft was kept too.")
        })
    }
    suspend fun resolve(id: String, reapply: Boolean) {
        update { c ->
            val conflict = c.conflicts.firstOrNull { it.command.operation_id == id } ?: return@update c
            if (!reapply) c.copy(conflicts = c.conflicts.filterNot { it.command.operation_id == id }) else {
                val snap = c.snapshot ?: error("Synchronize before reapplying.")
                val old = conflict.command
                val current = snap.records.firstOrNull { it.id == old.target }
                check(current != null || old.action.endsWith(".create")) { "The item was deleted. Copy this text into a new task instead." }
                check(snap.actor.role != "viewer") { "Your role is read-only." }
                c.copy(conflicts = c.conflicts.filterNot { it.command.operation_id == id }, outbox = c.outbox + Pending(old.copy(operation_id = newId(), epoch = snap.epoch, generation = snap.generation, expected_revision = current?.revision)))
            }
        }
    }
    suspend fun preferences(preferences: Preferences) { update { it.copy(preferences = preferences) } }
    suspend fun markReminded(ids: Set<String>) { update { it.copy(reminded = it.reminded + ids) } }
    fun newId() = idFactory.invoke()
}

/** Visible state includes pending work, with a separate provisional marker. */
data class VisibleRecord(val record: Record, val pending: Boolean = false)
fun projectRecords(cache: Cache): List<VisibleRecord> {
    val rows = cache.snapshot?.records?.associate { it.id to VisibleRecord(it) }?.toMutableMap() ?: mutableMapOf()
    for (item in cache.outbox) {
        val c = item.command
        if (c.action.endsWith(".create")) {
            val kind = c.action.substringBefore('.')
            val data = when (kind) {
                "task" -> JsonObject(mapOf("notes" to "".j(), "quadrant" to "do".j(), "due" to "".j(), "status" to "todo".j(), "claimant" to JsonNull) + c.data)
                "comment" -> JsonObject(c.data + ("author" to (cache.snapshot?.actor?.name ?: "You").j()))
                else -> JsonObject(c.data - "bytes")
            }
            rows[c.target] = VisibleRecord(Record(c.target, kind, 0, data), true)
        } else {
            val current = rows[c.target] ?: continue
            val d = current.record.data
            if (c.action.endsWith(".delete")) { rows.remove(c.target); rows.entries.removeAll { it.value.record.data.text("task_id") == c.target }; continue }
            val change = when (c.action) {
                "task.update" -> c.data
                "task.start" -> obj("status" to "in_progress".j(), "claimant" to (cache.snapshot?.actor?.user_id ?: "").j())
                "task.complete" -> obj("status" to "done".j(), "claimant" to JsonNull)
                "task.release", "task.reopen" -> obj("status" to "todo".j(), "claimant" to JsonNull)
                else -> obj()
            }
            rows[c.target] = VisibleRecord(current.record.copy(data = JsonObject(d + change)), true)
        }
    }
    return rows.values.toList()
}
