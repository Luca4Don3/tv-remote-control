package dev.lucasdone.tvremote.agent.auth

import dev.lucasdone.tvremote.agent.protocol.Hex
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class PairingWindow(
    val code: String,
    val tvNonce: ByteArray,
    val expiresAtMs: Long,
    /** 扫码配对一次性 token（32B hex），替代手输 6 位码；SAS 核对不受影响。 */
    val qrToken: String,
    val qrTokenExpiresAtMs: Long,
)

data class PairingSas(
    val pairingId: String,
    val controllerName: String,
    val sas: String,
    val tvNonce: ByteArray,
    val controllerNonce: ByteArray,
    val certificateFingerprint: ByteArray,
    val expiresAtMs: Long,
)

data class PairedCredential(
    val controllerId: String,
    val controllerName: String,
    val certificateFingerprint: ByteArray,
    val secret: ByteArray,
)

sealed class PairingSubmission {
    data class AwaitingTvConfirmation(val details: PairingSas) : PairingSubmission()
    data class Rejected(val reason: String) : PairingSubmission()
}

sealed class PairingDecision {
    data class Accepted(val credential: PairedCredential) : PairingDecision()
    data class Rejected(val reason: String) : PairingDecision()
}

class PairingManager(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Pending(
        val window: PairingWindow,
        var failedAttempts: Int = 0,
        var sas: PairingSas? = null,
        var decision: PairingDecision? = null,
        val decisionReady: CountDownLatch = CountDownLatch(1),
        var qrTokenConsumed: Boolean = false,
    )

    private var pending: Pending? = null

    @Synchronized
    fun openWindow(): PairingWindow {
        pending?.let { close(it, "pairing replaced by a new local window") }
        val expiresAtMs = nowMs() + PAIRING_TTL_MS
        val window = PairingWindow(
            code = random.nextInt(1_000_000).toString().padStart(6, '0'),
            tvNonce = randomBytes(NONCE_BYTES),
            expiresAtMs = expiresAtMs,
            qrToken = Hex.encode(randomBytes(NONCE_BYTES)),
            qrTokenExpiresAtMs = minOf(expiresAtMs, nowMs() + QR_TOKEN_TTL_MS),
        )
        pending = Pending(window)
        return window.copy(tvNonce = window.tvNonce.copyOf())
    }

    @Synchronized
    fun submit(
        code: String,
        controllerName: String,
        controllerNonce: ByteArray,
        certificateFingerprint: ByteArray,
    ): PairingSubmission {
        val current = activePending() ?: return PairingSubmission.Rejected("pairing unavailable")
        if (current.sas != null) return PairingSubmission.Rejected("pairing already awaiting confirmation")
        if (code.length != 6 || code.any { !it.isDigit() } || code != current.window.code) {
            current.failedAttempts += 1
            if (current.failedAttempts >= MAX_FAILED_ATTEMPTS) close(current, "too many failed attempts")
            return PairingSubmission.Rejected("pairing rejected")
        }
        return beginPairing(current, controllerName, controllerNonce, certificateFingerprint, sasCode = current.window.code)
    }

    /**
     * 扫码配对：一次性 token 替代 6 位码输入（token 只出现在电视屏幕二维码中，
     * 视为“在电视机前”的证明）；SAS 双端核对流程不受影响。
     */
    @Synchronized
    fun submitWithToken(
        token: String,
        controllerName: String,
        controllerNonce: ByteArray,
        certificateFingerprint: ByteArray,
    ): PairingSubmission {
        val current = activePending() ?: return PairingSubmission.Rejected("pairing unavailable")
        if (current.sas != null) return PairingSubmission.Rejected("pairing already awaiting confirmation")
        if (current.qrTokenConsumed) return PairingSubmission.Rejected("pairing token already consumed")
        val tokenValid = token.length == QR_TOKEN_HEX_LENGTH &&
            token.all { it.isDigit() || it in 'a'..'f' } &&
            token == current.window.qrToken &&
            nowMs() < current.window.qrTokenExpiresAtMs
        if (!tokenValid) {
            current.failedAttempts += 1
            if (current.failedAttempts >= MAX_FAILED_ATTEMPTS) close(current, "too many failed attempts")
            return PairingSubmission.Rejected("pairing rejected")
        }
        current.qrTokenConsumed = true
        // 扫码路径 SAS 输入 = token（扫码端只持有 token，无法知道 6 位码——
        // 若 SAS 仍用 code，扫码端无法独立核对，SAS 退化为装饰）
        return beginPairing(current, controllerName, controllerNonce, certificateFingerprint, sasCode = token)
    }

    private fun beginPairing(
        current: Pending,
        controllerName: String,
        controllerNonce: ByteArray,
        certificateFingerprint: ByteArray,
        sasCode: String,
    ): PairingSubmission {
        if (!validControllerName(controllerName)) return PairingSubmission.Rejected("invalid controller name")
        if (controllerNonce.size != NONCE_BYTES) return PairingSubmission.Rejected("invalid controller nonce")
        if (certificateFingerprint.size != FINGERPRINT_BYTES) return PairingSubmission.Rejected("invalid certificate fingerprint")

        val normalizedName = controllerName.trim()
        val details = PairingSas(
            pairingId = Hex.encode(randomBytes(PAIRING_ID_BYTES)),
            controllerName = normalizedName,
            sas = PairingTranscript.computeSas(
                code = sasCode,
                protocolVersion = PROTOCOL_VERSION,
                certificateFingerprint = certificateFingerprint,
                tvNonce = current.window.tvNonce,
                controllerNonce = controllerNonce,
                controllerName = normalizedName,
            ),
            tvNonce = current.window.tvNonce.copyOf(),
            controllerNonce = controllerNonce.copyOf(),
            certificateFingerprint = certificateFingerprint.copyOf(),
            expiresAtMs = current.window.expiresAtMs,
        )
        current.sas = details
        return PairingSubmission.AwaitingTvConfirmation(details.copy(
            tvNonce = details.tvNonce.copyOf(),
            controllerNonce = details.controllerNonce.copyOf(),
            certificateFingerprint = details.certificateFingerprint.copyOf(),
        ))
    }

    @Synchronized
    fun confirm(pairingId: String, accepted: Boolean): PairingDecision? {
        val current = activePending() ?: return null
        val details = current.sas ?: return null
        if (details.pairingId != pairingId || current.decision != null) return null
        val decision = if (accepted) {
            PairingDecision.Accepted(
                PairedCredential(
                    controllerId = Hex.encode(randomBytes(CONTROLLER_ID_BYTES)),
                    controllerName = details.controllerName,
                    certificateFingerprint = details.certificateFingerprint.copyOf(),
                    secret = randomBytes(SECRET_BYTES),
                ),
            )
        } else {
            PairingDecision.Rejected("rejected on TV")
        }
        current.decision = decision
        current.decisionReady.countDown()
        return decision.copyForCaller()
    }

    fun awaitDecision(pairingId: String, timeoutMs: Long): PairingDecision? {
        val snapshot = synchronized(this) {
            val current = activePending() ?: return null
            if (current.sas?.pairingId != pairingId) return null
            current to maxOf(0L, minOf(timeoutMs, current.window.expiresAtMs - nowMs()))
        }
        if (!snapshot.first.decisionReady.await(snapshot.second, TimeUnit.MILLISECONDS)) {
            synchronized(this) {
                if (pending === snapshot.first) close(snapshot.first, "pairing confirmation timed out")
            }
        }
        return synchronized(this) { snapshot.first.decision?.copyForCaller() }
    }

    @Synchronized
    fun complete(pairingId: String) {
        val current = pending ?: return
        if (current.sas?.pairingId == pairingId) pending = null
    }

    @Synchronized
    fun cancel(pairingId: String? = null) {
        val current = pending ?: return
        if (pairingId != null && current.sas?.pairingId != pairingId) return
        close(current, "pairing cancelled")
    }

    @Synchronized
    fun currentWindow(): PairingWindow? = activePending()?.window?.let {
        it.copy(tvNonce = it.tvNonce.copyOf())
    }

    private fun activePending(): Pending? {
        val current = pending ?: return null
        if (nowMs() >= current.window.expiresAtMs) {
            close(current, "pairing expired")
            return null
        }
        return current
    }

    private fun close(current: Pending, reason: String) {
        if (current.decision == null) current.decision = PairingDecision.Rejected(reason)
        current.decisionReady.countDown()
        if (pending === current) pending = null
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun validControllerName(value: String): Boolean {
        val normalized = value.trim()
        if (value != normalized) return false
        if (normalized.isEmpty() || normalized.length > MAX_CONTROLLER_NAME_CHARS) return false
        if (normalized.any { it < ' ' || Character.isISOControl(it) }) return false
        return normalized.toByteArray(Charsets.UTF_8).size <= MAX_CONTROLLER_NAME_BYTES
    }

    private fun PairingDecision.copyForCaller(): PairingDecision = when (this) {
        is PairingDecision.Accepted -> PairingDecision.Accepted(
            credential.copy(
                certificateFingerprint = credential.certificateFingerprint.copyOf(),
                secret = credential.secret.copyOf(),
            ),
        )
        is PairingDecision.Rejected -> copy()
    }

    companion object {
        const val PAIRING_TTL_MS = 120_000L
        const val QR_TOKEN_TTL_MS = 60_000L
        private const val QR_TOKEN_HEX_LENGTH = 64
        private const val MAX_CONTROLLER_NAME_CHARS = 64
        private const val MAX_CONTROLLER_NAME_BYTES = 128
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val PROTOCOL_VERSION = 1
        private const val NONCE_BYTES = 32
        private const val FINGERPRINT_BYTES = 32
        private const val SECRET_BYTES = 32
        private const val PAIRING_ID_BYTES = 16
        private const val CONTROLLER_ID_BYTES = 16
    }
}
