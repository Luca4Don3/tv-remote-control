package dev.lucasdone.tvremote.controller.data

/** 默认 TLS 控制端口（发现返回 controlPort 时以返回值为准）。 */
const val DEFAULT_CONTROL_PORT: Int = 47_832

/**
 * 已配对电视的本地记录。
 *
 * 身份以电视证书指纹为准（[id] == [certificateFingerprintHex]）；IP/端口只作为可变的
 * 最近端点，不参与身份判断——电视换地址后仍能识别为同一设备。
 */
data class StoredDevice(
    val id: String,
    val displayName: String,
    val controllerId: String,
    val secret: ByteArray,
    val tvCertificateFingerprint: ByteArray,
    val certificateFingerprintHex: String,
    val lastHost: String?,
    val lastPort: Int,
    val lastUsedAtMs: Long,
    val tvDisplayName: String?,
) {
    fun withName(newName: String): StoredDevice = copy(displayName = newName)

    fun withEndpoint(host: String, port: Int, tvName: String?, nowMs: Long): StoredDevice =
        copy(lastHost = host, lastPort = port, lastUsedAtMs = nowMs, tvDisplayName = tvName ?: tvDisplayName)

    // secret 为字节数组，data class 默认 equals 不适用；身份只由 id 决定。
    override fun equals(other: Any?): Boolean = other is StoredDevice && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** 凭据加载结果：不可读取的记录必须显式暴露，不能伪装成“未配对”。 */
data class DeviceLoad(
    val devices: List<StoredDevice>,
    val unreadableIds: List<String> = emptyList(),
)

/** 凭据存储失败：必须显式抛出，禁止静默丢弃（凭据未落盘不得发 pair_store_ack）。 */
class CredentialStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 凭据存储抽象：生产实现使用 Android Keystore（[KeystoreCredentialStore]），
 * 单元测试用伪实现验证失败路径。
 */
interface CredentialStore {
    fun load(): DeviceLoad

    /** 读取有效记录；解密失败抛 [CredentialStoreException]，不存在返回 null。 */
    fun find(id: String): StoredDevice?

    /** 持久化为有效记录；失败必须抛 [CredentialStoreException]。 */
    fun save(device: StoredDevice)

    /**
     * 持久化为「待完成配对」记录，**不覆盖**同指纹的有效记录。
     * 配对确认（`pair_complete`）后由 [promotePending] 切换为有效。
     */
    fun savePending(device: StoredDevice)

    fun promotePending(id: String)

    fun discardPending(id: String)

    fun rename(id: String, newName: String)

    fun remove(id: String)

    fun markUsed(id: String, host: String, port: Int, tvDisplayName: String?, nowMs: Long)
}
