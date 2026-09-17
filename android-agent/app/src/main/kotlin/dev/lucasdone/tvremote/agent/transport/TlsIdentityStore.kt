@file:Suppress("DEPRECATION")

package dev.lucasdone.tvremote.agent.transport

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import dev.lucasdone.tvremote.agent.auth.authorizationIncompatible
import dev.lucasdone.tvremote.agent.auth.isPermanentInvalidation
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

data class TlsIdentity(
    val sslContext: SSLContext,
    val certificateFingerprint: ByteArray,
    /** True when a previously stored identity was replaced; controllers must pair again. */
    val regenerated: Boolean = false,
)

class TlsIdentityStore(context: Context) {
    private val applicationContext = context.applicationContext

    private var regeneratedThisLoad = false
    private val recovery = TlsIdentityRecovery<KeyStore.PrivateKeyEntry>(
        isPermanentInvalidation = { isPermanentInvalidation(it) },        authorizationIncompatible = { keyInfoRejectsSign(loadEntryOrNull()) },
        deleteIdentity = {
            Log.w(TAG, "Recreating TLS identity (confirmed unusable)")
            regeneratedThisLoad = true
            deleteEntry()
        },
        generateIdentity = { generateKeyPair(KEY_ALIAS) },
    )

    @Synchronized
    fun loadOrCreate(): TlsIdentity {
        regeneratedThisLoad = false
        val entry = recovery.resolve { load() }
        val certificate = entry.certificate as X509Certificate
        val keyManager = FixedServerKeyManager(entry.privateKey, arrayOf(certificate))
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), null, SecureRandom())
        }
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return TlsIdentity(sslContext, fingerprint, regeneratedThisLoad)
    }

    /**
     * Loads the identity without ever turning a read failure into [TlsLoad.Absent]: a missing alias is
     * the only case that triggers first-run generation.
     */
    private fun load(): TlsLoad<KeyStore.PrivateKeyEntry> {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (error: Exception) {
            return TlsLoad.Unusable(null, error)
        }
        if (!keyStore.containsAlias(KEY_ALIAS)) return TlsLoad.Absent
        val entry = try {
            keyStore.getEntry(KEY_ALIAS, null)
        } catch (error: Exception) {
            return TlsLoad.Unusable(null, error)
        } as? KeyStore.PrivateKeyEntry
            ?: return TlsLoad.Unusable(null, IllegalStateException("TLS alias is not a private-key entry"))
        if (!signingRoundTripSucceeds(entry)) {
            return TlsLoad.Unusable(entry, IllegalStateException("TLS identity failed its signing self-test"))
        }
        return TlsLoad.Usable(entry)
    }

    private fun loadEntryOrNull(): KeyStore.PrivateKeyEntry? = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            .getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
    } catch (_: Exception) {
        null
    }

    /** Only SIGN (the private-key operation) and the digests/paddings actually used are checked. */
    @SuppressLint("NewApi")
    private fun keyInfoRejectsSign(entry: KeyStore.PrivateKeyEntry?): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        val key = entry ?: return false
        val info = try {
            KeyFactory.getInstance(key.privateKey.algorithm, ANDROID_KEYSTORE)
                .getKeySpec(key.privateKey, KeyInfo::class.java)
        } catch (_: Exception) {
            return false
        }
        return authorizationIncompatible(
            purposes = info.purposes,
            digests = info.digests?.toSet().orEmpty(),
            paddings = info.signaturePaddings?.toSet().orEmpty(),
            requiredPurpose = KeyProperties.PURPOSE_SIGN,
            requiredDigest = KeyProperties.DIGEST_SHA256,
            requiredPadding = KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
            extraPadding = if (tls13Available()) KeyProperties.SIGNATURE_PADDING_RSA_PSS else null,
        )
    }

    /**
     * The key must sign with PKCS#1 (all versions) and, when TLS 1.3 is available, with PSS, which
     * TLS 1.3 mandates. Older platforms never negotiate TLS 1.3, so PKCS#1 alone is sufficient there
     * and re-creating a working key (which invalidates pairings) is avoided.
     */
    private fun signingRoundTripSucceeds(entry: KeyStore.PrivateKeyEntry): Boolean =
        signatureRoundTrip(entry, "SHA256withRSA") &&
            (!tls13Available() || signatureRoundTrip(entry, "SHA256withRSA/PSS"))

    private fun tls13Available(): Boolean = try {
        SSLContext.getInstance("TLS").apply { init(null, null, null) }
            .supportedSSLParameters.protocols.contains("TLSv1.3")
    } catch (_: Exception) {
        false
    }

    private fun signatureRoundTrip(entry: KeyStore.PrivateKeyEntry, algorithm: String): Boolean = try {
        val sample = ByteArray(32).also(SecureRandom()::nextBytes)
        val signed = Signature.getInstance(algorithm).apply {
            initSign(entry.privateKey)
            update(sample)
        }.sign()
        Signature.getInstance(algorithm).run {
            initVerify(entry.certificate)
            update(sample)
            verify(signed)
        }
    } catch (_: Exception) {
        false
    }

    private fun deleteEntry() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun generateKeyPair(alias: String) {
        val generator = KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE)
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
        val serial = BigInteger(160, SecureRandom()).abs().add(BigInteger.ONE)
        val subject = X500Principal("CN=TV Remote Agent")
        if (Build.VERSION.SDK_INT >= 23) {
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setKeySize(2048)
                    // SHA384 covers ...AES_256_GCM_SHA384; RSA-PSS is mandatory for TLS 1.3 CertificateVerify.
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                    )
                    .setCertificateSubject(subject)
                    .setCertificateSerialNumber(serial)
                    .setCertificateNotBefore(start.time)
                    .setCertificateNotAfter(end.time)
                    .build(),
            )
        } else {
            generator.initialize(
                KeyPairGeneratorSpec.Builder(applicationContext)
                    .setAlias(alias)
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
        private const val TAG = "TvrcTlsIdentity"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tv_remote_tls_identity_v1"
    }
}
