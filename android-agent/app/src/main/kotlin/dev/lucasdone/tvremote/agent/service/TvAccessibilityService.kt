package dev.lucasdone.tvremote.agent.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.model.TextAction
import dev.lucasdone.tvremote.agent.model.TextCommand

class TvAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    fun perform(key: LogicalKey): Boolean = when (key) {
        LogicalKey.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        LogicalKey.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        LogicalKey.DPAD_CENTER -> performCenter()
        LogicalKey.DPAD_UP -> performDirection(View.FOCUS_UP)
        LogicalKey.DPAD_DOWN -> performDirection(View.FOCUS_DOWN)
        LogicalKey.DPAD_LEFT -> performDirection(View.FOCUS_LEFT)
        LogicalKey.DPAD_RIGHT -> performDirection(View.FOCUS_RIGHT)
        else -> false
    }

    private fun performCenter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        return try {
            focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            recycle(focused, root)
        }
    }

    private fun performDirection(direction: Int): Boolean {
        if (Build.VERSION.SDK_INT < 22) return false
        return performDirectionApi22(direction)
    }

    private fun performDirectionApi22(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        val target = focused.focusSearch(direction) ?: run {
            recycle(focused, root)
            return false
        }
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        } finally {
            recycle(target, focused, root)
        }
    }

    /**
     * 向当前输入焦点节点提交文本。ACTION_SET_TEXT 要求 API 21+。
     * COMMIT/DRAFT 首版同语义（整段覆盖写回焦点节点）；DRAFT 预留给
     * 后续 IME commitContent 流式草稿。
     */
    fun performText(command: TextCommand): Boolean {
        if (Build.VERSION.SDK_INT < 21) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: run { recycle(root); return false }
        val target = sequenceOf(focused)
            .plus(generateSequence(focused) { it.parent }.take(4).toList())
            .firstOrNull { node -> node.isEditable } ?: run {
            recycle(focused, root)
            return false
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.text)
        }
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) &&
                command.action == TextAction.COMMIT
        } finally {
            recycle(target, focused, root)
        }
    }

    // API 33+ 的 recycle() 已弃用；clear() 为系统隐藏 API 不可用，改为交由 GC 回收。
    private fun recycle(vararg nodes: AccessibilityNodeInfo) {
        nodes.distinctBy(System::identityHashCode).forEach { node ->
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
    }

    companion object {
        @Volatile
        var active: TvAccessibilityService? = null
            private set
    }
}
