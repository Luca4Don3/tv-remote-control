package dev.tvremote.agent.adapter

import dev.tvremote.agent.adapter.xiaomi.XiaomiKeyBackend
import dev.tvremote.agent.adapter.xiaomi.XiaomiLoopbackClient
import dev.tvremote.agent.command.CommandDispatcher
import dev.tvremote.agent.command.KeyStateTracker
import dev.tvremote.agent.device.CapabilityDetector
import dev.tvremote.agent.device.KeyCapability
import dev.tvremote.agent.device.XiaomiProfile
import dev.tvremote.agent.model.AckStatus
import dev.tvremote.agent.model.KeyEventCommand
import dev.tvremote.agent.model.KeyState
import dev.tvremote.agent.model.LogicalKey
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BackendTest {
    private class FakeBackend : KeyBackend {
        override val kind = BackendKind.VENDOR
        override val keys = XiaomiLoopbackClient.KEY_CODES.keys
        override var available = true
        val pressed = mutableListOf<LogicalKey>()
        override fun press(key: LogicalKey): AckStatus { pressed += key; return AckStatus.SUCCESS }
    }
    private class FakeProvider(
        override val id: String,
        override val displayName: String = id,
        private val result: () -> KeyBackend?,
    ) : BackendProvider {
        override fun probe(): KeyBackend? = result()
    }
    private class FakeFallbackExecutor(
        private val keys: Set<LogicalKey>,
        val handled: MutableList<LogicalKey>,
    ) : dev.tvremote.agent.command.CommandExecutor {
        override fun supports(key: LogicalKey) = key in keys
        override fun execute(command: KeyEventCommand): AckStatus { handled += command.key; return AckStatus.SUCCESS }
    }
    @Test fun repeatAndReleaseProduceAtomicPressesWithoutStuckState() {
        val backend = FakeBackend()
        val dispatcher = CommandDispatcher(KeyStateTracker(), listOf(BackendKeyExecutor { backend }))
        fun send(sequence: Long, state: KeyState, count: Int = 0) = dispatcher.dispatch(KeyEventCommand(sequence, LogicalKey.DPAD_RIGHT, state, count)).status
        assertEquals(AckStatus.REJECTED, send(1, KeyState.REPEAT, 1))
        assertEquals(AckStatus.SUCCESS, send(2, KeyState.DOWN))
        assertEquals(AckStatus.SUCCESS, send(3, KeyState.REPEAT, 1))
        assertEquals(AckStatus.SUCCESS, send(4, KeyState.UP))
        assertEquals(2, backend.pressed.size)
        assertEquals(AckStatus.SUCCESS, send(5, KeyState.DOWN))
        dispatcher.disconnect()
        assertEquals(AckStatus.REJECTED, send(6, KeyState.REPEAT, 2))
        assertEquals(3, backend.pressed.size)
    }
    @Test fun stoppedBackendCannotExecuteAndReleaseDoesNotNeedNetwork() {
        val backend = FakeBackend().apply { available = false }
        val executor = BackendKeyExecutor { backend }
        assertEquals(AckStatus.EXECUTION_FAILED, executor.execute(KeyEventCommand(1, LogicalKey.DPAD_UP, KeyState.PRESS, 0)))
        assertEquals(AckStatus.SUCCESS, executor.execute(KeyEventCommand(2, LogicalKey.DPAD_UP, KeyState.UP, 0)))
        assertTrue(backend.pressed.isEmpty())
    }
    @Test fun homeCannotBeRepeatedAndPowerIsUnsupported() {
        val backend = FakeBackend(); val executor = BackendKeyExecutor { backend }
        assertEquals(AckStatus.UNSUPPORTED, executor.execute(KeyEventCommand(1, LogicalKey.HOME, KeyState.REPEAT, 1)))
        assertEquals(AckStatus.UNSUPPORTED, executor.execute(KeyEventCommand(2, LogicalKey.POWER, KeyState.PRESS, 0)))
        assertTrue(backend.pressed.isEmpty())
    }
    @Test fun xiaomiProbeIsReadOnlyAndCommandsAreWhitelisted() {
        val paths = mutableListOf<String>()
        val client = XiaomiLoopbackClient { path -> paths += path; "{\"status\":0}".toByteArray() }
        assertTrue(client.probe())
        assertEquals(listOf("/request?action=isalive"), paths)
        assertTrue(client.press(LogicalKey.DPAD_LEFT))
        assertEquals("/controller?action=keyevent&keycode=left", paths.last())
        assertFalse(client.press(LogicalKey.POWER))
        assertEquals(2, paths.size)
    }
    @Test fun xiaomiRequiresExplicitSuccessStatus() {
        for (json in listOf("{}", "{\"status\":1}", "{\"status\":\"0\"}"))
            assertFalse(XiaomiLoopbackClient { json.toByteArray() }.probe())
    }
    @Test fun failedXiaomiCommandIsNotRetriedOrSwitched() {
        var calls = 0
        val backend = XiaomiKeyBackend(XiaomiLoopbackClient { calls++; throw IOException("test") })
        assertEquals(AckStatus.EXECUTION_FAILED, backend.press(LogicalKey.DPAD_DOWN))
        assertFalse(backend.available)
        assertEquals(AckStatus.EXECUTION_FAILED, backend.press(LogicalKey.DPAD_DOWN))
        assertEquals(1, calls)
    }
    @Test fun adbRequiresExplicitOptInAndXiaomiHasPriority() {
        assertEquals(BackendKind.NONE, BackendSelection.select(false, false, true))
        assertEquals(BackendKind.ADB, BackendSelection.select(false, true, true))
        assertEquals(BackendKind.VENDOR, BackendSelection.select(true, true, true))
        assertEquals(BackendKind.NONE, BackendSelection.select(false, true, false))
    }
    @Test fun providerProbeFollowsOrderAndIsolatesFailures() {
        val failures = mutableListOf<String>()
        val providers = listOf(
            FakeProvider("throws") { throw IOException("down") },
            FakeProvider("absent") { null },
            FakeProvider("ready") { FakeBackend() },
        )
        val selected = LocalBackends.probeFirst(providers) { provider, _ -> failures += provider.id }
        assertEquals("ready", selected?.first?.id)
        assertEquals(listOf("throws"), failures)
    }
    @Test fun providerProbeReturnsNullWhenNoChannelIsAvailable() {
        assertNull(LocalBackends.probeFirst(listOf(FakeProvider("absent") { null })))
    }
    @Test fun xiaomiLoopbackIsTheDefaultPreferredProvider() {
        assertEquals("xiaomi-loopback", XiaomiProfile.backendProviders.first().id)
    }
    @Test fun executorResolvesBackendOnEveryCall() {
        var current: KeyBackend? = null
        val executor = BackendKeyExecutor { current }
        assertFalse(executor.supports(LogicalKey.DPAD_UP))
        current = FakeBackend()
        assertTrue(executor.supports(LogicalKey.DPAD_UP))
    }
    @Test fun failedVendorBackendFallsThroughToSystemExecutor() {
        val vendor = FakeBackend().apply { available = false }
        val handled = mutableListOf<LogicalKey>()
        val dispatcher = CommandDispatcher(KeyStateTracker(), listOf(
            BackendKeyExecutor { vendor },
            FakeFallbackExecutor(setOf(LogicalKey.VOLUME_UP), handled),
        ))
        assertEquals(AckStatus.SUCCESS, dispatcher.dispatch(KeyEventCommand(1, LogicalKey.VOLUME_UP, KeyState.PRESS, 0)).status)
        assertEquals(listOf(LogicalKey.VOLUME_UP), handled)
    }
    @Test fun failedVendorBackendReportsUnsupportedForDpad() {
        val vendor = FakeBackend().apply { available = false }
        val dispatcher = CommandDispatcher(KeyStateTracker(), listOf(BackendKeyExecutor { vendor }))
        assertEquals(AckStatus.UNSUPPORTED, dispatcher.dispatch(KeyEventCommand(1, LogicalKey.DPAD_UP, KeyState.PRESS, 0)).status)
    }
    @Test fun failedBackendDegradesToUnsupportedAndNeverReportsSupported() {
        val live = FakeBackend()
        val dead = FakeBackend().apply { available = false }
        assertEquals(KeyCapability.BEST_EFFORT, CapabilityDetector.keyState(LogicalKey.DPAD_UP, live, false))
        assertEquals(KeyCapability.UNSUPPORTED, CapabilityDetector.keyState(LogicalKey.DPAD_UP, dead, false))
        assertEquals(KeyCapability.UNSUPPORTED, CapabilityDetector.keyState(LogicalKey.DPAD_UP, null, false))
        assertEquals(KeyCapability.BEST_EFFORT, CapabilityDetector.keyState(LogicalKey.VOLUME_UP, null, false))
        assertEquals(KeyCapability.UNSUPPORTED, CapabilityDetector.keyState(LogicalKey.VOLUME_UP, null, true))
        assertEquals(KeyCapability.BEST_EFFORT, CapabilityDetector.keyState(LogicalKey.MEDIA_NEXT, null, false))
        for (key in LogicalKey.values())
            for (backend in listOf(null, live, dead))
                assertNotEquals(KeyCapability.SUPPORTED, CapabilityDetector.keyState(key, backend, false))
    }
}
