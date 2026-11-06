package org.fieldnotes.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val RECOVERY_FEATURE="content-recovery-v1"
fun Cache.supportsRecovery()=compatibility?.let { !it.blocked&&RECOVERY_FEATURE in it.features }==true
@Serializable data class RecoveryIntent(val actor:String,val session:String,val request:JsonObject,val error:String="")
@Serializable data class RecoveryVersion(val id:String,val index:Long,val record_revision:Long=0,val at:Long,val actor_name:String,val action:String,val title:String,val kind:String?=null,val deleted:Boolean=false,val records:Int=0,val images:Int=0,val can_restore:Boolean=false,val problem:String="")
@Serializable data class RecoveryPage(val epoch:String,val generation:Long,val lineage:String,val entries:List<RecoveryVersion>,val more:Boolean,val next_before:Long,val history_revision:Long)
@Serializable data class RecoveryField(val field:String,val label:String="",val before:JsonElement=JsonNull,val after:JsonElement=JsonNull,val truncated:Boolean=false)
@Serializable data class RecoveryChange(val id:String,val kind:String,val title:String,val action:String,val changes:List<RecoveryField> = emptyList(),val total_fields:Int=changes.size)
@Serializable data class RecoveryPreview(val preview_id:String,val expires_at:Long,val epoch:String,val generation:Long,val source:String,val source_index:Long,val title:String,val checkpoint:Boolean,val reauth_required:Boolean=false,val change_count:Int,val restored:Int=0,val removed:Int=0,val changed:Int=0,val changes:List<RecoveryChange>,val preview_limited:Boolean=false,val saves_current_state:Boolean=true,val clears_claims:Boolean=true,val fences_offline_writes:Boolean=false)
fun recoveryVersionLabel(value:RecoveryVersion)=if(value.problem.isNotBlank())"Unreadable saved state" else if(value.action=="baseline.before_change")"Previous state captured" else if(value.deleted)"Deleted state" else when {value.action.startsWith("restore.")->"Restored state";value.action=="checkpoint.before_restore"->"Automatically saved before restore";value.action=="checkpoint.create"->"Content checkpoint";else->value.action.substringAfter('.').replaceFirstChar { it.uppercase() }}
fun RecoveryPreview.applyRequest(operation:String)=obj("action" to "apply".j(),"epoch" to epoch.j(),"generation" to generation.j(),"preview_id" to preview_id.j(),"operation_id" to operation.j(),"confirmation" to (if(checkpoint)"RESTORE CONTENT" else "RESTORE ITEM").j())
