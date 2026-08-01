package dev.lucasdone.tvremote.agent.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object FrameCodec {
    const val MAX_FRAME_SIZE = 64 * 1024

    fun read(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        readFully(input, header, 1, 3)
        val length = ((header[0].toLong() and 0xffL) shl 24) or
            ((header[1].toLong() and 0xffL) shl 16) or
            ((header[2].toLong() and 0xffL) shl 8) or
            (header[3].toLong() and 0xffL)
        if (length > MAX_FRAME_SIZE) throw ProtocolException("frame exceeds 64 KiB")
        if (length == 0L) throw ProtocolException("empty frame is not valid")
        return ByteArray(length.toInt()).also { readFully(input, it, 0, it.size) }
    }

    fun write(output: OutputStream, payload: ByteArray) {
        if (payload.isEmpty()) throw ProtocolException("empty frame is not valid")
        if (payload.size > MAX_FRAME_SIZE) throw ProtocolException("frame exceeds 64 KiB")
        output.write(
            byteArrayOf(
                (payload.size ushr 24).toByte(),
                (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
            ),
        )
        output.write(payload)
        output.flush()
    }

    private fun readFully(input: InputStream, target: ByteArray, start: Int, count: Int) {
        var offset = start
        val end = start + count
        while (offset < end) {
            val read = input.read(target, offset, end - offset)
            if (read < 0) throw EOFException("connection closed during a frame")
            offset += read
        }
    }
}
