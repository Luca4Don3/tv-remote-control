package dev.tvremote.agent.service

import android.annotation.SuppressLint
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
import dev.tvremote.agent.BuildConfig
import dev.tvremote.agent.adapter.BackendKeyExecutor
import dev.tvremote.agent.adapter.KeyBackend
import dev.tvremote.agent.adapter.LocalBackends
import dev.tvremote.agent.adapter.adb.AdbIdentityStore
import dev.tvremote.agent.adapter.adb.AdbKeyBackend
import dev.tvremote.agent.auth.KeystoreCredentialStore
import dev.tvremote.agent.auth.PairingManager
import dev.tvremote.agent.auth.PairingSas
import dev.tvremote.agent.auth.PairingWindow
import dev.tvremote.agent.auth.SessionManager
import dev.tvremote.agent.command.AccessibilityCommandExecutor
import dev.tvremote.agent.command.AccessibilityTextCommandExecutor
import dev.tvremote.agent.command.CommandDispatcher
import dev.tvremote.agent.command.CommandExecutor
import dev.tvremote.agent.command.KeyStateTracker
import dev.tvremote.agent.command.MediaCommandExecutor
import dev.tvremote.agent.command.TextCommandDispatcher
import dev.tvremote.agent.command.TextCommandExecutor
import dev.tvremote.agent.device.CapabilityDetector
import dev.tvremote.agent.device.DeviceProfile
import dev.tvremote.agent.device.DeviceProfiles
import dev.tvremote.agent.device.TextInputChannel
import dev.tvremote.agent.input.ImeTextExecutor
import dev.tvremote.agent.media.ControlMediaSession
import dev.tvremote.agent.media.DisabledControlMediaSession
import dev.tvremote.agent.media.MediaRuntime
import dev.tvremote.agent.media.MediaSessionCoordinator
import dev.tvremote.agent.model.LogicalKey
import dev.tvremote.agent.transport.ControlServer
import dev.tvremote.agent.transport.ControlServerCallbacks
import dev.tvremote.agent.transport.DiscoveryServer
import dev.tvremote.agent.transport.TlsIdentityStore
import dev.tvremote.agent.transport.ws.WebSocketDebugServer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Single TV-side agent. The active [DeviceProfile] decides which control backends exist and which
 * agent surfaces (accessibility, MediaProjection, text input) are exposed, so one APK adapts to
 * every supported set-top box without per-vendor builds.
 */
