package dev.lucasdone.tvremote.agent.transport

import android.util.Log
import dev.lucasdone.tvremote.agent.protocol.Hex
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.StrictJson
import dev.lucasdone.tvremote.agent.protocol.jsonLong
import dev.lucasdone.tvremote.agent.protocol.jsonObject
import dev.lucasdone.tvremote.agent.protocol.jsonString
import dev.lucasdone.tvremote.agent.protocol.requireLong
import dev.lucasdone.tvremote.agent.protocol.requireString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryServer(
    displayName: String,
    private val controlPort: Int = CONTROL_PORT,
    random: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private data class RateWindow(var startedAtMs: Long, var requests: Int)

    private val safeDisplayName = validateDisplayName(displayName)
    private val instanceId = Hex.encode(ByteArray(32).also(random::nextBytes))
    private val running = AtomicBoolean(false)
    // access-order LRU：最久未访问的 IP 条目被淘汰，防止不同 IP 凑满容量后拒绝合法请求。
    private val rateWindows = object : LinkedHashMap<String, RateWindow>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RateWindow>): Boolean =
            size > MAX_RATE_ENTRIES
    }
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val opened = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
            socket = opened
            thread = Thread({ receiveLoop(opened) }, "tvrc-discovery").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            running.set(false)
            socket?.close()
            socket = null
            throw error
        }
    }

    private fun receiveLoop(opened: DatagramSocket) {
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                opened.receive(packet)
                handlePacket(opened, packet)
            } catch (error: SocketException) {
                if (running.get()) Log.e(TAG, "Discovery socket failed", error)
                break
            } catch (error: RuntimeException) {
                Log.w(TAG, "Rejected malformed discovery datagram: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun handlePacket(opened: DatagramSocket, packet: DatagramPacket) {
        if (!isLocalAddress(packet.address) || packet.length <= 0 || packet.length >= MAX_DATAGRAM_BYTES) return
        if (!allow(packet.address)) return
        val request = StrictJson.parseObject(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
        if (request.fields.keys != setOf("protocolVersion", "type")) return
        if (request.requireLong("protocolVersion") != 1L || request.requireString("type", 16) != "probe") return

        val response = StrictJson.encode(
            jsonObject(
                "protocolVersion" to jsonLong(1),
                "type" to jsonString("probe_response"),
                "instanceId" to jsonString(instanceId),
                "displayName" to jsonString(safeDisplayName),
                "controlPort" to jsonLong(controlPort.toLong()),
            ),
        )
        opened.send(DatagramPacket(response, response.size, packet.address, packet.port))
    }

    @Synchronized
    private fun allow(address: InetAddress): Boolean {
        val now = System.nanoTime() / 1_000_000L
        val key = address.hostAddress.orEmpty()
        val window = rateWindows[key]
        if (window == null || now - window.startedAtMs >= RATE_WINDOW_MS) {
            rateWindows[key] = RateWindow(now, 1)
            return true
        }
        if (window.requests >= MAX_REQUESTS_PER_WINDOW) return false
        window.requests += 1
        return true
    }

    @Synchronized
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        socket?.close()
        socket = null
        thread?.interrupt()
        thread = null
        rateWindows.clear()
    }

    companion object {
        const val DISCOVERY_PORT = 47_831
        const val CONTROL_PORT = 47_832
        private const val MAX_DATAGRAM_BYTES = 512
        private const val RATE_WINDOW_MS = 10_000L
        private const val MAX_REQUESTS_PER_WINDOW = 20
        private const val MAX_RATE_ENTRIES = 128
        private const val TAG = "TvrcDiscovery"

        fun isLocalAddress(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            val bytes = address.address
            return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
        }

        private fun validateDisplayName(value: String): String {
            val normalized = value.trim()
            require(normalized.isNotEmpty()) { "displayName must not be empty" }
            require(normalized.toByteArray(StandardCharsets.UTF_8).size <= 64) { "displayName exceeds 64 UTF-8 bytes" }
            require(normalized.none { Character.isISOControl(it) }) { "displayName contains a control character" }
            return normalized
        }
    }
}
