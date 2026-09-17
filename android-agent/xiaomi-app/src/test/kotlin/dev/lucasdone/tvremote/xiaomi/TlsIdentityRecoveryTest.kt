package dev.lucasdone.tvremote.xiaomi

import dev.lucasdone.tvremote.agent.transport.TlsIdentityRecovery
import dev.lucasdone.tvremote.agent.transport.TlsLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TlsIdentityRecoveryTest {
    private class FakeIdentity(val id: String)

    private class Fixture(private val permanent: Boolean = false, private val incompatible: Boolean = false) {
        val events = mutableListOf<String>()
        val recovery = TlsIdentityRecovery<FakeIdentity>(
            isPermanentInvalidation = { events += "permanent"; permanent },
            authorizationIncompatible = { events += "incompatible"; incompatible },
            deleteIdentity = { events += "delete" },
            generateIdentity = { events += "generate" },
        )
    }

    @Test fun absentIdentityIsGeneratedOnFirstRun() {
        val fixture = Fixture()
        var first = true
        val result = fixture.recovery.resolve {
            if (first) {
                first = false
                TlsLoad.Absent
            } else {
                TlsLoad.Usable(FakeIdentity("first-run"))
            }
        }
        assertEquals("first-run", result.id)
        assertEquals(listOf("generate"), fixture.events)
    }

    @Test fun unknownFailurePreservesIdentity() {
        val fixture = Fixture(permanent = false, incompatible = false)
        try {
            fixture.recovery.resolve { TlsLoad.Unusable(FakeIdentity("existing"), IllegalStateException("self-test failed")) }
            fail("expected the error to propagate")
        } catch (error: IllegalStateException) {
            assertEquals("self-test failed", error.message)
        }
        assertFalse(fixture.events.contains("delete"))
        assertFalse(fixture.events.contains("generate"))
    }

    @Test fun permanentInvalidationRegenerates() {
        val fixture = Fixture(permanent = true)
        var loaded = false
        val result = fixture.recovery.resolve {
            if (!loaded) {
                loaded = true
                TlsLoad.Unusable(FakeIdentity("old"), IllegalStateException("invalidated"))
            } else {
                TlsLoad.Usable(FakeIdentity("new"))
            }
        }
        assertEquals("new", result.id)
        assertEquals(listOf("permanent", "delete", "generate"), fixture.events)
    }

    @Test fun authorizationIncompatibleRegenerates() {
        val fixture = Fixture(permanent = false, incompatible = true)
        var loaded = false
        val result = fixture.recovery.resolve {
            if (!loaded) {
                loaded = true
                TlsLoad.Unusable(FakeIdentity("old"), IllegalStateException("no PSS"))
            } else {
                TlsLoad.Usable(FakeIdentity("new"))
            }
        }
        assertEquals("new", result.id)
        assertEquals(listOf("permanent", "incompatible", "delete", "generate"), fixture.events)
    }

    @Test fun loadFailureThenRecoveryKeepsIdentityAndFingerprintSlot() {
        val fixture = Fixture()
        val existing = FakeIdentity("existing")
        try {
            fixture.recovery.resolve { TlsLoad.Unusable(existing, IllegalStateException("load failed")) }
            fail("expected preserve")
        } catch (_: IllegalStateException) {
        }
        // A later, healthy load reuses the same identity: no delete, no regenerate.
        val result = fixture.recovery.resolve { TlsLoad.Usable(existing) }
        assertEquals("existing", result.id)
        assertTrue(fixture.events.none { it == "delete" || it == "generate" })
    }

    @Test fun signingFailureThenRecoveryKeepsIdentity() {
        val fixture = Fixture()
        val existing = FakeIdentity("existing")
        try {
            fixture.recovery.resolve { TlsLoad.Unusable(existing, IllegalStateException("sign self-test failed")) }
            fail("expected preserve")
        } catch (_: IllegalStateException) {
        }
        val result = fixture.recovery.resolve { TlsLoad.Usable(existing) }
        assertEquals("existing", result.id)
        assertTrue(fixture.events.none { it == "delete" || it == "generate" })
    }
}
