package dev.lucasdone.tvremote.xiaomi.backend

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dadb.AdbKeyPair
import dev.lucasdone.tvremote.agent.auth.KeystoreRecovery
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/** ADB private key is AES-GCM encrypted; its AES key is wrapped by Android Keystore. */
@SuppressLint("ApplySharedPref")
class AdbIdentityStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("xiaomi_adb_identity", Context.MODE_PRIVATE)
    private val recovery = KeystoreRecovery(
        keyUsable = { probeWrappingKey() || probeWrappingKey() },
        deleteKey = {
            Log.w(TAG, "Rebuilding unusable ADB wrapping key")
            deleteWrappingKey()
        },
        clearRecords = { clearIdentityRecord() },
        removeRecord = {
            Log.w(TAG, "Dropping unreadable ADB identity")
            clearIdentityRecord()
        },
    )
    @Synchronized fun loadOrCreate(): AdbKeyPair {
        val encoded = recovery.run(RECORD_ID) { readIdentity() } ?: generateIdentity()
        try {
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded)) as RSAPrivateCrtKey
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey
            val publicText = Base64.encodeToString(AdbPublicKeyEncoder.encode(publicKey), Base64.NO_WRAP) + " tvrc@local\u0000"
            return AdbKeyPair(privateKey, publicText.toByteArray(Charsets.UTF_8))
        } finally { encoded.fill(0) }
    }
    /** Re-reads the record so a repair that clears it yields null and triggers regeneration. */
    private fun readIdentity(): ByteArray? = prefs.getString("identity", null)?.let(::decrypt)
    private fun generateIdentity(): ByteArray {
        val privateBytes = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded
        persistIdentity(privateBytes)
        return privateBytes
    }
    private fun persistIdentity(privateBytes: ByteArray) {
        recovery.run(RECORD_ID) { writeIdentity(privateBytes) }
    }
    private fun writeIdentity(privateBytes: ByteArray) {
        check(prefs.edit().putString("identity", encrypt(privateBytes)).commit()) { "failed to save ADB identity" }
    }
    private fun clearIdentityRecord() {
        check(prefs.edit().remove("identity").commit()) { "failed to clear unreadable ADB identity" }
    }
    private fun deleteWrappingKey() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    }
    /** Non-destructive RSA wrap/unwrap round trip proving the alias can still be used. */
    private fun probeWrappingKey(): Boolean = try {
        val key = wrappingKey()
        val sample = ByteArray(32).also(SecureRandom()::nextBytes)
        val wrap = Cipher.getInstance(transformation())
        wrap.init(Cipher.ENCRYPT_MODE, key.certificate.publicKey)
        val wrapped = wrap.doFinal(sample)
        val unwrap = Cipher.getInstance(transformation())
        unwrap.init(Cipher.DECRYPT_MODE, key.privateKey)
        MessageDigest.isEqual(sample, unwrap.doFinal(wrapped))
    } catch (_: Exception) {
        false
    }
    private fun encrypt(bytes: ByteArray): String {
        val aes = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(bytes)
            val rsa = Cipher.getInstance(transformation())
            rsa.init(Cipher.ENCRYPT_MODE, wrappingKey().certificate.publicKey)
            val wrapped = rsa.doFinal(aes)
            val envelope = ByteBuffer.allocate(4 + wrapped.size + iv.size + encrypted.size)
                .putInt(wrapped.size).put(wrapped).put(iv).put(encrypted).array()
            return Base64.encodeToString(envelope, Base64.NO_WRAP)
        } finally { aes.fill(0) }
    }
    private fun decrypt(value: String): ByteArray {
        val raw = Base64.decode(value, Base64.NO_WRAP)
        require(raw.size in 300..8192) { "invalid encrypted ADB identity" }
        val input = ByteBuffer.wrap(raw)
        val length = input.int
        require(length == 256 && input.remaining() > length + 28) { "invalid ADB identity envelope" }
        val wrapped = ByteArray(length).also(input::get)
        val iv = ByteArray(12).also(input::get)
        val encrypted = ByteArray(input.remaining()).also(input::get)
        val rsa = Cipher.getInstance(transformation())
        rsa.init(Cipher.DECRYPT_MODE, wrappingKey().privateKey)
        val aes = rsa.doFinal(wrapped)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            return cipher.doFinal(encrypted)
        } finally { aes.fill(0) }
    }
    private fun transformation() = if (Build.VERSION.SDK_INT >= 23) "RSA/ECB/OAEPWithSHA-256AndMGF1Padding" else "RSA/ECB/PKCS1Padding"
    private fun wrappingKey(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
        if (Build.VERSION.SDK_INT >= 23) {
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256).build())
        } else {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            @Suppress("DEPRECATION")
            generator.initialize(android.security.KeyPairGeneratorSpec.Builder(context)
                .setAlias(ALIAS).setSubject(X500Principal("CN=TV Remote Local ADB"))
                .setSerialNumber(BigInteger.ONE).setStartDate(start.time).setEndDate(end.time).setKeySize(2048).build())
        }
        generator.generateKeyPair()
        return store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }
    companion object {
        private const val TAG = "TvrcAdbIdentity"
        private const val RECORD_ID = "identity"
        private const val ALIAS = "xiaomi_local_adb_wrap_v1"
    }
}
