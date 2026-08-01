@file:Suppress("DEPRECATION")

package dev.lucasdone.tvremote.agent.transport

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

data class TlsIdentity(
    val sslContext: SSLContext,
    val certificateFingerprint: ByteArray,
)

class TlsIdentityStore(context: Context) {
    private val applicationContext = context.applicationContext

    @Synchronized
    fun loadOrCreate(): TlsIdentity {
        val entry = getOrCreateEntry()
        val certificate = entry.certificate as X509Certificate
        val keyManager = FixedServerKeyManager(entry.privateKey, arrayOf(certificate))
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), null, SecureRandom())
        }
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return TlsIdentity(sslContext, fingerprint)
    }

    private fun getOrCreateEntry(): KeyStore.PrivateKeyEntry {
        loadEntry()?.let { return it }
        generateIdentity()
        return loadEntry() ?: throw IllegalStateException("Android Keystore did not retain TLS identity")
    }

    private fun loadEntry(): KeyStore.PrivateKeyEntry? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
    }

    private fun generateIdentity() {
        val generator = KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE)
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
        val serial = BigInteger(160, SecureRandom()).abs().add(BigInteger.ONE)
        val subject = X500Principal("CN=TV Remote Agent")
        if (Build.VERSION.SDK_INT >= 23) {
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(subject)
                    .setCertificateSerialNumber(serial)
                    .setCertificateNotBefore(start.time)
                    .setCertificateNotAfter(end.time)
                    .build(),
            )
        } else {
            generator.initialize(
                KeyPairGeneratorSpec.Builder(applicationContext)
                    .setAlias(KEY_ALIAS)
                    .setSubject(subject)
                    .setSerialNumber(serial)
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .setKeySize(2048)
                    .build(),
            )
        }
        generator.generateKeyPair()
    }

    private class FixedServerKeyManager(
        private val privateKey: PrivateKey,
        private val certificateChain: Array<X509Certificate>,
    ) : X509KeyManager {
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            socket: java.net.Socket?,
        ): String? = null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            socket: java.net.Socket?,
        ): String? = if (keyType.equals("RSA", ignoreCase = true)) KEY_ALIAS else null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == KEY_ALIAS) certificateChain.copyOf() else null

        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null

        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == KEY_ALIAS) privateKey else null

        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? =
            if (keyType.equals("RSA", ignoreCase = true)) arrayOf(KEY_ALIAS) else null
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tv_remote_tls_identity_v1"
    }
}
