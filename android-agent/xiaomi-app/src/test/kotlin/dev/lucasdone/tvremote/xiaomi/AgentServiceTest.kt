package dev.lucasdone.tvremote.xiaomi

import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.xiaomi.backend.BackendKind
import dev.lucasdone.tvremote.xiaomi.backend.KeyBackend
import org.junit.Assert.*
import org.junit.Test

class AgentServiceTest {
    private class ProbeBackend(private val events: MutableList<String>, private val onClose: () -> Unit = {}) : KeyBackend {
        override val kind = BackendKind.XIAOMI
        override val keys = setOf(LogicalKey.DPAD_UP)
        override var available = true
        override fun press(key: LogicalKey): AckStatus = AckStatus.SUCCESS
        override fun close() { events += "close:backend"; onClose() }
    }

    private class ProbeResource(
        private val name: String,
        private val events: MutableList<String>,
        private val onClose: () -> Unit = {},
    ) : AutoCloseable {
        override fun close() { events += "close:$name"; onClose() }
    }

    @Test fun shutdownDetachesEverythingBeforeClosing() {
        val events = mutableListOf<String>()
        val resources = AgentResources()
        val backend = ProbeBackend(events) { assertNull(resources.currentBackend()) }
        val control = ProbeResource("control", events) { assertNull(resources.control()) }
        val discovery = ProbeResource("discovery", events) { assertNull(resources.currentBackend()) }
        assertTrue(resources.install(control, discovery, backend))
        resources.shutdown()
        assertEquals(listOf("close:control", "close:discovery", "close:backend"), events)
    }

    @Test fun shutdownIsIdempotentAndClearsReferences() {
        val events = mutableListOf<String>()
        val resources = AgentResources()
        resources.install(ProbeResource("control", events), ProbeResource("discovery", events), ProbeBackend(events))
        resources.shutdown()
        resources.shutdown()
        assertEquals(listOf("close:control", "close:discovery", "close:backend"), events)
        assertTrue(resources.isClosed)
        assertNull(resources.control())
        assertNull(resources.currentBackend())
    }

    @Test fun installAfterShutdownClosesLateResources() {
        val events = mutableListOf<String>()
        val resources = AgentResources()
        resources.shutdown()
        assertFalse(resources.install(ProbeResource("control", events), ProbeResource("discovery", events), ProbeBackend(events)))
        assertEquals(listOf("close:control", "close:discovery", "close:backend"), events)
        assertNull(resources.currentBackend())
    }

    @Test fun backendRefreshClosesPreviousExactlyOnce() {
        val events = mutableListOf<String>()
        val resources = AgentResources()
        val first = ProbeBackend(events)
        resources.install(ProbeResource("control", events), ProbeResource("discovery", events), first)
        val second = ProbeBackend(events)
        assertSame(first, resources.swapBackend(second))
        assertSame(second, resources.currentBackend())
        assertEquals(listOf("close:backend"), events)
        assertSame(second, resources.swapBackend(null))
        assertEquals(listOf("close:backend", "close:backend"), events)
        assertNull(resources.currentBackend())
    }

    @Test fun failedProbeInstallsNothing() {
        val events = mutableListOf<String>()
        val resources = AgentResources()
        resources.install(ProbeResource("control", events), ProbeResource("discovery", events), null)
        assertNull(resources.swapBackend(null))
        assertNull(resources.currentBackend())
        assertTrue(events.isEmpty())
    }

    @Test fun swapAfterShutdownClosesReplacement() {
        val events = mutableListOf<String>()
        val resources = AgentResources()
        resources.shutdown()
        assertNull(resources.swapBackend(ProbeBackend(events)))
        assertEquals(listOf("close:backend"), events)
    }

    @Test fun detachDisarmsWithoutClosingUntilCloseDetachedRuns() {
        val events = mutableListOf<String>()
        val resources = AgentResources()
        resources.install(ProbeResource("control", events), ProbeResource("discovery", events), ProbeBackend(events))
        val detached = resources.detach()
        assertTrue(resources.isClosed)
        assertNull(resources.control())
        assertNull(resources.currentBackend())
        assertTrue(events.isEmpty())
        AgentResources.closeDetached(detached)
        assertEquals(listOf("close:control", "close:discovery", "close:backend"), events)
    }

    @Test fun detachForRebuildNullsReferencesButAllowsReinstall() {
        val events = mutableListOf<String>()
        val resources = AgentResources()
        resources.install(ProbeResource("control", events), ProbeResource("discovery", events), ProbeBackend(events))
        val detached = resources.detachForRebuild()
        assertFalse(resources.isClosed)
        assertNull(resources.control())
        assertNull(resources.currentBackend())
        assertTrue(events.isEmpty())
        // A rebuild can install fresh resources; the gate is not closed by a rebuild.
        assertTrue(resources.install(ProbeResource("control2", events), ProbeResource("discovery2", events), ProbeBackend(events)))
        AgentResources.closeDetached(detached)
        assertEquals(listOf("close:control", "close:discovery", "close:backend"), events)
    }

    @Test fun detachIsIdempotentAndReturnsNothingOnceDisarmed() {
        val events = mutableListOf<String>()
        val resources = AgentResources()
        resources.install(ProbeResource("control", events), ProbeResource("discovery", events), ProbeBackend(events))
        val first = resources.detach()
        assertTrue(first.isNotEmpty())
        assertTrue(resources.detach().isEmpty())
        AgentResources.closeDetached(first)
        assertEquals(listOf("close:control", "close:discovery", "close:backend"), events)
    }

    @Test fun controllerIdValidationMatchesCredentialStoreFormat() {
        assertTrue(isValidControllerId("0123456789abcdef0123456789abcdef"))
        assertTrue(isValidControllerId("A.b_c-d:e"))
        assertFalse(isValidControllerId(""))
        assertFalse(isValidControllerId("has space"))
        assertFalse(isValidControllerId("semi;colon"))
        assertFalse(isValidControllerId("x".repeat(129)))
    }
}
