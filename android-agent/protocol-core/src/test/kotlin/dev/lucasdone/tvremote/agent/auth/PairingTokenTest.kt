package dev.lucasdone.tvremote.agent.auth

import dev.lucasdone.tvremote.agent.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PairingTokenTest {
    private fun fingerprint() = ByteArray(32) { it.toByte() }
    private fun nonce() = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun qrTokenLifecycle() {
        val manager = PairingManager()
        val window = manager.openWindow()
        assertEquals(64, window.qrToken.length)
        assertTrue(window.qrToken.all { it.isDigit() || it in 'a'..'f' })
        // token 有效期不超过 60s
        assertTrue(window.qrTokenExpiresAtMs <= window.expiresAtMs)
    }

    @Test
    fun tokenSubmissionEntersAwaitingConfirmation() {
        val manager = PairingManager()
        val window = manager.openWindow()
        val submission = manager.submitWithToken(window.qrToken, "Phone", nonce(), fingerprint())
        assertTrue(submission is PairingSubmission.AwaitingTvConfirmation)
    }

    @Test
    fun tokenIsOneTime() {
        val manager = PairingManager()
        val window = manager.openWindow()
        assertTrue(manager.submitWithToken(window.qrToken, "Phone", nonce(), fingerprint()) is PairingSubmission.AwaitingTvConfirmation)
        // 已进入 SAS 阶段，再次提交一律拒绝
        assertTrue(manager.submitWithToken(window.qrToken, "Phone2", nonce(), fingerprint()) is PairingSubmission.Rejected)
    }

    @Test
    fun newWindowIssuesNewToken() {
        val manager = PairingManager()
        val first = manager.openWindow()
        assertTrue(manager.submitWithToken(first.qrToken, "Phone", nonce(), fingerprint()) is PairingSubmission.AwaitingTvConfirmation)
        // 开新窗口后旧 token 不再有效，新窗口 token 不同
        val second = manager.openWindow()
        assertTrue(second.qrToken != first.qrToken)
        assertTrue(manager.submitWithToken(first.qrToken, "Phone2", nonce(), fingerprint()) is PairingSubmission.Rejected)
        assertTrue(manager.submitWithToken(second.qrToken, "Phone3", nonce(), fingerprint()) is PairingSubmission.AwaitingTvConfirmation)
    }

    @Test
    fun expiredTokenRejected() {
        var clock = 0L
        val manager = PairingManager(nowMs = { clock })
        val window = manager.openWindow()
        clock = window.qrTokenExpiresAtMs + 1
        assertTrue(manager.submitWithToken(window.qrToken, "Phone", nonce(), fingerprint()) is PairingSubmission.Rejected)
    }

    @Test
    fun wrongTokenDoesNotConsumeValidOne() {
        val manager = PairingManager()
        val window = manager.openWindow()
        assertTrue(manager.submitWithToken("f".repeat(64), "Phone", nonce(), fingerprint()) is PairingSubmission.Rejected)
        assertTrue(manager.submitWithToken(window.qrToken, "Phone", nonce(), fingerprint()) is PairingSubmission.AwaitingTvConfirmation)
    }

    @Test
    fun codePathStillWorksAfterTokenWindow() {
        val manager = PairingManager()
        val window = manager.openWindow()
        // token 未消费时 6 位码路径仍可用
        val submission = manager.submit(window.code, "Phone", nonce(), fingerprint())
        assertTrue(submission is PairingSubmission.AwaitingTvConfirmation)
    }
}
