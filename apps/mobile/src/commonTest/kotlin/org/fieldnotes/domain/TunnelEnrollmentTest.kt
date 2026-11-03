@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
package org.fieldnotes.domain

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private fun fixtureKey(byte:Int)=Base64.encode(ByteArray(32){byte.toByte()})
private fun tunnelInvite(expires:Long=Clock.System.now().epochSeconds+600)=Invitation(2,"https://10.82.0.1:8443/api/enrollment",
    "-----BEGIN CERTIFICATE-----"+"A".repeat(140),"a".repeat(64),"Alex","member",expires,
    obj("public_key" to fixtureKey(1).j(),"address" to "10.82.0.2/32".j(),"server_key" to fixtureKey(2).j(),"preshared_key" to fixtureKey(3).j(),"api" to "https://10.82.0.1:8443".j(),"endpoint" to "home.example:49272".j(),"alternate_endpoints" to JsonArray(listOf("192.168.1.10:49272".j()))))
private class TunnelVault:Vault {
    var cache=Cache();override suspend fun load()=cache
    override suspend fun save(cache:Cache){this.cache=WireJson.decodeFromString(WireJson.encodeToString(cache))}
    override suspend fun clearImages(){}
}
private class TunnelTransport:Transport {
    var lostClaim=true;var ready=false;var lostActivation=true;var stopped=false
    val requests=mutableListOf<Pair<String,JsonObject?>>();var configured:JsonObject?=null
    val actor=Actor("u","Alex","member","session")
    override suspend fun keys()=obj("private_key" to fixtureKey(9).j(),"public_key" to fixtureKey(1).j())
    override suspend fun configure(configuration:JsonObject){configured=configuration;assertEquals(fixtureKey(9),configuration.text("private_key"));assertFalse(configuration.containsKey("public_key"))}
    override suspend fun stop(){stopped=true}
    override suspend fun bootstrap(url:String,ca:String,path:String,body:JsonObject):Reply=error("A v2 phone must never dial management or LAN bootstrap")
    override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?):Reply {
        requests+=path to body;assertEquals(fixtureKey(2),serverKey)
        assertNull(token)
        val data=when(path) {
            "/api/enrollment/claim"->{if(lostClaim){lostClaim=false;error("Lost claim reply")};obj("id" to "11111111-1111-4111-8111-111111111111".j(),"verification" to "1234ABCD".j(),"state" to "pending_approval".j())}
            "/api/enrollment/status"->{val fields=mutableMapOf<String,JsonElement>("user" to "Alex".j(),"role" to "member".j(),"state" to (if(ready)"ready" else "pending_approval").j());if(ready){fields["configuration"]=JsonObject(configured!!-"private_key");fields["activation_secret"]="receipt".j()};JsonObject(fields)}
            "/api/device/activate"->{if(lostActivation){lostActivation=false;error("Lost activation reply")};obj("token" to "phone-session".j(),"actor" to WireJson.encodeToJsonElement(actor))}
            "/api/enrollment/cancel"->obj("canceled" to JsonPrimitive(true))
            else->error("Unexpected enrollment route")
        }
        return Reply(200,data.toString())
    }
}
class TunnelEnrollmentTest {
    @Test fun compactCameraCodeIsTheSameInvitationWithLegacyPasteStillSupported() {
        val der=Base64.encode(ByteArray(500) { 65 })
        val ca="-----BEGIN CERTIFICATE-----\n"+der.chunked(64).joinToString("\n")+"\n-----END CERTIFICATE-----\n"
        val invite=tunnelInvite().copy(ca=ca);val t=invite.tunnel!!
        val compact=obj("fieldnotes" to 2.j(),"u" to invite.url.j(),"c" to der.j(),"t" to invite.token.j(),"n" to invite.user.j(),"r" to invite.role.j(),"e" to invite.expires.j(),
            "w" to JsonArray(listOf("address","endpoint","alternate_endpoints","server_key","preshared_key","api","public_key").map { t.getValue(it) })).toString()
        val legacy="fieldnotes://enroll#"+Base64.UrlSafe.encode(WireJson.encodeToString(invite).encodeToByteArray())
        assertEquals(invite,readInvitation(compact));assertEquals(invite,readInvitation(legacy))
        assertTrue(compact.length<legacy.length*0.75)
        assertFails { readInvitation(compact.replace("\"fieldnotes\":2","\"fieldnotes\":9")) }
        assertFails { readInvitation(compact.dropLast(1)+",\"extra\":true}") }
    }