@SuppressLint("ApplySharedPref")
class AgentService : Service(), ControlServerCallbacks {
    private val resources = AgentResources()
    private val generation = GENERATION.begin()
    private lateinit var profile: DeviceProfile
    private val status = AgentStatusPublisher(
        isActive = { !resources.isClosed },
        isCurrentInstance = { GENERATION.isCurrent(generation) },
    )
    private val lifecycleExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tvrc-service-lifecycle").apply { isDaemon = true }
    }
    @Volatile private var credentialStore: KeystoreCredentialStore? = null
    @Volatile private var mediaCoordinator: ControlMediaSession? = null
    @Volatile private var wsDebugServer: WebSocketDebugServer? = null
    @Volatile private var listenerFailed = false

    override fun onCreate() {
        super.onCreate()
        profile = DeviceProfiles.resolve(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动"))
        status.starting()
        lifecycleExecutor.execute(::initializeNetwork)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                preferences().startAtBoot = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> lifecycleExecutor.execute { control()?.disconnectActive() }
            ACTION_REFRESH -> lifecycleExecutor.execute {
                if (control() == null || listenerFailed) rebuild() else refreshBackend()
            }
            ACTION_TEST_VOLUME -> lifecycleExecutor.execute { testVolume() }
            ACTION_REVOKE_CONTROLLER -> {
                val controllerId = intent.getStringExtra(EXTRA_CONTROLLER_ID).orEmpty()
                lifecycleExecutor.execute {
                    if (isValidControllerId(controllerId)) {
                        control()?.revokeController(controllerId)
                        refreshControllers()
                    } else {
                        Log.w(TAG, "Ignored invalid controller id")
                    }
                }
            }
            ACTION_OPEN_PAIRING -> lifecycleExecutor.execute {
                val server = control()
                if (server == null) status.failed("网络遥控尚未就绪") else server.openPairingWindow()
            }
            ACTION_CONFIRM_PAIRING -> {
                val pairingId = intent.getStringExtra(EXTRA_PAIRING_ID).orEmpty()
                val accepted = intent.getBooleanExtra(EXTRA_PAIRING_ACCEPTED, false)
                lifecycleExecutor.execute {
                    if (pairingId.isEmpty() || control()?.confirmPairing(pairingId, accepted) != true) {
                        Log.w(TAG, "Ignored stale local pairing confirmation")
                    }
                }
            }
            ACTION_REJECT_MEDIA -> {
                val attachmentId = intent.getLongExtra(EXTRA_ATTACHMENT_ID, -1L)
                lifecycleExecutor.execute {
                    if (attachmentId > 0L) mediaCoordinator?.stopAttachment(attachmentId)
                }
            }
            ACTION_START, null -> Unit
            else -> Log.e(TAG, "Unknown service action: ${intent.action}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val detached = resources.detach()
        val media = mediaCoordinator
        val ws = wsDebugServer
        mediaCoordinator = null
        wsDebugServer = null
        credentialStore = null
        status.stopped()
        Thread({
            try {
                AgentResources.closeDetached(detached)
                ws?.close()
                (media as? MediaSessionCoordinator)?.let(MediaRuntime::uninstall) ?: media?.close()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Resource teardown failed: ${error.javaClass.simpleName}")
            }
        }, "tvrc-resource-teardown").apply { isDaemon = true }.start()
        lifecycleExecutor.shutdownNow()
        lifecycleExecutor.awaitTermination(2, TimeUnit.SECONDS)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initializeNetwork() {
        var control: ControlServer? = null
        var discovery: DiscoveryServer? = null
        var wsDebug: WebSocketDebugServer? = null
        var media: ControlMediaSession? = null
        var mediaInstalled = false
        var selected: KeyBackend? = null
        try {
            selected = selectBackend()
            val identity = TlsIdentityStore(this).loadOrCreate()
            val store = KeystoreCredentialStore(this)
            media = if (profile.mediaEnabled) {
                MediaSessionCoordinator(onState = ::onMediaState).also {
                    MediaRuntime.install(it)
                    mediaInstalled = true
                }
            } else {
                DisabledControlMediaSession
            }
            control = ControlServer(
                identity = identity,
                credentialStore = store,
                pairingManager = PairingManager(),
                sessionManager = SessionManager(),
                dispatcherFactory = { CommandDispatcher(KeyStateTracker(), buildKeyExecutors()) },
                textDispatcherFactory = { TextCommandDispatcher(buildTextExecutors()) },
                mediaCoordinator = media,
                mediaAvailable = { profile.mediaEnabled && CapabilityDetector.isMediaTransportAvailable(this) },
                capabilities = { CapabilityDetector.detect(this, profile, resources.currentBackend()).toProtocolJson() },
                callbacks = this,
            )
            // 明文调试通道仅限 debug 构建（生产 APK 不含 47833 监听）：
            // 与项目安全边界一致——正式控制链路仅 TLS；调试 WS 无运行时用户开关。
            if (BuildConfig.DEBUG) {
                wsDebug = WebSocketDebugServer(
                    credentialStore = store,
                    dispatcherFactory = { CommandDispatcher(KeyStateTracker(), buildKeyExecutors()) },
                    textDispatcherFactory = { TextCommandDispatcher(buildTextExecutors()) },
                )
                wsDebug.start()
                Log.i(TAG, "WS debug channel enabled (debug build)")
            }
            discovery = DiscoveryServer(displayName = getString(dev.tvremote.agent.R.string.app_name))
            control.start()
            discovery.start()
            if (!resources.install(control, discovery, selected)) {
                wsDebug?.close()
                if (mediaInstalled) MediaRuntime.uninstall(media as MediaSessionCoordinator)
                return
            }
            credentialStore = store
            mediaCoordinator = media
            wsDebugServer = wsDebug
            listenerFailed = false
            runCatching {
                preferences().startupFailure = false
                refreshControllers()
            }.onFailure { Log.w(TAG, "Post-start bookkeeping failed: ${it.javaClass.simpleName}") }
            status.listening()
            updateNotification(if (identity.regenerated) "安全身份已重建，请重新配对控制端" else "等待已认证的控制端")
            autoPairIfUnpaired()
        } catch (error: Exception) {
            wsDebug?.close()
            discovery?.close()
            control?.close()
            selected?.close()
            if (mediaInstalled) MediaRuntime.uninstall(media as MediaSessionCoordinator)
            if (resources.isClosed) return
            val reason = when {
                error is java.net.BindException -> "服务端口被占用，请停止其他电视遥控服务后重试"
                error is java.security.GeneralSecurityException -> "TLS 身份初始化失败，网络遥控不可用"
                else -> "安全网络监听启动失败，网络遥控不可用"
            }
            Log.e(TAG, "$reason: ${error.javaClass.simpleName}")
            status.failed(reason)
            updateNotification(reason)
        }
    }

    /** Closes a dead control/discovery listener, then rebuilds it. REFRESH uses this after onNetworkFailure. */
    private fun rebuild() {
        listenerFailed = false
        try {
            AgentResources.closeDetached(resources.detachForRebuild())
        } catch (error: Exception) {
            Log.w(TAG, "Previous listener teardown failed: ${error.javaClass.simpleName}")
        }
        initializeNetwork()
    }

    private fun refreshBackend() {
        control()?.disconnectActive()
        resources.swapBackend(selectBackend())
    }

    private fun testVolume() {
        val result = resources.currentBackend()
            ?.takeIf { LogicalKey.VOLUME_UP in it.keys }
            ?.press(LogicalKey.VOLUME_UP)
        status.lastAction(
            if (result == null) "当前后端不能测试音量键" else "测试命令：${result.name}；请核对实际音量变化",
        )
    }

    private fun buildKeyExecutors(): List<CommandExecutor> = buildList {
        if (profile.backendProviders.isNotEmpty()) add(BackendKeyExecutor { resources.currentBackend() })
        if (profile.accessibilityEnabled) add(AccessibilityCommandExecutor())
        add(MediaCommandExecutor(this@AgentService))
    }

    private fun buildTextExecutors(): List<TextCommandExecutor> = when (profile.textInput) {
        TextInputChannel.IME -> listOf(ImeTextExecutor())
        TextInputChannel.ACCESSIBILITY -> listOf(AccessibilityTextCommandExecutor())
        TextInputChannel.NONE -> emptyList()
    }

    private fun selectBackend(): KeyBackend? {
        status.backend("正在检测本机遥控接口")
        val providers = profile.backendProviders
        val probed = if (providers.isEmpty()) {
            null
        } else {
            LocalBackends.probeFirst(providers) { provider, error ->
                Log.i(TAG, "Local provider ${provider.id} unavailable: ${error.javaClass.simpleName}")
            }
        }
        if (probed != null) {
            status.backend("${probed.first.displayName}可连接；按键效果待实测")
            return probed.second
        }
        if (!profile.localAdbEnabled) {
            status.backend("无厂商本机接口；使用系统按键与媒体控制")
            return null
        }
        if (!preferences().adbAllowed) {
            status.backend("本机遥控接口不可用；ADB 备用未启用。基础音量/媒体键可尝试")
            return null
        }
        status.backend("正在连接本机 ADB；若出现系统提示，请用实体遥控器授权后重新检测")
        return try {
            val selected = AdbKeyBackend.connect(AdbIdentityStore(this).loadOrCreate())
            status.backend("本机 ADB 已授权；按键效果待实测")
            selected
        } catch (error: Exception) {
            status.backend("本机 ADB 不可用（${error.javaClass.simpleName}）；请检查调试开关及授权后重新检测")
            null
        }
    }

    private fun control(): ControlServer? = resources.control() as? ControlServer

    override fun onPairingWindow(window: PairingWindow) {
        status.pairingWindow(window)
        statusExpirePairing(window.expiresAtMs)
        updateNotification("电视端配对窗口已开启")
    }

    override fun onPairingSas(details: PairingSas) {
        status.pairingSas(details)
        // SAS 有独立时限：按该时限重新调度到期清理，避免窗口到期把确认弹窗一起收起。
        statusExpirePairing(details.expiresAtMs)
        updateNotification("请在电视画面核对配对码")
    }

    override fun onPairingClosed(pairingId: String) {
        status.pairingClosed(pairingId)
        refreshControllers()
        updateNotification("等待已认证的控制端")
    }

    override fun onControllerConnected(controllerName: String) {
        status.connected(controllerName)
        updateNotification("已连接：$controllerName")
    }

    override fun onControllerDisconnected() {
        status.disconnected()
        updateNotification("控制端已断开")
    }

    override fun onNetworkFailure(reason: String) {
        listenerFailed = true
        status.failed(reason)
        updateNotification(reason)
    }

    private fun statusExpirePairing(expiresAtMs: Long) {
        val remainingMs = maxOf(0L, expiresAtMs - System.nanoTime() / 1_000_000L)
        lifecycleExecutor.schedule(
            {
                status.expirePairing(expiresAtMs)
                autoPairIfUnpaired()
            },
            remainingMs,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Keeps a pairing code on screen while the TV has no paired controller, so a first-time phone can
     * always scan/enter a code without navigating the TV UI. Skips when a window is already active or
     * a controller has been paired (the expiry task re-arms this after each window lapses).
     */
    private fun autoPairIfUnpaired() {
        val server = control() ?: return
        if (AgentStatusRegistry.snapshot().pairing != null) return
        if (credentialStore?.controllerSummaries()?.isNotEmpty() == true) return
        runCatching { server.openPairingWindow() }
            .onFailure { Log.w(TAG, "Auto pairing window failed: ${it.javaClass.simpleName}") }
    }

    private fun onMediaState(state: String) {
        control()?.notifyMediaState(
            when (state) {
                "media_requested", "media_permission_required" -> "waiting_tv_authorization"
                "media_streaming" -> "streaming"
                "media_streaming_video_only", "video_only_audio_unavailable" -> "video_only"
                "media_idle" -> "stopped"
                else -> "failed"
            },
        )
        when (state) {
            "media_requested" -> status.mediaAttaching()
            "media_permission_required" -> {
                val attachmentId = MediaRuntime.currentAttachmentId() ?: return
                status.mediaPermissionRequired(attachmentId)
                updateNotification("控制端请求屏幕共享，请在电视端确认")
            }
            "media_streaming" -> {
                status.mediaStreaming(videoOnly = false)
                updateNotification("正在共享电视画面")
            }
            "media_streaming_video_only", "video_only_audio_unavailable" -> {
                status.mediaStreaming(videoOnly = true)
                updateNotification("正在共享电视画面（无音频）")
            }
            "media_idle" -> {
                status.mediaIdle()
                ProjectionService.stop(this)
            }
            else -> {
                Log.w(TAG, "Media pipeline failed: $state")
                status.mediaFailed()
                ProjectionService.stop(this)
            }
        }
    }

    private fun refreshControllers() {
        val controllers = credentialStore?.controllerSummaries() ?: emptyList()
        status.pairedControllers(controllers)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "TV Remote Agent", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("TV Remote Agent 正在运行")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(activityPendingIntent(Intent(this, dev.tvremote.agent.MainActivity::class.java), 10))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开控制端", servicePendingIntent(serviceIntent(ACTION_DISCONNECT), 11))
            .addAction(android.R.drawable.ic_delete, "停止服务", servicePendingIntent(serviceIntent(ACTION_STOP), 12))
            .build()
    }

    private fun serviceIntent(action: String) = Intent(this, AgentService::class.java).setAction(action)
    private fun pendingIntentFlags() = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    private fun servicePendingIntent(intent: Intent, requestCode: Int) = PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
    private fun activityPendingIntent(intent: Intent, requestCode: Int) = PendingIntent.getActivity(this, requestCode, intent, pendingIntentFlags())
    private fun preferences() = AgentPreferences(this)

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "tv_remote_agent"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "dev.tvremote.agent.START"
        const val ACTION_STOP = "dev.tvremote.agent.STOP"
        const val ACTION_DISCONNECT = "dev.tvremote.agent.DISCONNECT"
        const val ACTION_REFRESH = "dev.tvremote.agent.REFRESH"
        const val ACTION_TEST_VOLUME = "dev.tvremote.agent.TEST_VOLUME"
        const val ACTION_OPEN_PAIRING = "dev.tvremote.agent.OPEN_PAIRING"
        const val ACTION_CONFIRM_PAIRING = "dev.tvremote.agent.CONFIRM_PAIRING"
        const val ACTION_REVOKE_CONTROLLER = "dev.tvremote.agent.REVOKE_CONTROLLER"
        const val ACTION_REJECT_MEDIA = "dev.tvremote.agent.REJECT_MEDIA"
        private const val EXTRA_PAIRING_ID = "pairing_id"
        private const val EXTRA_PAIRING_ACCEPTED = "pairing_accepted"
        private const val EXTRA_CONTROLLER_ID = "controller_id"
        private const val EXTRA_ATTACHMENT_ID = "attachment_id"
        private val GENERATION = GenerationGate()

        fun start(context: Context, enableAtBoot: Boolean = true) {
            if (enableAtBoot) AgentPreferences(context).startAtBoot = true
            send(context, Intent(context, AgentService::class.java).setAction(ACTION_START))
        }

        /** Stops the service from the TV UI (same semantics as the notification action). */
        fun stop(context: Context) = send(context, Intent(context, AgentService::class.java).setAction(ACTION_STOP))

        fun openPairing(context: Context) = send(context, Intent(context, AgentService::class.java).setAction(ACTION_OPEN_PAIRING))

        fun refresh(context: Context) = send(context, Intent(context, AgentService::class.java).setAction(ACTION_REFRESH))

        fun testVolume(context: Context) = send(context, Intent(context, AgentService::class.java).setAction(ACTION_TEST_VOLUME))

        fun confirmPairing(context: Context, pairingId: String, accepted: Boolean) = send(
            context,
            Intent(context, AgentService::class.java)
                .setAction(ACTION_CONFIRM_PAIRING)
                .putExtra(EXTRA_PAIRING_ID, pairingId)
                .putExtra(EXTRA_PAIRING_ACCEPTED, accepted),
        )

        fun revokeController(context: Context, controllerId: String) = send(
            context,
            Intent(context, AgentService::class.java)
                .setAction(ACTION_REVOKE_CONTROLLER)
                .putExtra(EXTRA_CONTROLLER_ID, controllerId),
        )

        fun rejectMedia(context: Context, attachmentId: Long) = send(
            context,
            Intent(context, AgentService::class.java)
                .setAction(ACTION_REJECT_MEDIA)
                .putExtra(EXTRA_ATTACHMENT_ID, attachmentId),
        )

        private fun send(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun shouldStartAtBoot(context: Context): Boolean = AgentPreferences(context).startAtBoot
    }
}
