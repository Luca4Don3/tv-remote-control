package dev.lucasdone.tvremote.controller.net

import dev.lucasdone.tvremote.agent.protocol.Hex
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.ProtocolCodec
import dev.lucasdone.tvremote.agent.protocol.ProtocolEnvelope
import dev.lucasdone.tvremote.agent.protocol.jsonLong
import dev.lucasdone.tvremote.agent.protocol.jsonObject
import dev.lucasdone.tvremote.agent.protocol.jsonString
import dev.lucasdone.tvremote.agent.protocol.requireLong
import dev.lucasdone.tvremote.agent.protocol.requireString
import dev.lucasdone.tvremote.agent.transport.ws.DebugSessionCrypto
import dev.lucasdone.tvremote.agent.transport.ws.ReplayWindow
import dev.lucasdone.tvremote.agent.transport.ws.WsFrameCodec
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * WS 调试通道客户端（对端：agent 明文 WS 端口 47833）。
 * 安全模型与 agent 端 WebSocketDebugServer 对齐：
 * 明文 WS + 应用层端到端加密（HKDF+AES-GCM+防重放）。
 */
class WsDebugClient(
    host: String,
    private val controllerId: String,
    private val secret: ByteArray,
    port: Int = DEBUG_PORT,
) : AutoCloseable {
    private val socket = Socket()
    private val replay = ReplayWindow(64)
    private val random = SecureRandom()
    private var serverCipher: DebugSessionCrypto.DirectionCipher? = null
    private var clientCipher: DebugSessionCrypto.DirectionCipher? = null
    private var serverCounter = 0L
    private var outboundSequence = 0L
    private val writeLock = Any()
    private val heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tvrc-ws-heartbeat").apply { isDaemon = true }
    }

    init {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        upgrade()
        val clientRandom = DebugSessionCrypto.randomBytes(DebugSessionCrypto.RANDOM_BYTES)
        val hello = ProtocolCodec.encode(
            ProtocolEnvelope(
                protocolVersion = ProtocolCodec.VERSION,
                requestId = nextRequestId(),
                sessionId = "",
                sequence = 1,
                type = "ws_hello",
                payload = jsonObject(
                    "controllerId" to jsonString(controllerId),
                    "clientRandom" to jsonString(Hex.encode(clientRandom)),
                ),
            ),
        )
        WsFrameCodec.writeClient(socket.outputStream, WsFrameCodec.OPCODE_TEXT, hello)
        val ackFrame = WsFrameCodec.read(socket.inputStream) ?: throw IOException("closed during hello")
        val ack = ProtocolCodec.decode(ackFrame.payload)
        if (ack.type != "ws_hello_ack") throw IOException("unexpected handshake reply: ${ack.type}")
        val serverRandom = Hex.decode(ack.payload.requireString("serverRandom", 64))
        val keys = DebugSessionCrypto.deriveSessionKeys(secret, clientRandom, serverRandom)
        clientCipher = DebugSessionCrypto.DirectionCipher(keys.clientToServer)
        serverCipher = DebugSessionCrypto.DirectionCipher(keys.serverToClient)
        // 15s 加密心跳：agent 端读超时 45s，闲置也会保持链路活跃
        heartbeat.scheduleWithFixedDelay({
            try {
                sendCommand("ping", jsonObject())
            } catch (_: Exception) {
                runCatching { close() }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun sendKeyEvent(key: String, state: String, repeatCount: Int = 0): Boolean {
        val ack = sendCommand(
            "key_event",
            jsonObject(
                "key" to jsonString(key),
                "state" to jsonString(state),
                "repeatCount" to jsonLong(repeatCount.toLong()),
            ),
        )
        return ack.status == "SUCCESS"
    }

    fun sendText(text: String, draft: Boolean): Boolean {
        val ack = sendCommand(if (draft) "text_draft" else "text_commit", jsonObject("text" to jsonString(text)))
        return ack.status == "SUCCESS"
    }

    private fun sendCommand(type: String, payload: JsonValue.ObjectValue): Ack {
        val message = ProtocolCodec.encode(
            ProtocolEnvelope(
                protocolVersion = ProtocolCodec.VERSION,
                requestId = nextRequestId(),
                sessionId = controllerId,
                sequence = 1,
                type = type,
                payload = payload,
            ),
        )
        val sealed = synchronized(writeLock) {
            val cipher = clientCipher ?: throw IllegalStateException("not ready")
            val counter = outboundSequence
            outboundSequence += 1
            cipher.seal(message, aad(counter))
        }
        WsFrameCodec.writeClient(socket.outputStream, WsFrameCodec.OPCODE_BINARY, sealed)
        while (true) {
            val frame = WsFrameCodec.read(socket.inputStream) ?: throw IOException("connection closed")
            if (frame.opcode == WsFrameCodec.OPCODE_PING) {
                WsFrameCodec.writePong(socket.outputStream, frame.payload)
                continue
            }
            if (frame.opcode != WsFrameCodec.OPCODE_BINARY) continue
            val replyCounter = DebugSessionCrypto.DirectionCipher.readCounter(frame.payload)
            if (!replay.checkAndAccept(replyCounter)) throw IOException("replayed debug message")
            val plaintext = (serverCipher ?: throw IllegalStateException("not ready"))
                .open(frame.payload, replyCounter)
            val reply = ProtocolCodec.decode(plaintext)
            if (reply.type == "ping") continue
            if (reply.type != "command_ack") continue
            return Ack(
                reply.payload.requireLong("commandSequence"),
                reply.payload.requireString("status", 32),
            )
        }
    }

    /** HTTP 升级握手（Sec-WebSocket-Key = 随机 16B base64）。 */
    private fun upgrade() {
        val keyBytes = ByteArray(16).also(random::nextBytes)
        val key = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
        val request = (
            "GET / HTTP/1.1\r\n" +
                "Host: ${socket.inetAddress.hostAddress}\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            )
        socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
        socket.outputStream.flush()
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.US_ASCII))
        val status = reader.readLine() ?: throw IOException("no handshake response")
        if (!status.contains("101")) throw IOException("handshake rejected: $status")
        while (true) {
            val line = reader.readLine() ?: throw IOException("truncated handshake")
            if (line.isEmpty()) break
        }
    }

    private fun nextRequestId(): String = "c-${System.nanoTime()}"

    private fun aad(counter: Long): ByteArray =
        ByteArray(8).also { buf -> for (i in 0 until 8) buf[i] = (counter ushr ((7 - i) * 8)).toByte() }

    override fun close() {
        heartbeat.shutdownNow()
        runCatching { socket.close() }
    }

    data class Ack(val sequence: Long, val status: String) {
        val isSuccess: Boolean get() = status == "SUCCESS"
    }

    companion object {
        const val DEBUG_PORT = 47833
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val READ_TIMEOUT_MS = 45_000

        fun sha1Base64(data: ByteArray): String =
            android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(data), android.util.Base64.NO_WRAP)
    }
}
