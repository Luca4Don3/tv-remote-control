package dev.tvremote.agent.media

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class DisabledMediaTest {
    @Test fun captureCannotBeRequestedOrAttached() {
        val output = ByteArrayOutputStream()
        assertNull(DisabledControlMediaSession.issueOffer("controller", "session"))
        assertNull(DisabledControlMediaSession.attach("controller", "session", "token", output))
        DisabledControlMediaSession.stopSession("session")
        DisabledControlMediaSession.stopAttachment(1)
        DisabledControlMediaSession.close()
        assertEquals(0, output.size())
    }
}
