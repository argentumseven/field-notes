package org.fieldnotes.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.datetime.Clock

/** Immutable persisted commands are the retry unit. Local display is a projection,
 * never an authoritative grant of ownership. Platform effects stay behind interfaces. */
class Workspace(
    private val transport: Transport, private val vault: Vault,
    private val idFactory: () -> String, private val scope: CoroutineScope,
) {
    private val writes = Mutex()
    private val connection = Mutex()
    private var prepared = false
    private val pairingGeneration=MutableStateFlow(0L)
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
            _state.update { it.copy(cache = loaded,connectionPaused=loaded.preferences.connectionPaused) }
            if (loaded.keys == null) {
                check(loaded.config==null && loaded.enrollment==null && loaded.snapshot==null && loaded.outbox.isEmpty() && loaded.drafts.isEmpty() && loaded.conflicts.isEmpty()) { "Saved phone identity is missing. Original records are preserved; recover the identity or use Android app settings." }
                val keys = transport.keys(); update { it.copy(keys = keys) }
            }
            var networkError:String?=null
            cache.config?.takeUnless { loaded.preferences.connectionPaused }?.let { try { transport.configure(it);prepared=true } catch(e:Exception){if(e is CancellationException)throw e;networkError="Private connection unavailable; cached work and reminders remain available."} }
            _state.update { it.copy(ready = true, needsLogin = cache.token == null, error = networkError) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "The encrypted local vault is unavailable.") }
        }
    }
    /** Persist the claim nonce before any network operation. A retry resumes the same device. */
    suspend fun beginEnrollment(invitation: Invitation, name: String) = connection.withLock {
        check(cache.config == null && cache.enrollment == null && cache.snapshot == null && cache.outbox.isEmpty() && cache.drafts.isEmpty() && cache.conflicts.isEmpty()) { "This workspace already has a connection or retained data." }
        require(name.trim().encodeToByteArray().size in 1..80) { "Enter a short phone name." }
        val secret = (newId() + newId()).replace("-", "")
        check(secret.matches(Regex("[a-f0-9]{64}")))
        pairingGeneration.update { it+1 }
        update { it.copy(enrollment = Enrollment(invitation, secret, name.trim()),preferences=it.preferences.copy(connectionPaused=false)) }
        _state.update { it.copy(connectionPaused=false,error=null) }
    }
    suspend fun advanceEnrollment() = connection.withLock {
        val pending = cache.enrollment ?: return@withLock
        if(state.value.connectionPaused)return@withLock
        val generation=pairingGeneration.value
        fun currentAttempt(){if(generation!=pairingGeneration.value||state.value.connectionPaused)throw CancellationException("Pairing paused; saved credentials are retained")}
        suspend fun rememberEnrollment(change:(Cache)->Cache){update { currentAttempt();change(it) }}
        try {
        val invitation = pending.invitation
        suspend fun bootstrap(path: String, body: JsonObject): JsonObject {
            val reply = transport.bootstrap(invitation.url, invitation.ca, path, body)
            currentAttempt()
            val result = WireJson.parseToJsonElement(reply.body).jsonObject
            if (reply.status >= 400) throw ApiError(reply.status, result.text("error", "Enrollment failed."))
            return result
        }
        var current = pending
        if (current.id.isEmpty()) {
            val claim = bootstrap("/claim", obj("token" to invitation.token.j(), "claim_secret" to current.claimSecret.j(), "public_key" to cache.keys!!.getValue("public_key"), "name" to current.name.j()))
            current = current.copy(id = claim.text("id"), verification = claim.text("verification"), stage = claim.text("state"))
            check(current.id.length == 36 && current.verification.matches(Regex("[A-F0-9]{8}"))) { "Invalid enrollment response." }
            rememberEnrollment { it.copy(enrollment = current) }
        }
        if (current.activationSecret == null) {
            val status = bootstrap("/status", obj("id" to current.id.j(), "claim_secret" to current.claimSecret.j()))
            check(status.text("user") == invitation.user && status.text("role") == invitation.role) { "Account permissions changed. Ask your administrator for a fresh invitation." }
            val configuration = status["configuration"] as? JsonObject
            if (configuration != null) {
                check(configuration.keys.all { it in setOf("address", "endpoint", "alternate_endpoints", "server_key", "preshared_key", "api", "ca_pem") }) { "Unexpected connection fields." }
                check(configuration.text("ca_pem") == invitation.ca) { "The approved server identity changed." }
                val full = JsonObject(configuration + ("private_key" to cache.keys!!.getValue("private_key")))
                current = current.copy(stage = "activating", activationSecret = status.text("activation_secret"))
                // Keep configuration and receipt credential together across process death.
                rememberEnrollment { it.copy(config = full, enrollment = current) }
            } else {
                check(status.text("state") != "active") { "This phone was activated but the receipt is unavailable. Ask the administrator to revoke it and enroll again." }
                current = current.copy(stage = status.text("state")); rememberEnrollment { it.copy(enrollment = current) }
                _state.update { it.copy(error=null) }
                return@withLock
            }
        }
        transport.configure(cache.config!!)
        currentAttempt();prepared=true
        val activation = request("/api/device/activate", obj("id" to current.id.j(), "activation_secret" to current.activationSecret!!.j()), token = null)
        val actor = WireJson.decodeFromJsonElement<Actor>(activation.getValue("actor"))
        check(actor.name == invitation.user && actor.role == invitation.role) { "Activated account does not match your invitation." }
        rememberEnrollment { it.copy(token = activation.text("token"), enrollment = null) }
        _state.update { it.copy(needsLogin = false, error = null) }
        } catch(e:ApiError){
            if(e.status in setOf(400,401,403,404,409,410,422))pauseConnection()
            throw e
        } catch(e:Exception){
            if(state.value.connectionPaused)throw CancellationException("Pairing paused; saved credentials are retained")
            throw e
        }
    }
    /** A local stop never needs a server response and never erases queued work. */
    suspend fun pauseConnection(){
        pairingGeneration.update { it+1 }
        _state.update { it.copy(connectionPaused=true,online=false) }
        try { update { it.copy(preferences=it.preferences.copy(connectionPaused=true)) } }
        catch(e:Exception){if(e is CancellationException)throw e;throw IllegalStateException("Connection paused for this process, but the pause could not be saved. Use Android app settings to force-stop before restarting.",e)}
        finally { try { withContext(NonCancellable){transport.stop()} } finally { prepared=false } }
    }
    suspend fun resumeConnection()=connection.withLock {
        update { it.copy(preferences=it.preferences.copy(connectionPaused=false)) }
        _state.update { it.copy(connectionPaused=false,error=null) }
    }
    suspend fun enrollmentSignIn(enabled: Boolean) = connection.withLock {
        val current = cache.enrollment ?: return@withLock
        check(cache.config != null && current.activationSecret != null) { "Wait for the approved tunnel configuration." }
        update { it.copy(enrollment = current.copy(stage = if (enabled) "sign_in" else "activating")) }
        _state.update { it.copy(needsLogin = true, error = null) }
    }
    suspend fun cancelEnrollment() = connection.withLock {
        val current = cache.enrollment ?: return@withLock
        check(cache.snapshot == null && cache.outbox.isEmpty() && cache.drafts.isEmpty())
        val proof = if(current.id.isNotEmpty()) obj("id" to current.id.j(), "claim_secret" to current.claimSecret.j())
            else obj("token" to current.invitation.token.j(),"claim_secret" to current.claimSecret.j(),"public_key" to cache.keys!!.getValue("public_key"))
        val result = transport.bootstrap(current.invitation.url, current.invitation.ca, "/cancel", proof)
        check(result.status in 200..299 || result.status == 410) { "Connect to your home LAN to cancel. Your administrator can also revoke this phone. An older server may need updating to cancel an attempt with a lost claim reply." }
        transport.stop()
        prepared=false
        val keys = cache.keys ?: transport.keys()
        update { it.copy(enrollment = null, config = null, keys = keys, token = null) }
    }
    suspend fun resetConnection(newKeys:Boolean=false) = connection.withLock {
        check(cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty()) { "Copy and resolve retained changes before enrolling again." }
        pauseConnection(); vault.clearImages()
        val keys = if(newKeys)transport.keys() else cache.keys ?: transport.keys()
        update { Cache(keys = keys, preferences = it.preferences.copy(connectionPaused=true)) }
        _state.update { it.copy(needsLogin = true, online = false, error = null) }
    }
    suspend fun discardRetainedChanges() { update { it.copy(outbox = emptyList(), conflicts = emptyList(), drafts = emptyMap()) } }
    suspend fun pair(raw: String) = connection.withLock {
        check(cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty()) { "Resolve retained changes before changing servers." }
        val public = WireJson.parseToJsonElement(raw).jsonObject
        check(public.keys.all { it in setOf("address", "endpoint", "server_key", "preshared_key", "api", "ca_pem") }) { "Unexpected pairing fields. Private keys must stay on this device." }
        val private = cache.keys?.get("private_key") ?: error("Generate this device's key first.")
        val configuration = JsonObject(public + ("private_key" to private))
        transport.configure(configuration);prepared=true
        vault.clearImages()
        update { it.copy(config = configuration, token = null, snapshot = null, people = emptyList(), reminded = emptySet()) }
        _state.update { it.copy(needsLogin = true, online = false, error = null) }
    }
    suspend fun updateEndpoint(endpoint: String) = connection.withLock {
        val old = cache.config ?: error("Pair this device first.")
        val next = JsonObject(old + ("endpoint" to endpoint.trim().j()))
        // Changing a route never changes the paired server key, CA or identity.
        transport.configure(next);prepared=true
        try { update { it.copy(config = next) } }
        catch (e: Exception) { transport.configure(old); throw e }
        _state.update { it.copy(online = false, error = null) }
    }
    private suspend fun exchange(path: String, body: JsonObject?, token: String?): Reply {
        check(!state.value.connectionPaused){"Private connection paused. Resume it to contact your server."}
        if(!prepared){transport.configure(cache.config?:error("Enroll this device first."));prepared=true}
        if(state.value.connectionPaused){transport.stop();prepared=false;throw CancellationException("Private connection paused")}
        val r = try { transport.exchange(path, token, body, cache.config?.text("server_key")) }
        catch(e:Exception){
            // Closing only our peer makes the next retry resolve trusted DDNS
            // again; do not keep retrying a numeric endpoint cached at launch.
            try { withContext(NonCancellable){transport.stop()} } finally { prepared=false }
            throw e
        }
        if(state.value.connectionPaused)throw CancellationException("Private connection paused; transmitted changes may already have reached home")
        if (r.status == 401) {
            _state.update { it.copy(needsLogin = true) }
            update { it.copy(token = null) } // Retain work, revoke the cached display credential durably.
        }
        return r
    }
    /** Fixed software routes share the normal connection and session fences. */
    suspend fun softwareUpdate(path:String):Reply=connection.withLock {
        require(path=="/api/device/update" || path.matches(Regex("/api/device/update/chunks/[a-f0-9]{64}/[0-9]{1,4}"))){"Only the paired software endpoint is allowed"}
        check(!state.value.needsLogin&&cache.token!=null){"Sign in before checking updates"}
        exchange(path,null,cache.token)
    }
    private suspend fun request(path: String, body: JsonObject? = null, token: String? = cache.token): JsonObject {
        val r=exchange(path,body,token)
        val json = try { WireJson.parseToJsonElement(r.body).jsonObject } catch (_: Exception) { throw ApiError(r.status, "The server returned an invalid response.") }
        if(r.status>=400)throw ApiError(r.status,json.text("error","Request failed"))
        return json
    }
    suspend fun login(name: String, password: String) = connection.withLock {
        val r = request("/api/login", obj("name" to name.j(), "password" to password.j(), "device" to "Field Notes Android".j()), token = null)
        val actor = WireJson.decodeFromJsonElement<Actor>(r.getValue("actor"))
        val old = cache.snapshot?.actor?.user_id
        check(old == null || old == actor.user_id || (cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty())) { "Retained edits belong to another account. Sign in to that account before switching." }
        if (old != null && old != actor.user_id) vault.clearImages()
        update { it.copy(token = r.text("token"), enrollment = null, snapshot = if (old == actor.user_id) it.snapshot else null, people = if(old == actor.user_id)it.people else emptyList()) }
        _state.update { it.copy(needsLogin = false, error = null) }
    }
    suspend fun removeInstallation() = connection.withLock {
        check(cache.outbox.isEmpty()&&cache.drafts.isEmpty()&&cache.conflicts.isEmpty()) { "Sync or review retained changes before removing this installation." }
        val result=request("/api/device/revoke-self",obj())
        check(result["authorization_revoked"]==JsonPrimitive(true)){"The server did not confirm device revocation."}
        _state.update { it.copy(needsLogin=true) }
        transport.stop();prepared=false
        vault.clearImages()
        update { Cache(preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false)) }
    }
    suspend fun logout() = connection.withLock {
        check(cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty()) { "Resolve queued changes, drafts and conflicts before signing out." }
        request("/api/logout", obj())
        vault.clearImages()
        update { it.copy(token = null, snapshot = null, people = emptyList(), reminded = emptySet()) }
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
    suspend fun saveDraft(target: String, revision: Long, data: JsonObject, basis: Basis? = null, action: String = "task.update") {
        require(action in setOf("task.update", "event.update", "note.update"))
        val snap = cache.snapshot ?: return
        val stamp = basis ?: snap.basis
        update { c ->
            val prior = c.drafts[target]?.takeIf { it.epoch == stamp.epoch && it.generation == stamp.generation }
            val parent = c.outbox.lastOrNull { it.command.target == target && it.command.epoch == stamp.epoch && it.command.generation == stamp.generation }?.command?.operation_id
            c.copy(drafts = c.drafts + (target to Draft(target, stamp.epoch, stamp.generation, maxOf(revision, prior?.revision ?: revision), data, parent ?: prior?.dependsOn, action)))
        }
    }
    suspend fun submitDraft(target: String) {
        // Add the command and remove its source draft in one durable write.
        update { c ->
            val draft = c.drafts[target] ?: error("No draft to save.")
            check(c.snapshot?.actor?.role != "viewer") { "Your role is read-only." }
            check(c.outbox.size + c.conflicts.size < 500) { "Resolve retained changes first." }
            val command = Command(newId(), draft.epoch, draft.generation, draft.action, target, draft.revision, draft.data)
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
            val directoryReply = try { request("/api/people") } catch(e:ApiError) { if(e.status==404)obj("people" to JsonArray(emptyList())) else throw e }
            val directory=WireJson.decodeFromJsonElement<List<HouseholdPerson>>(directoryReply.getValue("people"))
            update { c ->
                val ids = fresh.records.map { it.id }.toSet()
                val orphaned = c.drafts.values.filter { d ->
                    d.epoch != fresh.epoch || d.generation != fresh.generation ||
                        (d.target !in ids && c.outbox.none { it.command.target == d.target && it.command.action.endsWith(".create") && it.command.epoch == fresh.epoch && it.command.generation == fresh.generation })
                }
                c.copy(snapshot = fresh, people = directory, directoryRevision=directoryReply.number("revision"), drafts = c.drafts - orphaned.map { it.target }.toSet(), conflicts = c.conflicts + orphaned.map { d ->
                    Conflict(Command(newId(), d.epoch, d.generation, d.action, d.target, d.revision, d.data), "The server item was removed or restored. Copy this draft to a new item if needed.")
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
            update { it.copy(snapshot = final,lastSyncedAt=Clock.System.now().epochSeconds) }
            _state.update { it.copy(online = true, error = null) }
        } catch (e: Exception) {
            report(e); _state.update { it.copy(online = false) }
        } finally { _state.update { it.copy(syncing = false) }; connection.unlock() }
    }
    /** Cheap background checks keep the private connection alive without
     * downloading and re-encrypting an unchanged workspace every few seconds. */
    suspend fun poll(): Boolean {
        if(state.value.connectionPaused)return false
        if(!connection.tryLock())return false
        var changed=false
        try {
            if(cache.token==null||cache.config==null||!state.value.ready)return false
            val head=request("/api/head")
            changed=cache.snapshot?.let { it.epoch!=head.text("epoch")||it.generation!=head.number("generation")||it.sequence!=head.number("sequence")||cache.directoryRevision!=head.number("directory_revision") }?:true
            if(cache.outbox.isNotEmpty())changed=true
            _state.update { it.copy(online=true,error=null) }
        }catch(e:Exception){report(e);_state.update { it.copy(online=false) }}
        finally{connection.unlock()}
        if(changed)sync()
        return changed&&state.value.online
    }

    private suspend fun reject(id: String, reason: String) = update { c ->
        val pending = c.outbox.first { it.command.operation_id == id }
        val dependentDrafts = c.drafts.values.filter { it.dependsOn == id }
        c.copy(outbox = c.outbox.filterNot { it.command.operation_id == id }, drafts = c.drafts - dependentDrafts.map { it.target }.toSet(), conflicts = c.conflicts + Conflict(pending.command, reason) + dependentDrafts.map { d ->
            Conflict(Command(newId(), d.epoch, d.generation, d.action, d.target, d.revision, d.data), "An earlier local edit was rejected. This unsaved dependent draft was kept too.")
        })
    }
    suspend fun resolve(id: String, reapply: Boolean) {
        update { c ->
            val conflict = c.conflicts.firstOrNull { it.command.operation_id == id } ?: return@update c
            if (!reapply) c.copy(conflicts = c.conflicts.filterNot { it.command.operation_id == id }) else {
                val snap = c.snapshot ?: error("Synchronize before reapplying.")
                val old = conflict.command
                val current = snap.records.firstOrNull { it.id == old.target }
                check(current != null || old.action.endsWith(".create")) { "The item was deleted. Copy this text into a new item instead." }
                check(snap.actor.role != "viewer") { "Your role is read-only." }
                c.copy(conflicts = c.conflicts.filterNot { it.command.operation_id == id }, outbox = c.outbox + Pending(old.copy(operation_id = newId(), epoch = snap.epoch, generation = snap.generation, expected_revision = current?.revision)))
            }
        }
    }
    suspend fun preferences(preferences: Preferences) { update { it.copy(preferences = preferences.copy(connectionPaused=it.preferences.connectionPaused)) } }
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
                "task" -> JsonObject(mapOf("priority" to 3.j(), "planned_for" to "".j(), "estimate_minutes" to 0.j(), "pinned" to JsonPrimitive(false), "blocked" to JsonPrimitive(false), "notes" to "".j(), "quadrant" to "do".j(), "due" to "".j(), "status" to "todo".j(), "claimant" to JsonNull,"assignee" to JsonNull) + c.data)
                "comment" -> JsonObject(c.data + ("author" to (cache.snapshot?.actor?.name ?: "You").j()))
                else -> JsonObject(c.data - "bytes")
            }
            rows[c.target] = VisibleRecord(Record(c.target, kind, 0, data), true)
        } else {
            val current = rows[c.target] ?: continue
            val d = current.record.data
            if (c.action.endsWith(".delete")) { rows.remove(c.target); rows.entries.removeAll { it.value.record.data.text("task_id") == c.target }; continue }
            val change = when (c.action) {
                "task.update", "event.update", "note.update", "project.update", "list.update" -> c.data
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
