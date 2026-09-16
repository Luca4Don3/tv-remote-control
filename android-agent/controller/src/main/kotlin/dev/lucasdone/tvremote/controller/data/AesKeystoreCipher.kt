package dev.lucasdone.tvremote.controller.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * API 23+：AndroidKeyStore 中的 AES-256-GCM 主密钥。
 */
@RequiresApi(23)
class AesKeystoreCipher : CredentialCipher {
    override val id: String = CredentialCipher.KEYSTORE_AES

    override fun encrypt(plaintext: ByteArray): ByteArray = AesGcmCrypto.encrypt(masterKey(), plaintext)

    override fun decrypt(blob: ByteArray): ByteArray = AesGcmCrypto.decrypt(masterKey(), blob)

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        when (val existing = keyStore.getEntry(MASTER_ALIAS, null)) {
            is KeyStore.SecretKeyEntry -> return existing.secretKey
            null -> Unit
            // 别名存在但不是本应用期望的 AES 密钥：清除后重建，避免类型不匹配崩溃
            else -> keyStore.deleteEntry(MASTER_ALIAS)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_ALIAS = "tvrc_controller_master_key"
    }
}
