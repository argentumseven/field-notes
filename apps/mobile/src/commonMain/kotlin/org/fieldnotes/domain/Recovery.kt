package org.fieldnotes.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val RECOVERY_FEATURE="content-recovery-v1"
const val TRASH_FEATURE="content-trash-v1"
fun Cache.supportsTrash()=compatibility?.let{!it.blocked&&TRASH_FEATURE in it.features}==true
const val HIERARCHY_RECOVERY_FEATURE="hierarchy-recovery-v1"
fun Cache.supportsHierarchyRecovery()=compatibility?.let{!it.blocked&&HIERARCHY_RECOVERY_FEATURE in it.features}==true
fun Cache.supportsRecovery()=compatibility?.let { !it.blocked&&RECOVERY_FEATURE in it.features }==true
@Serializable data class RecoveryIntent(val actor:String,val session:String,val request:JsonObject,val error:String="")
@Serializable data class RecoveryVersion(val id:String,val index:Long,val record_revision:Long=0,val at:Long,val actor_name:String,val action:String,val title:String,val kind:String?=null,val deleted:Boolean=false,val records:Int=0,val images:Int=0,val can_restore:Boolean=false,val can_delete:Boolean=false,val problem:String="")
@Serializable data class RecoveryPage(val epoch:String,val generation:Long,val lineage:String,val entries:List<RecoveryVersion>,val more:Boolean,val next_before:Long,val history_revision:Long)
@Serializable data class RecoveryField(val field:String,val label:String="",val before:JsonElement=JsonNull,val after:JsonElement=JsonNull,val truncated:Boolean=false)
@Serializable data class RecoveryChange(val id:String,val kind:String,val title:String,val action:String,val changes:List<RecoveryField> = emptyList(),val total_fields:Int=changes.size)
@Serializable data class ItemStateField(val field:String,val label:String,val value:JsonElement)
@Serializable data class ItemStateChild(val kind:String,val title:String,val body:JsonElement=JsonNull,val author:JsonElement=JsonNull)
@Serializable data class ItemStateView(val title:String,val kind:String,val fields:List<ItemStateField>,val comments:Int=0,val images:Int=0,val children:List<ItemStateChild> = emptyList(),val children_limited:Boolean=false)
@Serializable data class ItemStatePair(val current:ItemStateView?=null,val saved:ItemStateView?=null)
@Serializable data class RecoveryHierarchy(val mode:String,val linked_existing:Int=0,val missing:Int=0,val respawned:Int=0,val kept_deleted:Int=0)
@Serializable data class RecoveryPreview(val preview_id:String,val expires_at:Long,val epoch:String,val generation:Long,val source:String,val source_index:Long,val title:String,val checkpoint:Boolean,val reauth_required:Boolean=false,val change_count:Int,val restored:Int=0,val removed:Int=0,val changed:Int=0,val changes:List<RecoveryChange>,val preview_limited:Boolean=false,val saves_current_state:Boolean=true,val clears_claims:Boolean=true,val fences_offline_writes:Boolean=false,val item_state:ItemStatePair?=null,val confirmation_phrase:String="",val hierarchy:RecoveryHierarchy?=null)
fun recoveryVersionLabel(value:RecoveryVersion)=if(value.problem.isNotBlank())"Unreadable saved state" else if(value.action=="baseline.before_change")"Previous state captured" else if(value.action=="collection.delete.before")"Before deleting collection" else if(value.deleted)"Deleted state" else when {value.action.startsWith("restore.")->"Restored state";value.action=="checkpoint.before_restore"->"Automatically saved before restore";value.action=="checkpoint.create"->"Content checkpoint";else->value.action.substringAfter('.').replaceFirstChar { it.uppercase() }}
fun RecoveryPreview.applyRequest(operation:String,confirmation:String,reviewed:Boolean):JsonObject {
    check(confirmation_phrase.isNotBlank()&&confirmation==confirmation_phrase&&reviewed){"Review the changes and type the exact restore confirmation phrase."}
    return obj("action" to "apply".j(),"epoch" to epoch.j(),"generation" to generation.j(),"preview_id" to preview_id.j(),"operation_id" to operation.j(),"confirmation" to confirmation.j(),"reviewed" to JsonPrimitive(reviewed))
}
fun RecoveryVersion.deleteRequest(epoch:String,generation:Long,revision:Long,operation:String,confirmation:String):JsonObject {
    check(can_delete&&confirmation=="DELETE CHECKPOINT #$index"){"Review the checkpoint and type the exact delete confirmation phrase."}
    return obj("action" to "checkpoint.delete".j(),"epoch" to epoch.j(),"generation" to generation.j(),"source" to id.j(),"operation_id" to operation.j(),"history_revision" to revision.j(),"confirmation" to confirmation.j())
}

fun itemStateValue(field:String,value:JsonElement,zone:kotlinx.datetime.TimeZone,use24Hour:Boolean=false):String {
    if(field=="recurrence") {
        val rule=runCatching {WireJson.decodeFromJsonElement<EventRepeat>(value)}.getOrNull()
        if(rule!=null)return listOf(repeatLabel(rule),rule.zone,
            if(rule.dates.isNotEmpty())"Dates: "+rule.dates.joinToString(", "){org.fieldnotes.domain.friendlyDate(kotlinx.datetime.LocalDate.parse(it))} else "",
            if(rule.excluded.isNotEmpty())"Skipped: "+rule.excluded.joinToString(", "){org.fieldnotes.domain.friendlyDate(kotlinx.datetime.LocalDate.parse(it))} else "").filter {it.isNotBlank()}.joinToString(" · ")
    }
    if(value is JsonArray&&field in setOf("weekdays","days"))return historyValue(HistoryChange(field,field),value,zone,use24Hour)
    if(value is JsonArray)return value.joinToString("; "){itemStateValue(field,it,zone,use24Hour)}
    if(value is JsonObject)return value.filterKeys {it!="id"}.entries.joinToString(" · "){(key,v)->key.replace('_',' ')+": "+itemStateValue(key,v,zone,use24Hour)}
    return historyValue(HistoryChange(field,field),value,zone,use24Hour)
}
