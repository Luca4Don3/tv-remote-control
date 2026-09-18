package dev.tvremote.controller.ui

import dev.tvremote.controller.net.DiscoveryClient
import dev.tvremote.controller.session.ControllerSession

enum class Screen { DEVICES, ADD_DEVICE, PAIRING, REMOTE, SETTINGS }

enum class ConnectionPhase { IDLE, CONNECTING, AUTHENTICATING, CONNECTED, DISCONNECTING, FAILED }

/** 失败分类决定是否允许重试（证书变化/凭据撤销/用户断开不自动重试）。 */
enum class FailureKind { NONE, NETWORK, CERTIFICATE, CREDENTIAL_REVOKED, PAIRING, PROTOCOL, BUSY, UNKNOWN }

data class ConnectionFailure(val kind: FailureKind, val message: String) {
    /** 断线后的自动重连仅针对网络类；其他失败交给用户显式操作。 */
    val retryable: Boolean get() = kind == FailureKind.NETWORK

    /** 是否允许用户手动重试（网络断开或电视被占用后可稍后重试）。 */
    val canRetry: Boolean get() = kind == FailureKind.NETWORK || kind == FailureKind.BUSY
}

data class DeviceSummary(
    val id: String,
    val displayName: String,
    val tvDisplayName: String?,
    val lastHost: String?,
    val lastPort: Int,
    val lastUsedAtMs: Long,
    val connected: Boolean,
)

enum class PairingChannel { CODE, QR_TOKEN }

enum class PairingPhase { INPUT, AWAITING_TV_CONFIRMATION, COMPLETING }

data class PairingUiState(
    val host: String,
    val port: Int,
    val channel: PairingChannel,
    val phase: PairingPhase = PairingPhase.INPUT,
    val sas: String? = null,
    val error: String? = null,
    /** 发现到的电视名（用于保存设备名）；扫码/手动时为 null。 */
    val tvName: String? = null,
)

enum class TextSendStatus { IDLE, SENDING, SENT, FAILED }

data class ControllerUiState(
    val version: String = "",
    val screen: Screen = Screen.DEVICES,
    val devices: List<DeviceSummary> = emptyList(),
    val activeDeviceId: String? = null,
    val connection: ConnectionPhase = ConnectionPhase.IDLE,
    val failure: ConnectionFailure? = null,
    val capabilities: ControllerSession.Capabilities? = null,
    val discovered: List<DiscoveryClient.DiscoveredTv> = emptyList(),
    val discovering: Boolean = false,
    val discoveryMessage: String? = null,
    val pairing: PairingUiState? = null,
    val notice: String? = null,
    val lastAck: String? = null,
    val textStatus: TextSendStatus = TextSendStatus.IDLE,
    /** 内存草稿（不落盘）：按设备归属，发送成功或切换设备时清除。 */
    val textDraft: String = "",
    val textDraftDeviceId: String? = null,
) {
    val activeDevice: DeviceSummary? get() = devices.firstOrNull { it.id == activeDeviceId }
    val connected: Boolean get() = connection == ConnectionPhase.CONNECTED
}
