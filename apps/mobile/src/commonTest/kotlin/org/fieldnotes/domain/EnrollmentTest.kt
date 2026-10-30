@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
package org.fieldnotes.domain

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private class EnrollmentVault : Vault {
    var cache = Cache(); var fail = false
    override suspend fun load() = cache
    override suspend fun save(cache: Cache) { check(!fail) { "Disk full" }; this.cache = WireJson.decodeFromString(WireJson.encodeToString(cache)) }
    override suspend fun clearImages() {}
}
private class EnrollmentTransport(private val vault: EnrollmentVault) : Transport {
    var lostClaim = true; var lostActivation = true; var ready = false
    val claims = mutableListOf<JsonObject>(); var activations = 0
    val account = Actor("u", "Alex", "member", "session")
    override suspend fun keys() = obj("private_key" to "never-upload-this".j(), "public_key" to "public".j())
    override suspend fun configure(configuration: JsonObject) { assertEquals("never-upload-this", configuration.text("private_key")) }
    override suspend fun stop() {}
    override suspend fun bootstrap(url: String, ca: String, path: String, body: JsonObject): Reply {
        assertNotNull(vault.cache.enrollment)
        assertFalse(body.toString().contains("never-upload-this"))
        if(path == "/claim") { claims += body; if(lostClaim) { lostClaim = false; error("Lost claim response") }; return Reply(200, """{"id":"11111111-1111-1111-1111-111111111111","verification":"1234ABCD","state":"pending_approval"}""") }
        return Reply(200, obj("user" to "Alex".j(), "role" to "member".j(), "state" to (if(ready) "ready" else "provisioning").j(), *if(ready) arrayOf("configuration" to obj("ca_pem" to ca.j(), "server_key" to "server".j()), "activation_secret" to "secret".j()) else emptyArray()).toString())
    }
    override suspend fun exchange(path: String, token: String?, body: JsonObject?, serverKey: String?): Reply {
        if(path == "/api/device/activate") { activations++; assertEquals("secret", vault.cache.enrollment?.activationSecret); assertNotNull(vault.cache.config); if(lostActivation) { lostActivation = false; error("Lost activation response") }; return Reply(200, obj("token" to "active-token".j(), "actor" to WireJson.encodeToJsonElement(account)).toString()) }
        return Reply(200, WireJson.encodeToString(Snapshot(1,"epoch",1,0,emptyList(),account)))
    }
}
class EnrollmentTest {
    private fun invitation() = Invitation(1,"https://192.168.1.10:9443/api/enrollment","-----BEGIN CERTIFICATE-----" + "x".repeat(120),"a".repeat(64),"Alex","member",1100)
    @Test fun invitationIsStrictScopedAndExpires() {
        fun code(i: Invitation) = "fieldnotes://enroll#"+Base64.UrlSafe.encode(WireJson.encodeToString(i).encodeToByteArray()).trimEnd('=')
        assertEquals("Alex",readInvitation(code(invitation()),1000).user)
        assertFails { readInvitation(code(invitation()),1101) }; assertFails { readInvitation(code(invitation().copy(url="https://8.8.8.8:9443/api/enrollment")),1000) }
        assertFails { readInvitation(code(invitation().copy(role="root")),1000) }
        assertFails { readInvitation(code(invitation().copy(url="https://192.168.1.10:9443/api/enrollment?redirect=yes")),1000) }
    }
    @Test fun lostResponsesAndProcessDeathKeepClaimAndActivationCredentials() = runTest {
        val vault=EnrollmentVault(); val transport=EnrollmentTransport(vault)
        fun model() = Workspace(transport,vault,{"11111111-1111-4111-8111-111111111111"},backgroundScope)
        var m=model();m.initialize();m.beginEnrollment(invitation(),"My phone")
        assertFails { m.advanceEnrollment() }; val nonce=vault.cache.enrollment!!.claimSecret
        m=model();m.initialize();m.advanceEnrollment();assertNull(vault.cache.config);assertEquals(transport.claims[0],transport.claims[1]);assertEquals(nonce,vault.cache.enrollment!!.claimSecret)
        transport.ready=true;assertFails { m.advanceEnrollment() }; assertNotNull(vault.cache.config)
        m=model();m.initialize();m.advanceEnrollment();assertEquals(2,transport.activations);assertNull(vault.cache.enrollment);assertEquals("active-token",vault.cache.token)
        m=model();m.initialize();assertFalse(m.state.value.needsLogin);m.sync();assertEquals("Alex",vault.cache.snapshot!!.actor.name)
    }
    @Test fun failedPersistenceCannotSubmitAClaimAndWidgetTitlesAreOptIn() = runTest {
        val v=EnrollmentVault();val t=EnrollmentTransport(v);val m=Workspace(t,v,{"11111111-1111-4111-8111-111111111111"},backgroundScope);m.initialize();v.fail=true
        assertFails { m.beginEnrollment(invitation(),"Phone") };assertTrue(t.claims.isEmpty());assertNull(m.state.value.cache.enrollment)
        val signed=Cache(token="token",snapshot=Snapshot(1,"e",1,0,emptyList(),t.account))
        assertFalse(widgetMayShowTitles(signed,true));assertFalse(widgetMayShowTitles(signed.copy(preferences=Preferences(widgetTitles=true)),false));assertTrue(widgetMayShowTitles(signed.copy(preferences=Preferences(widgetTitles=true)),true))
    }
}
