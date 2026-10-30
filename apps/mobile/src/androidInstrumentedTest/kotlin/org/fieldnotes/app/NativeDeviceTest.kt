package org.fieldnotes.app

import android.content.pm.PackageManager
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.fieldnotes.platform.AndroidTransport
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import java.io.File
import java.util.UUID

class NativeDeviceTest {
    @Test fun installedManifestLeavesTheSystemVpnSlotFree() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS)
        assertFalse(info.services.orEmpty().any{it.permission=="android.permission.BIND_VPN_SERVICE" || it.name.contains("VpnService")})
        assertFalse(info.requestedPermissions.orEmpty().any{it=="android.permission.ACCESS_FINE_LOCATION" || it=="android.permission.BIND_DEVICE_ADMIN"})
    }
    @Test fun realJniWireGuardTlsAndServerRoundTripPreserveUnicode() = runBlocking {
        val arg=InstrumentationRegistry.getArguments().getString("fieldNotesConfig");assumeNotNull(arg)
        val config=WireJson.parseToJsonElement(Base64.decode(arg,Base64.NO_WRAP).decodeToString()).jsonObject
        val transport=AndroidTransport();val keys=transport.keys()
        assertEquals(32,Base64.decode(keys.text("private_key"),Base64.NO_WRAP).size)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir,"native-test-public.txt").writeText(keys.text("public_key"))
        val paired=JsonObject(config+ ("private_key" to keys.getValue("private_key")))
        transport.configure(paired)
        val peer=config.text("server_key")
        try{
            val login=transport.exchange("/api/login",null,obj("name" to "owner".j(),"password" to "household-test-password-only".j(),"device" to "Emulator native test".j()),peer)
            assertEquals(200,login.status);val token=WireJson.parseToJsonElement(login.body).jsonObject.text("token")
            val snapshot=transport.exchange("/api/snapshot",token,serverKey=peer);assertEquals(200,snapshot.status)
            val meta=WireJson.parseToJsonElement(snapshot.body).jsonObject
            suspend fun command(action:String,target:String,data:JsonObject,revision:Long?=null):Reply{
                val c=Command(UUID.randomUUID().toString(),meta.text("epoch"),meta.number("generation"),action,target,revision,data)
                return transport.exchange("/api/commands",token,WireJson.encodeToJsonElement(c).jsonObject,peer)
            }
            val project=UUID.randomUUID().toString();assertEquals(200,command("project.create",project,obj("title" to "Emulator kitchen".j())).status)
            val task=UUID.randomUUID().toString();val title="Kitchen shelves 🪴 — notes for us"
            val created=command("task.create",task,obj("title" to title.j(),"project_id" to project.j(),"notes" to "Real Android → JNI → WireGuard → TLS → Rust".j()))
            assertEquals(200,created.status);assertEquals(title,WireJson.parseToJsonElement(created.body).jsonObject["record"]!!.jsonObject["data"]!!.jsonObject.text("title"))
            val start=command("task.start",task,obj(),1);assertEquals(200,start.status)
            val repeat=command("task.start",task,obj(),1);assertEquals(409,repeat.status)
            try{transport.exchange("/api/head",token,serverKey="different-server");fail("Stale server identity accepted")}catch(_:IllegalStateException){}
        }finally{transport.stop();File(context.filesDir,"native-test-public.txt").delete()}
    }
}
