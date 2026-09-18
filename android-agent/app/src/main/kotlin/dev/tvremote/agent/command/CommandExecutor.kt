package dev.tvremote.agent.command

import dev.tvremote.agent.model.AckStatus
import dev.tvremote.agent.model.CommandAck
import dev.tvremote.agent.model.KeyEventCommand
import dev.tvremote.agent.model.KeyState
import dev.tvremote.agent.model.LogicalKey

interface CommandExecutor {
    fun supports(key: LogicalKey): Boolean
    fun execute(command: KeyEventCommand): AckStatus
    fun release(key: LogicalKey): AckStatus = AckStatus.SUCCESS
}

class CommandDispatcher(
    private val tracker: KeyStateTracker,
    private val executors: List<CommandExecutor>,
) {
    // Binds a held key to the executor that handled its DOWN, so REPEAT/UP and disconnect release
    // always reach that same executor even if routing would change mid-press (e.g. a backend swap).
    private val pressExecutors = mutableMapOf<LogicalKey, CommandExecutor>()

    @Synchronized
    fun dispatch(command: KeyEventCommand): CommandAck {
        val stateAck = tracker.validate(command)
        if (stateAck.status != AckStatus.SUCCESS) return stateAck
        val executor = when (command.state) {
            KeyState.UP, KeyState.REPEAT -> pressExecutors[command.key] ?: firstSupporting(command.key)
            KeyState.DOWN, KeyState.PRESS -> firstSupporting(command.key)
        } ?: return CommandAck(command.sequence, AckStatus.UNSUPPORTED, "no executor supports this action")
        val ack = try {
            val status = executor.execute(command)
            CommandAck(command.sequence, status, if (status == AckStatus.SUCCESS) null else "executor rejected action")
        } catch (_: SecurityException) {
            CommandAck(command.sequence, AckStatus.PERMISSION_DENIED, "executor permission denied")
        } catch (_: RuntimeException) {
            CommandAck(command.sequence, AckStatus.EXECUTION_FAILED, "executor failed")
        }
        if (ack.status == AckStatus.SUCCESS) {
            tracker.commit(command)
            when (command.state) {
                KeyState.DOWN -> pressExecutors[command.key] = executor
                KeyState.UP -> pressExecutors.remove(command.key)
                KeyState.REPEAT, KeyState.PRESS -> Unit
            }
        }
        return ack
    }

    @Synchronized
    fun disconnect(): Map<LogicalKey, AckStatus> = tracker.releaseAll().associateWith { key ->
        val executor = pressExecutors.remove(key) ?: firstSupporting(key) ?: return@associateWith AckStatus.UNSUPPORTED
        try {
            executor.release(key)
        } catch (_: SecurityException) {
            AckStatus.PERMISSION_DENIED
        } catch (_: RuntimeException) {
            AckStatus.EXECUTION_FAILED
        }
    }

    private fun firstSupporting(key: LogicalKey): CommandExecutor? = executors.firstOrNull { it.supports(key) }
}
