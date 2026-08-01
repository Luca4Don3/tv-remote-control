package dev.lucasdone.tvremote.agent.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPacketTest {
    @Test
    fun fixedHeaderMatchesDesktopWireLayout() {
        val header = MediaPacketHeader(
            track = MediaTrack.VIDEO,
            keyFrame = true,
            sequence = 42,
            presentationTimeUs = 123_456,
            payloadLength = 1_024,
            codecConfigId = 3,
            width = 1_280,
            height = 720,
        )
        val encoded = header.encode()
        assertEquals(32, encoded.size)
        assertArrayEquals(byteArrayOf(0x54, 0x56, 0x52, 0x4d), encoded.copyOfRange(0, 4))
        assertEquals(header, MediaPacketHeader.decode(encoded))
    }

    @Test
    fun rejectsReservedFlagsAndOversizedPayload() {
        val valid = MediaPacketHeader(MediaTrack.AUDIO, sequence = 1, presentationTimeUs = 0, payloadLength = 0, codecConfigId = 1).encode()
        valid[6] = 0x10
        val result = runCatching { MediaPacketHeader.decode(valid) }
        assertTrue(result.isFailure)
        assertTrue(
            runCatching {
                MediaPacketHeader(MediaTrack.VIDEO, sequence = 1, presentationTimeUs = 0, payloadLength = 4 * 1024 * 1024 + 1, codecConfigId = 1)
            }.isFailure,
        )
    }
}
