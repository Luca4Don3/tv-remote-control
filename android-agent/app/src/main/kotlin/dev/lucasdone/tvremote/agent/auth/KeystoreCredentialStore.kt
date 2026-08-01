package dev.lucasdone.tvremote.agent.auth

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.lucasdone.tvremote.agent.protocol.Hex
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.crypto.Cipher
import javax.security.auth.x500.X500Principal

@SuppressLint("ApplySharedPref")
class KeystoreCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("paired_controllers", Context.MODE_PRIVATE)
    private val applicationContext = context.applicationContext

    @Synchronized
    fun put(controllerId: String, secret: ByteArray) {
        putRecord(
            StoredCredential(
                controllerId = controllerId,
                controllerName = controllerId,
                certificateFingerprint = ByteArray(32),
                secret = secret,
                state = CredentialState.ACTIVE,
            ),
        )
    }

    @Synchronized
    fun putPending(credential: PairedCredential) {
        putRecord(
            StoredCredential(
                controllerId = credential.controllerId,
                controllerName = credential.controllerName,
                certificateFingerprint = credential.certificateFingerprint.copyOf(),
                secret = credential.secret.copyOf(),
                state = CredentialState.PENDING,
            ),
        )
    }

    @Synchronized
    fun activate(controllerId: String) {
        requireControllerId(controllerId)
        if (preferences.getString(stateKey(controllerId), null) != CredentialState.PENDING.name) {
            throw IllegalStateException("controller credential is not pending")
        }
        check(preferences.edit().putString(stateKey(controllerId), CredentialState.ACTIVE.name).commit()) {
            "failed to activate paired controller"
        }
    }

    @Synchronized
    fun getActive(controllerId: String): StoredCredential? {
        requireControllerId(controllerId)
        if (preferences.getString(stateKey(controllerId), null) != CredentialState.ACTIVE.name) return null
        val secret = decrypt(preferences.getString(secretKey(controllerId), null) ?: return null)
        val name = preferences.getString(nameKey(controllerId), null)
            ?: throw IllegalStateException("paired controller metadata is incomplete")
        val fingerprint = Hex.decode(
            preferences.getString(fingerprintKey(controllerId), null)
                ?: throw IllegalStateException("paired controller metadata is incomplete"),
            expectedBytes = 32,
        )
        return StoredCredential(controllerId, name, fingerprint, secret, CredentialState.ACTIVE)
    }

    @Synchronized
    fun removePending(controllerId: String) {
        requireControllerId(controllerId)
        if (preferences.getString(stateKey(controllerId), null) == CredentialState.PENDING.name) remove(controllerId)
    }

    @Synchronized
    fun cleanupPending() {
        val pendingIds = storedControllerIds().filter {
            preferences.getString(stateKey(it), null) == CredentialState.PENDING.name
        }
        if (pendingIds.isEmpty()) return
        val editor = preferences.edit()
        pendingIds.forEach { removeFromEditor(editor, it) }
        check(editor.commit()) { "failed to remove incomplete pairing credentials" }
    }

    private fun putRecord(record: StoredCredential) {
        val controllerId = record.controllerId
        val secret = record.secret
        require(secret.size == 32) { "controller secret must be 256 bits" }
        requireControllerId(controllerId)
        require(record.controllerName.isNotBlank() && record.controllerName.length <= 64) { "invalid controller name" }
        require(record.certificateFingerprint.size == 32) { "certificate fingerprint must be 32 bytes" }
        if (controllerId !in storedControllerIds() && storedControllerIds().size >= MAX_CONTROLLERS) {
            throw IllegalStateException("paired controller limit reached")
        }
        val encrypted = encrypt(secret)
        check(
            preferences.edit()
                .putString(secretKey(controllerId), encrypted)
                .putString(nameKey(controllerId), record.controllerName)
                .putString(fingerprintKey(controllerId), Hex.encode(record.certificateFingerprint))
                .putString(stateKey(controllerId), record.state.name)
                .commit(),
        ) {
            "failed to persist paired controller"
        }
    }

    @Synchronized
    fun get(controllerId: String): ByteArray? {
        return getActive(controllerId)?.secret
    }

    @Synchronized
    fun remove(controllerId: String) {
        requireControllerId(controllerId)
        val editor = preferences.edit()
        removeFromEditor(editor, controllerId)
        check(editor.commit()) { "failed to revoke paired controller" }
    }

    @Synchronized
    fun controllerIds(): Set<String> = storedControllerIds().filterTo(linkedSetOf()) {
        preferences.getString(stateKey(it), null) == CredentialState.ACTIVE.name
    }

    @Synchronized
    fun controllerSummaries(): List<ControllerSummary> = controllerIds().map { controllerId ->
        val name = preferences.getString(nameKey(controllerId), null)
            ?: throw IllegalStateException("paired controller metadata is incomplete")
        ControllerSummary(controllerId, name)
    }.sortedBy { it.controllerName.lowercase() }

    private fun encrypt(secret: ByteArray): String {
        val publicKey = getOrCreateKeyPair().certificate.publicKey
        val cipher = Cipher.getInstance(rsaTransformation())
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(secret), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val privateKey = getOrCreateKeyPair().privateKey
        val cipher = Cipher.getInstance(rsaTransformation())
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
    }

    // API 23+ 使用 OAEP 填充（抗 Bleichenbacher 攻击）；API 19-22 的 AndroidKeyStore
    // 不支持 OAEP，只能保留 PKCS1 v1.5，密钥仅存于本地安全存储，攻击面有限。
    private fun rsaTransformation(): String =
        if (Build.VERSION.SDK_INT >= 23) RSA_OAEP_TRANSFORMATION else RSA_PKCS1_TRANSFORMATION

    private fun storedControllerIds(): Set<String> = preferences.all.keys
        .filter { it.startsWith(STATE_PREFIX) }
        .mapTo(linkedSetOf()) { it.removePrefix(STATE_PREFIX) }

    private fun removeFromEditor(editor: android.content.SharedPreferences.Editor, controllerId: String) {
        editor.remove(secretKey(controllerId))
            .remove(nameKey(controllerId))
            .remove(fingerprintKey(controllerId))
            .remove(stateKey(controllerId))
    }

    private fun requireControllerId(controllerId: String) {
        require(CONTROLLER_ID.matches(controllerId)) { "invalid controllerId" }
    }

    private fun secretKey(controllerId: String) = "$SECRET_PREFIX$controllerId"
    private fun nameKey(controllerId: String) = "$NAME_PREFIX$controllerId"
    private fun fingerprintKey(controllerId: String) = "$FINGERPRINT_PREFIX$controllerId"
    private fun stateKey(controllerId: String) = "$STATE_PREFIX$controllerId"

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
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .build(),
            )
        } else {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            @Suppress("DEPRECATION")
            generator.initialize(
                // 全限定名：仅旧 API 分支引用，避免 import 触发文件级弃用警告。
                android.security.KeyPairGeneratorSpec.Builder(applicationContext)
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
        private const val RSA_OAEP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private const val RSA_PKCS1_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        private const val MAX_CONTROLLERS = 8
        private const val SECRET_PREFIX = "secret."
        private const val NAME_PREFIX = "name."
        private const val FINGERPRINT_PREFIX = "fingerprint."
        private const val STATE_PREFIX = "state."
        private val CONTROLLER_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

enum class CredentialState { PENDING, ACTIVE }

data class StoredCredential(
    val controllerId: String,
    val controllerName: String,
    val certificateFingerprint: ByteArray,
    val secret: ByteArray,
    val state: CredentialState,
)

data class ControllerSummary(val controllerId: String, val controllerName: String)
