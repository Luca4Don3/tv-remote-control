package dev.lucasdone.tvremote.agent.auth

import dev.lucasdone.tvremote.agent.protocol.Hex
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AuthChallenge(
    val controllerId: String,
    val challengeId: String,
    val clientNonce: ByteArray,
    val serverNonce: ByteArray,
    val expiresAtMs: Long,
)

data class AuthSession(
    val controllerId: String,
    val sessionId: String,
    val expiresAtMs: Long,
)

class SessionManager(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: SecureRandom = SecureRandom(),
) {
    private data class PendingChallenge(
        val controllerId: String,
        val challengeId: String,
        val clientNonce: ByteArray,
        val serverNonce: ByteArray,
        val expiresAtMs: Long,
    )

    private val challenges = mutableMapOf<String, PendingChallenge>()
    private val sessions = mutableMapOf<String, AuthSession>()
    private val dummySecret = randomBytes(32)

    @Synchronized
    fun createChallenge(controllerId: String, clientNonce: ByteArray): AuthChallenge {
        require(controllerId.matches(IDENTIFIER)) { "invalid controllerId" }
        require(clientNonce.size == NONCE_BYTES) { "client nonce must be 32 bytes" }
        cleanupExpired()
        if (challenges.size >= MAX_PENDING_CHALLENGES) throw IllegalStateException("pending challenge limit reached")
        var challengeId: String
        do {
            challengeId = Hex.encode(randomBytes(CHALLENGE_ID_BYTES))
        } while (challengeId in challenges)
        val challenge = PendingChallenge(
            controllerId = controllerId,
            challengeId = challengeId,
            clientNonce = clientNonce.copyOf(),
            serverNonce = randomBytes(NONCE_BYTES),
            expiresAtMs = nowMs() + CHALLENGE_TTL_MS,
        )
        challenges[challengeId] = challenge
        return challenge.copyForCaller()
    }

    @Synchronized
    fun authenticate(
        controllerId: String,
        challengeId: String,
        certificateFingerprint: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        secret: ByteArray?,
        response: ByteArray,
    ): AuthSession? {
        cleanupExpired()
        val challenge = challenges.remove(challengeId) ?: return null
        if (nowMs() >= challenge.expiresAtMs ||
            challenge.controllerId != controllerId ||
            !MessageDigest.isEqual(challenge.clientNonce, clientNonce) ||
            !MessageDigest.isEqual(challenge.serverNonce, serverNonce)
        ) return null

        val expected = AuthTranscript.hmac(
            secret = secret ?: dummySecret,
            certificateFingerprint = certificateFingerprint,
            controllerId = controllerId,
            challengeId = challengeId,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
        )
        if (!MessageDigest.isEqual(expected, response) || secret == null) return null

        cleanupExpired()
        if (sessions.size >= MAX_ACTIVE_SESSIONS) return null

        val session = AuthSession(
            controllerId = controllerId,
            sessionId = Hex.encode(randomBytes(SESSION_ID_BYTES)),
            expiresAtMs = nowMs() + SESSION_TTL_MS,
        )
        sessions[session.sessionId] = session
        return session.copy()
    }

    @Synchronized
    fun validate(controllerId: String, sessionId: String): Boolean {
        cleanupExpired()
        val session = sessions[sessionId] ?: return false
        if (session.controllerId != controllerId) return false
        val now = nowMs()
        if (now >= session.expiresAtMs) return false
        // 仅在过期时间变化时写回，避免每次 validate 都创建新 AuthSession 对象增加 GC 压力。
        val renewedExpiry = now + SESSION_TTL_MS
        if (renewedExpiry != session.expiresAtMs) {
            sessions[sessionId] = session.copy(expiresAtMs = renewedExpiry)
        }
        return true
    }

    @Synchronized
    fun revokeController(controllerId: String) {
        challenges.entries.removeAll { it.value.controllerId == controllerId }
        sessions.entries.removeAll { it.value.controllerId == controllerId }
    }

    @Synchronized
    fun revokeSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    private fun cleanupExpired() {
        val now = nowMs()
        challenges.entries.removeAll { now >= it.value.expiresAtMs }
        sessions.entries.removeAll { now >= it.value.expiresAtMs }
    }

    private fun PendingChallenge.copyForCaller() = AuthChallenge(
        controllerId = controllerId,
        challengeId = challengeId,
        clientNonce = clientNonce.copyOf(),
        serverNonce = serverNonce.copyOf(),
        expiresAtMs = expiresAtMs,
    )

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    companion object {
        const val CHALLENGE_TTL_MS = 30_000L
        const val SESSION_TTL_MS = 15 * 60_000L
        private const val MAX_PENDING_CHALLENGES = 16
        private const val MAX_ACTIVE_SESSIONS = 16
        private const val NONCE_BYTES = 32
        private const val CHALLENGE_ID_BYTES = 16
        private const val SESSION_ID_BYTES = 16
        private val IDENTIFIER = Regex("[0-9a-f]{32}")
    }
}

object AuthTranscript {
    private val DOMAIN = "TVRC-AUTH-v1".toByteArray(StandardCharsets.US_ASCII)

    fun build(
        certificateFingerprint: ByteArray,
        controllerId: String,
        challengeId: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray {
        require(certificateFingerprint.size == 32) { "certificate fingerprint must be 32 bytes" }
        require(clientNonce.size == 32) { "client nonce must be 32 bytes" }
        require(serverNonce.size == 32) { "server nonce must be 32 bytes" }
        val fields = arrayOf(
            certificateFingerprint,
            controllerId.toByteArray(StandardCharsets.UTF_8),
            challengeId.toByteArray(StandardCharsets.UTF_8),
            clientNonce,
            serverNonce,
        )
        fields.forEach { require(it.size <= 0xffff) { "authentication transcript field is too long" } }
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(DOMAIN)
                fields.forEach { field ->
                    output.writeShort(field.size)
                    output.write(field)
                }
            }
        }.toByteArray()
    }

    fun hmac(
        secret: ByteArray,
        certificateFingerprint: ByteArray,
        controllerId: String,
        challengeId: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray = Mac.getInstance("HmacSHA256").run {
        require(secret.size == 32) { "controller secret must be 32 bytes" }
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(build(certificateFingerprint, controllerId, challengeId, clientNonce, serverNonce))
    }
}
