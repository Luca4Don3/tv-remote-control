package dev.lucasdone.tvremote.controller

import dev.lucasdone.tvremote.agent.auth.AuthTranscript
import dev.lucasdone.tvremote.agent.auth.PairingTranscript
import dev.lucasdone.tvremote.agent.protocol.Hex
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.ProtocolCodec
import dev.lucasdone.tvremote.agent.protocol.ProtocolEnvelope
import dev.lucasdone.tvremote.agent.protocol.jsonLong
import dev.lucasdone.tvremote.agent.protocol.jsonObject
import dev.lucasdone.tvremote.agent.protocol.jsonString
import dev.lucasdone.tvremote.agent.protocol.requireString
import dev.lucasdone.tvremote.controller.capability.KeySupport
import dev.lucasdone.tvremote.controller.capability.TextSupport
import dev.lucasdone.tvremote.controller.net.ConnectionTransport
import dev.lucasdone.tvremote.controller.session.AuthFailedException
import dev.lucasdone.tvremote.controller.session.ControllerSession
import dev.lucasdone.tvremote.controller.session.PairingFlow
import dev.lucasdone.tvremote.controller.session.PairingRejectedException
import dev.lucasdone.tvremote.controller.session.SessionClosedException
import dev.lucasdone.tvremote.controller.data.CredentialStore
import dev.lucasdone.tvremote.controller.data.CredentialStoreException
import dev.lucasdone.tvremote.controller.data.DeviceLoad
import dev.lucasdone.tvremote.controller.data.StoredDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ControllerSession 状态机测试：脚本化传输按 agent 端消息序列回放，
 * 验证配对（SAS → credential → store_ack → complete）、认证与凭据落盘顺序。
 */
class ControllerSessionTest {
    private val fingerprint = ByteArray(32) { it.toByte() }

    /** 脚本化传输：按序回放入站响应（对齐 agent 端消息到达顺序）。 */
    private class ScriptedTransport(
        override val peerFingerprint: ByteArray,
        private val script: ArrayDeque<ProtocolEnvelope>,
    ) : ConnectionTransport {
        val sent = mutableListOf<Pair<String, ProtocolEnvelope>>()
        @Volatile var closed = false
            private set
        private var counter = 0L

        override fun nextRequestId(): String = "c-${++counter}"

        override fun send(requestId: String, sessionId: String, type: String, payload: JsonValue.ObjectValue): ProtocolEnvelope {
            val envelope = ProtocolEnvelope(
                protocolVersion = ProtocolCodec.VERSION,
                requestId = requestId,
                sessionId = sessionId,
                sequence = counter,
                type = type,
                payload = payload,
            )
            sent += type to envelope
            return envelope
        }

        override fun receive(): ProtocolEnvelope? {
            val message = script.removeFirstOrNull() ?: return null
            return message.copy(requestId = sent.lastOrNull()?.second?.requestId ?: message.requestId)
        }

        override fun close() {
            closed = true
        }
    }

    private fun envelope(type: String, payload: JsonValue.ObjectValue, requestId: String) = ProtocolEnvelope(
        protocolVersion = ProtocolCodec.VERSION,
        requestId = requestId,
        sessionId = "",
        sequence = 1L,
        type = type,
        payload = payload,
    )

