package dev.lucasdone.tvremote.agent.command

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyStateTrackerTest {
    @Test
    fun acceptsDownRepeatUpAndReleasesState() {
        val tracker = KeyStateTracker()
        assertEquals(AckStatus.SUCCESS, tracker.accept(command(1, KeyState.DOWN)).status)
        assertEquals(AckStatus.SUCCESS, tracker.accept(command(2, KeyState.REPEAT)).status)
        assertEquals(AckStatus.SUCCESS, tracker.accept(command(3, KeyState.UP)).status)
        assertTrue(tracker.releaseAll().isEmpty())
    }

    @Test
    fun rejectsReplayAndReleaseWithoutPress() {
        val tracker = KeyStateTracker()
        assertEquals(AckStatus.REJECTED, tracker.accept(command(1, KeyState.UP)).status)
        // 被拒命令不推进序列号，相同序列可重试。
        assertEquals(AckStatus.SUCCESS, tracker.accept(command(1, KeyState.DOWN)).status)
        // 成功命令推进序列号，重放被拒。
        assertEquals(AckStatus.REJECTED, tracker.accept(command(1, KeyState.DOWN)).status)
        assertEquals(AckStatus.SUCCESS, tracker.accept(command(2, KeyState.UP)).status)
    }

    @Test
    fun validateHasNoSideEffectAndCommitAdvancesSequence() {
        val tracker = KeyStateTracker()
        // validate 是纯校验：通过后序列号不变，相同序列仍可通过。
        assertEquals(AckStatus.SUCCESS, tracker.validate(command(1, KeyState.DOWN)).status)
        assertEquals(AckStatus.SUCCESS, tracker.validate(command(1, KeyState.DOWN)).status)
        // commit 才推进序列号：commit 后相同序列被拒。
        tracker.commit(command(1, KeyState.DOWN))
        assertEquals(AckStatus.REJECTED, tracker.validate(command(1, KeyState.DOWN)).status)
        assertEquals(AckStatus.SUCCESS, tracker.validate(command(2, KeyState.REPEAT)).status)
        tracker.commit(command(2, KeyState.REPEAT))
        tracker.commit(command(3, KeyState.UP))
        assertTrue(tracker.releaseAll().isEmpty())
    }

    @Test
    fun releasesPressedKeysOnDisconnect() {
        val tracker = KeyStateTracker()
        tracker.accept(command(1, KeyState.DOWN))
        assertEquals(setOf(LogicalKey.DPAD_UP), tracker.releaseAll())
    }

    private fun command(sequence: Long, state: KeyState) = KeyEventCommand(
        sequence = sequence,
        key = LogicalKey.DPAD_UP,
        state = state,
    )
}
