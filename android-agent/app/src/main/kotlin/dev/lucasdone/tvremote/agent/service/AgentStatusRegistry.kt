package dev.lucasdone.tvremote.agent.service

import dev.lucasdone.tvremote.agent.auth.PairingSas
import dev.lucasdone.tvremote.agent.auth.PairingWindow
import dev.lucasdone.tvremote.agent.auth.ControllerSummary
import java.util.concurrent.CopyOnWriteArraySet

enum class AgentNetworkState { STOPPED, STARTING, LISTENING, CONNECTED, FAILED }
enum class AgentMediaState { IDLE, ATTACHING, PERMISSION_REQUIRED, STREAMING, STREAMING_VIDEO_ONLY, FAILED }

data class PairingUiState(
    val code: String,
    val expiresAtMs: Long,
    val pairingId: String? = null,
    val controllerName: String? = null,
    val sas: String? = null,
    /** 扫码配对一次性 token（与 QR 同生命周期 60s）。 */
    val qrToken: String? = null,
    val qrTokenExpiresAtMs: Long = 0,
)

data class AgentUiSnapshot(
    val networkState: AgentNetworkState = AgentNetworkState.STOPPED,
    val statusText: String = "服务未启动",
    val pairing: PairingUiState? = null,
    val pairedControllers: List<ControllerSummary> = emptyList(),
    val mediaState: AgentMediaState = AgentMediaState.IDLE,
    val mediaRequestId: Long = 0,
)

object AgentStatusRegistry {
    fun interface Listener {
        fun onChanged(snapshot: AgentUiSnapshot)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    private var current = AgentUiSnapshot()

    fun snapshot(): AgentUiSnapshot = current

    fun addListener(listener: Listener) = synchronized(this) {
        listeners += listener
        listener.onChanged(current)
    }

    fun removeListener(listener: Listener) = synchronized(this) {
        listeners -= listener
    }

    fun starting() = update { AgentUiSnapshot(AgentNetworkState.STARTING, "正在初始化 TLS 网络遥控") }

    fun listening() = update { it.copy(networkState = AgentNetworkState.LISTENING, statusText = "等待已认证的控制端") }

    fun pairingWindow(window: PairingWindow) = update { snapshot ->
        snapshot.copy(
            pairing = PairingUiState(
                code = window.code,
                expiresAtMs = window.expiresAtMs,
                qrToken = window.qrToken,
                qrTokenExpiresAtMs = window.qrTokenExpiresAtMs,
            ),
        )
    }

    fun pairingSas(details: PairingSas) {
        update { snapshot ->
            val existing = snapshot.pairing ?: return@update snapshot
            snapshot.copy(
                pairing = existing.copy(
                    pairingId = details.pairingId,
                    controllerName = details.controllerName,
                    sas = details.sas,
                ),
            )
        }
    }

    fun pairingClosed(pairingId: String) {
        update { if (it.pairing?.pairingId == pairingId) it.copy(pairing = null) else it }
    }

    fun expirePairing(expiresAtMs: Long) {
        update { if (it.pairing?.expiresAtMs == expiresAtMs) it.copy(pairing = null) else it }
    }

    fun pairedControllers(controllers: List<ControllerSummary>) = update { it.copy(pairedControllers = controllers) }

    fun mediaAttaching() = update { it.copy(mediaState = AgentMediaState.ATTACHING) }

    fun mediaPermissionRequired(attachmentId: Long) = update {
        it.copy(
            mediaState = AgentMediaState.PERMISSION_REQUIRED,
            mediaRequestId = attachmentId,
        )
    }

    fun mediaStreaming(videoOnly: Boolean) = update {
        it.copy(
            mediaState = if (videoOnly) AgentMediaState.STREAMING_VIDEO_ONLY else AgentMediaState.STREAMING,
        )
    }

    fun mediaFailed() = update { it.copy(mediaState = AgentMediaState.FAILED) }

    fun mediaIdle() = update { it.copy(mediaState = AgentMediaState.IDLE, mediaRequestId = 0) }

    fun connected(controllerName: String) = update {
        it.copy(
            networkState = AgentNetworkState.CONNECTED,
            statusText = "已连接：$controllerName",
            pairing = null,
        )
    }

    fun disconnected() = update {
        it.copy(
            networkState = AgentNetworkState.LISTENING,
            statusText = "控制端已断开",
        )
    }

    fun failed(reason: String) = update {
        it.copy(networkState = AgentNetworkState.FAILED, statusText = reason, pairing = null)
    }

    fun stopped() = update { AgentUiSnapshot() }

    private fun update(transform: (AgentUiSnapshot) -> AgentUiSnapshot) {
        synchronized(this) {
            val snapshot = transform(current).also { current = it }
            listeners.forEach { it.onChanged(snapshot) }
        }
    }
}
