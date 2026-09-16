package dev.lucasdone.tvremote.xiaomi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.lucasdone.tvremote.agent.auth.KeystoreCredentialStore
import dev.lucasdone.tvremote.agent.auth.PairingManager
import dev.lucasdone.tvremote.agent.auth.PairingSas
import dev.lucasdone.tvremote.agent.auth.PairingWindow
import dev.lucasdone.tvremote.agent.auth.SessionManager
import dev.lucasdone.tvremote.agent.command.CommandDispatcher
import dev.lucasdone.tvremote.agent.command.KeyStateTracker
import dev.lucasdone.tvremote.agent.command.MediaCommandExecutor
import dev.lucasdone.tvremote.agent.command.TextCommandDispatcher
import dev.lucasdone.tvremote.agent.media.DisabledControlMediaSession
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.transport.ControlServer
import dev.lucasdone.tvremote.agent.transport.ControlServerCallbacks
import dev.lucasdone.tvremote.agent.transport.DiscoveryServer
import dev.lucasdone.tvremote.agent.transport.TlsIdentityStore
import dev.lucasdone.tvremote.xiaomi.backend.AdbIdentityStore
import dev.lucasdone.tvremote.xiaomi.backend.AdbKeyBackend
import dev.lucasdone.tvremote.xiaomi.backend.BackendKeyExecutor
import dev.lucasdone.tvremote.xiaomi.backend.KeyBackend
import dev.lucasdone.tvremote.xiaomi.backend.LocalBackends
import dev.lucasdone.tvremote.xiaomi.input.ImeTextExecutor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class XiaomiAgentService : Service(), ControlServerCallbacks {
    private val resources = AgentResources()
    private val generation = GENERATION.begin()
    private val status = AgentStatusPublisher({ !resources.isClosed }, { GENERATION.isCurrent(generation) })
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "xiaomi-agent-lifecycle").apply { isDaemon = true } }
    @Volatile private var credentials: KeystoreCredentialStore? = null
    @Volatile private var listenerFailed = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, "电视遥控服务", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(NOTIFICATION, notification("正在启动"))
        status.starting()
        worker.execute { initialize() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            TvPreferences(this).startAtBoot = false
            stopSelf()
            return START_NOT_STICKY
        }
        worker.execute {
            if (resources.isClosed) return@execute
            try {
                when (intent?.action) {
                    REFRESH -> if (control() == null || listenerFailed) rebuild() else refreshBackend()
                    PAIR -> control()?.openPairingWindow() ?: throw IllegalStateException("service not ready")
                    CONFIRM -> {
                        val id = intent.getStringExtra("pairingId").orEmpty()
                        if (control()?.confirmPairing(id, intent.getBooleanExtra("accepted", false)) != true)
                            throw IllegalStateException("pairing expired")
                    }
                    DISCONNECT -> control()?.disconnectActive()
                    REVOKE -> {
                        val controllerId = intent.getStringExtra("controllerId").orEmpty()
                        if (!isValidControllerId(controllerId)) throw IllegalArgumentException("invalid controllerId")
                        control()?.revokeController(controllerId)
                        refreshControllers()
                    }
                    TEST_VOLUME -> {
                        val result = resources.currentBackend()?.takeIf { LogicalKey.VOLUME_UP in it.keys }?.press(LogicalKey.VOLUME_UP)
                        publishAction(if (result == null) "当前后端不能测试音量键" else "测试命令：${result.name}；请核对实际音量变化")
                    }
                    null, START -> Unit
                    else -> throw IllegalArgumentException("unknown service action")
                }
            } catch (error: Exception) {
                publishAction("操作失败（${error.javaClass.simpleName}），请检查设置后重试")
                Log.w(TAG, "Local action failed: ${error.javaClass.simpleName}")
            }
        }
        return START_STICKY
    }
    private fun initialize() {
        var selected: KeyBackend? = null
        var server: ControlServer? = null
        var probe: DiscoveryServer? = null
        try {
            selected = selectBackend()
            val identity = TlsIdentityStore(this).loadOrCreate()
            if (identity.regenerated) publishAction("安全身份已重建，请在手机端重新配对")
            val store = KeystoreCredentialStore(this)
            server = ControlServer(identity, store, PairingManager(), SessionManager(),
                dispatcherFactory = {
                    CommandDispatcher(KeyStateTracker(), listOf(
                        BackendKeyExecutor { resources.currentBackend() },
                        MediaCommandExecutor(this),
                    ))
                },
                textDispatcherFactory = { TextCommandDispatcher(listOf(ImeTextExecutor())) },
                mediaCoordinator = DisabledControlMediaSession,
                mediaAvailable = { false }, capabilities = { TvCapabilities.snapshot(this, resources.currentBackend()) }, callbacks = this)
            probe = DiscoveryServer(getString(R.string.app_name))
            server.start()
            probe.start()
            if (!resources.install(server, probe, selected)) return
            credentials = store
            listenerFailed = false
        } catch (error: Exception) {
            server?.close(); probe?.close(); selected?.close()
            if (!resources.isClosed) {
                val message = if (error is java.net.BindException) "服务端口被占用，请停止其他电视遥控服务后重试"
                    else "遥控服务启动失败（${error.javaClass.simpleName}），请重新启动"
                status.failed(message)
                notifyStatus(message)
            }
            return
        }
        runCatching {
            TvPreferences(this).startupFailure = false
            refreshControllers()
        }.onFailure { Log.w(TAG, "Post-start bookkeeping failed: ${it.javaClass.simpleName}") }
        status.listening()
        notifyStatus("等待已配对手机")
    }
    /** Closes a dead control/discovery listener, then rebuilds it. REFRESH uses this after onNetworkFailure. */
    private fun rebuild() {
        listenerFailed = false
        try {
            AgentResources.closeDetached(resources.detachForRebuild())
        } catch (error: Exception) {
            Log.w(TAG, "Previous listener teardown failed: ${error.javaClass.simpleName}")
        }
        initialize()
    }
    private fun refreshBackend() {
        control()?.disconnectActive()
        resources.swapBackend(selectBackend())
    }
    private fun control(): ControlServer? = resources.control() as? ControlServer
    private fun selectBackend(): KeyBackend? {
        publishBackend("正在检测本机遥控接口")
        val probed = LocalBackends.probeFirst { provider, error ->
            Log.i(TAG, "Local provider ${provider.id} unavailable: ${error.javaClass.simpleName}")
        }
        if (probed != null) {
            publishBackend("${probed.first.displayName}可连接；按键效果待实测")
            return probed.second
        }
        if (!TvPreferences(this).adbAllowed) {
            publishBackend("本机遥控接口不可用；ADB 备用未启用。基础音量/媒体键可尝试")
            return null
        }
        publishBackend("正在连接本机 ADB；若出现系统提示，请用实体遥控器授权后重新检测")
        return try {
            val selected = AdbKeyBackend.connect(AdbIdentityStore(this).loadOrCreate())
            publishBackend("本机 ADB 已授权；按键效果待实测")
            selected
        } catch (error: Exception) {
            publishBackend("本机 ADB 不可用（${error.javaClass.simpleName}）；请检查调试开关及授权后重新检测")
            null
        }
    }
    override fun onPairingWindow(window: PairingWindow) {
        status.pairingWindow(window)
        worker.schedule({ status.expirePairing(window.expiresAtMs) },
            maxOf(0L, window.expiresAtMs - System.nanoTime() / 1_000_000L), TimeUnit.MILLISECONDS)
    }
    override fun onPairingSas(details: PairingSas) { status.pairingSas(details) }
    override fun onPairingClosed(pairingId: String) { status.pairingClosed(pairingId); refreshControllers() }
    override fun onControllerConnected(controllerName: String) {
        status.connected(controllerName); notifyStatus("手机正在连接并控制电视")
    }
    override fun onControllerDisconnected() { status.disconnected(); notifyStatus("手机已断开") }
    override fun onNetworkFailure(reason: String) { listenerFailed = true; status.failed("网络监听异常，请重新启动服务") }
    private fun refreshControllers() { credentials?.let { status.pairedControllers(it.controllerSummaries()) } }
    // Shared UI state is process-global, so a stopped instance must not overwrite a newer instance's text.
    private fun publishBackend(text: String) {
        if (GENERATION.isCurrent(generation)) backendDescription = text
    }
    private fun publishAction(text: String) {
        if (GENERATION.isCurrent(generation)) lastAction = text
    }
    private fun notifyStatus(text: String) {
        if (!resources.isClosed) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification(text))
    }
    @Suppress("DEPRECATION")
    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, XiaomiAgentService::class.java).setAction(STOP), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(getString(R.string.app_name))
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_delete, "停止服务", stop).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        val detached = resources.detach()
        status.stopped()
        publishBackend("服务未启动")
        Thread({
            try {
                AgentResources.closeDetached(detached)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Resource teardown failed: ${error.javaClass.simpleName}")
            }
        }, "tvrc-resource-teardown").apply { isDaemon = true }.start()
        worker.shutdownNow()
        super.onDestroy()
    }
    companion object {
        private const val TAG = "TvRemoteAgent"
        private val GENERATION = GenerationGate()
        private const val CHANNEL = "xiaomi_tv_remote"
        private const val NOTIFICATION = 4101
        const val START = "xiaomi.START"
        const val STOP = "xiaomi.STOP"
        const val REFRESH = "xiaomi.REFRESH"
        const val PAIR = "xiaomi.PAIR"
        const val CONFIRM = "xiaomi.CONFIRM"
        const val DISCONNECT = "xiaomi.DISCONNECT"
        const val REVOKE = "xiaomi.REVOKE"
        const val TEST_VOLUME = "xiaomi.TEST_VOLUME"
        @Volatile var backendDescription = "服务未启动"
            private set
        @Volatile var lastAction = ""
            private set
        fun start(context: Context, action: String = START, configure: (Intent) -> Unit = {}) {
            val intent = Intent(context, XiaomiAgentService::class.java).setAction(action).also(configure)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
