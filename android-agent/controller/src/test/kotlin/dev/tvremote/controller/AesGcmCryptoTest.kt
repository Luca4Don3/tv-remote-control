package dev.tvremote.controller

import dev.tvremote.controller.data.AesGcmCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AES-GCM 封装（API 21+ 与 23+ 两条路径共用）：IV 前置、随机、防篡改。 */
class AesGcmCryptoTest {
    @Test
    fun roundTripPreservesPlaintext() {
        val key = AesGcmCrypto.randomAesKey()
        val plaintext = "controller-credential-json".toByteArray()
        val blob = AesGcmCrypto.encrypt(key, plaintext)
        assertTrue("IV + 密文", blob.size > AesGcmCrypto.IV_LENGTH)
        assertArrayEquals(plaintext, AesGcmCrypto.decrypt(key, blob))
        assertFalse("密文不得等于明文", blob.copyOfRange(AesGcmCrypto.IV_LENGTH, blob.size).contentEquals(plaintext))
    }

    @Test
    fun ivIsRandomPerEncryption() {
        val key = AesGcmCrypto.randomAesKey()
        val plaintext = "same".toByteArray()
        val first = AesGcmCrypto.encrypt(key, plaintext)
        val second = AesGcmCrypto.encrypt(key, plaintext)
        assertFalse(first.copyOfRange(0, AesGcmCrypto.IV_LENGTH).contentEquals(second.copyOfRange(0, AesGcmCrypto.IV_LENGTH)))
    }

    @Test(expected = Exception::class)
    fun tamperedBlobFailsAuthentication() {
        val key = AesGcmCrypto.randomAesKey()
        val blob = AesGcmCrypto.encrypt(key, "secret".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        AesGcmCrypto.decrypt(key, blob)
    }
}
