package dev.tvremote.agent.adapter

/**
 * A vendor-local control channel. Adding a new brand means adding one provider and registering
 * it with the active device profile; the service and protocol stay unchanged. A successful probe is
 * a connectivity signal, never model certification.
 */
interface BackendProvider {
    val id: String

    /** Vendor-neutral label for the TV UI. */
    val displayName: String

    /** Returns a ready backend, or null when this channel is unavailable. Never executes commands. */
    fun probe(): KeyBackend?
}

object LocalBackends {
    /**
     * Probes providers in preference order. A provider that throws is reported through [onFailure]
     * and skipped, so one broken vendor channel can never block the others.
     */
    fun probeFirst(
        providers: List<BackendProvider>,
        onFailure: (BackendProvider, Exception) -> Unit = { _, _ -> },
    ): Pair<BackendProvider, KeyBackend>? {
        for (provider in providers) {
            val backend = try {
                provider.probe()
            } catch (error: Exception) {
                onFailure(provider, error)
                continue
            } ?: continue
            return provider to backend
        }
        return null
    }
}
