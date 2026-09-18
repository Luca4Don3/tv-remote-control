package dev.tvremote.controller

import dev.tvremote.agent.protocol.Hex
import dev.tvremote.agent.protocol.JsonValue
import dev.tvremote.agent.protocol.ProtocolCodec
import dev.tvremote.agent.protocol.ProtocolEnvelope
import dev.tvremote.agent.protocol.jsonObject
import dev.tvremote.agent.protocol.jsonString
import dev.tvremote.controller.data.CredentialStore
import dev.tvremote.controller.data.DeviceLoad
import dev.tvremote.controller.data.StoredDevice
import dev.tvremote.controller.net.ConnectionTransport
import dev.tvremote.controller.session.ControllerSession
import dev.tvremote.controller.session.PairingFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R11：配对两阶段——确认前不覆盖有效凭据；确认失败丢弃 pending。 */
class PairingFlowTest {
    private val fingerprint = ByteArray(32) { 0x11 }

    private class ScriptedTransport(
        override val peerFingerprint: ByteArray,
        private val script: ArrayDeque<ProtocolEnvelope>,
    ) : ConnectionTransport {
        override fun nextRequestId(): String = "c-1"

        override fun send(requestId: String, sessionId: String, type: String, payload: JsonValue.ObjectValue): ProtocolEnvelope =
            ProtocolEnvelope(ProtocolCodec.VERSION, requestId, sessionId, 1L, type, payload)

        override fun receive(): ProtocolEnvelope? = script.removeFirstOrNull()

        override fun close() = Unit
    }

    private class FakeStore(private val existing: StoredDevice?) : CredentialStore {
        var active: StoredDevice? = existing
        var pending: StoredDevice? = null
        var promoted = false
        var discarded = false

        var ackAttempted = false
        override fun load() = DeviceLoad(listOfNotNull(active))
        override fun find(id: String): StoredDevice? = active?.takeIf { it.id == id }
        override fun save(device: StoredDevice) { active = device }
        override fun savePending(device: StoredDevice) {
            pending = device
            ackAttempted = false
        }
        override fun markPendingAckAttempted(id: String) { ackAttempted = true }
        override fun promotePending(id: String) {
            promoted = true
            active = pending
            pending = null
        }
        override fun discardPending(id: String) { discarded = true; pending = null }
        override fun rename(id: String, newName: String) = Unit
        override fun remove(id: String) = Unit
        override fun markUsed(id: String, host: String, port: Int, tvDisplayName: String?, nowMs: Long) = Unit
    }

    private fun credential(): ControllerSession.PairingCredential = ControllerSession.PairingCredential(
        pairingId = "a1b2c3d4e5f60718",
        controllerId = "ab".repeat(16),
        secret = ByteArray(32) { 2 },
        tvCertificateFingerprint = fingerprint,
    )

    private fun oldDevice(): StoredDevice = StoredDevice(
        id = Hex.encode(fingerprint),
        displayName = "Old",
        controllerId = "cd".repeat(16),
        secret = ByteArray(32) { 3 },
        tvCertificateFingerprint = fingerprint,
        certificateFingerprintHex = Hex.encode(fingerprint),
        lastHost = "192.0.2.10",
        lastPort = 47832,
        lastUsedAtMs = 1L,
        tvDisplayName = null,
    )

    private fun envelope(type: String, payload: JsonValue.ObjectValue) =
        ProtocolEnvelope(ProtocolCodec.VERSION, "c-1", "", 1L, type, payload)

    @Test
    fun confirmSuccessPromotesPendingAndReplacesActive() {
        val script = ArrayDeque<ProtocolEnvelope>()
        script += envelope("pair_complete", jsonObject("controllerId" to jsonString(credential().controllerId)))
        val store = FakeStore(oldDevice())
        val session = ControllerSession(connectionFactory = { _ -> ScriptedTransport(fingerprint, script) })

        PairingFlow.persistCredentialThenConfirm(
            session = session,
            credential = credential(),
            store = store,
            displayName = "New",
            host = "192.0.2.20",
            port = 47832,
            tvName = "Living Room TV",
            nowMs = 2L,
        )
        assertTrue(store.promoted)
        assertEquals("New", store.active?.displayName)
        assertEquals("Living Room TV", store.active?.tvDisplayName)
        assertNull(store.pending)
    }

    /** R2：ACK 已尝试发送后 pair_complete 丢失 → pending 保留（电视可能已激活），旧有效记录不被覆盖。 */
    @Test
    fun confirmFailureAfterAckKeepsPendingAndExistingActive() {
        // 空脚本：ACK 发送成功但等待 pair_complete 时连接关闭
        val store = FakeStore(oldDevice())
        val session = ControllerSession(connectionFactory = { _ -> ScriptedTransport(fingerprint, ArrayDeque()) })

        val failed = runCatching {
            PairingFlow.persistCredentialThenConfirm(
                session = session,
                credential = credential(),
                store = store,
                displayName = "New",
                host = "192.0.2.20",
                port = 47832,
                tvName = null,
                nowMs = 2L,
            )
        }.isFailure

        assertTrue(failed)
        assertTrue("ACK 已尝试发送，必须保留 pending", store.ackAttempted)
        assertFalse("不得丢弃待确认凭据", store.discarded)
        assertFalse(store.promoted)
        assertEquals("旧有效凭据必须保留", "Old", store.active?.displayName)
    }
}
