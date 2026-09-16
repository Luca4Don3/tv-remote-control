package dev.lucasdone.tvremote.xiaomi.backend

import dev.lucasdone.tvremote.agent.command.CommandExecutor
import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey

enum class BackendKind { NONE, XIAOMI, ADB }

object BackendSelection {
    fun select(xiaomiAvailable: Boolean, adbAllowed: Boolean, adbAvailable: Boolean): BackendKind = when {
        xiaomiAvailable -> BackendKind.XIAOMI
        adbAllowed && adbAvailable -> BackendKind.ADB
        else -> BackendKind.NONE
    }
}

interface KeyBackend : AutoCloseable {
    val kind: BackendKind
    val keys: Set<LogicalKey>
    val available: Boolean
    fun press(key: LogicalKey): AckStatus
    override fun close() = Unit
}

/**
 * One atomic press per DOWN/REPEAT/PRESS. Never queues its own repeat timer or retries. The
 * current backend is resolved on every call, so a backend replaced by a refresh is immediately
 * visible to sessions authenticated before the swap. An unavailable backend reports no support,
 * letting volume/media keys fall through to the system executor instead of failing on a dead
 * vendor channel.
 */
class BackendKeyExecutor(private val backendProvider: () -> KeyBackend?) : CommandExecutor {
    override fun supports(key: LogicalKey): Boolean {
        val backend = backendProvider() ?: return false
        return backend.available && key in backend.keys
    }
    override fun execute(command: KeyEventCommand): AckStatus {
        val backend = backendProvider() ?: return AckStatus.UNSUPPORTED
        if (command.key !in backend.keys) return AckStatus.UNSUPPORTED
        if (command.state == KeyState.UP) return AckStatus.SUCCESS
        if (command.state == KeyState.REPEAT && command.key !in REPEATABLE) return AckStatus.UNSUPPORTED
        if (!backend.available) return AckStatus.EXECUTION_FAILED
        return backend.press(command.key)
    }
    companion object {
        val REPEATABLE = setOf(LogicalKey.DPAD_UP, LogicalKey.DPAD_DOWN, LogicalKey.DPAD_LEFT,
            LogicalKey.DPAD_RIGHT, LogicalKey.VOLUME_UP, LogicalKey.VOLUME_DOWN)
    }
}
