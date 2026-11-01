package org.fieldnotes.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val WireJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }
fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
fun String.j() = JsonPrimitive(this)
fun Int.j() = JsonPrimitive(this)
fun Long.j() = JsonPrimitive(this)
fun JsonObject.text(key: String, default: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: default
fun JsonObject.number(key: String, default: Long = 0) = (get(key) as? JsonPrimitive)?.longOrNull ?: default

@Serializable data class Actor(val user_id: String, val name: String, val role: String, val session_id: String, val elevated: Boolean = false)
@Serializable data class HouseholdPerson(val id:String,val name:String,val can_assign:Boolean)
@Serializable data class Record(val id: String, val kind: String, val revision: Long, val data: JsonObject)
@Serializable data class Snapshot(val protocol: Int, val epoch: String, val generation: Long, val sequence: Long, val records: List<Record>, val actor: Actor)
@Serializable data class Command(val operation_id: String, val epoch: String, val generation: Long, val action: String, val target: String, val expected_revision: Long?, val data: JsonObject)
@Serializable data class Basis(val epoch: String, val generation: Long)
val Snapshot.basis get() = Basis(epoch, generation)
@Serializable data class Pending(val command: Command, val dependsOn: String? = null, val attempted: Boolean = false)
@Serializable data class Conflict(val command: Command, val reason: String)
@Serializable data class Draft(val target: String, val epoch: String, val generation: Long, val revision: Long, val data: JsonObject, val dependsOn: String? = null, val action: String = "task.update")
@Serializable data class Preferences(val backgroundSync: Boolean = true, val reminders: Boolean = true, val widgetTitles: Boolean = false, val liveUpdates: Boolean = true, val connectionPaused: Boolean = false)
@Serializable data class Cache(
    val keys: JsonObject? = null, val config: JsonObject? = null, val token: String? = null,
    val snapshot: Snapshot? = null, val people: List<HouseholdPerson> = emptyList(), val directoryRevision:Long = 0, val lastSyncedAt:Long = 0, val outbox: List<Pending> = emptyList(),
    val conflicts: List<Conflict> = emptyList(), val drafts: Map<String, Draft> = emptyMap(),
    val enrollment: Enrollment? = null, val preferences: Preferences = Preferences(), val reminded: Set<String> = emptySet(),
)
data class WorkspaceState(val cache: Cache = Cache(), val ready: Boolean = false, val syncing: Boolean = false, val online: Boolean = false, val needsLogin: Boolean = false, val error: String? = null, val connectionPaused:Boolean = false)
data class Reply(val status: Int, val body: String = "", val binary: String? = null)
interface Transport {
    suspend fun keys(): JsonObject
    suspend fun configure(configuration: JsonObject)
    suspend fun exchange(path: String, token: String?, body: JsonObject? = null, serverKey: String? = null): Reply
    suspend fun bootstrap(url: String, ca: String, path: String, body: JsonObject): Reply = error("Enrollment transport unavailable.")
    suspend fun stop()
}
interface Vault {
    suspend fun load(): Cache
    suspend fun save(cache: Cache)
    suspend fun clearImages()
}
class ApiError(val status: Int, override val message: String) : Exception(message)
