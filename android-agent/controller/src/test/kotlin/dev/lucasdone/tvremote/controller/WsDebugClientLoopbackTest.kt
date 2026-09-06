package dev.lucasdone.tvremote.controller

import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.protocol.Hex
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
import dev.lucasdone.tvremote.controller.net.WsDebugClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WS 调试通道 JVM 回环门禁：真实 `WsDebugClient` ↔ 按 agent 服务端规范实现的
 * 最小回环服务端（protocol-core 原语），跑在 localhost 真实 socket 上。
 *
 * 锚定（此前仅模拟器冒烟覆盖、CI 跳过）：
 * - 客户端出向帧必须掩码（服务端 `read(expectMasked = true)` 硬校验）
 * - HTTP 升级（Sec-WebSocket-Key/Accept）→ 明文 ws_hello → ws_hello_ack 密钥派生
 * - 加密信封 counter 从 1 起、双端加解密互操作、key_event → command_ack 全链路
 * - 帧级 PING → 掩码 PONG 应答；加密信封 ping 客户端静默消化
 * - 同一加密信封重放被 ReplayWindow 拒绝
 */
class WsDebugClientLoopbackTest {

    /** 回环服务端：复刻 agent WebSocketDebugServer 的协议行为（无 Android 依赖）。 */
    private class LoopbackServer(private val secret: ByteArray) : AutoCloseable {
        val serverSocket = ServerSocket(0)
        val receivedCommands = java.util.Collections.synchronizedList(mutableListOf<String>())
        val replayRejected = AtomicReference<Boolean?>(null)
        val replayRejectedLatch = CountDownLatch(1)
        val pongSeen = CountDownLatch(1)
        val handshakeSeen = CountDownLatch(1)
        private var serverCipher: DebugSessionCrypto.DirectionCipher? = null
        private var clientCipher: DebugSessionCrypto.DirectionCipher? = null
        private val replay = ReplayWindow(64)
        private var lastCommandSequence = 0L
        private val workers = ThreadPoolExecutor(1, 1, 5L, TimeUnit.SECONDS, ArrayBlockingQueue(2)) {
            Thread(it, "tvrc-loopback-server").apply { isDaemon = true }
        }
        private var client: Socket? = null

        fun start() {
            workers.execute {
                try {
                    serve()
                } catch (_: Exception) {
                    // 正常退出路径：client 断开或读超时
                }
            }
        }

