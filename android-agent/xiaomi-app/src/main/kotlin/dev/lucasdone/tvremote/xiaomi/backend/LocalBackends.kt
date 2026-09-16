package dev.lucasdone.tvremote.xiaomi.backend

/**
 * A vendor-local control channel. Adding a new brand means adding one provider and registering
 * it in [LocalBackends.defaults]; the service and protocol stay unchanged. A successful probe is
 * a connectivity signal, never model certification.
 */
interface BackendProvider {
    val id: String

    /** Vendor-neutral label for the TV UI. */
    val displayName: String

    /** Returns a ready backend, or null when this channel is unavailable. Never executes commands. */
    fun probe(): KeyBackend?
}

/** The only known vendor loopback channel today: Xiaomi firmware's 127.0.0.1:6095 surface. */
object XiaomiLoopbackProvider : BackendProvider {
    override val id = "xiaomi-loopback"
    override val displayName = "本机接口"
    override fun probe(): KeyBackend? {
        val client = XiaomiLoopbackClient()
        return if (client.probe()) XiaomiKeyBackend(client) else null
    }
}

object LocalBackends {
    fun defaults(): List<BackendProvider> = listOf(XiaomiLoopbackProvider)

    /**
     * Probes providers in preference order. A provider that throws is reported through [onFailure]
     * and skipped, so one broken vendor channel can never block the others.
     */
    fun probeFirst(
        providers: List<BackendProvider> = defaults(),
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
