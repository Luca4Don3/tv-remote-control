package dev.lucasdone.tvremote.agent.command

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.service.TvAccessibilityService

class AccessibilityCommandExecutor : CommandExecutor {
    override fun supports(key: LogicalKey): Boolean = key == LogicalKey.BACK || key == LogicalKey.HOME

    override fun execute(command: KeyEventCommand): AckStatus {
        if (command.state != KeyState.PRESS) return AckStatus.UNSUPPORTED
        val service = TvAccessibilityService.active ?: return AckStatus.PERMISSION_DENIED
        return if (service.perform(command.key)) AckStatus.SUCCESS else AckStatus.EXECUTION_FAILED
    }
}
