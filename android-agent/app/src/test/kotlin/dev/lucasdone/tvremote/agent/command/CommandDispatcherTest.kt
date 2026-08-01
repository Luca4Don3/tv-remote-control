package dev.lucasdone.tvremote.agent.command

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandDispatcherTest {
    @Test
    fun routesSupportedActionAndRejectsUnknownAction() {
        val executor = RecordingExecutor()
        val dispatcher = CommandDispatcher(KeyStateTracker(), listOf(executor))

        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(1, LogicalKey.BACK)).status)
        assertEquals(AckStatus.UNSUPPORTED, dispatcher.dispatch(command(2, LogicalKey.DPAD_UP)).status)
        assertEquals(listOf(LogicalKey.BACK), executor.executed)
    }

    @Test
    fun failedExecutionDoesNotLeaveKeyPressed() {
        val failing = object : CommandExecutor {
            override fun supports(key: LogicalKey) = key == LogicalKey.BACK
            override fun execute(command: KeyEventCommand) = AckStatus.EXECUTION_FAILED
        }
        val recording = RecordingExecutor()
        val tracker = KeyStateTracker()
        val dispatcher = CommandDispatcher(tracker, listOf(failing, recording))

        assertEquals(AckStatus.EXECUTION_FAILED, dispatcher.dispatch(command(1, LogicalKey.BACK, KeyState.DOWN)).status)
        assertEquals(emptySet<LogicalKey>(), tracker.releaseAll())
    }

    private fun command(sequence: Long, key: LogicalKey, state: KeyState = KeyState.PRESS) = KeyEventCommand(
        sequence = sequence,
        key = key,
        state = state,
    )

    private class RecordingExecutor : CommandExecutor {
        val executed = mutableListOf<LogicalKey>()
        override fun supports(key: LogicalKey): Boolean = key == LogicalKey.BACK
        override fun execute(command: KeyEventCommand): AckStatus {
            executed += command.key
            return AckStatus.SUCCESS
        }
    }
}
