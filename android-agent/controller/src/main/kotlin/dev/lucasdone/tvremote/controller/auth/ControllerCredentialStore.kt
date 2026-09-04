package dev.lucasdone.tvremote.controller.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 手机控制端凭据存储：AndroidKeyStore AES-256-GCM 主密钥加密
 * SharedPreferences 中的每台电视记录（secret / TV 证书指纹 / controllerId）。
 * minSdk 24（本模块下限），直接使用 KeyGenParameterSpec。
 */
class ControllerCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class TvCredential(
        val controllerId: String,
        val secret: ByteArray,
        val tvCertificateFingerprint: ByteArray,
        val displayName: String,
    )

    @Synchronized
    fun save(tvAddress: String, credential: TvCredential) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey(), GCMParameterSpec(128, ByteArray(12)))
        val payload = linkedMapOf(
            "controllerId" to credential.controllerId,
            "secret" to android.util.Base64.encodeToString(credential.secret, android.util.Base64.NO_WRAP),
            "fingerprint" to android.util.Base64.encodeToString(credential.tvCertificateFingerprint, android.util.Base64.NO_WRAP),
            "displayName" to credential.displayName,
        )
        val json = payload.entries.joinToString("|") { (k, v) -> "${k.length}:$k=$v" }
        val encrypted = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("tv.$tvAddress", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
            .putString("tv.$tvAddress.name", credential.displayName)
            .apply()
    }

    @Synchronized
    fun load(tvAddress: String): TvCredential? {
        val stored = prefs.getString("tv.$tvAddress", null) ?: return null
        val bytes = android.util.Base64.decode(stored, android.util.Base64.DEFAULT)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            val fields = json.split("|").associate { entry ->
                val lenSep = entry.indexOf(":")
                val key = entry.substring(lenSep + 1, entry.indexOf("="))
                val value = entry.substring(entry.indexOf("=") + 1)
                key to value
            }
            TvCredential(
                controllerId = fields.getValue("controllerId"),
                secret = android.util.Base64.decode(fields.getValue("secret"), android.util.Base64.DEFAULT),
                tvCertificateFingerprint = android.util.Base64.decode(fields.getValue("fingerprint"), android.util.Base64.DEFAULT),
                displayName = fields.getValue("displayName"),
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun remove(tvAddress: String) {
        prefs.edit().remove("tv.$tvAddress").remove("tv.$tvAddress.name").apply()
    }

    @Synchronized
    fun pairedAddresses(): List<String> =
        prefs.all.keys.filter { it.startsWith("tv.") && it.endsWith(".name") }
            .map { it.removePrefix("tv.").removeSuffix(".name") }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
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

    companion object {
        private const val PREFS_NAME = "tvrc_controller_credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_ALIAS = "tvrc_controller_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
