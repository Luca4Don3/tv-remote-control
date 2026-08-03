package dev.lucasdone.tvremote.agent.protocol

data class ProtocolEnvelope(
    val protocolVersion: Int,
    val requestId: String,
    val sessionId: String,
    val sequence: Long,
    val type: String,
    val payload: JsonValue.ObjectValue,
)

class ProtocolException(message: String) : IllegalArgumentException(message)

object ProtocolCodec {
    const val VERSION = 1
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_SESSION_ID_LENGTH = 128
    const val MAX_TYPE_LENGTH = 64

    fun decode(bytes: ByteArray): ProtocolEnvelope {
        val root = try {
            StrictJson.parseObject(bytes)
        } catch (error: JsonParseException) {
            throw ProtocolException(error.message ?: "invalid JSON")
        }
        val version = root.requireLong("protocolVersion")
        if (version != VERSION.toLong()) throw ProtocolException("unsupported protocolVersion")
        val requestId = requireIdentifier(root, "requestId", MAX_REQUEST_ID_LENGTH)
        val sessionId = requireIdentifier(root, "sessionId", MAX_SESSION_ID_LENGTH, allowEmpty = true)
        val sequence = root.requireLong("sequence")
        if (sequence <= 0L) throw ProtocolException("sequence must be in 1..Long.MAX_VALUE")
        val type = root.requireString("type", MAX_TYPE_LENGTH)
        if (!TYPE.matches(type)) throw ProtocolException("invalid message type")
        val payload = root.requireObject("payload")
        return ProtocolEnvelope(VERSION, requestId, sessionId, sequence, type, payload)
    }

    private fun requireIdentifier(
        objectValue: JsonValue.ObjectValue,
        field: String,
        maxLength: Int,
        allowEmpty: Boolean = false,
    ): String {
        val value = objectValue.requireString(field, maxLength)
        if ((value.isEmpty() && !allowEmpty) || (value.isNotEmpty() && !IDENTIFIER.matches(value))) {
            throw ProtocolException("invalid $field")
        }
        return value
    }

    fun encode(envelope: ProtocolEnvelope): ByteArray {
        require(envelope.protocolVersion == VERSION) { "unsupported protocolVersion" }
        require(IDENTIFIER.matches(envelope.requestId)) { "invalid requestId" }
        require(envelope.sessionId.isEmpty() || IDENTIFIER.matches(envelope.sessionId)) { "invalid sessionId" }
        require(envelope.sequence > 0L) { "invalid sequence" }
        require(TYPE.matches(envelope.type)) { "invalid type" }
        return StrictJson.encode(
            jsonObject(
                "protocolVersion" to jsonLong(VERSION.toLong()),
                "requestId" to jsonString(envelope.requestId),
                "sessionId" to jsonString(envelope.sessionId),
                "sequence" to jsonLong(envelope.sequence),
                "type" to jsonString(envelope.type),
                "payload" to envelope.payload,
            ),
        ).also {
            if (it.size > FrameCodec.MAX_FRAME_SIZE) throw ProtocolException("encoded frame exceeds 64 KiB")
        }
    }

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val TYPE = Regex("[a-z][a-z0-9_]{0,63}")
}
