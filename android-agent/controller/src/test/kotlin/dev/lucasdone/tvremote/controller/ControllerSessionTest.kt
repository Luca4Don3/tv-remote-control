package dev.lucasdone.tvremote.controller

import dev.lucasdone.tvremote.agent.auth.AuthTranscript
import dev.lucasdone.tvremote.agent.protocol.Hex
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.ProtocolCodec
import dev.lucasdone.tvremote.agent.protocol.ProtocolEnvelope
import dev.lucasdone.tvremote.agent.protocol.jsonLong
import dev.lucasdone.tvremote.agent.protocol.requireString
import dev.lucasdone.tvremote.agent.protocol.jsonObject
import dev.lucasdone.tvremote.agent.protocol.jsonString
import dev.lucasdone.tvremote.controller.net.ConnectionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ControllerSession 端到端状态机测试：脚本化传输按 agent 端消息序列回放，
 * 验证配对（SAS → pair_credential → store_ack → pair_complete）与认证响应
 * 与 AuthTranscript 精确一致。
 */
class ControllerSessionTest {
    private val fingerprint = ByteArray(32) { it.toByte() }

    /** 脚本化传输：按序回放入站响应（对齐 agent 端消息到达顺序）。 */
    private class ScriptedTransport(
        override val peerFingerprint: ByteArray,
        private val script: ArrayDeque<ProtocolEnvelope>,
    ) : ConnectionTransport {
        val sent = mutableListOf<Pair<String, ProtocolEnvelope>>()
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
            // 对齐服务端：响应的 requestId 与最近出站请求一致
            return message.copy(requestId = sent.lastOrNull()?.second?.requestId ?: message.requestId)
        }

        override fun close() = Unit
    }

    private fun envelope(type: String, payload: JsonValue.ObjectValue, requestId: String) = ProtocolEnvelope(
        protocolVersion = ProtocolCodec.VERSION,
        requestId = requestId,
        sessionId = "",
        sequence = 1L,
        type = type,
        payload = payload,
    )

    @Test
    fun pairingFullFlow() {
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = ScriptedTransport(fingerprint, script)
        val session = ControllerSession(transport)

        // 阶段一：pair_request → pairing_sas
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
        val sas = session.pairWithCode("654321", "Test")
        assertEquals("123456", sas)
        assertEquals("pair_request", transport.sent.first().first)

        // 阶段二：pair_credential → store_ack → pair_complete
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
        val paired = session.completePairing()
        assertEquals(controllerId, paired.controllerId)
        assertTrue(paired.secret.contentEquals(secret))
        // store_ack 携带真实 controllerId（修复 B1）
        val storeAck = transport.sent.first { it.first == "pair_store_ack" }.second
        assertEquals(
            controllerId,
            storeAck.payload.requireString("controllerId", 32),
        )
    }

    @Test
    fun authenticationResponseMatchesTranscript() {
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = ScriptedTransport(fingerprint, script)
        val session = ControllerSession(transport)
        val controllerId = "ab".repeat(16)
        val secret = ByteArray(32) { (it + 3).toByte() }
        val challengeId = "challenge-x"
        val clientNonce = ByteArray(32) { (it + 0xa0).toByte() }
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
                ),
            ),
            requestId = "c-2",
        )
        val capabilities = session.authenticate(controllerId, secret, fingerprint)

        // 关键断言：发出的 auth_response 与 AuthTranscript 精确一致（B3 回归锚点）
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
        assertEquals("SUPPORTED", capabilities.textInput)
    }

    @Test
    fun pairingRejectionSurfaces() {
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = ScriptedTransport(fingerprint, script)
        val session = ControllerSession(transport)
        script += envelope(
            "pair_rejected",
            jsonObject("reason" to jsonString("pairing rejected")),
            requestId = "c-1",
        )
        try {
            session.pairWithCode("000000", "Test")
            throw AssertionError("expected PairingRejectedException")
        } catch (expected: ControllerSession.PairingRejectedException) {
            Unit
        }
    }
}
