package org.fieldnotes.platform

import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
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
    @JvmStatic external fun bootstrap(input: ByteArray): ByteArray
    @JvmStatic external fun stop()
}
class AndroidTransport : Transport {
    private val gate = Mutex()
    private val generation=AtomicLong(0)
    private inline fun <T> native(block:()->T):T=try{block()}catch(e:LinkageError){throw IllegalStateException("Private transport could not load. Your saved data is kept; use Android app settings for recovery.",e)}
    private var peerKey: String? = null
    private fun decode(bytes: ByteArray): JsonObject {
        val json = WireJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        if ("error" in json) throw IllegalStateException(json.text("error"))
        return json
    }
    override suspend fun keys(): JsonObject = withContext(Dispatchers.IO) { native { decode(NativeBridge.keys()) } }
    override suspend fun configure(configuration: JsonObject) {
        val stamp=generation.get()
        withContext(Dispatchers.IO) { gate.withLock {
            check(stamp==generation.get()){"Private connection stopped"}
            native { decode(NativeBridge.configure(configuration.toString().encodeToByteArray())) }
            if(stamp!=generation.get()){native { NativeBridge.stop() };error("Private connection stopped")}
            peerKey = configuration.text("server_key")
        } }
    }
    override suspend fun exchange(path: String, token: String?, body: JsonObject?, serverKey: String?): Reply {
        val stamp=generation.get()
        return withContext(Dispatchers.IO) {
            gate.withLock {
                check(stamp==generation.get()) { "Private connection stopped" }
                check(serverKey != null && serverKey == peerKey) { "The server connection changed. Retry from the current workspace." }
                val request = obj("method" to (if (body == null) "GET" else "POST").j(), "path" to path.j(), "token" to (token ?: "").j(), "body" to (body?.toString() ?: "").j())
                val json = native { decode(NativeBridge.exchange(request.toString().encodeToByteArray())) }
                check(stamp==generation.get()) { "Private connection stopped; a transmitted change may already have reached home" }
                Reply(json.number("status").toInt(), json.text("body"), json["binary"]?.jsonPrimitive?.contentOrNull)
            }
        }
    }
    override suspend fun bootstrap(url: String, ca: String, path: String, body: JsonObject): Reply = withContext(Dispatchers.IO) {
        val input = obj("url" to url.j(), "ca" to ca.j(), "path" to path.j(), "body" to body.toString().j())
        val result = native { decode(NativeBridge.bootstrap(input.toString().encodeToByteArray())) }
        Reply(result.number("status").toInt(), result.text("body"))
    }
    override suspend fun stop() {
        generation.incrementAndGet()
        withContext(Dispatchers.IO) {
            native { NativeBridge.stop() } // Cancel active exchange before waiting for its Kotlin gate.
            gate.withLock { native { NativeBridge.stop() }; peerKey = null }
        }
    }
    suspend fun image(id: String, token: String, serverKey: String): ByteArray {
        val response = exchange("/api/images/$id", token, serverKey = serverKey)
        check(response.status == 200 && response.binary != null) { "Image unavailable. Reconnect and retry." }
        return Base64.getDecoder().decode(response.binary)
    }
}
