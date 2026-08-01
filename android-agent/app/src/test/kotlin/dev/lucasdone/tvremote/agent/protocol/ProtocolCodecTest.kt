package dev.lucasdone.tvremote.agent.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ProtocolCodecTest {
    @Test
    fun frameReaderHandlesFragmentedAndCoalescedInput() {
        val output = ByteArrayOutputStream()
        FrameCodec.write(output, "{\"one\":1}".toByteArray())
        FrameCodec.write(output, "{\"two\":2}".toByteArray())
        val input = OneByteAtATimeInputStream(output.toByteArray())

        assertEquals("{\"one\":1}", FrameCodec.read(input)!!.toString(Charsets.UTF_8))
        assertEquals("{\"two\":2}", FrameCodec.read(input)!!.toString(Charsets.UTF_8))
        assertEquals(null, FrameCodec.read(input))
    }

    @Test
    fun frameReaderRejectsOversizedLengthBeforeAllocating() {
        val header = byteArrayOf(0, 1, 0, 1)
        assertThrows(ProtocolException::class.java) { FrameCodec.read(ByteArrayInputStream(header)) }
    }

    @Test
    fun envelopeRoundTripsAndRequiresPositiveSequence() {
        val encoded = ProtocolCodec.encode(
            ProtocolEnvelope(1, "request-1", "", 1, "ping", jsonObject()),
        )
        assertEquals(ProtocolEnvelope(1, "request-1", "", 1, "ping", jsonObject()), ProtocolCodec.decode(encoded))
        val invalid = encoded.toString(Charsets.UTF_8).replace("\"sequence\":1", "\"sequence\":0").toByteArray()
        assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(invalid) }
    }

    @Test
    fun strictJsonRejectsDuplicateFieldsDeepNestingAndInvalidUtf8() {
        assertThrows(JsonParseException::class.java) {
            StrictJson.parseObject("{\"a\":1,\"a\":2}".toByteArray())
        }
        val deep = "{\"x\":".repeat(14) + "0" + "}".repeat(14)
        assertThrows(JsonParseException::class.java) { StrictJson.parseObject(deep.toByteArray()) }
        assertThrows(JsonParseException::class.java) {
            StrictJson.parseObject(byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xc3.toByte(), '"'.code.toByte(), '}'.code.toByte()))
        }
    }

    private class OneByteAtATimeInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        override fun read(): Int = delegate.read()
        override fun read(target: ByteArray, offset: Int, length: Int): Int = delegate.read(target, offset, minOf(1, length))
    }
}
