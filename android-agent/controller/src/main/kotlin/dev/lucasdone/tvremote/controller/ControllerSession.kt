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
import dev.lucasdone.tvremote.agent.protocol.requireLong
import dev.lucasdone.tvremote.agent.protocol.requireObject
import dev.lucasdone.tvremote.agent.protocol.requireString
import java.io.IOException
import java.security.SecureRandom

/**
 * 电视会话编排：连接 → 配对（6 位码 + SAS）→ 认证 → 遥控/文本。
 * 流程语义与桌面控制端一致；证书指纹在配对成功后由调用方持久化。
 */
/**
 * 电视会话编排：连接 → 配对（6 位码 + SAS）→ 认证 → 遥控/文本。
 *
 * 协议要求 `auth_begin` 只能作为连接的第一条消息（服务端 handleSocket 按
 * first 消息路由），因此配对与认证分属两条连接：`authenticate` 内部会
 * 用 `connectionFactory` 重建一条干净连接。
 */
class ControllerSession(
    private val connectionFactory: () -> dev.lucasdone.tvremote.controller.net.ConnectionTransport,
    random: SecureRandom = SecureRandom(),
) {
    private val random: SecureRandom = random
    private var sessionId: String = ""
    private var connection: dev.lucasdone.tvremote.controller.net.ConnectionTransport? = null
    private var connectionUsedForPairing = false

    val fingerprintHex: String
        get() = Hex.encode(activeConnection().peerFingerprint)

    private fun activeConnection(): dev.lucasdone.tvremote.controller.net.ConnectionTransport {
        var current = connection
        if (current == null) {
            current = connectionFactory()
            connection = current
        }
        return current
    }

    /** 6 位码配对阶段一：等待 SAS 展示；凭据下发在 `completePairing` 阶段接收。 */
    fun pairWithCode(code: String, controllerName: String): String {
        require(code.length == 6 && code.all { it.isDigit() }) { "code must be 6 digits" }
        connectionUsedForPairing = true
        val controllerNonce = randomBytes(32)
        val pairingTransport = activeConnection()
        pairingTransport.send(
            requestId = pairingTransport.nextRequestId(),
            sessionId = "",
            type = "pair_request",
            payload = jsonObject(
                "code" to jsonString(code),
                "controllerName" to jsonString(controllerName),
                "controllerNonce" to jsonString(Hex.encode(controllerNonce)),
            ),
        )
        val sasMessage = receiveExpect("pairing_sas", "pair_rejected") ?: throw PairingRejectedException("pairing unavailable")
        if (sasMessage.type != "pairing_sas") throw PairingRejectedException("pairing rejected on TV")
        // 独立重算 SAS（对齐桌面 Zig 客户端）：code + 本端证书指纹 + 双 nonce + 名称。
        // 仅展示服务端发来的 SAS 会让中间人转发配对码并伪造显示，SAS 失去身份核对意义。
        val tvNonce = Hex.decode(sasMessage.payload.requireString("tvNonce", 64))
        val expected = PairingTranscript.computeSas(
            code = code,
            protocolVersion = ProtocolCodec.VERSION,
            certificateFingerprint = pairingTransport.peerFingerprint,
            tvNonce = tvNonce,
            controllerNonce = controllerNonce,
            controllerName = controllerName,
        )
        val reported = sasMessage.payload.requireString("sas", 6)
        if (!java.security.MessageDigest.isEqual(
                expected.toByteArray(Charsets.US_ASCII),
                reported.toByteArray(Charsets.US_ASCII),
            )
        ) {
            throw PairingRejectedException("SAS mismatch — possible man-in-the-middle")
        }
        return reported
    }

    /**
     * 配对阶段二：电视端用户确认后，接收服务端 `pair_credential` 推送，
     * 回 `pair_store_ack`（携带真实 controllerId）直至 `pair_complete`。
     * 返回的凭据由调用方持久化（ControllerCredentialStore）。
     */
    fun completePairing(): PairedController {
        val credential = receiveExpect("pair_credential", "pair_rejected")
            ?: throw PairingRejectedException("pairing closed before credential delivery")
        if (credential.type != "pair_credential") throw PairingRejectedException("pairing was not confirmed on TV")
        val controllerId = credential.payload.requireString("controllerId", 32)
        val secret = Hex.decode(credential.payload.requireString("secret", 64))
        if (controllerId.length != 32) throw PairingRejectedException("invalid controller id length")
        val pairingTransport = activeConnection()
        pairingTransport.send(
            requestId = pairingTransport.nextRequestId(),
            sessionId = "",
            type = "pair_store_ack",
            payload = jsonObject(
                "pairingId" to jsonString(credential.payload.requireString("pairingId", 32)),
                "controllerId" to jsonString(controllerId),
            ),
        )
        val complete = receiveExpect("pair_complete") ?: throw PairingRejectedException("no pair_complete")
        val confirmedId = complete.payload.requireString("controllerId", 32)
        if (confirmedId != controllerId) throw PairingRejectedException("pair_complete controller id mismatch")
        return PairedController(
            controllerId = controllerId,
            secret = secret,
            tvCertificateFingerprint = activeConnection().peerFingerprint,
        )
    }

    data class PairedController(
        val controllerId: String,
        val secret: ByteArray,
        val tvCertificateFingerprint: ByteArray,
    )

    /**
     * 认证挑战：controllerId + secret + 电视证书指纹（由凭据存储持有）。
     * 响应计算复用 :protocol-core 的 AuthTranscript（与电视端/桌面端单一实现）。
     */
    fun authenticate(
        controllerId: String,
        secret: ByteArray,
        certificateFingerprint: ByteArray,
    ): Capabilities {
        if (connectionUsedForPairing) {
            // 协议要求 auth_begin 为连接首条消息：配对连接复用即被服务端断开
            connection?.close()
            connection = connectionFactory()
            connectionUsedForPairing = false
        }
        val clientNonce = randomBytes(32)
        val authTransport = activeConnection()
        authTransport.send(
            requestId = authTransport.nextRequestId(),
            sessionId = "",
            type = "auth_begin",
            payload = jsonObject(
                "controllerId" to jsonString(controllerId),
                "clientNonce" to jsonString(Hex.encode(clientNonce)),
            ),
        )
        val challenge = receiveExpect("auth_challenge") ?: throw AuthFailedException("no challenge")
        val challengeId = challenge.payload.requireString("challengeId", 64)
        val serverNonce = Hex.decode(challenge.payload.requireString("serverNonce", 64))
        val response = AuthTranscript.hmac(
            secret = secret,
            certificateFingerprint = certificateFingerprint,
            controllerId = controllerId,
            challengeId = challengeId,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
        )
        authTransport.send(
            requestId = authTransport.nextRequestId(),
            sessionId = "",
            type = "auth_response",
            payload = jsonObject(
                "controllerId" to jsonString(controllerId),
                "challengeId" to jsonString(challengeId),
                "clientNonce" to jsonString(Hex.encode(clientNonce)),
                "serverNonce" to jsonString(Hex.encode(serverNonce)),
                "response" to jsonString(Hex.encode(response)),
            ),
        )
        val complete = receiveExpect("auth_complete") ?: throw AuthFailedException("no auth complete")
        sessionId = complete.payload.requireString("sessionId", 128)
        return parseCapabilities(complete.payload.requireObject("capabilities"))
    }

    fun sendKeyEvent(key: String, state: String, repeatCount: Int = 0): AckResult {
        val transport = activeConnection()
        val message = transport.send(
            requestId = transport.nextRequestId(),
            sessionId = sessionId,
            type = "key_event",
            payload = jsonObject(
                "key" to jsonString(key),
                "state" to jsonString(state),
                "repeatCount" to jsonLong(repeatCount.toLong()),
            ),
        )
        val ack = receiveExpect("command_ack") ?: throw IOException("connection closed")
        return AckResult(
            sequence = ack.payload.requireLong("commandSequence"),
            status = ack.payload.requireString("status", 32),
            reason = ack.payload["reason"]?.let { (it as? JsonValue.StringValue)?.value },
        )
    }

    fun sendText(text: String, draft: Boolean): AckResult {
        val transport = activeConnection()
        val message = transport.send(
            requestId = transport.nextRequestId(),
            sessionId = sessionId,
            type = if (draft) "text_draft" else "text_commit",
            payload = jsonObject("text" to jsonString(text)),
        )
        val ack = receiveExpect("command_ack") ?: throw IOException("connection closed")
        return AckResult(
            sequence = ack.payload.requireLong("commandSequence"),
            status = ack.payload.requireString("status", 32),
            reason = ack.payload["reason"]?.let { (it as? JsonValue.StringValue)?.value },
        )
    }

    private fun receiveExpect(vararg types: String): ProtocolEnvelope? {
        while (true) {
            val message = activeConnection().receive() ?: return null
            if (message.type in types) return message
            if (message.type == "error") return message
            // 其他服务端推送（media_state 等）忽略
        }
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    internal fun parseCapabilitiesPublic(payload: JsonValue.ObjectValue): Capabilities = parseCapabilities(payload)

    private fun parseCapabilities(payload: JsonValue.ObjectValue): Capabilities {
        val textInput = (payload["textInput"] as? JsonValue.StringValue)?.value ?: "UNSUPPORTED"
        val keySupport = payload["keySupport"]?.let { entry ->
            (entry as? JsonValue.ObjectValue)?.fields?.mapNotNull { (key, value) ->
                ((value as? JsonValue.StringValue)?.value)?.let { key to it }
            }?.toMap()
        }.orEmpty()
        return Capabilities(textInput = textInput, keySupport = keySupport)
    }

    data class Capabilities(
        val textInput: String,
        val keySupport: Map<String, String>,
    ) {
        fun keySupported(key: String): Boolean =
            keySupport[key] == "SUPPORTED" || keySupport[key] == "BEST_EFFORT"
    }

    data class AckResult(val sequence: Long, val status: String, val reason: String?) {
        val isSuccess: Boolean get() = status == "SUCCESS"
    }

    /** 关闭当前连接（认证后的控制连接；配对连接已被 authenticate 替换关闭）。 */
    fun close() {
        connection?.close()
        connection = null
    }

    class PairingRejectedException(message: String) : RuntimeException(message)
    class AuthFailedException(message: String) : RuntimeException(message)
}
