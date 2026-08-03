package dev.lucasdone.tvremote.agent.media

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class MediaPacketChannel(
    output: OutputStream,
    private val closeTransport: () -> Unit = { output.close() },
) : MediaPacketSink, AutoCloseable {
    private val output = BufferedOutputStream(output, OUTPUT_BUFFER_SIZE)
    private val queue = ArrayDeque<MediaPacket>()
    private val closed = AtomicBoolean(false)
    private val interrupted = AtomicBoolean(false)
    private var queuedBytes = 0
    private var discontinuityPending = false

    @Synchronized
    override fun offer(packet: MediaPacket): Boolean {
        if (closed.get()) return false
        var droppedQueuedVideo = false
        while (queue.size >= MAX_PACKETS || queuedBytes + packet.payload.size > MAX_QUEUED_BYTES) {
            val droppable = queue.indexOfFirst {
                it.header.track == MediaTrack.VIDEO && !it.header.keyFrame && !it.header.codecConfig
            }
            if (droppable < 0) return false
            val removed = queue.removeAt(droppable)
            queuedBytes -= removed.payload.size
            discontinuityPending = true
            droppedQueuedVideo = true
        }
        val stored = if (discontinuityPending && packet.header.track == MediaTrack.VIDEO && packet.header.keyFrame) {
            discontinuityPending = false
            packet.copy(header = packet.header.copy(discontinuity = true))
        } else {
            packet
        }
        queue.addLast(stored)
        queuedBytes += stored.payload.size
        (this as java.lang.Object).notifyAll()
        val ordinaryVideo = packet.header.track == MediaTrack.VIDEO && !packet.header.keyFrame && !packet.header.codecConfig
        return !droppedQueuedVideo || !ordinaryVideo
    }

    @Throws(IOException::class)
    fun writeLoop() {
        try {
            while (!closed.get()) {
                val packet = take() ?: break
                output.write(packet.header.encode())
                output.write(packet.payload)
                val shouldFlush = synchronized(this) { queue.isEmpty() }
                if (shouldFlush) output.flush()
            }
        } finally {
            // 进入 finally 前保存中断状态快照：close() 不会修改 interrupted 标志，但基于快照
            // 才能在 close() 之后区分「中断退出」与「正常关闭」，且 close() 本身不受异常影响。
            val wasInterrupted = interrupted.get()
            // close() 幂等（AtomicBoolean CAS 保护），此处只真正执行一次关闭。
            close()
            if (wasInterrupted) {
                // 中断标志已在 take() 中通过 Thread.currentThread().interrupt() 恢复并保持，
                // 此处抛出 InterruptedIOException 供调用方区分结束原因并可重试。
                throw InterruptedIOException("media write loop was interrupted")
            }
        }
    }

    @Synchronized
    private fun take(): MediaPacket? {
        while (queue.isEmpty() && !closed.get()) {
            try {
                (this as java.lang.Object).wait()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                interrupted.set(true)
                return null
            }
        }
        if (queue.isEmpty()) return null
        return queue.removeFirst().also { queuedBytes -= it.payload.size }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) {
            queue.clear()
            queuedBytes = 0
            (this as java.lang.Object).notifyAll()
        }
        runCatching(closeTransport)
    }

    private fun <T> ArrayDeque<T>.removeAt(index: Int): T {
        val iterator = iterator()
        var cursor = 0
        while (iterator.hasNext()) {
            val value = iterator.next()
            if (cursor++ == index) {
                iterator.remove()
                return value
            }
        }
        throw IndexOutOfBoundsException(index.toString())
    }

    companion object {
        const val MAX_QUEUED_BYTES = 16 * 1024 * 1024
        const val MAX_PACKETS = 128
        private const val OUTPUT_BUFFER_SIZE = 64 * 1024
    }
}
