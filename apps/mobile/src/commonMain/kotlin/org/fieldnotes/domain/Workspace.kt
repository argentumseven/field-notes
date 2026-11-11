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
        val candidate = transform(cache)
        var next = if(candidate.history!=null&&candidate.currentHistory()==null)candidate.copy(history=null) else candidate
        // A disposable read cache must never displace queued edits or drafts.
        if(next.history!=null&&WireJson.encodeToString(next).encodeToByteArray().size>32*1024*1024)next=next.copy(history=null)
        require(WireJson.encodeToString(next).encodeToByteArray().size <= 32 * 1024 * 1024) { "Local encrypted cache is full. Sync or resolve pending images before adding more." }
        // Persist and publish as one short write unit. Cancellation after persistence
        // must not leave the next edit working from an older in-memory queue.
        withContext(NonCancellable){vault.save(next);_state.update { it.copy(cache = next) }}
    }
    suspend fun initialize() = connection.withLock {
        try {
            val loaded = vault.load()
            _state.update { it.copy(cache = loaded,connectionPaused=loaded.preferences.connectionPaused,problem=loaded.lastSyncProblem.takeIf{it.isNotBlank()} ?: loaded.recoveryNotice.takeIf{it.isNotBlank()},contactAt=loaded.lastContactAt) }
            if (loaded.keys == null) {
                check(loaded.config==null && loaded.enrollment==null && loaded.snapshot==null && loaded.history==null && loaded.recoveryPending==null && loaded.outbox.isEmpty() && loaded.drafts.isEmpty() && loaded.conflicts.isEmpty()) { "Saved phone identity is missing. Original records are preserved; recover the identity or use Android app settings." }
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
        check(cache.config == null && cache.enrollment == null && cache.snapshot == null && cache.outbox.isEmpty() && cache.drafts.isEmpty() && cache.conflicts.isEmpty()&&cache.recoveryPending==null) { "This workspace already has a connection or retained data." }
        require(name.trim().encodeToByteArray().size in 1..80) { "Enter a short phone name." }
        if(invitation.version==2)invitation.tunnelConfiguration(checkNotNull(cache.keys))
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
            val reply = if(invitation.version==2) {
                val config=invitation.tunnelConfiguration(checkNotNull(cache.keys))
                if(!prepared) { transport.configure(config); currentAttempt(); prepared=true }
                transport.exchange("/api/enrollment"+path,null,body,config.text("server_key"))
            } else transport.bootstrap(invitation.url, invitation.ca, path, body)
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
                if(invitation.version==2) {
                    val expected=invitation.tunnelConfiguration(checkNotNull(cache.keys))
                    for(field in listOf("address","api","server_key","preshared_key"))check(configuration[field]==expected[field]) { "The approved tunnel identity changed." }
                }
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
        val result = if(current.invitation.version==2) {
            val config=current.invitation.tunnelConfiguration(checkNotNull(cache.keys))
            if(!prepared) { transport.configure(config);prepared=true }
            transport.exchange("/api/enrollment/cancel",null,proof,config.text("server_key"))
        } else transport.bootstrap(current.invitation.url, current.invitation.ca, "/cancel", proof)
        check(result.status in 200..299 || result.status == 410) { if(current.invitation.version==2)"Reconnect the private task tunnel to cancel, or revoke the invitation from your PC. Saved phone keys and the pending proof are retained." else "This is a legacy LAN attempt. Your PC administrator can revoke it; after expiry an unused attempt can be replaced while keeping phone keys." }
        transport.stop()
        prepared=false
        val keys = cache.keys ?: transport.keys()
        update { it.copy(enrollment = null, config = null, keys = keys, token = null) }
    }
    /** Only abandon an expired, never-activated attempt; keys and queued work stay intact. */
    suspend fun forgetExpiredEnrollment() = connection.withLock {
        val current=checkNotNull(cache.enrollment)
        check(current.invitation.expires<=Clock.System.now().epochSeconds && current.activationSecret==null && cache.config==null && cache.snapshot==null && cache.outbox.isEmpty() && cache.drafts.isEmpty() && cache.conflicts.isEmpty()&&cache.recoveryPending==null) { "Keep this pairing attempt; it may have activated or contain retained work." }
        transport.stop();prepared=false;pairingGeneration.update { it+1 }
        update { it.copy(enrollment=null,preferences=it.preferences.copy(connectionPaused=false)) }
        _state.update { it.copy(connectionPaused=false,error=null) }
    }
    suspend fun resetConnection(newKeys:Boolean=false) = connection.withLock {
        check(cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty() && cache.recoveryPending==null) { "Copy and resolve retained changes before enrolling again." }
        pauseConnection(); vault.clearImages()
        val keys = if(newKeys)transport.keys() else cache.keys ?: transport.keys()
        update { Cache(keys = keys, preferences = it.preferences.copy(connectionPaused=true)) }
        _state.update { it.copy(needsLogin = true, online = false, error = null) }
    }
    suspend fun discardRetainedChanges() { update { it.copy(outbox = emptyList(), conflicts = emptyList(), drafts = emptyMap()) } }
    suspend fun pair(raw: String) = connection.withLock {
        check(cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty() && cache.recoveryPending==null) { "Resolve retained changes before changing servers." }
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
    suspend fun softwareUpdate(path:String,body:JsonObject?=null):Reply=connection.withLock {
        require(path=="/api/device/update" || path.matches(Regex("/api/device/update/chunks/[a-f0-9]{64}/[0-9]{1,4}"))){"Only the paired software endpoint is allowed"}
        check(!state.value.needsLogin&&cache.token!=null){"Sign in before checking updates"}
        require(body==null||path=="/api/device/update"&&body.keys==setOf("installed_signer_sha256")&&body.text("installed_signer_sha256").matches(Regex("[a-f0-9]{64}"))){"Only the installed signing certificate may select the paired APK"}
        exchange(path,body,cache.token)
    }
    /** Export holds the connection fence so sync/account changes cannot remove
     * cached attachments midway through a local encrypted copy. */
    suspend fun <T> portableSnapshot(block:suspend(Cache)->T):T=connection.withLock { block(cache) }
    suspend fun transfer(action:String,body:JsonObject?=null,expectedBasis:Basis?=null,expectedActor:String?=null):JsonObject=connection.withLock {
        require(action in setOf("export","recovered","prepare","review","image","commit","cancel","restore","discard"))
        check((expectedBasis==null||cache.snapshot?.basis==expectedBasis)&&(expectedActor==null||cache.snapshot?.actor?.user_id==expectedActor)){"The workspace or account changed. Choose the file again; current data was kept."}
        check(!state.value.needsLogin&&cache.token!=null){"Sign in to the paired server before importing."}
        val result=request("/api/transfer/$action",body)
        if(action=="recovered")update {it.copy(recoveredImports=result["items"]!!.jsonArray.map{item->item.jsonObject})}
        result
    }
    suspend fun transferMedia(id:String):Reply=connection.withLock {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        check(!state.value.needsLogin&&cache.token!=null){"Sign in to retrieve this attachment."}
        exchange("/api/transfer/media/$id",null,cache.token)
    }
    suspend fun recovery(action:String,extra:JsonObject=obj()):JsonObject {
        require(action in setOf("status","versions","checkpoints","preview")){"Recovery mutations must use the durable confirmation path."}
        require(action in setOf("status","versions","checkpoints","preview"))
        val expected=checkNotNull(cache.snapshot){"Synchronize before opening recovery."}
        return connection.withLock {
            check(cache.snapshot?.basis==expected.basis&&cache.snapshot?.actor?.user_id==expected.actor.user_id){"The account or workspace changed. Open recovery again."}
            check(cache.supportsRecovery()){"Update the paired server to use saved versions and checkpoints. Your notes are kept."}
            val response=request("/api/recovery",JsonObject(extra+obj("action" to action.j(),"epoch" to expected.epoch.j(),"generation" to expected.generation.j())))
            check(response.text("epoch")==expected.epoch&&response.number("generation")==expected.generation){"The workspace changed. Sync before opening recovery again."}
            response
        }
    }
    suspend fun createCheckpoint(name:String):JsonObject {
        val status=recovery("status");val snap=checkNotNull(cache.snapshot)
        return executeRecovery(obj("action" to "checkpoint.create".j(),"epoch" to snap.epoch.j(),"generation" to snap.generation.j(),"operation_id" to newId().j(),"name" to name.trim().j(),"history_revision" to status.number("history_revision").j()))
    }
    suspend fun applyRecovery(preview:RecoveryPreview,confirmation:String,reviewed:Boolean):JsonObject=executeRecovery(preview.applyRequest(newId(),confirmation,reviewed))
    suspend fun deleteCheckpoint(entry:RecoveryVersion,page:RecoveryPage,confirmation:String):JsonObject {
        val snap=checkNotNull(cache.snapshot)
        check(snap.actor.role in setOf("owner","admin")){"An administrator must delete checkpoints."}
        return executeRecovery(entry.deleteRequest(page.epoch,page.generation,page.history_revision,newId(),confirmation))
    }
    private suspend fun executeRecovery(body:JsonObject):JsonObject {
        val expected=checkNotNull(cache.snapshot){"Synchronize first."}
        return connection.withLock {
            val current=checkNotNull(cache.snapshot)
            check(current.basis==expected.basis&&current.actor.user_id==expected.actor.user_id){"The account or workspace changed. Make a new recovery preview."}
            check(body.text("epoch")==current.epoch&&body.number("generation")==current.generation){"This recovery preview belongs to an earlier workspace. Review it again."}
            check(cache.supportsRecovery()&&current.actor.role!="viewer"){"Your current account cannot restore content."}
            check(cache.recoveryPending==null){"Review the previous recovery action before starting another."}
            check(body.text("action") in setOf("apply","checkpoint.create","checkpoint.delete")&&WireJson.encodeToString(body).length<=4096){"Invalid recovery request."}
            // Persist the exact idempotent request before any network write.
            update { it.copy(recoveryPending=RecoveryIntent(current.actor.user_id,current.actor.session_id,body)) }
            sendPendingRecovery()
        }
    }
    private fun pendingRecovery(requireSameSession:Boolean=true):RecoveryIntent {
        val pending=checkNotNull(cache.recoveryPending){"There is no recovery action awaiting confirmation."}
        val actor=checkNotNull(cache.snapshot).actor
        check(pending.actor==actor.user_id&&(!requireSameSession||pending.session==actor.session_id)&&pending.request.text("epoch")==cache.snapshot?.epoch){"This recovery action belongs to another account, session or server state. Review its status before dismissing it."}
        return pending
    }
    private suspend fun confirmRecoveryResult(result:JsonObject):JsonObject {
        check(result["ok"]?.jsonPrimitive?.booleanOrNull==true){"The server did not confirm recovery. Review its status."}
        try {update { it.copy(recoveryPending=null,recoveryNotice="Recovery completed on the server. Sync to download its current state.") }}
        catch(e:Exception){if(e is CancellationException)throw e;throw IllegalStateException("The server confirmed recovery, but its confirmation could not be saved locally. Review the pending action before starting another.",e)}
        try {pullSnapshot();update {it.copy(lastSyncedAt=Clock.System.now().epochSeconds,lastContactAt=Clock.System.now().epochSeconds,lastSyncProblem="",recoveryNotice="")};_state.update{it.copy(online=true,error=null,problem=null)}}catch(e:Exception){if(e is CancellationException)throw e;_state.update { it.copy(problem="Recovery completed; the local cache could not refresh. Sync again. Saved edits are kept.",error="Recovery completed; sync again to download the current shared state.") }}
        return result
    }
    private suspend fun sendPendingRecovery():JsonObject {
        val pending=pendingRecovery()
        val result=try {request("/api/recovery",pending.request)}catch(e:Exception){
            if(e is CancellationException)throw e
            try { update { c->c.copy(recoveryPending=c.recoveryPending?.copy(error=(e.message ?: "Result could not be confirmed").take(500))) } }catch(storage:Exception){if(storage is CancellationException)throw storage}
            throw IllegalStateException("Recovery was not confirmed. Its exact request is saved; check its status before trying a different restore. "+(e.message ?: ""),e)
        }
        return confirmRecoveryResult(result)
    }
    suspend fun checkRecoveryResult():JsonObject?=connection.withLock {
        val pending=pendingRecovery(false)
        val result=request("/api/recovery",obj("action" to "result".j(),"epoch" to pending.request.getValue("epoch"),"generation" to (cache.snapshot?.generation ?: 0).j(),"operation_id" to pending.request.getValue("operation_id")))
        if(result["known"]?.jsonPrimitive?.booleanOrNull==true)confirmRecoveryResult(checkNotNull(result["result"] as? JsonObject){"The recovery result was incomplete."}) else null
    }
    suspend fun retryRecovery():JsonObject=connection.withLock {sendPendingRecovery()}
    suspend fun dismissRecoveryStatus() {update { it.copy(recoveryPending=null) }}
    suspend fun reauthenticate(password:String)=connection.withLock {request("/api/reauth",obj("password" to password.j()));Unit}
    suspend fun history(filter:HistoryFilter,zone:kotlinx.datetime.TimeZone,anchor:Long?=null,before:Long?=null):HistoryPage {
        val expected=checkNotNull(cache.snapshot){"Sync the workspace before opening History."}
        return connection.withLock {
            check(cache.snapshot?.basis==expected.basis&&cache.snapshot?.actor?.user_id==expected.actor.user_id){"The account or workspace changed. Open History again."}
            check(!state.value.needsLogin&&cache.token!=null){"Sign in to load History."}
            check(cache.supportsHistory()){"Update the paired server to view History. Notes and saved work are kept."}
            try {
                val body=filter.body(expected.basis,zone,anchor,before)
                val response=request("/api/history",if(cache.compatibility?.features?.contains("history-items-v1")==true)JsonObject(body+("view" to "items".j())) else body)
                val page=SyncJson.decodeFromJsonElement<HistoryPage>(response)
                check(page.entries.size<=50&&page.entries.all { it.sequence>0&&it.sequence<=page.anchor&&it.changes.size<=64&&it.total_fields>=it.changes.size }&&page.entries.zipWithNext().all { (a,b)->a.sequence>b.sequence }&&(!page.has_more||page.next_before<(before?:page.anchor+1))){"The server returned invalid history pagination."}
                check(cache.snapshot?.basis==expected.basis&&cache.snapshot?.actor?.user_id==expected.actor.user_id){"The account or workspace changed while loading History."}
                update { it.copy(history=mergeHistory(it,page,Clock.System.now().epochSeconds)) }
                page
            } catch(e:Exception) {
                if(e is CancellationException)throw e
                if(e is ApiError&&e.status in setOf(401,403))report(e)
                throw e
            }
        }
    }
    private suspend fun request(path: String, body: JsonObject? = null, token: String? = cache.token): JsonObject {
        val wallStart=Clock.System.now().toEpochMilliseconds()
        val tick=kotlin.time.TimeSource.Monotonic.markNow()
        val r=exchange(path,body,token)
        val json = try { WireJson.parseToJsonElement(r.body).jsonObject } catch (_: Exception) { throw ApiError(r.status, "The server returned an invalid response.") }
        if(r.status>=400)throw ApiError(r.status,json.text("error","Request failed"))
        if(path=="/api/head")_state.update { it.copy(clockWarning=clockWarning(json,wallStart,Clock.System.now().toEpochMilliseconds(),tick.elapsedNow().inWholeMilliseconds)) }
        return json
    }
    suspend fun login(name: String, password: String) = connection.withLock {
        val r = request("/api/login", obj("name" to name.j(), "password" to password.j(), "device" to "Field Notes Android".j()), token = null)
        val actor = WireJson.decodeFromJsonElement<Actor>(r.getValue("actor"))
        val old = cache.snapshot?.actor?.user_id
        check(old == null || old == actor.user_id || (cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty()&&cache.recoveryPending==null)) { "Retained edits or a recovery action belong to another account. Review them before switching." }
        if (old != null && old != actor.user_id) vault.clearImages()
        update { it.copy(token = r.text("token"), enrollment = null, snapshot = if (old == actor.user_id) it.snapshot else null, people = if(old == actor.user_id)it.people else emptyList(),recoveredImports=if(old==actor.user_id)it.recoveredImports else emptyList()) }
        _state.update { it.copy(needsLogin = false, error = null) }
    }
    suspend fun removeInstallation() = connection.withLock {
        check(cache.outbox.isEmpty()&&cache.drafts.isEmpty()&&cache.conflicts.isEmpty()&&cache.recoveryPending==null) { "Sync or review retained changes before removing this installation." }
        val result=request("/api/device/revoke-self",obj())
        check(result["authorization_revoked"]==JsonPrimitive(true)){"The server did not confirm device revocation."}
        _state.update { it.copy(needsLogin=true) }
        transport.stop();prepared=false
        vault.clearImages()
        update { Cache(preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false)) }
    }
    suspend fun logout() = connection.withLock {
        check(cache.outbox.isEmpty() && cache.conflicts.isEmpty() && cache.drafts.isEmpty()&&cache.recoveryPending==null) { "Resolve queued changes, drafts, conflicts and pending recovery before signing out." }
        request("/api/logout", obj())
        vault.clearImages()
        update { it.copy(token = null, snapshot = null, people = emptyList(), reminded = emptySet(),recoveredImports=emptyList()) }
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
            val parent=if(action in setOf("comment.create","image.create"))projectRecords(c).firstOrNull { it.record.id==data.text("task_id") }?.record else null
            val protectedData=if(parent!=null&&c.supportsRecovery())JsonObject(data+("parent_incarnation" to parent.data.number("recovery_incarnation").j()))else data
            val command = Command(newId(), stamp.epoch, stamp.generation, action, target, revision, protectedData)
            val predecessor = c.outbox.lastOrNull { it.command.target == target }?.command?.operation_id
            val parentId=if(action.startsWith("task.")||action.startsWith("list."))data.text("project_id") else data.text("task_id")
            val parentCreate=if(action.endsWith(".create")&&parentId.isNotBlank())c.outbox.lastOrNull {it.command.target==parentId&&it.command.action.endsWith(".create")}?.command?.operation_id else null
            c.copy(outbox = c.outbox + Pending(command, predecessor,parentCreation=parentCreate))
        }
    }
    /** Explicit deletion discards this editor's unsent draft only after the
     * removal command is durably queued. Earlier queued saves keep their order. */
    suspend fun removeTask(item:Record,basis:Basis) {
        update { c ->
            val snapshot=c.snapshot ?: error("Synchronize first.")
            check(snapshot.basis==basis){"The workspace changed. Reopen this item before deleting it."}
            check(snapshot.actor.role!="viewer"){"Your role is read-only."}
            check(item.kind=="task"){"Choose a task or list item."}
            check(c.outbox.size+c.conflicts.size<500){"Resolve pending changes before deleting more items."}
            val predecessor=c.outbox.lastOrNull{it.command.target==item.id}?.command?.operation_id
            val command=Command(newId(),basis.epoch,basis.generation,"task.delete",item.id,item.revision,obj())
            c.copy(outbox=c.outbox+Pending(command,predecessor),drafts=c.drafts-item.id)
        }
    }
    /** Membership-only change, preserving unsaved drafts and server revision fences. */
    suspend fun moveTask(target:String, destination:String, revision:Long, basis:Basis) {
        update { c ->
            val snap=c.snapshot ?: error("Synchronize first.")
            check(snap.actor.role!="viewer") { "Your role is read-only." }
            check(snap.basis==basis) { "The workspace changed. Reopen the project." }
            check(target !in c.drafts) { "Save or discard this task's draft before moving it." }
            check(c.outbox.none { it.command.target==target }) { "Synchronize this task's pending changes before moving it." }
            val task=snap.records.firstOrNull { it.id==target&&it.kind=="task" } ?: error("Task is unavailable.")
            check(task.revision==revision) { "This task changed. Reopen the task picker." }
            check(destination.isEmpty() || snap.records.any { it.id==destination&&it.kind in setOf("list","project") }) { "Project or list is unavailable." }
            check(c.outbox.size+c.conflicts.size<500) { "Resolve pending changes before moving tasks." }
            val command=Command(newId(),basis.epoch,basis.generation,"task.update",target,revision,obj("project_id" to destination.j()))
            c.copy(outbox=c.outbox+Pending(command))
        }
    }
    /** Freeze the exact checked rows in one durable local write. Existing per-task
     * dependency/revision handling protects a concurrent remote reopen or edit. */
    suspend fun clearChecked(listId:String) {
        update { c ->
            val snap=c.snapshot ?: error("Synchronize first.")
            check(snap.actor.role!="viewer") { "Your role is read-only." }
            check(projectRecords(c).any { it.record.id==listId&&it.record.kind=="list" }) { "List is unavailable." }
            val checked=projectRecords(c).filter { it.record.kind=="task"&&it.record.data.text("project_id")==listId&&it.record.data.text("status")=="done" }
            check(c.outbox.size+c.conflicts.size+checked.size<=500) { "Synchronize pending work before clearing this many items." }
            val commands=checked.map { row -> Pending(Command(newId(),snap.epoch,snap.generation,"task.delete",row.record.id,row.record.revision,obj()),c.outbox.lastOrNull { it.command.target==row.record.id }?.command?.operation_id) }
            c.copy(outbox=c.outbox+commands)
        }
    }
    suspend fun clearCheckedItems(items:List<Record>,basis:Basis) {
        changeCheckedItems(items,basis,"task.delete")
    }
    suspend fun reuseCheckedItems(items:List<Record>,basis:Basis) {
        changeCheckedItems(items,basis,"task.reopen")
    }
    private suspend fun changeCheckedItems(items:List<Record>,basis:Basis,action:String) {
        update { c ->
            val snap=c.snapshot ?: error("Synchronize first.")
            check(snap.basis==basis){"The workspace changed. Reopen this list before changing checked items."}
            check(snap.actor.role!="viewer"){"Your role is read-only."}
            val rows=projectRecords(c).map { it.record };val ids=items.map { it.id }.toSet()
            check(items.isNotEmpty()&&ids.size==items.size&&items.all { r->r.kind=="task"&&r.data.text("status")=="done"&&rows.any{it.id==r.data.text("project_id")&&it.kind=="list"} }){"Choose checked list items."}
            check(c.outbox.size+c.conflicts.size+items.size<=500){"Synchronize pending work before changing this many items."}
            val commands=items.map { row->Pending(Command(newId(),snap.epoch,snap.generation,action,row.id,row.revision,obj()),c.outbox.lastOrNull { it.command.target==row.id }?.command?.operation_id) }
            c.copy(outbox=c.outbox+commands)
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
    private fun report(e: Exception) { if (e is CancellationException) throw e; _state.update { it.copy(error = e.message ?: "Operation failed.",problem=e.message ?: "Operation failed.") } }
    fun clearError() { _state.update { it.copy(error = null) } }
    fun launch(block: suspend () -> Unit) = scope.launch { try { block() } catch (e: Exception) { report(e) } }
    /** The queue is already durable. Its delivery belongs to the workspace,
     * so closing an editor cannot cancel the sync that follows a successful save. */
    fun requestSync() = launch { syncNow(waitForConnection=true) }

    private suspend fun rememberCompatibility(head:JsonObject) {
        val next=ServerCompatibility.read(head,Clock.System.now().epochSeconds)
        val old=cache.compatibility
        // Head polling must not rewrite the encrypted outbox every 15 seconds.
        if(old==null || old.copy(checkedAt=next.checkedAt)!=next || next.checkedAt-old.checkedAt>=3600)update { it.copy(compatibility=next) }
    }
    private suspend fun inspectCompatibility() {
        val head=try { request("/api/head") }catch(e:ApiError) {
            if(e.status!=404)throw e
            rememberCompatibility(obj());return
        }
        try { rememberCompatibility(head) }
        catch(e:IllegalArgumentException) {
            val old=cache.compatibility
            update { it.copy(compatibility=(old ?: ServerCompatibility()).copy(checkedAt=Clock.System.now().epochSeconds,warning="Server version report needs review. Core sync still verifies its data protocol; new options stay disabled." ,features=emptySet())) }
        }
    }
    suspend fun observeCompatibility(reply:JsonObject)=rememberCompatibility(reply)

    private suspend fun pullSnapshot():Snapshot {
        val fresh = SyncJson.decodeFromJsonElement<Snapshot>(request("/api/snapshot"))
        check(fresh.protocol == 1) { "Update this client before using the server's new protocol." }
        val directoryReply = try { request("/api/people") } catch(e:ApiError) { if(e.status==404)obj("people" to JsonArray(emptyList())) else throw e }
        val directory=SyncJson.decodeFromJsonElement<List<HouseholdPerson>>(directoryReply.getValue("people"))
        update { c ->
            val ids = fresh.records.map { it.id }.toSet()
            val orphaned = c.drafts.values.filter { d ->
                d.epoch != fresh.epoch || d.generation != fresh.generation ||
                    (d.target !in ids && c.outbox.none { it.command.target == d.target && it.command.action.endsWith(".create") && it.command.epoch == fresh.epoch && it.command.generation == fresh.generation })
            }
            c.copy(snapshot = fresh, people = directory, recoveredImports=if(c.snapshot?.basis==fresh.basis)c.recoveredImports else emptyList(), directoryRevision=directoryReply.number("revision"), drafts = c.drafts - orphaned.map { it.target }.toSet(), conflicts = c.conflicts + orphaned.map { d ->
                Conflict(Command(newId(), d.epoch, d.generation, d.action, d.target, d.revision, d.data), "The server item was removed or restored. Copy this draft to a new item if needed.")
            })
        }
        _state.update{it.copy(refreshPending=false)}
        return fresh
    }

    suspend fun sync() = syncNow(waitForConnection=false)
    private suspend fun syncNow(waitForConnection:Boolean) {
        if (waitForConnection) connection.lock() else if (!connection.tryLock()) return
        if (cache.config == null || cache.token == null || !_state.value.ready) { connection.unlock(); return }
        _state.update { it.copy(syncing = true) }
        try {
            if(cache.compatibility==null || Clock.System.now().epochSeconds-cache.compatibility!!.checkedAt>3600)inspectCompatibility()
            check(cache.compatibility?.blocked!=true) { cache.compatibility!!.message }
            val fresh = pullSnapshot()
            while (true) {
                val item = cache.outbox.firstOrNull() ?: break
                val c = item.command
                val fence = when {
                    c.epoch != fresh.epoch || c.generation != fresh.generation -> "Workspace reset or restored. The old draft was not uploaded."
                    (c.data["reminder_options"] as? JsonObject)?.number("playback_version")==2L&&!cache.supportsReminderAudio()->"This saved edit needs the newer server’s sound controls. Update the server; the complete edit is kept."
                    c.data.containsKey("reminder_options")&&!cache.supportsRemindersV2()->"This saved edit needs the newer server's reminder controls. Update the server or review the retained edit; nothing was discarded."
                    c.data.keys.any { it in setOf("recurrence","category","store","on_radar","remind_at","notify_additions","item_reminders") }&&!cache.supportsPlannerV3()->"This saved edit uses newer planner fields. Update the paired server; the complete edit is kept in Retained changes."
                    (c.data.containsKey("items_are_tasks")||c.action.startsWith("list.")&&c.data.containsKey("project_id")||c.action.startsWith("project.")&&c.data.keys.any{it in setOf("status","due","assignee")})&&!cache.supportsProjects()->"This saved edit needs the newer project/work-list server. Update the server; its complete content is kept."
                    (c.data["reminders"] as? JsonObject)?.number("timing_version")==2L&&!cache.supportsReminderSchedule()->"This saved edit needs the newer server’s day-and-time reminders. Update the server; the complete edit is kept."
                    (c.data["reminders"] as? JsonObject)?.containsKey("timing_version")==true&&!cache.supportsReminderTiming()->"This saved edit needs the newer server’s custom reminder timing. Update the server; the complete edit is kept."
                    c.data.keys.any{it in setOf("note_type","ingredients","instructions","location")}&&!cache.supportsPlannerDetails()->"This saved edit needs newer recipe/location fields. Update the server; the complete edit is kept."
                    c.data.containsKey("item_category")&&!cache.supportsItemContexts()->"This saved item category needs a newer server. Update the server; the complete edit is kept."
                    c.action=="list.unbundle"&&cache.compatibility?.features?.contains("list-unbundle-v1")!=true->"This queued list release requires the updated server. All linked work is retained for review."
                    (c.data.text("category").startsWith("custom:")||c.data["category_override"]==JsonPrimitive(true))&&!cache.supportsHouseholdCategories()->"This saved custom category edit needs the updated server; the whole edit is kept for review."
                    item.parentCreation!=null->"The parent collection could not be created. Review its retained change before this child item."
                    item.dependsOn != null -> "An earlier queued edit was rejected. Review this dependent draft."
                    else -> null
                }
                if (fence != null) { reject(c.operation_id, fence); continue }
                try {
                    update { it.copy(outbox = it.outbox.map { p -> if (p.command.operation_id == c.operation_id) p.copy(attempted = true) else p }) }
                    val result = request("/api/commands", WireJson.encodeToJsonElement(c).jsonObject)
                    val record = SyncJson.decodeFromJsonElement<Record>(result.getValue("record"))
                    update { state ->
                        val snap = state.snapshot!!
                        val deleted = record.data["deleted"] == JsonPrimitive(true)
                        val rows = snap.records.filterNot { it.id == record.id || (deleted && it.data.text("task_id") == record.id) } + if (deleted) emptyList() else listOf(record)
                        state.copy(snapshot = snap.copy(records = rows, sequence = result.number("sequence")), drafts = state.drafts.mapValues { (_, draft) -> if (draft.dependsOn == c.operation_id) draft.copy(revision = record.revision, dependsOn = null) else draft }, outbox = state.outbox.filterNot { it.command.operation_id == c.operation_id }.map { next ->
                            if (next.dependsOn == c.operation_id) next.copy(dependsOn = null, command = next.command.copy(expected_revision = record.revision)) else if(next.parentCreation==c.operation_id)next.copy(parentCreation=null) else next
                        })
                    }
                } catch (e: ApiError) {
                    if (e.status == 401 || e.status == 429 || e.status >= 500) throw e
                    reject(c.operation_id, e.message)
                }
            }
            val final = SyncJson.decodeFromJsonElement<Snapshot>(request("/api/snapshot"))
            update { it.copy(snapshot = final,lastSyncedAt=Clock.System.now().epochSeconds,lastContactAt=Clock.System.now().epochSeconds,lastSyncProblem="",recoveryNotice="") }
            _state.update { it.copy(online = true, error = null,problem=null,contactAt=Clock.System.now().epochSeconds,refreshPending=false) }
        } catch (e: Exception) {
            report(e); _state.update { it.copy(online = false) };runCatching{update { it.copy(lastSyncProblem=(e.message ?: "Sync failed").take(600)) }}
        } finally { _state.update { it.copy(syncing = false) }; connection.unlock() }
    }
    /** Cheap background checks keep the private connection alive without
     * downloading and re-encrypting an unchanged workspace every few seconds. */
    suspend fun poll(): Boolean = pollNow(waitForConnection=false)
    suspend fun refreshWhileVisible():Boolean = pollNow(waitForConnection=true)
    private suspend fun pollNow(waitForConnection:Boolean): Boolean {
        if(state.value.connectionPaused)return false
        if(waitForConnection)connection.lock()else if(!connection.tryLock())return false
        var changed=false
        try {
            if(cache.token==null||cache.config==null||!state.value.ready||state.value.connectionPaused)return false
            val head=request("/api/head")
            rememberCompatibility(head)
            check(cache.compatibility?.blocked!=true) { cache.compatibility!!.message }
            val remoteChanged=cache.snapshot?.let { it.epoch!=head.text("epoch")||it.generation!=head.number("generation")||it.sequence!=head.number("sequence")||it.history_revision!=head.number("history_revision")||cache.directoryRevision!=head.number("directory_revision") }?:true
            changed=remoteChanged
            if(cache.outbox.isNotEmpty())changed=true
            val now=Clock.System.now().epochSeconds
            if(now-cache.lastContactAt>=300||cache.lastSyncProblem.isNotBlank())update { it.copy(lastContactAt=now,lastSyncProblem="") }
            _state.update { it.copy(online=true,error=null,problem=null,contactAt=now,refreshPending=remoteChanged) }
        }catch(e:Exception){report(e);_state.update { it.copy(online=false) };runCatching{update { it.copy(lastSyncProblem=(e.message ?: "Connection failed").take(600)) }}}
        finally{connection.unlock()}
        if(changed)syncNow(waitForConnection=true)
        return changed&&state.value.online
    }
    /** Reopen only our userspace transport after Android changes its default
     * network. Reuse the existing identity, pin, session and outbox. */
    suspend fun networkChanged(refresh:Boolean=true) {
        connection.withLock {
            if(!state.value.ready||state.value.connectionPaused||cache.config==null||cache.token==null)return
            try {transport.stop()} finally {prepared=false}
            _state.update{it.copy(online=false,contactAt=0)}
        }
        if(refresh)refreshWhileVisible()
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
                check(current==null||!old.action.endsWith(".create")){"This item already exists. Compare its current copy before discarding the retained create; it was not duplicated."}
                val parent=if(old.action in setOf("comment.create","image.create"))snap.records.firstOrNull {it.id==old.data.text("task_id")&&it.kind=="task"} ?: error("The parent task is unavailable. Restore it or copy this retained work to another task.") else null
                // Only an explicit Reapply may adopt the current parent fence.
                // Ordinary synchronization always preserves the original one.
                val reviewedData=if(parent!=null)JsonObject(old.data-"parent_incarnation"+(if(c.supportsRecovery())mapOf("parent_incarnation" to parent.data.number("recovery_incarnation").j())else emptyMap()))else old.data
                c.copy(conflicts = c.conflicts.filterNot { it.command.operation_id == id }, outbox = c.outbox + Pending(old.copy(operation_id = newId(), epoch = snap.epoch, generation = snap.generation, expected_revision = current?.revision,data=reviewedData)))
            }
        }
    }
    suspend fun preferences(preferences: Preferences) { update { it.copy(preferences = preferences.copy(connectionPaused=it.preferences.connectionPaused)) } }
    suspend fun preferences(change:(Preferences)->Preferences) { update { it.copy(preferences=change(it.preferences).copy(connectionPaused=it.preferences.connectionPaused)) } }
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
            if (c.action=="list.unbundle") {
                for((key,row) in rows.toMap()) {
                    if(row.record.kind=="task"&&row.record.data.text("project_id")==c.target)
                        rows[key]=VisibleRecord(row.record.copy(data=JsonObject(row.record.data+("project_id" to "".j()))),true)
                }
                rows[c.target]=VisibleRecord(current.record,true)
                continue
            }
            if (c.action.endsWith(".delete")) { rows.remove(c.target); rows.entries.removeAll { it.value.record.data.text("task_id") == c.target }; continue }
            val change = when (c.action) {
                "task.update", "event.update", "note.update", "project.update", "list.update" -> c.data
                "task.start" -> obj("status" to "in_progress".j(), "claimant" to (cache.snapshot?.actor?.user_id ?: "").j())
                "task.complete" -> obj("status" to "done".j(), "claimant" to JsonNull)
                "task.release", "task.reopen" -> obj("status" to "todo".j(), "claimant" to JsonNull)
                else -> obj()
            }
            val base=if(change.containsKey("reminders"))JsonObject(d-setOf("reminder_rules","reminder_timing_rules","reminder_schedule_rules"))else d
            rows[c.target] = VisibleRecord(current.record.copy(data = JsonObject(base + change)), true)
        }
    }
    return rows.values.toList()
}
