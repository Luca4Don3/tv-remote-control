package dev.tvremote.controller

import dev.tvremote.agent.protocol.JsonValue
import dev.tvremote.agent.protocol.ProtocolCodec
import dev.tvremote.agent.protocol.ProtocolEnvelope
import dev.tvremote.agent.protocol.jsonLong
import dev.tvremote.agent.protocol.jsonObject
import dev.tvremote.agent.protocol.jsonString
import dev.tvremote.controller.data.CredentialStore
import dev.tvremote.controller.data.DeviceLoad
import dev.tvremote.controller.data.StoredDevice
import dev.tvremote.controller.net.ConnectionTransport
import dev.tvremote.controller.ui.ConnectionPhase
import dev.tvremote.controller.ui.ControllerOrchestrator
import dev.tvremote.controller.ui.PairingChannel
import dev.tvremote.controller.ui.StringProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * R5/R6/R8 真实调用流程测试：用可阻塞的 store/factory 与可控调度器驱动
 * [ControllerOrchestrator]，验证旧任务在挂起恢复后不越权、取消时资源被关闭。
 */
class ControllerOrchestratorTest {

    private class FakeAuthTransport(
        override val peerFingerprint: ByteArray,
        private val authGate: CountDownLatch? = null,
        private val failKeyEvents: Boolean = false,
    ) : ConnectionTransport {
        val outbound = mutableListOf<String>()
        @Volatile var closed = false
        private val inbound = ConcurrentLinkedQueue<ProtocolEnvelope>()

        override fun nextRequestId(): String = "c-${outbound.size + 1}"

        override fun send(requestId: String, sessionId: String, type: String, payload: JsonValue.ObjectValue): ProtocolEnvelope {
            outbound += type
            val sequence = outbound.size.toLong()
            val envelope = ProtocolEnvelope(ProtocolCodec.VERSION, requestId, sessionId, sequence, type, payload)
            when (type) {
                "auth_begin" -> inbound += env(requestId, sequence, "auth_challenge", jsonObject(
                    "challengeId" to jsonString("challenge-1"),
                    "serverNonce" to jsonString("ab".repeat(32)),
                    "expiresInMs" to jsonLong(30_000),
                ))
                "auth_response" -> {
                    authGate?.await()
                    inbound += env(requestId, sequence, "auth_complete", jsonObject(
                        "sessionId" to jsonString("session-1"),
                        "expiresInMs" to jsonLong(900_000),
                        "capabilities" to jsonObject("textInput" to jsonString("SUPPORTED")),
                    ))
                }
                "key_event" -> {
                    if (failKeyEvents) throw IOException("simulated drop")
                    inbound += env(requestId, sequence, "command_ack", jsonObject(
                        "commandSequence" to jsonLong(sequence),
                        "status" to jsonString("SUCCESS"),
                    ))
                }
                "disconnect" -> inbound += env(requestId, sequence, "disconnect_ack", jsonObject())
                "capabilities_request" -> inbound += env(requestId, sequence, "capabilities", jsonObject(
                    "textInput" to jsonString("SUPPORTED"),
                ))
                "ping" -> Unit
            }
            return envelope
        }

        override fun receive(): ProtocolEnvelope? = inbound.poll()
        override fun close() { closed = true }

        private fun env(requestId: String, sequence: Long, type: String, payload: JsonValue.ObjectValue) =
            ProtocolEnvelope(ProtocolCodec.VERSION, requestId, "", sequence, type, payload)
    }

    private class FakeStore(private val devices: MutableMap<String, StoredDevice>) : CredentialStore {
        @Volatile var removeGate: CountDownLatch? = null
        @Volatile var findGate: CountDownLatch? = null
        val pending = mutableListOf<StoredDevice>()
        override fun load() = DeviceLoad(
            devices = devices.values.sortedByDescending { it.lastUsedAtMs },
            pending = pending.toList(),
        )
        override fun find(id: String): StoredDevice? {
            findGate?.await()
            return devices[id]
        }
        override fun save(device: StoredDevice) { devices[device.id] = device }
        override fun savePending(device: StoredDevice) = Unit
        override fun markPendingAckAttempted(id: String) = Unit
        override fun promotePending(id: String) = Unit
        override fun discardPending(id: String) = Unit
        override fun rename(id: String, newName: String) {
            devices[id]?.let { devices[id] = it.withName(newName) }
        }
        override fun remove(id: String) {
            removeGate?.await()
            devices.remove(id)
        }
        override fun markUsed(id: String, host: String, port: Int, tvDisplayName: String?, nowMs: Long) {
            devices[id]?.let { devices[id] = it.withEndpoint(host, port, tvDisplayName, nowMs) }
        }
    }

