package dev.tvremote.controller.ui

import dev.tvremote.agent.protocol.Hex
import dev.tvremote.controller.R
import dev.tvremote.controller.data.CredentialStore
import dev.tvremote.controller.data.StoredDevice
import dev.tvremote.controller.net.ConnectionTransport
import dev.tvremote.controller.net.DiscoveryClient
import dev.tvremote.controller.pairing.PairInvitation
import dev.tvremote.controller.session.AuthFailedException
import dev.tvremote.controller.session.ControllerSession
import dev.tvremote.controller.session.InFlightSessionRegistry
import dev.tvremote.controller.session.KeyRepeatController
import dev.tvremote.controller.session.PairingFlow
import dev.tvremote.controller.session.PairingRejectedException
import dev.tvremote.controller.session.SessionClosedException
import dev.tvremote.controller.session.SessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

/** 字符串资源解析（Android 注入 `getString`；测试注入固定实现）。 */
fun interface StringProvider {
    fun get(id: Int, vararg args: Any): String
}

/**
 * 会话与 UI 状态编排（纯 Kotlin，不依赖 Android，便于 JVM 测试）。
 *
 * 关键约束：
 * - 每次连接/配对操作带 generation；在途会话由 [InFlightSessionRegistry] 统一登记，
 *   代数失效与资源抽取在同一把锁内完成，晚到的旧任务无法登记。
 * - 进后台关闭会话/心跳/连接；回前台认证恢复并按需对账待确认凭据。
 * - 证书不匹配、凭据撤销、用户主动断开不自动重试。
 */
