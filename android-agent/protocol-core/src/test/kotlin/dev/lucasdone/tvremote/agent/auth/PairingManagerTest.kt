package dev.lucasdone.tvremote.agent.auth

import dev.lucasdone.tvremote.agent.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PairingManagerTest {
    @Test
    fun requiresLocalWindowCodeSasAndTvConfirmation() {
        val manager = PairingManager(nowMs = { 1_000L }, random = DeterministicRandom())
        val window = manager.openWindow()
        val submission = manager.submit(
            code = window.code,
            controllerName = "Windows Controller",
            controllerNonce = ByteArray(32) { (it + 64).toByte() },
            certificateFingerprint = ByteArray(32) { it.toByte() },
        ) as PairingSubmission.AwaitingTvConfirmation

        assertEquals(6, submission.details.sas.length)
        assertNotNull(manager.confirm(submission.details.pairingId, true))
        val decision = manager.awaitDecision(submission.details.pairingId, 1) as PairingDecision.Accepted
        assertEquals(32, decision.credential.secret.size)
        assertEquals(ByteArray(32) { it.toByte() }.toList(), decision.credential.certificateFingerprint.toList())
        manager.complete(submission.details.pairingId)
        assertNull(manager.currentWindow())
    }

    @Test
    fun rejectsExpiredWindow() {
        var now = 1_000L
        val manager = PairingManager(nowMs = { now }, random = DeterministicRandom())
        val window = manager.openWindow()
        now = window.expiresAtMs
        assertTrue(
            manager.submit(window.code, "Controller", ByteArray(32), ByteArray(32)) is PairingSubmission.Rejected,
        )
        assertNull(manager.currentWindow())
    }

    @Test
    fun closesWindowOnFifthWrongCode() {
        val manager = PairingManager(nowMs = { 1_000L }, random = DeterministicRandom())
        val window = manager.openWindow()
        repeat(5) {
            assertTrue(
                manager.submit("000000", "Controller", ByteArray(32), ByteArray(32)) is PairingSubmission.Rejected,
            )
        }
        val result = manager.submit(window.code, "Controller", ByteArray(32), ByteArray(32))
        assertTrue(result is PairingSubmission.Rejected)
        assertEquals("pairing unavailable", (result as PairingSubmission.Rejected).reason)
    }

    @Test
    fun matchesCrossPlatformPairingGoldenVector() {
        val fingerprint = ByteArray(32) { it.toByte() }
        val tvNonce = ByteArray(32) { (it + 0x20).toByte() }
        val controllerNonce = ByteArray(32) { (it + 0x40).toByte() }

        assertEquals(
            "d7930b6134a464b6bce1a8d23a6da2ab6c4cedb9811bd9562b36536f00dbe970",
            Hex.encode(PairingTranscript.hmac("123456", 1, fingerprint, tvNonce, controllerNonce, "Windows Controller")),
        )
        assertEquals(
            "738145",
            PairingTranscript.computeSas("123456", 1, fingerprint, tvNonce, controllerNonce, "Windows Controller"),
        )
    }

    private class DeterministicRandom : SecureRandom() {
        override fun nextInt(bound: Int): Int = 123456 % bound
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { bytes[it] = it.toByte() }
        }
    }
}
