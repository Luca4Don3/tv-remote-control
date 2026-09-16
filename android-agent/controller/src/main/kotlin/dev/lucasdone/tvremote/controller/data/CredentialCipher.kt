package dev.lucasdone.tvremote.controller.data

import android.content.Context
import android.os.Build
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 凭据加密抽象：生产实现基于 Android Keystore（不同 API 等级不同实现）。 */
interface CredentialCipher {
    /** 记录中标注的密钥版本（用于按记录格式选择解密实现）。 */
    val id: String

    /** 加密明文，返回 IV（12B）前置的密文。 */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** 解密 IV 前置的密文。 */
    fun decrypt(blob: ByteArray): ByteArray

    companion object {
        const val KEYSTORE_AES = "keystore-aes-v1"
        const val RSA_WRAPPED_AES = "rsa-aes-v1"
    }
}

object CredentialCiphers {
    /**
     * API 23+ 直接用 AndroidKeyStore 的 AES-256-GCM 主密钥；
     * API 21–22 无 `KeyGenParameterSpec`，改用 Keystore RSA 密钥对包裹软件 AES 数据密钥。
     */
    fun create(context: Context): CredentialCipher =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AesKeystoreCipher()
        } else {
            RsaWrappedAesCipher(context.applicationContext)
        }
}

/** AES-256-GCM 封装（IV 随机、前置）。GCMParameterSpec 自 API 19 可用。 */
internal object AesGcmCrypto {
    const val IV_LENGTH = 12
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > IV_LENGTH) { "credential blob is truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LENGTH)))
        return cipher.doFinal(blob.copyOfRange(IV_LENGTH, blob.size))
    }

    fun randomAesKey(): SecretKey {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return javax.crypto.spec.SecretKeySpec(bytes, "AES")
    }
}
