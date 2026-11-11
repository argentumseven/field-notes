package org.fieldnotes.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val CURRENT_CLIENT_CODE=25
const val REMINDER_OPTIONS_FEATURE="reminder-options-v1"
@Serializable data class ServerCompatibility(val version:String="legacy",val protocolMin:Int=1,val protocolMax:Int=1,val minimumClient:Int=1,val recommendedClient:Int=1,val features:Set<String> = emptySet(),val checkedAt:Long=0,val warning:String="") {
    val blocked get()=protocolMin>1||protocolMax<1||minimumClient>CURRENT_CLIENT_CODE
    val message get()=when {
        blocked->"This server requires a newer app. Saved notes and queued edits are kept. Open App updates."
        warning.isNotBlank()->warning
        recommendedClient>CURRENT_CLIENT_CODE->"A newer app is recommended. Core sync remains compatible; new features need the update."
        version=="legacy"->"Connected to an older server. Core sync works; update the server for recipient controls and long alarms."
        else->"Core protocol compatible · server $version"
    }
    companion object {
        fun read(head:JsonObject,at:Long):ServerCompatibility {
            val report=head["compatibility"] as? JsonObject ?: return ServerCompatibility(checkedAt=at)
            val value=ServerCompatibility(report.text("server_version"),report.number("protocol_min").toInt(),report.number("protocol_max").toInt(),report.number("minimum_client_code",1).toInt(),report.number("recommended_client_code",1).toInt(),(report["features"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet(),at)
            require(value.version.length in 1..40 && value.protocolMin>=1 && value.protocolMax>=value.protocolMin && value.minimumClient>=1 && value.recommendedClient>=value.minimumClient && value.features.size<=64) { "The server's compatibility report needs review. Core data is kept." }
            return value
        }
    }
}
fun Cache.supportsRemindersV2()=compatibility?.let { !it.blocked&&REMINDER_OPTIONS_FEATURE in it.features }==true
