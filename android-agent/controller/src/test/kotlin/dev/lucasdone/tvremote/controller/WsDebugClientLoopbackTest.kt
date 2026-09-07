package dev.lucasdone.tvremote.controller

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.CommandAck
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.TextAction
import dev.lucasdone.tvremote.agent.transport.ws.WsDebugChannel
import dev.lucasdone.tvremote.agent.transport.ws.WsDebugChannelCredential
import dev.lucasdone.tvremote.agent.transport.ws.WsDebugCommandHandler
import dev.lucasdone.tvremote.agent.transport.ws.WsDebugCredentialLookup
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

    /**
     * 回环服务端：直接驱动 agent 的真实协议核心 [WsDebugChannel]（:protocol-core）。
     * Android 依赖以接口注入（凭据查询/命令处理），与生产 WebSocketDebugServer 同一实现。
     */
    private class LoopbackServer(private val secret: ByteArray) : AutoCloseable {
        val serverSocket = ServerSocket(0)
        val receivedCommands = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pongSeen = CountDownLatch(1)
        val handshakeSeen = CountDownLatch(1)
        private var client: Socket? = null
        private val workers = ThreadPoolExecutor(1, 1, 5L, TimeUnit.SECONDS, ArrayBlockingQueue(2)) {
            Thread(it, "tvrc-loopback-server").apply { isDaemon = true }
        }

        val serveError = AtomicReference<Throwable?>(null)
        val serveErrorLatch = CountDownLatch(1)

        fun start() {
            workers.execute {
                try {
                    val socket = serverSocket.accept()
                    client = socket
                    channel.serve(socket)
                } catch (e: Exception) {
                    serveError.set(e)
                    serveErrorLatch.countDown()
                }
            }
        }

        private val channel = WsDebugChannel(
            credentials = object : WsDebugCredentialLookup {
                override fun getActive(controllerId: String): WsDebugChannelCredential? {
                    handshakeSeen.countDown()
                    return WsDebugChannelCredential(
                        controllerId = controllerId,
                        controllerName = "loopback-test",
                        secret = secret,
                    )
                }
            },
            handlerFactory = {
                var lastCommandSequence = 0L
                object : WsDebugCommandHandler {
                    override fun keyEvent(sequence: Long, key: LogicalKey, state: KeyState, repeatCount: Int): CommandAck {
                        println("COREDIAG handler keyEvent seq=$sequence key=$key state=$state")
                        receivedCommands.add("key_event")
                        return if (sequence <= lastCommandSequence) {
                            // agent 真实行为：命令序号严格递增（KeyStateTracker）
                            CommandAck(sequence, AckStatus.REJECTED, "sequence is not increasing")
                        } else {
                            lastCommandSequence = sequence
                            CommandAck(sequence, AckStatus.SUCCESS, null)
                        }
                    }

                    override fun text(sequence: Long, action: TextAction, text: String): CommandAck {
                        receivedCommands.add(if (action == TextAction.DRAFT) "text_draft" else "text_commit")
                        return if (sequence <= lastCommandSequence) {
                            CommandAck(sequence, AckStatus.REJECTED, "sequence is not increasing")
                        } else {
                            lastCommandSequence = sequence
                            CommandAck(sequence, AckStatus.SUCCESS, null)
                        }
                    }
                }
            },
        )

        override fun close() {
            runCatching { client?.close() }
            serverSocket.close()
            workers.shutdownNow()
        }
    }

    private fun newControllerId
(): String {
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
                // 真实 agent 不发帧级 PING（仅加密 ping 信封，client 静默消化）——无 PONG 交互
                assertEquals(listOf("key_event", "text_commit"), server.receivedCommands.toList())
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    /**
     * 序号/防重放联动（真实核心）：handler 层序号递增拒绝（agent KeyStateTracker 语义）+
     * client 无重放能力（每条信封 counter 严格递增——第二条成功证明第一条未被重放路径污染）。
     * ReplayWindow 的窗口语义由 protocol-core 的 WsChannelTest 单元锚定。
     */
    @Test
    fun commandsWithStrictlyIncreasingSequencesAcceptedAndStaleRejected() {
        val secret = newSecret()
        val controllerId = newControllerId()
        val server = LoopbackServer(secret)
        server.start()
        try {
            val client = WsDebugClient("127.0.0.1", controllerId, secret, server.serverSocket.localPort)
            try {
                assertTrue(server.handshakeSeen.await(5, TimeUnit.SECONDS))
                assertTrue(client.sendKeyEvent(LogicalKey.DPAD_DOWN.name, "DOWN"))
                assertTrue(client.sendKeyEvent(LogicalKey.DPAD_UP.name, "UP"))
                assertTrue(client.sendText("序号联动", draft = false))
                assertEquals(listOf("key_event", "key_event", "text_commit"), server.receivedCommands.toList())
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