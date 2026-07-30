package dev.lucasdone.tvremote.agent.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PairingManagerTest {
    @Test
    fun requiresCodeAndTvConfirmation() {
        var now = 1_000L
        val manager = PairingManager(nowMs = { now }, random = DeterministicRandom())
        val offer = manager.begin("Windows Controller")

        assertTrue(manager.submit(offer.code) is PairingSubmission.AwaitingTvConfirmation)
        val credential = manager.confirm(true)
        assertNotNull(credential)
        assertEquals(32, credential!!.secret.size)
        assertNull(manager.confirm(true))
    }

    @Test
    fun rejectsExpiredCode() {
        var now = 1_000L
        val manager = PairingManager(nowMs = { now }, random = DeterministicRandom())
        val offer = manager.begin("Controller")
        now = offer.expiresAtMs
        assertTrue(manager.submit(offer.code) is PairingSubmission.Rejected)
        assertNull(manager.confirm(true))
    }

    private class DeterministicRandom : SecureRandom() {
        override fun nextInt(bound: Int): Int = 123456 % bound
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { bytes[it] = it.toByte() }
        }
    }
}
