package dev.lucasdone.tvremote.xiaomi

import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.xiaomi.backend.AdbCommands
import dev.lucasdone.tvremote.xiaomi.backend.AdbPublicKeyEncoder
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class AdbCommandsTest {
    @Test fun onlyFixedKeyCommandsAreAccepted() {
        for (key in AdbCommands.keys) {
            val command = AdbCommands.keyCommand(key)!!
            assertTrue(command.matches(Regex("/system/bin/input keyevent [0-9]+")))
            assertTrue(AdbCommands.destination(command).startsWith("shell:$command;"))
        }
        assertNull(AdbCommands.keyCommand(LogicalKey.POWER))
        assertNull(AdbCommands.keyCommand(LogicalKey.CHANNEL_UP))
        assertNull(AdbCommands.keyCommand(LogicalKey.MENU))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsCommandInjection() { AdbCommands.destination("/system/bin/input keyevent 19; reboot") }
    @Test fun legacyShellContainsExitStatusAndNoUnexpandedEscape() {
        assertEquals("shell:id -u; printf '\\nTVRC_EXIT:%s\\n' \"\$?\"", AdbCommands.destination(AdbCommands.PROBE))
        assertEquals(0 to "2000", AdbCommands.parseResponse("2000\r\n\nTVRC_EXIT:0\n"))
        assertEquals(1 to "failed", AdbCommands.parseResponse("failed\nTVRC_EXIT:1\n"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun incompleteOutputCannotBeSuccess() { AdbCommands.parseResponse("2000\n") }
    @Test(expected = IllegalArgumentException::class)
    fun malformedExitStatusCannotBeSuccess() { AdbCommands.parseResponse("\nTVRC_EXIT:0\ninjected") }
    @Test fun rejectsEveryNonWhitelistedCommandVariant() {
        val bad = listOf(
            "/system/bin/input keyevent 19; reboot",
            "/system/bin/input keyevent 19\nrm -rf /",
            "/system/bin/input keyevent 19`id`",
            "/system/bin/input keyevent 19$(id)",
            "/system/bin/input keyevent 19 && id",
            "id -u; reboot",
            "test -x /system/bin/input; reboot",
            "/system/bin/input text hi",
            "rm -rf /",
        )
        for (command in bad) {
            try {
                AdbCommands.destination(command)
                fail("accepted: $command")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
    @Test fun parseResponseUsesLastExitMarkerAndAcceptsSignedStatus() {
        assertEquals(0 to "TVRC_EXIT:9", AdbCommands.parseResponse("TVRC_EXIT:9\nTVRC_EXIT:0\n"))
        assertEquals(-1 to "x", AdbCommands.parseResponse("x\nTVRC_EXIT:-1\n"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun nonNumericExitStatusIsRejected() { AdbCommands.parseResponse("x\nTVRC_EXIT:abc\n") }
    @Test fun publicKeyMatchesAndroidRsaWireLayout() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        val encoded = AdbPublicKeyEncoder.encode(key)
        assertEquals(524, encoded.size)
        val bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bytes.int)
        val inverse = bytes.int.toLong() and 0xffffffffL
        val word = BigInteger.ONE.shiftLeft(32)
        assertEquals(word.subtract(BigInteger.ONE), key.modulus.mod(word).multiply(BigInteger.valueOf(inverse)).mod(word))
        val n = ByteArray(256); bytes.get(n)
        assertEquals(key.modulus, BigInteger(1, n.reversedArray()))
        val rr = ByteArray(256); bytes.get(rr)
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(key.modulus), BigInteger(1, rr.reversedArray()))
        assertEquals(65537, bytes.int)
    }
}
