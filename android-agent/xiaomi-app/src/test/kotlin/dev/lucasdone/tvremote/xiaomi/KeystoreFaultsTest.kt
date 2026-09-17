package dev.lucasdone.tvremote.xiaomi

import dev.lucasdone.tvremote.agent.auth.KeystoreFailureAction
import dev.lucasdone.tvremote.agent.auth.authorizationIncompatible
import dev.lucasdone.tvremote.agent.auth.classifyKeystoreFailure
import dev.lucasdone.tvremote.agent.auth.isPermanentInvalidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException

class KeystoreFaultsTest {
    private class Permanent : InvalidKeyException()

    @Test fun missingPrivateKeyPurposeIsIncompatible() {
        assertTrue(incompatible(purposes = 0))
        assertTrue(incompatible(purposes = 1)) // ENCRYPT only, no DECRYPT
    }

    @Test fun missingPublicKeyPurposeAloneIsNotIncompatible() {
        // DECRYPT present, ENCRYPT absent: the private-key operation is covered, so not evidence.
        assertFalse(incompatible(purposes = 2))
        // SIGN present, VERIFY absent.
        assertFalse(
            authorizationIncompatible(
                purposes = 4, digests = setOf("SHA-256"), paddings = setOf("PKCS1"),
                requiredPurpose = 4, requiredDigest = "SHA-256", requiredPadding = "PKCS1",
            ),
        )
    }

    @Test fun missingDigestOrPaddingIsIncompatible() {
        assertTrue(incompatible(digests = setOf("SHA-1")))
        assertTrue(incompatible(paddings = setOf("PKCS1Padding")))
    }

    @Test fun extraPaddingIsCheckedWhenRequested() {
        assertTrue(
            authorizationIncompatible(
                purposes = 4, digests = setOf("SHA-256"), paddings = setOf("PKCS1"),
                requiredPurpose = 4, requiredDigest = "SHA-256", requiredPadding = "PKCS1", extraPadding = "PSS",
            ),
        )
        assertFalse(
            authorizationIncompatible(
                purposes = 4, digests = setOf("SHA-256"), paddings = setOf("PKCS1", "PSS"),
                requiredPurpose = 4, requiredDigest = "SHA-256", requiredPadding = "PKCS1", extraPadding = "PSS",
            ),
        )
    }

    @Test fun permanentInvalidationWalksTheCauseChainByType() {
        val permanent: (Throwable) -> Boolean = { it is Permanent }
        assertFalse(isPermanentInvalidation(InvalidKeyException("plain"), 23, permanent))
        assertTrue(isPermanentInvalidation(Permanent(), 23, permanent))
        assertTrue(isPermanentInvalidation(RuntimeException("wrapped", Permanent()), 23, permanent))
    }

    @Test fun permanentInvalidationIsIgnoredOnOldPlatforms() {
        val permanent: (Throwable) -> Boolean = { it is Permanent }
        assertFalse(isPermanentInvalidation(Permanent(), 19, permanent))
    }

    @Test fun classificationWalksTheCauseChain() {
        assertEquals(
            KeystoreFailureAction.KEY_REPAIR,
            classifyKeystoreFailure(RuntimeException("wrapped", InvalidKeyException("digest not authorized"))),
        )
        assertEquals(
            KeystoreFailureAction.KEY_REPAIR,
            classifyKeystoreFailure(RuntimeException("wrapped", UnrecoverableKeyException("lost"))),
        )
        assertEquals(
            KeystoreFailureAction.DROP_RECORD,
            classifyKeystoreFailure(RuntimeException("wrapped", BadPaddingException("corrupt"))),
        )
        assertEquals(KeystoreFailureAction.PROPAGATE, classifyKeystoreFailure(RuntimeException("unrelated")))
    }

    private fun incompatible(
        purposes: Int = 2,
        digests: Set<String> = setOf("SHA-256"),
        paddings: Set<String> = setOf("OAEPPadding"),
    ): Boolean = authorizationIncompatible(
        purposes = purposes,
        digests = digests,
        paddings = paddings,
        requiredPurpose = 2,
        requiredDigest = "SHA-256",
        requiredPadding = "OAEPPadding",
    )
}
