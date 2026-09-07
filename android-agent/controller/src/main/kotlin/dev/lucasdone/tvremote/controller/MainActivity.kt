package dev.lucasdone.tvremote.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lucasdone.tvremote.controller.auth.ControllerCredentialStore
import dev.lucasdone.tvremote.controller.net.DiscoveryClient
import dev.lucasdone.tvremote.controller.net.TvConnection
import dev.lucasdone.tvremote.controller.net.WsDebugClient
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** 控制端在配对/凭据中统一使用的设备名（与 PairingScreen 的显示一致）。 */
private const val CONTROLLER_NAME = "Android 手机"

class MainActivity : ComponentActivity() {
    private val state = mutableStateOf<UiState>(UiState.Idle)
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tvrc-controller-io").apply { isDaemon = true }
    }
    private var connection: TvConnection? = null
    private var session: ControllerSession? = null
    private var wsClient: WsDebugClient? = null
    private var credentialStore: ControllerCredentialStore? = null

    internal sealed class UiState {
        data object Idle : UiState()
        data class Discovering(val progress: String) : UiState()
        data class Pairing(val host: String, val sas: String?, val error: String?) : UiState()
        data class Connected(
            val host: String,
            val capabilities: ControllerSession.Capabilities,
            val statusMessage: String? = null,
        ) : UiState()

        data class Error(val message: String) : UiState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialStore = ControllerCredentialStore(this)
        setContent {
            MaterialTheme {
                Root(state = state, callbacks = Callbacks())
            }
        }
    }

    override fun onDestroy() {
        wsClient?.close()
        wsClient = null
        connection?.close()
        connection = null
        session = null
        io.shutdownNow()
        super.onDestroy()
    }

    internal inner class Callbacks {
        fun startDiscovery() {
            state.value = UiState.Discovering("正在搜索电视…")
            io.execute {
                val found = try {
                    DiscoveryClient.probe()
                } catch (_: Exception) {
                    emptyList()
                }
                runOnUiThread {
                    if (found.isEmpty()) {
                        state.value = UiState.Error("未发现电视。请确认电视端应用已启动并处于同一局域网。")
                    } else {
                        state.value = UiState.Discovering("发现 ${found.joinToString { it.displayName }}，请输入配对码或 IP。")
                    }
                }
            }
        }

        fun startPairing(host: String, code: String, controllerName: String) {
            state.value = UiState.Pairing(host = host, sas = null, error = null)
            io.execute {
                try {
                    val tls = TvConnection.connect(host, TvConnection.DEFAULT_PORT, pinnedFingerprint = null)
                    connection = tls
                    // 配对后认证需要新连接（auth_begin 必须为连接首条消息）
                    val controllerSession = ControllerSession(connectionFactory = {
                        val fresh = TvConnection.connect(
                            host, TvConnection.DEFAULT_PORT,
                            pinnedFingerprint = credentialStore?.load(host)?.tvCertificateFingerprint,
                        )
                        fresh
                    })
                    session = controllerSession
                    val sas = controllerSession.pairWithCode(code, controllerName)
                    runOnUiThread { state.value = UiState.Pairing(host = host, sas = sas, error = null) }
                } catch (error: Exception) {
                    runOnUiThread { state.value = UiState.Pairing(host = host, sas = null, error = error.message ?: "配对失败") }
                }
            }
        }

        fun confirmPairingAndConnect(host: String) {
            val currentSession = session ?: return
            io.execute {
                try {
                    // 电视端确认后：接收 pair_credential → pair_store_ack → pair_complete
                    val paired = currentSession.completePairing()
                    credentialStore?.save(
                        host,
                        ControllerCredentialStore.TvCredential(
                            controllerId = paired.controllerId,
                            secret = paired.secret,
                            tvCertificateFingerprint = paired.tvCertificateFingerprint,
                            displayName = CONTROLLER_NAME,
                        ),
                    )
                    val capabilities = currentSession.authenticate(
                        controllerId = paired.controllerId,
                        secret = paired.secret,
                        certificateFingerprint = paired.tvCertificateFingerprint,
                    )
                    runOnUiThread {
                        state.value = UiState.Connected(host = host, capabilities = capabilities)
                    }
                } catch (error: Exception) {
                    runOnUiThread { state.value = UiState.Error("配对/认证失败：${error.message}") }
                }
            }
        }

        fun sendKey(key: String, stateName: String, repeatCount: Int = 0) {
            val ws = wsClient
            if (ws != null) {
                io.execute {
                    try {
                        ws.sendKeyEvent(key, stateName, repeatCount)
                    } catch (_: Exception) {
                        runOnUiThread { state.value = UiState.Error("调试通道已断开") }
                    }
                }
                return
            }
            val currentSession = session ?: return
            io.execute {
                try {
                    currentSession.sendKeyEvent(key, stateName, repeatCount)
                } catch (_: Exception) {
                    runOnUiThread { state.value = UiState.Error("连接已断开") }
                }
            }
        }

        fun sendText(text: String, draft: Boolean) {
            val ws = wsClient
            if (ws != null) {
                io.execute {
                    try {
                        ws.sendText(text, draft)
                    } catch (_: Exception) {
                        runOnUiThread { state.value = UiState.Error("调试通道已断开") }
                    }
                }
                return
            }
            val currentSession = session ?: return
            io.execute {
                try {
                    currentSession.sendText(text, draft)
                } catch (_: Exception) {
                    runOnUiThread { state.value = UiState.Error("连接已断开") }
                }
            }
        }

        /** 切换到明文 WS 调试通道（应用层端到端加密；仅供开发调试）。 */
        fun switchToDebugChannel(host: String) {
            io.execute {
                try {
                    val credential = credentialStore?.load(host)
                        ?: throw IllegalStateException("该电视尚未配对，调试通道需要已配对凭据")
                    val client = WsDebugClient(host, credential.controllerId, credential.secret)
                    runOnUiThread {
                        wsClient = client
                        val current = state.value
                        if (current is UiState.Connected) {
                            state.value = current.copy(statusMessage = "调试通道已启用（明文 WS + 应用层加密）")
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread { state.value = UiState.Error("调试通道连接失败：${error.message}") }
                }
            }
        }

        fun disconnect() {
            wsClient?.close()
            wsClient = null
            connection?.close()
            connection = null
            session = null
            state.value = UiState.Idle
        }
    }
}

@Composable
internal fun Root(state: MutableState<MainActivity.UiState>, callbacks: MainActivity.Callbacks) {
    when (val current = state.value) {
        is MainActivity.UiState.Idle -> IdleScreen(onDiscover = { callbacks.startDiscovery() })
        is MainActivity.UiState.Discovering -> DiscoveringScreen(current.progress)
        is MainActivity.UiState.Pairing -> PairingScreen(current, callbacks)
        is MainActivity.UiState.Connected -> RemoteScreen(current, callbacks)
        is MainActivity.UiState.Error -> ErrorScreen(current.message) { state.value = MainActivity.UiState.Idle }
    }
}

@Composable
private fun IdleScreen(onDiscover: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TV 遥控", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("与电视端应用配对后即可遥控", fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDiscover) { Text("搜索电视") }
    }
}

