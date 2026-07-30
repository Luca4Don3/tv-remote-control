@file:Suppress("DEPRECATION")

package dev.lucasdone.tvremote.agent.auth

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.crypto.Cipher
import javax.security.auth.x500.X500Principal

class KeystoreCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("paired_controllers", Context.MODE_PRIVATE)
    private val applicationContext = context.applicationContext

    @Synchronized
    fun put(controllerId: String, secret: ByteArray) {
        require(secret.size == 32) { "controller secret must be 256 bits" }
        val publicKey = getOrCreateKeyPair().certificate.publicKey
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        preferences.edit().putString(controllerId, Base64.encodeToString(cipher.doFinal(secret), Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun get(controllerId: String): ByteArray? {
        val encoded = preferences.getString(controllerId, null) ?: return null
        val privateKey = getOrCreateKeyPair().privateKey
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
    }

    @Synchronized
    fun remove(controllerId: String) {
        preferences.edit().remove(controllerId).apply()
    }

    fun controllerIds(): Set<String> = preferences.all.keys

    private fun getOrCreateKeyPair(): KeyStore.PrivateKeyEntry {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }

        val generator = KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE)
        if (Build.VERSION.SDK_INT >= 23) {
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(2048)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .build(),
            )
        } else {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            @Suppress("DEPRECATION")
            generator.initialize(
                KeyPairGeneratorSpec.Builder(applicationContext)
                    .setAlias(KEY_ALIAS)
                    .setSubject(X500Principal("CN=TV Remote Agent"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .setKeySize(2048)
                    .build(),
            )
        }
        generator.generateKeyPair()
        return keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tv_remote_controller_wrap_key"
        private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    }
}
