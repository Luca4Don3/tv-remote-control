package dev.lucasdone.tvremote.agent.auth

import dev.lucasdone.tvremote.agent.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    @Test
    fun activeSessionCountIsBounded() {
        val manager = SessionManager(nowMs = { 1_000L })
        val secret = ByteArray(32) { it.toByte() }
        val fingerprint = ByteArray(32) { (it + 32).toByte() }
        fun authenticate(controllerId: String): AuthSession? {
            val challenge = manager.createChallenge(controllerId, ByteArray(32))
            return manager.authenticate(
                controllerId,
                challenge.challengeId,
                fingerprint,
                challenge.clientNonce,
                challenge.serverNonce,
                secret,
                AuthTranscript.hmac(
                    secret,
                    fingerprint,
                    controllerId,
                    challenge.challengeId,
                    challenge.clientNonce,
                    challenge.serverNonce,
                ),
            )
        }

        repeat(16) { assertNotNull(authenticate("%032x".format(it + 1))) }
        assertNull(authenticate("%032x".format(17)))
    }

    @Test
    fun validTrafficSlidesSessionExpiryBeyondOneHour() {
        var now = 1_000L
        val controllerId = "0123456789abcdef0123456789abcdef"
        val manager = SessionManager(nowMs = { now })
        val secret = ByteArray(32) { it.toByte() }
        val fingerprint = ByteArray(32) { (it + 32).toByte() }
        val challenge = manager.createChallenge(controllerId, ByteArray(32))
        val response = AuthTranscript.hmac(
            secret,
            fingerprint,
            controllerId,
            challenge.challengeId,
            challenge.clientNonce,
            challenge.serverNonce,
        )
        val session = manager.authenticate(
            controllerId,
            challenge.challengeId,
            fingerprint,
            challenge.clientNonce,
            challenge.serverNonce,
            secret,
            response,
        )!!

        repeat(5) {
            now += 14 * 60_000L
            assertTrue(manager.validate(controllerId, session.sessionId))
        }
        assertTrue(now > 60 * 60_000L)
    }

    @Test
    fun challengeIsSingleUseAndSessionCanBeRevoked() {
        val controllerId = "0123456789abcdef0123456789abcdef"
        val manager = SessionManager(nowMs = { 1_000L })
        val secret = ByteArray(32) { it.toByte() }
        val fingerprint = ByteArray(32) { (it + 32).toByte() }
        val clientNonce = ByteArray(32) { (it + 64).toByte() }
        val challenge = manager.createChallenge(controllerId, clientNonce)
        val response = AuthTranscript.hmac(
            secret,
            fingerprint,
            controllerId,
            challenge.challengeId,
            challenge.clientNonce,
            challenge.serverNonce,
        )

        val session = manager.authenticate(
            controllerId,
            challenge.challengeId,
            fingerprint,
            challenge.clientNonce,
            challenge.serverNonce,
            secret,
            response,
        )
        assertNotNull(session)
        assertTrue(manager.validate(controllerId, session!!.sessionId))
        assertNull(
            manager.authenticate(
                controllerId,
                challenge.challengeId,
                fingerprint,
                challenge.clientNonce,
                challenge.serverNonce,
                secret,
                response,
            ),
        )
        manager.revokeController(controllerId)
        assertFalse(manager.validate(controllerId, session.sessionId))
    }

    @Test
    fun unknownControllerUsesSameChallengeFlowButCannotAuthenticate() {
        val controllerId = "0123456789abcdef0123456789abcdef"
        val manager = SessionManager(nowMs = { 1_000L })
        val challenge = manager.createChallenge(controllerId, ByteArray(32))
        assertNull(
            manager.authenticate(
                controllerId,
                challenge.challengeId,
                ByteArray(32),
                challenge.clientNonce,
                challenge.serverNonce,
                null,
                ByteArray(32),
            ),
        )
    }

    @Test
    fun matchesCrossPlatformAuthenticationGoldenVector() {
        assertEquals(
            "d9cd5a5424899eee5a4b1188cc79647bf88381c3561b6d4315648e6e750e1296",
            Hex.encode(
                AuthTranscript.hmac(
                    secret = ByteArray(32) { (it + 0x80).toByte() },
                    certificateFingerprint = ByteArray(32) { it.toByte() },
                    controllerId = "controller-01",
                    challengeId = "challenge-2026-07-30",
                    clientNonce = ByteArray(32) { (it + 0xa0).toByte() },
                    serverNonce = ByteArray(32) { (it + 0xc0).toByte() },
                ),
            ),
        )
    }
}
