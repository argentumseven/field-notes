package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class PortableDataTest {
    @Test fun restoredTaskExportsKeepContentWithoutTransplantingTheServerWriteFence(){
        val task=Record("task","task",3,obj("title" to "Restored reference".j(),"notes" to "Kept notes".j(),"recovery_incarnation" to 2.j()))
        val cache=Cache(snapshot=Snapshot(1,"workspace",1,3,listOf(task),Actor("person","Alex","owner","session")))
        val record=portableManifest(cache).getValue("records").jsonArray.single().jsonObject.getValue("data").jsonObject
        assertEquals("Kept notes",record.text("notes"));assertFalse(record.containsKey("recovery_incarnation"));assertEquals(2,cache.snapshot!!.records.single().data.number("recovery_incarnation"))
    }

    @Test fun contentExportsKeepOfflineImagesAndDraftsWithoutCredentials(){
        val task=Record("task","task",1,obj("title" to "Context to retain".j()))
        val image=Record("image","image",1,obj("task_id" to task.id.j(),"name" to "photo.png".j()))
        val snapshot=Snapshot(1,"workspace",1,2,listOf(task,image),Actor("person","Alex","owner","secret-session"))
        val command=Command("operation","workspace",1,"task.delete",task.id,1,obj())
        val pending=Command("pending-image","workspace",1,"image.create","new-image",null,obj("task_id" to task.id.j(),"name" to "new.png".j(),"bytes" to "PRIVATE-IMAGE-BASE64".j()))
        val cache=Cache(keys=obj("private_key" to "PRIVATE-WIREGUARD".j()),config=obj("preshared_key" to "PRIVATE-PSK".j()),token="PRIVATE-TOKEN",snapshot=snapshot,outbox=listOf(Pending(command),Pending(pending)),drafts=mapOf("note" to Draft("note","workspace",1,0,obj("title" to "An offline idea".j(),"body" to "Keep the full context".j()),action="note.update")))
        val file=portableManifest(cache);val text=file.toString()
        for(secret in listOf("PRIVATE-WIREGUARD","PRIVATE-PSK","PRIVATE-TOKEN","secret-session","PRIVATE-IMAGE-BASE64"))assertFalse(text.contains(secret),secret)
        val retained=file["retained"]!!.jsonArray.map{it.jsonObject};assertTrue(retained.any{it["media_id"]?.jsonPrimitive?.contentOrNull=="image"});assertTrue(retained.any{it["media_id"]?.jsonPrimitive?.contentOrNull=="new-image"})
        assertTrue(text.contains("Keep the full context"));assertTrue(text.contains("Context to retain"));assertEquals(1,file["people"]!!.jsonArray.size)
        assertEquals(cache,cache.copy(),"Constructing an export does not mutate or clear the outbox")
    }
}
