package dev.lucasdone.tvremote.agent.transport

import android.os.Build
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

    // API 19/20 兼容性回退套件：老设备安全提供者可能不支持任何 AEAD（GCM 部分固件不支持，
    // ChaCha20/TLS1.3 需 API 26/29）。安全权衡：老设备无 AEAD 支持时优先保证可用性，
    // 仅允许这一种广泛支持的 TLS1.2 CBC 套件；API 21+ 严格只允许 AEAD。
    internal const val FALLBACK_CBC_CIPHER = "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"

    data class Probe(val tls12Available: Boolean, val secureCipherCount: Int) {
        val networkControlAvailable: Boolean get() = tls12Available && secureCipherCount > 0
    }

    internal fun selectCiphers(supported: Collection<String>, sdkInt: Int): List<String> {
        val aead = supported.filter { it in ALLOWED_CIPHERS }
        if (aead.isNotEmpty() || sdkInt >= 21) return aead
        // API 19/20：无 AEAD 可用时回退到唯一广泛支持的 TLS1.2 CBC 套件，避免完全丧失遥控链路。
        return supported.filter { it == FALLBACK_CBC_CIPHER }
    }

    internal fun selectProtocols(supported: Collection<String>): List<String> {
        val protocols = supported.filter { it in ALLOWED_PROTOCOLS }
        if ("TLSv1.2" !in protocols) throw IllegalStateException("TLS 1.2 is unavailable")
        return protocols
    }

    internal fun requireApprovedCiphers(supported: Collection<String>, sdkInt: Int): List<String> =
        selectCiphers(supported, sdkInt).also {
            if (it.isEmpty()) throw IllegalStateException("no approved TLS cipher is available")
        }

    fun probe(sdkInt: Int = Build.VERSION.SDK_INT): Probe = try {
        val parameters = SSLContext.getInstance("TLS").apply { init(null, null, null) }.supportedSSLParameters
        Probe(
            tls12Available = "TLSv1.2" in parameters.protocols,
            // 计数与 configure() 的选择逻辑一致，API 19/20 无 AEAD 时计入回退套件。
            secureCipherCount = selectCiphers(parameters.cipherSuites.toList(), sdkInt).size,
        )
    } catch (_: Exception) {
        Probe(tls12Available = false, secureCipherCount = 0)
    }

    fun configure(serverSocket: SSLServerSocket, sdkInt: Int = Build.VERSION.SDK_INT) {
        val protocols = selectProtocols(serverSocket.supportedProtocols.toList())
        val ciphers = requireApprovedCiphers(serverSocket.supportedCipherSuites.toList(), sdkInt)
        serverSocket.enabledProtocols = protocols.toTypedArray()
        serverSocket.enabledCipherSuites = ciphers.toTypedArray()
        serverSocket.needClientAuth = false
        serverSocket.useClientMode = false
    }

    fun configure(socket: Socket, sdkInt: Int = Build.VERSION.SDK_INT): SSLSocket {
        val tlsSocket = socket as? SSLSocket ?: throw IllegalArgumentException("socket is not TLS")
        val protocols = selectProtocols(tlsSocket.supportedProtocols.toList())
        val ciphers = requireApprovedCiphers(tlsSocket.supportedCipherSuites.toList(), sdkInt)
        tlsSocket.enabledProtocols = protocols.toTypedArray()
        tlsSocket.enabledCipherSuites = ciphers.toTypedArray()
        tlsSocket.useClientMode = false
        return tlsSocket
    }
}
