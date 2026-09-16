package dev.lucasdone.tvremote.xiaomi.backend

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.StrictJson
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Firmware-specific, loopback-only candidate. A successful probe is not model certification. */
class XiaomiLoopbackClient(
    private val request: (String) -> ByteArray = ::loopbackGet,
) {
    fun probe(): Boolean = succeeded(request("/request?action=isalive"))
    fun press(key: LogicalKey): Boolean {
        val code = KEY_CODES[key] ?: return false
        return succeeded(request("/controller?action=keyevent&keycode=$code"))
    }
    companion object {
        val KEY_CODES = mapOf(
            LogicalKey.DPAD_UP to "up", LogicalKey.DPAD_DOWN to "down",
            LogicalKey.DPAD_LEFT to "left", LogicalKey.DPAD_RIGHT to "right",
            LogicalKey.DPAD_CENTER to "enter", LogicalKey.HOME to "home", LogicalKey.BACK to "back",
            LogicalKey.VOLUME_UP to "volumeup", LogicalKey.VOLUME_DOWN to "volumedown",
        )
        private fun succeeded(bytes: ByteArray): Boolean {
            val value = StrictJson.parseObject(bytes)["status"] as? JsonValue.NumberValue
            return value?.source?.toLongOrNull() == 0L
        }
        private fun loopbackGet(path: String): ByteArray {
            val allowed = path == "/request?action=isalive" || KEY_CODES.values.any {
                path == "/controller?action=keyevent&keycode=$it"
            }
            require(allowed) { "unsupported local Xiaomi request" }
            val connection = URL("http://127.0.0.1:6095$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            try {
                if (connection.responseCode != 200) throw IOException("Xiaomi local service rejected request")
                return connection.inputStream.use { input ->
                    val result = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    val deadline = System.nanoTime() + 2_000_000_000L
                    while (true) {
                        if (System.nanoTime() > deadline) throw IOException("Xiaomi response deadline exceeded")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (result.size() + count > 16_384) throw IOException("Xiaomi response exceeds limit")
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                }
            } finally { connection.disconnect() }
        }
    }
}

class XiaomiKeyBackend(private val client: XiaomiLoopbackClient) : KeyBackend {
    override val kind = BackendKind.XIAOMI
    override val keys = XiaomiLoopbackClient.KEY_CODES.keys
    @Volatile override var available = true
        private set
    override fun press(key: LogicalKey): AckStatus {
        if (key !in keys) return AckStatus.UNSUPPORTED
        if (!available) return AckStatus.EXECUTION_FAILED
        return try {
            if (client.press(key)) AckStatus.SUCCESS else fail()
        } catch (_: IOException) { fail() }
        catch (_: IllegalArgumentException) { fail() }
    }
    private fun fail(): AckStatus { available = false; return AckStatus.EXECUTION_FAILED }
    override fun close() { available = false }
}
