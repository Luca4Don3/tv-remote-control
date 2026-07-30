package dev.lucasdone.tvremote.agent.auth

import java.security.SecureRandom
import java.util.UUID

data class PairingOffer(
    val controllerName: String,
    val code: String,
    val expiresAtMs: Long,
)

data class PairedCredential(
    val controllerId: String,
    val controllerName: String,
    val secret: ByteArray,
)

sealed class PairingSubmission {
    data object AwaitingTvConfirmation : PairingSubmission()
    data class Rejected(val reason: String) : PairingSubmission()
}

class PairingManager(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Pending(
        val offer: PairingOffer,
        var failedAttempts: Int = 0,
        var codeAccepted: Boolean = false,
    )

    private var pending: Pending? = null

    @Synchronized
    fun begin(controllerName: String): PairingOffer {
        require(controllerName.isNotBlank()) { "controllerName must not be blank" }
        val offer = PairingOffer(
            controllerName = controllerName.trim().take(MAX_CONTROLLER_NAME_LENGTH),
            code = random.nextInt(1_000_000).toString().padStart(6, '0'),
            expiresAtMs = nowMs() + PAIRING_TTL_MS,
        )
        pending = Pending(offer)
        return offer
    }

    @Synchronized
    fun submit(code: String): PairingSubmission {
        val current = pending ?: return PairingSubmission.Rejected("no active pairing")
        if (nowMs() >= current.offer.expiresAtMs) {
            pending = null
            return PairingSubmission.Rejected("pairing code expired")
        }
        if (current.failedAttempts >= MAX_FAILED_ATTEMPTS) {
            pending = null
            return PairingSubmission.Rejected("too many failed attempts")
        }
        if (code != current.offer.code) {
            current.failedAttempts += 1
            return PairingSubmission.Rejected("pairing code mismatch")
        }
        current.codeAccepted = true
        return PairingSubmission.AwaitingTvConfirmation
    }

    @Synchronized
    fun confirm(accepted: Boolean): PairedCredential? {
        val current = pending ?: return null
        pending = null
        if (!accepted || !current.codeAccepted || nowMs() >= current.offer.expiresAtMs) return null

        return PairedCredential(
            controllerId = UUID.randomUUID().toString(),
            controllerName = current.offer.controllerName,
            secret = ByteArray(32).also(random::nextBytes),
        )
    }

    @Synchronized
    fun cancel() {
        pending = null
    }

    companion object {
        private const val MAX_CONTROLLER_NAME_LENGTH = 64
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val PAIRING_TTL_MS = 120_000L
    }
}