class ControllerOrchestrator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val store: CredentialStore,
    private val transportFactory: (String, Int, ByteArray?) -> ConnectionTransport,
    private val probe: suspend () -> List<DiscoveryClient.DiscoveredTv>,
    private val strings: StringProvider,
    version: String,
    private val closeSessionResource: (ControllerSession) -> Unit = { it.close() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val reconnectDelayMs: Long = 1_000L,
    private val reconnectBackoffMs: LongArray = longArrayOf(1_000L, 2_000L, 4_000L),
    private val autoReconnectAttempts: Int = 3,
) {
    private val _state = MutableStateFlow(ControllerUiState(version = version))
    val state: StateFlow<ControllerUiState> = _state.asStateFlow()

    private val registry = InFlightSessionRegistry(closeSessionResource)
    private var session: ControllerSession? = null
    private var keyRepeat: KeyRepeatController? = null
    private var foreground = false
    private var userDisconnected = false
    private var reconnectJob: Job? = null
    private var reconcileJob: Job? = null
    private var reconnectCycles = 0
    private var discoveryGeneration = 0

    private data class ConnectTarget(val token: Int, val deviceId: String)

    /**
     * 正在连接的目标（含凭据读取/认证在途阶段），用于「忘记该设备」时使其失效。
     * 以操作 token 限定，避免旧任务的 finally 清掉同一设备的新连接目标。
     */
    @Volatile
    private var connectTarget: ConnectTarget? = null

    /** 对账在途会话引用：取消对账时主动关闭，避免阻塞中的网络操作一直不释放。 */
    @Volatile
    private var reconcileSession: ControllerSession? = null

    private fun str(id: Int, vararg args: Any): String = strings.get(id, *args)

    private fun currentGeneration(): Int = registry.currentGeneration()

    private fun nextGeneration(): Int = registry.nextGeneration()

    private fun isCurrent(token: Int): Boolean = registry.isCurrent(token)

    private fun newSession(connect: (ByteArray?) -> ConnectionTransport): ControllerSession {
        lateinit var created: ControllerSession
        created = ControllerSession(
            connectionFactory = connect,
            onUnexpectedClose = {
                val origin = created
                scope.launch {
                    handleSessionError(SessionClosedException("connection lost"), origin)
                }
            },
        )
        return created
    }

    private fun bindSession(target: ControllerSession) {
        session = target
        keyRepeat = KeyRepeatController(
            scope = scope,
            sender = { key, state, count ->
                withContext(ioDispatcher) { target.sendKeyEvent(key, state, count) }
            },
            onAck = { key, ack ->
                if (session === target && !ack.isSuccess) {
                    _state.update { it.copy(lastAck = describeAck(key, ack)) }
                }
            },
            onError = { error -> handleSessionError(error, target) },
        )
    }

    /** 关闭当前活动会话，但**不动 generation**（供同一操作内部切换连接时使用）。 */
    private fun closeActiveSession() {
        keyRepeat?.stopAll()
        keyRepeat = null
        val current = session
        session = null
        runCatching { current?.close() }
    }

    /** 关闭当前会话并使代数失效、抽取在途会话（锁外关闭）。 */
    private fun closeSession() {
        closeActiveSession()
        registry.invalidateAndDrain()
    }

    /**
     * 取消对账并关闭其可能已建立的会话：仅 `job.cancel()` 无法中断阻塞中的网络操作，
     * 必须主动 `close()` 让 socket 关闭、使阻塞读取立即返回。
     */
    private fun cancelReconcile() {
        reconcileJob?.cancel()
        reconcileJob = null
        reconcileSession?.let { runCatching { it.close() } }
        reconcileSession = null
    }

    // ---- 生命周期 ----

    fun onForeground() {
        foreground = true
        val token = currentGeneration()
        // 记录前台任务句柄：导航/连接/退后台可取消其中尚未完成的对账
        cancelReconcile()
        reconcileJob = scope.launch {
            applyDevices()
            if (!isCurrent(token) || !foreground) return@launch
            // 优先对账待确认凭据（若是当前操作）
            if (reconcilePending(token)) return@launch
            if (!isCurrent(token) || !foreground) return@launch
            val state = _state.value
            val activeId = state.activeDeviceId
            val autoConnectable = state.screen == Screen.DEVICES || state.screen == Screen.REMOTE
            // 证书变化/凭据撤销/BUSY 等不可重试失败不因前后台切换而重新尝试
            val blockedByFailure = state.failure?.retryable == false
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
        cancelReconcile()
        keyRepeat?.stopAll()
        closeSession()
        _state.update {
            it.copy(
                connection = ConnectionPhase.IDLE,
                capabilities = null,
                pairing = null,
                lastAck = null,
                textStatus = TextSendStatus.IDLE,
                screen = if (it.screen == Screen.PAIRING) Screen.ADD_DEVICE else it.screen,
            )
        }
    }

    /** 作用域/ViewModel 销毁时的兜底清理：关闭当前会话并 drain 未交接的在途会话。 */
    fun onCleared() {
        foreground = false
        reconnectJob?.cancel()
        cancelReconcile()
        closeSession()
    }

    // ---- 设备列表 ----

    fun selectDevice(deviceId: String) {
        userDisconnected = false
        reconnectCycles = 0
        _state.update { it.copy(failure = null) }
        connect(deviceId, auto = false)
    }

    fun renameDevice(deviceId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val outcome = withContext(ioDispatcher) { runCatching { store.rename(deviceId, trimmed) } }
            outcome.exceptionOrNull()?.let { error ->
                _state.update { it.copy(notice = str(R.string.vm_rename_failed, error.message ?: str(R.string.vm_storage_error))) }
            }
            applyDevices()
        }
    }

    /** R5：只使「被删的当前设备」相关操作失效；删除非当前设备不影响当前会话。 */
    fun forgetDevice(deviceId: String) {
        scope.launch {
            val removesCurrentTarget = _state.value.activeDeviceId == deviceId || connectTarget?.deviceId == deviceId
            val token = if (removesCurrentTarget) {
                reconnectJob?.cancel()
                // 使在途连接（含凭据读取/认证中）失效并关闭（不影响后续新操作）
                registry.invalidateAndDrain()
            } else {
                currentGeneration()
            }
            val outcome = withContext(ioDispatcher) { runCatching { store.remove(deviceId) } }
            outcome.exceptionOrNull()?.let { error ->
                _state.update { it.copy(notice = str(R.string.vm_forget_failed, error.message ?: str(R.string.vm_storage_error))) }
            }
            val removed = outcome.isSuccess
            if (removed && removesCurrentTarget && isCurrent(token)) {
                closeActiveSession()
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
        val current = session
        val pressedKeys = keyRepeat?.pressedKeys().orEmpty()
        keyRepeat?.stopAll()
        keyRepeat = null
        session = null
        // 使代数失效并关闭在途会话
        val token = registry.invalidateAndDrain()
        _state.update { it.copy(connection = ConnectionPhase.DISCONNECTING) }
        scope.launch {
            withContext(ioDispatcher) {
                pressedKeys.forEach { key -> runCatching { current?.sendKeyEvent(key, "UP", 0) } }
                runCatching { current?.disconnect() }
            }
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

    fun beginKeyPress(key: String) {
        keyRepeat?.begin(key)
    }

    fun endKeyPress(key: String) {
        keyRepeat?.end(key)
    }

    /** 导航离开遥控页时释放 Long-press（保持连接）。 */
    fun releaseAllRepeats() {
        keyRepeat?.releaseAll()
    }

    private fun stopAllRepeats() {
        keyRepeat?.stopAll()
    }

    // ---- 连接 ----

    fun connectToEndpoint(deviceId: String, host: String, port: Int) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        if (port !in 1..65535) {
            _state.update { it.copy(notice = str(R.string.vm_invalid_port)) }
            return
        }
        userDisconnected = false
        reconnectCycles = 0
        connect(deviceId, auto = false, hostOverride = trimmed, portOverride = port)
    }

    private fun connect(
        deviceId: String,
        auto: Boolean,
        hostOverride: String? = null,
        portOverride: Int? = null,
    ) {
        reconnectJob?.cancel()
        cancelReconcile()
        stopAllRepeats()
        // 先关闭旧会话（含失效在途），再捕获本操作的 token，避免自失效
        closeSession()
        val token = nextGeneration()
        connectTarget = ConnectTarget(token, deviceId)
        _state.update { it.copy(textStatus = TextSendStatus.IDLE) }
        scope.launch {
            var created: ControllerSession? = null
            var handedOff = false
            try {
                val found = withContext(ioDispatcher) { runCatching { store.find(deviceId) } }
                if (!isCurrent(token)) return@launch
                if (found.isFailure) {
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
                _state.update {
                    it.copy(
                        screen = Screen.REMOTE,
                        activeDeviceId = deviceId,
                        connection = ConnectionPhase.CONNECTING,
                        failure = null,
                        capabilities = null,
                        lastAck = null,
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
                        val result = withContext(ioDispatcher) {
                            val candidate = newSession { _ -> transportFactory(host, port, device.tvCertificateFingerprint) }
                            created = candidate
                            if (!registry.register(token, candidate)) {
                                candidate.close()
                                throw SessionClosedException("connect superseded")
                            }
                            try {
                                candidate to candidate.authenticate(
                                    controllerId = device.controllerId,
                                    secret = device.secret,
                                    certificateFingerprint = device.tvCertificateFingerprint,
                                )
                            } catch (error: Exception) {
                                registry.clear(token, candidate)
                                runCatching { candidate.close() }
                                throw error
                            }
                        }
                        created = null
                        if (!isCurrent(token)) {
                            registry.clear(token, result.first)
                            result.first.close()
                            return@launch
                        }
                        registry.clear(token, result.first)
                        bindSession(result.first)
                        handedOff = true
                        reconnectCycles = 0
                        _state.update {
                            it.copy(connection = ConnectionPhase.CONNECTED, failure = null, capabilities = result.second)
                        }
                        markUsed(device.id, host, port)
                        if (!isCurrent(token)) return@launch
                        applyDevices()
                        return@launch
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (!isCurrent(token)) return@launch
                        val failure = classify(error)
                        if (auto && failure.retryable && attempt < autoReconnectAttempts) {
                            delay(reconnectBackoffMs[attempt])
                            attempt += 1
                            continue
                        }
                        _state.update { it.copy(connection = ConnectionPhase.FAILED, failure = failure) }
                        applyDevices()
                        // 有界持续重连：电视端应用被系统停止后，重新打开即可自动恢复
                        if (auto && failure.retryable) scheduleReconnect()
                        return@launch
                    }
                }
            } finally {
                // 作用域取消/异常时，关闭尚未交接的会话（显式关闭路径已由 closeSession 处理）
                if (!handedOff) {
                    created?.let { candidate ->
                        registry.clear(token, candidate)
                        runCatching { candidate.close() }
                    }
                }
                if (connectTarget?.token == token) connectTarget = null
            }
        }
    }

    private suspend fun markUsed(deviceId: String, host: String, port: Int) {
        withContext(ioDispatcher) {
            runCatching { store.markUsed(deviceId, host, port, null, clock()) }
        }
    }

    // ---- 发现 ----

    fun openAddDevice() {
        reconnectJob?.cancel()
        cancelReconcile()
        _state.update { it.copy(screen = Screen.ADD_DEVICE, notice = null) }
        discover()
    }

    fun discover() {
        val discoveryToken = ++discoveryGeneration
        _state.update { it.copy(discovering = true, discoveryMessage = str(R.string.vm_searching)) }
        scope.launch {
            val found = try {
                withContext(ioDispatcher) { probe() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
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
        startPairingExchange(pairing.host, pairing.port, code = code, token = null, tvName = pairing.tvName)
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
        startPairingExchange(invitation.host, invitation.port, code = null, token = invitation.token, tvName = null)
    }

    private fun startPairingExchange(host: String, port: Int, code: String?, token: String?, tvName: String?) {
        reconnectJob?.cancel()
        cancelReconcile()
        // 先关闭旧会话（含失效在途），再捕获本操作的 token
        closeSession()
        val pairingToken = nextGeneration()
        _state.update { current ->
            current.copy(
                pairing = current.pairing?.copy(phase = PairingPhase.INPUT, error = null),
                notice = null,
            )
        }
        scope.launch {
            try {
                val started = withContext(ioDispatcher) {
                    val created = newSession { pin -> transportFactory(host, port, pin) }
                    if (!registry.register(pairingToken, created)) {
                        created.close()
                        throw SessionClosedException("pairing superseded")
                    }
                    try {
                        val challenge = if (token != null) {
                            created.pairWithToken(token, CONTROLLER_NAME)
                        } else {
                            created.pairWithCode(requireNotNull(code), CONTROLLER_NAME)
                        }
                        created to challenge
                    } catch (error: Exception) {
                        registry.clear(pairingToken, created)
                        runCatching { created.close() }
                        throw error
                    }
                }
                if (!isCurrent(pairingToken)) {
                    registry.clear(pairingToken, started.first)
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

                val credential = withContext(ioDispatcher) { started.first.awaitPairingCredential() }
                if (!isCurrent(pairingToken)) return@launch
                _state.update { it.copy(pairing = it.pairing?.copy(phase = PairingPhase.COMPLETING)) }

                withContext(ioDispatcher) {
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
                val capabilities = withContext(ioDispatcher) {
                    started.first.authenticate(credential.controllerId, credential.secret, credential.tvCertificateFingerprint)
                }
                if (!isCurrent(pairingToken)) return@launch
                registry.clear(pairingToken, started.first)
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
        closeSession()
        _state.update { it.copy(screen = Screen.ADD_DEVICE, pairing = null, connection = ConnectionPhase.IDLE, capabilities = null) }
    }

    // ---- 待确认凭据对账 ----

    /**
     * R2：对已尝试发送 ACK 的待确认凭据静默认证。
     * - 成功 → 提升为有效并连接（是否进入遥控页单独判断）；
     * - 服务端明确 AUTHENTICATION_FAILED → 丢弃；
     * - 网络/超时/BUSY/证书不匹配等 → 保留，下次前台再试。
     */
    private fun reconcileEligible(): Boolean {
        if (!foreground || userDisconnected) return false
        val state = _state.value
        return state.connection != ConnectionPhase.CONNECTED &&
            state.pairing == null &&
            state.screen == Screen.DEVICES
    }

    private suspend fun reconcilePending(token: Int): Boolean {
        if (!reconcileEligible()) return false
        val loaded = withContext(ioDispatcher) { runCatching { store.load() }.getOrNull() } ?: return false
        // store.load 可能挂起：恢复后重新检查页面/配对/前台/归属
        if (!isCurrent(token) || !reconcileEligible()) return false
        val candidate = loaded.pending.firstOrNull() ?: return false
        val host = candidate.lastHost
        if (host.isNullOrBlank()) {
            withContext(ioDispatcher) { runCatching { store.discardPending(candidate.id) } }
            return false
        }
        var handedOff = false
        val created = newSession { _ -> transportFactory(host, candidate.lastPort, candidate.tvCertificateFingerprint) }
        reconcileSession = created
        try {
            if (!registry.register(token, created)) {
                return false
            }
            val outcome = withContext(ioDispatcher) {
                runCatching {
                    created.authenticate(candidate.controllerId, candidate.secret, candidate.tvCertificateFingerprint)
                }
            }
            // 认证挂起后再次检查：导航/退后台/配对/新操作都放弃绑定
            if (!isCurrent(token) || !reconcileEligible()) return false
            if (outcome.isSuccess) {
                withContext(ioDispatcher) { runCatching { store.promotePending(candidate.id) } }
                if (!isCurrent(token) || !reconcileEligible()) return true
                registry.clear(token, created)
                bindSession(created)
                handedOff = true
                userDisconnected = false
                _state.update {
                    it.copy(
                        screen = if (it.screen == Screen.DEVICES) Screen.REMOTE else it.screen,
                        activeDeviceId = candidate.id,
                        connection = ConnectionPhase.CONNECTED,
                        failure = null,
                        capabilities = outcome.getOrNull(),
                        // 会话接管：切换到不同设备时清除上一台设备的草稿
                        textDraft = if (it.textDraftDeviceId != null && it.textDraftDeviceId != candidate.id) "" else it.textDraft,
                        textDraftDeviceId = candidate.id,
                    )
                }
                applyDevices()
                return true
            }
            // 失败：仅服务端明确拒绝才丢弃，其余保留待下次前台
            val error = outcome.exceptionOrNull()
            if (error is SessionException && error.code == "AUTHENTICATION_FAILED") {
                withContext(ioDispatcher) { runCatching { store.discardPending(candidate.id) } }
            }
            return false
        } finally {
            if (reconcileSession === created) reconcileSession = null
            // 未交接（取消/导航/失败）→ 关闭并清空登记，避免残留 socket
            if (!handedOff) {
                registry.clear(token, created)
                runCatching { created.close() }
            }
        }
    }

    // ---- 遥控 / 文字 ----

    /** R3：已连接时直接返回遥控页；未连接则按需连接。 */
    fun openRemote() {
        val state = _state.value
        val activeId = state.activeDeviceId
        reconnectCycles = 0
        if (state.connection == ConnectionPhase.CONNECTED && activeId != null) {
            userDisconnected = false
            _state.update { it.copy(screen = Screen.REMOTE) }
        } else if (activeId != null) {
            userDisconnected = false
            _state.update { it.copy(failure = null) }
            connect(activeId, auto = false)
        }
    }

    fun sendKey(key: String, state: String, repeatCount: Int = 0) {
        val current = session ?: return
        scope.launch {
            try {
                val ack = withContext(ioDispatcher) { current.sendKeyEvent(key, state, repeatCount) }
                if (session !== current) return@launch
                _state.update { it.copy(lastAck = describeAck(key, ack)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                handleSessionError(error, current)
            }
        }
    }

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
        scope.launch {
            try {
                val ack = withContext(ioDispatcher) { current.sendText(sentText) }
                if (session !== current) return@launch
                _state.update {
                    val clearDraft = ack.isSuccess && it.textDraft == sentText
                    it.copy(
                        textStatus = if (ack.isSuccess) TextSendStatus.SENT else TextSendStatus.FAILED,
                        textDraft = if (clearDraft) "" else it.textDraft,
                        lastAck = if (ack.isSuccess) str(R.string.vm_text_sent) else describeAck(str(R.string.vm_text_label), ack),
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (session === current) _state.update { it.copy(textStatus = TextSendStatus.FAILED) }
                handleSessionError(error, current)
            }
        }
    }

    fun refreshCapabilities() {
        val current = session ?: return
        scope.launch {
            try {
                val capabilities = withContext(ioDispatcher) { current.requestCapabilities() }
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

    fun openSettings() {
        releaseAllRepeats()
        _state.update { it.copy(screen = Screen.SETTINGS) }
    }

    fun backToDevices() {
        releaseAllRepeats()
        _state.update { it.copy(screen = Screen.DEVICES, notice = null) }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---- 内部 ----

    private suspend fun applyDevices() {
        val loaded = withContext(ioDispatcher) { runCatching { store.load() }.getOrNull() }
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

    private fun handleSessionError(error: Throwable, origin: ControllerSession? = null) {
        if (origin != null && session !== origin) return
        keyRepeat?.stopAll()
        val failure = classify(error)
        when (failure.kind) {
            FailureKind.NETWORK -> {
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
            FailureKind.CERTIFICATE, FailureKind.CREDENTIAL_REVOKED, FailureKind.BUSY -> {
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

    /** R6：运行中断线的有界自动重连（仅前台、非用户断开、DEVICES/REMOTE、非配对中）。 */
    private fun scheduleReconnect() {
        if (!foreground || userDisconnected) return
        if (reconnectCycles >= MAX_RECONNECT_CYCLES) return
        val deviceId = _state.value.activeDeviceId ?: return
        val token = currentGeneration()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            if (!isCurrent(token) || !foreground || userDisconnected) return@launch
            if (_state.value.pairing != null) return@launch
            val screen = _state.value.screen
            if (screen != Screen.DEVICES && screen != Screen.REMOTE) return@launch
            reconnectCycles += 1
            connect(deviceId, auto = true)
        }
    }

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

    private fun sessionFailure(error: SessionException): ConnectionFailure = when (error.code) {
        "AUTHENTICATION_FAILED" ->
            ConnectionFailure(FailureKind.CREDENTIAL_REVOKED, str(R.string.vm_credential_revoked))
        "INVALID_SESSION", "AUTHENTICATION_REQUIRED" ->
            ConnectionFailure(FailureKind.NETWORK, str(R.string.vm_connection_closed))
        "BUSY" -> ConnectionFailure(FailureKind.BUSY, str(R.string.vm_tv_busy))
        else -> ConnectionFailure(FailureKind.PROTOCOL, str(R.string.vm_tv_rejected, error.message ?: error.code))
    }

    private fun classify(error: Throwable): ConnectionFailure = when {
        error is SessionException -> sessionFailure(error)
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
        error is java.net.SocketException || error is java.io.EOFException || error is java.io.IOException ->
            ConnectionFailure(FailureKind.NETWORK, str(R.string.vm_connection_closed))
        error is PairingRejectedException ->
            ConnectionFailure(FailureKind.PAIRING, error.message ?: str(R.string.vm_pairing_rejected))
        error is IllegalArgumentException || error is IllegalStateException ->
            ConnectionFailure(FailureKind.PROTOCOL, error.message ?: str(R.string.vm_protocol_error))
        else -> ConnectionFailure(FailureKind.UNKNOWN, error.message ?: error.javaClass.simpleName)
    }

    companion object {
        const val CONTROLLER_NAME = "Android Phone"
        private const val MAX_RECONNECT_CYCLES = 12
    }
}
