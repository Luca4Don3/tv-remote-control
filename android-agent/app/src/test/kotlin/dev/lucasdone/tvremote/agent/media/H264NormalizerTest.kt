package dev.lucasdone.tvremote.agent.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class H264NormalizerTest {
    @Test
    fun convertsAnnexBAccessUnitToAvcc() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 0, 0, 1, 0x41, 3)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 3),
            H264Normalizer.accessUnit(annexB),
        )
    }

    @Test
    fun buildsAvcConfigurationRecord() {
        val record = H264Normalizer.configurationRecord(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, 0x1f),
            byteArrayOf(0, 0, 0, 1, 0x68, 1),
        )
        assertEquals(1, record[0].toInt())
        assertEquals(0x64, record[1].toInt() and 0xff)
    }

    @Test
    fun rejectsWrongTruncatedAndForbiddenConfigurationNals() {
        val sps = byteArrayOf(0x67, 0x64, 0, 0x1f)
        val pps = byteArrayOf(0x68, 1)
        assertThrows(IllegalArgumentException::class.java) {
            H264Normalizer.configurationRecord(byteArrayOf(0x68, 0x64, 0, 0x1f), pps)
        }
        assertThrows(IllegalArgumentException::class.java) {
            H264Normalizer.configurationRecord(sps, byteArrayOf(0x68))
        }
        assertThrows(IllegalArgumentException::class.java) {
            H264Normalizer.configurationRecord(byteArrayOf(0xe7.toByte(), 0x64, 0, 0x1f), pps)
        }
        assertThrows(IllegalArgumentException::class.java) {
            H264Normalizer.configurationRecord(sps, byteArrayOf(0xe8.toByte(), 1))
        }
    }
}
