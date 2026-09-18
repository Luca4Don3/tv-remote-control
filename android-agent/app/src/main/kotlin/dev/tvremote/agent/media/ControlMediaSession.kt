package dev.tvremote.agent.media

import java.io.OutputStream

data class MediaAttachOffer(val token: String, val expiresAtMs: Long)
data class MediaAttachment(val attachmentId: Long, val channel: MediaPacketChannel)

/** Control transport can be reused by products that ship no capture implementation. */
interface ControlMediaSession : AutoCloseable {
    fun issueOffer(controllerId: String, sessionId: String): MediaAttachOffer?
    fun attach(controllerId: String, sessionId: String, tokenHex: String, output: OutputStream,
        closeTransport: () -> Unit = { output.close() }): MediaAttachment?
    fun stopSession(sessionId: String)
    fun stopAttachment(expectedAttachmentId: Long)
}

object DisabledControlMediaSession : ControlMediaSession {
    override fun issueOffer(controllerId: String, sessionId: String): MediaAttachOffer? = null
    override fun attach(controllerId: String, sessionId: String, tokenHex: String, output: OutputStream,
        closeTransport: () -> Unit): MediaAttachment? = null
    override fun stopSession(sessionId: String) = Unit
    override fun stopAttachment(expectedAttachmentId: Long) = Unit
    override fun close() = Unit
}
