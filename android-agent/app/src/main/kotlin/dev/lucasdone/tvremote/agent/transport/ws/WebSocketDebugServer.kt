package dev.lucasdone.tvremote.agent.transport.ws

import android.util.Log
import dev.lucasdone.tvremote.agent.auth.KeystoreCredentialStore
import dev.lucasdone.tvremote.agent.command.CommandDispatcher
import dev.lucasdone.tvremote.agent.command.KeyStateTracker
import dev.lucasdone.tvremote.agent.command.TextCommandDispatcher
import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.model.TextAction
import dev.lucasdone.tvremote.agent.model.TextCommand
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
import dev.lucasdone.tvremote.agent.protocol.requireObject
import dev.lucasdone.tvremote.agent.protocol.requireString
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 明文 WebSocket 调试通道（端口 47833）。
 *
 * 安全模型：明文传输 + 应用层端到端加密（HKDF+AES-GCM+防重放）。
 * - 仅遥控消息（key_event/text_commit/text_draft/ping/capabilities_request）；
 *   不支持配对、认证协商与媒体，凭证来自已配对控制端。
 * - 握手：明文 `ws_hello`（controllerId + clientRandom）→ 服务端校验已配对 →
 *   `ws_hello_ack`（serverRandom）→ 双方派生双向会话密钥。
 * - 之后每条消息为加密信封 `counter(8B) || ciphertext||tag`，AAD 绑定 counter，
 *   ReplayWindow 拒绝重复/过旧序号。
 * - 本通道仅供开发调试（小程序/无 TLS 环境遥控），UI 与文档显式标注调试边界。
 */
