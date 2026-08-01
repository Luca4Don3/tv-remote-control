package dev.lucasdone.tvremote.agent.command

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityCommandExecutorTest {
    @Test
    fun dpadDownAndRepeatActWhileUpOnlyReleasesState() {
        val performed = mutableListOf<LogicalKey>()
        val executor = AccessibilityCommandExecutor { key -> performed.add(key); ActionResult.Success }
        val dispatcher = CommandDispatcher(KeyStateTracker(), listOf(executor))

        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(1, KeyState.DOWN)).status)
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(2, KeyState.REPEAT, repeatCount = 7)).status)
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(3, KeyState.UP, repeatCount = 7)).status)
        assertEquals(listOf(LogicalKey.DPAD_RIGHT, LogicalKey.DPAD_RIGHT), performed)
    }

    @Test
    fun homeAndBackRemainPressOnly() {
        val executor = AccessibilityCommandExecutor { ActionResult.Success }
        assertEquals(AckStatus.SUCCESS, executor.execute(command(1, KeyState.PRESS, key = LogicalKey.HOME)))
        assertEquals(AckStatus.UNSUPPORTED, executor.execute(command(2, KeyState.DOWN, key = LogicalKey.HOME)))
    }

    @Test
    fun failedDownRollsBackTrackerForAKeyWithoutAffectingTheNextAttempt() {
        var attempts = 0
        val executor = AccessibilityCommandExecutor {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("isolated key failure")
            ActionResult.Success
        }
        val dispatcher = CommandDispatcher(KeyStateTracker(), listOf(executor))

        assertEquals(AckStatus.EXECUTION_FAILED, dispatcher.dispatch(command(1, KeyState.DOWN)).status)
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(2, KeyState.DOWN)).status)
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(3, KeyState.UP)).status)
    }

    @Test
    fun reportsMissingPermissionAndFailedFocusMove() {
        assertEquals(
            AckStatus.PERMISSION_DENIED,
            AccessibilityCommandExecutor { ActionResult.Unavailable }.execute(command(1, KeyState.PRESS)),
        )
        assertEquals(
            AckStatus.EXECUTION_FAILED,
            AccessibilityCommandExecutor { ActionResult.Failed }.execute(command(2, KeyState.PRESS)),
        )
    }

    private fun command(
        sequence: Long,
        state: KeyState,
        repeatCount: Int = 0,
        key: LogicalKey = LogicalKey.DPAD_RIGHT,
    ) = KeyEventCommand(sequence, key, state, repeatCount)
}
