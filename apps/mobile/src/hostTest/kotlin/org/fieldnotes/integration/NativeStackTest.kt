package org.fieldnotes.integration

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.fieldnotes.platform.AndroidTransport
import kotlin.test.*

class NativeStackTest {
    @Test fun actualJniWireGuardHttpsAndRustPreserveUnicodeAndAuthority() = runBlocking<Unit> {
        val file = System.getenv("FIELD_NOTES_JNI_CONFIG") ?: error("Run through scripts/test-native-client.mjs")
        val config = WireJson.parseToJsonElement(File(file).readText()).jsonObject
        val peerFile = System.getenv("FIELD_NOTES_JNI_PEER_FILE") ?: error("Missing fixture peer file")
        val native = AndroidTransport()
        val keys = native.keys()
        File(peerFile).writeText(keys.text("public_key"))
        val peer = config.text("server_key")
        native.configure(JsonObject(config + ("private_key" to keys.getValue("private_key"))))
        try {
            val login = native.exchange("/api/login", null, obj("name" to "owner".j(), "password" to "household-test-password-only".j(), "device" to "JNI integration fixture".j()), peer)
            assertEquals(200,login.status)
            val token = WireJson.parseToJsonElement(login.body).jsonObject.text("token")
            val snapshot = native.exchange("/api/snapshot",token,serverKey=peer)
            assertEquals(200,native.exchange("/api/device/update",token,serverKey=peer).status)
            assertEquals(200,snapshot.status)
            val directory=native.exchange("/api/people",token,serverKey=peer);assertEquals(200,directory.status)
            assertEquals("owner",WireJson.parseToJsonElement(directory.body).jsonObject.getValue("people").jsonArray.single().jsonObject.text("name"))
            val state=WireJson.parseToJsonElement(snapshot.body).jsonObject
            suspend fun send(action:String,target:String,data:JsonObject,revision:Long?=null):Reply {
                val command=Command(UUID.randomUUID().toString(),state.text("epoch"),state.number("generation"),action,target,revision,data)
                return native.exchange("/api/commands",token,WireJson.encodeToJsonElement(command).jsonObject,peer)
            }
            val project=UUID.randomUUID().toString();assertEquals(200,send("project.create",project,obj("title" to "Kitchen".j())).status)
            val task=UUID.randomUUID().toString();val title="Shelves 🪴 — café and family notes"
            val result=send("task.create",task,obj("title" to title.j(),"notes" to "End to end through the actual JNI adapter".j(),"project_id" to project.j()))
            assertEquals(200,result.status);assertEquals(title,WireJson.parseToJsonElement(result.body).jsonObject["record"]!!.jsonObject["data"]!!.jsonObject.text("title"))
            assertEquals(200,send("task.start",task,obj(),1).status);assertEquals(409,send("task.start",task,obj(),1).status)
            assertFailsWith<IllegalStateException>{native.exchange("/api/head",token,serverKey="wrong-server")}
            assertFailsWith<IllegalStateException>{native.exchange("/api/admin",token,serverKey=peer)}
            val gate=File(System.getenv("FIELD_NOTES_JNI_GATE")?:error("Missing cancellation fixture gate"))
            File(gate,"armed").writeText("armed")
            val blocked=async(Dispatchers.IO){runCatching { native.exchange("/api/head",token,serverKey=peer) }}
            withTimeout(5000){while(!File(gate,"entered").exists())delay(20)}
            // UNDISPATCHED enters exchange and captures its generation before
            // the IO dispatcher queues it behind the blocked native exchange.
            val queued=async(start=CoroutineStart.UNDISPATCHED){runCatching { native.exchange("/api/head",token,serverKey=peer) }}
            val started=System.nanoTime();withTimeout(3000){native.stop()}
            assertTrue((System.nanoTime()-started)/1_000_000<3000,"Stop must cancel the active native request before waiting for its Kotlin gate")
            assertTrue(withTimeout(3000){blocked.await()}.isFailure)
            val canceled=withTimeout(3000){queued.await()}.exceptionOrNull()
            assertTrue(canceled is IllegalStateException && canceled.message=="Private connection stopped", "An exchange queued before Stop must fail its generation check without sending")
        } finally {native.stop()}
    }
}
