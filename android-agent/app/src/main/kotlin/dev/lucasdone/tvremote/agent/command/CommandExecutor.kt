package dev.lucasdone.tvremote.agent.command

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.CommandAck
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.LogicalKey

interface CommandExecutor {
    fun supports(key: LogicalKey): Boolean
    fun execute(command: KeyEventCommand): AckStatus
    fun release(key: LogicalKey): AckStatus = AckStatus.SUCCESS
}

class CommandDispatcher(
    private val tracker: KeyStateTracker,
    private val executors: List<CommandExecutor>,
) {
    @Synchronized
    fun dispatch(command: KeyEventCommand): CommandAck {
        val stateAck = tracker.accept(command)
        if (stateAck.status != AckStatus.SUCCESS) return stateAck

        val executor = executors.firstOrNull { it.supports(command.key) }
            ?: return CommandAck(command.sequence, AckStatus.UNSUPPORTED, "no executor supports this action")
        return try {
            val status = executor.execute(command)
            CommandAck(command.sequence, status, if (status == AckStatus.SUCCESS) null else "executor rejected action")
        } catch (_: SecurityException) {
            CommandAck(command.sequence, AckStatus.PERMISSION_DENIED, "executor permission denied")
        } catch (_: RuntimeException) {
            CommandAck(command.sequence, AckStatus.EXECUTION_FAILED, "executor failed")
        }
    }

    @Synchronized
    fun disconnect(): Map<LogicalKey, AckStatus> = tracker.releaseAll().associateWith { key ->
        executors.firstOrNull { it.supports(key) }?.release(key) ?: AckStatus.UNSUPPORTED
    }
}
