package dev.lucasdone.tvremote.agent.transport

/** Outcome of loading the TLS identity, before any regeneration decision. */
internal sealed interface TlsLoad<out T> {
    data class Usable<T>(val entry: T) : TlsLoad<T>
    data class Unusable<T>(val entry: T?, val error: Throwable) : TlsLoad<T>
    data object Absent : TlsLoad<Nothing>
}

/**
 * Decides whether a stored TLS identity may be replaced. Regeneration requires positive evidence — a
 * permanent invalidation or an authorization-config incompatibility. An absent identity is created on
 * first run; an unusable one without such evidence is preserved and its original error rethrown.
 */
internal class TlsIdentityRecovery<T : Any>(
    private val isPermanentInvalidation: (Throwable) -> Boolean,
    private val authorizationIncompatible: (T?) -> Boolean,
    private val deleteIdentity: () -> Unit,
    private val generateIdentity: () -> Unit,
) {
    fun resolve(load: () -> TlsLoad<T>): T = when (val result = load()) {
        is TlsLoad.Usable -> result.entry
        is TlsLoad.Absent -> {
            generateIdentity()
            reload(load)
        }
        is TlsLoad.Unusable -> {
            if (!isPermanentInvalidation(result.error) && !authorizationIncompatible(result.entry)) throw result.error
            deleteIdentity()
            generateIdentity()
            reload(load)
        }
    }

    private fun reload(load: () -> TlsLoad<T>): T =
        (load() as? TlsLoad.Usable<T>)?.entry
            ?: throw IllegalStateException("Android Keystore did not retain TLS identity")
}
