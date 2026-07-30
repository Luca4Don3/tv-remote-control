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
        assertEquals(AckStatus.REJECTED, tracker.accept(command(1, KeyState.DOWN)).status)
        assertEquals(AckStatus.SUCCESS, tracker.accept(command(2, KeyState.DOWN)).status)
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
