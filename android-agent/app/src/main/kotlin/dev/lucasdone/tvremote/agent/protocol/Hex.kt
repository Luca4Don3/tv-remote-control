package dev.lucasdone.tvremote.agent.protocol

object Hex {
    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { value ->
            val unsigned = value.toInt() and 0xff
            append(DIGITS[unsigned ushr 4])
            append(DIGITS[unsigned and 0x0f])
        }
    }

    fun decode(value: String, expectedBytes: Int? = null): ByteArray {
        if (value.length % 2 != 0) throw ProtocolException("hex value has odd length")
        if (expectedBytes != null && value.length != expectedBytes * 2) {
            throw ProtocolException("hex value must contain $expectedBytes bytes")
        }
        return ByteArray(value.length / 2) { index ->
            val high = Character.digit(value[index * 2], 16)
            val low = Character.digit(value[index * 2 + 1], 16)
            if (high < 0 || low < 0) throw ProtocolException("hex value contains a non-hex character")
            ((high shl 4) or low).toByte()
        }
    }

    private const val DIGITS = "0123456789abcdef"
}
