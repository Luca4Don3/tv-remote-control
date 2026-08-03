package dev.lucasdone.tvremote.agent.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MediaPacketChannelTest {
    @Test
    fun writesFixedHeaderAndPayload() {
        val output = ByteArrayOutputStream()
        val channel = MediaPacketChannel(output)
        channel.offer(
            MediaPacket(
                MediaPacketHeader(MediaTrack.AUDIO, sequence = 1, presentationTimeUs = 2, payloadLength = 4, codecConfigId = 1),
                byteArrayOf(1, 2, 3, 4),
            ),
        )
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                channel.writeLoop()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { start() }
        val deadline = System.nanoTime() + 1_000_000_000L
        while (output.size() < 36 && System.nanoTime() < deadline) Thread.yield()
        assertEquals(36, output.size())
        channel.close()
        writer.join(1_000)
        assertFalse(writer.isAlive)
        failure.get()?.let { throw AssertionError("media writer failed", it) }
    }

    @Test
    fun closeUsesTransportHookWithoutFlushingBufferedOutput() {
        var transportClosed = false
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun flush() = throw AssertionError("close must not flush a blocked media stream")
            override fun close() = throw AssertionError("close must use the transport hook")
        }
        val channel = MediaPacketChannel(output) { transportClosed = true }
        channel.close()
        assertTrue(transportClosed)
    }

    @Test
    fun reportsQueuedVideoDropSoEncoderCanRequestAKeyFrame() {
        val channel = MediaPacketChannel(ByteArrayOutputStream())
        repeat(MediaPacketChannel.MAX_PACKETS) { index ->
            assertTrue(channel.offer(videoPacket(index.toLong() + 1L)))
        }
        assertFalse(channel.offer(videoPacket(MediaPacketChannel.MAX_PACKETS.toLong() + 1L)))
        channel.close()
    }

    @Test
    fun interruptedWriterClosesTransportAndReportsInterruption() {
        val started = CountDownLatch(1)
        var transportClosed = false
        val channel = MediaPacketChannel(ByteArrayOutputStream()) { transportClosed = true }
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            started.countDown()
            try {
                channel.writeLoop()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { start() }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        writer.interrupt()
        writer.join(1_000)
        assertFalse(writer.isAlive)
        assertTrue(transportClosed)
        assertTrue(failure.get() is InterruptedIOException)
    }

    private fun videoPacket(sequence: Long) = MediaPacket(
        MediaPacketHeader(
            track = MediaTrack.VIDEO,
            sequence = sequence,
            presentationTimeUs = sequence,
            payloadLength = 1,
            codecConfigId = 1,
        ),
        byteArrayOf(1),
    )
}
