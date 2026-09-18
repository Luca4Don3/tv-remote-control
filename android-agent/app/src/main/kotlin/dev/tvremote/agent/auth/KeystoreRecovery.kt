package dev.tvremote.agent.auth

/**
 * Confirm-before-repair policy for Keystore-wrapped data.
 *
 * A key-level failure only causes a rebuild when the existing key is not usable now **and** the
 * failure carries positive evidence — a permanent invalidation or an authorization-config
 * incompatibility. Any other failure (transient, environment-specific, unknown, or on platforms
 * without such signals) preserves the key and its records and rethrows the original error.
 *
 * Record-level corruption drops only the affected record; anything else propagates.
 */
internal class KeystoreRecovery(
    private val existingKeyUsable: () -> Boolean,
    private val keyFault: (Throwable) -> KeyFault,
    private val deleteKey: () -> Unit,
    private val clearRecords: () -> Unit,
    private val removeRecord: (String) -> Unit,
) {
    /**
     * Runs [operation], applying the policy on failure. Returns the operation result, or null when a
     * corrupt record was dropped. [controllerId] must be non-null on read paths.
     */
    fun <T> run(controllerId: String?, operation: () -> T): T? = try {
        operation()
    } catch (error: Exception) {
        when (classifyKeystoreFailure(error)) {
            KeystoreFailureAction.KEY_REPAIR -> {
                if (existingKeyUsable()) throw error
                when (keyFault(error)) {
                    KeyFault.PERMANENT, KeyFault.AUTHORIZATION_INCOMPATIBLE -> {
                        deleteKey() // propagates on failure, before any record is cleared
                        clearRecords()
                        operation()
                    }
                    KeyFault.UNKNOWN -> throw error
                }
            }
            KeystoreFailureAction.DROP_RECORD -> {
                removeRecord(controllerId ?: throw error)
                null
            }
            KeystoreFailureAction.PROPAGATE -> throw error
        }
    }
}
