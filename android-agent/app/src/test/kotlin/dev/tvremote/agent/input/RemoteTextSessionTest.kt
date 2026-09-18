package dev.tvremote.agent.input

import dev.tvremote.agent.model.AckStatus
import org.junit.Assert.*
import org.junit.Test

class RemoteTextSessionTest {
    @Test fun unicodeIsDeliveredExactlyOnceWithoutReadingExistingText() {
        val writes = mutableListOf<String>()
        val session = RemoteTextSession()
        val ticket = session.attach { text -> writes += text; true }
        val text = "小米 4C，遥控🙂\n第二行 abc 123"
        assertEquals(AckStatus.SUCCESS, session.submit(ticket, text))
        assertEquals(listOf(text), writes)
    }
    @Test fun focusChangeRejectsOldQueuedText() {
        val writes = mutableListOf<String>()
        val session = RemoteTextSession()
        val old = session.attach { writes += it; true }
        session.detach()
        assertNull(session.ticket())
        val fresh = session.attach { writes += it; true }
        assertEquals(AckStatus.PERMISSION_DENIED, session.submit(old, "stale"))
        assertTrue(writes.isEmpty())
        assertEquals(AckStatus.SUCCESS, session.submit(fresh, "current"))
        assertEquals(listOf("current"), writes)
    }
    @Test fun finishInputInvalidatesSubmission() {
        val session = RemoteTextSession()
        val ticket = session.attach { true }
        session.detach()
        assertEquals(AckStatus.PERMISSION_DENIED, session.submit(ticket, "text"))
    }
    @Test fun rejectedEditorCommitIsNotReportedAsSuccess() {
        val session = RemoteTextSession(); val ticket = session.attach { false }
        assertEquals(AckStatus.EXECUTION_FAILED, session.submit(ticket, "text"))
    }
    @Test fun emptyAndOversizeTextNeverReachTheEditor() {
        var calls = 0
        val session = RemoteTextSession(); val ticket = session.attach { calls++; true }
        assertEquals(AckStatus.REJECTED, session.submit(ticket, ""))
        assertEquals(AckStatus.REJECTED, session.submit(ticket, "a".repeat(4097)))
        assertEquals(0, calls)
    }
}