        private fun serve() {
            val socket = serverSocket.accept()
            client = socket
            socket.soTimeout = 10_000
            acceptUpgrade(socket)
            val hello = readHello(socket)
            handshakeSeen.countDown()

            val serverRandom = DebugSessionCrypto.randomBytes(DebugSessionCrypto.RANDOM_BYTES)
            val keys = DebugSessionCrypto.deriveSessionKeys(
                psk = secret,
                clientRandom = Hex.decode(hello.clientRandomHex, expectedBytes = 32),
                serverRandom = serverRandom,
            )
            serverCipher = DebugSessionCrypto.DirectionCipher(keys.serverToClient)
            clientCipher = DebugSessionCrypto.DirectionCipher(keys.clientToServer)
            val ack = ProtocolCodec.encode(
                ProtocolEnvelope(
                    protocolVersion = ProtocolCodec.VERSION,
                    requestId = hello.requestId,
                    sessionId = "",
                    sequence = 1,
                    type = "ws_hello_ack",
                    payload = jsonObject("serverRandom" to jsonString(Hex.encode(serverRandom))),
                ),
            )
            WsFrameCodec.write(socket.outputStream, WsFrameCodec.OPCODE_TEXT, ack)

            // 帧级 PING（不掩码）→ 期望客户端回掩码 PONG
            WsFrameCodec.write(socket.outputStream, WsFrameCodec.OPCODE_PING, "tvrc".toByteArray())

            // 加密信封 ping（serverHelloPing 语义：envelope type=ping，无应答要求）
            WsFrameCodec.write(socket.outputStream, WsFrameCodec.OPCODE_BINARY, serverCipher!!.seal(pingEnvelope()))

            var firstEnvelope: ByteArray? = null
            while (true) {
                val frame = WsFrameCodec.read(socket.inputStream, expectMasked = true) ?: break
                if (frame.opcode == WsFrameCodec.OPCODE_PONG) {
                    assertEquals("tvrc", String(frame.payload, Charsets.US_ASCII))
                    pongSeen.countDown()
                    continue
                }
                if (frame.opcode != WsFrameCodec.OPCODE_BINARY) continue
                val counter = DebugSessionCrypto.DirectionCipher.readCounter(frame.payload)
                assertTrue("replay window must accept fresh counter", replay.checkAndAccept(counter))
                val plaintext = clientCipher!!.open(frame.payload, counter)
                val envelope = ProtocolCodec.decode(plaintext)
                // agent 真实行为：client 的 ping 不回 ack（handleEncryptedMessage ack=null）
                if (envelope.type == "ping") {
                    receivedCommands.add("ping")
                    continue
                }
                // agent 真实行为：命令序号严格递增（KeyStateTracker/TextCommandDispatcher）
                if (envelope.sequence <= lastCommandSequence) {
                    val rejected = ProtocolCodec.encode(
                        ProtocolEnvelope(
                            protocolVersion = ProtocolCodec.VERSION,
                            requestId = envelope.requestId,
                            sessionId = envelope.sessionId,
                            sequence = envelope.sequence + 1,
                            type = "command_ack",
                            payload = dev.lucasdone.tvremote.agent.protocol.jsonObject(
                                "commandSequence" to dev.lucasdone.tvremote.agent.protocol.jsonLong(envelope.sequence),
                                "status" to dev.lucasdone.tvremote.agent.protocol.jsonString("REJECTED"),
                            ),
                        ),
                    )
                    WsFrameCodec.write(socket.outputStream, WsFrameCodec.OPCODE_BINARY, serverCipher!!.seal(rejected))
                    continue
                }
                lastCommandSequence = envelope.sequence
                if (firstEnvelope == null) {
                    firstEnvelope = frame.payload
                } else {
                    // 端到端重放：同一加密信封字节再次进入防重放窗口（模拟捕获重放），必须被拒
                    val replayedCounter = DebugSessionCrypto.DirectionCipher.readCounter(firstEnvelope!!)
                    replayRejected.set(!replay.checkAndAccept(replayedCounter))
                    replayRejectedLatch.countDown()
                }
                receivedCommands.add(envelope.type)

                val status = if (envelope.type == "key_event") "SUCCESS" else "SUCCESS"
                val commandAck = ProtocolCodec.encode(
                    ProtocolEnvelope(
                        protocolVersion = ProtocolCodec.VERSION,
                        requestId = envelope.requestId,
                        sessionId = envelope.sessionId,
                        sequence = envelope.sequence + 1,
                        type = "command_ack",
                        payload = jsonObject(
                            "commandSequence" to jsonLong(envelope.sequence),
                            "status" to jsonString(status),
                        ),
                    ),
                )
                WsFrameCodec.write(socket.outputStream, WsFrameCodec.OPCODE_BINARY, serverCipher!!.seal(commandAck))
            }
        }

