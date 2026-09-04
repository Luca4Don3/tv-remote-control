package dev.lucasdone.tvremote.agent.transport.ws

/**
 * 滑动窗口防重放（与 Rust core `replay.rs` 语义对齐）：
 * 高水位 + 窗口位图；窗口内未见过的延迟序号接受，重复/过旧/跨窗口拒绝。
 */
class ReplayWindow(private val windowBits: Int) {
    init {
        require(windowBits in 1..64) { "window must be 1..=64" }
    }

    private var highWatermark: Long = 0L
    private var window: Long = 0L

    @Synchronized
    fun checkAndAccept(sequence: Long): Boolean {
        if (sequence <= 0L) return false
        if (highWatermark == 0L) {
            highWatermark = sequence
            window = 0L
            return true
        }
        if (sequence > highWatermark) {
            val advance = sequence - highWatermark
            window = if (advance >= windowBits) 0L else (window shl advance.toInt()) and mask()
            if (advance <= windowBits.toLong()) {
                window = window or (1L shl (advance.toInt() - 1))
            }
            highWatermark = sequence
            return true
        }
        val delta = highWatermark - sequence
        if (delta == 0L || delta > windowBits.toLong()) return false
        val bit = 1L shl (delta.toInt() - 1)
        if (window and bit != 0L) return false
        window = window or bit
        return true
    }

    @Synchronized
    fun highWatermark(): Long = highWatermark

    private fun mask(): Long = if (windowBits >= 64) -1L else (1L shl windowBits) - 1L
}
