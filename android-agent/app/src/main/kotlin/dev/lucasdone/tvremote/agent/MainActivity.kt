package dev.lucasdone.tvremote.agent

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.lucasdone.tvremote.agent.device.CapabilityDetector
import dev.lucasdone.tvremote.agent.transport.DiscoveryServer
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.EnumMap
import dev.lucasdone.tvremote.agent.service.AgentService
import dev.lucasdone.tvremote.agent.service.AgentMediaState
import dev.lucasdone.tvremote.agent.service.AgentStatusRegistry
import dev.lucasdone.tvremote.agent.service.AgentUiSnapshot
import dev.lucasdone.tvremote.agent.service.ProjectionService

class MainActivity : Activity() {
    private lateinit var reportView: TextView
    private lateinit var serviceStatusView: TextView
    private lateinit var mediaStatusView: TextView
    private lateinit var mediaActionButton: Button
    private lateinit var pairingView: TextView
    private lateinit var qrView: ImageView
    private lateinit var controllersView: LinearLayout
    private var displayedPairingId: String? = null
    private val DISCOVERY_CONTROL_PORT = DiscoveryServer.CONTROL_PORT
    private var displayedMediaRequestId = 0L
    private var pendingProjectionRequestId: Long? = null
    private val statusListener = AgentStatusRegistry.Listener { snapshot ->
        runOnUiThread { renderStatus(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProjectionRequestId = savedInstanceState?.getLong(STATE_PROJECTION_REQUEST_ID)
            ?.takeIf { it > 0L }
        // 恢复已展示过的会话级 UI 状态，避免旋转后重复弹配对/媒体授权对话框。
        displayedPairingId = savedInstanceState?.getString(STATE_PAIRING_ID)
        displayedMediaRequestId = savedInstanceState?.getLong(STATE_MEDIA_REQUEST_ID) ?: 0L
        setContentView(buildContent())
        refreshReport()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::reportView.isInitialized) refreshReport()
    }

    override fun onStart() {
        super.onStart()
        AgentStatusRegistry.addListener(statusListener)
    }

    override fun onStop() {
        AgentStatusRegistry.removeListener(statusListener)
        super.onStop()
    }

    private fun buildContent(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 32, 32, 32)
        }
        serviceStatusView = TextView(this).apply { textSize = 18f }
        mediaStatusView = TextView(this).apply { textSize = 18f }
        pairingView = TextView(this).apply { textSize = 24f }
        controllersView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        reportView = TextView(this).apply { setTextIsSelectable(true) }

        content.addView(serviceStatusView, matchWidth())
        content.addView(mediaStatusView, matchWidth())
        content.addView(TextView(this).apply {
            text = "提示：受 DRM/HDCP 保护的内容可能显示黑屏；本应用不会检测或绕过内容保护。"
        }, matchWidth())
        content.addView(pairingView, matchWidth())
        qrView = ImageView(this).apply {
            contentDescription = "扫码配对二维码"
        }
        content.addView(qrView, matchWidth())
        content.addView(controllersView, matchWidth())
        content.addView(Button(this).apply {
            text = "启动基础遥控服务"
            setOnClickListener { AgentService.start(this@MainActivity) }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "开启两分钟配对窗口"
            setOnClickListener { AgentService.openPairing(this@MainActivity) }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, matchWidth())
        mediaActionButton = Button(this).apply {
            text = "响应屏幕共享请求"
            isEnabled = false
            setOnClickListener {
                val snapshot = AgentStatusRegistry.snapshot()
                if (snapshot.mediaState == AgentMediaState.PERMISSION_REQUIRED) {
                    showMediaConfirmation(snapshot.mediaRequestId)
                }
            }
        }
        content.addView(mediaActionButton, matchWidth())
        content.addView(Button(this).apply {
            text = "刷新能力报告"
            setOnClickListener { refreshReport() }
        }, matchWidth())
        content.addView(reportView, matchWidth())

        return ScrollView(this).apply { addView(content) }
    }

