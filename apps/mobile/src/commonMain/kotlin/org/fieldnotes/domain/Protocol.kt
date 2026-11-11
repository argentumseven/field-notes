package org.fieldnotes.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val WireJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }
// Additive server metadata must not break an older app's core sync. Enrollment
// and command serialization keep the strict parser above.
val SyncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
fun String.j() = JsonPrimitive(this)
fun Int.j() = JsonPrimitive(this)
fun Long.j() = JsonPrimitive(this)
fun JsonObject.text(key: String, default: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: default
fun JsonObject.number(key: String, default: Long = 0) = (get(key) as? JsonPrimitive)?.longOrNull ?: default

@Serializable data class Actor(val user_id: String, val name: String, val role: String, val session_id: String, val elevated: Boolean = false)
@Serializable data class HouseholdPerson(val id:String,val name:String,val can_assign:Boolean)
@Serializable data class Record(val id: String, val kind: String, val revision: Long, val data: JsonObject)
@Serializable data class HouseholdCategory(val id:String,val name:String,val archived:Boolean=false,val revision:Long=1)
@Serializable data class Snapshot(val protocol: Int, val epoch: String, val generation: Long, val sequence: Long, val records: List<Record>, val actor: Actor,val history_revision:Long=0,val categories:List<HouseholdCategory> = emptyList())
@Serializable data class Command(val operation_id: String, val epoch: String, val generation: Long, val action: String, val target: String, val expected_revision: Long?, val data: JsonObject)
@Serializable data class Basis(val epoch: String, val generation: Long)
val Snapshot.basis get() = Basis(epoch, generation)
@Serializable data class Pending(val command: Command, val dependsOn: String? = null, val attempted: Boolean = false,val parentCreation:String?=null)
@Serializable data class Conflict(val command: Command, val reason: String)
@Serializable data class Draft(val target: String, val epoch: String, val generation: Long, val revision: Long, val data: JsonObject, val dependsOn: String? = null, val action: String = "task.update")
@Serializable data class Preferences(val backgroundSync: Boolean = true, val reminders: Boolean = true, val widgetTitles: Boolean = true, val liveUpdates: Boolean = true, val connectionPaused: Boolean = false, val autoRefresh:Boolean=true, val theme:String="system", val notificationTitles:Boolean=true,
    val appLock:Boolean=false,val lockMinutes:Int=5,val notificationOnLockScreen:Boolean=false,val widgetOnLockScreen:Boolean=false,val widgetHousehold:Boolean=false,val allowAlarmVolumeOverride:Boolean=false,val autoDownloadUpdates:Boolean=true,val visibilityChoiceSeen:Boolean=false,val sharedAdditions:Boolean=true,val federalHolidays:Boolean=false,val allowNotificationVolumeOverride:Boolean=false)
@Serializable data class Cache(
    val keys: JsonObject? = null, val config: JsonObject? = null, val token: String? = null,
    val snapshot: Snapshot? = null, val people: List<HouseholdPerson> = emptyList(), val directoryRevision:Long = 0, val lastSyncedAt:Long = 0, val outbox: List<Pending> = emptyList(),
    val conflicts: List<Conflict> = emptyList(), val drafts: Map<String, Draft> = emptyMap(),
    val enrollment: Enrollment? = null, val preferences: Preferences = Preferences(), val reminded: Set<String> = emptySet(),
    val compatibility:ServerCompatibility?=null,
    val lastContactAt:Long=0,val lastSyncProblem:String="",
    val recoveredImports:List<JsonObject> = emptyList(),
    val history:HistoryCache? = null,
    val recoveryPending:RecoveryIntent? = null,
    val recoveryNotice:String = "",
)
data class WorkspaceState(val cache: Cache = Cache(), val ready: Boolean = false, val syncing: Boolean = false, val online: Boolean = false, val needsLogin: Boolean = false, val error: String? = null, val connectionPaused:Boolean = false,val problem:String?=null,val contactAt:Long=0,val refreshPending:Boolean=false,val clockWarning:String?=null)
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
