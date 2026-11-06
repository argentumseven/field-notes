package org.fieldnotes.domain

import kotlinx.datetime.Clock
import kotlinx.serialization.json.*

/** Content only. This explicit allowlist never serializes Cache, connection
 * configuration, credentials or encryption/signing keys into an export. */
fun portableManifest(cache:Cache):JsonObject {
    val snapshot=checkNotNull(cache.snapshot){"Download a workspace before exporting."}
    val rows=projectRecords(cache).map{it.record.copy(data=JsonObject(it.record.data-"bytes"-"recovery_incarnation"))}
    val rowIds=rows.map{it.id}.toSet()
    val orphanAttachments=rows.filter{it.kind in setOf("image","comment")&&it.data.text("task_id") !in rowIds}
    val orphans=orphanAttachments.map{it.id}.toSet()
    val records=rows.filter{it.id !in orphans}.map { r->if(r.kind in setOf("task","list")&&r.data.text("project_id").isNotEmpty()&&r.data.text("project_id") !in rowIds)r.copy(data=JsonObject(r.data+("project_id" to "".j()))) else r }
    val originals=snapshot.records.associateBy{it.id}
    val retained=mutableListOf<JsonElement>()
    fun retain(command:Command,reason:String){
        val clean=command.copy(data=JsonObject(command.data-"bytes"))
        val record=originals[command.target]?:records.firstOrNull{it.id==command.target}
        retained+=obj("id" to command.operation_id.j(),"title" to (record?.data?.text("title")?.takeIf{it.isNotBlank()}?:command.data.text("title","Offline change")).j(),"payload" to obj("command" to WireJson.encodeToJsonElement(Command.serializer(),clean),"record" to (record?.let{WireJson.encodeToJsonElement(Record.serializer(),it)}?:JsonNull),"reason" to reason.j()),"media_id" to if(command.action=="image.create")command.target.j() else JsonNull)
    }
    cache.outbox.forEach{retain(it.command,"Unsynchronized phone change")}
    cache.conflicts.forEach{retain(it.command,it.reason)}
    // A queued deletion changes the on-screen projection, but an export still
    // keeps its original content and child attachments as recoverable context.
    for(record in snapshot.records.filter{it.id !in rowIds}+orphanAttachments){
        retained+=obj("id" to "local-hidden:${record.id}:${record.revision}".j(),"title" to record.data.text("title",record.data.text("name","Retained item")).j(),"payload" to obj("record" to WireJson.encodeToJsonElement(Record.serializer(),record),"reason" to "Original context hidden by a queued phone change".j()),"media_id" to if(record.kind=="image")record.id.j() else JsonNull)
    }
    for(draft in cache.drafts.values){
        val base=originals[draft.target];val record=Record(draft.target,draft.action.substringBefore('.'),draft.revision,JsonObject(base?.data.orEmpty()+draft.data))
        retained+=obj("id" to "draft:${draft.target}:${draft.revision}".j(),"title" to record.data.text("title","Unsubmitted draft").j(),"payload" to obj("record" to WireJson.encodeToJsonElement(Record.serializer(),record),"reason" to "Unsubmitted phone draft".j()),"media_id" to JsonNull)
    }
    retained+=cache.recoveredImports.map { item->obj("id" to item.getValue("id"),"title" to (item["title"]?:"Recovered change".j()),"payload" to item.getValue("payload"),"media_id" to if(item["has_image"]==JsonPrimitive(true))item.getValue("id") else JsonNull) }
    val people=(cache.people.map{obj("id" to it.id.j(),"name" to it.name.j())}+obj("id" to snapshot.actor.user_id.j(),"name" to snapshot.actor.name.j())).distinctBy{it.text("id")}
    return obj("format" to "org.fieldnotes.archive".j(),"version" to 2.j(),"source" to snapshot.epoch.j(),"generation" to snapshot.generation.j(),"created_at" to Clock.System.now().epochSeconds.j(),"records" to JsonArray(records.map{WireJson.encodeToJsonElement(Record.serializer(),it)}),"people" to JsonArray(people),"media" to JsonArray(emptyList()),"retained" to JsonArray(retained))
}

data class TransferUi(val busy:Boolean=false,val message:String="",val fileName:String="",val preview:JsonObject?=null,val reviewed:Boolean=false,val recovered:List<JsonObject> = emptyList())
