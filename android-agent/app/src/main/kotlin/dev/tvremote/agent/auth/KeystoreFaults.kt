package dev.tvremote.agent.auth

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

/** Evidence available when a Keystore key fails a private-key operation. */
internal enum class KeyFault { PERMANENT, AUTHORIZATION_INCOMPATIBLE, UNKNOWN }

/**
 * Candidate handling for a Keystore failure:
 * - [KeystoreFailureAction.KEY_REPAIR]: a key-level failure; [KeystoreRecovery] rebuilds the key only
 *   with positive evidence ([KeyFault.PERMANENT] or [KeyFault.AUTHORIZATION_INCOMPATIBLE]).
 * - [KeystoreFailureAction.DROP_RECORD]: only the affected ciphertext is corrupt.
 * - [KeystoreFailureAction.PROPAGATE]: transient or unexpected, so callers fail loudly.
 */
internal enum class KeystoreFailureAction { KEY_REPAIR, DROP_RECORD, PROPAGATE }

private const val MAX_CAUSE_DEPTH = 8

/**
 * Classifies a Keystore failure, walking the (bounded) cause chain so a key or record fault wrapped
 * in another exception is still recognized instead of being treated as unexpected.
 */
internal fun classifyKeystoreFailure(error: Throwable): KeystoreFailureAction {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is InvalidKeyException, is UnrecoverableKeyException -> return KeystoreFailureAction.KEY_REPAIR
            is AEADBadTagException, is BadPaddingException, is IllegalArgumentException ->
                return KeystoreFailureAction.DROP_RECORD
        }
        current = current.cause
        depth += 1
    }
    return KeystoreFailureAction.PROPAGATE
}

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
