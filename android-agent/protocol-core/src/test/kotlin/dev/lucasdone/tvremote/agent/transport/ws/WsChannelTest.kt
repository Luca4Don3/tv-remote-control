package dev.lucasdone.tvremote.agent.transport.ws

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WsChannelTest {
    /** 手工构造掩码客户端帧。 */
    private fun maskFrame(opcode: Int, payload: ByteArray, fin: Boolean = true): ByteArray {
        val out = mutableListOf<Byte>()
        out.add(((if (fin) 0x80 else 0) or opcode).toByte())
        val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44.toByte())
        if (payload.size < 126) {
            out.add((0x80 or payload.size).toByte())
        } else {
            out.add((0x80 or 126).toByte())
            out.add(((payload.size ushr 8) and 0xFF).toByte())
            out.add((payload.size and 0xFF).toByte())
        }
        out.addAll(mask.toList())
        payload.forEachIndexed { index, b -> out.add((b.toInt() xor mask[index % 4].toInt()).toByte()) }
        return out.toByteArray()
    }

    @Test
    fun frameRoundtrip() {
        val input = ByteArrayInputStream(maskFrame(WsFrameCodec.OPCODE_TEXT, "hello".toByteArray()))
        val incoming = WsFrameCodec.read(input)!!
        assertEquals(WsFrameCodec.OPCODE_TEXT, incoming.opcode)
        assertArrayEquals("hello".toByteArray(), incoming.payload)
    }

    @Test
    fun fragmentedMessage() {
        val stream = ByteArrayInputStream(
            maskFrame(WsFrameCodec.OPCODE_TEXT, "he".toByteArray(), fin = false) +
                maskFrame(WsFrameCodec.OPCODE_CONTINUATION, "llo".toByteArray()),
        )
        val incoming = WsFrameCodec.read(stream)!!
        assertArrayEquals("hello".toByteArray(), incoming.payload)
    }

    @Test
    fun serverEncodingMatchesRustLayout() {
        val out = ByteArrayOutputStream()
        WsFrameCodec.write(out, WsFrameCodec.OPCODE_TEXT, "ok".toByteArray())
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x02, 'o'.code.toByte(), 'k'.code.toByte()), out.toByteArray())
        val big = ByteArrayOutputStream()
        WsFrameCodec.write(big, WsFrameCodec.OPCODE_BINARY, ByteArray(126))
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), 126, 0x00, 0x7E) + ByteArray(126),
            big.toByteArray(),
        )
    }

    @Test
    fun unmaskedClientFrameRejected() {
        val raw = byteArrayOf(0x81.toByte(), 0x05) + "hello".toByteArray()
        val stream = ByteArrayInputStream(raw)
        org.junit.Assert.assertThrows(WsFrameCodec.WsProtocolException::class.java) {
            WsFrameCodec.read(stream)
        }
    }

    @Test
    fun hkdfRfc5869TestCase1() {
        // RFC 5869 A.1（与 Rust core 测试同一向量，跨实现一致性锚点）
        val ikm = "0b".repeat(22).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val salt = "000102030405060708090a0b0c".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val info = "f0f1f2f3f4f5f6f7f8f9".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val okm = DebugSessionCrypto.hkdfSha256(salt, ikm, info, 42)
        val expected = (
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, okm)
    }

    @Test
    fun directionCipherSealOpenRoundtrip() {
        val a = DebugSessionCrypto.DirectionCipher(ByteArray(32) { 7 })
        val b = DebugSessionCrypto.DirectionCipher(ByteArray(32) { 7 })
        val sealed = a.seal("hello".toByteArray(), "aad1".toByteArray())
        val opened = b.open(sealed, 0, "aad1".toByteArray())
        assertArrayEquals("hello".toByteArray(), opened)
    }

    @Test
    fun wrongCounterRejected() {
        val a = DebugSessionCrypto.DirectionCipher(ByteArray(32) { 3 })
        val b = DebugSessionCrypto.DirectionCipher(ByteArray(32) { 3 })
        val sealed = a.seal("m".toByteArray())
        org.junit.Assert.assertThrows(Exception::class.java) { b.open(sealed, 5) }
    }

    @Test
    fun replayWindowSemantics() {
        val window = ReplayWindow(64)
        assertTrue(window.checkAndAccept(1))
        assertTrue(window.checkAndAccept(2))
        assertFalse(window.checkAndAccept(1))
        assertFalse(window.checkAndAccept(0))
        // 延迟到达：窗口内未见过的序号
        val sparse = ReplayWindow(8)
        sparse.checkAndAccept(1)
        sparse.checkAndAccept(4)
        assertTrue(sparse.checkAndAccept(3))
        assertFalse(sparse.checkAndAccept(3))
    }
}
