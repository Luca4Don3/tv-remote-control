package dev.tvremote.controller

import dev.tvremote.agent.protocol.JsonValue
import dev.tvremote.agent.protocol.ProtocolCodec
import dev.tvremote.agent.protocol.ProtocolEnvelope
import dev.tvremote.controller.net.ConnectionTransport
import dev.tvremote.controller.session.ControllerSession
import dev.tvremote.controller.session.InFlightSessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R8：在途会话登记表的代数/归属语义（同一把锁内失效并抽取）。 */
class InFlightSessionRegistryTest {
    private class FakeTransport : ConnectionTransport {
        override val peerFingerprint: ByteArray = ByteArray(32)
        override fun nextRequestId(): String = "c-1"
        override fun send(requestId: String, sessionId: String, type: String, payload: JsonValue.ObjectValue): ProtocolEnvelope =
            ProtocolEnvelope(ProtocolCodec.VERSION, requestId, sessionId, 1L, type, payload)
        override fun receive(): ProtocolEnvelope? = null
        override fun close() = Unit
    }

    private fun session() = ControllerSession(connectionFactory = { _ -> FakeTransport() })

    @Test
    fun staleRegistrationIsRejected() {
        val closed = mutableListOf<ControllerSession>()
        val registry = InFlightSessionRegistry { closed += it }
        val oldToken = registry.nextGeneration()
        registry.invalidateAndDrain() // 旧 token 失效
        assertFalse("过期 token 的登记必须被拒绝", registry.register(oldToken, session()))
        assertTrue("被拒的会话不被登记表关闭（由调用方关闭）", closed.isEmpty())
    }

    @Test
    fun invalidateAndDrainClosesInFlight() {
        val closed = mutableListOf<ControllerSession>()
        val registry = InFlightSessionRegistry { closed += it }
        val token = registry.nextGeneration()
        val a = session()
        assertTrue(registry.register(token, a))
        registry.invalidateAndDrain()
        assertEquals(listOf(a), closed)
        assertFalse("drain 后旧 token 失效", registry.isCurrent(token))
    }

    @Test
    fun clearOnlyClearsMatchingInstance() {
        val closed = mutableListOf<ControllerSession>()
        val registry = InFlightSessionRegistry { closed += it }
        val token = registry.nextGeneration()
        val a = session()
        assertTrue(registry.register(token, a))
        registry.clear(token, session()) // 不同实例：不应清空 a
        registry.invalidateAndDrain()
        assertTrue("a 仍应被 drain 关闭", closed.contains(a))
    }

    @Test
    fun registerReplacesInFlightAndClosesOld() {
        val closed = mutableListOf<ControllerSession>()
        val registry = InFlightSessionRegistry { closed += it }
        val token = registry.nextGeneration()
        val first = session()
        val second = session()
        registry.register(token, first)
        registry.register(token, second)
        assertTrue("被替换的旧在途会话应关闭", closed.contains(first))
        assertFalse(closed.contains(second))
    }

    @Test
    fun invalidateAndDrainReturnsNewGenerationAndOldBecomesStale() {
        val registry = InFlightSessionRegistry { }
        val old = registry.nextGeneration()
        val newToken = registry.invalidateAndDrain()
        assertTrue(registry.isCurrent(newToken))
        assertFalse(registry.isCurrent(old))
        assertEquals(newToken, registry.currentGeneration())
    }
}
