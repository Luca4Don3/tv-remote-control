package dev.tvremote.controller.session

import dev.tvremote.agent.auth.AuthTranscript
import dev.tvremote.agent.auth.PairingTranscript
import dev.tvremote.agent.protocol.Hex
import dev.tvremote.agent.protocol.JsonValue
import dev.tvremote.agent.protocol.ProtocolCodec
import dev.tvremote.agent.protocol.ProtocolEnvelope
import dev.tvremote.agent.protocol.jsonLong
import dev.tvremote.agent.protocol.jsonObject
import dev.tvremote.agent.protocol.jsonString
import dev.tvremote.agent.protocol.requireLong
import dev.tvremote.agent.protocol.requireObject
import dev.tvremote.agent.protocol.requireString
import dev.tvremote.controller.capability.KeySupport
import dev.tvremote.controller.capability.TextSupport
import dev.tvremote.controller.net.ConnectionTransport
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 电视会话编排（单连接、单读者）：
 * 6 位码/扫码 token 配对（SAS 本地独立校验）→ 凭据持久化确认 → 新连接认证 → 遥控/文字。
 *
 * 协议要求 `auth_begin` 必须是连接首条消息（服务端按首条消息路由），因此配对与认证
 * 分属两条连接。所有请求/响应成对操作串行化，避免多读者串线。
 */
