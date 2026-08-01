package dev.lucasdone.tvremote.agent.media

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class MediaSessionCoordinatorTest {
    @Test
    fun attachTokenIsOneTimeAndBoundToSession() {
        var now = 1_000L
        val coordinator = MediaSessionCoordinator(DeterministicRandom(), { now })
        val offer = coordinator.issueOffer("controller-1", "session-1")!!
        assertNull(coordinator.attach("other-controller", "session-1", offer.token, ByteArrayOutputStream()))
        assertNull(coordinator.attach("controller-1", "other-session", offer.token, ByteArrayOutputStream()))
        assertNotNull(coordinator.attach("controller-1", "session-1", offer.token, ByteArrayOutputStream()))
        assertNull(coordinator.attach("controller-1", "session-1", offer.token, ByteArrayOutputStream()))
        coordinator.stopSession("session-1")

        val expired = coordinator.issueOffer("controller-1", "session-2")!!
        now = expired.expiresAtMs
        assertNull(coordinator.attach("controller-1", "session-2", expired.token, ByteArrayOutputStream()))
        coordinator.close()
    }

    @Test
    fun repeatedOfferIsRejectedWithoutRotatingToken() {
        val coordinator = coordinator()
        val first = coordinator.issueOffer("controller-1", "session-1")!!
        assertNull(coordinator.issueOffer("controller-1", "session-1"))
        assertNotNull(coordinator.attach("controller-1", "session-1", first.token, ByteArrayOutputStream()))
        assertNull(coordinator.issueOffer("controller-1", "session-1"))
        coordinator.close()
    }

    @Test
    fun attachedSessionExpiresWhenLocalAuthorizationDoesNotArrive() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val states = mutableListOf<String>()
        val coordinator = MediaSessionCoordinator(
            random = DeterministicRandom(),
            nowMs = { 1_000L },
            onState = states::add,
            scheduleExpiry = { delay, action -> scheduled += delay to action },
        )
        val offer = coordinator.issueOffer("controller-1", "session-1")!!
        val output = ByteArrayOutputStream()
        assertNotNull(coordinator.attach("controller-1", "session-1", offer.token, output))

        scheduled.single { it.first == MediaSessionCoordinator.AUTHORIZATION_TTL_MS }.second.invoke()

        assertNull(coordinator.currentAttachmentId())
        assertEquals("media_authorization_timeout", states.last())
    }

    @Test
    fun staleLocalAuthorizationCannotStopReplacementAttachment() {
        val coordinator = coordinator()
        val first = coordinator.issueOffer("controller-1", "session-1")!!
        coordinator.attach("controller-1", "session-1", first.token, ByteArrayOutputStream())
        val firstAttachment = coordinator.currentAttachmentId()!!
        coordinator.stopAttachment(firstAttachment)

        val second = coordinator.issueOffer("controller-1", "session-1")!!
        coordinator.attach("controller-1", "session-1", second.token, ByteArrayOutputStream())
        val secondAttachment = coordinator.currentAttachmentId()!!
        coordinator.stopAttachment(firstAttachment)
        assertEquals(secondAttachment, coordinator.currentAttachmentId())
        coordinator.close()
    }

    @Test
    fun expiredAttachClearsUiBeforeScheduledExpiryRuns() {
        var now = 1_000L
        var scheduled: (() -> Unit)? = null
        val states = mutableListOf<String>()
        val coordinator = MediaSessionCoordinator(
            random = DeterministicRandom(),
            nowMs = { now },
            onState = states::add,
            scheduleExpiry = { _, action -> scheduled = action },
        )
        val offer = coordinator.issueOffer("controller-1", "session-1")!!
        now = offer.expiresAtMs
        assertNull(coordinator.attach("controller-1", "session-1", offer.token, ByteArrayOutputStream()))
        scheduled!!.invoke()
        assertEquals(listOf("media_requested", "media_idle"), states)
        coordinator.close()
    }

    @Test
    fun pendingOfferExpiresAndReturnsToIdle() {
        var now = 1_000L
        var scheduled: (() -> Unit)? = null
        val states = mutableListOf<String>()
        val coordinator = MediaSessionCoordinator(
            random = DeterministicRandom(),
            nowMs = { now },
            onState = states::add,
            scheduleExpiry = { _, action -> scheduled = action },
        )
        val offer = coordinator.issueOffer("controller-1", "session-1")!!
        now = offer.expiresAtMs
        scheduled!!.invoke()
        assertEquals(listOf("media_requested", "media_idle"), states)
        assertNull(coordinator.attach("controller-1", "session-1", offer.token, ByteArrayOutputStream()))
        coordinator.close()
    }

    private fun coordinator() = MediaSessionCoordinator(
        random = DeterministicRandom(),
        nowMs = { 1_000L },
        scheduleExpiry = { _, _ -> },
    )

    private class DeterministicRandom : SecureRandom() {
        private var invocation = 0

        override fun nextBytes(bytes: ByteArray) {
            val offset = invocation++
            bytes.indices.forEach { bytes[it] = (it + offset).toByte() }
        }
    }
}
