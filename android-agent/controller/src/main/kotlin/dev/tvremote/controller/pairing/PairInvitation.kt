package dev.tvremote.controller.pairing

import java.net.URI
import java.net.URLDecoder

/**
 * 扫码配对邀请 `tvrc://pair?host=..&port=..&token=..&ttl=..`。
 *
 * 只解析专用 scheme/action；不执行二维码内容、不自动打开任意 URL。
 * token 是电视端一次性配对材料，禁止日志/历史/持久化；ttl 仅作提示，
 * 实际有效期以电视端判断为准。
 */
data class PairInvitation(
    val host: String,
    val port: Int,
    val token: String,
    val ttlSeconds: Long,
)

object PairInvitationParser {
    private const val SCHEME = "tvrc"
    private const val ACTION = "pair"
    private const val TOKEN_HEX_LENGTH = 64

    /** 解析失败返回 null（调用方展示“无效二维码”而非静默失败）。 */
    fun parse(text: String): PairInvitation? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(ACTION, ignoreCase = true)) return null
        val query = parseQuery(uri.rawQuery ?: return null) ?: return null

        val host = query["host"]?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) } ?: return null
        val port = query["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val token = query["token"]?.takeIf { isValidToken(it) } ?: return null
        val ttl = query["ttl"]?.let { raw ->
            raw.toLongOrNull()?.takeIf { it in 1..3600 } ?: return null
        } ?: DEFAULT_TTL_SECONDS

        return PairInvitation(host = host, port = port, token = token, ttlSeconds = ttl)
    }

    private fun isValidToken(value: String): Boolean =
        value.length == TOKEN_HEX_LENGTH && value.all { it.isDigit() || it in 'a'..'f' }

    private fun parseQuery(rawQuery: String): Map<String, String>? {
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            if (separator <= 0) return null
            val name = decode(pair.substring(0, separator)) ?: return null
            val value = decode(pair.substring(separator + 1)) ?: return null
            if (name !in result) result[name] = value
        }
        return result
    }

    private fun decode(value: String): String? = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    private const val DEFAULT_TTL_SECONDS = 120L
}
