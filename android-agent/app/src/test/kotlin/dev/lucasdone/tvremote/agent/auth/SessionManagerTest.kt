package dev.lucasdone.tvremote.agent.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    @Test
    fun authenticatesChallengeOnceAndRevokesSession() {
        val manager = SessionManager(nowMs = { 1_000L })
        val secret = ByteArray(32) { it.toByte() }
        val challenge = manager.createChallenge("controller")
        val response = SessionManager.hmac(secret, challenge.nonce)

        val session = manager.authenticate("controller", secret, response)
        assertNotNull(session)
        assertTrue(manager.validate("controller", session!!.token))
        assertFalse(manager.authenticate("controller", secret, response) != null)

        manager.revoke("controller")
        assertFalse(manager.validate("controller", session.token))
    }
}
