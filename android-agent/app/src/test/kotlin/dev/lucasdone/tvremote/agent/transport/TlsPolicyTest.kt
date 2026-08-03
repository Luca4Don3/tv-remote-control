package dev.lucasdone.tvremote.agent.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TlsPolicyTest {
    private val aead = listOf("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")
    private val cbcFallback = listOf(TlsPolicy.FALLBACK_CBC_CIPHER)
    private val otherCbc = listOf("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA")

    @Test
    fun api21AndAboveRequiresAeadOnly() {
        // API 21+：即使只支持 CBC 也拒绝，保持严格 AEAD 策略。
        assertEquals(emptyList<String>(), TlsPolicy.selectCiphers(cbcFallback + otherCbc, 21))
        assertEquals(emptyList<String>(), TlsPolicy.selectCiphers(cbcFallback, 34))
        assertEquals(aead, TlsPolicy.selectCiphers(aead, 21))
    }

    @Test
    fun pre21FallsBackToCbcWhenNoAeadAvailable() {
        // API 19/20 无 AEAD：回退到唯一广泛支持的 TLS1.2 CBC 套件，保证遥控链路可用。
        assertEquals(cbcFallback, TlsPolicy.selectCiphers(cbcFallback + otherCbc, 19))
        assertEquals(cbcFallback, TlsPolicy.selectCiphers(otherCbc + cbcFallback, 20))
        // 连回退套件都不支持时仍为空，configure() 将显式失败。
        assertEquals(emptyList<String>(), TlsPolicy.selectCiphers(otherCbc, 19))
    }

    @Test
    fun pre21PrefersAeadWhenAvailable() {
        // API 19/20 若实际支持 AEAD，仍优先使用 AEAD，不回退到 CBC。
        assertEquals(aead, TlsPolicy.selectCiphers(aead + cbcFallback, 19))
    }

    @Test
    fun unavailableProtocolsAndCipherSuitesFailExplicitly() {
        assertThrows(IllegalStateException::class.java) {
            TlsPolicy.selectProtocols(listOf("TLSv1.3"))
        }
        assertThrows(IllegalStateException::class.java) {
            TlsPolicy.requireApprovedCiphers(otherCbc, 34)
        }
    }
}
