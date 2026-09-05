package dev.lucasdone.tvremote.controller

import dev.lucasdone.tvremote.agent.auth.AuthTranscript
import dev.lucasdone.tvremote.agent.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 认证响应跨端一致性锚点：controller 直接复用 :protocol-core 的 AuthTranscript，
 * 与电视端 SessionManagerTest 的跨平台黄金向量使用同一向量断言。
 */
class AuthTranscriptAlignmentTest {
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

    @Test
    fun differentFingerprintChangesResponse() {
        val secret = ByteArray(32) { (it + 0x80).toByte() }
        val controllerId = "controller-01"
        val challengeId = "challenge-2026-07-30"
        val clientNonce = ByteArray(32) { (it + 0xa0).toByte() }
        val serverNonce = ByteArray(32) { (it + 0xc0).toByte() }
        val a = AuthTranscript.hmac(secret, ByteArray(32), controllerId, challengeId, clientNonce, serverNonce)
        val b = AuthTranscript.hmac(secret, ByteArray(32) { 1 }, controllerId, challengeId, clientNonce, serverNonce)
        assertEquals(false, a.contentEquals(b))
    }
}
