package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetContinuityTest {
    @Test fun cancelledPersistencePublishesTheQueueBeforeAnotherWrite()=runTest {
        val seed=Cache(keys=obj("private_key" to "synthetic-key".j()),token="synthetic-token",snapshot=Snapshot(1,"synthetic-epoch",1,1,emptyList(),Actor("owner","Owner","owner","session")))
        val persisted=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val vault=object:Vault {
            var value=seed;var block=false
            override suspend fun load()=value
            override suspend fun save(cache:Cache){value=cache;if(block){block=false;persisted.complete(Unit);release.await()}}
            override suspend fun clearImages(){}
        }
        val transport=object:Transport{
            override suspend fun keys()=error("Existing keys must be retained")
            override suspend fun configure(configuration:JsonObject){}
            override suspend fun stop(){}
            override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?)=error("Offline queue test must not use a network")
        }
        var id=0;val model=Workspace(transport,vault,{"operation-${id++}"},backgroundScope);model.initialize()
        vault.block=true
        val save=launch{model.enqueue("task.create","first",obj("title" to "Keep the first edit".j()),null)}
        persisted.await();save.cancel();release.complete(Unit);save.join()
        model.enqueue("task.create","second",obj("title" to "Keep the second edit".j()),null)
        assertEquals(listOf("first","second"),model.state.value.cache.outbox.map{it.command.target})
        assertEquals(model.state.value.cache.outbox,vault.value.outbox)
        assertEquals(seed.keys,vault.value.keys)
    }
    @Test fun pendingReminderEditsReplaceServerProjectionsWhileOtherEditsPreserveThem(){
        val old=reminderData(ReminderSet(listOf(ReminderAlert("alert",10))))
        val changed=reminderData(ReminderSet(listOf(ReminderAlert("alert",120))))
        val empty=reminderData(ReminderSet())
        for(kind in listOf("task","event","note")){
            val item=Record("item",kind,1,obj("title" to "Original".j(),"reminders" to empty,"reminder_rules" to empty,"reminder_timing_rules" to old,"reminder_schedule_rules" to old))
            val cache=Cache(snapshot=Snapshot(1,"synthetic",1,1,listOf(item),Actor("owner","Owner","owner","session")))
            fun projected(data:JsonObject)=projectRecords(cache.copy(outbox=listOf(Pending(Command("operation","synthetic",1,"$kind.update",item.id,1,data))))).single().record
            assertEquals(old,reminderData(reminderSet(projected(obj("title" to "Amended".j())))!!))
            assertEquals(changed,reminderData(reminderSet(projected(obj("reminders" to changed)))!!))
            assertEquals(empty,reminderData(reminderSet(projected(obj("reminders" to empty)))!!))
        }
    }
}