@Composable
private fun DiscoveringScreen(progress: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(progress)
    }
}

@Composable
private fun PairingScreen(state: MainActivity.UiState.Pairing, callbacks: MainActivity.Callbacks) {
    var code by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("配对 ${state.host}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        if (state.sas == null) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.take(6) },
                label = { Text("6 位配对码") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { callbacks.startPairing(state.host, code, CONTROLLER_NAME) },
                enabled = code.length == 6,
            ) { Text("连接并配对") }
        } else {
            Text("请在电视上核对安全码：", fontSize = 14.sp)
            Text(state.sas, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("在电视端确认后点击继续", fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { callbacks.confirmPairingAndConnect(state.host) }) { Text("已确认，进入遥控") }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { callbacks.disconnect() }) { Text("取消") }
    }
}

@Composable
private fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun RemoteScreen(
    state: MainActivity.UiState.Connected,
    callbacks: MainActivity.Callbacks,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("已连接 ${state.host}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        state.statusMessage?.let { Text(it, fontSize = 12.sp) }
        Spacer(Modifier.height(12.dp))
        DpadGrid(callbacks)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("返回") { callbacks.sendKey("BACK", "PRESS") }
            KeyButton("主页") { callbacks.sendKey("HOME", "PRESS") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("音量+") { callbacks.sendKey("VOLUME_UP", "PRESS") }
            KeyButton("静音") { callbacks.sendKey("VOLUME_MUTE", "PRESS") }
            KeyButton("音量-") { callbacks.sendKey("VOLUME_DOWN", "PRESS") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("播放/暂停") { callbacks.sendKey("MEDIA_PLAY_PAUSE", "PRESS") }
            KeyButton("停止") { callbacks.sendKey("MEDIA_STOP", "PRESS") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { callbacks.switchToDebugChannel(state.host) }) {
            Text("启用调试通道（WS）")
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { callbacks.disconnect() }) { Text("断开连接") }
    }
}

@Composable
private fun DpadGrid(callbacks: MainActivity.Callbacks) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        KeyButton("▲") { callbacks.sendKey("DPAD_UP", "PRESS") }
        Row {
            KeyButton("◀") { callbacks.sendKey("DPAD_LEFT", "PRESS") }
            KeyButton("OK") { callbacks.sendKey("DPAD_CENTER", "PRESS") }
            KeyButton("▶") { callbacks.sendKey("DPAD_RIGHT", "PRESS") }
        }
        KeyButton("▼") { callbacks.sendKey("DPAD_DOWN", "PRESS") }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(4.dp)) { Text(label) }
}
