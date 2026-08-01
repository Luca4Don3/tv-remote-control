package dev.lucasdone.tvremote.agent.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object H264Normalizer {
    fun configurationRecord(csd0: ByteArray, csd1: ByteArray): ByteArray {
        val sps = firstNal(csd0)
        val pps = firstNal(csd1)
        require(sps.size >= 4 && (sps[0].toInt() and 0x1f) == 7) { "invalid H.264 SPS" }
        require(pps.isNotEmpty() && (pps[0].toInt() and 0x1f) == 8) { "invalid H.264 PPS" }
        require(sps.size <= 0xffff && pps.size <= 0xffff)
        return ByteArrayOutputStream(11 + sps.size + pps.size).apply {
            write(1)
            write(sps[1].toInt() and 0xff)
            write(sps[2].toInt() and 0xff)
            write(sps[3].toInt() and 0xff)
            write(0xff)
            write(0xe1)
            write((sps.size ushr 8) and 0xff)
            write(sps.size and 0xff)
            write(sps)
            write(1)
            write((pps.size ushr 8) and 0xff)
            write(pps.size and 0xff)
            write(pps)
        }.toByteArray()
    }

    fun accessUnit(input: ByteArray): ByteArray {
        if (!hasStartCode(input, 0)) {
            validateAvcc(input)
            return input.copyOf()
        }
        val output = ByteArrayOutputStream(input.size + 16)
        var cursor = findStartCode(input, 0)
        while (cursor >= 0) {
            val prefix = startCodeLength(input, cursor)
            val nalStart = cursor + prefix
            val next = findStartCode(input, nalStart)
            val nalEnd = if (next < 0) input.size else next
            if (nalEnd > nalStart) {
                val length = nalEnd - nalStart
                output.write((length ushr 24) and 0xff)
                output.write((length ushr 16) and 0xff)
                output.write((length ushr 8) and 0xff)
                output.write(length and 0xff)
                output.write(input, nalStart, length)
            }
            cursor = next
        }
        return output.toByteArray().also(::validateAvcc)
    }

    private fun firstNal(input: ByteArray): ByteArray {
        val start = findStartCode(input, 0)
        if (start < 0) return input.copyOf()
        val nalStart = start + startCodeLength(input, start)
        val next = findStartCode(input, nalStart)
        return input.copyOfRange(nalStart, if (next < 0) input.size else next)
    }

    private fun validateAvcc(input: ByteArray) {
        val buffer = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
        while (buffer.hasRemaining()) {
            require(buffer.remaining() >= 4) { "truncated AVCC NAL length" }
            val length = buffer.int
            require(length > 0 && length <= buffer.remaining()) { "invalid AVCC NAL length" }
            buffer.position(buffer.position() + length)
        }
    }

    private fun findStartCode(input: ByteArray, from: Int): Int {
        var index = from
        while (index + 3 <= input.size) {
            if (hasStartCode(input, index)) return index
            index += 1
        }
        return -1
    }

    private fun hasStartCode(input: ByteArray, index: Int): Boolean =
        index + 3 <= input.size && input[index] == 0.toByte() && input[index + 1] == 0.toByte() &&
            (input[index + 2] == 1.toByte() ||
                (index + 4 <= input.size && input[index + 2] == 0.toByte() && input[index + 3] == 1.toByte()))

    private fun startCodeLength(input: ByteArray, index: Int): Int = if (input[index + 2] == 1.toByte()) 3 else 4
}
