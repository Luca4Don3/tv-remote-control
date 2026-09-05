package dev.lucasdone.tvremote.agent.transport.ws

/**
 * RFC 4648 标准_BASE64 编码（无填充仅用于 WebSocket 握手键场景按需调用方决定）。
 * 纯 JVM 实现，避免 android.util.Base64（纯 JVM 单测不可用）与
 * java.util.Base64（API 26+）的平台依赖。
 */
object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray, withPadding: Boolean = true): String {
        val output = StringBuilder(((data.size + 2) / 3) * 4)
        var index = 0
        while (index < data.size) {
            val remaining = data.size - index
            val b0 = data[index].toInt() and 0xFF
            val b1 = if (remaining > 1) data[index + 1].toInt() and 0xFF else 0
            val b2 = if (remaining > 2) data[index + 2].toInt() and 0xFF else 0
            output.append(ALPHABET[(b0 shr 2) and 0x3F])
            output.append(ALPHABET[((b0 shl 4) or (b1 shr 4)) and 0x3F])
            if (remaining > 1) {
                output.append(ALPHABET[((b1 shl 2) or (b2 shr 6)) and 0x3F])
            } else if (withPadding) {
                output.append('=')
            }
            if (remaining > 2) {
                output.append(ALPHABET[b2 and 0x3F])
            } else if (withPadding) {
                output.append('=')
            }
            index += 3
        }
        return output.toString()
    }
}
