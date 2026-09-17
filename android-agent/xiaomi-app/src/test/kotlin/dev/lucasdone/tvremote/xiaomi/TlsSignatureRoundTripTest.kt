package dev.lucasdone.tvremote.xiaomi

import dev.lucasdone.tvremote.agent.transport.SignatureVerificationFailedException
import dev.lucasdone.tvremote.agent.transport.tlsSignatureRoundTripError
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import java.security.KeyPair
import java.security.KeyPairGenerator

class TlsSignatureRoundTripTest {
    @Test fun matchingKeyAndPublicKeyVerify() {
        val pair = rsa()
        assertNull(tlsSignatureRoundTripError(pair.private, pair.public, "SHA256withRSA"))
    }

    @Test fun mismatchedPublicKeyIsReportedAsFailure() {
        // The private key does not match the certificate public key: verification returns false and
        // must not be treated as success.
        val error = tlsSignatureRoundTripError(rsa().private, rsa().public, "SHA256withRSA")
        assertTrue(error is SignatureVerificationFailedException)
    }

    @Test fun signingFailurePreservesTheOriginalException() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val error = tlsSignatureRoundTripError(ec.private, rsa().public, "SHA256withRSA")
        assertTrue(error is InvalidKeyException)
    }

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}
