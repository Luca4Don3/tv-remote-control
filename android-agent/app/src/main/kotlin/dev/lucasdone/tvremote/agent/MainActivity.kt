package dev.lucasdone.tvremote.agent

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.TextView
import dev.lucasdone.tvremote.agent.device.CapabilityDetector
import dev.lucasdone.tvremote.agent.service.AgentService

class MainActivity : Activity() {
    private lateinit var reportView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        refreshReport()
    }

    override fun onResume() {
        super.onResume()
        if (::reportView.isInitialized) refreshReport()
    }

    private fun buildContent(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 32, 32, 32)
        }
        reportView = TextView(this).apply { setTextIsSelectable(true) }

        content.addView(Button(this).apply {
            text = "启动基础遥控服务"
            setOnClickListener { AgentService.start(this@MainActivity) }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "验证屏幕采集授权"
            isEnabled = Build.VERSION.SDK_INT >= 21
            setOnClickListener { requestProjectionPermission() }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "刷新能力报告"
            setOnClickListener { refreshReport() }
        }, matchWidth())
        content.addView(reportView, matchWidth())

        return ScrollView(this).apply { addView(content) }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = 16 }

    private fun refreshReport() {
        reportView.text = CapabilityDetector.detect(this).toJson()
    }

    private fun requestProjectionPermission() {
        if (Build.VERSION.SDK_INT < 21) return
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Deprecated("Uses the API 19-compatible activity result flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        val accepted = resultCode == RESULT_OK && data != null
        AlertDialog.Builder(this)
            .setMessage(if (accepted) "系统已授予本次屏幕采集权限；探针不会开始后台录屏。" else "屏幕采集授权被拒绝。")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val REQUEST_PROJECTION = 100
    }
}
