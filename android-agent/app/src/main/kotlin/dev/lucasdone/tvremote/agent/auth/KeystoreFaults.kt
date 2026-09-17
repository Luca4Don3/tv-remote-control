package dev.lucasdone.tvremote.agent.auth

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException

/** Evidence available when a Keystore key fails a private-key operation. */
internal enum class KeyFault { PERMANENT, AUTHORIZATION_INCOMPATIBLE, UNKNOWN }

private const val MAX_CAUSE_DEPTH = 8

/**
 * True when the failure (or a wrapped cause) is a permanent key invalidation. Checked by type only —
 * never by message text — and only on API 23+ where the signal exists; older platforms report false
 * so callers stay conservative. [sdkInt] and [isPermanentType] are injectable for unit tests.
 */
internal fun isPermanentInvalidation(
    error: Throwable,
    sdkInt: Int = Build.VERSION.SDK_INT,
    isPermanentType: (Throwable) -> Boolean = ::isPermanentKeyInvalidation,
): Boolean {
    if (sdkInt < 23) return false
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (isPermanentType(current)) return true
        current = current.cause
        depth += 1
    }
    return false
}

private fun isPermanentKeyInvalidation(error: Throwable): Boolean =
    if (Build.VERSION.SDK_INT >= 23) error is KeyPermanentlyInvalidatedException else false

/**
 * True when the key's authorized private-key operation, digests or paddings cannot perform the
 * operation we need. Public-key purpose bits (ENCRYPT/VERIFY) are intentionally ignored: Android
 * Keystore authorization constrains private-key operations, so their absence is not evidence.
 */
internal fun authorizationIncompatible(
    purposes: Int,
    digests: Set<String>,
    paddings: Set<String>,
    requiredPurpose: Int,
    requiredDigest: String,
    requiredPadding: String,
    extraPadding: String? = null,
): Boolean =
    (purposes and requiredPurpose) == 0 ||
        requiredDigest !in digests ||
        requiredPadding !in paddings ||
        (extraPadding != null && extraPadding !in paddings)
