package org.fieldnotes.platform

import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

internal object NativeBridge {
    init { System.loadLibrary("fieldnotes"); System.loadLibrary("fieldnotes_jni") }
    @JvmStatic external fun keys(): ByteArray
    @JvmStatic external fun configure(input: ByteArray): ByteArray
    @JvmStatic external fun exchange(input: ByteArray): ByteArray
    @JvmStatic external fun stop()
}
class AndroidTransport : Transport {
    private val gate = Mutex()
    private var peerKey: String? = null
    private fun decode(bytes: ByteArray): JsonObject {
        val json = WireJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        if ("error" in json) throw IllegalStateException(json.text("error"))
        return json
    }
    override suspend fun keys(): JsonObject = withContext(Dispatchers.IO) { decode(NativeBridge.keys()) }
    override suspend fun configure(configuration: JsonObject) { withContext(Dispatchers.IO) { gate.withLock { decode(NativeBridge.configure(configuration.toString().encodeToByteArray())); peerKey = configuration.text("server_key") } } }
    override suspend fun exchange(path: String, token: String?, body: JsonObject?, serverKey: String?): Reply = withContext(Dispatchers.IO) { gate.withLock {
        check(serverKey != null && serverKey == peerKey) { "The server connection changed. Retry from the current workspace." }
        val request = obj("method" to (if (body == null) "GET" else "POST").j(), "path" to path.j(), "token" to (token ?: "").j(), "body" to (body?.toString() ?: "").j())
        val json = decode(NativeBridge.exchange(request.toString().encodeToByteArray()))
        Reply(json.number("status").toInt(), json.text("body"), json["binary"]?.jsonPrimitive?.contentOrNull)
    }
    }
    override suspend fun stop() { withContext(Dispatchers.IO) { gate.withLock { NativeBridge.stop(); peerKey = null } } }
    suspend fun image(id: String, token: String, serverKey: String): ByteArray {
        val response = exchange("/api/images/$id", token, serverKey = serverKey)
        check(response.status == 200 && response.binary != null) { "Image unavailable. Reconnect and retry." }
        return Base64.getDecoder().decode(response.binary)
    }
}
