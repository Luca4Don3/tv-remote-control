package dev.lucasdone.tvremote.agent.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AuthChallenge(val nonce: ByteArray, val expiresAtMs: Long)
data class AuthSession(val token: ByteArray, val expiresAtMs: Long)

class SessionManager(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private data class PendingChallenge(val nonce: ByteArray, val expiresAtMs: Long)
    private val challenges = mutableMapOf<String, PendingChallenge>()
    private val sessions = mutableMapOf<String, AuthSession>()

    @Synchronized
    fun createChallenge(controllerId: String): AuthChallenge {
        val challenge = PendingChallenge(
            nonce = ByteArray(32).also(random::nextBytes),
            expiresAtMs = nowMs() + CHALLENGE_TTL_MS,
        )
        challenges[controllerId] = challenge
        return AuthChallenge(challenge.nonce.copyOf(), challenge.expiresAtMs)
    }

    @Synchronized
    fun authenticate(controllerId: String, secret: ByteArray, response: ByteArray): AuthSession? {
        val challenge = challenges.remove(controllerId) ?: return null
        if (nowMs() >= challenge.expiresAtMs) return null
        val expected = hmac(secret, challenge.nonce)
        if (!MessageDigest.isEqual(expected, response)) return null

        val session = AuthSession(
            token = ByteArray(32).also(random::nextBytes),
            expiresAtMs = nowMs() + SESSION_TTL_MS,
        )
        sessions[controllerId] = session
        return session.copy(token = session.token.copyOf())
    }

    @Synchronized
    fun validate(controllerId: String, token: ByteArray): Boolean {
        val session = sessions[controllerId] ?: return false
        if (nowMs() >= session.expiresAtMs) {
            sessions.remove(controllerId)
            return false
        }
        return MessageDigest.isEqual(session.token, token)
    }

    @Synchronized
    fun revoke(controllerId: String) {
        challenges.remove(controllerId)
        sessions.remove(controllerId)
    }

    companion object {
        private const val CHALLENGE_TTL_MS = 30_000L
        private const val SESSION_TTL_MS = 15 * 60_000L

        fun hmac(secret: ByteArray, nonce: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(nonce)
        }
    }
}
