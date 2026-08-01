package dev.lucasdone.tvremote.agent.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProjectionPacketStateTest {
    private val packet = MediaPacket(
        MediaPacketHeader(
            track = MediaTrack.VIDEO,
            sequence = 0,
            presentationTimeUs = 0,
            payloadLength = 1,
            codecConfigId = 0,
        ),
        byteArrayOf(1),
    )

    @Test
    fun `stale generation never reaches sink`() {
        val state = ProjectionPacketState()
        val stale = state.beginEncoder(reduced = false)
        val current = state.beginEncoder(reduced = false)
        var offers = 0
        val sink = MediaPacketSink { offers++; true }

        assertEquals(ProjectionPacketState.OfferResult.STALE, state.offerConfig(stale, sink) { packet })
        assertEquals(ProjectionPacketState.OfferResult.STALE, state.offerFrame(stale, true, sink) { packet }?.result)
        assertEquals(0, offers)
        assertTrue(state.isCurrent(current))
    }

    @Test
    fun `invalidation and sink offer share one linearization point`() {
        val state = ProjectionPacketState()
        val generation = state.beginEncoder(reduced = false)
        val enteredOffer = CountDownLatch(1)
        val releaseOffer = CountDownLatch(1)
        val invalidationStarted = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        val sink = MediaPacketSink {
            enteredOffer.countDown()
            check(releaseOffer.await(2, TimeUnit.SECONDS))
            true
        }
        val offering = Thread { state.offerFrame(generation, true, sink) { packet } }
        val invalidating = Thread {
            invalidationStarted.countDown()
            state.invalidateEncoder()
            invalidated.countDown()
        }

        offering.start()
        assertTrue(enteredOffer.await(2, TimeUnit.SECONDS))
        invalidating.start()
        assertTrue(invalidationStarted.await(2, TimeUnit.SECONDS))
        assertFalse(invalidated.await(50, TimeUnit.MILLISECONDS))
        releaseOffer.countDown()
        offering.join(2_000)
        invalidating.join(2_000)
        assertFalse(offering.isAlive)
        assertFalse(invalidating.isAlive)
        assertEquals(ProjectionPacketState.OfferResult.STALE, state.offerFrame(generation, true, sink) { packet }?.result)
    }

    @Test
    fun `reduced encoder gates frames until config and first key frame`() {
        val state = ProjectionPacketState()
        val sink = MediaPacketSink { true }
        state.beginEncoder(reduced = false)
        val generation = state.beginEncoder(reduced = true)

        assertNull(state.offerFrame(generation, true, sink) { packet })
        assertEquals(ProjectionPacketState.OfferResult.ACCEPTED, state.offerConfig(generation, sink) { packet })
        assertNull(state.offerFrame(generation, false, sink) { packet })
        var discontinuity = false
        val key = requireNotNull(state.offerFrame(generation, true, sink) { ticket ->
            discontinuity = ticket.discontinuity
            packet
        })
        assertTrue(key.firstReducedKeyFrame)
        assertTrue(discontinuity)
        assertFalse(requireNotNull(state.offerFrame(generation, false, sink) { ticket ->
            discontinuity = ticket.discontinuity
            packet
        }).firstReducedKeyFrame)
        assertFalse(discontinuity)
    }

    @Test
    fun `reduced config and first key frame rejection are terminal`() {
        val rejected = MediaPacketSink { false }
        val accepted = MediaPacketSink { true }
        val configState = ProjectionPacketState()
        val configGeneration = configState.beginEncoder(reduced = true)
        assertEquals(ProjectionPacketState.OfferResult.REDUCTION_FAILED, configState.offerConfig(configGeneration, rejected) { packet })

        val keyState = ProjectionPacketState()
        val keyGeneration = keyState.beginEncoder(reduced = true)
        keyState.offerConfig(keyGeneration, accepted) { packet }
        assertEquals(
            ProjectionPacketState.OfferResult.REDUCTION_FAILED,
            keyState.offerFrame(keyGeneration, true, rejected) { packet }?.result,
        )
    }

    @Test
    fun `sequence and config id remain global across generations`() {
        val state = ProjectionPacketState()
        val sink = MediaPacketSink { true }
        var firstConfigSequence = 0L
        var firstConfigId = 0L
        var firstFrameSequence = 0L
        var secondConfigSequence = 0L
        var secondConfigId = 0L
        val first = state.beginEncoder(reduced = false)
        state.offerConfig(first, sink) { ticket ->
            firstConfigSequence = ticket.sequence
            firstConfigId = ticket.configId
            packet
        }
        state.offerFrame(first, true, sink) { ticket ->
            firstFrameSequence = ticket.sequence
            packet
        }
        val second = state.beginEncoder(reduced = false)
        state.offerConfig(second, sink) { ticket ->
            secondConfigSequence = ticket.sequence
            secondConfigId = ticket.configId
            packet
        }

        assertTrue(firstFrameSequence > firstConfigSequence)
        assertTrue(secondConfigSequence > firstFrameSequence)
        assertTrue(secondConfigId > firstConfigId)
    }
}
