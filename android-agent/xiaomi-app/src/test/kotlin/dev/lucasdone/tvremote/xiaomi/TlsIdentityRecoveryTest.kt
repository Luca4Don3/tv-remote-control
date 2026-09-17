package dev.lucasdone.tvremote.xiaomi

import dev.lucasdone.tvremote.agent.transport.TlsIdentityRecovery
import dev.lucasdone.tvremote.agent.transport.TlsLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class PermanentMarker : Exception()

private fun Throwable.isPermanent(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is PermanentMarker) return true
        current = current.cause
        depth += 1
    }
    return false
}

class TlsIdentityRecoveryTest {
    private class FakeIdentity(val id: String)

    private class Fixture(private val incompatible: Boolean = false) {
        val events = mutableListOf<String>()
        var selfTestError: Throwable? = null
        val recovery = TlsIdentityRecovery<FakeIdentity>(
            selfTest = { events += "selfTest"; selfTestError },
            isPermanentInvalidation = { error -> events += "permanent"; error.isPermanent() },
            authorizationIncompatible = { events += "incompatible"; incompatible },
            deleteIdentity = { events += "delete" },
            generateIdentity = { events += "generate" },
        )
    }

    @Test fun loadedIdentityThatPassesSelfTestIsUsed() {
        val fixture = Fixture()
        val entry = FakeIdentity("existing")
        assertEquals("existing", fixture.recovery.resolve { TlsLoad.Loaded(entry) }.id)
        assertEquals(listOf("selfTest"), fixture.events)
    }

    @Test fun absentIdentityIsGeneratedOnFirstRun() {
        val fixture = Fixture()
        var first = true
        val result = fixture.recovery.resolve {
            if (first) {
                first = false
                TlsLoad.Absent
            } else {
                TlsLoad.Loaded(FakeIdentity("first-run"))
            }
        }
        assertEquals("first-run", result.id)
        assertEquals(listOf("generate", "selfTest"), fixture.events)
    }

    @Test fun selfTestPermanentErrorRegenerates() {
        val fixture = Fixture()
        fixture.selfTestError = PermanentMarker()
        var loaded = false
        val result = fixture.recovery.resolve {
            if (!loaded) {
                loaded = true
                TlsLoad.Loaded(FakeIdentity("old"))
            } else {
                fixture.selfTestError = null
                TlsLoad.Loaded(FakeIdentity("new"))
            }
        }
        assertEquals("new", result.id)
        assertEquals(listOf("selfTest", "permanent", "delete", "generate", "selfTest"), fixture.events)
    }

    @Test fun wrappedPermanentSelfTestErrorRegenerates() {
        val fixture = Fixture()
        fixture.selfTestError = RuntimeException("wrapped", PermanentMarker())
        var loaded = false
        val result = fixture.recovery.resolve {
            if (!loaded) {
                loaded = true
                TlsLoad.Loaded(FakeIdentity("old"))
            } else {
                fixture.selfTestError = null
                TlsLoad.Loaded(FakeIdentity("new"))
            }
        }
        assertEquals("new", result.id)
        assertTrue(fixture.events.contains("delete"))
    }

    @Test fun unknownSelfTestErrorPreservesIdentityAndThrows() {
        val fixture = Fixture()
        fixture.selfTestError = IllegalStateException("sign self-test failed")
        try {
            fixture.recovery.resolve { TlsLoad.Loaded(FakeIdentity("existing")) }
            fail("expected the original error to propagate")
        } catch (error: IllegalStateException) {
            assertEquals("sign self-test failed", error.message)
        }
        assertTrue(fixture.events.none { it == "delete" || it == "generate" })
    }

    @Test fun unknownSelfTestErrorWithIncompatibleAuthorizationRegenerates() {
        val fixture = Fixture(incompatible = true)
        fixture.selfTestError = IllegalStateException("no PSS")
        var loaded = false
        val result = fixture.recovery.resolve {
            if (!loaded) {
                loaded = true
                TlsLoad.Loaded(FakeIdentity("old"))
            } else {
                fixture.selfTestError = null
                TlsLoad.Loaded(FakeIdentity("new"))
            }
        }
        assertEquals("new", result.id)
        assertEquals(listOf("selfTest", "permanent", "incompatible", "delete", "generate", "selfTest"), fixture.events)
    }

    @Test fun loadFailureUnknownPreservesAndThrows() {
        val fixture = Fixture()
        try {
            fixture.recovery.resolve { TlsLoad.Failed(IllegalStateException("load failed")) }
            fail("expected the failure to propagate")
        } catch (error: IllegalStateException) {
            assertEquals("load failed", error.message)
        }
        assertFalse(fixture.events.contains("delete"))
        assertFalse(fixture.events.contains("generate"))
    }

    @Test fun loadFailureWithPermanentCauseRegenerates() {
        val fixture = Fixture()
        var loaded = false
        val result = fixture.recovery.resolve {
            if (!loaded) {
                loaded = true
                TlsLoad.Failed(RuntimeException("wrapped", PermanentMarker()))
            } else {
                TlsLoad.Loaded(FakeIdentity("new"))
            }
        }
        assertEquals("new", result.id)
        assertEquals(listOf("permanent", "delete", "generate", "selfTest"), fixture.events)
    }
}
