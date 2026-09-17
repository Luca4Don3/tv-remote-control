package dev.lucasdone.tvremote.agent.auth

/**
 * Confirm-before-repair policy for Keystore-wrapped data.
 *
 * A key-level failure only causes a rebuild when the existing key fails its self-test **and** a
 * freshly generated control key works, proving the Keystore/provider is healthy and the old key is
 * the fault. If the existing key works, or the control key also fails (a transient/provider fault we
 * cannot attribute to the key), the original error is rethrown so no data is discarded.
 *
 * Record-level corruption drops only the affected record; anything else propagates.
 */
internal class KeystoreRecovery(
    private val existingKeyUsable: () -> Boolean,
    private val controlKeyUsable: () -> Boolean,
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
                if (!controlKeyUsable()) throw error // environment unhealthy: cannot confirm the key is at fault
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
