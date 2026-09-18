package dev.tvremote.controller

import dev.tvremote.agent.protocol.Hex
import dev.tvremote.agent.protocol.JsonValue
import dev.tvremote.agent.protocol.ProtocolCodec
import dev.tvremote.agent.protocol.ProtocolEnvelope
import dev.tvremote.agent.protocol.jsonLong
import dev.tvremote.agent.protocol.jsonObject
import dev.tvremote.agent.protocol.jsonString
import dev.tvremote.controller.net.ConnectionTransport
import dev.tvremote.controller.session.ControllerSession
import org.junit.Assert.assertEquals
import org.junit.Test

/** 回执交错：陈旧 ACK（requestId/commandSequence 不匹配）必须被忽略，只接受匹配请求的 ACK。 */
class CommandAckCorrelationTest {
    private val fingerprint = ByteArray(32) { it.toByte() }

    private class PlainTransport(
        override val peerFingerprint: ByteArray,
        private val script: ArrayDeque<ProtocolEnvelope>,
    ) : ConnectionTransport {
        val sent = mutableListOf<ProtocolEnvelope>()
        private var counter = 0L

        override fun nextRequestId(): String = "c-${++counter}"

        override fun send(requestId: String, sessionId: String, type: String, payload: JsonValue.ObjectValue): ProtocolEnvelope {
            val envelope = ProtocolEnvelope(ProtocolCodec.VERSION, requestId, sessionId, counter, type, payload)
            sent += envelope
            return envelope
        }

        override fun receive(): ProtocolEnvelope? = script.removeFirstOrNull()

        override fun close() = Unit
    }

    private fun env(requestId: String, sequence: Long, type: String, payload: JsonValue.ObjectValue) =
        ProtocolEnvelope(ProtocolCodec.VERSION, requestId, "", sequence, type, payload)

    @Test
    fun staleAckIsIgnoredAndCommandSequenceMatched() {
        val script = ArrayDeque<ProtocolEnvelope>()
        val transport = PlainTransport(fingerprint, script)
        val session = ControllerSession(connectionFactory = { _ -> transport })

        val serverNonce = Hex.encode(ByteArray(32) { 0xc0.toByte() })
        script += env(
            "c-1",
            1,
            "auth_challenge",
            jsonObject(
                "challengeId" to jsonString("challenge"),
                "serverNonce" to jsonString(serverNonce),
                "expiresInMs" to jsonLong(30_000),
            ),
        )
        script += env(
            "c-2",
            2,
            "auth_complete",
            jsonObject(
                "sessionId" to jsonString("sess-1"),
                "expiresInMs" to jsonLong(900_000),
                "capabilities" to jsonObject("textInput" to jsonString("SUPPORTED")),
            ),
        )
        session.authenticate("ab".repeat(16), ByteArray(32) { 1 }, fingerprint)

        // 先来一条陈旧 ACK（requestId=c-2 / commandSequence=2），再来匹配 ACK（c-3 / 3）
        script += env(
            "c-2",
            2,
            "command_ack",
            jsonObject("commandSequence" to jsonLong(2), "status" to jsonString("REJECTED")),
        )
        script += env(
            "c-3",
            3,
            "command_ack",
            jsonObject("commandSequence" to jsonLong(3), "status" to jsonString("SUCCESS")),
        )
        val ack = session.sendKeyEvent("DPAD_UP", "PRESS")
        assertEquals(3L, ack.sequence)
        assertEquals("SUCCESS", ack.status)
    }
}
