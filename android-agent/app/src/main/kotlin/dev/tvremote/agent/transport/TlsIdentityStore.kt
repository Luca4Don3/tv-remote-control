@file:Suppress("DEPRECATION")

package dev.tvremote.agent.transport

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import dev.tvremote.agent.auth.authorizationIncompatible
import dev.tvremote.agent.auth.isPermanentInvalidation
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
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
        selfTest = { entry -> signingSelfTestError(entry) },
        isPermanentInvalidation = { isPermanentInvalidation(it) },
        authorizationIncompatible = { entry -> keyInfoRejectsSign(entry) },
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
        cleanupLegacyAliases()
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
     * the only case that triggers first-run generation. The signing self-test runs in the recovery
     * policy so its original exception reaches the permanent/authorization decision.
     */
    private fun load(): TlsLoad<KeyStore.PrivateKeyEntry> {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (error: Exception) {
            return TlsLoad.Failed(error)
        }
        if (!keyStore.containsAlias(KEY_ALIAS)) return TlsLoad.Absent
        val entry = try {
            keyStore.getEntry(KEY_ALIAS, null)
        } catch (error: Exception) {
            return TlsLoad.Failed(error)
        } as? KeyStore.PrivateKeyEntry
            ?: return TlsLoad.Failed(IllegalStateException("TLS alias is not a private-key entry"))
        return TlsLoad.Loaded(entry)
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

    private fun tls13Available(): Boolean = try {
        SSLContext.getInstance("TLS").apply { init(null, null, null) }
            .supportedSSLParameters.protocols.contains("TLSv1.3")
    } catch (_: Exception) {
        false
    }

    /**
     * The key must sign with PKCS#1 (all versions) and, when TLS 1.3 is available, with PSS. Returns
     * the original failure so the recovery policy can see a permanent invalidation through the cause.
     */
    private fun signingSelfTestError(entry: KeyStore.PrivateKeyEntry): Throwable? =
        tlsSignatureRoundTripError(entry.privateKey, entry.certificate.publicKey, "SHA256withRSA")
            ?: if (tls13Available()) {
                tlsSignatureRoundTripError(entry.privateKey, entry.certificate.publicKey, "SHA256withRSA/PSS")
            } else {
                null
            }

    private fun deleteEntry() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    /**
     * Older identities were generated without [KeyProperties.DIGEST_NONE], so API 23/24 conscrypt's
     * `NONEwithRSA` handshake signature was rejected ("Incompatible digest"). KeyStore key digests are
     * immutable, so a key that predates this fix must be dropped; controllers then re-pair.
     */
    private fun cleanupLegacyAliases() {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (_: Exception) {
            return
        }
        for (alias in LEGACY_KEY_ALIASES) {
            try {
                if (keyStore.containsAlias(alias)) {
                    Log.w(TAG, "Dropping legacy TLS identity alias $alias (no NONE digest for the TLS handshake)")
                    regeneratedThisLoad = true
                    keyStore.deleteEntry(alias)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not drop legacy TLS alias $alias: ${error.javaClass.simpleName}")
            }
        }
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
                    // TLS 栈（API 23/24 conscrypt）用 NONEwithRSA 完成握手签名（摘要由栈计算），
                    // 密钥必须显式授权 DIGEST_NONE，否则握手报 "Incompatible digest"。
                    // SHA384 覆盖 ...AES_256_GCM_SHA384；RSA-PSS 为 TLS 1.3 CertificateVerify 所需。
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512,
                    )
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
        private const val KEY_ALIAS = "tv_remote_tls_identity_v2"
        private val LEGACY_KEY_ALIASES = listOf("tv_remote_tls_identity_v1")
    }
}

/**
 * Signs a random sample with [privateKey] and verifies it with [publicKey]. Returns null on success,
 * or the failure — including a signature that simply does not verify — so the caller can decide
 * whether an identity is usable. Extracted from the store so it is testable without Android.
 */
internal fun tlsSignatureRoundTripError(
    privateKey: PrivateKey,
    publicKey: PublicKey,
    algorithm: String,
): Throwable? = try {
    val sample = ByteArray(32).also(SecureRandom()::nextBytes)
    val signed = Signature.getInstance(algorithm).apply {
        initSign(privateKey)
        update(sample)
    }.sign()
    val verified = Signature.getInstance(algorithm).run {
        initVerify(publicKey)
        update(sample)
        verify(signed)
    }
    if (verified) null else SignatureVerificationFailedException()
} catch (error: Exception) {
    error
}

/** Returned as a value when a signature does not verify against the certificate public key. */
internal class SignatureVerificationFailedException : IllegalStateException("TLS signature did not verify")
