package dev.lucasdone.tvremote.agent.transport

import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

object TlsPolicy {
    private val ALLOWED_PROTOCOLS = setOf("TLSv1.2", "TLSv1.3")
    private val ALLOWED_CIPHERS = setOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    )

    data class Probe(val tls12Available: Boolean, val secureCipherCount: Int) {
        val networkControlAvailable: Boolean get() = tls12Available && secureCipherCount > 0
    }

    fun probe(): Probe = try {
        val parameters = SSLContext.getInstance("TLS").apply { init(null, null, null) }.supportedSSLParameters
        Probe(
            tls12Available = "TLSv1.2" in parameters.protocols,
            secureCipherCount = parameters.cipherSuites.count { it in ALLOWED_CIPHERS },
        )
    } catch (_: Exception) {
        Probe(tls12Available = false, secureCipherCount = 0)
    }

    fun configure(serverSocket: SSLServerSocket) {
        val protocols = serverSocket.supportedProtocols.filter { it in ALLOWED_PROTOCOLS }
        if ("TLSv1.2" !in protocols) throw IllegalStateException("TLS 1.2 is unavailable")
        val ciphers = serverSocket.supportedCipherSuites.filter { it in ALLOWED_CIPHERS }
        if (ciphers.isEmpty()) throw IllegalStateException("no approved TLS cipher is available")
        serverSocket.enabledProtocols = protocols.toTypedArray()
        serverSocket.enabledCipherSuites = ciphers.toTypedArray()
        serverSocket.needClientAuth = false
        serverSocket.useClientMode = false
    }

    fun configure(socket: Socket): SSLSocket {
        val tlsSocket = socket as? SSLSocket ?: throw IllegalArgumentException("socket is not TLS")
        val protocols = tlsSocket.supportedProtocols.filter { it in ALLOWED_PROTOCOLS }
        if ("TLSv1.2" !in protocols) throw IllegalStateException("TLS 1.2 is unavailable")
        val ciphers = tlsSocket.supportedCipherSuites.filter { it in ALLOWED_CIPHERS }
        if (ciphers.isEmpty()) throw IllegalStateException("no approved TLS cipher is available")
        tlsSocket.enabledProtocols = protocols.toTypedArray()
        tlsSocket.enabledCipherSuites = ciphers.toTypedArray()
        tlsSocket.useClientMode = false
        return tlsSocket
    }
}
