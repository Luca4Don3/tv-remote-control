package dev.lucasdone.tvremote.controller

import dev.lucasdone.tvremote.controller.pairing.PairInvitationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 扫码邀请解析：只接受专用 scheme/action，校验 host/端口/token 格式与 ttl 范围。 */
class PairInvitationParserTest {
    private val token = "a".repeat(64)

    @Test
    fun parsesValidInvitation() {
        val invitation = PairInvitationParser.parse("tvrc://pair?host=192.0.2.50&port=47832&token=$token&ttl=60")
        assertEquals("192.0.2.50", invitation?.host)
        assertEquals(47832, invitation?.port)
        assertEquals(token, invitation?.token)
        assertEquals(60L, invitation?.ttlSeconds)
    }

    @Test
    fun ttlDefaultsWhenAbsent() {
        val invitation = PairInvitationParser.parse("tvrc://pair?host=192.0.2.2&port=47832&token=$token")
        assertEquals(120L, invitation?.ttlSeconds)
    }

    @Test
    fun rejectsMissingHost() {
        assertNull(PairInvitationParser.parse("tvrc://pair?port=47832&token=$token"))
    }

    @Test
    fun rejectsMissingPort() {
        assertNull(PairInvitationParser.parse("tvrc://pair?host=192.0.2.4&token=$token"))
    }

    @Test
    fun rejectsShortToken() {
        assertNull(PairInvitationParser.parse("tvrc://pair?host=192.0.2.4&port=47832&token=abcd"))
    }

    @Test
    fun rejectsUppercaseToken() {
        assertNull(PairInvitationParser.parse("tvrc://pair?host=192.0.2.4&port=47832&token=${"A".repeat(64)}"))
    }

    @Test
    fun rejectsNonHexToken() {
        assertNull(PairInvitationParser.parse("tvrc://pair?host=192.0.2.4&port=47832&token=${"g".repeat(64)}"))
    }

    @Test
    fun rejectsZeroPort() {
        assertNull(PairInvitationParser.parse("tvrc://pair?host=192.0.2.4&port=0&token=$token"))
    }

    @Test
    fun rejectsTtlOutOfRange() {
        assertNull(PairInvitationParser.parse("tvrc://pair?host=192.0.2.4&port=47832&token=$token&ttl=99999"))
        assertNull(PairInvitationParser.parse("tvrc://pair?host=192.0.2.4&port=47832&token=$token&ttl=0"))
    }

    @Test
    fun rejectsWhitespaceInHost() {
        assertNull(PairInvitationParser.parse("tvrc://pair?host=192.0.2.4%20x&port=47832&token=$token"))
    }

    @Test
    fun rejectsForeignScheme() {
        assertNull(PairInvitationParser.parse("https://example.com/pair?host=192.0.2.4&port=47832&token=$token"))
    }

    @Test
    fun rejectsForeignAction() {
        assertNull(PairInvitationParser.parse("tvrc://other?host=192.0.2.4&port=47832&token=$token"))
    }

    @Test
    fun rejectsPlainText() {
        assertNull(PairInvitationParser.parse("hello world"))
        assertNull(PairInvitationParser.parse(""))
    }
}
