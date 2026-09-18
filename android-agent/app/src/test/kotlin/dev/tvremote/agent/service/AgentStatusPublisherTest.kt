package dev.tvremote.agent.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AgentStatusPublisherTest {
    @Before fun reset() = AgentStatusRegistry.stopped()
    @After fun cleanup() = AgentStatusRegistry.stopped()

    @Test fun lateCallbackAfterStopDoesNotResurrectListening() {
        val active = AtomicBoolean(true)
        val publisher = AgentStatusPublisher({ active.get() })
        publisher.connected("Phone")
        assertEquals(AgentNetworkState.CONNECTED, AgentStatusRegistry.snapshot().networkState)

        active.set(false) // the service detached its resources and published STOPPED
        publisher.stopped()
        assertEquals(AgentNetworkState.STOPPED, AgentStatusRegistry.snapshot().networkState)

        publisher.disconnected() // late ControlServer close callback
        assertEquals(AgentNetworkState.STOPPED, AgentStatusRegistry.snapshot().networkState)
    }

    @Test fun staleInstanceCannotOverwriteRestartedServiceState() {
        val firstActive = AtomicBoolean(true)
        val first = AgentStatusPublisher({ firstActive.get() })
        first.connected("Phone")
        firstActive.set(false)

        val second = AgentStatusPublisher({ true })
        second.listening()
        first.disconnected() // previous instance's late callback

        assertEquals(AgentNetworkState.LISTENING, AgentStatusRegistry.snapshot().networkState)
    }

    @Test fun supersededInstanceCannotStopANewerInstance() {
        val current = AtomicBoolean(true)
        val publisher = AgentStatusPublisher({ true }, { current.get() })
        publisher.listening()
        assertEquals(AgentNetworkState.LISTENING, AgentStatusRegistry.snapshot().networkState)

        current.set(false) // a newer instance took over the process-global state
        publisher.connected("Phone")
        publisher.stopped()

        assertEquals(AgentNetworkState.LISTENING, AgentStatusRegistry.snapshot().networkState)
    }

    @Test fun stopIsAtomicWithInFlightCallback() {
        val checked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val publisher = AgentStatusPublisher({
            checked.countDown()
            proceed.await(2, TimeUnit.SECONDS)
            true
        })
        val callback = Thread { publisher.connected("Phone") }
        callback.start()
        assertTrue(checked.await(2, TimeUnit.SECONDS))

        val stopFinished = CountDownLatch(1)
        val stopper = Thread {
            publisher.stopped()
            stopFinished.countDown()
        }
        stopper.start()
        // The callback already passed the active check, so stopped() must wait for it to publish.
        assertFalse(stopFinished.await(200, TimeUnit.MILLISECONDS))

        proceed.countDown()
        callback.join(2_000)
        stopper.join(2_000)
        assertFalse(callback.isAlive)
        assertFalse(stopper.isAlive)
        assertEquals(AgentNetworkState.STOPPED, AgentStatusRegistry.snapshot().networkState)
    }
}
