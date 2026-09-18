package dev.tvremote.controller.data

import android.content.Context
import android.util.Base64
import dev.tvremote.agent.protocol.Hex
import dev.tvremote.agent.protocol.JsonValue
import dev.tvremote.agent.protocol.StrictJson
import dev.tvremote.agent.protocol.jsonLong
import dev.tvremote.agent.protocol.jsonObject
import dev.tvremote.agent.protocol.jsonString
import dev.tvremote.agent.protocol.requireString

/**
 * Android Keystore 保护的凭据存储（API 21+）。
 *
 * - 记录按证书指纹（`device.<fingerprintHex>`）存储；IP 只作为最近端点。
 * - 每条记录带密钥版本前缀（[CredentialCipher.id]），按版本选择解密实现，
 *   支持 API 21–22（RSA 包裹 AES）→ API 23+（Keystore AES）跨系统升级读取与迁移。
 * - 配对采用 pending 记录：`pending.<fp>` 保底、`pair_complete` 后 [promotePending] 切换，
 *   失败不覆盖原有有效凭据。
 * - 兼容读取早期按 IP 存储的 `tv.<address>` 记录；新记录写入成功后才移除旧键。
 * - 解密失败显式计入 [DeviceLoad.unreadableIds]，不静默丢弃。
 */
