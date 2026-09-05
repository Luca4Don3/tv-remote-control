package dev.lucasdone.tvremote.controller.net

import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.ProtocolEnvelope

/** 与电视的传输抽象：`TvConnection`（TLS）实现之，测试用伪实现驱动状态机。 */
interface ConnectionTransport : AutoCloseable {
    val peerFingerprint: ByteArray

    fun nextRequestId(): String

    fun send(requestId: String, sessionId: String, type: String, payload: JsonValue.ObjectValue): ProtocolEnvelope

    /** 阻塞读一条入向信封；null 表示对端关闭。 */
    fun receive(): ProtocolEnvelope?
}
