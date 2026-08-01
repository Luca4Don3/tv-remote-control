package dev.lucasdone.tvremote.agent.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MediaTrack(val wireValue: Int) { VIDEO(1), AUDIO(2) }

data class MediaPacketHeader(
    val track: MediaTrack,
    val keyFrame: Boolean = false,
    val codecConfig: Boolean = false,
    val discontinuity: Boolean = false,
    val endOfStream: Boolean = false,
    val sequence: Long,
    val presentationTimeUs: Long,
    val payloadLength: Int,
    val codecConfigId: Long,
    val width: Int = 0,
    val height: Int = 0,
) {
    init {
        validate()
    }

    // 序列化字段校验集中于此，供构造与 decode 共用同一套约束。
    private fun validate() {
        require(sequence in 0..UINT32_MAX)
        require(presentationTimeUs >= 0)
        require(payloadLength in 0..MAX_PACKET_SIZE)
        require(codecConfigId in 0..UINT32_MAX)
        require(width in 0..0xffff && height in 0..0xffff)
    }

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
        put(MAGIC)
        put(VERSION)
        put(track.wireValue.toByte())
        var flags = 0
        if (keyFrame) flags = flags or FLAG_KEY_FRAME
        if (codecConfig) flags = flags or FLAG_CODEC_CONFIG
        if (discontinuity) flags = flags or FLAG_DISCONTINUITY
        if (endOfStream) flags = flags or FLAG_END_OF_STREAM
        putShort(flags.toShort())
        putInt(sequence.toInt())
        putLong(presentationTimeUs)
        putInt(payloadLength)
        putInt(codecConfigId.toInt())
        putShort(width.toShort())
        putShort(height.toShort())
    }.array()

    companion object {
        const val HEADER_SIZE = 32
        const val MAX_PACKET_SIZE = 4 * 1024 * 1024
        private const val UINT32_MAX = 0xffff_ffffL
        private const val FLAG_KEY_FRAME = 1
        private const val FLAG_CODEC_CONFIG = 2
        private const val FLAG_DISCONTINUITY = 4
        private const val FLAG_END_OF_STREAM = 8
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'V'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte())
        private const val VERSION: Byte = 1

        fun decode(bytes: ByteArray): MediaPacketHeader {
            require(bytes.size == HEADER_SIZE) { "media header must be exactly 32 bytes" }
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val receivedMagic = ByteArray(4).also(input::get)
            require(receivedMagic.contentEquals(MAGIC)) { "invalid media magic" }
            require(input.get() == VERSION) { "unsupported media version" }
            val track = when (input.get().toInt() and 0xff) {
                1 -> MediaTrack.VIDEO
                2 -> MediaTrack.AUDIO
                else -> throw IllegalArgumentException("invalid media track")
            }
            val flags = input.short.toInt() and 0xffff
            require(flags and 0xfff0 == 0) { "invalid media flags" }
            return MediaPacketHeader(
                track = track,
                keyFrame = flags and FLAG_KEY_FRAME != 0,
                codecConfig = flags and FLAG_CODEC_CONFIG != 0,
                discontinuity = flags and FLAG_DISCONTINUITY != 0,
                endOfStream = flags and FLAG_END_OF_STREAM != 0,
                sequence = input.int.toLong() and UINT32_MAX,
                presentationTimeUs = input.long.also { require(it >= 0) },
                payloadLength = input.int.also { require(it in 0..MAX_PACKET_SIZE) },
                codecConfigId = input.int.toLong() and UINT32_MAX,
                width = input.short.toInt() and 0xffff,
                height = input.short.toInt() and 0xffff,
            )
        }
    }
}

data class MediaPacket(val header: MediaPacketHeader, val payload: ByteArray) {
    init {
        validate()
    }

    private fun validate() {
        require(payload.size == header.payloadLength) { "media payload length mismatch" }
    }
}

fun interface MediaPacketSink {
    /** Ownership of [packet] transfers to the sink. Returns false on bounded-queue backpressure. */
    fun offer(packet: MediaPacket): Boolean
}
