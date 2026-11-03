@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
package org.fieldnotes.domain

import kotlin.io.encoding.Base64
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Successful pending approval polls remain cheap; failed contacts back off. */
fun pairingRetryDelay(failures:Int,jitter:Double=kotlin.random.Random.nextDouble()):Long {
    require(jitter in 0.0..1.0)
    if(failures<=0)return 3_000
    val ceiling=(15_000L shl (failures-1).coerceAtMost(5)).coerceAtMost(300_000)
    return (ceiling*(0.8+0.2*jitter)).toLong()
}

@Serializable data class Invitation(val version: Int, val url: String, val ca: String, val token: String, val user: String, val role: String, val expires: Long, val tunnel:JsonObject?=null)
@Serializable data class Enrollment(val invitation: Invitation, val claimSecret: String, val name: String, val id: String = "", val verification: String = "", val stage: String = "claiming", val activationSecret: String? = null)

fun readInvitation(raw: String, now: Long = Clock.System.now().epochSeconds): Invitation {
    val code = raw.trim()
    require(code.length in 80..20000 && (code.startsWith("fieldnotes://enroll#") || code.startsWith("{"))) { "Scan or paste a Field Notes invitation from your trusted LAN administrator." }
    val invitation = try {
        if(code.startsWith("{")) {
            val compact=WireJson.parseToJsonElement(code).jsonObject
            require(compact.keys==setOf("fieldnotes","u","c","t","n","r","e","w") && compact.number("fieldnotes")==2L)
            val der=Base64.decode(compact.text("c"));require(der.size in 100..9000)
            val ca="-----BEGIN CERTIFICATE-----\n"+Base64.encode(der).chunked(64).joinToString("\n")+"\n-----END CERTIFICATE-----\n"
            val w=compact["w"]!!.jsonArray;require(w.size==7)
            Invitation(2,compact.text("u"),ca,compact.text("t"),compact.text("n"),compact.text("r"),compact.number("e"),
                JsonObject(listOf("address","endpoint","alternate_endpoints","server_key","preshared_key","api","public_key").mapIndexed { i,key -> key to w[i] }.toMap()))
        } else {
            val data = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(code.substringAfter('#')).decodeToString()
            WireJson.decodeFromString<Invitation>(data)
        }
    } catch (_: Exception) { throw IllegalArgumentException("This QR is not a valid Field Notes invitation. Ask for a fresh invitation or paste it instead.") }
    require(invitation.version in 1..2 && invitation.token.matches(Regex("[a-f0-9]{64}"))) { "Unsupported invitation." }
    val match = Regex("https://(\\d{1,3}(?:\\.\\d{1,3}){3}):([0-9]{1,5})/api/enrollment").matchEntire(invitation.url) ?: error("Enrollment requires a private LAN HTTPS address.")
    val ip = match.groupValues[1].split('.').map { it.toInt() }
    require(ip.all { it in 0..255 } && (ip[0] == 10 || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 192 && ip[1] == 168)) && match.groupValues[2].toInt() in 1..65535) { "Enrollment requires your home LAN." }
    require(invitation.ca.length in 100..12000 && invitation.ca.startsWith("-----BEGIN CERTIFICATE-----") && !invitation.ca.contains("PRIVATE KEY")) { "Invalid server certificate." }
    require(invitation.role in setOf("owner", "admin", "member", "viewer") && invitation.user.length in 1..64) { "Invalid account scope." }
    require(invitation.expires > now && invitation.expires <= now + 660) { "Invitation expired, or your phone's clock is incorrect. Ask for a new invitation." }
    if(invitation.version==2) {
        val tunnel=invitation.tunnel ?: error("This invitation is missing its restricted tunnel.")
        require(tunnel.keys==setOf("address","endpoint","alternate_endpoints","server_key","preshared_key","api","public_key")) { "Unexpected enrollment tunnel fields." }
        require(tunnel.text("api")+"/api/enrollment"==invitation.url && match.groupValues[2]=="8443") { "Enrollment must use the private task API." }
        val local=tunnel.text("address").removeSuffix("/32").split('.').mapNotNull { it.toIntOrNull() }
        require(tunnel.text("address").endsWith("/32") && local.size==4 && local.all { it in 0..255 } && local.take(3)==ip.take(3) && ip.last()==1 && local.last() in 2..254) { "Invalid restricted phone address." }
        for(field in listOf("server_key","preshared_key","public_key")) {
            val bytes=Base64.decode(tunnel.text(field));require(bytes.size==32 && bytes.any { it.toInt()!=0 } && Base64.encode(bytes)==tunnel.text(field)) { "Invalid enrollment key." }
        }
        val endpoints=listOf(tunnel.text("endpoint"))+(tunnel["alternate_endpoints"] as? JsonArray ?: error("Invalid enrollment endpoints.")).map { it.jsonPrimitive.content }
        require(endpoints.size<=3 && endpoints.all { it.length<=260 && Regex("[A-Za-z0-9.\\-\\[\\]:]+:[0-9]{1,5}").matches(it) && (it.substringAfterLast(':').toIntOrNull() ?: 0) in 1..65535 }) { "Invalid WireGuard endpoint." }
    }
    return invitation
}

/** Private key is attached locally; the QR contains only the phone's public key. */
fun Invitation.tunnelConfiguration(keys:JsonObject):JsonObject {
    val wireguard=tunnel ?: error("A restricted tunnel invitation is required.")
    require(version==2 && wireguard.text("public_key")==keys.text("public_key")) { "This invitation belongs to another phone. Use this phone's public key on your PC." }
    return JsonObject((wireguard-"public_key")+mapOf("ca_pem" to ca.j(),"private_key" to keys.getValue("private_key")))
}
