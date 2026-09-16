package dev.lucasdone.tvremote.controller.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lucasdone.tvremote.agent.protocol.Hex
import dev.lucasdone.tvremote.controller.BuildConfig
import dev.lucasdone.tvremote.controller.R
import dev.lucasdone.tvremote.controller.data.CredentialStore
import dev.lucasdone.tvremote.controller.data.KeystoreCredentialStore
import dev.lucasdone.tvremote.controller.data.StoredDevice
import dev.lucasdone.tvremote.controller.net.ConnectionTransport
import dev.lucasdone.tvremote.controller.net.DiscoveryClient
import dev.lucasdone.tvremote.controller.net.TvConnection
import dev.lucasdone.tvremote.controller.pairing.PairInvitation
import dev.lucasdone.tvremote.controller.session.AuthFailedException
import dev.lucasdone.tvremote.controller.session.ControllerSession
import dev.lucasdone.tvremote.controller.session.KeyRepeatController
import dev.lucasdone.tvremote.controller.session.PairingFlow
import dev.lucasdone.tvremote.controller.session.PairingRejectedException
import dev.lucasdone.tvremote.controller.session.SessionClosedException
import dev.lucasdone.tvremote.controller.session.SessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/**
 * 会话与 UI 状态编排。网络会话、发现与凭据存储均在此管理，Activity 只承载 Compose。
 *
 * 关键约束：
 * - 每次连接操作带 generation，旧异步任务结果不得覆盖新会话。
 * - 进后台关闭会话/心跳/连接；回前台用保存凭据认证恢复。
 * - 证书不匹配、凭据撤销、用户主动断开不自动重试。
 */