        private fun acceptUpgrade(socket: Socket) {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.US_ASCII))
            var key: String? = null
            while (true) {
                val line = reader.readLine() ?: throw IllegalStateException("truncated upgrade")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("sec-websocket-key", ignoreCase = true)) {
                    key = line.substring(colon + 1).trim()
                }
            }
            val accept = java.util.Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest("$key$WS_GUID".toByteArray(Charsets.US_ASCII)),
            )
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

        private fun readHello(socket: Socket): Hello {
            val frame = WsFrameCodec.read(socket.inputStream, expectMasked = true)
                ?: throw IllegalStateException("no hello")
            assertTrue(frame.opcode == WsFrameCodec.OPCODE_TEXT)
            val envelope = ProtocolCodec.decode(frame.payload)
            assertTrue(envelope.type == "ws_hello")
            assertTrue("sessionId must be empty in ws_hello", envelope.sessionId.isEmpty())
            val controllerId = envelope.payload.requireString("controllerId", 32)
            assertTrue("controllerId must be 32 hex", controllerId.matches(Regex("[0-9a-f]{32}")))
            return Hello(envelope.requestId, envelope.payload.requireString("clientRandom", 64))
        }

        private fun pingEnvelope(): ByteArray = ProtocolCodec.encode(
            ProtocolEnvelope(
                protocolVersion = ProtocolCodec.VERSION,
                requestId = "ws-ping-1",
                sessionId = "0".repeat(32),
                sequence = 2,
                type = "ping",
                payload = jsonObject(),
            ),
        )

        override fun close() {
            runCatching { client?.close() }
            serverSocket.close()
            workers.shutdownNow()
        }

        private data class Hello(val requestId: String, val clientRandomHex: String)

        companion object {
            private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        }
    }

    private fun newControllerId(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return Hex.encode(bytes)
    }

    private fun newSecret(): ByteArray = DebugSessionCrypto.randomBytes(32)

    @Test
    fun loopbackMaskedFramesHandshakeEncryptionAndAck() {
        // 配对后双端各持一份 secret/controllerId——回环里共享同一凭据
        val secret = newSecret()
        val controllerId = newControllerId()
        val server = LoopbackServer(secret)
        server.start()
        try {
            val client = WsDebugClient("127.0.0.1", controllerId, secret, server.serverSocket.localPort)
            try {
                assertTrue("handshake must complete", server.handshakeSeen.await(5, TimeUnit.SECONDS))

                // client 无独立读线程：帧级 PING 的掩码 PONG 应答在首个 sendCommand 读循环中处理
                assertTrue(client.sendKeyEvent(LogicalKey.DPAD_UP.name, "UP"))
                assertTrue(client.sendText("回环测试文本", draft = false))
                assertEquals(listOf("key_event", "text_commit"), server.receivedCommands.toList())
                assertTrue("pong (masked) must reach the server", server.pongSeen.await(5, TimeUnit.SECONDS))
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun replayedEnvelopeRejectedByServerReplayWindow() {
        val secret = newSecret()
        val controllerId = newControllerId()
        val server = LoopbackServer(secret)
        server.start()
        try {
            val client = WsDebugClient("127.0.0.1", controllerId, secret, server.serverSocket.localPort)
            try {
                assertTrue(server.handshakeSeen.await(5, TimeUnit.SECONDS))
                // 第一条命令正常入库；第二条触发服务端对第一条信封的重放判定
                assertTrue(client.sendKeyEvent(LogicalKey.DPAD_DOWN.name, "DOWN"))
                assertTrue(client.sendKeyEvent(LogicalKey.DPAD_DOWN.name, "DOWN"))
                assertTrue(server.replayRejectedLatch.await(5, TimeUnit.SECONDS))
                assertEquals(
                    "replayed envelope must be rejected by the replay window",
                    true,
                    server.replayRejected.get(),
                )
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }


    /** 并发命令（等价心跳与 UI 同时发的场景）：请求/响应必须严格不错配。 */
    @Test
    fun concurrentCommandsNeverCrossWire() {
        val secret = newSecret()
        val controllerId = newControllerId()
        val server = LoopbackServer(secret)
        server.start()
        try {
            val client = WsDebugClient("127.0.0.1", controllerId, secret, server.serverSocket.localPort)
            try {
                assertTrue(server.handshakeSeen.await(5, TimeUnit.SECONDS))
                val threads = 4
                val perThread = 6
                val startGate = CountDownLatch(1)
                val failures = java.util.concurrent.atomic.AtomicInteger(0)
                val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
                val total = threads * perThread
                val done = CountDownLatch(threads)
                val workers = (0 until threads).map { t ->
                    Thread {
                        try {
                            startGate.await()
                            repeat(perThread) { i ->
                                val key = if (t % 2 == 0) LogicalKey.DPAD_UP.name else LogicalKey.DPAD_DOWN.name
                                try {
                                    val ok = client.sendKeyEvent(key, if (i % 2 == 0) "DOWN" else "UP")
                                    if (!ok) errors.add("ack-not-success t$t#$i")
                                } catch (e: Exception) {
                                    errors.add("t$t#$i => ${e::class.simpleName}: ${e.message}")
                                    failures.incrementAndGet()
                                }
                            }
                        } catch (e: Exception) {
                            failures.incrementAndGet()
                            errors.add("worker$t => ${e::class.simpleName}: ${e.message}")
                        } finally {
                            done.countDown()
                        }
                    }.apply { isDaemon = true }
                }
                workers.forEach(Thread::start)
                startGate.countDown()
                assertTrue("all concurrent commands must finish well before the 45s read timeout", done.await(20, TimeUnit.SECONDS))
                assertEquals("并发命令应全部成功：${errors.joinToString("; ")}", 0, failures.get())
                // 回环服务端必须恰好收到 total 条 key_event（帧完好、无交错损坏）
                val deadline = System.currentTimeMillis() + 5_000
                while (server.receivedCommands.size < total && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                assertEquals(total, server.receivedCommands.size)
                assertTrue(server.receivedCommands.all { it == "key_event" })
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }
}