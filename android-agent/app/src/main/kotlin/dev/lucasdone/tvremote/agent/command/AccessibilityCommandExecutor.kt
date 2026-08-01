package dev.lucasdone.tvremote.agent.command

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.service.TvAccessibilityService

/** 无障碍执行结果：Success=执行成功，Failed=执行失败，Unavailable=服务未连接。 */
sealed class ActionResult {
    object Success : ActionResult()
    object Failed : ActionResult()
    object Unavailable : ActionResult()
}

class AccessibilityCommandExecutor(
    private val performAction: (LogicalKey) -> ActionResult = { key ->
        when (val result = TvAccessibilityService.active?.perform(key)) {
            null -> ActionResult.Unavailable
            true -> ActionResult.Success
            false -> ActionResult.Failed
        }
    },
) : CommandExecutor {
    override fun supports(key: LogicalKey): Boolean = key in supportedKeys

    override fun execute(command: KeyEventCommand): AckStatus = when {
        command.key == LogicalKey.BACK || command.key == LogicalKey.HOME -> {
            if (command.state != KeyState.PRESS) AckStatus.UNSUPPORTED else perform(command.key)
        }
        command.state == KeyState.UP -> AckStatus.SUCCESS
        command.state == KeyState.PRESS || command.state == KeyState.DOWN || command.state == KeyState.REPEAT -> {
            perform(command.key)
        }
        else -> AckStatus.UNSUPPORTED
    }

    private fun perform(key: LogicalKey): AckStatus = when (performAction(key)) {
        ActionResult.Unavailable -> AckStatus.PERMISSION_DENIED
        ActionResult.Success -> AckStatus.SUCCESS
        ActionResult.Failed -> AckStatus.EXECUTION_FAILED
    }

    companion object {
        private val supportedKeys = setOf(
            LogicalKey.BACK,
            LogicalKey.HOME,
            LogicalKey.DPAD_UP,
            LogicalKey.DPAD_DOWN,
            LogicalKey.DPAD_LEFT,
            LogicalKey.DPAD_RIGHT,
            LogicalKey.DPAD_CENTER,
        )
    }
}
