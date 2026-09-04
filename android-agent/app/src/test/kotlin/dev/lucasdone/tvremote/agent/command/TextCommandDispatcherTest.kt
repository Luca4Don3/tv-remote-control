package dev.lucasdone.tvremote.agent.command

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.TextAction
import dev.lucasdone.tvremote.agent.model.TextCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCommandDispatcherTest {
    private class FakeExecutor(
        private val supported: Boolean = true,
        private val result: AckStatus = AckStatus.SUCCESS,
        private val resultOverrideOnCall: ((Int) -> AckStatus)? = null,
    ) : TextCommandExecutor {
        var executed: List<TextCommand> = emptyList()
            private set

        var callCount: Int = 0
            private set

        override fun supportsText(): Boolean = supported

        override fun executeText(command: TextCommand): AckStatus {
            executed += command
            callCount += 1
            return resultOverrideOnCall?.invoke(callCount) ?: result
        }
    }

    private fun command(sequence: Long, text: String = "hi") =
        TextCommand(sequence, TextAction.COMMIT, text)

    @Test
    fun successAdvancesSequence() {
        val executor = FakeExecutor()
        val dispatcher = TextCommandDispatcher(listOf(executor))
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(1)).status)
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(2)).status)
        assertEquals(listOf(1L, 2L), executor.executed.map { it.sequence })
    }

    @Test
    fun rejectsNonIncreasingSequence() {
        val executor = FakeExecutor()
        val dispatcher = TextCommandDispatcher(listOf(executor))
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(5)).status)
        val replay = dispatcher.dispatch(command(5))
        assertEquals(AckStatus.REJECTED, replay.status)
        assertEquals("sequence is not increasing", replay.reason)
        val older = dispatcher.dispatch(command(4))
        assertEquals(AckStatus.REJECTED, older.status)
        // 失败不消耗：6 仍可执行
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(6)).status)
    }

    @Test
    fun unsupportedWhenNoExecutor() {
        val dispatcher = TextCommandDispatcher(emptyList())
        val ack = dispatcher.dispatch(command(1))
        assertEquals(AckStatus.UNSUPPORTED, ack.status)
    }

    @Test
    fun permissionDeniedMapsCorrectly() {
        val dispatcher = TextCommandDispatcher(listOf(FakeExecutor(result = AckStatus.PERMISSION_DENIED)))
        val ack = dispatcher.dispatch(command(1))
        assertEquals(AckStatus.PERMISSION_DENIED, ack.status)
        assertEquals("executor permission denied", ack.reason)
    }

    @Test
    fun executionFailedDoesNotConsumeSequence() {
        val executor = FakeExecutor(
            result = AckStatus.EXECUTION_FAILED,
            resultOverrideOnCall = { call -> if (call > 1) AckStatus.SUCCESS else AckStatus.EXECUTION_FAILED },
        )
        val dispatcher = TextCommandDispatcher(listOf(executor))
        assertEquals(AckStatus.EXECUTION_FAILED, dispatcher.dispatch(command(1)).status)
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(command(1)).status)
    }

    @Test
    fun securityExceptionMapsToPermissionDenied() {
        val executor = object : TextCommandExecutor {
            override fun supportsText() = true
            override fun executeText(command: TextCommand): AckStatus =
                throw SecurityException("denied")
        }
        val dispatcher = TextCommandDispatcher(listOf(executor))
        assertEquals(AckStatus.PERMISSION_DENIED, dispatcher.dispatch(command(1)).status)
    }

    @Test
    fun draftAndCommitBothRouted() {
        val executor = FakeExecutor()
        val dispatcher = TextCommandDispatcher(listOf(executor))
        dispatcher.dispatch(TextCommand(1, TextAction.DRAFT, "dr"))
        dispatcher.dispatch(TextCommand(2, TextAction.COMMIT, "cm"))
        assertTrue(executor.executed.any { it.action == TextAction.DRAFT })
        assertTrue(executor.executed.any { it.action == TextAction.COMMIT })
    }

}
