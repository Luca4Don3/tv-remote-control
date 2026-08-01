package dev.lucasdone.tvremote.agent.media

/** Owns encoder-generation and packet ordering state independently of Android codecs. */
class ProjectionPacketState {
    data class ConfigTicket(
        val generation: Long,
        val sequence: Long,
        val configId: Long,
    )

    data class FrameTicket(
        val generation: Long,
        val sequence: Long,
        val configId: Long,
        val firstReducedKeyFrame: Boolean,
        val discontinuity: Boolean,
    )

    enum class OfferResult { ACCEPTED, REJECTED, REDUCTION_FAILED, STALE }

    data class FrameOfferResult(
        val result: OfferResult,
        val firstReducedKeyFrame: Boolean,
    )

    private var generation = 0L
    private var sequence = 0L
    private var configId = 0L
    private var discontinuityPending = false
    private var awaitingReducedConfig = false
    private var awaitingReducedKeyFrame = false

    @Synchronized
    fun beginEncoder(reduced: Boolean): Long {
        val value = ++generation
        if (reduced) {
            discontinuityPending = true
            awaitingReducedConfig = true
            awaitingReducedKeyFrame = false
        }
        return value
    }

    @Synchronized
    fun invalidateEncoder(): Long = ++generation

    @Synchronized
    fun isCurrent(expected: Long): Boolean = generation == expected

    @Synchronized
    fun offerConfig(
        expected: Long,
        sink: MediaPacketSink,
        packet: (ConfigTicket) -> MediaPacket,
    ): OfferResult {
        if (generation != expected) return OfferResult.STALE
        configId = increment32(configId)
        sequence = increment32(sequence)
        val ticket = ConfigTicket(expected, sequence, configId)
        val accepted = sink.offer(packet(ticket))
        if (!accepted) return if (awaitingReducedConfig) OfferResult.REDUCTION_FAILED else OfferResult.REJECTED
        if (awaitingReducedConfig) {
            awaitingReducedConfig = false
            awaitingReducedKeyFrame = true
        }
        return OfferResult.ACCEPTED
    }

    @Synchronized
    fun offerFrame(
        expected: Long,
        keyFrame: Boolean,
        sink: MediaPacketSink,
        packet: (FrameTicket) -> MediaPacket,
    ): FrameOfferResult? {
        if (generation != expected) return FrameOfferResult(OfferResult.STALE, false)
        if (awaitingReducedConfig || awaitingReducedKeyFrame && !keyFrame) return null
        sequence = increment32(sequence)
        val firstReducedKeyFrame = awaitingReducedKeyFrame && keyFrame
        val ticket = FrameTicket(
            generation = expected,
            sequence = sequence,
            configId = configId,
            firstReducedKeyFrame = firstReducedKeyFrame,
            discontinuity = keyFrame && (firstReducedKeyFrame || discontinuityPending),
        )
        val accepted = sink.offer(packet(ticket))
        if (ticket.firstReducedKeyFrame) {
            if (!accepted) return FrameOfferResult(OfferResult.REDUCTION_FAILED, true)
            awaitingReducedKeyFrame = false
            discontinuityPending = false
            return FrameOfferResult(OfferResult.ACCEPTED, true)
        }
        if (!accepted) {
            discontinuityPending = true
            return FrameOfferResult(OfferResult.REJECTED, false)
        }
        if (keyFrame) discontinuityPending = false
        return FrameOfferResult(OfferResult.ACCEPTED, false)
    }

    private fun increment32(value: Long): Long = (value + 1) and 0xffff_ffffL
}
