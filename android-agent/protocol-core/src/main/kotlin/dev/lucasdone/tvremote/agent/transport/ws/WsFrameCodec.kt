package dev.lucasdone.tvremote.agent.transport.ws

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * WebSocket 帧编解码（RFC 6455 服务端子集），与 Rust core `ws.rs` 语义对齐：
 * 入向必须带客户端掩码；出向不掩码；载荷上限 64 KiB；支持分片重组。
 */
object WsFrameCodec {
    const val OPCODE_CONTINUATION: Int = 0x0
    const val OPCODE_TEXT: Int = 0x1
    const val OPCODE_BINARY: Int = 0x2
    const val OPCODE_CLOSE: Int = 0x8
    const val OPCODE_PING: Int = 0x9
    const val OPCODE_PONG: Int = 0xA
    const val MAX_PAYLOAD_BYTES: Int = 64 * 1024

    class WsProtocolException(message: String) : IllegalArgumentException(message)

    /** 已解码的一条完整入向消息。 */
    data class Incoming(val opcode: Int, val payload: ByteArray)

    /**
     * 流式读取一条消息（自动处理分片）。返回 null 表示流结束。
     * 阻塞直到一条完整消息或对端关闭。
     *
     * `expectMasked`：服务端读客户端帧时 RFC 6455 强制掩码（默认）；
     * 客户端读服务端帧时服务端出向帧不掩码，传 false。
     */
    fun read(input: InputStream, expectMasked: Boolean = true): Incoming? {
        var firstOpcode = -1
        val fragments = StringBuilderByteSink()
        while (true) {
            val header = readFully(input, 2) ?: return null
            val fin = (header[0].toInt() and 0x80) != 0
            if ((header[0].toInt() and 0x70) != 0) throw WsProtocolException("reserved bits set")
            val opcode = header[0].toInt() and 0x0F
            val masked = (header[1].toInt() and 0x80) != 0
            if (expectMasked && !masked) throw WsProtocolException("client frames must be masked")
            var length = header[1].toInt() and 0x7F
            if (length == 126) {
                val ext = readFully(input, 2) ?: throw EOFException("connection closed during a frame")
                length = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
            } else if (length == 127) {
                val ext = readFully(input, 8) ?: throw EOFException("connection closed during a frame")
                val value = 0L shl 0 or
                    ((ext[0].toLong() and 0xFF) shl 56) or ((ext[1].toLong() and 0xFF) shl 48) or
                    ((ext[2].toLong() and 0xFF) shl 40) or ((ext[3].toLong() and 0xFF) shl 32) or
                    ((ext[4].toLong() and 0xFF) shl 24) or ((ext[5].toLong() and 0xFF) shl 16) or
                    ((ext[6].toLong() and 0xFF) shl 8) or (ext[7].toLong() and 0xFF)
                if (value > MAX_PAYLOAD_BYTES) throw WsProtocolException("payload exceeds 64 KiB")
                length = value.toInt()
            }
            if (length > MAX_PAYLOAD_BYTES) throw WsProtocolException("payload exceeds 64 KiB")
            val payload = if (masked) {
                val mask = readFully(input, 4) ?: throw EOFException("connection closed during a frame")
                val maskedPayload = readFully(input, length) ?: throw EOFException("connection closed during a frame")
                ByteArray(length) { i -> (maskedPayload[i].toInt() xor mask[i % 4].toInt()).toByte() }
            } else {
                readFully(input, length) ?: throw EOFException("connection closed during a frame")
            }

            when (opcode) {
                OPCODE_CONTINUATION -> {
                    if (firstOpcode < 0) throw WsProtocolException("continuation without start")
                    fragments.append(payload)
                    if (fragments.size() > MAX_PAYLOAD_BYTES) throw WsProtocolException("payload exceeds 64 KiB")
                    if (fin) return Incoming(firstOpcode, fragments.take())
                }
                OPCODE_TEXT, OPCODE_BINARY -> {
                    if (fin) return Incoming(opcode, payload)
                    if (firstOpcode >= 0) throw WsProtocolException("new fragment started mid-message")
                    firstOpcode = opcode
                    fragments.append(payload)
                }
                OPCODE_PING -> return Incoming(OPCODE_PING, payload)
                OPCODE_PONG -> return Incoming(OPCODE_PONG, payload)
                OPCODE_CLOSE -> throw ClosedException
                else -> throw WsProtocolException("unknown opcode $opcode")
            }
        }
    }

    object ClosedException : RuntimeException("connection closed by peer")

    private class StringBuilderByteSink {
        private var current = ByteArray(0)

        fun append(bytes: ByteArray) {
            val merged = ByteArray(current.size + bytes.size)
            System.arraycopy(current, 0, merged, 0, current.size)
            System.arraycopy(bytes, 0, merged, current.size, bytes.size)
            current = merged
        }

        fun size(): Int = current.size

        fun take(): ByteArray {
            val out = current
            current = ByteArray(0)
            return out
        }
    }

    /** 客户端出向帧（RFC 6455 强制掩码，掩码键由 SecureRandom 生成）。 */
    fun writeClient(
        output: OutputStream,
        opcode: Int,
        payload: ByteArray,
        random: java.security.SecureRandom = java.security.SecureRandom(),
    ) {
        if (payload.size > MAX_PAYLOAD_BYTES) throw WsProtocolException("payload exceeds 64 KiB")
        val mask = ByteArray(4).also(random::nextBytes)
        output.write(0x80 or opcode)
        val size = payload.size
        val maskedFlag = 0x80
        when {
            size < 126 -> output.write(maskedFlag or size)
            size <= 0xFFFF -> {
                output.write(maskedFlag or 126)
                output.write((size ushr 8) and 0xFF)
                output.write(size and 0xFF)
            }
            else -> {
                output.write(maskedFlag or 127)
                for (shift in 56 downTo 0 step 8) output.write(((size.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        output.write(mask)
        output.write(ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() })
        output.flush()
    }

    /** 服务端出向帧（不掩码，单帧 FIN）。 */
    fun write(output: OutputStream, opcode: Int, payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD_BYTES) throw WsProtocolException("payload exceeds 64 KiB")
        output.write(0x80 or opcode)
        val size = payload.size
        when {
            size < 126 -> output.write(size)
            size <= 0xFFFF -> {
                output.write(126)
                output.write((size ushr 8) and 0xFF)
                output.write(size and 0xFF)
            }
            else -> {
                output.write(127)
                for (shift in 56 downTo 0 step 8) output.write(((size.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        output.write(payload)
        output.flush()
    }

    fun writeText(output: OutputStream, text: String) {
        write(output, OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    fun writePong(output: OutputStream, payload: ByteArray) {
        write(output, OPCODE_PONG, payload)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray? {
        if (count == 0) return ByteArray(0)
        val target = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(target, offset, count - offset)
            if (read < 0) return null
            offset += read
        }
        return target
    }
}
