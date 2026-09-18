@file:Suppress("DEPRECATION")

package dev.tvremote.controller.data

import android.content.Context
import android.security.KeyPairGeneratorSpec
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * API 21–22：这些版本没有 `KeyGenParameterSpec`（AES in Keystore）。
 * 方案：AndroidKeyStore 生成 RSA-2048 密钥对，软件随机 AES-256 数据密钥由 RSA 公钥
 * 包裹后存 SharedPreferences；记录用 AES-GCM 加密。密钥材料仍受 Keystore 保护。
 */
class RsaWrappedAesCipher(context: Context) : CredentialCipher {
    override val id: String = CredentialCipher.RSA_WRAPPED_AES
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun encrypt(plaintext: ByteArray): ByteArray = AesGcmCrypto.encrypt(aesKey(), plaintext)

    override fun decrypt(blob: ByteArray): ByteArray = AesGcmCrypto.decrypt(aesKey(), blob)

    @Synchronized
    private fun aesKey(): SecretKey {
        val wrapped = prefs.getString(WRAPPED_KEY, null)
        if (wrapped != null) {
            val unwrapped = rsaDecrypt(Base64.decode(wrapped, Base64.NO_WRAP))
            require(unwrapped.size == 32) { "unexpected AES key size" }
            return SecretKeySpec(unwrapped, "AES")
        }
        val key = AesGcmCrypto.randomAesKey()
        val wrappedBytes = rsaEncrypt(key.encoded)
        val committed = prefs.edit()
            .putString(WRAPPED_KEY, Base64.encodeToString(wrappedBytes, Base64.NO_WRAP))
            .commit()
        if (!committed) throw CredentialStoreException("failed to persist wrapped AES key")
        return key
    }

    private fun rsaEncrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, rsaKeyPair().public)
        return cipher.doFinal(data)
    }

    private fun rsaDecrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, rsaKeyPair().private)
        return cipher.doFinal(data)
    }

    private fun rsaKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(MASTER_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { entry ->
            return KeyPair(entry.certificate.publicKey, entry.privateKey)
        }
        // 别名存在但不是 RSA 私钥条目：清除后重建
        keyStore.deleteEntry(MASTER_ALIAS)
        @Suppress("DEPRECATION")
        val spec = KeyPairGeneratorSpec.Builder(appContext)
            .setAlias(MASTER_ALIAS)
            .setSubject(X500Principal("CN=tvrc-controller-master"))
            .setSerialNumber(BigInteger.ONE)
            .setStartDate(Date())
            .setEndDate(Date(System.currentTimeMillis() + KEY_VALIDITY_MS))
            .setKeySize(2048)
            .build()
        val generator = KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE)
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_ALIAS = "tvrc_controller_master_rsa"
        const val PREFS_NAME = "tvrc_controller_credentials"
        const val WRAPPED_KEY = "master_rsa_wrapped_aes_key"
        const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        const val KEY_VALIDITY_MS = 30L * 365L * 24L * 3600L * 1000L
    }
}
