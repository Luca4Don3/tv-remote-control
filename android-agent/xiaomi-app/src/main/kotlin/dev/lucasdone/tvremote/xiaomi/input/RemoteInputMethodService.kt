package dev.lucasdone.tvremote.xiaomi.input

import android.inputmethodservice.InputMethodService
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class RemoteInputMethodService : InputMethodService() {
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        session.detach()
        val target = currentInputConnection ?: return
        session.attach { text -> target.commitText(text, 1) }
    }
    override fun onFinishInput() { session.detach(); super.onFinishInput() }
    override fun onUnbindInput() { session.detach(); super.onUnbindInput() }
    override fun onDestroy() { session.detach(); super.onDestroy() }
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(24, 16, 24, 16)
        addView(TextView(this@RemoteInputMethodService).apply { text = "在手机输入文字后点击发送"; textSize = 18f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(this@RemoteInputMethodService).apply {
            text = "切换输入法"
            setOnClickListener { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        })
    }
    companion object { val session = RemoteTextSession() }
}
