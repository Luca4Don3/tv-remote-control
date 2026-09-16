package dev.lucasdone.tvremote.xiaomi

import dev.lucasdone.tvremote.agent.auth.KeystoreFailureAction
import dev.lucasdone.tvremote.agent.auth.KeystoreRecovery
import dev.lucasdone.tvremote.agent.auth.classifyKeystoreFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

class KeystoreFailureTest {
    private class FakeStore(var keyUsable: Boolean = false) {
        val records = linkedMapOf("healthy" to "secret-a", "corrupt" to "secret-b")
        val events = mutableListOf<String>()
        var deleteFails = false

        val recovery = KeystoreRecovery(
            keyUsable = { events += "probe"; keyUsable },
            deleteKey = {
                events += "deleteKey"
                if (deleteFails) throw IllegalStateException("delete failed")
            },
            clearRecords = { events += "clearRecords"; records.clear() },
            removeRecord = { id -> events += "removeRecord:$id"; records.remove(id) },
        )
    }

    @Test fun keyLevelFailuresAreOnlyRepairCandidates() {
        assertEquals(KeystoreFailureAction.KEY_REPAIR, classifyKeystoreFailure(InvalidKeyException("digest not authorized")))
        assertEquals(KeystoreFailureAction.KEY_REPAIR, classifyKeystoreFailure(UnrecoverableKeyException("key lost")))
    }

    @Test fun corruptCiphertextRequestsRecordDrop() {
        assertEquals(KeystoreFailureAction.DROP_RECORD, classifyKeystoreFailure(AEADBadTagException("tag mismatch")))
        assertEquals(KeystoreFailureAction.DROP_RECORD, classifyKeystoreFailure(BadPaddingException("bad padding")))
        assertEquals(KeystoreFailureAction.DROP_RECORD, classifyKeystoreFailure(IllegalArgumentException("bad envelope")))
    }

    @Test fun transientOrUnexpectedFailuresPropagate() {
        assertEquals(KeystoreFailureAction.PROPAGATE, classifyKeystoreFailure(IllegalStateException("keystore busy")))
        assertEquals(KeystoreFailureAction.PROPAGATE, classifyKeystoreFailure(RuntimeException("unexpected")))
    }

    @Test fun usableKeyFailurePreservesDataAndRethrows() {
        val store = FakeStore(keyUsable = true)
        try {
            store.recovery.run("corrupt") { throw InvalidKeyException("temporary provider failure") }
            fail("expected the original failure to propagate")
        } catch (error: InvalidKeyException) {
            assertEquals("temporary provider failure", error.message)
        }
        assertEquals(listOf("probe"), store.events)
        assertEquals(mapOf("healthy" to "secret-a", "corrupt" to "secret-b"), store.records)
    }

    @Test fun confirmedUnusableKeyRepairsThenRetries() {
        val store = FakeStore(keyUsable = false)
        var attempts = 0
        val result = store.recovery.run("corrupt") {
            attempts += 1
            if (attempts == 1) throw InvalidKeyException("digest not authorized")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(listOf("probe", "deleteKey", "clearRecords"), store.events)
        assertTrue(store.records.isEmpty())
    }

    @Test fun deleteKeyFailurePropagatesBeforeRecordsAreCleared() {
        val store = FakeStore(keyUsable = false).apply { deleteFails = true }
        try {
            store.recovery.run("corrupt") { throw InvalidKeyException("digest not authorized") }
            fail("expected the delete failure to propagate")
        } catch (error: IllegalStateException) {
            assertEquals("delete failed", error.message)
        }
        assertEquals(listOf("probe", "deleteKey"), store.events)
        assertEquals(mapOf("healthy" to "secret-a", "corrupt" to "secret-b"), store.records)
    }

    @Test fun corruptRecordDropsOnlyThatRecord() {
        val store = FakeStore()
        assertNull(store.recovery.run("corrupt") { throw BadPaddingException("corrupt") })
        assertEquals(listOf("removeRecord:corrupt"), store.events)
        assertEquals(mapOf("healthy" to "secret-a"), store.records)
    }

    @Test fun propagateFailureTouchesNothing() {
        val store = FakeStore()
        try {
            store.recovery.run("corrupt") { throw IllegalStateException("keystore busy") }
            fail("expected the failure to propagate")
        } catch (error: IllegalStateException) {
            assertEquals("keystore busy", error.message)
        }
        assertTrue(store.events.isEmpty())
        assertEquals(mapOf("healthy" to "secret-a", "corrupt" to "secret-b"), store.records)
    }

    @Test fun writePathRepairsPreexistingBadAliasWithoutRecord() {
        val store = FakeStore(keyUsable = false)
        store.records.clear() // alias exists but no ciphertext has been stored yet
        var attempts = 0
        val written = store.recovery.run(null) {
            attempts += 1
            if (attempts == 1) throw InvalidKeyException("legacy alias lacks digest")
            "ciphertext"
        }
        assertEquals("ciphertext", written)
        assertEquals(listOf("probe", "deleteKey", "clearRecords"), store.events)
    }

    @Test fun writePathRecordDropWithoutIdRethrows() {
        val store = FakeStore()
        try {
            store.recovery.run(null) { throw BadPaddingException("corrupt") }
            fail("expected the write-path drop to rethrow")
        } catch (error: BadPaddingException) {
            assertEquals("corrupt", error.message)
        }
        assertTrue(store.events.isEmpty())
    }

    @Test fun readPathReturnsNullWhenTheRetryFindsNoStoredRecord() {
        // Mirrors the credential store: after a repair clears the records, a re-read yields null
        // instead of the stale ciphertext, so the read path must surface null rather than throw.
        val store = FakeStore(keyUsable = false)
        var attempts = 0
        val result = store.recovery.run("corrupt") {
            attempts += 1
            if (attempts == 1) throw InvalidKeyException("digest not authorized")
            null
        }
        assertNull(result)
        assertEquals(2, attempts)
        assertEquals(listOf("probe", "deleteKey", "clearRecords"), store.events)
    }
}
