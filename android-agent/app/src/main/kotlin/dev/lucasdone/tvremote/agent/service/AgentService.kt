package dev.lucasdone.tvremote.agent.service

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
import dev.lucasdone.tvremote.agent.auth.KeystoreCredentialStore
import dev.lucasdone.tvremote.agent.auth.PairingManager
import dev.lucasdone.tvremote.agent.auth.PairingSas
import dev.lucasdone.tvremote.agent.auth.PairingWindow
import dev.lucasdone.tvremote.agent.auth.SessionManager
import dev.lucasdone.tvremote.agent.command.AccessibilityCommandExecutor
import dev.lucasdone.tvremote.agent.command.CommandDispatcher
import dev.lucasdone.tvremote.agent.command.KeyStateTracker
import dev.lucasdone.tvremote.agent.command.MediaCommandExecutor
import dev.lucasdone.tvremote.agent.device.CapabilityDetector
import dev.lucasdone.tvremote.agent.media.MediaRuntime
import dev.lucasdone.tvremote.agent.media.MediaSessionCoordinator
import dev.lucasdone.tvremote.agent.transport.ControlServer
import dev.lucasdone.tvremote.agent.transport.ControlServerCallbacks
import dev.lucasdone.tvremote.agent.transport.DiscoveryServer
import dev.lucasdone.tvremote.agent.transport.TlsIdentityStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("ApplySharedPref")
class AgentService : Service(), ControlServerCallbacks {
    private val networkLock = Any()
    private var destroyed = false
    private val lifecycleExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tvrc-service-lifecycle").apply { isDaemon = true }
    }
    @Volatile private var controlServer: ControlServer? = null
    @Volatile private var discoveryServer: DiscoveryServer? = null
    @Volatile private var credentialStore: KeystoreCredentialStore? = null
    @Volatile private var mediaCoordinator: MediaSessionCoordinator? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在初始化 TLS 网络遥控"))
        AgentStatusRegistry.starting()
        lifecycleExecutor.execute(::initializeNetwork)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                preferences().edit().putBoolean(KEY_START_AT_BOOT, false).commit()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> lifecycleExecutor.execute { controlServer?.disconnectActive() }
            ACTION_REVOKE_CONTROLLER -> {
                val controllerId = intent.getStringExtra(EXTRA_CONTROLLER_ID).orEmpty()
                lifecycleExecutor.execute {
                    if (controllerId.isNotEmpty()) {
                        controlServer?.revokeController(controllerId)
                        refreshControllers()
                    }
                }
            }
            ACTION_OPEN_PAIRING -> lifecycleExecutor.execute {
                val server = controlServer
                if (server == null) AgentStatusRegistry.failed("TLS 网络遥控尚未就绪") else server.openPairingWindow()
            }
            ACTION_CONFIRM_PAIRING -> {
                val pairingId = intent.getStringExtra(EXTRA_PAIRING_ID).orEmpty()
                val accepted = intent.getBooleanExtra(EXTRA_PAIRING_ACCEPTED, false)
                lifecycleExecutor.execute {
                    if (pairingId.isEmpty() || controlServer?.confirmPairing(pairingId, accepted) != true) {
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
        val resources = synchronized(networkLock) {
            destroyed = true
            val snapshot = Triple(controlServer, discoveryServer, mediaCoordinator)
            controlServer = null
            discoveryServer = null
            credentialStore = null
            mediaCoordinator = null
            snapshot
        }
        resources.first?.close()
        resources.second?.close()
        resources.third?.let(MediaRuntime::uninstall)
        lifecycleExecutor.shutdownNow()
        lifecycleExecutor.awaitTermination(2, TimeUnit.SECONDS)
        AgentStatusRegistry.stopped()
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
        var media: MediaSessionCoordinator? = null
        try {
            val identity = TlsIdentityStore(this).loadOrCreate()
            val credentialStore = KeystoreCredentialStore(this)
            media = MediaSessionCoordinator(onState = ::onMediaState)
            MediaRuntime.install(media)
            control = ControlServer(
                identity = identity,
                credentialStore = credentialStore,
                pairingManager = PairingManager(),
                sessionManager = SessionManager(),
                dispatcherFactory = {
                    CommandDispatcher(
                        KeyStateTracker(),
                        listOf(AccessibilityCommandExecutor(), MediaCommandExecutor(this)),
                    )
                },
                mediaCoordinator = media,
                mediaAvailable = { CapabilityDetector.isMediaTransportAvailable(this) },
                capabilities = { CapabilityDetector.detect(this).toProtocolJson() },
                callbacks = this,
            )
            discovery = DiscoveryServer(displayName = getString(dev.lucasdone.tvremote.agent.R.string.app_name))
            control.start()
            discovery.start()
            synchronized(networkLock) {
                if (destroyed) throw InterruptedException("service was destroyed during network initialization")
                controlServer = control
                discoveryServer = discovery
                this.credentialStore = credentialStore
                mediaCoordinator = media
            }
            AgentStatusRegistry.listening()
            refreshControllers()
            updateNotification("等待已认证的控制端")
        } catch (error: Exception) {
            discovery?.close()
            control?.close()
            media?.let(MediaRuntime::uninstall)
            if (synchronized(networkLock) { destroyed }) return
            val reason = when (error) {
                is java.security.GeneralSecurityException -> "TLS 身份初始化失败，网络遥控不可用"
                else -> "安全网络监听启动失败，网络遥控不可用"
            }
            Log.e(TAG, "$reason: ${error.javaClass.simpleName}")
            AgentStatusRegistry.failed(reason)
            updateNotification(reason)
        }
    }

    override fun onPairingWindow(window: PairingWindow) {
        AgentStatusRegistry.pairingWindow(window)
        // 按剩余时间精确调度，避免 openWindow 到回调间的延迟造成 UI 过期滞后。
        // 与 PairingManager 共用 System.nanoTime() 单调时钟。
        val remainingMs = maxOf(0L, window.expiresAtMs - System.nanoTime() / 1_000_000L)
        lifecycleExecutor.schedule(
            { AgentStatusRegistry.expirePairing(window.expiresAtMs) },
            remainingMs,
            TimeUnit.MILLISECONDS,
        )
        updateNotification("电视端配对窗口已开启")
    }

    override fun onPairingSas(details: PairingSas) {
        AgentStatusRegistry.pairingSas(details)
        updateNotification("请在电视画面核对配对码")
    }

    override fun onPairingClosed(pairingId: String) {
        AgentStatusRegistry.pairingClosed(pairingId)
        refreshControllers()
        updateNotification("等待已认证的控制端")
    }

    override fun onControllerConnected(controllerName: String) {
        AgentStatusRegistry.connected(controllerName)
        updateNotification("已连接：$controllerName")
    }

    override fun onControllerDisconnected() {
        AgentStatusRegistry.disconnected()
        updateNotification("控制端已断开")
    }

    override fun onNetworkFailure(reason: String) {
        AgentStatusRegistry.failed(reason)
        updateNotification(reason)
    }

    private fun onMediaState(state: String) {
        controlServer?.notifyMediaState(
            when (state) {
                "media_requested", "media_permission_required" -> "waiting_tv_authorization"
                "media_streaming" -> "streaming"
                "media_streaming_video_only", "video_only_audio_unavailable" -> "video_only"
                "media_idle" -> "stopped"
                else -> "failed"
            },
        )
        when (state) {
            "media_requested" -> AgentStatusRegistry.mediaAttaching()
            "media_permission_required" -> {
                val attachmentId = MediaRuntime.currentAttachmentId() ?: return
                AgentStatusRegistry.mediaPermissionRequired(attachmentId)
                updateNotification("控制端请求屏幕共享，请在电视端确认")
            }
            "media_streaming" -> {
                AgentStatusRegistry.mediaStreaming(videoOnly = false)
                updateNotification("正在共享电视画面")
            }
            "media_streaming_video_only", "video_only_audio_unavailable" -> {
                AgentStatusRegistry.mediaStreaming(videoOnly = true)
                updateNotification("正在共享电视画面（无音频）")
            }
            "media_idle" -> {
                AgentStatusRegistry.mediaIdle()
                ProjectionService.stop(this)
            }
            else -> {
                Log.w(TAG, "Media pipeline failed: $state")
                AgentStatusRegistry.mediaFailed()
                ProjectionService.stop(this)
            }
        }
    }

    private fun refreshControllers() {
        val controllers = credentialStore?.controllerSummaries() ?: emptyList()
        AgentStatusRegistry.pairedControllers(controllers)
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
            .setContentIntent(activityPendingIntent(Intent(this, dev.lucasdone.tvremote.agent.MainActivity::class.java), 10))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开控制端", servicePendingIntent(serviceIntent(ACTION_DISCONNECT), 11))
            .addAction(android.R.drawable.ic_delete, "停止服务", servicePendingIntent(serviceIntent(ACTION_STOP), 12))
            .build()
    }

    private fun serviceIntent(action: String) = Intent(this, AgentService::class.java).setAction(action)
    private fun pendingIntentFlags() = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    private fun servicePendingIntent(intent: Intent, requestCode: Int) = PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
    private fun activityPendingIntent(intent: Intent, requestCode: Int) = PendingIntent.getActivity(this, requestCode, intent, pendingIntentFlags())
    private fun preferences() = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "tv_remote_agent"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "dev.lucasdone.tvremote.agent.START"
        const val ACTION_STOP = "dev.lucasdone.tvremote.agent.STOP"
        const val ACTION_DISCONNECT = "dev.lucasdone.tvremote.agent.DISCONNECT"
        const val ACTION_OPEN_PAIRING = "dev.lucasdone.tvremote.agent.OPEN_PAIRING"
        const val ACTION_CONFIRM_PAIRING = "dev.lucasdone.tvremote.agent.CONFIRM_PAIRING"
        const val ACTION_REVOKE_CONTROLLER = "dev.lucasdone.tvremote.agent.REVOKE_CONTROLLER"
        const val ACTION_REJECT_MEDIA = "dev.lucasdone.tvremote.agent.REJECT_MEDIA"
        private const val EXTRA_PAIRING_ID = "pairing_id"
        private const val EXTRA_PAIRING_ACCEPTED = "pairing_accepted"
        private const val EXTRA_CONTROLLER_ID = "controller_id"
        private const val EXTRA_ATTACHMENT_ID = "attachment_id"
        private const val PREFERENCES = "agent_service"
        private const val KEY_START_AT_BOOT = "start_at_boot"

        fun start(context: Context, enableAtBoot: Boolean = true) {
            if (enableAtBoot) {
                check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_START_AT_BOOT, true).commit()) {
                    "failed to persist background service preference"
                }
            }
            send(context, Intent(context, AgentService::class.java).setAction(ACTION_START))
        }

        fun openPairing(context: Context) = send(context, Intent(context, AgentService::class.java).setAction(ACTION_OPEN_PAIRING))

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

        fun shouldStartAtBoot(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_START_AT_BOOT, false)
    }
}
