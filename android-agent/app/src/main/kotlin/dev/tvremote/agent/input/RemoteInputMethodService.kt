package dev.tvremote.agent.input

import android.inputmethodservice.InputMethodService
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import dev.tvremote.agent.R

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
    override fun onCreateInputView(): View =
        LayoutInflater.from(this).inflate(R.layout.ime_input_view, null).apply {
            findViewById<View>(R.id.switchImeButton).setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
    companion object { val session = RemoteTextSession() }
}
