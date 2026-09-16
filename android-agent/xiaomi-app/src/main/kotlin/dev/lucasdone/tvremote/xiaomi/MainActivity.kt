package dev.lucasdone.tvremote.xiaomi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.lucasdone.tvremote.agent.service.AgentStatusRegistry
import dev.lucasdone.tvremote.agent.service.AgentNetworkState
import dev.lucasdone.tvremote.agent.transport.DiscoveryServer
import dev.lucasdone.tvremote.xiaomi.input.RemoteInputMethodService
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var backend: TextView
    private lateinit var inputStatus: TextView
    private lateinit var address: TextView
    private lateinit var pairing: TextView
    private lateinit var result: TextView
    private lateinit var qr: ImageView
    private lateinit var controllers: LinearLayout
    private var renderedControllers = ""
    private var qrContent: String? = null
    private var pairingDialog: AlertDialog? = null
    private var dialogPairingId: String? = null
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(content())
    }
    override fun onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh) }
    override fun onPause() {
        handler.removeCallbacks(refresh)
        pairingDialog?.dismiss(); pairingDialog = null; dialogPairingId = null
        super.onPause()
    }
    private fun content(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 28, 40, 28) }
        page.addView(label(getString(R.string.app_name), 30f))
        page.addView(label("${Build.MODEL} · Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}", 15f))
        page.addView(label("首次使用：启动服务 → 手机配对 → 核对按键效果", 18f))
        status = label(""); page.addView(status)
        address = label("", 15f); page.addView(address)
        backend = label("", 17f); page.addView(backend)
        result = label("", 15f); page.addView(result)
        val row = LinearLayout(this)
        row.addView(button("启动 / 重新检测") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            action(XiaomiAgentService.REFRESH)
        }, weighted())
        row.addView(button("停止服务") { action(XiaomiAgentService.STOP) }, weighted())
        row.addView(button("断开手机") { action(XiaomiAgentService.DISCONNECT) }, weighted())
        page.addView(row)
        page.addView(label("手机配对", 23f))
        page.addView(button("开启 / 刷新配对二维码") { action(XiaomiAgentService.PAIR) })
        pairing = label("请先启动服务，再开启配对。", 23f); page.addView(pairing)
        qr = ImageView(this).apply { contentDescription = "手机配对二维码" }
        page.addView(qr, LinearLayout.LayoutParams(280, 280).apply { gravity = Gravity.CENTER_HORIZONTAL })
        controllers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; page.addView(controllers)
        page.addView(label("文字输入", 23f))
        inputStatus = label("", 17f); page.addView(inputStatus)
        page.addView(label("在手机打字后发送。电视需选中“手机遥控输入法”，并把焦点移到搜索框。", 16f))
        val inputRow = LinearLayout(this)
        inputRow.addView(button("启用输入法") { openSettings(Settings.ACTION_INPUT_METHOD_SETTINGS) }, weighted())
        inputRow.addView(button("选择输入法") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }, weighted())
        page.addView(inputRow)
        page.addView(label("控制设置", 23f))
        val preferences = TvPreferences(this)
        val adb = CheckBox(this).apply {
            text = "允许本机 ADB 备用（默认关闭）"
            isChecked = preferences.adbAllowed
        }
        adb.setOnClickListener {
            if (!adb.isChecked) {
                attempt { preferences.adbAllowed = false; action(XiaomiAgentService.REFRESH) }
            } else {
                adb.isChecked = false
                AlertDialog.Builder(this).setTitle("启用 ADB 备用？")
                    .setMessage("仅在本机遥控接口不可用时使用。需要你在电视系统中开启调试，并用实体遥控器批准授权。ADB 是开发调试权限；部分旧固件需电脑辅助，重启后可能需要重新开启。应用仅连接本机并发送固定遥控键。")
                    .setPositiveButton("允许备用") { _, _ -> attempt {
                        preferences.adbAllowed = true; adb.isChecked = true; action(XiaomiAgentService.REFRESH)
                    } }
                    .setNegativeButton("取消", null).show()
            }
        }
        page.addView(adb)
        page.addView(button("打开系统设置") { openSettings(Settings.ACTION_SETTINGS) })
        page.addView(button("测试音量加一档") { action(XiaomiAgentService.TEST_VOLUME) })
        page.addView(CheckBox(this).apply {
            text = "开机启动遥控服务"; isChecked = preferences.startAtBoot
            setOnClickListener { attempt { preferences.startAtBoot = isChecked } }
        })
        page.addView(label("设备适配状态：待实机验证。接口可连接不代表所有 App 的按键均可用。", 15f))
        return ScrollView(this).apply { addView(page) }
    }
    private fun render() {
        val snapshot = AgentStatusRegistry.snapshot()
        status.text = snapshot.statusText
        backend.text = XiaomiAgentService.backendDescription
        result.text = if (TvPreferences(this).startupFailure) "上次开机启动失败，请手动启动服务。" else XiaomiAgentService.lastAction
        val host = localAddress()
        address.text = if (host == null) "未找到可用局域网 IPv4 地址" else "手机连接地址：$host:${DiscoveryServer.CONTROL_PORT}"
        inputStatus.text = when {
            RemoteInputMethodService.session.ticket() != null -> "输入框已就绪，可以从手机发送文字"
            isOurImeSelected() -> "已选择输入法；请先打开目标 App 的输入框"
            else -> "尚未选择手机遥控输入法"
        }
        val state = snapshot.pairing
        val now = System.nanoTime() / 1_000_000L
        val valid = state != null && now < state.expiresAtMs
        pairing.text = when {
            !valid -> "配对未开启或已过期"
            state?.sas != null -> "安全核对码：${state.sas}"
            else -> "配对码：${state?.code}（${((state!!.expiresAtMs - now) / 1000).coerceAtLeast(0)} 秒）"
        }
        val content = if (valid && state?.sas == null && state?.qrToken != null && host != null && now < state.qrTokenExpiresAtMs)
            "tvrc://pair?host=$host&port=${DiscoveryServer.CONTROL_PORT}&token=${state.qrToken}&ttl=${((state.qrTokenExpiresAtMs - now) / 1000).coerceAtLeast(1)}" else null
        // TTL is advisory; keep one image per token/address and let the TV enforce expiry.
        val identity = content?.substringBefore("&ttl=")
        if (identity != qrContent) {
            qrContent = identity
            if (content == null) { qr.setImageDrawable(null); qr.visibility = View.GONE }
            else {
                val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 384, 384)
                val pixels = IntArray(384 * 384) { index -> if (matrix[index % 384, index / 384]) Color.BLACK else Color.WHITE }
                qr.setImageBitmap(Bitmap.createBitmap(pixels, 384, 384, Bitmap.Config.RGB_565)); qr.visibility = View.VISIBLE
            }
        }
        if (valid && state?.pairingId != null && state.sas != null && dialogPairingId != state.pairingId) {
            pairingDialog?.dismiss()
            dialogPairingId = state.pairingId
            pairingDialog = AlertDialog.Builder(this).setTitle("确认手机配对")
                .setMessage("${state.controllerName}\n请核对手机也显示：${state.sas}")
                .setCancelable(false)
                .setPositiveButton("一致，允许") { _, _ -> confirm(state.pairingId, true) }
                .setNegativeButton("拒绝") { _, _ -> confirm(state.pairingId, false) }.show()
        } else if (!valid || state?.sas == null) {
            pairingDialog?.dismiss(); pairingDialog = null; dialogPairingId = null
        }
        val controllerKey = snapshot.pairedControllers.joinToString { it.controllerId + it.controllerName }
        if (renderedControllers != controllerKey) {
            renderedControllers = controllerKey
            controllers.removeAllViews()
            snapshot.pairedControllers.forEach { controller ->
                controllers.addView(button("撤销手机：${controller.controllerName}") {
                    AlertDialog.Builder(this).setMessage("撤销后，该手机必须重新配对。")
                        .setPositiveButton("撤销") { _, _ -> action(XiaomiAgentService.REVOKE) { putExtra("controllerId", controller.controllerId) } }
                        .setNegativeButton("取消", null).show()
                })
            }
        }
        if (snapshot.networkState == AgentNetworkState.CONNECTED || valid) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun confirm(id: String, accepted: Boolean) = action(XiaomiAgentService.CONFIRM) {
        putExtra("pairingId", id); putExtra("accepted", accepted)
    }
    private fun isOurImeSelected(): Boolean = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.startsWith("$packageName/") == true
    private fun action(name: String, extras: Intent.() -> Unit = {}) = attempt {
        XiaomiAgentService.start(this, name) { it.extras() }
    }
    private fun openSettings(name: String) = attempt { startActivity(Intent(name)) }
    private fun attempt(action: () -> Unit) {
        try { action() } catch (error: RuntimeException) {
            AlertDialog.Builder(this).setMessage("操作未完成（${error.javaClass.simpleName}），请检查电视设置。")
                .setPositiveButton("知道了", null).show()
        }
    }
    private fun label(value: String, size: Float = 19f) = TextView(this).apply {
        text = value; textSize = size; setPadding(0, 8, 0, 8)
    }
    private fun button(value: String, click: () -> Unit) = Button(this).apply { text = value; setOnClickListener { click() } }
    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: java.net.SocketException) { null }
}
