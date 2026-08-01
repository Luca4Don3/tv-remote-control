package dev.lucasdone.tvremote.agent.media

class QualityReductionController(
    private val rejectionThreshold: Int = DEFAULT_REJECTION_THRESHOLD,
) {
    enum class State { DEFAULT, PENDING, REDUCED, FAILED }

    init {
        require(rejectionThreshold > 0)
    }

    private var rejectedOrdinaryPackets = 0
    private var state = State.DEFAULT

    @Synchronized
    fun onVideoResult(accepted: Boolean, ordinary: Boolean): Boolean {
        if (state != State.DEFAULT) return false
        if (accepted) {
            rejectedOrdinaryPackets = 0
            return false
        }
        if (!ordinary) return false
        rejectedOrdinaryPackets++
        return requestWhen(rejectedOrdinaryPackets >= rejectionThreshold)
    }

    @Synchronized
    fun onThermalStatus(status: Int): Boolean = requestWhen(status >= THERMAL_STATUS_SEVERE)

    @Synchronized
    fun markReduced(): Boolean {
        if (state != State.PENDING) return false
        state = State.REDUCED
        return true
    }

    @Synchronized
    fun markFailed(): Boolean {
        if (state != State.PENDING) return false
        state = State.FAILED
        return true
    }

    @Synchronized
    fun snapshot(): State = state

    @Synchronized
    fun rejectedPacketCount(): Int = rejectedOrdinaryPackets

    private fun requestWhen(condition: Boolean): Boolean {
        if (!condition || state != State.DEFAULT) return false
        state = State.PENDING
        return true
    }

    companion object {
        const val DEFAULT_REJECTION_THRESHOLD = 30
        const val THERMAL_STATUS_SEVERE = 4
    }
}
