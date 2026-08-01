package dev.lucasdone.tvremote.agent.command

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.CommandAck
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey

class KeyStateTracker {
    private val pressed = mutableSetOf<LogicalKey>()
    private var lastSequence = -1L

    @Synchronized
    fun accept(command: KeyEventCommand): CommandAck {
        val validation = validate(command)
        if (validation.status == AckStatus.SUCCESS) commit(command)
        return validation
    }

    @Synchronized
    fun validate(command: KeyEventCommand): CommandAck {
        if (command.sequence <= lastSequence) {
            return CommandAck(command.sequence, AckStatus.REJECTED, "sequence is not increasing")
        }
        if (command.repeatCount < 0) {
            return CommandAck(command.sequence, AckStatus.REJECTED, "repeatCount must be non-negative")
        }
        val valid = when (command.state) {
            KeyState.PRESS -> command.key !in pressed
            KeyState.DOWN -> command.key !in pressed
            KeyState.REPEAT -> command.key in pressed
            KeyState.UP -> command.key in pressed
        }
        if (!valid) {
            return CommandAck(command.sequence, AckStatus.REJECTED, "invalid key state transition")
        }
        // 仅在全部校验通过后推进序列号，避免被拒命令造成重放拒绝（DoS）。
        lastSequence = command.sequence

        return CommandAck(command.sequence, AckStatus.SUCCESS)
    }

    @Synchronized
    fun commit(command: KeyEventCommand) {
        when (command.state) {
            KeyState.DOWN -> pressed.add(command.key)
            KeyState.UP -> pressed.remove(command.key)
            KeyState.REPEAT, KeyState.PRESS -> Unit
        }
    }

    @Synchronized
    fun releaseAll(): Set<LogicalKey> {
        val released = pressed.toSet()
        pressed.clear()
        return released
    }

}