class WebSocketDebugServer(
    private val credentialStore: KeystoreCredentialStore,
    private val dispatcherFactory: () -> CommandDispatcher,
    private val textDispatcherFactory: () -> TextCommandDispatcher,
    private val port: Int = DEBUG_PORT,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(4)) { runnable ->
        Thread(runnable, "tvrc-ws-worker").apply { isDaemon = true }
    }
    private val openSockets = java.util.Collections.synchronizedSet(HashSet<Socket>())

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port), 4)
            serverSocket = socket
        } catch (error: IOException) {
            running.set(false)
            throw error
        }
        val thread = Thread({ acceptLoop() }, "tvrc-ws-accept")
        thread.isDaemon = true
        thread.start()
        acceptThread = thread
        started.set(true)
        Log.i(TAG, "WebSocket debug channel listening on port $port")
    }

    @Synchronized
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        serverSocket?.close()
        serverSocket = null
        synchronized(openSockets) { openSockets.toList() }.forEach { runCatching { it.close() } }
        openSockets.clear()
        workers.shutdownNow()
        workers.awaitTermination(2, TimeUnit.SECONDS)
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                break
            }
            socket.soTimeout = PRE_AUTH_TIMEOUT_MS
            openSockets.add(socket)
            try {
                workers.execute { handleConnection(socket) }
            } catch (_: Exception) {
                runCatching { socket.close() }
                openSockets.remove(socket)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        // dispatcher 提升作用域：断连时无论走到哪一步都要释放按住的键（对齐 TLS 服务端语义）
        var dispatcher: CommandDispatcher? = null
        try {
            val upgraded = handshake(socket) ?: return
            socket.soTimeout = PRE_AUTH_TIMEOUT_MS
            val session = runDebugHandshake(upgraded) ?: return
            socket.soTimeout = HEARTBEAT_TIMEOUT_MS.toInt()
            dispatcher = dispatcherFactory()
            runControlLoop(upgraded, session, dispatcher)
        } catch (_: WsFrameCodec.ClosedException) {
            Unit
        } catch (_: SocketTimeoutException) {
            Log.w(TAG, "WebSocket debug connection timed out")
        } catch (_: SocketException) {
            Unit
        } catch (_: IOException) {
            Unit
        } catch (error: ProtocolException) {
            Log.w(TAG, "Rejected malformed debug channel message: ${error.message}")
        } catch (error: RuntimeException) {
            Log.e(TAG, "WebSocket debug connection failed: ${error.javaClass.simpleName}")
        } finally {
            openSockets.remove(socket)
            dispatcher?.disconnect()
            runCatching { socket.close() }
        }
    }

    /** HTTP 升级握手；不合法即返回 null 断开。 */
    private fun handshake(socket: Socket): Socket? {
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.US_ASCII))
        val requestLine = reader.readLine() ?: return null
        if (!requestLine.startsWith("GET ")) return null
        var webSocketKey: String? = null
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name == "sec-websocket-key") webSocketKey = value
        }
        val key = webSocketKey ?: return null
        val accept = base64(sha1("$key$WS_GUID".toByteArray(Charsets.US_ASCII)))
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
        return socket
    }

    /** 明文 `ws_hello` → 校验已配对 → `ws_hello_ack`。返回 null 拒绝。 */
    private fun runDebugHandshake(socket: Socket): DebugSession? {
        val incoming = WsFrameCodec.read(socket.inputStream) ?: return null
        if (incoming.opcode != WsFrameCodec.OPCODE_TEXT) return null
        val envelope = ProtocolCodec.decode(incoming.payload)
        if (envelope.type != "ws_hello" || envelope.sessionId.isNotEmpty()) return null
        val controllerId = envelope.payload.requireString("controllerId", 32)
        val clientRandomHex = envelope.payload.requireString("clientRandom", 64)
        if (!controllerId.matches(Regex("[0-9a-f]{32}"))) return null
        val clientRandom = Hex.decode(clientRandomHex, expectedBytes = 32)

        val credential = credentialStore.getActive(controllerId) ?: return null
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
        val serverCipher = DebugSessionCrypto.DirectionCipher(keys.serverToClient)
        val clientCipher = DebugSessionCrypto.DirectionCipher(keys.clientToServer)
        return DebugSession(
            controllerId = controllerId,
            controllerName = credential.controllerName,
            serverCipher = serverCipher,
            clientCipher = clientCipher,
            replay = ReplayWindow(REPLAY_WINDOW_BITS),
        )
    }

    private fun runControlLoop(socket: Socket, session: DebugSession, dispatcher: CommandDispatcher) {
        val textDispatcher = textDispatcherFactory()
        val limiter = RateLimiter()
        var lastPingAtMs = elapsedMs()
        while (running.get()) {
            val frame = try {
                WsFrameCodec.read(socket.inputStream) ?: return
            } catch (_: WsFrameCodec.ClosedException) {
                return
            }
            when (frame.opcode) {
                WsFrameCodec.OPCODE_PING -> WsFrameCodec.writePong(socket.outputStream, frame.payload)
                WsFrameCodec.OPCODE_TEXT, WsFrameCodec.OPCODE_BINARY -> {
                    if (elapsedMs() - lastPingAtMs >= HEARTBEAT_INTERVAL_MS) {
                        val ping = serverHelloPing(session)
                        WsFrameCodec.write(socket.outputStream, WsFrameCodec.OPCODE_TEXT, ping)
                        lastPingAtMs = elapsedMs()
                    }
                    handleEncryptedMessage(socket, session, frame.payload, dispatcher, textDispatcher, limiter) ?: return
                }
                else -> return
            }
        }
    }

    /** 加密 ping：每 15s 向客户端发送，防死链 + 保序探活。 */
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
        dispatcher: CommandDispatcher,
        textDispatcher: TextCommandDispatcher,
        limiter: RateLimiter,
    ): Unit? {
        if (payload.size <= DebugSessionCrypto.COUNTER_BYTES + 16) {
            throw ProtocolException("encrypted payload too short")
        }
        val counter = DebugSessionCrypto.DirectionCipher.readCounter(payload)
        Log.i(TAG, "encrypted message: ${payload.size} bytes, counter=$counter")
        if (!session.replay.checkAndAccept(counter)) {
            throw ProtocolException("replayed or stale sequence")
        }
        val plaintext = session.clientCipher.open(payload, counter)
        val message = ProtocolCodec.decode(plaintext)
        if (message.sessionId != session.controllerId) {
            throw ProtocolException("sessionId mismatch on debug channel")
        }
        val ack = when (message.type) {
            "ping" -> {
                requireFields(message.payload, emptySet())
                null
            }
            "capabilities_request" -> {
                requireFields(message.payload, emptySet())
                null
            }
            "key_event" -> dispatchKeyEvent(message, dispatcher, limiter)
            "text_commit" -> dispatchText(message, textDispatcher, limiter, TextAction.COMMIT)
            "text_draft" -> dispatchText(message, textDispatcher, limiter, TextAction.DRAFT)
            "disconnect" -> return null
            else -> throw ProtocolException("unknown debug message type: ${message.type}")
        }
        ack?.let { ackBytes ->
            session.serverCounter += 1
            val sealed = session.serverCipher.seal(ackBytes)
            Log.i(TAG, "sending encrypted ack (${sealed.size} bytes)")
            WsFrameCodec.write(socket.outputStream, WsFrameCodec.OPCODE_BINARY, sealed)
        }
        return Unit
    }

    private fun dispatchKeyEvent(
        message: ProtocolEnvelope,
        dispatcher: CommandDispatcher,
        limiter: RateLimiter,
    ): ByteArray? {
        requireFields(message.payload, setOf("key", "state"), setOf("repeatCount"))
        if (!limiter.allow()) {
            return encodeAck(message, AckStatus.REJECTED, "rate limit exceeded")
        }
        val key = enumValueOrNull<LogicalKey>(message.payload.requireString("key", 32))
        val state = enumValueOrNull<KeyState>(message.payload.requireString("state", 16))
        val repeatCount = if (message.payload["repeatCount"] == null) 0 else message.payload.requireLong("repeatCount")
        val ack = if (key == null || state == null || repeatCount !in 0..MAX_REPEAT_COUNT.toLong()) {
            dev.lucasdone.tvremote.agent.model.CommandAck(message.sequence, AckStatus.REJECTED, "invalid key event")
        } else {
            dispatcher.dispatch(KeyEventCommand(message.sequence, key, state, repeatCount.toInt()))
        }
        return encodeAck(message, ack)
    }

    private fun dispatchText(
        message: ProtocolEnvelope,
        textDispatcher: TextCommandDispatcher,
        limiter: RateLimiter,
        action: TextAction,
    ): ByteArray? {
        requireFields(message.payload, setOf("text"))
        if (!limiter.allow()) {
            return encodeAck(message, AckStatus.REJECTED, "rate limit exceeded")
        }
        val text = message.payload.requireString("text", MAX_TEXT_LENGTH)
        val ack = if (text.isEmpty()) {
            dev.lucasdone.tvremote.agent.model.CommandAck(message.sequence, AckStatus.REJECTED, "text must not be empty")
        } else {
            textDispatcher.dispatch(TextCommand(message.sequence, action, text))
        }
        return encodeAck(message, ack)
    }

    private fun encodeAck(
        message: ProtocolEnvelope,
        status: AckStatus,
        reason: String? = null,
    ): ByteArray = encodeAck(
        message,
        dev.lucasdone.tvremote.agent.model.CommandAck(message.sequence, status, reason),
    )

    private fun encodeAck(message: ProtocolEnvelope, ack: dev.lucasdone.tvremote.agent.model.CommandAck): ByteArray {
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

    private class DebugSession(
        val controllerId: String,
        val controllerName: String,
        val serverCipher: DebugSessionCrypto.DirectionCipher,
        val clientCipher: DebugSessionCrypto.DirectionCipher,
        val replay: ReplayWindow,
    ) {
        var serverCounter: Long = 0L
    }

    companion object {
        private const val TAG = "TvrcWsDebug"
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

        private fun elapsedMs(): Long = System.nanoTime() / 1_000_000L

        private fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

        private fun base64(data: ByteArray): String =
            android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)

        private fun aadOf(counter: Long): ByteArray =
            ByteArray(8).also { buf -> for (i in 0 until 8) buf[i] = (counter ushr ((7 - i) * 8)).toByte() }
    }
}
