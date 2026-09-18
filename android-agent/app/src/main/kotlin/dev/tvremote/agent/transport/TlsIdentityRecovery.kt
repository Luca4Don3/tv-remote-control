package dev.tvremote.agent.transport

/** Outcome of loading the TLS identity, before the self-test and any regeneration decision. */
internal sealed interface TlsLoad<out T> {
    data class Loaded<T>(val entry: T) : TlsLoad<T>
    data class Failed(val error: Throwable) : TlsLoad<Nothing>
    data object Absent : TlsLoad<Nothing>
}

/**
 * Decides whether a stored TLS identity may be used or replaced. Regeneration requires positive
 * evidence — a permanent invalidation or an authorization-config incompatibility — taken from the
 * original failure, never a synthesized one. An absent identity is created on first run; an unusable
 * one without such evidence is preserved and its original error rethrown.
 */
internal class TlsIdentityRecovery<T : Any>(
    private val selfTest: (T) -> Throwable?,
    private val isPermanentInvalidation: (Throwable) -> Boolean,
    private val authorizationIncompatible: (T?) -> Boolean,
    private val deleteIdentity: () -> Unit,
    private val generateIdentity: () -> Unit,
) {
    fun resolve(load: () -> TlsLoad<T>): T = when (val result = load()) {
        is TlsLoad.Loaded -> {
            val error = selfTest(result.entry)
            when {
                error == null -> result.entry
                regenerateIfConfirmed(error, result.entry) -> createAndLoad(load)
                else -> throw error
            }
        }
        is TlsLoad.Failed ->
            if (regenerateIfConfirmed(result.error, null)) createAndLoad(load) else throw result.error
        TlsLoad.Absent -> createAndLoad(load)
    }

    private fun regenerateIfConfirmed(error: Throwable, entry: T?): Boolean {
        if (!isPermanentInvalidation(error) && !authorizationIncompatible(entry)) return false
        deleteIdentity()
        return true
    }

    private fun createAndLoad(load: () -> TlsLoad<T>): T {
        generateIdentity()
        val result = load()
        if (result !is TlsLoad.Loaded) throw IllegalStateException("Android Keystore did not retain TLS identity")
        selfTest(result.entry)?.let { throw it }
        return result.entry
    }
}
