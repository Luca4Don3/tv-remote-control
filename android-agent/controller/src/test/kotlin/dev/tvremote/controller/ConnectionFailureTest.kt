package dev.tvremote.controller

import dev.tvremote.controller.ui.ConnectionFailure
import dev.tvremote.controller.ui.FailureKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C3：只有网络断线自动重连；BUSY 允许手动重试；证书/凭据/协议类均不自动重试。 */
class ConnectionFailureTest {
    @Test
    fun autoReconnectOnlyForNetwork() {
        assertTrue(ConnectionFailure(FailureKind.NETWORK, "").retryable)
        assertFalse(ConnectionFailure(FailureKind.BUSY, "").retryable)
        assertFalse(ConnectionFailure(FailureKind.PROTOCOL, "").retryable)
        assertFalse(ConnectionFailure(FailureKind.UNKNOWN, "").retryable)
        assertFalse(ConnectionFailure(FailureKind.CERTIFICATE, "").retryable)
        assertFalse(ConnectionFailure(FailureKind.CREDENTIAL_REVOKED, "").retryable)
    }

    @Test
    fun manualRetryForNetworkAndBusyOnly() {
        assertTrue(ConnectionFailure(FailureKind.NETWORK, "").canRetry)
        assertTrue(ConnectionFailure(FailureKind.BUSY, "").canRetry)
        assertFalse(ConnectionFailure(FailureKind.CERTIFICATE, "").canRetry)
        assertFalse(ConnectionFailure(FailureKind.CREDENTIAL_REVOKED, "").canRetry)
        assertFalse(ConnectionFailure(FailureKind.PROTOCOL, "").canRetry)
        assertFalse(ConnectionFailure(FailureKind.UNKNOWN, "").canRetry)
    }
}
