package dev.tvremote.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.tvremote.agent.auth.DebugCredentialExporter
import dev.tvremote.agent.auth.KeystoreCredentialStore
import dev.tvremote.agent.databinding.ActivityMainBinding
import dev.tvremote.agent.databinding.ItemControllerBinding
import dev.tvremote.agent.device.CapabilityDetector
import dev.tvremote.agent.device.DeviceProfile
import dev.tvremote.agent.device.DeviceProfiles
import dev.tvremote.agent.device.TextInputChannel
import dev.tvremote.agent.input.RemoteInputMethodService
import dev.tvremote.agent.service.AgentMediaState
import dev.tvremote.agent.service.AgentNetworkState
import dev.tvremote.agent.service.AgentPreferences
import dev.tvremote.agent.service.AgentService
import dev.tvremote.agent.service.AgentStatusRegistry
import dev.tvremote.agent.service.AgentUiSnapshot
import dev.tvremote.agent.service.PairingUiState
import dev.tvremote.agent.service.ProjectionService
import dev.tvremote.agent.transport.DiscoveryServer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.EnumMap

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var profile: DeviceProfile
    private var displayedPairingId: String? = null
    private var pairingDialog: AlertDialog? = null
    private val DISCOVERY_CONTROL_PORT = DiscoveryServer.CONTROL_PORT
    private var displayedMediaRequestId = 0L
    private var pendingProjectionRequestId: Long? = null
    private var diagnosticsExpanded = false
    private val statusListener = AgentStatusRegistry.Listener { snapshot ->
        runOnUiThread { renderStatus(snapshot) }
    }
    /** Retries the pairing area when a valid token exists but the LAN address is not resolvable yet. */
    private val hostRetry = Runnable { renderPairing(AgentStatusRegistry.snapshot().pairing) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = DeviceProfiles.resolve(this)
        pendingProjectionRequestId = savedInstanceState?.getLong(STATE_PROJECTION_REQUEST_ID)
            ?.takeIf { it > 0L }
        // 恢复已展示过的会话级 UI 状态，避免旋转后重复弹配对/媒体授权对话框。
        displayedPairingId = savedInstanceState?.getString(STATE_PAIRING_ID)
        displayedMediaRequestId = savedInstanceState?.getLong(STATE_MEDIA_REQUEST_ID) ?: 0L
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyProfileToUi()
        wireActions()
        refreshReport()
        // 打开 App 即确保服务在跑（幂等；不改动“开机启动”偏好），未配对时服务会自动开启配对窗口。
        attempt { AgentService.start(this, enableAtBoot = false) }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshReport()
        updateDeviceFields()
        // 兜底：任何漏掉的监听通知都会在回到前台时同步一次。
        renderStatus(AgentStatusRegistry.snapshot())
    }

    override fun onStart() {
        super.onStart()
        AgentStatusRegistry.addListener(statusListener)
    }

    override fun onStop() {
        AgentStatusRegistry.removeListener(statusListener)
        super.onStop()
    }

    /** Hides sections the active device profile does not expose. */
    private fun applyProfileToUi() {
        binding.mediaCard.visibility = if (profile.mediaEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.imeCard.visibility = if (profile.textInput == TextInputChannel.IME) android.view.View.VISIBLE else android.view.View.GONE
        binding.accessibilityButton.visibility = if (profile.accessibilityEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.adbCheck.visibility = if (profile.localAdbEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.testVolumeButton.visibility =
            if (profile.backendProviders.isNotEmpty() || profile.localAdbEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.debugCredentialsButton.visibility = if (BuildConfig.DEBUG) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun wireActions() {
        binding.primaryActionButton.setOnClickListener { onPrimaryAction() }
        binding.stopButton.setOnClickListener { attempt { AgentService.stop(this) } }
        binding.testVolumeButton.setOnClickListener { attempt { AgentService.testVolume(this) } }
        binding.openPairingButton.setOnClickListener { AgentService.openPairing(this) }
        binding.mediaActionButton.setOnClickListener {
            val snapshot = AgentStatusRegistry.snapshot()
            if (snapshot.mediaState == AgentMediaState.PERMISSION_REQUIRED) {
                showMediaConfirmation(snapshot.mediaRequestId)
            }
        }
        binding.enableImeButton.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        binding.chooseImeButton.setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        binding.accessibilityButton.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.refreshReportButton.setOnClickListener { refreshReport() }
        binding.debugCredentialsButton.setOnClickListener { showDebugCredentials() }
        binding.diagnosticsHeader.setOnClickListener { setDiagnosticsExpanded(!diagnosticsExpanded) }

        val preferences = AgentPreferences(this)
        binding.adbCheck.isChecked = preferences.adbAllowed
        binding.adbCheck.setOnClickListener {
            if (!binding.adbCheck.isChecked) {
                attempt { preferences.adbAllowed = false; AgentService.refresh(this) }
            } else {
                binding.adbCheck.isChecked = false
                AlertDialog.Builder(this).setTitle("启用 ADB 备用？")
                    .setMessage(
                        "仅在本机遥控接口不可用时使用。需要你在电视系统中开启调试，并用实体遥控器批准授权。" +
                            "ADB 是开发调试权限；部分旧固件需电脑辅助，重启后可能需要重新开启。应用仅连接本机并发送固定遥控键。",
                    )
                    .setPositiveButton("允许备用") { _, _ ->
                        attempt {
                            preferences.adbAllowed = true
                            binding.adbCheck.isChecked = true
                            AgentService.refresh(this)
                        }
                    }
                    .setNegativeButton("取消", null).show()
            }
        }
        binding.bootCheck.isChecked = preferences.startAtBoot
        binding.bootCheck.setOnClickListener { attempt { preferences.startAtBoot = binding.bootCheck.isChecked } }
    }

    /** State-aware primary action: start when stopped, otherwise re-probe/rebuild the listeners. */
    private fun onPrimaryAction() {
        if (AgentStatusRegistry.snapshot().networkState == AgentNetworkState.STOPPED) {
            attempt { AgentService.start(this, enableAtBoot = false) }
        } else {
            attempt { AgentService.refresh(this) }
        }
    }

    private fun setDiagnosticsExpanded(expanded: Boolean) {
        diagnosticsExpanded = expanded
        binding.diagnosticsContent.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
        binding.diagnosticsChevron.text = getString(if (expanded) R.string.chevron_expanded else R.string.chevron_collapsed)
    }

    private fun renderStatus(snapshot: AgentUiSnapshot) {
        binding.statusText.text = snapshot.statusText
        applyStatusPill(snapshot.networkState)
        binding.primaryActionButton.setText(
            when (snapshot.networkState) {
                AgentNetworkState.STOPPED -> R.string.btn_start
                AgentNetworkState.FAILED -> R.string.btn_retry
                else -> R.string.btn_refresh
            },
        )
        binding.stopButton.isEnabled = snapshot.networkState != AgentNetworkState.STOPPED
        val action = if (AgentPreferences(this).startupFailure) {
            "上次开机启动失败，请手动启动服务。"
        } else {
            snapshot.lastAction
        }
        binding.summaryText.text =
            if (action.isEmpty()) snapshot.backendDescription else "${snapshot.backendDescription} ｜ $action"
        updateDeviceFields()
        binding.mediaStatusText.text = when (snapshot.mediaState) {
            AgentMediaState.IDLE -> "屏幕共享：空闲"
            AgentMediaState.ATTACHING -> "屏幕共享：等待安全媒体连接"
            AgentMediaState.PERMISSION_REQUIRED -> "屏幕共享：等待电视端授权"
            AgentMediaState.STREAMING -> "屏幕共享：正在传输画面和音频"
            AgentMediaState.STREAMING_VIDEO_ONLY -> "屏幕共享：正在传输画面（无音频）"
            AgentMediaState.FAILED -> "屏幕共享：采集失败"
        }
        binding.mediaActionButton.isEnabled = snapshot.mediaState == AgentMediaState.PERMISSION_REQUIRED &&
            pendingProjectionRequestId == null
        renderPairing(snapshot.pairing)
        renderControllers(snapshot)
        val pairing = snapshot.pairing
        val pairingId = pairing?.pairingId
        if (pairingId != null && pairing.sas != null && !isFinishing) {
            if (pairingId != displayedPairingId) {
                pairingDialog?.dismiss()
                displayedPairingId = pairingId
                pairingDialog = AlertDialog.Builder(this)
                    .setTitle("确认控制端")
                    .setMessage("控制端：${pairing.controllerName}\n\n请确认控制端与电视均显示：${pairing.sas}")
                    .setCancelable(false)
                    .setPositiveButton("一致，允许") { _, _ -> AgentService.confirmPairing(this, pairingId, true) }
                    .setNegativeButton("拒绝") { _, _ -> AgentService.confirmPairing(this, pairingId, false) }
                    .create()
                    .also { it.show() }
            }
        } else if (displayedPairingId != null) {
            // SAS 结束/过期或被新窗口取代：关闭遗留弹窗，避免一直挡住界面。
            pairingDialog?.dismiss()
            pairingDialog = null
            displayedPairingId = null
        }
        if (snapshot.mediaState == AgentMediaState.PERMISSION_REQUIRED &&
            snapshot.mediaRequestId != displayedMediaRequestId && pendingProjectionRequestId == null && !isFinishing
        ) {
            displayedMediaRequestId = snapshot.mediaRequestId
            showMediaConfirmation(snapshot.mediaRequestId)
        }
    }

    private fun applyStatusPill(state: AgentNetworkState) {
        val color = when (state) {
            AgentNetworkState.CONNECTED -> getColorCompat(R.color.tvrc_primary)
            AgentNetworkState.LISTENING -> getColorCompat(R.color.tvrc_success)
            AgentNetworkState.STARTING -> getColorCompat(R.color.tvrc_warning)
            AgentNetworkState.FAILED -> getColorCompat(R.color.tvrc_error)
            AgentNetworkState.STOPPED -> getColorCompat(R.color.tvrc_muted)
        }
        binding.statusPill.background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(2, Color.argb(140, Color.red(color), Color.green(color), Color.blue(color)))
        }
        binding.statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    @Suppress("DEPRECATION")
    private fun getColorCompat(resId: Int): Int =
        if (Build.VERSION.SDK_INT >= 23) resources.getColor(resId, theme) else resources.getColor(resId)

    private fun renderControllers(snapshot: AgentUiSnapshot) {
        binding.controllersTitle.visibility =
            if (snapshot.pairedControllers.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.controllersContainer.removeAllViews()
        snapshot.pairedControllers.forEach { controller ->
            val row = ItemControllerBinding.inflate(layoutInflater, binding.controllersContainer, false)
            row.revokeButton.text = getString(R.string.revoke_controller, controller.controllerName)
            row.revokeButton.setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage("撤销后，该控制端必须重新配对。")
                    .setPositiveButton("撤销") { _, _ ->
                        AgentService.revokeController(this, controller.controllerId)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            binding.controllersContainer.addView(row.root)
        }
    }

    /** Address and IME readiness change outside the status registry, so refresh them on resume. */
    private fun updateDeviceFields() {
        val address = primaryLanAddress()?.hostAddress?.let { "手机连接地址：$it:$DISCOVERY_CONTROL_PORT" }
            ?: "未找到可用局域网 IPv4 地址"
        binding.deviceText.text =
            "${Build.MODEL} · Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} ｜ $address"
        binding.imeStatusText.text = when {
            RemoteInputMethodService.session.ticket() != null -> "输入框已就绪，可以从手机发送文字"
            isOurImeSelected() -> "已选择输入法；请先打开目标 App 的输入框"
            else -> "尚未选择手机遥控输入法"
        }
    }

    /** 配对区三态：二维码 / 大号数字码 / 启动或未开启提示，保证屏幕上始终有可读内容。 */
    private fun renderPairing(pairing: PairingUiState?) {
        val nowMs = System.nanoTime() / 1_000_000L
        val host = primaryLanAddress()?.hostAddress
        val tokenLive = pairing != null && pairing.sas == null && pairing.qrToken != null &&
            nowMs < pairing.qrTokenExpiresAtMs
        val qrValid = tokenLive && host != null
        // 自动续开期间不显示手动按钮，保持界面聚焦；无窗口时才提供手动开启入口。
        binding.openPairingButton.visibility =
            if (pairing == null) android.view.View.VISIBLE else android.view.View.GONE
        // 展示配对码/二维码时保持屏幕常亮，避免电视屏保盖住导致"看不到二维码"。
        if (pairing != null) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        when {
            pairing == null -> {
                binding.pairingText.text = when (AgentStatusRegistry.snapshot().networkState) {
                    AgentNetworkState.STARTING -> "正在启动服务，请稍候…"
                    AgentNetworkState.STOPPED -> "服务未启动，点击下方按钮启动"
                    AgentNetworkState.FAILED -> "服务启动失败，请重试"
                    else -> "配对未开启或已过期"
                }
                showQr(null)
            }
            pairing.sas != null -> {
                binding.pairingText.text = "请在手机与本机核对以下安全码"
                binding.pairingCodeText.text = pairing.sas
                binding.pairingCodeText.visibility = android.view.View.VISIBLE
                showQr(null)
            }
            qrValid -> {
                binding.pairingText.text = "用手机扫描二维码，或输入配对码：${pairing.code}"
                binding.pairingCodeText.visibility = android.view.View.GONE
                showQr(pairing)
            }
            else -> {
                // 窗口有效但本机地址暂时取不到：先显示数字码，1s 后重试出二维码。
                binding.pairingText.text =
                    if (tokenLive) "正在获取本机地址…" else "请在手机上输入以下配对码"
                binding.pairingCodeText.text = pairing.code
                binding.pairingCodeText.visibility = android.view.View.VISIBLE
                showQr(null)
                if (tokenLive) {
                    binding.qrView.removeCallbacks(hostRetry)
                    binding.qrView.postDelayed(hostRetry, 1_000L)
                }
            }
        }
    }

    private fun showQr(pairing: PairingUiState?) {
        if (pairing == null) {
            binding.qrCard.visibility = android.view.View.GONE
            binding.qrView.setImageDrawable(null)
            return
        }
        binding.qrCard.visibility = android.view.View.VISIBLE
        renderQr(pairing)
    }

    /** 渲染扫码配对二维码：tvrc://pair?host&port&token&ttl；token 过期后回落到数字配对码。 */
    private fun renderQr(pairing: PairingUiState) {
        val nowMs = System.nanoTime() / 1_000_000L
        val host = primaryLanAddress()?.hostAddress
        val ttl = maxOf(1L, (pairing.qrTokenExpiresAtMs - nowMs) / 1000L)
        val content = "tvrc://pair?host=$host&port=$DISCOVERY_CONTROL_PORT&token=${pairing.qrToken}&ttl=$ttl"
        val size = 512
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 1)
        }
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.RGB_565)
        binding.qrView.setImageBitmap(bitmap)
        // 二维码与配对窗口同寿：到期即窗口到期，服务会续开新窗口，此处仅按当前状态重绘一次。
        binding.qrView.postDelayed(
            { renderPairing(AgentStatusRegistry.snapshot().pairing) },
            pairing.qrTokenExpiresAtMs - nowMs,
        )
    }

    /** 枚举网络接口取第一个站点内网 IPv4（与 DiscoveryServer 的内网判定一致）。 */
    private fun primaryLanAddress(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { address ->
                    !address.isLoopbackAddress && address is Inet4Address && address.isSiteLocalAddress
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun isOurImeSelected(): Boolean = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.startsWith("$packageName/") == true

    private fun refreshReport() {
        binding.reportText.text = CapabilityDetector.detect(this, profile).toJson()
    }

    /**
     * 调试凭据导出（BuildConfig.DEBUG gate）：展示已配对控制端的 controllerId/PSK，
     * 供小程序等调试客户端手动录入。PSK 等价完整控制权，仅限开发者自有设备。
     */
    private fun showDebugCredentials() {
        val store = KeystoreCredentialStore(applicationContext)
        val text = DebugCredentialExporter.format(DebugCredentialExporter.export(store))
        val copyText = DebugCredentialExporter.export(store).joinToString("\n\n") {
            "controllerId=${it.controllerId}\nPSK=${it.pskHex}"
        }
        AlertDialog.Builder(this)
            .setTitle("调试凭据（开发环境）")
            .setMessage(text + "\n\n仅用于自有设备的开发调试，请勿外传。")
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("tvrc-debug-credential", copyText))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showMediaConfirmation(attachmentId: Long) {
        if (attachmentId <= 0L || isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("允许屏幕共享？")
            .setMessage("已认证的控制端请求查看电视画面。只有在你继续并通过系统授权后，采集才会开始。受 DRM/HDCP 保护的内容可能黑屏，本应用不会检测或绕过保护机制。")
            .setCancelable(false)
            .setPositiveButton("继续") { _, _ -> requestProjectionPermission(attachmentId) }
            .setNegativeButton("拒绝") { _, _ -> AgentService.rejectMedia(this, attachmentId) }
            .show()
    }

    private fun requestProjectionPermission(attachmentId: Long) {
        if (Build.VERSION.SDK_INT < 21 || !isCurrentMediaRequest(attachmentId)) return
        pendingProjectionRequestId = attachmentId
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        launchProjectionPermission(attachmentId)
    }

    @SuppressLint("NewApi")
    private fun launchProjectionPermission(attachmentId: Long) {
        if (!isCurrentMediaRequest(attachmentId)) {
            pendingProjectionRequestId = null
            renderStatus(AgentStatusRegistry.snapshot())
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Deprecated("Uses the API 19-compatible activity result flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        val attachmentId = pendingProjectionRequestId
        pendingProjectionRequestId = null
        if (attachmentId == null || !isCurrentMediaRequest(attachmentId)) {
            renderStatus(AgentStatusRegistry.snapshot())
            return
        }
        val accepted = resultCode == RESULT_OK && data != null
        val started = if (accepted) {
            val captureAudio = Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            runCatching { ProjectionService.startAuthorized(this, data!!, captureAudio, attachmentId) }.isSuccess
        } else {
            false
        }
        if (!started) AgentService.rejectMedia(this, attachmentId)
        AlertDialog.Builder(this)
            .setMessage(if (started) "系统已授予本次屏幕共享权限。" else "屏幕采集未启动。")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO && Build.VERSION.SDK_INT >= 21) {
            val attachmentId = pendingProjectionRequestId ?: return
            launchProjectionPermission(attachmentId)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingProjectionRequestId?.let { outState.putLong(STATE_PROJECTION_REQUEST_ID, it) }
        displayedPairingId?.let { outState.putString(STATE_PAIRING_ID, it) }
        if (displayedMediaRequestId > 0L) outState.putLong(STATE_MEDIA_REQUEST_ID, displayedMediaRequestId)
        super.onSaveInstanceState(outState)
    }

    private fun isCurrentMediaRequest(attachmentId: Long): Boolean = AgentStatusRegistry.snapshot().let {
        it.mediaState == AgentMediaState.PERMISSION_REQUIRED && it.mediaRequestId == attachmentId
    }

    private fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (error: RuntimeException) {
            AlertDialog.Builder(this).setMessage("操作未完成（${error.javaClass.simpleName}），请检查电视设置。")
                .setPositiveButton("知道了", null).show()
        }
    }

    companion object {
        private const val REQUEST_PROJECTION = 100
        private const val REQUEST_AUDIO = 101
        private const val REQUEST_NOTIFICATIONS = 102
        private const val STATE_PROJECTION_REQUEST_ID = "projection_request_id"
        private const val STATE_PAIRING_ID = "displayed_pairing_id"
        private const val STATE_MEDIA_REQUEST_ID = "displayed_media_request_id"
    }
}
