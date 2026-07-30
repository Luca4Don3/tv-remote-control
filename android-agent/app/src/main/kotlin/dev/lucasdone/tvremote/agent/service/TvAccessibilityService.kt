package dev.lucasdone.tvremote.agent.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.lucasdone.tvremote.agent.model.LogicalKey

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
        else -> false
    }

    companion object {
        @Volatile
        var active: TvAccessibilityService? = null
            private set
    }
}