    @Test fun lostRepliesRestartAndActivationUseOnlyTheSameRestrictedPeerAndNonce()=runTest {
        val vault=TunnelVault();val transport=TunnelTransport()
        fun model()=Workspace(transport,vault,{"11111111-1111-4111-8111-111111111111"},backgroundScope)
        var m=model();m.initialize();m.beginEnrollment(tunnelInvite(),"My phone");val keys=vault.cache.keys;val saved=vault.cache.enrollment
        assertFails { m.advanceEnrollment() };assertEquals(saved,vault.cache.enrollment);assertNull(vault.cache.config)
        m=model();m.initialize();m.advanceEnrollment();assertEquals(transport.requests[0],transport.requests[1]);assertEquals("pending_approval",vault.cache.enrollment!!.stage)
        transport.ready=true;assertFails { m.advanceEnrollment() };assertEquals("receipt",vault.cache.enrollment!!.activationSecret);assertNotNull(vault.cache.config)
        m=model();m.initialize();m.advanceEnrollment();assertNull(vault.cache.enrollment);assertEquals("phone-session",vault.cache.token);assertEquals(keys,vault.cache.keys)
        assertTrue(transport.requests.all { it.first.startsWith("/api/enrollment/")||it.first=="/api/device/activate" })
    }
    @Test fun wrongPhoneKeyCannotPersistClaimOrContactTheNetwork()=runTest {
        val vault=TunnelVault();val transport=TunnelTransport();val m=Workspace(transport,vault,{"11111111-1111-4111-8111-111111111111"},backgroundScope);m.initialize()
        val wrong=tunnelInvite().copy(tunnel=JsonObject(tunnelInvite().tunnel!!+("public_key" to fixtureKey(4).j())))
        assertFails { m.beginEnrollment(wrong,"Other phone") };assertNull(vault.cache.enrollment);assertTrue(transport.requests.isEmpty())
    }
    @Test fun expiredUnactivatedAttemptCanBeReplacedWithoutDiscardingPhoneKeys()=runTest {
        val vault=TunnelVault();val transport=TunnelTransport();val m=Workspace(transport,vault,{"11111111-1111-4111-8111-111111111111"},backgroundScope);m.initialize();val keys=vault.cache.keys
        m.beginEnrollment(tunnelInvite(Clock.System.now().epochSeconds-1),"Phone");m.forgetExpiredEnrollment();assertNull(vault.cache.enrollment);assertEquals(keys,vault.cache.keys);assertTrue(transport.stopped)
        m.beginEnrollment(tunnelInvite(),"Phone");assertFails { m.forgetExpiredEnrollment() };assertNotNull(vault.cache.enrollment)
    }
    @Test fun parserRejectsBroadEndpointsAndAcceptsOnlyTheBoundPrivateApi() {
        fun code(invite:Invitation)="fieldnotes://enroll#"+Base64.UrlSafe.encode(WireJson.encodeToString(invite).encodeToByteArray()).trimEnd('=')
        val original=tunnelInvite();assertEquals(original,readInvitation(code(original)))
        assertFails { readInvitation(code(original.copy(url="https://192.168.1.10:9443/api/enrollment"))) }
        assertFails { readInvitation(code(original.copy(tunnel=JsonObject(original.tunnel!!+("address" to "10.83.0.2/32".j()))))) }
        assertFalse(code(original).contains("private_key"))
    }
}
