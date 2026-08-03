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

    @Test
    fun failedExecutionDoesNotConsumeSequence() {
        var fail = true
        val flaky = object : CommandExecutor {
            override fun supports(key: LogicalKey) = key == LogicalKey.BACK
            override fun execute(command: KeyEventCommand) =
                if (fail) {
                    fail = false
                    AckStatus.EXECUTION_FAILED
                } else {
                    AckStatus.SUCCESS
                }
        }
        val tracker = KeyStateTracker()
        val dispatcher = CommandDispatcher(tracker, listOf(flaky))

        // executor 失败：不消耗序列号，按键未加入 pressed。
        assertEquals(AckStatus.EXECUTION_FAILED, dispatcher.dispatch(command(1, LogicalKey.BACK, KeyState.DOWN)).status)
        assertEquals(emptySet<LogicalKey>(), tracker.releaseAll())
        // 相同序列号可重放，成功后推进并记录按下状态。
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(1, LogicalKey.BACK, KeyState.DOWN)).status)
        assertEquals(setOf(LogicalKey.BACK), tracker.releaseAll())
        // 已推进序列号后重放被拒。
        assertEquals(AckStatus.REJECTED, dispatcher.dispatch(command(1, LogicalKey.BACK, KeyState.DOWN)).status)
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
