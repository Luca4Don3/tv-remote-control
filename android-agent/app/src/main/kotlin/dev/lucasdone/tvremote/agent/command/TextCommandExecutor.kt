package dev.lucasdone.tvremote.agent.command

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.CommandAck
import dev.lucasdone.tvremote.agent.model.TextCommand

interface TextCommandExecutor {
    fun supportsText(): Boolean
    fun executeText(command: TextCommand): AckStatus
}

/**
 * 文本命令分发：仅要求 sequence 严格递增（不消耗失败序号，与 KeyEventCommand 语义一致），
 * 无按键状态机——文本注入是幂等覆盖操作。
 */
class TextCommandDispatcher(
    private val executors: List<TextCommandExecutor>,
) {
    @Volatile
    private var lastAcceptedSequence: Long = 0L

    @Synchronized
    fun dispatch(command: TextCommand): CommandAck {
        if (command.sequence <= lastAcceptedSequence) {
            return CommandAck(command.sequence, AckStatus.REJECTED, "sequence is not increasing")
        }
        val executor = executors.firstOrNull { it.supportsText() }
            ?: return CommandAck(command.sequence, AckStatus.UNSUPPORTED, "no executor supports text input")
        val status = try {
            executor.executeText(command)
        } catch (_: SecurityException) {
            AckStatus.PERMISSION_DENIED
        } catch (_: RuntimeException) {
            AckStatus.EXECUTION_FAILED
        }
        return when (status) {
            AckStatus.SUCCESS -> {
                lastAcceptedSequence = command.sequence
                CommandAck(command.sequence, AckStatus.SUCCESS)
            }
            AckStatus.UNSUPPORTED -> CommandAck(command.sequence, AckStatus.UNSUPPORTED)
            AckStatus.PERMISSION_DENIED -> CommandAck(command.sequence, AckStatus.PERMISSION_DENIED, "executor permission denied")
            else -> CommandAck(command.sequence, AckStatus.EXECUTION_FAILED, "executor failed")
        }
    }
}
