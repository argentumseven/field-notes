package org.fieldnotes.domain

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class CookingStage(val id:String,val name:String,val duration_minutes:Int=30,val offset_minutes:Int=0,val wait_minutes:Int=0,val after:List<String> = emptyList(),val check_every_minutes:Int=0,val notes:String="",val notify_start:Boolean=true,val notify_end:Boolean=true)
@Serializable data class CookingItem(val id:String,val name:String,val notes:String="",val stages:List<CookingStage> = emptyList())
@Serializable data class CookingProgress(val occurrence:Long,val stage_id:String,val state:String,val at:Long)
@Serializable data class CookingPlan(val version:Int=1,val items:List<CookingItem> = emptyList(),val progress:List<CookingProgress> = emptyList(),val profile:AlertProfile=AlertProfile(volume=100,sound="signal"))
data class CookingTime(val item:CookingItem,val stage:CookingStage,val planned:Long,val start:Long,val end:Long,val progress:CookingProgress?)
fun cookingPlan(record:Record)=record.data["meal_plan"]?.takeUnless{it==JsonNull}?.let{runCatching{WireJson.decodeFromJsonElement<CookingPlan>(it)}.getOrNull()}
fun cookingData(plan:CookingPlan)=WireJson.encodeToJsonElement(plan).jsonObject
fun validateCookingPlan(plan:CookingPlan) {
    require(plan.version==1&&plan.items.size<=24){"Use up to 24 items."}
    val stages=plan.items.flatMap{it.stages};val ids=stages.map{it.id}.toSet()
    require(stages.size<=64&&ids.size==stages.size&&plan.items.map{it.id}.distinct().size==plan.items.size){"Use distinct items and up to 64 phases."}
    for(item in plan.items){require(item.name.isNotBlank()&&item.name.length<=160&&item.notes.length<=8192&&item.stages.size<=12){"Enter an item name and up to 12 phases."}}
    for(s in stages)require(s.name.isNotBlank()&&s.name.length<=160&&s.notes.length<=8192&&s.duration_minutes in 0..20160&&s.offset_minutes in -20160..0&&s.wait_minutes in 0..20160&&s.check_every_minutes in 0..1440&&s.after.distinct()==s.after&&s.after.all{it in ids&&it!=s.id}){"Check phase names, durations and dependencies."}
    val done=mutableSetOf<String>();while(done.size<stages.size){val ready=stages.filter{it.id !in done&&it.after.all{d->d in done}};require(ready.isNotEmpty()){"Dependencies cannot form a loop."};done+=ready.map{it.id}}
}
/** Backward pass inherits the serving deadline; forward pass propagates actual
 * starts/completions through prerequisites without changing the serving target. */