    private class Harness {
        val ioPool = Executors.newCachedThreadPool()
        val mainPool = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(mainPool.asCoroutineDispatcher() + SupervisorJob())
        val factoryCalls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, FakeAuthTransport>>())
        lateinit var orchestrator: ControllerOrchestrator

        fun close() {
            scope.cancel()
            ioPool.shutdownNow()
            mainPool.shutdownNow()
        }
    }

    private fun fp(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    private fun device(id: String, host: String, fingerprint: ByteArray) = StoredDevice(
        id = id,
        displayName = id,
        controllerId = "0".repeat(32),
        secret = ByteArray(32) { 7 },
        tvCertificateFingerprint = fingerprint,
        certificateFingerprintHex = id,
        lastHost = host,
        lastPort = 47832,
        lastUsedAtMs = 0L,
        tvDisplayName = null,
    )

    private fun harness(
        store: CredentialStore,
        reconnectDelayMs: Long = 1_000L,
        authenticateGate: CountDownLatch? = null,
        failKeyEvents: Boolean = false,
    ): Harness {
        val h = Harness()
        h.orchestrator = ControllerOrchestrator(
            scope = h.scope,
            ioDispatcher = h.ioPool.asCoroutineDispatcher(),
            store = store,
            transportFactory = { host, _, pin ->
                val transport = FakeAuthTransport(pin ?: ByteArray(32), authenticateGate, failKeyEvents)
                h.factoryCalls += host to transport
                transport
            },
            probe = { emptyList() },
            strings = StringProvider { id, _ -> "s$id" },
            version = "test",
            reconnectDelayMs = reconnectDelayMs,
        )
        return h
    }

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    /** R5：删除当前设备 A 等待期间连接 B，删除恢复后不得关闭 B。 */
    @Test
    fun forgetOldDeviceDuringConnectDoesNotCloseNewSession() {
        val idA = "a".repeat(64)
        val idB = "b".repeat(64)
        val store = FakeStore(
            mutableMapOf(
                idA to device(idA, "192.0.2.1", fp(1)),
                idB to device(idB, "192.0.2.2", fp(2)),
            ),
        )
        val h = harness(store)
        try {
            h.orchestrator.selectDevice(idA)
            awaitUntil { h.orchestrator.state.value.connection == ConnectionPhase.CONNECTED }
            val transportA = h.factoryCalls.first { it.first == "192.0.2.1" }.second

            // 删除 A 时阻塞，期间连接 B
            store.removeGate = CountDownLatch(1)
            h.orchestrator.forgetDevice(idA)
            Thread.sleep(50)
            h.orchestrator.selectDevice(idB)
            awaitUntil { h.orchestrator.state.value.connection == ConnectionPhase.CONNECTED && h.orchestrator.state.value.activeDeviceId == idB }
            val transportB = h.factoryCalls.first { it.first == "192.0.2.2" }.second

            store.removeGate?.countDown()
            Thread.sleep(120)

            assertEquals("删除旧设备后不得关闭新会话（连接保持）", ConnectionPhase.CONNECTED, h.orchestrator.state.value.connection)
            assertTrue("新会话仍应存活", !transportB.closed)
            assertTrue("旧会话已断开", transportA.closed)
        } finally {
            h.close()
        }
    }

    /** R6：断线重连等待期间进入配对，旧重连不得再连接旧设备。 */
    @Test
    fun reconnectIsCancelledWhenPairingStarts() {
        val idA = "a".repeat(64)
        val store = FakeStore(mutableMapOf(idA to device(idA, "192.0.2.1", fp(1))))
        val h = harness(store, reconnectDelayMs = 300L, failKeyEvents = true)
        try {
            h.orchestrator.onForeground() // 设定前台，使 scheduleReconnect 生效（否则测试为假阳性）
            h.orchestrator.selectDevice(idA)
            awaitUntil { h.orchestrator.state.value.connection == ConnectionPhase.CONNECTED }

            // 触发一次网络失败 → scheduleReconnect
            h.orchestrator.sendKey("DPAD_UP", "PRESS")
            awaitUntil { h.orchestrator.state.value.connection == ConnectionPhase.FAILED }
            val callsAfterFailure = h.factoryCalls.size

            // 立即进入配对，应取消旧重连
            h.orchestrator.openPairing("192.0.2.9", 47832, PairingChannel.CODE)
            h.orchestrator.submitPairingCode("123456")
            Thread.sleep(500)

            assertEquals("旧设备不应被再次连接", callsAfterFailure, h.factoryCalls.count { it.first == "192.0.2.1" })
            assertTrue("应处于配对流程", h.orchestrator.state.value.pairing != null)
        } finally {
            h.close()
        }
    }

    /** R8：连接建立（认证在途）期间退后台，在途会话必须被关闭（取消时不泄漏）。 */
    @Test
    fun backgroundDuringConnectClosesInFlightSession() {
        val idA = "a".repeat(64)
        val store = FakeStore(mutableMapOf(idA to device(idA, "192.0.2.1", fp(1))))
        val authGate = CountDownLatch(1)
        val h = harness(store, authenticateGate = authGate)
        try {
            h.orchestrator.selectDevice(idA)
            awaitUntil { h.factoryCalls.isNotEmpty() } // 认证在途（auth_response 阻塞）
            val transport = h.factoryCalls.first().second
            assertFalse(transport.closed)

            h.orchestrator.onBackground()
            awaitUntil { transport.closed } // 在途会话被 drain 关闭

            authGate.countDown()
            Thread.sleep(120)
            assertTrue(transport.closed)
            assertEquals(ConnectionPhase.IDLE, h.orchestrator.state.value.connection)
        } finally {
            authGate.countDown()
            h.close()
        }
    }

    /** R2：对账认证在途时导航到添加设备页，不得绑定会话、且对账会话被关闭。 */
    @Test
    fun navigatingAwayDuringReconcileDoesNotBindSession() {
        val idP = "c".repeat(64)
        val store = FakeStore(mutableMapOf())
        store.pending += device(idP, "192.0.2.5", fp(3))
        val authGate = CountDownLatch(1)
        val h = harness(store, authenticateGate = authGate)
        try {
            h.orchestrator.onForeground() // 触发对账
            awaitUntil { h.factoryCalls.any { it.first == "192.0.2.5" } } // 认证在途（auth_response 阻塞）
            val transport = h.factoryCalls.first { it.first == "192.0.2.5" }.second

            h.orchestrator.openAddDevice() // 导航离开 → 取消并主动关闭对账会话
            // 不释放 authGate：验证取消时主动 close() 令阻塞中的网络操作释放
            awaitUntil { transport.closed }

            assertTrue("不得绑定对账会话", h.orchestrator.state.value.connection != ConnectionPhase.CONNECTED)
        } finally {
            authGate.countDown()
            h.close()
        }
    }

    /** R5 补充：正在连接 B（凭据读取在途）时忘记 B，B 不得再连接成功。 */
    @Test
    fun forgetInFlightConnectTargetPreventsConnect() {
        val idA = "a".repeat(64)
        val idB = "b".repeat(64)
        val store = FakeStore(
            mutableMapOf(
                idA to device(idA, "192.0.2.1", fp(1)),
                idB to device(idB, "192.0.2.2", fp(2)),
            ),
        )
        val h = harness(store)
        try {
            h.orchestrator.selectDevice(idA)
            awaitUntil { h.orchestrator.state.value.connection == ConnectionPhase.CONNECTED }

            store.findGate = CountDownLatch(1) // 连接 B 时阻塞在凭据读取
            h.orchestrator.selectDevice(idB)
            Thread.sleep(50)
            h.orchestrator.forgetDevice(idB) // 应使在途连接目标失效
            store.findGate?.countDown()
            Thread.sleep(250)

            assertFalse("B 不得连接成功", h.factoryCalls.any { it.first == "192.0.2.2" })
            assertTrue(h.orchestrator.state.value.activeDeviceId != idB)
        } finally {
            store.findGate?.countDown()
            h.close()
        }
    }
}
