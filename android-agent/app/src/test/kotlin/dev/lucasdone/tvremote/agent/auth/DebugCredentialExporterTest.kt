package dev.lucasdone.tvremote.agent.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCredentialExporterTest {
    @Test
    fun `format returns placeholder when no credentials`() {
        assertEquals("当前没有已配对的控制端。", DebugCredentialExporter.format(emptyList()))
    }

    @Test
    fun `format lists controllerId and psk hex per entry`() {
        val text = DebugCredentialExporter.format(
            listOf(
                DebugCredentialExporter.Entry(
                    controllerName = "客厅电视",
                    controllerId = "ab".repeat(16),
                    pskHex = "01".repeat(32),
                ),
            ),
        )
        assertTrue(text.contains("控制端：客厅电视"))
        assertTrue(text.contains("controllerId：${"ab".repeat(16)}"))
        assertTrue(text.contains("PSK：${"01".repeat(32)}"))
    }

    @Test
    fun `format separates multiple entries with blank lines`() {
        val text = DebugCredentialExporter.format(
            listOf(
                DebugCredentialExporter.Entry("a", "1".repeat(32), "2".repeat(64)),
                DebugCredentialExporter.Entry("b", "3".repeat(32), "4".repeat(64)),
            ),
        )
        assertEquals(2, text.split("\n\n").size)
        assertTrue(text.contains("控制端：a"))
        assertTrue(text.contains("控制端：b"))
    }
}
