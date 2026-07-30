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
        if (command.sequence <= lastSequence) {
            return CommandAck(command.sequence, AckStatus.REJECTED, "sequence is not increasing")
        }
        if (command.repeatCount < 0) {
            return CommandAck(command.sequence, AckStatus.REJECTED, "repeatCount must be non-negative")
        }
        lastSequence = command.sequence

        val valid = when (command.state) {
            KeyState.PRESS -> command.key !in pressed
            KeyState.DOWN -> pressed.add(command.key)
            KeyState.REPEAT -> command.key in pressed
            KeyState.UP -> pressed.remove(command.key)
        }
        if (!valid) {
            return CommandAck(command.sequence, AckStatus.REJECTED, "invalid key state transition")
        }

        return CommandAck(command.sequence, AckStatus.SUCCESS)
    }

    @Synchronized
    fun releaseAll(): Set<LogicalKey> {
        val released = pressed.toSet()
        pressed.clear()
        return released
    }
}
