package dev.lucasdone.tvremote.xiaomi

import dev.lucasdone.tvremote.xiaomi.backend.KeyBackend

/**
 * Owns the service's replaceable control/discovery/backend resources. References are detached
 * before closing so a concurrent callback never observes a half-closed resource, and every
 * resource is closed exactly once even when shutdown races with initialization.
 */
internal class AgentResources {
    private val lock = Any()
    private var control: AutoCloseable? = null
    private var discovery: AutoCloseable? = null
    private var backend: KeyBackend? = null
    private var closed = false

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /**
     * Installs the initial resources. When the service already shut down, the passed-in resources
     * are closed here and false is returned so a late [XiaomiAgentService.initialize] cannot leak
     * listeners or sockets.
     */
    fun install(control: AutoCloseable, discovery: AutoCloseable, backend: KeyBackend?): Boolean {
        val accepted = synchronized(lock) {
            if (closed) {
                false
            } else {
                this.control = control
                this.discovery = discovery
                this.backend = backend
                true
            }
        }
        if (!accepted) closeDetached(listOf(control, discovery, backend))
        return accepted
    }

    fun control(): AutoCloseable? = synchronized(lock) { control }

    fun currentBackend(): KeyBackend? = synchronized(lock) { backend }

    /** Installs the replacement before closing the previous backend; the old one closes exactly once. */
    fun swapBackend(replacement: KeyBackend?): KeyBackend? {
        var previous: KeyBackend? = null
        val installed = synchronized(lock) {
            if (closed) {
                false
            } else {
                previous = backend
                backend = replacement
                true
            }
        }
        if (!installed) {
            closeDetached(listOf(replacement))
            return null
        }
        previous?.close()
        return previous
    }

    /**
     * Closes the gate and detaches every reference without closing them, returning a snapshot for
     * the caller to close. Detaching synchronously lets [XiaomiAgentService.onDestroy] fail fast on
     * the main thread and close sockets on another thread.
     */
    fun detach(): List<AutoCloseable?> = synchronized(lock) {
        if (closed) return emptyList()
        closed = true
        listOf(control, discovery, backend).also {
            control = null
            discovery = null
            backend = null
        }
    }

    /** Detaches every reference, then closes in control → discovery → backend order. Idempotent. */
    fun shutdown() {
        closeDetached(detach())
    }

    /**
     * Detaches every reference for a rebuild without closing the gate, so a later [install] succeeds.
     * Unlike [detach] this is not terminal; callers must close the returned snapshot.
     */
    fun detachForRebuild(): List<AutoCloseable?> = synchronized(lock) {
        if (closed) return emptyList()
        listOf(control, discovery, backend).also {
            control = null
            discovery = null
            backend = null
        }
    }

    companion object {
        /** Closes a detached snapshot in order; every resource is closed even if one throws. */
        fun closeDetached(resources: List<AutoCloseable?>) {
            var failure: Throwable? = null
            for (resource in resources) {
                try {
                    resource?.close()
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                }
            }
            failure?.let { throw it }
        }
    }
}

/** Matches the controller id format accepted by the credential store. */
internal fun isValidControllerId(controllerId: String): Boolean = CONTROLLER_ID.matches(controllerId)

private val CONTROLLER_ID = Regex("[A-Za-z0-9._:-]{1,128}")