class KeystoreCredentialStore(
    context: Context,
    private val currentCipher: CredentialCipher = CredentialCiphers.create(context),
    // API 21–22 的 current 本身就是 RSA 包裹实现；复用同一实例，避免两个实例
    // 并发生成/覆盖同一包裹密钥导致记录间歇性无法解密。
    private val legacyCipher: CredentialCipher =
        if (currentCipher.id == CredentialCipher.RSA_WRAPPED_AES) currentCipher else RsaWrappedAesCipher(context),
) : CredentialStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // 上次进程在 promote 前中断的 pending 记录无法确认电视端状态，启动时清理
        discardStalePending()
    }

    @Synchronized
    override fun load(): DeviceLoad {
        val merged = LinkedHashMap<String, StoredDevice>()
        val unreadable = mutableListOf<String>()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(DEVICE_PREFIX)) return@forEach
            val id = key.removePrefix(DEVICE_PREFIX)
            val raw = value as? String ?: return@forEach
            try {
                val device = decodeRecord(id, raw)
                merged[id] = device
                migrateIfNeeded(id, raw)
            } catch (_: Exception) {
                unreadable += id
            }
        }
        legacyRecords().forEach { legacy -> if (legacy.id !in merged) merged[legacy.id] = legacy }
        return DeviceLoad(
            devices = merged.values.sortedByDescending { it.lastUsedAtMs },
            unreadableIds = unreadable,
            pending = pendingRecords(),
        )
    }

    /** 已尝试发送 ack 的待确认凭据（需认证确认）；未尝试发送的由启动清理丢弃。 */
    private fun pendingRecords(): List<StoredDevice> {
        val result = mutableListOf<StoredDevice>()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(PENDING_PREFIX) || key.endsWith(ACK_SUFFIX)) return@forEach
            // 已尝试（"1"）或旧格式缺标记（null）都保留；仅显式未尝试（"0"）由启动清理
            if (prefs.getString(key + ACK_SUFFIX, null) == ACK_NOT_ATTEMPTED) return@forEach
            val raw = value as? String ?: return@forEach
            val id = key.removePrefix(PENDING_PREFIX)
            runCatching { decodeRecord(id, raw) }.getOrNull()?.let { result += it }
        }
        return result
    }

    @Synchronized
    override fun find(id: String): StoredDevice? {
        prefs.getString(DEVICE_PREFIX + id, null)?.let { raw ->
            return decodeRecord(id, raw) // 解密失败抛出，不伪装成未配对
        }
        return legacyRecords().firstOrNull { it.id == id }
    }

    @Synchronized
    override fun save(device: StoredDevice) {
        writeRecord(DEVICE_PREFIX + device.id, device)
        // 新记录确认成功后，移除同指纹的旧 IP 记录
        removeLegacyFor(device.certificateFingerprintHex)
    }

    @Synchronized
    override fun savePending(device: StoredDevice) {
        writePending(PENDING_PREFIX + device.id, device)
        // 新 pending 显式标记“尚未尝试发送 ack”（旧格式缺标记按已尝试保守保留）
        prefs.edit().putString(PENDING_PREFIX + device.id + ACK_SUFFIX, ACK_NOT_ATTEMPTED).commit()
    }

    @Synchronized
    override fun markPendingAckAttempted(id: String) {
        val key = PENDING_PREFIX + id
        if (prefs.getString(key, null) == null) throw CredentialStoreException("pending credential missing")
        if (!prefs.edit().putString(key + ACK_SUFFIX, ACK_ATTEMPTED).commit()) {
            throw CredentialStoreException("failed to persist ack-attempted flag")
        }
    }

    @Synchronized
    override fun promotePending(id: String) {
        val pendingKey = PENDING_PREFIX + id
        val raw = prefs.getString(pendingKey, null) ?: throw CredentialStoreException("pending credential missing")
        val device = decodeRecord(id, raw)
        val encoded = encodeRecord(device)
        val committed = prefs.edit()
            .putString(DEVICE_PREFIX + id, encoded)
            .remove(pendingKey)
            .remove(pendingKey + ACK_SUFFIX)
            .commit()
        if (!committed) throw CredentialStoreException("failed to promote credential")
        removeLegacyFor(device.certificateFingerprintHex)
    }

    @Synchronized
    override fun discardPending(id: String) {
        prefs.edit()
            .remove(PENDING_PREFIX + id)
            .remove(PENDING_PREFIX + id + ACK_SUFFIX)
            .commit()
    }

    @Synchronized
    override fun rename(id: String, newName: String) {
        val device = find(id) ?: throw CredentialStoreException("device not found: $id")
        save(device.withName(newName))
    }

    @Synchronized
    override fun remove(id: String) {
        val committed = prefs.edit()
            .remove(DEVICE_PREFIX + id)
            .remove(PENDING_PREFIX + id)
            .remove(PENDING_PREFIX + id + ACK_SUFFIX)
            .commit()
        if (!committed) throw CredentialStoreException("failed to persist device removal")
        // 即使有效记录已损坏（find 失败），也按指纹清理同设备的旧 IP 记录，避免设备“复活”
        removeLegacyFor(id)
    }

    @Synchronized
    override fun markUsed(id: String, host: String, port: Int, tvDisplayName: String?, nowMs: Long) {
        val device = find(id) ?: return
        save(device.withEndpoint(host, port, tvDisplayName, nowMs))
    }

    // ---- 编码 / 加密 ----

    private fun encodeRecord(device: StoredDevice): String {
        val json = StrictJson.encode(
            jsonObject(
                "controllerId" to jsonString(device.controllerId),
                "secret" to jsonString(Base64.encodeToString(device.secret, Base64.NO_WRAP)),
                "fingerprint" to jsonString(Base64.encodeToString(device.tvCertificateFingerprint, Base64.NO_WRAP)),
                "name" to jsonString(device.displayName),
                "tvName" to jsonString(device.tvDisplayName.orEmpty()),
                "lastHost" to jsonString(device.lastHost.orEmpty()),
                "lastPort" to jsonLong(device.lastPort.toLong()),
                "lastUsedAtMs" to jsonLong(device.lastUsedAtMs),
            ),
        )
        val encrypted = currentCipher.encrypt(json)
        return currentCipher.id + CIPHER_SEPARATOR + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun writeRecord(key: String, device: StoredDevice) {
        val encoded = try {
            encodeRecord(device)
        } catch (error: Exception) {
            throw CredentialStoreException("failed to encrypt credential", error)
        }
        if (!prefs.edit().putString(key, encoded).commit()) {
            throw CredentialStoreException("failed to persist credential")
        }
    }

    private fun writePending(key: String, device: StoredDevice) {
        val encoded = try {
            encodeRecord(device)
        } catch (error: Exception) {
            throw CredentialStoreException("failed to encrypt pending credential", error)
        }
        if (!prefs.edit().putString(key, encoded).commit()) {
            throw CredentialStoreException("failed to persist pending credential")
        }
    }

    private fun decodeRecord(id: String, raw: String): StoredDevice {
        val obj = StrictJson.parseObject(decryptStored(raw))
        val fingerprint = Base64.decode(obj.requireString("fingerprint", 128), Base64.DEFAULT)
        val fingerprintHex = Hex.encode(fingerprint)
        // 记录键必须与内容指纹一致，避免以错误身份连接
        if (fingerprintHex != id) throw CredentialStoreException("credential id does not match certificate fingerprint")
        return StoredDevice(
            id = id,
            displayName = obj.requireString("name", 128),
            controllerId = obj.requireString("controllerId", 32),
            secret = Base64.decode(obj.requireString("secret", 128), Base64.DEFAULT),
            tvCertificateFingerprint = fingerprint,
            certificateFingerprintHex = fingerprintHex,
            lastHost = optionalString(obj, "lastHost"),
            lastPort = optionalLong(obj, "lastPort")?.toInt() ?: DEFAULT_CONTROL_PORT,
            lastUsedAtMs = optionalLong(obj, "lastUsedAtMs") ?: 0L,
            tvDisplayName = optionalString(obj, "tvName"),
        )
    }

    /** 按记录的密钥版本前缀选择解密实现；无前缀的旧格式依次尝试。 */
    private fun decryptStored(raw: String): ByteArray {
        val separator = raw.indexOf(CIPHER_SEPARATOR)
        if (separator > 0) {
            val cipherId = raw.substring(0, separator)
            val blob = Base64.decode(raw.substring(separator + 1), Base64.DEFAULT)
            return cipherFor(cipherId).decrypt(blob)
        }
        val blob = Base64.decode(raw, Base64.DEFAULT)
        return try {
            currentCipher.decrypt(blob)
        } catch (first: Exception) {
            if (legacyCipher.id == currentCipher.id) throw first
            legacyCipher.decrypt(blob)
        }
    }

    private fun cipherFor(cipherId: String): CredentialCipher = when (cipherId) {
        CredentialCipher.KEYSTORE_AES ->
            currentCipher.takeIf { it.id == CredentialCipher.KEYSTORE_AES }
                ?: throw CredentialStoreException("keystore credential is not readable on this device")
        CredentialCipher.RSA_WRAPPED_AES -> legacyCipher
        else -> throw CredentialStoreException("unknown credential cipher: $cipherId")
    }

    /** 旧密钥版本记录在可解密的前提下迁移到当前密钥（安全重加密）。 */
    private fun migrateIfNeeded(id: String, raw: String) {
        if (currentCipher.id != CredentialCipher.KEYSTORE_AES) return
        val separator = raw.indexOf(CIPHER_SEPARATOR)
        val alreadyCurrent = separator > 0 && raw.substring(0, separator) == currentCipher.id
        if (alreadyCurrent) return
        runCatching {
            val device = decodeRecord(id, raw)
            save(device)
        }
    }

    /** 旧格式（按 IP）：`tv.<address>` 加密 JSON {controllerId, secret, fingerprint, displayName}。 */
    private fun legacyRecords(): List<StoredDevice> {
        val devices = mutableListOf<StoredDevice>()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(LEGACY_PREFIX) || key.endsWith(".name")) return@forEach
            val raw = value as? String ?: return@forEach
            val host = key.removePrefix(LEGACY_PREFIX)
            runCatching { decodeLegacy(host, raw) }.getOrNull()?.let { devices += it }
        }
        return devices
    }

    private fun decodeLegacy(host: String, raw: String): StoredDevice {
        val obj = StrictJson.parseObject(decryptStored(raw))
        val fingerprint = Base64.decode(obj.requireString("fingerprint", 128), Base64.DEFAULT)
        val fingerprintHex = Hex.encode(fingerprint)
        return StoredDevice(
            id = fingerprintHex,
            displayName = obj.requireString("displayName", 128),
            controllerId = obj.requireString("controllerId", 32),
            secret = Base64.decode(obj.requireString("secret", 128), Base64.DEFAULT),
            tvCertificateFingerprint = fingerprint,
            certificateFingerprintHex = fingerprintHex,
            lastHost = host.takeIf { it.isNotBlank() },
            lastPort = DEFAULT_CONTROL_PORT,
            lastUsedAtMs = 0L,
            tvDisplayName = null,
        )
    }

    private fun removeLegacyFor(fingerprintHex: String) {
        val edit = prefs.edit()
        var changed = false
        legacyRecords().filter { it.certificateFingerprintHex == fingerprintHex }.forEach { device ->
            val host = device.lastHost ?: return@forEach
            edit.remove(LEGACY_PREFIX + host)
            edit.remove(LEGACY_PREFIX + host + ".name")
            changed = true
        }
        if (changed && !edit.commit()) throw CredentialStoreException("failed to clean legacy credential")
    }

    private fun discardStalePending() {
        // 只清理“确定未尝试发送 ack”的 pending；已尝试的保留以便认证对账（电视可能已激活）
        // 只清理显式标记“未尝试发送”的新格式 pending；缺标记的旧格式保守保留
        val stale = prefs.all.keys
            .filter { it.startsWith(PENDING_PREFIX) && !it.endsWith(ACK_SUFFIX) }
            .filter { prefs.getString(it + ACK_SUFFIX, null) == ACK_NOT_ATTEMPTED }
        if (stale.isEmpty()) return
        repeat(2) {
            val edit = prefs.edit()
            stale.forEach {
                edit.remove(it)
                edit.remove(it + ACK_SUFFIX)
            }
            if (edit.commit()) return
        }
    }

    private fun optionalString(obj: JsonValue.ObjectValue, name: String): String? =
        (obj[name] as? JsonValue.StringValue)?.value?.takeIf { it.isNotEmpty() }

    private fun optionalLong(obj: JsonValue.ObjectValue, name: String): Long? =
        (obj[name] as? JsonValue.NumberValue)?.source?.toLongOrNull()

    companion object {
        private const val PREFS_NAME = "tvrc_controller_credentials"
        private const val DEVICE_PREFIX = "device."
        private const val PENDING_PREFIX = "pending."
        private const val ACK_SUFFIX = ".ack"
        private const val ACK_ATTEMPTED = "1"
        private const val ACK_NOT_ATTEMPTED = "0"
        private const val LEGACY_PREFIX = "tv."
        private const val CIPHER_SEPARATOR = ':'
    }
}
