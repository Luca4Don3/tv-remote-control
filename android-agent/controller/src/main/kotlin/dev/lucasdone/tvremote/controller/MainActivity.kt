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
        data class Discovering(
            val progress: String,
            val found: List<DiscoveryClient.DiscoveredTv> = emptyList(),
        ) : UiState()
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
        // 先关 ControllerSession（含其内部活动连接与保活线程），再清 MainActivity 的直连引用
        session?.close()
        session = null
        connection?.close()
        connection = null
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
                        // 未发现不阻塞：仍可手动输入电视 IP 进入配对
                        state.value = UiState.Discovering("未发现电视，可手动输入电视 IP 配对。")
                    } else {
                        state.value = UiState.Discovering("发现 ${found.size} 台电视，选择后输入配对码。", found)
                    }
                }
            }
        }

        /** 选中电视（发现列表点击或手动 IP）：进入配对阶段输入 6 位码。 */
        fun selectTv(host: String) {
            state.value = UiState.Pairing(host = host.trim(), sas = null, error = null)
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
        is MainActivity.UiState.Discovering -> DiscoveringScreen(current, callbacks)
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
private fun DiscoveringScreen(state: MainActivity.UiState.Discovering, callbacks: MainActivity.Callbacks) {
    var manualHost by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("发现电视", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(state.progress, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        // 发现列表：选择电视 → 配对阶段输入 6 位码
        state.found.forEach { tv ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("${tv.displayName}（${tv.address}）", fontSize = 14.sp)
                Button(onClick = { callbacks.selectTv(tv.address) }) { Text("配对") }
            }
            Spacer(Modifier.height(8.dp))
        }
        // 手动 IP：未发现/跨网段时仍可进入配对
        OutlinedTextField(
            value = manualHost,
            onValueChange = { manualHost = it.trim() },
            label = { Text("手动输入电视 IP") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { callbacks.selectTv(manualHost) },
            enabled = manualHost.isNotBlank(),
        ) { Text("手动连接") }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { callbacks.startDiscovery() }) { Text("重新搜索") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { callbacks.disconnect() }) { Text("返回") }
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
    // 能力位：UNSUPPORTED 键禁用（对齐 agent 能力协商），避免"点了没反应"的静默失败
    fun supported(key: String): Boolean =
        state.capabilities.keySupport[key] == "SUPPORTED"
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("已连接 ${state.host}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        state.statusMessage?.let { Text(it, fontSize = 12.sp) }
        Spacer(Modifier.height(12.dp))
        DpadGrid(callbacks, ::supported)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("返回", supported("BACK")) { callbacks.sendKey("BACK", "PRESS") }
            KeyButton("主页", supported("HOME")) { callbacks.sendKey("HOME", "PRESS") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("音量+", supported("VOLUME_UP")) { callbacks.sendKey("VOLUME_UP", "PRESS") }
            KeyButton("静音", supported("VOLUME_MUTE")) { callbacks.sendKey("VOLUME_MUTE", "PRESS") }
            KeyButton("音量-", supported("VOLUME_DOWN")) { callbacks.sendKey("VOLUME_DOWN", "PRESS") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("播放/暂停", supported("MEDIA_PLAY_PAUSE")) { callbacks.sendKey("MEDIA_PLAY_PAUSE", "PRESS") }
            KeyButton("停止", supported("MEDIA_STOP")) { callbacks.sendKey("MEDIA_STOP", "PRESS") }
        }
        Spacer(Modifier.height(8.dp))
        if (state.capabilities.textInput == "SUPPORTED") {
            TextInputRow(callbacks)
        }
        OutlinedButton(onClick = { callbacks.switchToDebugChannel(state.host) }) {
            Text("启用调试通道（WS）")
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { callbacks.disconnect() }) { Text("断开连接") }
    }
}

@Composable
private fun DpadGrid(callbacks: MainActivity.Callbacks, supported: (String) -> Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        KeyButton("▲", supported("DPAD_UP")) { callbacks.sendKey("DPAD_UP", "PRESS") }
        Row {
            KeyButton("◀", supported("DPAD_LEFT")) { callbacks.sendKey("DPAD_LEFT", "PRESS") }
            KeyButton("OK", supported("DPAD_CENTER")) { callbacks.sendKey("DPAD_CENTER", "PRESS") }
            KeyButton("▶", supported("DPAD_RIGHT")) { callbacks.sendKey("DPAD_RIGHT", "PRESS") }
        }
        KeyButton("▼", supported("DPAD_DOWN")) { callbacks.sendKey("DPAD_DOWN", "PRESS") }
    }
}

/** 文本注入入口（textInput=SUPPORTED 时显示）。 */
@Composable
private fun TextInputRow(callbacks: MainActivity.Callbacks) {
    var text by remember { mutableStateOf("") }
    var sentStatus by remember { mutableStateOf<String?>(null) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("输入注入文本") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    callbacks.sendText(text, draft = true)
                    sentStatus = "draft 已发送"
                },
                enabled = text.isNotBlank(),
            ) { Text("预览") }
            Button(
                onClick = {
                    callbacks.sendText(text, draft = false)
                    sentStatus = "commit 已发送"
                },
                enabled = text.isNotBlank(),
            ) { Text("提交") }
        }
        sentStatus?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(4.dp)) { Text(label) }
}
