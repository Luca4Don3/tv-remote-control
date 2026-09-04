package dev.lucasdone.tvremote.controller.net

import android.util.Log
import dev.lucasdone.tvremote.agent.protocol.FrameCodec
import dev.lucasdone.tvremote.agent.protocol.Hex
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.ProtocolCodec
import dev.lucasdone.tvremote.agent.protocol.ProtocolEnvelope
import dev.lucasdone.tvremote.agent.protocol.jsonBoolean
import dev.lucasdone.tvremote.agent.protocol.jsonLong
import dev.lucasdone.tvremote.agent.protocol.jsonObject
import dev.lucasdone.tvremote.agent.protocol.jsonString
import dev.lucasdone.tvremote.agent.protocol.requireLong
import dev.lucasdone.tvremote.agent.protocol.requireObject
import dev.lucasdone.tvremote.agent.protocol.requireString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 与电视端 TLS 控制链路（端口 47832）的客户端连接。
 *
 * 证书信任策略：TOFU（首次接触信任并保存 SHA-256 指纹）；配对后固定指纹，
 * 不匹配立即断开（防 MITM 换证）。
 */
class TvConnection private constructor(
    private val socket: SSLSocket,
    val peerFingerprint: ByteArray,
) : AutoCloseable {
    private val inboundSequence = AtomicLong(0)
    private val outboundSequence = AtomicLong(0)

    val input: InputStream = socket.inputStream
    val output: OutputStream = socket.outputStream

    fun nextRequestId(): String = "c-${outboundSequence.incrementAndGet()}"

    @Synchronized
    fun send(requestId: String, sessionId: String, type: String, payload: JsonValue.ObjectValue): ProtocolEnvelope {
        val sequence = outboundSequence.incrementAndGet()
        val envelope = ProtocolEnvelope(
            protocolVersion = ProtocolCodec.VERSION,
            requestId = requestId,
            sessionId = sessionId,
            sequence = sequence,
            type = type,
            payload = payload,
        )
        FrameCodec.write(output, ProtocolCodec.encode(envelope))
        return envelope
    }

    /** 阻塞读一帧；返回 null 表示对端关闭。 */
    fun receive(): ProtocolEnvelope? {
        val frame = FrameCodec.read(input) ?: return null
        val envelope = ProtocolCodec.decode(frame)
        if (envelope.sequence <= inboundSequence.get()) throw IOException("sequence is not increasing")
        inboundSequence.set(envelope.sequence)
        return envelope
    }

    @Synchronized
    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "TvrcController"
        const val DEFAULT_PORT = 47832
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 45_000

        /**
         * 建立到电视的 TLS 连接。
         * `pinnedFingerprint` 非空时严格校验；为空时 TOFU 首次信任。
         */
        fun connect(
            host: String,
            port: Int = DEFAULT_PORT,
            pinnedFingerprint: ByteArray?,
        ): TvConnection {
            val trustManager = PinningTrustManager(pinnedFingerprint)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), SecureRandom())
            val factory = context.socketFactory
            val socket = factory.createSocket() as SSLSocket
            socket.tcpNoDelay = true
            socket.connect(java.net.InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val enabled = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
            socket.enabledProtocols = enabled.toTypedArray()
            socket.startHandshake()
            val fingerprint = sha256(socket.session.peerCertificates[0].encoded)
            return TvConnection(socket, fingerprint)
        }

        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    }

    /** 证书校验：pinned 时逐条比对；TOFU 时放行并由调用方保存指纹。 */
    private class PinningTrustManager(
        private val pinnedFingerprint: ByteArray?,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("controller side does not accept client certificates")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw CertificateException("empty certificate chain")
            val fingerprint = sha256(chain[0].encoded)
            if (pinnedFingerprint != null && !MessageDigest.isEqual(pinnedFingerprint, fingerprint)) {
                throw CertificateException("server certificate fingerprint mismatch")
            }
            // 自签证书：TOFU 模式下不做系统信任链校验，身份由应用层 HMAC 挑战兜底
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}

/**
 * UDP 组播发现客户端（对齐 DiscoveryServer 的 probe/probe_response）。
 */
object DiscoveryClient {
    data class DiscoveredTv(
        val address: String,
        val displayName: String,
        val controlPort: Int,
        val instanceId: String,
    )

    /**
     * 发送一次 probe 并收集 `timeoutMs` 内的响应（对齐 Kotlin/桌面端语义：
     * 请求必须恰好是 {"protocolVersion":1,"type":"probe"}）。
     */
    fun probe(timeoutMs: Long = 5000, port: Int = 47831): List<DiscoveredTv> {
        val found = LinkedHashMap<String, DiscoveredTv>()
        java.net.DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs.toInt()
            socket.broadcast = true
            val request = """{"protocolVersion":1,"type":"probe"}""".toByteArray(Charsets.UTF_8)
            socket.send(
                java.net.DatagramPacket(
                    request,
                    request.size,
                    java.net.InetAddress.getByName("255.255.255.255"),
                    port,
                ),
            )
            val buffer = ByteArray(512)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                if (remaining <= 0) break
                socket.soTimeout = remaining
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
                val tv = parseResponse(packet) ?: continue
                found[tv.address] = tv
            }
        }
        return found.values.toList()
    }

    private fun parseResponse(packet: java.net.DatagramPacket): DiscoveredTv? {
        return try {
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val json = dev.lucasdone.tvremote.agent.protocol.StrictJson.parseObject(text.toByteArray())
            val obj = json as? JsonValue.ObjectValue ?: return null
            if ((obj["type"] as? JsonValue.StringValue)?.value != "probe_response") return null
            val version = (obj["protocolVersion"] as? JsonValue.NumberValue)?.source?.toLongOrNull()
            if (version != 1L) return null
            val displayName = (obj["displayName"] as? JsonValue.StringValue)?.value ?: return null
            val controlPort = (obj["controlPort"] as? JsonValue.NumberValue)?.source?.toIntOrNull() ?: return null
            val instanceId = (obj["instanceId"] as? JsonValue.StringValue)?.value ?: return null
            DiscoveredTv(
                address = packet.address.hostAddress ?: return null,
                displayName = displayName,
                controlPort = controlPort,
                instanceId = instanceId,
            )
        } catch (_: Exception) {
            null
        }
    }
}
