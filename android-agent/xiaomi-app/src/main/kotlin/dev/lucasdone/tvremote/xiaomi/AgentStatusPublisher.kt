package dev.lucasdone.tvremote.xiaomi

import dev.lucasdone.tvremote.agent.auth.ControllerSummary
import dev.lucasdone.tvremote.agent.auth.PairingSas
import dev.lucasdone.tvremote.agent.auth.PairingWindow
import dev.lucasdone.tvremote.agent.service.AgentStatusRegistry

/**
 * Publishes service callbacks only while the owning service is active. Closing a ControlServer
 * fires late callbacks (e.g. onControllerDisconnected); without this gate they would overwrite the
 * STOPPED state after shutdown, or leak a previous instance's state into a fast restart.
 */
internal class AgentStatusPublisher(
    private val isActive: () -> Boolean,
    private val isCurrentInstance: () -> Boolean = { true },
) {
    private val lock = Any()
    private var terminated = false

    fun starting() = active { AgentStatusRegistry.starting() }
    fun listening() = active { AgentStatusRegistry.listening() }
    fun pairingWindow(window: PairingWindow) = active { AgentStatusRegistry.pairingWindow(window) }
    fun pairingSas(details: PairingSas) = active { AgentStatusRegistry.pairingSas(details) }
    fun pairingClosed(pairingId: String) = active { AgentStatusRegistry.pairingClosed(pairingId) }
    fun expirePairing(expiresAtMs: Long) = active { AgentStatusRegistry.expirePairing(expiresAtMs) }
    fun pairedControllers(controllers: List<ControllerSummary>) = active { AgentStatusRegistry.pairedControllers(controllers) }
    fun connected(controllerName: String) = active { AgentStatusRegistry.connected(controllerName) }
    fun disconnected() = active { AgentStatusRegistry.disconnected() }
    fun failed(reason: String) = active { AgentStatusRegistry.failed(reason) }

    /**
     * Terminal state under the same lock, so no in-flight callback can publish after it. A superseded
     * instance (a newer service already took over) must not publish STOPPED over the new instance.
     */
    fun stopped() = synchronized(lock) {
        terminated = true
        if (isCurrentInstance()) AgentStatusRegistry.stopped()
    }

    private fun active(action: () -> Unit) {
        synchronized(lock) {
            if (terminated || !isActive() || !isCurrentInstance()) return
            action()
        }
    }
}