fun cookingSchedule(plan:CookingPlan,occurrence:Long):List<CookingTime> {
    validateCookingPlan(plan)
    val stages=plan.items.flatMap{it.stages};val order=mutableListOf<CookingStage>();val ordered=mutableSetOf<String>()
    while(order.size<stages.size){val ready=stages.filter{s->s.id !in ordered&&s.after.all{it in ordered}};order+=ready;ordered+=ready.map{it.id}}
    val starts=mutableMapOf<String,Long>()
    for(s in order.asReversed()){val deadline=minOf(occurrence+s.offset_minutes*60L,stages.filter{s.id in it.after}.minOfOrNull{starts.getValue(it.id)-it.wait_minutes*60L}?:Long.MAX_VALUE);starts[s.id]=deadline-s.duration_minutes*60L;require(occurrence-starts.getValue(s.id)<=28*86400){"The full preparation chain must fit within 28 days."}}
    val ends=mutableMapOf<String,Long>();val result=mutableListOf<CookingTime>()
    for(s in order){val p=plan.progress.find{it.occurrence==occurrence&&it.stage_id==s.id};val planned=starts.getValue(s.id);val ready=s.after.maxOfOrNull{ends.getValue(it)+s.wait_minutes*60L}?:Long.MIN_VALUE;val start=if(p?.state=="running")p.at else maxOf(planned,ready);val end=if(p?.state=="done")p.at else start+s.duration_minutes*60L;ends[s.id]=end;result+=CookingTime(plan.items.first{i->s in i.stages},s,planned,start,end,p)}
    return result.sortedBy{it.start}
}
fun cookingProgressData(data:JsonObject,change:JsonObject):JsonObject {
    val plan=(data["meal_plan"] as? JsonObject)?:return data
    val progress=(plan["progress"] as? JsonArray).orEmpty().filterNot{p->p.jsonObject["occurrence"]==change["occurrence"]&&p.jsonObject["stage_id"]==change["stage_id"]}.toMutableList()
    if(change.text("state")!="planned")progress+=change
    return JsonObject(data+("meal_plan" to JsonObject(plan+("progress" to JsonArray(progress)))))
}
fun cookingAlerts(record:Record,now:Instant,zone:TimeZone):List<LocalAlert> {
    val plan=cookingPlan(record)?:return emptyList();if(runCatching{validateCookingPlan(plan)}.isFailure)return emptyList()
    val today=now.toLocalDateTime(zone).date;val end=today.plus(8,DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds
    // 64 bounded phases can chain long cooks. Derive the range from the actual
    // backward plan, so work before dinner's day is still scheduled.
    val relative=cookingSchedule(plan,0);val lead=-(relative.minOfOrNull{it.planned}?:0);val days=(lead/86400+10).coerceAtMost(39).toInt()
    val instances=eventInstances(record,today.minus(29,DateTimeUnit.DAY),today.plus(days,DateTimeUnit.DAY),zone)
    val durations=plan.items.flatMap{it.stages}.associate{it.id to it.duration_minutes}
    // Actual work may begin much earlier than the inherited plan (for example,
    // freezing food for a future occasion). Keep those live clocks in range.
    val live=plan.progress.filter{it.state=="running"&&it.at<end&&it.at+(durations[it.stage_id]?:0)*60L>=now.epochSeconds-600}.map{it.occurrence}.distinct().flatMap{at->
        val date=Instant.fromEpochSeconds(at).toLocalDateTime(zone).date
        eventInstances(record,date.minus(1,DateTimeUnit.DAY),date.plus(2,DateTimeUnit.DAY),zone).filter{it.data.number("starts_at")==at}
    }
    return (instances+live).distinctBy{it.data.number("starts_at")}.flatMap { r->
      val occurrence=r.data.number("starts_at")
      cookingSchedule(plan,occurrence).flatMap { t->
        if(t.progress?.state=="done")return@flatMap emptyList()
        fun cue(at:Long,type:String)=LocalAlert("cook:${r.id}:${t.stage.id}:$type:$at",at,r.id,"calendar",plan.profile,"cook:${r.id}:${t.stage.id}:$type",occurrence,"${if(type=="start")"Begin"else if(type=="end")"Finish"else"Check"} ${t.stage.name} · ${t.item.name}")
        val cues=mutableListOf<LocalAlert>()
        if(t.stage.notify_start&&t.progress==null)cues+=cue(t.start,"start")
        if(t.stage.notify_end)cues+=cue(t.end,"end")
        val step=t.stage.check_every_minutes*60L
        if(step>0&&t.end>t.start){val latest=maxOf(1,(now.epochSeconds-t.start)/step);for(n in latest..latest+2){val at=t.start+n*step;if(at<t.end)cues+=cue(at,"check")}}
        cues.filter{it.at>=now.epochSeconds-600&&it.at<end}
      }
    }
}

/** Resolve a delivered cooking cue against the current plan, including a ready
 * time edited while it rang. This is navigation only, independent of visibility. */
fun cookingAlertOccurrence(record:Record,key:String,zone:TimeZone):Long? {
    if(!key.startsWith("cook:${record.id}:"))return null
    val at=key.substringAfterLast(':').toLongOrNull()?.takeIf{it in 1..4102444800L}?:return null
    return runCatching{cookingAlerts(record,Instant.fromEpochSeconds(at),zone).firstOrNull{it.key==key}?.sourceAt}.getOrNull()
}
