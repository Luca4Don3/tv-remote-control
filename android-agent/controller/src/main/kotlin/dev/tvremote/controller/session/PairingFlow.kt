package dev.tvremote.controller.session

import dev.tvremote.agent.protocol.Hex
import dev.tvremote.controller.data.CredentialStore
import dev.tvremote.controller.data.StoredDevice

/**
 * 配对收尾（两阶段，R11）：
 * 1. 先以 **pending** 记录持久化新凭据，**不覆盖**同指纹的有效记录；
 * 2. 发送 `pair_store_ack` 并等待 `pair_complete`；
 * 3. 仅在确认后把 pending 提升为有效记录（旧记录此时才被替换）。
 *
 * 任一步失败都会丢弃 pending，原有可用的有效凭据保持不变。
 */
object PairingFlow {
    fun persistCredentialThenConfirm(
        session: ControllerSession,
        credential: ControllerSession.PairingCredential,
        store: CredentialStore,
        displayName: String,
        host: String,
        port: Int,
        tvName: String?,
        nowMs: Long,
    ) {
        val fingerprintHex = Hex.encode(credential.tvCertificateFingerprint)
        val device = StoredDevice(
            id = fingerprintHex,
            displayName = displayName,
            controllerId = credential.controllerId,
            secret = credential.secret,
            tvCertificateFingerprint = credential.tvCertificateFingerprint,
            certificateFingerprintHex = fingerprintHex,
            lastHost = host,
            lastPort = port,
            lastUsedAtMs = nowMs,
            tvDisplayName = tvName,
        )
        store.savePending(device)
        try {
            session.confirmCredentialStored(credential)
        } catch (error: Exception) {
            runCatching { store.discardPending(fingerprintHex) }
            throw error
        }
        store.promotePending(fingerprintHex)
    }
}
