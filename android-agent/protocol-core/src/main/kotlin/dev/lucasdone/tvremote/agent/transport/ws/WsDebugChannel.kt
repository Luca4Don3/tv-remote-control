package dev.lucasdone.tvremote.agent.transport.ws

import dev.lucasdone.tvremote.agent.model.CommandAck
import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.TextAction
import dev.lucasdone.tvremote.agent.protocol.Hex
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.ProtocolCodec
import dev.lucasdone.tvremote.agent.protocol.ProtocolEnvelope
import dev.lucasdone.tvremote.agent.protocol.ProtocolException
import dev.lucasdone.tvremote.agent.protocol.jsonBoolean
import dev.lucasdone.tvremote.agent.protocol.jsonLong
import dev.lucasdone.tvremote.agent.protocol.jsonObject
import dev.lucasdone.tvremote.agent.protocol.jsonString
import dev.lucasdone.tvremote.agent.protocol.requireLong
import dev.lucasdone.tvremote.agent.protocol.requireString
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** 已配对凭据快照（核心层不持有 Android Keystore 依赖）。 */
class WsDebugChannelCredential(
    val controllerId: String,
    val controllerName: String,
    val secret: ByteArray,
)

/** 已配对凭据查询（app 层实现接 KeystoreCredentialStore）。 */
interface WsDebugCredentialLookup {
    fun getActive(controllerId: String): WsDebugChannelCredential?
}

/** 命令执行接口（app 层实现接 CommandDispatcher/TextCommandDispatcher）。 */
interface WsDebugCommandHandler {
    fun keyEvent(sequence: Long, key: LogicalKey, state: KeyState, repeatCount: Int): CommandAck
    fun text(sequence: Long, action: TextAction, text: String): CommandAck

    /** 连接关闭时释放按住的键（对齐 TLS 服务端语义）。 */
    fun onConnectionClosed() {}
}

/**
 * WS 调试通道协议核心（纯 JVM；单连接 serve）：
 * HTTP 升级 → 明文 ws_hello（controllerId+clientRandom）→ 凭据查询 → ws_hello_ack
 * → 双向密钥派生 → 加密信封循环（counter 从 1 起、防重放、sessionId 逐字校验、
 * 15s 加密保活 ping、帧级 ping/pong、限速、严格递增命令序号）。
 *
 * Android 依赖零耦合：凭据查询与命令执行通过接口注入；app 层负责线程池与日志。
 */
