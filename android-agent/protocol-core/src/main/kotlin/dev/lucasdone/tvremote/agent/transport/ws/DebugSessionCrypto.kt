package dev.lucasdone.tvremote.agent.transport.ws

import java.security.InvalidKeyException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WS 调试通道应用层加密：HKDF-SHA256 派生双向密钥 + AES-256-GCM。
 *
 * 算法与 Rust core `crypto.rs` 逐条对齐（跨实现一致性由测试向量保证）：
 * - salt = client_random || server_random；IKM = psk
 * - info 分别为 "tvremote c2s key" / "tvremote s2c key"，各出 32B
 * - nonce = 12B：4B 零前缀 + 8B 大端 counter；AAD 参与认证
 */
object DebugSessionCrypto {
    const val SECRET_BYTES = 32
    const val RANDOM_BYTES = 32
    const val COUNTER_BYTES = 8

    data class SessionKeys(
        val clientToServer: ByteArray,
        val serverToClient: ByteArray,
    )

    /** 双向密钥派生（与 Kotlin/Rust 双端一致）。 */
    fun deriveSessionKeys(psk: ByteArray, clientRandom: ByteArray, serverRandom: ByteArray): SessionKeys {
        require(psk.size == SECRET_BYTES) { "psk must be 32 bytes" }
        require(clientRandom.size == RANDOM_BYTES && serverRandom.size == RANDOM_BYTES) {
            "randoms must be 32 bytes"
        }
        val salt = clientRandom + serverRandom
        return SessionKeys(
            clientToServer = hkdfSha256(salt, psk, "tvremote c2s key".toByteArray(Charsets.US_ASCII), SECRET_BYTES),
            serverToClient = hkdfSha256(salt, psk, "tvremote s2c key".toByteArray(Charsets.US_ASCII), SECRET_BYTES),
        )
    }

    /** RFC 5869 HKDF-SHA256（extract + expand）。 */
    fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, outLength: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val hashLen = 32
        require(outLength <= 255 * hashLen) { "requested too many bytes" }
        val result = ByteArray(outLength)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < outLength) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val chunk = minOf(hashLen, outLength - offset)
            System.arraycopy(t, 0, result, offset, chunk)
            offset += chunk
            counter += 1
        }
        return result
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

    /**
     * 单方向加密封装：持有密钥与发送计数器；计数器与 AAD 绑定。
     * 密文布局：`counter(8B) || ciphertext || tag(16B)`。
     */
    class DirectionCipher(key: ByteArray) {
        private val keySpec = SecretKeySpec(key, "AES")
        private var sendCounter = 0L

        @Synchronized
        fun seal(plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
            if (sendCounter == Long.MAX_VALUE) throw IllegalStateException("nonce exhausted")
            val counter = sendCounter
            sendCounter += 1
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(counter)))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            val body = cipher.doFinal(plaintext)
            val out = ByteArray(COUNTER_BYTES + body.size)
            for (i in 0 until COUNTER_BYTES) out[i] = (counter ushr ((7 - i) * 8)).toByte()
            System.arraycopy(body, 0, out, COUNTER_BYTES, body.size)
            return out
        }

        /** 解密（对端计数器由调用方经防重放窗口校验后传入）。 */
        fun open(envelope: ByteArray, counter: Long, aad: ByteArray = ByteArray(0)): ByteArray {
            if (envelope.size <= COUNTER_BYTES + 16) throw WsFrameCodec.WsProtocolException("ciphertext too short")
            val embedded = readCounter(envelope)
            if (embedded != counter) throw WsFrameCodec.WsProtocolException("counter mismatch")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(counter)))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            return cipher.doFinal(envelope, COUNTER_BYTES, envelope.size - COUNTER_BYTES)
        }

        companion object {
            fun nonce(counter: Long): ByteArray = ByteArray(12).also { n ->
                for (i in 0 until COUNTER_BYTES) n[4 + i] = (counter ushr ((7 - i) * 8)).toByte()
            }

            fun readCounter(envelope: ByteArray): Long {
                var value = 0L
                for (i in 0 until COUNTER_BYTES) {
                    value = (value shl 8) or (envelope[i].toLong() and 0xFF)
                }
                return value
            }
        }
    }

    /** 常数时间比较。 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        a.size == b.size && MessageDigest.isEqual(a, b)

    @Suppress("UNUSED_PARAMETER")
    private fun unused(key: InvalidKeyException) = Unit
}