    private fun renderStatus(snapshot: AgentUiSnapshot) {
        if (!::serviceStatusView.isInitialized) return
        serviceStatusView.text = snapshot.statusText
        mediaStatusView.text = when (snapshot.mediaState) {
            AgentMediaState.IDLE -> "屏幕共享：空闲"
            AgentMediaState.ATTACHING -> "屏幕共享：等待安全媒体连接"
            AgentMediaState.PERMISSION_REQUIRED -> "屏幕共享：等待电视端授权"
            AgentMediaState.STREAMING -> "屏幕共享：正在传输画面和音频"
            AgentMediaState.STREAMING_VIDEO_ONLY -> "屏幕共享：正在传输画面（无音频）"
            AgentMediaState.FAILED -> "屏幕共享：采集失败"
        }
        mediaActionButton.isEnabled = snapshot.mediaState == AgentMediaState.PERMISSION_REQUIRED &&
            pendingProjectionRequestId == null
        val pairing = snapshot.pairing
        pairingView.text = when {
            pairing == null -> ""
            pairing.sas == null -> {
                "配对码：${pairing.code}\n请仅向当前控制端输入此码，或扫码配对。"
            }
            else -> "安全核对码：${pairing.sas}"
        }
        renderQr(pairing)
        controllersView.removeAllViews()
        if (snapshot.pairedControllers.isNotEmpty()) {
            controllersView.addView(TextView(this).apply { text = "已配对控制端" })
        }
        snapshot.pairedControllers.forEach { controller ->
            controllersView.addView(Button(this).apply {
                text = getString(R.string.revoke_controller, controller.controllerName)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("撤销后，该控制端必须重新配对。")
                        .setPositiveButton("撤销") { _, _ ->
                            AgentService.revokeController(this@MainActivity, controller.controllerId)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }, matchWidth())
        }
        val pairingId = pairing?.pairingId
        if (pairingId != null && pairing.sas != null && pairingId != displayedPairingId && !isFinishing) {
            displayedPairingId = pairingId
            AlertDialog.Builder(this)
                .setTitle("确认控制端")
                .setMessage("控制端：${pairing.controllerName}\n\n请确认控制端与电视均显示：${pairing.sas}")
                .setCancelable(false)
                .setPositiveButton("一致，允许") { _, _ -> AgentService.confirmPairing(this, pairingId, true) }
                .setNegativeButton("拒绝") { _, _ -> AgentService.confirmPairing(this, pairingId, false) }
                .show()
        }
        if (pairing == null) displayedPairingId = null
        if (snapshot.mediaState == AgentMediaState.PERMISSION_REQUIRED &&
            snapshot.mediaRequestId != displayedMediaRequestId && pendingProjectionRequestId == null && !isFinishing
        ) {
            displayedMediaRequestId = snapshot.mediaRequestId
            showMediaConfirmation(snapshot.mediaRequestId)
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = 16 }

    /** 渲染扫码配对二维码：tvrc://pair?host&port&token&ttl；token 过期后自动隐藏。 */
    private fun renderQr(pairing: dev.lucasdone.tvremote.agent.service.PairingUiState?) {
        val nowMs = System.nanoTime() / 1_000_000L
        val token = pairing?.qrToken
        if (token == null || nowMs >= pairing.qrTokenExpiresAtMs) {
            qrView.setImageDrawable(null)
            return
        }
        val host = primaryLanAddress()?.hostAddress
        if (host == null) {
            qrView.setImageDrawable(null)
            return
        }
        val ttl = maxOf(1L, (pairing.qrTokenExpiresAtMs - nowMs) / 1000L)
        val content = "tvrc://pair?host=$host&port=$DISCOVERY_CONTROL_PORT&token=$token&ttl=$ttl"
        val size = 512
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 1)
        }
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.RGB_565)
        qrView.setImageBitmap(bitmap)
    }

    /** 枚举网络接口取第一个站点内网 IPv4（与 DiscoveryServer 的内网判定一致）。 */
    private fun primaryLanAddress(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { address ->
                    !address.isLoopbackAddress && address is java.net.Inet4Address && address.isSiteLocalAddress
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshReport() {
        reportView.text = CapabilityDetector.detect(this).toJson()
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

    companion object {
        private const val REQUEST_PROJECTION = 100
        private const val REQUEST_AUDIO = 101
        private const val REQUEST_NOTIFICATIONS = 102
        private const val STATE_PROJECTION_REQUEST_ID = "projection_request_id"
        private const val STATE_PAIRING_ID = "displayed_pairing_id"
        private const val STATE_MEDIA_REQUEST_ID = "displayed_media_request_id"
    }
}