    private fun deterministicRandom() = object : java.security.SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { bytes[it] = (it + 7).toByte() }
        }
    }

    /** 中间人语义：服务端 SAS 与本地计算不符必须拒绝（仅展示不验证的历史缺陷回归锚）。 */
    @Test
    fun pairingRejectsTamperedSas() {
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = ScriptedTransport(fingerprint, script)
        val session = ControllerSession(connectionFactory = { _ -> transport }, random = deterministicRandom())
        script += envelope(
            "pairing_sas",
            jsonObject(
                "pairingId" to jsonString("a1b2c3d4e5f60718"),
                "sas" to jsonString("123456"),
                "tvNonce" to jsonString("ab".repeat(32)),
                "expiresInMs" to jsonLong(120_000),
            ),
            requestId = "c-1",
        )
        try {
            session.pairWithCode("654321", "Test")
            fail("mismatched SAS must be rejected")
        } catch (_: PairingRejectedException) {
        }
    }

    @Test
    fun pairingFullFlowPersistsBeforeAck() {
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = ScriptedTransport(fingerprint, script)

        val code = "654321"
        val tvNonce = "ab".repeat(32)
        val controllerNonce = ByteArray(32) { (it + 7).toByte() }
        val serverSas = PairingTranscript.computeSas(
            code = code,
            protocolVersion = 1,
            certificateFingerprint = fingerprint,
            tvNonce = Hex.decode(tvNonce),
            controllerNonce = controllerNonce,
            controllerName = "Test",
        )
        script += envelope(
            "pairing_sas",
            jsonObject(
                "pairingId" to jsonString("a1b2c3d4e5f60718"),
                "sas" to jsonString(serverSas),
                "tvNonce" to jsonString(tvNonce),
                "expiresInMs" to jsonLong(120_000),
            ),
            requestId = "c-1",
        )
        val session = ControllerSession({ _ -> transport }, deterministicRandom())
        val challenge = session.pairWithCode(code, "Test")
        assertEquals(serverSas, challenge.sas)
        assertEquals("pair_request", transport.sent.first().first)

        val controllerId = "ab".repeat(16)
        val secret = ByteArray(32) { (it + 1).toByte() }
        script += envelope(
            "pair_credential",
            jsonObject(
                "pairingId" to jsonString("a1b2c3d4e5f60718"),
                "controllerId" to jsonString(controllerId),
                "secret" to jsonString(Hex.encode(secret)),
            ),
            requestId = "c-2",
        )
        script += envelope(
            "pair_complete",
            jsonObject("controllerId" to jsonString(controllerId)),
            requestId = "c-3",
        )

        val credential = session.awaitPairingCredential()
        assertEquals(controllerId, credential.controllerId)
        // 落盘之前不得发送 pair_store_ack（顺序修复的回归锚）
        assertFalse(transport.sent.any { it.first == "pair_store_ack" })

        val store = RecordingStore()
        PairingFlow.persistCredentialThenConfirm(
            session = session,
            credential = credential,
            store = store,
            displayName = "Test",
            host = "192.0.2.10",
            port = 47832,
            tvName = null,
            nowMs = 1L,
        )
        assertEquals(1, store.saved.size)
        val storeAck = transport.sent.first { it.first == "pair_store_ack" }.second
        assertEquals(controllerId, storeAck.payload.requireString("controllerId", 32))
    }

    @Test
    fun credentialStoreFailurePreventsPairStoreAck() {
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = ScriptedTransport(fingerprint, script)
        val session = ControllerSession({ _ -> transport }, deterministicRandom())
        script += envelope(
            "pairing_sas",
            jsonObject(
                "pairingId" to jsonString("a1b2c3d4e5f60718"),
                "sas" to jsonString(
                    PairingTranscript.computeSas(
                        code = "654321",
                        protocolVersion = 1,
                        certificateFingerprint = fingerprint,
                        tvNonce = "ab".repeat(32).let(Hex::decode),
                        controllerNonce = ByteArray(32) { (it + 7).toByte() },
                        controllerName = "Test",
                    ),
                ),
                "tvNonce" to jsonString("ab".repeat(32)),
                "expiresInMs" to jsonLong(120_000),
            ),
            requestId = "c-1",
        )
        session.pairWithCode("654321", "Test")
        script += envelope(
            "pair_credential",
            jsonObject(
                "pairingId" to jsonString("a1b2c3d4e5f60718"),
                "controllerId" to jsonString("ab".repeat(16)),
                "secret" to jsonString(Hex.encode(ByteArray(32) { 1 })),
            ),
            requestId = "c-2",
        )
        val credential = session.awaitPairingCredential()
        try {
            PairingFlow.persistCredentialThenConfirm(
                session = session,
                credential = credential,
                store = object : CredentialStore {
                    override fun load() = DeviceLoad(emptyList())
                    override fun find(id: String): StoredDevice? = null
                    override fun save(device: StoredDevice) = Unit
                    override fun savePending(device: StoredDevice) = throw CredentialStoreException("disk full")
                    override fun promotePending(id: String) = Unit
                    override fun discardPending(id: String) = Unit
                    override fun rename(id: String, newName: String) = Unit
                    override fun remove(id: String) = Unit
                    override fun markUsed(id: String, host: String, port: Int, tvDisplayName: String?, nowMs: Long) = Unit
                },
                displayName = "Test",
                host = "192.0.2.10",
                port = 47832,
                tvName = null,
                nowMs = 1L,
            )
            fail("credential persistence failure must abort pairing")
        } catch (_: CredentialStoreException) {
        }
        assertFalse("凭据未落盘时禁止发送 pair_store_ack", transport.sent.any { it.first == "pair_store_ack" })
    }

    @Test
    fun authenticationResponseMatchesTranscriptAndParsesCapabilities() {
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = ScriptedTransport(fingerprint, script)
        val session = ControllerSession(connectionFactory = { _ -> transport }, random = deterministicRandom())
        val controllerId = "ab".repeat(16)
        val secret = ByteArray(32) { (it + 3).toByte() }
        val challengeId = "challenge-x"
        val serverNonce = ByteArray(32) { (it + 0xc0).toByte() }

        script += envelope(
            "auth_challenge",
            jsonObject(
                "challengeId" to jsonString(challengeId),
                "serverNonce" to jsonString(Hex.encode(serverNonce)),
                "expiresInMs" to jsonLong(30_000),
            ),
            requestId = "c-1",
        )
        script += envelope(
            "auth_complete",
            jsonObject(
                "sessionId" to jsonString("sess-1"),
                "expiresInMs" to jsonLong(900_000),
                "capabilities" to jsonObject(
                    "textInput" to jsonString("SUPPORTED"),
                    "keySupport" to jsonObject(
                        "DPAD_UP" to jsonString("BEST_EFFORT"),
                        "HOME" to jsonString("SUPPORTED"),
                        "POWER" to jsonString("UNSUPPORTED"),
                        "MENU" to jsonString("PERMISSION_REQUIRED"),
                        "POWER_X" to jsonString("UNVERIFIED"),
                    ),
                ),
            ),
            requestId = "c-2",
        )
        val capabilities = session.authenticate(controllerId, secret, fingerprint)

        val authResponse = transport.sent.first { it.first == "auth_response" }.second
        val expected = AuthTranscript.hmac(
            secret = secret,
            certificateFingerprint = fingerprint,
            controllerId = controllerId,
            challengeId = challengeId,
            clientNonce = Hex.decode(authResponse.payload.requireString("clientNonce", 64)),
            serverNonce = serverNonce,
        )
        assertEquals(Hex.encode(expected), authResponse.payload.requireString("response", 64))
        assertEquals(TextSupport.SUPPORTED, capabilities.textInput)
        assertEquals(KeySupport.BEST_EFFORT, capabilities.keySupportOf("DPAD_UP"))
        assertEquals(KeySupport.SUPPORTED, capabilities.keySupportOf("HOME"))
        assertEquals(KeySupport.UNSUPPORTED, capabilities.keySupportOf("POWER"))
        assertEquals(KeySupport.PERMISSION_REQUIRED, capabilities.keySupportOf("MENU"))
        assertEquals(KeySupport.UNVERIFIED, capabilities.keySupportOf("POWER_X"))
        // 未上报的按键按不支持处理，避免静默成功
        assertEquals(KeySupport.UNSUPPORTED, capabilities.keySupportOf("CHANNEL_UP"))
    }

    /** R01：认证连接的对端指纹与保存指纹不一致必须被拒绝（即使 factory 未 pin）。 */
    @Test
    fun authenticateRejectsPeerFingerprintDifferentFromSaved() {
        val savedFingerprint = ByteArray(32) { 0x11 }
        val actualFingerprint = ByteArray(32) { 0x22 }
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = ScriptedTransport(actualFingerprint, script)
        // factory 忽略 pin（模拟未固定的连接）；authenticate 必须自行校验并拒绝
        val session = ControllerSession(connectionFactory = { _ -> transport })
        try {
            session.authenticate("ab".repeat(16), ByteArray(32) { 1 }, savedFingerprint)
            fail("mismatched peer fingerprint must be rejected")
        } catch (_: AuthFailedException) {
        }
        assertTrue("换证连接必须被关闭", transport.closed)
    }

    /** R02：建连进行中关闭会话，晚到的 transport 必须被释放且认证失败。 */
    @Test
    fun closeDuringConnectReleasesLateTransport() {
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = ScriptedTransport(ByteArray(32) { 1 }, script)
        val factoryEntered = java.util.concurrent.CountDownLatch(1)
        val releaseFactory = java.util.concurrent.CountDownLatch(1)
        val session = ControllerSession(
            connectionFactory = {
                factoryEntered.countDown()
                releaseFactory.await()
                transport
            },
        )
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                session.authenticate("ab".repeat(16), ByteArray(32) { 1 }, ByteArray(32) { 1 })
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }.apply { isDaemon = true; start() }

        assertTrue(factoryEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        session.close()
        releaseFactory.countDown()
        worker.join(5_000)

        assertTrue("关闭后认证必须失败：${error.get()}", error.get() is SessionClosedException)
        assertTrue("晚到 transport 必须被关闭", transport.closed)
    }

    @Test
    fun pairingRejectionSurfaces() {
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = ScriptedTransport(fingerprint, script)
        val session = ControllerSession(connectionFactory = { _ -> transport }, random = deterministicRandom())
        script += envelope(
            "pair_rejected",
            jsonObject("reason" to jsonString("pairing rejected")),
            requestId = "c-1",
        )
        try {
            session.pairWithCode("000000", "Test")
            throw AssertionError("expected PairingRejectedException")
        } catch (_: PairingRejectedException) {
        }
    }

    private class RecordingStore : CredentialStore {
        val saved = mutableListOf<StoredDevice>()
        override fun load() = DeviceLoad(saved.toList())
        override fun find(id: String): StoredDevice? = saved.firstOrNull { it.id == id }
        override fun save(device: StoredDevice) {
            saved += device
        }
        override fun savePending(device: StoredDevice) {
            saved += device
        }
        override fun promotePending(id: String) = Unit
        override fun discardPending(id: String) = Unit
        override fun rename(id: String, newName: String) = Unit
        override fun remove(id: String) = Unit
        override fun markUsed(id: String, host: String, port: Int, tvDisplayName: String?, nowMs: Long) = Unit
    }
}