class WsDebugChannel(
    private val credentials: WsDebugCredentialLookup,
    private val handlerFactory: () -> WsDebugCommandHandler,
) {
    private val rateLimiter = RateLimiter()

    /** 单连接完整服务（握手 + 控制循环）。正常返回表示对端关闭/超时；协议违例上抛。 */
    fun serve(socket: Socket) {
        val handler = handlerFactory()
        try {
            handshake(socket)
            socket.soTimeout = HEARTBEAT_TIMEOUT_MS.toInt()
            val session = debugHandshake(socket) ?: return
            controlLoop(socket, session, handler)
        } finally {
            handler.onConnectionClosed()
        }
    }

    /**
     * HTTP 协议升级握手；不合法即抛 [ProtocolException]。
     *
     * 必须**逐字节**读到头终止符（\r\n\r\n）为止：带缓冲的 Reader 会预读升级后
     * 紧随的 WS 帧字节（如 client 的 ws_hello），缓冲随 reader 丢弃造成帧蒸发
     * （client 端同类问题已在 upgrade() 规避，此处为服务端对称修复）。
     */
    private fun handshake(socket: Socket) {
        socket.soTimeout = PRE_AUTH_TIMEOUT_MS
        val input = socket.inputStream
        val header = StringBuilder()
        var state = 0 // 匹配 "\r\n\r\n" 的进度
        while (state < 4) {
            val b = input.read()
            if (b < 0) throw ProtocolException("truncated upgrade request")
            header.append(b.toChar())
            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> 4
                b == 13 -> 1
                else -> 0
            }
        }
        val lines = header.toString().split("\r\n")
        val requestLine = lines.firstOrNull() ?: throw ProtocolException("empty upgrade request")
        if (!requestLine.startsWith("GET ")) throw ProtocolException("not a websocket upgrade")
        var webSocketKey: String? = null
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name == "sec-websocket-key") webSocketKey = value
        }
        val key = webSocketKey ?: throw ProtocolException("missing Sec-WebSocket-Key")
        val accept = java.util.Base64.getEncoder()
            .encodeToString(sha1("$key$WS_GUID".toByteArray(Charsets.US_ASCII)))
        socket.outputStream.apply {
            write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            flush()
        }
    }

    /** 明文 `ws_hello` → 校验已配对 → `ws_hello_ack`。拒绝返回 null。 */
    private fun debugHandshake(socket: Socket): DebugSession? {
        val incoming = WsFrameCodec.read(socket.inputStream) ?: return null
        if (incoming.opcode != WsFrameCodec.OPCODE_TEXT) return null
        val envelope = ProtocolCodec.decode(incoming.payload)
        if (envelope.type != "ws_hello" || envelope.sessionId.isNotEmpty()) return null
        val controllerId = envelope.payload.requireString("controllerId", 32)
        val clientRandomHex = envelope.payload.requireString("clientRandom", 64)
        if (!controllerId.matches(Regex("[0-9a-f]{32}"))) return null
        val clientRandom = Hex.decode(clientRandomHex, expectedBytes = 32)

        val credential = credentials.getActive(controllerId) ?: return null
        val serverRandom = DebugSessionCrypto.randomBytes(DebugSessionCrypto.RANDOM_BYTES)
        val keys = DebugSessionCrypto.deriveSessionKeys(
            psk = credential.secret,
            clientRandom = clientRandom,
            serverRandom = serverRandom,
        )
        val ack = ProtocolCodec.encode(
            ProtocolEnvelope(
                protocolVersion = ProtocolCodec.VERSION,
                requestId = envelope.requestId,
                sessionId = controllerId,
                sequence = envelope.sequence + 1,
                type = "ws_hello_ack",
                payload = jsonObject(
                    "serverRandom" to jsonString(Hex.encode(serverRandom)),
                    "authenticated" to jsonBoolean(true),
                ),
            ),
        )
        WsFrameCodec.writeText(socket.outputStream, String(ack, Charsets.UTF_8))
        return DebugSession(
            controllerId = controllerId,
            serverCipher = DebugSessionCrypto.DirectionCipher(keys.serverToClient),
            clientCipher = DebugSessionCrypto.DirectionCipher(keys.clientToServer),
            replay = ReplayWindow(REPLAY_WINDOW_BITS),
        )
    }

    private fun controlLoop(socket: Socket, session: DebugSession, handler: WsDebugCommandHandler) {
        var lastPingAtMs = elapsedMs()
        while (true) {
            val frame = try {
                WsFrameCodec.read(socket.inputStream) ?: return
            } catch (_: WsFrameCodec.ClosedException) {
                return
            }
            when (frame.opcode) {
                WsFrameCodec.OPCODE_PING -> WsFrameCodec.writePong(socket.outputStream, frame.payload)
                WsFrameCodec.OPCODE_TEXT, WsFrameCodec.OPCODE_BINARY -> {
                    if (elapsedMs() - lastPingAtMs >= HEARTBEAT_INTERVAL_MS) {
                        // 加密信封是二进制载荷：TEXT 帧的合法 UTF-8 约束会被客户端解码器拒
                        WsFrameCodec.write(socket.outputStream, WsFrameCodec.OPCODE_BINARY, serverHelloPing(session))
                        lastPingAtMs = elapsedMs()
                    }
                    handleEncryptedMessage(socket, session, frame.payload, handler) ?: return
                }
                else -> return
            }
        }
    }

    /** 加密 ping：每 15s 向客户端发送（fire-and-forget 探活，无需应答）。 */
    private fun serverHelloPing(session: DebugSession): ByteArray {
        val counter = session.serverCounter
        session.serverCounter += 1
        val envelope = ProtocolCodec.encode(
            ProtocolEnvelope(
                protocolVersion = ProtocolCodec.VERSION,
                requestId = "ws-ping-$counter",
                sessionId = session.controllerId,
                sequence = counter + 1,
                type = "ping",
                payload = jsonObject(),
            ),
        )
        return session.serverCipher.seal(envelope)
    }

    /** 解密一条加密信封并分发；返回 null 表示客户端断开。 */
    private fun handleEncryptedMessage(
        socket: Socket,
        session: DebugSession,
        payload: ByteArray,
        handler: WsDebugCommandHandler,
    ): Unit? {
        val counter = DebugSessionCrypto.DirectionCipher.readCounter(payload)
        if (!session.replay.checkAndAccept(counter)) {
            throw ProtocolException("replayed debug message")
        }
        val plaintext = session.clientCipher.open(payload, counter)
        val message = ProtocolCodec.decode(plaintext)
        if (message.sessionId != session.controllerId) {
            throw ProtocolException("sessionId mismatch on debug channel")
        }
        val ack = when (message.type) {
            // agent 对 client 的 ping/capabilities_request 不回 ack（fire-and-forget 语义）
            "ping" -> {
                requireFields(message.payload, emptySet())
                null
            }
            "capabilities_request" -> {
                requireFields(message.payload, emptySet())
                null
            }
            "key_event" -> encodeAck(message, dispatchKeyEventAck(message, handler))
            "text_commit" -> encodeAck(message, dispatchTextAck(message, TextAction.COMMIT, handler))
            "text_draft" -> encodeAck(message, dispatchTextAck(message, TextAction.DRAFT, handler))
            "disconnect" -> return null
            else -> throw ProtocolException("unknown debug message type: ${message.type}")
        }
        ack?.let { ackBytes ->
            session.serverCounter += 1
            val sealed = session.serverCipher.seal(ackBytes)
            WsFrameCodec.write(socket.outputStream, WsFrameCodec.OPCODE_BINARY, sealed)
        }
        return Unit
    }

    private fun dispatchKeyEvent(message: ProtocolEnvelope, handler: WsDebugCommandHandler): ByteArray {
        return encodeAck(message, dispatchKeyEventAck(message, handler))
    }

    private fun dispatchKeyEventAck(message: ProtocolEnvelope, handler: WsDebugCommandHandler): CommandAck {
        requireFields(message.payload, setOf("key", "state"), setOf("repeatCount"))
        if (!rateLimiter.allow()) {
            return CommandAck(message.sequence, AckStatus.REJECTED, "rate limit exceeded")
        }
        val key = enumValueOrNull<LogicalKey>(message.payload.requireString("key", 32))
        val state = enumValueOrNull<KeyState>(message.payload.requireString("state", 16))
        val repeatCount = if (message.payload["repeatCount"] == null) 0 else message.payload.requireLong("repeatCount")
        return if (key == null || state == null || repeatCount !in 0..MAX_REPEAT_COUNT.toLong()) {
            CommandAck(message.sequence, AckStatus.REJECTED, "invalid key event")
        } else {
            handler.keyEvent(message.sequence, key, state, repeatCount.toInt())
        }
    }

    private fun dispatchText(message: ProtocolEnvelope, action: TextAction, handler: WsDebugCommandHandler): ByteArray {
        return encodeAck(message, dispatchTextAck(message, action, handler))
    }

    private fun dispatchTextAck(message: ProtocolEnvelope, action: TextAction, handler: WsDebugCommandHandler): CommandAck {
        requireFields(message.payload, setOf("text"))
        if (!rateLimiter.allow()) {
            return CommandAck(message.sequence, AckStatus.REJECTED, "rate limit exceeded")
        }
        val text = message.payload.requireString("text", MAX_TEXT_LENGTH)
        return if (text.isEmpty()) {
            CommandAck(message.sequence, AckStatus.REJECTED, "text must not be empty")
        } else {
            handler.text(message.sequence, action, text)
        }
    }

    private fun encodeAck(message: ProtocolEnvelope, ack: CommandAck): ByteArray {
        val fields = linkedMapOf<String, JsonValue>(
            "commandSequence" to jsonLong(ack.sequence),
            "status" to jsonString(ack.status.name),
        )
        ack.reason?.let { fields["reason"] = jsonString(it) }
        return ProtocolCodec.encode(
            ProtocolEnvelope(
                protocolVersion = ProtocolCodec.VERSION,
                requestId = message.requestId,
                sessionId = message.sessionId,
                sequence = message.sequence + 1,
                type = "command_ack",
                payload = JsonValue.ObjectValue(fields),
            ),
        )
    }

    private fun requireFields(
        payload: JsonValue.ObjectValue,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        if (!payload.fields.keys.containsAll(required) || payload.fields.keys.any { it !in required && it !in optional }) {
            throw ProtocolException("payload fields do not match message schema")
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().find { it.name == value }

    private class RateLimiter {
        private var windowStartMs = elapsedMs()
        private var count = 0

        @Synchronized
        fun allow(): Boolean {
            val now = elapsedMs()
            if (now - windowStartMs >= COMMAND_RATE_WINDOW_MS) {
                windowStartMs = now
                count = 0
            }
            if (count >= MAX_COMMANDS_PER_WINDOW) return false
            count += 1
            return true
        }
    }

    class DebugSession internal constructor(
        val controllerId: String,
        val serverCipher: DebugSessionCrypto.DirectionCipher,
        val clientCipher: DebugSessionCrypto.DirectionCipher,
        val replay: ReplayWindow,
    ) {
        var serverCounter: Long = 0L
    }

    companion object {
        const val DEBUG_PORT = 47833
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val PRE_AUTH_TIMEOUT_MS = 10_000
        private const val HEARTBEAT_TIMEOUT_MS = 45_000L
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val COMMAND_RATE_WINDOW_MS = 1_000L
        private const val MAX_COMMANDS_PER_WINDOW = 60
        private const val MAX_REPEAT_COUNT = 10_000
        private const val MAX_TEXT_LENGTH = 4 * 1024
        private const val REPLAY_WINDOW_BITS = 64

        internal fun elapsedMs(): Long = System.nanoTime() / 1_000_000L

        private fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)
    }
}
