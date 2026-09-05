package dev.lucasdone.tvremote.agent.command

import android.os.Build
import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.TextCommand
import dev.lucasdone.tvremote.agent.service.TvAccessibilityService

/**
 * 经 AccessibilityService 注入文本。ACTION_SET_TEXT 要求 API 21+，
 * 低版本与无服务在线时返回 UNSUPPORTED（能力协商会提前拦住大部分场景）。
 */
class AccessibilityTextCommandExecutor : TextCommandExecutor {
    override fun supportsText(): Boolean =
        Build.VERSION.SDK_INT >= 21 && TvAccessibilityService.active != null

    override fun executeText(command: TextCommand): AckStatus {
        if (Build.VERSION.SDK_INT < 21) return AckStatus.UNSUPPORTED
        val service = TvAccessibilityService.active ?: return AckStatus.PERMISSION_DENIED
        if (command.text.length > MAX_TEXT_LENGTH) return AckStatus.REJECTED
        return if (service.performText(command)) AckStatus.SUCCESS
        else AckStatus.EXECUTION_FAILED
    }

    companion object {
        const val MAX_TEXT_LENGTH = 4 * 1024
    }
}