class ControllerViewModel(
    application: Application,
    private val store: CredentialStore = KeystoreCredentialStore(application),
    private val transportFactory: (String, Int, ByteArray?) -> ConnectionTransport =
        { host, port, pinned -> TvConnection.connect(host, port, pinned) },
    private val probe: suspend () -> List<DiscoveryClient.DiscoveredTv> = { DiscoveryClient.probe() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ControllerUiState(version = BuildConfig.VERSION_NAME))
    val state: StateFlow<ControllerUiState> = _state.asStateFlow()

    private var session: ControllerSession? = null
    private var generation = 0

    /**
     * 长按重复控制器：**按会话绑定**。换设备/断线/退后台会停止并丢弃旧控制器，
     * 旧会话的 DOWN/REPEAT 不会再发往新连接。
     */
    private var keyRepeat: KeyRepeatController? = null

    /** 是否在前台（回前台自动重连、断线重连的前置条件）。 */
    private var foreground = false

    /** 用户主动断开：在用户再次发起连接前不自动重连。 */
    private var userDisconnected = false

    /** 运行中断线的有界自动重连任务（可被后台/断开/新连接取消）。 */
    private var reconnectJob: kotlinx.coroutines.Job? = null

    /** 发现请求代数：仅接受最新一次搜索的结果。 */
    private var discoveryGeneration = 0

    private fun bindSession(target: ControllerSession) {
        session = target
        keyRepeat = KeyRepeatController(
            scope = viewModelScope,
            sender = { key, state, count ->
                withContext(Dispatchers.IO) { target.sendKeyEvent(key, state, count) }
            },
            onAck = { key, ack ->
                // 旧会话的回执不得覆盖新连接的界面状态
                if (session === target && !ack.isSuccess) {
                    _state.update { it.copy(lastAck = describeAck(key, ack)) }
                }
            },
            onError = { error -> handleSessionError(error, target) },
        )
    }

    /**
     * 建立会话并接入「意外断线」回调（保活失败/读循环关闭）。回调按会话归属过滤，
     * 仅当该会话仍是当前会话时才影响界面。
     */
    private fun newSession(connect: (ByteArray?) -> ConnectionTransport): ControllerSession {
        lateinit var created: ControllerSession
        created = ControllerSession(
            connectionFactory = connect,
            onUnexpectedClose = {
                val origin = created
                viewModelScope.launch {
                    handleSessionError(SessionClosedException("connection lost"), origin)
                }
            },
        )
        return created
    }

    private fun nextGeneration(): Int = ++generation

    private fun isCurrent(token: Int): Boolean = token == generation

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    fun onForeground() {
        foreground = true
        val token = generation
        viewModelScope.launch {
            applyDevices()
            // applyDevices 期间可能已退后台/换设备
            if (!isCurrent(token) || !foreground) return@launch
            val state = _state.value
            val activeId = state.activeDeviceId
            // 用户主动断开不自动重连；添加/配对流程不被回前台打断
            val autoConnectable = state.screen == Screen.DEVICES || state.screen == Screen.REMOTE
            // 证书变化/凭据撤销等不可重试失败不在回前台时反复尝试，需用户显式操作
            val blockedByFailure = state.connection == ConnectionPhase.FAILED && state.failure?.retryable == false
            if (activeId != null && state.connection != ConnectionPhase.CONNECTED &&
                !userDisconnected && autoConnectable && !blockedByFailure
            ) {
                connect(activeId, auto = true)
            }
        }
    }

    fun onBackground() {
        foreground = false
        reconnectJob?.cancel()
        nextGeneration()
        stopAllRepeats()
        closeSession()
        _state.update {
            it.copy(
                connection = ConnectionPhase.IDLE,
                capabilities = null,
                pairing = null,
                lastAck = null,
                textStatus = TextSendStatus.IDLE,
                screen = if (it.screen == Screen.PAIRING) Screen.DEVICES else it.screen,
            )
        }
    }

    // ---- 设备列表 ----

    fun selectDevice(deviceId: String) {
        userDisconnected = false
        connect(deviceId, auto = false)
    }

    fun renameDevice(deviceId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { store.rename(deviceId, trimmed) }
            }
            outcome.exceptionOrNull()?.let { error ->
                _state.update { it.copy(notice = str(R.string.vm_rename_failed, error.message ?: str(R.string.vm_storage_error))) }
            }
            applyDevices()
        }
    }

    fun forgetDevice(deviceId: String) {
        viewModelScope.launch {
            val removingActive = _state.value.activeDeviceId == deviceId ||
                session != null && _state.value.connection != ConnectionPhase.IDLE &&
                _state.value.activeDeviceId == deviceId
            if (removingActive) {
                // 使在途连接/配对失效，避免被忘记的设备仍完成认证并接管
                nextGeneration()
                reconnectJob?.cancel()
            }
            val outcome = withContext(Dispatchers.IO) { runCatching { store.remove(deviceId) } }
            outcome.exceptionOrNull()?.let { error ->
                _state.update { it.copy(notice = str(R.string.vm_forget_failed, error.message ?: str(R.string.vm_storage_error))) }
            }
            if (removingActive) {
                closeSession()
                _state.update {
                    it.copy(
                        activeDeviceId = null,
                        capabilities = null,
                        connection = ConnectionPhase.IDLE,
                        textStatus = TextSendStatus.IDLE,
                    )
                }
            }
            applyDevices()
        }
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        val token = nextGeneration()
        val current = session
        val pressedKeys = stopAllRepeatsAndCollect()
        keyRepeat = null
        session = null
        _state.update { it.copy(connection = ConnectionPhase.DISCONNECTING) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 尽力先释放按下中的按键，再走协议断开（电视端也会在断连时释放全部按键）
                pressedKeys.forEach { key -> runCatching { current?.sendKeyEvent(key, "UP", 0) } }
                runCatching { current?.disconnect() }
            }
            // 期间已发起新连接：不要用旧断开结果覆盖新界面
            if (!isCurrent(token)) return@launch
            _state.update {
                it.copy(
                    connection = ConnectionPhase.IDLE,
                    capabilities = null,
                    lastAck = null,
                    textStatus = TextSendStatus.IDLE,
                )
            }
            applyDevices()
        }
    }

    // ---- 长按 / 按键 ----

    /**
     * 长按开始：发 DOWN 后按固定间隔串行重发 REPEAT（repeatCount 递增）。
     * 单路串行（同一 key 只允许一个任务）+ 限速，避免积压；失败即停止。
     */
    fun beginKeyPress(key: String) {
        keyRepeat?.begin(key)
    }

    /** 松手/手势取消：停止重复并尽力发送 UP。 */
    fun endKeyPress(key: String) {
        keyRepeat?.end(key)
    }

    private fun stopAllRepeats() {
        keyRepeat?.stopAll()
    }

    private fun stopAllRepeatsAndCollect(): List<String> {
        val controller = keyRepeat ?: return emptyList()
        val keys = controller.pressedKeys()
        controller.stopAll()
        return keys
    }

    // ---- 连接 ----

    /** R09：用既有凭据连接新的端点（电视换地址后无需重新配对）。 */
    fun connectToEndpoint(deviceId: String, host: String, port: Int) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        if (port !in 1..65535) {
            _state.update { it.copy(notice = str(R.string.vm_invalid_port)) }
            return
        }
        userDisconnected = false
        connect(deviceId, auto = false, hostOverride = trimmed, portOverride = port)
    }

    private fun connect(
        deviceId: String,
        auto: Boolean,
        hostOverride: String? = null,
        portOverride: Int? = null,
    ) {
        val token = nextGeneration()
        // 新连接取消待执行的重连；换设备前停止重复（旧按键不得在新会话重放）
        reconnectJob?.cancel()
        stopAllRepeats()
        _state.update { it.copy(textStatus = TextSendStatus.IDLE) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { store.find(deviceId) } }
            if (!isCurrent(token)) return@launch
            if (found.isFailure) {
                // 凭据存在但无法解密：显式提示，不伪装成未配对
                _state.update {
                    it.copy(
                        notice = str(R.string.vm_credential_unavailable),
                        connection = ConnectionPhase.FAILED,
                        failure = ConnectionFailure(FailureKind.CREDENTIAL_REVOKED, str(R.string.vm_credential_unavailable)),
                    )
                }
                return@launch
            }
            val device = found.getOrNull()
            if (device == null) {
                _state.update { it.copy(notice = str(R.string.vm_device_not_found), activeDeviceId = deviceId) }
                return@launch
            }
            closeSession()
            _state.update {
                it.copy(
                    screen = Screen.REMOTE,
                    activeDeviceId = deviceId,
                    connection = ConnectionPhase.CONNECTING,
                    failure = null,
                    capabilities = null,
                    lastAck = null,
                    // 切换到不同设备时清除上一台设备的草稿
                    textDraft = if (it.textDraftDeviceId != null && it.textDraftDeviceId != deviceId) "" else it.textDraft,
                    textDraftDeviceId = deviceId,
                )
            }
            val host = hostOverride ?: device.lastHost
            val port = portOverride ?: device.lastPort
            var attempt = 0
            while (true) {
                if (!isCurrent(token)) return@launch
                if (host.isNullOrBlank()) {
                    _state.update {
                        it.copy(
                            connection = ConnectionPhase.FAILED,
                            failure = ConnectionFailure(FailureKind.UNKNOWN, str(R.string.vm_no_endpoint)),
                        )
                    }
                    return@launch
                }
                try {
                    val result = withContext(Dispatchers.IO) {
                        val created = newSession { _ -> transportFactory(host, port, device.tvCertificateFingerprint) }
                        try {
                            val capabilities = created.authenticate(
                                controllerId = device.controllerId,
                                secret = device.secret,
                                certificateFingerprint = device.tvCertificateFingerprint,
                            )
                            created to capabilities
                        } catch (error: Exception) {
                            runCatching { created.close() }
                            throw error
                        }
                    }
                    if (!isCurrent(token)) {
                        result.first.close()
                        return@launch
                    }
                    bindSession(result.first)
                    _state.update {
                        it.copy(
                            connection = ConnectionPhase.CONNECTED,
                            failure = null,
                            capabilities = result.second,
                        )
                    }
                    // 认证成功后才更新端点/最近使用；成功连接后端点保持覆盖值
                    markUsed(device.id, host, port)
                    if (!isCurrent(token)) return@launch
                    applyDevices()
                    return@launch
                } catch (error: Exception) {
                    // 协程取消必须原样抛出，不能当作协议/网络错误处理
                    if (error is CancellationException) throw error
                    if (!isCurrent(token)) return@launch
                    val failure = classify(error)
                    if (auto && failure.retryable && attempt < AUTO_RECONNECT_ATTEMPTS) {
                        delay(RECONNECT_BACKOFF_MS[attempt])
                        attempt += 1
                        continue
                    }
                    // 连接失败用尽内部退避即转手动（不在此调度下一次重连，避免无限循环）；
                    // 运行中断线才由 handleSessionError 触发一次有界重连。
                    _state.update { it.copy(connection = ConnectionPhase.FAILED, failure = failure) }
                    applyDevices()
                    return@launch
                }
            }
        }
    }

    private suspend fun markUsed(deviceId: String, host: String, port: Int) {
        withContext(Dispatchers.IO) {
            runCatching { store.markUsed(deviceId, host, port, null, clock()) }
        }
    }

    // ---- 发现 ----

    fun openAddDevice() {
        _state.update { it.copy(screen = Screen.ADD_DEVICE, notice = null) }
        discover()
    }

    fun discover() {
        val discoveryToken = ++discoveryGeneration
        _state.update { it.copy(discovering = true, discoveryMessage = str(R.string.vm_searching)) }
        viewModelScope.launch {
            val found = try {
                withContext(Dispatchers.IO) { probe() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            // 期间已发起新搜索/离开：丢弃旧结果
            if (discoveryToken != discoveryGeneration) return@launch
            _state.update {
                it.copy(
                    discovering = false,
                    discovered = found,
                    discoveryMessage = if (found.isEmpty()) {
                        str(R.string.vm_discovery_none)
                    } else {
                        str(R.string.vm_discovery_found, found.size)
                    },
                )
            }
        }
    }

    // ---- 配对 ----

    fun openPairing(host: String, port: Int, channel: PairingChannel, tvName: String? = null) {
        if (host.isBlank()) return
        if (port !in 1..65535) {
            _state.update { it.copy(notice = str(R.string.vm_invalid_port)) }
            return
        }
        _state.update {
            it.copy(
                screen = Screen.PAIRING,
                notice = null,
                pairing = PairingUiState(host = host.trim(), port = port, channel = channel, tvName = tvName),
            )
        }
    }

    fun submitPairingCode(code: String) {
        val pairing = _state.value.pairing ?: return
        if (pairing.channel != PairingChannel.CODE) return
        startPairingExchange(pairing.host, pairing.port, code = code, token = null)
    }

    fun submitPairingInvitation(invitation: PairInvitation) {
        _state.update {
            it.copy(
                screen = Screen.PAIRING,
                pairing = PairingUiState(
                    host = invitation.host,
                    port = invitation.port,
                    channel = PairingChannel.QR_TOKEN,
                ),
            )
        }
        startPairingExchange(invitation.host, invitation.port, code = null, token = invitation.token)
    }

    private fun startPairingExchange(host: String, port: Int, code: String?, token: String?) {
        val pairingToken = nextGeneration()
        closeSession()
        _state.update { current ->
            current.copy(
                pairing = current.pairing?.copy(phase = PairingPhase.INPUT, error = null),
                notice = null,
            )
        }
        viewModelScope.launch {
            try {
                val started = withContext(Dispatchers.IO) {
                    val created = newSession { pin -> transportFactory(host, port, pin) }
                    try {
                        val challenge = if (token != null) {
                            created.pairWithToken(token, CONTROLLER_NAME)
                        } else {
                            created.pairWithCode(requireNotNull(code), CONTROLLER_NAME)
                        }
                        created to challenge
                    } catch (error: Exception) {
                        runCatching { created.close() }
                        throw error
                    }
                }
                if (!isCurrent(pairingToken)) {
                    started.first.close()
                    return@launch
                }
                session = started.first
                _state.update {
                    it.copy(
                        pairing = it.pairing?.copy(
                            phase = PairingPhase.AWAITING_TV_CONFIRMATION,
                            sas = started.second.sas,
                            error = null,
                        ),
                    )
                }

                val credential = withContext(Dispatchers.IO) { started.first.awaitPairingCredential() }
                if (!isCurrent(pairingToken)) return@launch
                _state.update { it.copy(pairing = it.pairing?.copy(phase = PairingPhase.COMPLETING)) }

                withContext(Dispatchers.IO) {
                    val tvName = _state.value.pairing?.tvName
                    PairingFlow.persistCredentialThenConfirm(
                        session = started.first,
                        credential = credential,
                        store = store,
                        displayName = tvName ?: CONTROLLER_NAME,
                        host = host,
                        port = port,
                        tvName = tvName,
                        nowMs = clock(),
                    )
                }
                if (!isCurrent(pairingToken)) return@launch

                val fingerprintHex = Hex.encode(credential.tvCertificateFingerprint)
                val capabilities = withContext(Dispatchers.IO) {
                    started.first.authenticate(credential.controllerId, credential.secret, credential.tvCertificateFingerprint)
                }
                if (!isCurrent(pairingToken)) return@launch
                // 认证完成：把长按重复绑定到这条已认证会话
                bindSession(started.first)
                userDisconnected = false
                _state.update {
                    it.copy(
                        screen = Screen.REMOTE,
                        pairing = null,
                        activeDeviceId = fingerprintHex,
                        connection = ConnectionPhase.CONNECTED,
                        failure = null,
                        capabilities = capabilities,
                        notice = str(R.string.vm_paired_success),
                        textStatus = TextSendStatus.IDLE,
                        textDraft = if (it.textDraftDeviceId != null && it.textDraftDeviceId != fingerprintHex) "" else it.textDraft,
                        textDraftDeviceId = fingerprintHex,
                    )
                }
                markUsed(fingerprintHex, host, port)
                if (!isCurrent(pairingToken)) return@launch
                applyDevices()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!isCurrent(pairingToken)) return@launch
                closeSession()
                _state.update {
                    it.copy(
                        connection = ConnectionPhase.IDLE,
                        capabilities = null,
                        pairing = it.pairing?.copy(
                            phase = PairingPhase.INPUT,
                            sas = null,
                            error = classifyPairing(error),
                        ),
                    )
                }
                applyDevices()
            }
        }
    }

    fun cancelPairing() {
        nextGeneration()
        closeSession()
        _state.update { it.copy(screen = Screen.DEVICES, pairing = null, connection = ConnectionPhase.IDLE, capabilities = null) }
    }

    // ---- 遥控 / 文字 ----

    fun sendKey(key: String, state: String, repeatCount: Int = 0) {
        val current = session ?: return
        viewModelScope.launch {
            try {
                val ack = withContext(Dispatchers.IO) { current.sendKeyEvent(key, state, repeatCount) }
                // 会话已被替换时丢弃结果，避免覆盖新连接界面
                if (session !== current) return@launch
                _state.update { it.copy(lastAck = describeAck(key, ack)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                handleSessionError(error, current)
            }
        }
    }

    /** 更新内存草稿（按当前设备归属）。 */
    fun updateTextDraft(value: String) {
        _state.update {
            it.copy(
                textDraft = value.take(ControllerSession.MAX_TEXT_CHARS),
                textDraftDeviceId = it.activeDeviceId ?: it.textDraftDeviceId,
            )
        }
    }

    fun sendText(text: String) {
        val current = session ?: return
        if (text.isEmpty()) return
        if (text.length > ControllerSession.MAX_TEXT_CHARS) {
            _state.update { it.copy(notice = str(R.string.vm_text_too_long, ControllerSession.MAX_TEXT_CHARS)) }
            return
        }
        val sentText = text
        _state.update { it.copy(textStatus = TextSendStatus.SENDING) }
        viewModelScope.launch {
            try {
                val ack = withContext(Dispatchers.IO) { current.sendText(sentText) }
                if (session !== current) return@launch
                _state.update {
                    // 仅当草稿未被改动时才清空，避免删除等待期间新输入的内容
                    val clearDraft = ack.isSuccess && it.textDraft == sentText
                    it.copy(
                        textStatus = if (ack.isSuccess) TextSendStatus.SENT else TextSendStatus.FAILED,
                        textDraft = if (clearDraft) "" else it.textDraft,
                        lastAck = if (ack.isSuccess) str(R.string.vm_text_sent) else describeAck(str(R.string.vm_text_label), ack),
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // 失败保留草稿；会话失配时 onBackground/closeSession 已重置 textStatus
                if (session === current) _state.update { it.copy(textStatus = TextSendStatus.FAILED) }
                handleSessionError(error, current)
            }
        }
    }

    fun refreshCapabilities() {
        val current = session ?: return
        viewModelScope.launch {
            try {
                val capabilities = withContext(Dispatchers.IO) { current.requestCapabilities() }
                if (session === current) {
                    _state.update { it.copy(capabilities = capabilities, notice = str(R.string.vm_capabilities_refreshed)) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                handleSessionError(error, current)
            }
        }
    }

    // ---- 导航 / 提示 ----

    fun openSettings() = _state.update { it.copy(screen = Screen.SETTINGS) }

    fun backToDevices() {
        _state.update { it.copy(screen = Screen.DEVICES, notice = null) }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---- 内部 ----

    private suspend fun applyDevices() {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { store.load() }.getOrNull()
        }
        if (loaded == null) {
            _state.update { it.copy(notice = str(R.string.vm_credential_unavailable)) }
            return
        }
        val summaries = loaded.devices.map { device ->
            DeviceSummary(
                id = device.id,
                displayName = device.displayName,
                tvDisplayName = device.tvDisplayName,
                lastHost = device.lastHost,
                lastPort = device.lastPort,
                lastUsedAtMs = device.lastUsedAtMs,
                connected = false,
            )
        }
        _state.update { current ->
            val active = current.activeDeviceId ?: summaries.firstOrNull()?.id
            current.copy(
                devices = summaries.map {
                    it.copy(connected = it.id == active && current.connection == ConnectionPhase.CONNECTED)
                },
                activeDeviceId = active,
                notice = if (loaded.unreadableIds.isNotEmpty()) str(R.string.vm_credential_unavailable) else current.notice,
            )
        }
    }

    private fun closeSession() {
        keyRepeat?.stopAll()
        keyRepeat = null
        val current = session
        session = null
        runCatching { current?.close() }
    }

    /**
     * 处理会话错误。带 [origin] 时必须仍是当前会话才生效：切换设备/重连后，
     * 旧会话的失败不得覆盖新连接状态，更不得关闭新会话。
     */
    private fun handleSessionError(error: Throwable, origin: ControllerSession? = null) {
        if (origin != null && session !== origin) return
        keyRepeat?.stopAll()
        val failure = classify(error)
        when (failure.kind) {
            FailureKind.NETWORK -> {
                nextGeneration()
                closeSession()
                _state.update {
                    it.copy(
                        connection = ConnectionPhase.FAILED,
                        failure = failure,
                        capabilities = null,
                        textStatus = TextSendStatus.IDLE,
                    )
                }
                scheduleReconnect()
            }
            FailureKind.CERTIFICATE, FailureKind.CREDENTIAL_REVOKED -> {
                nextGeneration()
                closeSession()
                _state.update {
                    it.copy(
                        connection = ConnectionPhase.FAILED,
                        failure = failure,
                        capabilities = null,
                        textStatus = TextSendStatus.IDLE,
                    )
                }
            }
            else -> _state.update { it.copy(notice = failure.message) }
        }
    }

    /** 运行中断线的有界自动重连：仅前台、非用户主动断开、网络类失败时启动。 */
    private fun scheduleReconnect() {
        if (!foreground || userDisconnected) return
        val deviceId = _state.value.activeDeviceId ?: return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!foreground || userDisconnected) return@launch
            connect(deviceId, auto = true)
        }
    }

    // SUCCESS 仅表示电视接口已接受/执行该请求，不代表目标 App 已完成业务动作
    private fun describeAck(key: String, ack: ControllerSession.AckResult): String = when (ack.status) {
        "SUCCESS" -> str(R.string.ack_success, key)
        "UNSUPPORTED" -> str(R.string.ack_unsupported, key)
        "PERMISSION_DENIED" -> str(R.string.ack_permission_denied, key)
        "MAPPING_MISSING" -> str(R.string.ack_mapping_missing, key)
        "REJECTED" -> ack.reason?.let { str(R.string.ack_rejected_reason, key, it) } ?: str(R.string.ack_rejected, key)
        "EXECUTION_FAILED" -> ack.reason?.let { str(R.string.ack_failed_reason, key, it) } ?: str(R.string.ack_failed, key)
        else -> str(R.string.ack_status, key, ack.status)
    }

    private fun classifyPairing(error: Throwable): String = when (error) {
        is PairingRejectedException -> str(R.string.vm_pairing_rejected)
        is SessionException -> str(R.string.vm_tv_rejected, error.message ?: error.code)
        else -> classify(error).message
    }

    /** 服务端结构化错误按 code 细分，避免 INVALID_SESSION/BUSY 被误当未知可重试错误。 */
    private fun sessionFailure(error: SessionException): ConnectionFailure = when (error.code) {
        "AUTHENTICATION_FAILED" ->
            ConnectionFailure(FailureKind.CREDENTIAL_REVOKED, str(R.string.vm_credential_revoked))
        // 会话失效/需要认证：服务端随后关闭连接，按网络断开处理以尽快重连
        "INVALID_SESSION", "AUTHENTICATION_REQUIRED" ->
            ConnectionFailure(FailureKind.NETWORK, str(R.string.vm_connection_closed))
        // 电视正被另一控制端占用：不可自动重连，允许用户稍后手动重试
        "BUSY" -> ConnectionFailure(FailureKind.BUSY, str(R.string.vm_tv_busy))
        else -> ConnectionFailure(FailureKind.PROTOCOL, str(R.string.vm_tv_rejected, error.message ?: error.code))
    }

    private fun classify(error: Throwable): ConnectionFailure = when {
        error is SessionException -> sessionFailure(error)
        // 认证对端指纹与保存指纹不一致（换证/MITM）
        error is AuthFailedException ->
            ConnectionFailure(FailureKind.CERTIFICATE, str(R.string.vm_identity_mismatch))
        error is SessionClosedException ->
            ConnectionFailure(FailureKind.NETWORK, str(R.string.vm_connection_closed))
        error is SSLHandshakeException || error is CertificateException ->
            ConnectionFailure(FailureKind.CERTIFICATE, str(R.string.vm_cert_changed))
        error is SocketTimeoutException ->
            ConnectionFailure(FailureKind.NETWORK, str(R.string.vm_timeout))
        error is ConnectException ->
            ConnectionFailure(FailureKind.NETWORK, str(R.string.vm_connect_failed))
        error is UnknownHostException ->
            ConnectionFailure(FailureKind.NETWORK, str(R.string.vm_dns_failed))
        // 运行中断线：TCP reset / 半帧 EOF / 一般 IO 失败都按网络断开处理
        error is java.net.SocketException || error is java.io.EOFException || error is java.io.IOException ->
            ConnectionFailure(FailureKind.NETWORK, str(R.string.vm_connection_closed))
        error is PairingRejectedException ->
            ConnectionFailure(FailureKind.PAIRING, error.message ?: str(R.string.vm_pairing_rejected))
        error is IllegalArgumentException || error is IllegalStateException ->
            ConnectionFailure(FailureKind.PROTOCOL, error.message ?: str(R.string.vm_protocol_error))
        else -> ConnectionFailure(FailureKind.UNKNOWN, error.message ?: error.javaClass.simpleName)
    }

    override fun onCleared() {
        nextGeneration()
        closeSession()
        super.onCleared()
    }

    companion object {
        const val CONTROLLER_NAME = "Android Phone"
        private const val AUTO_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 1_000L
        private val RECONNECT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)

        /**
         * 显式工厂：默认 `SavedStateViewModelFactory` 需要 `(Application)` 构造，
         * 而主构造带默认注入参数（仅生成合成构造），必须由此创建。
         */
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { ControllerViewModel(application) }
        }
    }
}
