package dev.lucasdone.tvremote.xiaomi.input

import dev.lucasdone.tvremote.agent.model.AckStatus

/** The IME owns this session. It stores a commit callback, never editor text. */
class RemoteTextSession {
    private var generation = 0L
    private var commit: ((String) -> Boolean)? = null
    @Synchronized fun attach(commitText: (String) -> Boolean): Long {
        generation += 1
        commit = commitText
        return generation
    }
    @Synchronized fun detach() { generation += 1; commit = null }
    @Synchronized fun ticket(): Long? = if (commit == null) null else generation
    @Synchronized fun submit(ticket: Long, text: String): AckStatus {
        if (ticket != generation || commit == null) return AckStatus.PERMISSION_DENIED
        if (text.isEmpty() || text.length > 4096) return AckStatus.REJECTED
        return if (commit!!.invoke(text)) AckStatus.SUCCESS else AckStatus.EXECUTION_FAILED
    }
}
