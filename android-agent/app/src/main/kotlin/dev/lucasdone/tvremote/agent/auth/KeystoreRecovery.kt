package dev.lucasdone.tvremote.agent.auth

/**
 * Confirm-before-repair policy for Keystore-wrapped data.
 *
 * A key-level failure only causes a rebuild after [keyUsable] proves the wrapping key cannot perform
 * a round trip; when the key still works the original error is rethrown so no data is discarded.
 * Record-level corruption drops only the affected record, and anything else propagates.
 */
internal class KeystoreRecovery(
    private val keyUsable: () -> Boolean,
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
                if (keyUsable()) throw error
                deleteKey() // propagates on failure, before any record is cleared
                clearRecords()
                operation()
            }
            KeystoreFailureAction.DROP_RECORD -> {
                removeRecord(controllerId ?: throw error)
                null
            }
            KeystoreFailureAction.PROPAGATE -> throw error
        }
    }
}
