package dev.lucasdone.tvremote.controller.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lucasdone.tvremote.controller.BuildConfig
import dev.lucasdone.tvremote.controller.data.KeystoreCredentialStore
import dev.lucasdone.tvremote.controller.net.DiscoveryClient
import dev.lucasdone.tvremote.controller.net.TvConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * 薄壳 ViewModel：仅装配 Android 依赖（Keystore 存储、TLS 传输、资源字符串）
 * 并把命令转发给纯 Kotlin 的 [ControllerOrchestrator]（便于 JVM 测试编排逻辑）。
 */
class ControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val orchestrator = ControllerOrchestrator(
        scope = viewModelScope,
        ioDispatcher = Dispatchers.IO,
        store = KeystoreCredentialStore(application),
        transportFactory = { host, port, pinned -> TvConnection.connect(host, port, pinned) },
        probe = { DiscoveryClient.probe() },
        strings = { id, args -> application.getString(id, *args) },
        version = BuildConfig.VERSION_NAME,
        closeSessionResource = { it.close() },
    )

    val state: StateFlow<ControllerUiState> = orchestrator.state

    fun onForeground() = orchestrator.onForeground()
    fun onBackground() = orchestrator.onBackground()

    fun selectDevice(deviceId: String) = orchestrator.selectDevice(deviceId)
    fun renameDevice(deviceId: String, newName: String) = orchestrator.renameDevice(deviceId, newName)
    fun forgetDevice(deviceId: String) = orchestrator.forgetDevice(deviceId)
    fun disconnect() = orchestrator.disconnect()

    fun beginKeyPress(key: String) = orchestrator.beginKeyPress(key)
    fun endKeyPress(key: String) = orchestrator.endKeyPress(key)
    fun releaseAllRepeats() = orchestrator.releaseAllRepeats()

    fun connectToEndpoint(deviceId: String, host: String, port: Int) =
        orchestrator.connectToEndpoint(deviceId, host, port)

    fun openAddDevice() = orchestrator.openAddDevice()
    fun discover() = orchestrator.discover()

    fun openPairing(host: String, port: Int, channel: PairingChannel, tvName: String? = null) =
        orchestrator.openPairing(host, port, channel, tvName)
    fun submitPairingCode(code: String) = orchestrator.submitPairingCode(code)
    fun submitPairingInvitation(invitation: dev.lucasdone.tvremote.controller.pairing.PairInvitation) =
        orchestrator.submitPairingInvitation(invitation)
    fun cancelPairing() = orchestrator.cancelPairing()

    fun openRemote() = orchestrator.openRemote()
    fun sendKey(key: String, state: String, repeatCount: Int = 0) =
        orchestrator.sendKey(key, state, repeatCount)
    fun updateTextDraft(value: String) = orchestrator.updateTextDraft(value)
    fun sendText(text: String) = orchestrator.sendText(text)
    fun refreshCapabilities() = orchestrator.refreshCapabilities()

    fun openSettings() = orchestrator.openSettings()
    fun backToDevices() = orchestrator.backToDevices()
    fun dismissNotice() = orchestrator.dismissNotice()

    override fun onCleared() {
        // 作用域取消时兜底关闭在途会话（避免未交接的 socket/线程泄漏）
        orchestrator.onCleared()
        super.onCleared()
    }

    companion object {
        const val CONTROLLER_NAME = ControllerOrchestrator.CONTROLLER_NAME

        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { ControllerViewModel(application) }
        }
    }
}
