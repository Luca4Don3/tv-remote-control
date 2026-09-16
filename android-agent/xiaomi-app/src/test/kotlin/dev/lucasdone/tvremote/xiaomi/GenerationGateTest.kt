package dev.lucasdone.tvremote.xiaomi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationGateTest {
    @Test fun newInstanceInvalidatesPreviousToken() {
        val gate = GenerationGate()
        val first = gate.begin()
        assertTrue(gate.isCurrent(first))
        val second = gate.begin()
        assertFalse(gate.isCurrent(first)) // a stopped instance can no longer write shared state
        assertTrue(gate.isCurrent(second))
    }

    @Test fun beginIsMonotonic() {
        val gate = GenerationGate()
        assertTrue(gate.begin() < gate.begin())
    }
}