class ControllerSession(
    private val connectionFactory: (pinnedFingerprint: ByteArray?) -> ConnectionTransport,
    private val random: SecureRandom = SecureRandom(),
    private val keepaliveIntervalMs: Long = KEEPALIVE_INTERVAL_MS,
    private val onUnexpectedClose: (() -> Unit)? = null,
) {
    private val ioLock = Any()
    private val closed = AtomicBoolean(false)
    private var connection: ConnectionTransport? = null
    private var connectionUsedForPairing = false
    private var sessionId: String = ""
    private var keepalive: java.util.concurrent.ScheduledExecutorService? = null

    /** 服务端下发的配对剩余时长（`pairing_sas.expiresInMs`），用于等待确认/凭据。 */
    private var pairingExpiresInMs: Long = 0

    private fun activeConnection(): ConnectionTransport {
        connection?.let { return it }
        return createConnection(null)
    }

    /**
     * 建立连接并发布。若发布前会话已关闭（关闭与建连并发），立即释放晚到的
     * transport 并抛 [SessionClosedException]，避免关闭后仍保留 socket。
     */
    private fun createConnection(pinnedFingerprint: ByteArray?): ConnectionTransport {
        if (closed.get()) throw SessionClosedException("session is closed")
        val created = connectionFactory(pinnedFingerprint)
        synchronized(this) {
            if (closed.get()) {
                runCatching { created.close() }
                throw SessionClosedException("session is closed")
            }
            connection = created
        }
        return created
    }

    /** 6 位配对码配对阶段一：等待 SAS 展示并本地校验。 */
    fun pairWithCode(code: String, controllerName: String): PairingChallenge {
        require(code.length == 6 && code.all { it.isDigit() }) { "pairing code must be 6 digits" }
        return submitPairRequest("code" to jsonString(code), sasInput = code, controllerName = controllerName)
    }

    /** 扫码 token 配对阶段一：token 为 64 位小写 hex，SAS 输入即 token。 */
    fun pairWithToken(token: String, controllerName: String): PairingChallenge {
        require(token.length == 64 && token.all { it.isDigit() || it in 'a'..'f' }) {
            "pairing token must be 64 lowercase hex characters"
        }
        return submitPairRequest("token" to jsonString(token), sasInput = token, controllerName = controllerName)
    }

    private fun submitPairRequest(
        credentialField: Pair<String, JsonValue>,
        sasInput: String,
        controllerName: String,
    ): PairingChallenge = synchronized(ioLock) {
        connectionUsedForPairing = true
        val controllerNonce = randomBytes(32)
        val transport = activeConnection()
        transport.send(
            requestId = transport.nextRequestId(),
            sessionId = "",
            type = "pair_request",
            payload = jsonObject(
                credentialField,
                "controllerName" to jsonString(controllerName),
                "controllerNonce" to jsonString(Hex.encode(controllerNonce)),
            ),
        )
        val sasMessage = receiveExpect(setOf("pairing_sas"))
        val tvNonce = Hex.decode(sasMessage.payload.requireString("tvNonce", 64), expectedBytes = 32)
        val reported = sasMessage.payload.requireString("sas", 6)
        // 独立重算 SAS：仅展示服务端 SAS 会让中间人伪造显示，SAS 失去身份核对意义。
        val expected = PairingTranscript.computeSas(
            code = sasInput,
            protocolVersion = ProtocolCodec.VERSION,
            certificateFingerprint = transport.peerFingerprint,
            tvNonce = tvNonce,
            controllerNonce = controllerNonce,
            controllerName = controllerName,
        )
        if (!MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.US_ASCII),
                reported.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            throw PairingRejectedException("SAS mismatch — possible man-in-the-middle")
        }
        val expiresInMs = sasMessage.payload.requireLong("expiresInMs")
        pairingExpiresInMs = expiresInMs
        PairingChallenge(
            pairingId = sasMessage.payload.requireString("pairingId", 32),
            sas = reported,
            expiresInMs = expiresInMs,
        )
    }

    /**
     * 配对阶段二：等待电视端用户确认后下发的 `pair_credential`。
     * 本方法不发送 `pair_store_ack`——ACK 必须在凭据成功持久化之后才发送。
     */
    fun awaitPairingCredential(): PairingCredential = synchronized(ioLock) {
        val transport = activeConnection()
        // 电视端配对有效期可达 120s，用户可能晚于默认 45s 读超时才确认
        transport.setReadTimeout(pairingReadTimeoutMs())
        val message = try {
            receiveExpect(setOf("pair_credential"))
        } finally {
            transport.setReadTimeout(READ_TIMEOUT_MS)
        }
        val controllerId = message.payload.requireString("controllerId", 32)
        if (!controllerId.matches(CONTROLLER_ID)) throw PairingRejectedException("invalid controller id")
        PairingCredential(
            pairingId = message.payload.requireString("pairingId", 32),
            controllerId = controllerId,
            secret = Hex.decode(message.payload.requireString("secret", 64), expectedBytes = 32),
            tvCertificateFingerprint = transport.peerFingerprint,
        )
    }

    /** 配对等待读超时：服务端剩余有效期 + 少量余量，收敛在 [READ_TIMEOUT_MS, MAX_PAIRING_WAIT_MS]。 */
    private fun pairingReadTimeoutMs(): Int =
        (pairingExpiresInMs + 5_000L).coerceIn(READ_TIMEOUT_MS.toLong(), MAX_PAIRING_WAIT_MS.toLong()).toInt()

    /**
     * 发送 `pair_store_ack`。与 [awaitPairComplete] 分开：调用方需在发送前持久化
     * 「已尝试发送」，以便 ACK 之后任何失败都保留待确认凭据（电视可能已激活）。
     */
    fun sendPairStoreAck(credential: PairingCredential) = synchronized(ioLock) {
        val transport = activeConnection()
        transport.send(
            requestId = transport.nextRequestId(),
            sessionId = "",
            type = "pair_store_ack",
            payload = jsonObject(
                "pairingId" to jsonString(credential.pairingId),
                "controllerId" to jsonString(credential.controllerId),
            ),
        )
    }

    /** 等待并校验 `pair_complete`。 */
    fun awaitPairComplete(credential: PairingCredential) = synchronized(ioLock) {
        val complete = receiveExpect(setOf("pair_complete"))
        if (complete.payload.requireString("controllerId", 32) != credential.controllerId) {
            throw PairingRejectedException("pair_complete controller id mismatch")
        }
    }

    /**
     * 认证：使用保存凭据在新 TLS 连接上交换 HMAC 挑战。
     * 响应计算复用 :protocol-core 的 AuthTranscript（与电视端/桌面端单一实现）。
     */
    fun authenticate(
        controllerId: String,
        secret: ByteArray,
        certificateFingerprint: ByteArray,
    ): Capabilities = synchronized(ioLock) {
        if (connectionUsedForPairing) {
            // auth_begin 必须是连接首条消息：配对连接不能复用
            runCatching { connection?.close() }
            connection = null
            connectionUsedForPairing = false
        }
        val clientNonce = randomBytes(32)
        // 认证连接必须固定已核对的电视证书；即使 factory 未 pin 也要在此拒绝换证
        val transport = createConnection(certificateFingerprint)
        if (!MessageDigest.isEqual(transport.peerFingerprint, certificateFingerprint)) {
            runCatching { transport.close() }
            connection = null
            throw AuthFailedException("peer certificate fingerprint mismatch")
        }
        transport.send(
            requestId = transport.nextRequestId(),
            sessionId = "",
            type = "auth_begin",
            payload = jsonObject(
                "controllerId" to jsonString(controllerId),
                "clientNonce" to jsonString(Hex.encode(clientNonce)),
            ),
        )
        val challenge = receiveExpect(setOf("auth_challenge"))
        val challengeId = challenge.payload.requireString("challengeId", 64)
        val serverNonce = Hex.decode(challenge.payload.requireString("serverNonce", 64), expectedBytes = 32)
        val response = AuthTranscript.hmac(
            secret = secret,
            certificateFingerprint = certificateFingerprint,
            controllerId = controllerId,
            challengeId = challengeId,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
        )
        transport.send(
            requestId = transport.nextRequestId(),
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
        val complete = receiveExpect(setOf("auth_complete"))
        sessionId = complete.payload.requireString("sessionId", 128)
        val capabilities = parseCapabilities(complete.payload.requireObject("capabilities"))
        startKeepalive()
        capabilities
    }

    /** 重新拉取能力（状态可能变化，不在首次登录后永久缓存）。 */
    fun requestCapabilities(): Capabilities = synchronized(ioLock) {
        check(sessionId.isNotEmpty()) { "not authenticated" }
        val transport = activeConnection()
        transport.send(transport.nextRequestId(), sessionId, "capabilities_request", jsonObject())
        parseCapabilities(receiveExpect(setOf("capabilities")).payload)
    }

    fun sendKeyEvent(key: String, state: String, repeatCount: Int = 0): AckResult = synchronized(ioLock) {
        require(sessionId.isNotEmpty()) { "not authenticated" }
        val transport = activeConnection()
        val request = transport.send(
            requestId = transport.nextRequestId(),
            sessionId = sessionId,
            type = "key_event",
            payload = jsonObject(
                "key" to jsonString(key),
                "state" to jsonString(state),
                "repeatCount" to jsonLong(repeatCount.toLong()),
            ),
        )
        awaitCommandAck(request)
    }

    /** 文字输入只使用 `text_commit`（不暴露 `text_draft`）。 */
    fun sendText(text: String): AckResult = synchronized(ioLock) {
        require(sessionId.isNotEmpty()) { "not authenticated" }
        require(text.isNotEmpty()) { "text must not be empty" }
        require(text.length <= MAX_TEXT_CHARS) { "text exceeds $MAX_TEXT_CHARS characters" }
        val transport = activeConnection()
        val request = transport.send(
            requestId = transport.nextRequestId(),
            sessionId = sessionId,
            type = "text_commit",
            payload = jsonObject("text" to jsonString(text)),
        )
        awaitCommandAck(request)
    }

    /** 主动断开：尽力发送 `disconnect`/`disconnect_ack`，随后关闭（幂等）。 */
    fun disconnect() {
        val transport = connection
        if (transport != null && sessionId.isNotEmpty() && !closed.get()) {
            try {
                // 断开等待用短超时，避免电视无响应时阻塞到默认 45s
                transport.setReadTimeout(DISCONNECT_ACK_TIMEOUT_MS)
                synchronized(ioLock) {
                    transport.send(transport.nextRequestId(), sessionId, "disconnect", jsonObject())
                    receiveExpect(setOf("disconnect_ack"))
                }
            } catch (_: Exception) {
                // 断开是尽力而为；无论电视是否应答都要释放本端资源
            }
        }
        close()
    }

    /** 幂等关闭：停止心跳并关闭 socket，未决的阻塞读会因 socket 关闭而失败。 */
    fun close() {
        closeInternal()
    }

    /**
     * 返回本次调用是否真正完成关闭（用于区分主动关闭与保活失败通知）。
     * 与 [createConnection] 共用同一把锁，确保「关闭后不再发布晚到连接」。
     */
    private fun closeInternal(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        // 与 startKeepalive 共用同一把锁：关闭后不会再被发布新的 keepalive
        val (scheduler, current) = synchronized(this) {
            val existingScheduler = keepalive
            keepalive = null
            val existingConnection = connection
            connection = null
            existingScheduler to existingConnection
        }
        scheduler?.shutdownNow()
        runCatching { current?.close() }
        sessionId = ""
        connectionUsedForPairing = false
        return true
    }

    private fun awaitCommandAck(request: ProtocolEnvelope): AckResult {
        while (true) {
            val ack = receiveExpect(setOf("command_ack"))
            if (ack.requestId != request.requestId) continue
            val sequence = ack.payload.requireLong("commandSequence")
            if (sequence != request.sequence) continue
            return AckResult(
                sequence = sequence,
                status = ack.payload.requireString("status", 32),
                reason = (ack.payload["reason"] as? JsonValue.StringValue)?.value,
            )
        }
    }

    /** 阻塞读取直到命中的类型；服务端 ping 即时应答，未知推送忽略，错误显式抛出。 */
    private fun receiveExpect(types: Set<String>): ProtocolEnvelope {
        while (true) {
            val message = activeConnection().receive()
                ?: throw SessionClosedException("connection closed by TV")
            if (message.type in types) return message
            when (message.type) {
                "error" -> throw SessionException(
                    code = (message.payload["code"] as? JsonValue.StringValue)?.value ?: "ERROR",
                    message = (message.payload["message"] as? JsonValue.StringValue)?.value ?: "TV rejected the request",
                )
                "pair_rejected" -> throw PairingRejectedException("pairing was not confirmed on TV")
                "ping" -> replyPong(message)
                else -> Unit // 其他服务端推送（media_state 等）忽略
            }
        }
    }

    private fun replyPong(ping: ProtocolEnvelope) {
        val transport = connection ?: return
        runCatching {
            transport.send(ping.requestId, sessionId, "pong", jsonObject())
        }
    }

    /**
     * TLS 主链路保活：15s fire-and-forget ping（agent 对 client ping 回 pong，
     * 空闲 45s 会被服务端断连）。写侧与命令共享 socket，由 TvConnection.send 串行化。
     */
    private fun startKeepalive() {
        synchronized(this) {
            // 已关闭或已有调度器：不创建，避免 close 之后发布无法回收的线程
            if (closed.get() || keepalive != null) return
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "tvrc-controller-keepalive").apply { isDaemon = true }
            }
            keepalive = scheduler
            scheduler.scheduleWithFixedDelay({
                val transport = connection
                if (transport == null || closed.get() || sessionId.isEmpty()) return@scheduleWithFixedDelay
                try {
                    transport.send(transport.nextRequestId(), sessionId, "ping", jsonObject())
                } catch (_: Exception) {
                    // 仅当本次由保活失败真正触发关闭时才通知；主动关闭（closed 已置位）不回调
                    if (closeInternal()) runCatching { onUnexpectedClose?.invoke() }
                }
            }, keepaliveIntervalMs, keepaliveIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun parseCapabilities(payload: JsonValue.ObjectValue): Capabilities {
        val textInput = TextSupport.parse((payload["textInput"] as? JsonValue.StringValue)?.value)
        val keySupport = (payload["keySupport"] as? JsonValue.ObjectValue)?.fields?.mapNotNull { (key, value) ->
            ((value as? JsonValue.StringValue)?.value)?.let { key to KeySupport.parse(it) }
        }?.toMap().orEmpty()
        return Capabilities(textInput = textInput, keySupport = keySupport)
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    data class PairingChallenge(val pairingId: String, val sas: String, val expiresInMs: Long)

    data class PairingCredential(
        val pairingId: String,
        val controllerId: String,
        val secret: ByteArray,
        val tvCertificateFingerprint: ByteArray,
    )

    data class Capabilities(
        val textInput: TextSupport,
        val keySupport: Map<String, KeySupport>,
    ) {
        fun keySupportOf(key: String): KeySupport = keySupport[key] ?: KeySupport.UNSUPPORTED

        companion object {
            val EMPTY = Capabilities(TextSupport.UNVERIFIED, emptyMap())
        }
    }

    data class AckResult(val sequence: Long, val status: String, val reason: String?) {
        val isSuccess: Boolean get() = status == "SUCCESS"
    }

    companion object {
        const val KEEPALIVE_INTERVAL_MS = 15_000L
        const val MAX_TEXT_CHARS = 4_096
        private const val READ_TIMEOUT_MS = 45_000
        private const val DISCONNECT_ACK_TIMEOUT_MS = 3_000
        private const val MAX_PAIRING_WAIT_MS = 130_000L
        private val CONTROLLER_ID = Regex("[0-9a-f]{32}")
    }
}

/** 服务端返回结构化错误（`error` 信封）。 */
class SessionException(val code: String, message: String) : RuntimeException(message)

/** 连接被对端关闭。 */
class SessionClosedException(message: String) : RuntimeException(message)

/** 配对被拒绝（SAS 不一致、电视端拒绝、凭据不匹配）。 */
class PairingRejectedException(message: String) : RuntimeException(message)

/** 认证失败（凭据撤销、证书变化、HMAC 不匹配）。 */
class AuthFailedException(message: String) : RuntimeException(message)
