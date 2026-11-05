package org.fieldnotes.domain

import kotlinx.serialization.Serializable

@Serializable data class AdditionNotice(val key:String,val title:String,val summary:String,val details:String,val target:String?=null)
/** Trusted server creation cursors, not a device clock, identify new additions.
 * Existing content edits and an initial household download are not additions. */
fun sharedAdditionNotices(cache:Cache,afterSequence:Long,throughSequence:Long=cache.snapshot?.sequence?:0):List<AdditionNotice> {
    val snapshot=cache.snapshot ?: return emptyList();val user=snapshot.actor.user_id
    val records=snapshot.records.associateBy { it.id }
    data class Addition(val record:Record,val person:String,val text:String,val target:String,val list:Record?)
    val additions=snapshot.records.filter { it.revision>0&&it.data.number("created_sequence")>afterSequence&&it.data.number("created_sequence")<=throughSequence&&recordCreator(it)!=user&&!it.flag("deleted") }
        .sortedWith(compareBy<Record>{it.data.number("created_sequence")}.thenBy{it.id}).mapNotNull { record->
            val d=record.data;val task=if(record.kind in setOf("comment","image"))records[d.text("task_id")] else record
            val list=task?.data?.text("project_id")?.let(records::get)?.takeIf { it.kind=="list" }
            if(list?.data?.get("notify_additions")==kotlinx.serialization.json.JsonPrimitive(false))return@mapNotNull null
            if(task!=null&&task.kind in setOf("task","event","note")&&!reminderRecipient(task,user))return@mapNotNull null
            if(record.kind in setOf("task","event","note")&&!reminderRecipient(record,user))return@mapNotNull null
            val person=d.text("created_by_name").ifBlank { personName(cache,recordCreator(record)) }
            val name="‘${d.text("title").take(240)}’"
            val description=when(record.kind) {
                "task"->(if(list!=null)when(list.data.text("template")){"groceries"->"grocery item";"shopping"->"shopping item";else->"list item"} else "task")+" $name"+(list?.let { " to ‘${it.data.text("title")}’" } ?: "")
                "list"->"${recordTypeLabel(record).lowercase()} $name"
                "project"->"project $name"
                "event"->(if(eventRepeat(record)!=null)"repeating calendar event" else "calendar event")+" $name"
                "note"->(if(record.flag("on_radar"))"reminder note" else "note")+" $name"
                "comment"->"a comment to ‘${task?.data?.text("title") ?: "an item"}’"
                "image"->"an image to ‘${task?.data?.text("title") ?: "an item"}’"
                else->return@mapNotNull null
            }
            Addition(record,person,"$person added $description",task?.id ?: record.id,list)
        }
    if(additions.isEmpty())return emptyList()
    fun details(a:Addition)=buildString {
        append(a.text)
        a.record.data.text("store").takeIf { it.isNotBlank() }?.let {append("\nStore: $it")}
        a.record.data.text("quantity").takeIf { it.isNotBlank() }?.let {append("\nQuantity: $it")}
        a.record.data.text("notes").ifBlank {a.record.data.text("body")}.trim().takeIf { it.isNotBlank() }?.let {append("\n\n");append(it.take(1400))}
    }
    if(additions.size<=3)return additions.map { AdditionNotice("addition:${it.record.id}",it.text.take(240),it.record.data.text("notes").ifBlank {it.record.data.text("body")}.take(300).ifBlank {"Tap to open the item."},details(it),it.target) }
    val people=additions.map { it.person }.distinct().take(3).joinToString(", ")
    val list=additions.mapNotNull { it.list }.distinctBy { it.id }.singleOrNull()?.takeIf { additions.all { a->a.list?.id==it.id } }
    val title="$people added ${additions.size} items"+(list?.let {" to ‘${it.data.text("title")}’"} ?: " to Field Notes")
    return listOf(AdditionNotice("additions:${snapshot.epoch}:${snapshot.generation}:$throughSequence",title.take(240),additions.take(4).joinToString(" · "){it.record.data.text("title").ifBlank {it.record.kind}}.take(300),additions.take(50).joinToString("\n\n",transform=::details).take(7000)+(if(additions.size>50)"\n\nOpen Field Notes for all ${additions.size} additions." else ""),list?.id))
}
