@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
package org.fieldnotes.domain

import kotlin.io.encoding.Base64
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class Invitation(val version: Int, val url: String, val ca: String, val token: String, val user: String, val role: String, val expires: Long)
@Serializable data class Enrollment(val invitation: Invitation, val claimSecret: String, val name: String, val id: String = "", val verification: String = "", val stage: String = "claiming", val activationSecret: String? = null)

fun readInvitation(raw: String, now: Long = Clock.System.now().epochSeconds): Invitation {
    require(raw.length in 80..20000 && raw.startsWith("fieldnotes://enroll#")) { "Scan or paste a Field Notes invitation from your trusted LAN administrator." }
    val data = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(raw.substringAfter('#')).decodeToString()
    val invitation = WireJson.decodeFromString<Invitation>(data)
    require(invitation.version == 1 && invitation.token.matches(Regex("[a-f0-9]{64}"))) { "Unsupported invitation." }
    val match = Regex("https://(\\d{1,3}(?:\\.\\d{1,3}){3}):([0-9]{1,5})/api/enrollment").matchEntire(invitation.url) ?: error("Enrollment requires a private LAN HTTPS address.")
    val ip = match.groupValues[1].split('.').map { it.toInt() }
    require(ip.all { it in 0..255 } && (ip[0] == 10 || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 192 && ip[1] == 168)) && match.groupValues[2].toInt() in 1..65535) { "Enrollment requires your home LAN." }
    require(invitation.ca.length in 100..12000 && invitation.ca.startsWith("-----BEGIN CERTIFICATE-----") && !invitation.ca.contains("PRIVATE KEY")) { "Invalid server certificate." }
    require(invitation.role in setOf("owner", "admin", "member", "viewer") && invitation.user.length in 1..64) { "Invalid account scope." }
    require(invitation.expires > now && invitation.expires <= now + 660) { "Invitation expired, or your phone's clock is incorrect. Ask for a new invitation." }
    return invitation
}
