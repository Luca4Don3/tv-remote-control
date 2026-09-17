package dev.lucasdone.tvremote.controller.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 长按重复控制器（每键串行队列）：
 * `begin`/`end` 只把 Press/Release 入队，每键一个 worker 串行执行
 * DOWN → 定时 REPEAT（repeatCount 递增）→ UP，保证按键事件严格有序、快速重按不丢不乱序。
 *
 * - 只有 DOWN 成功才发送 UP；DOWN 失败不发 UP。
 * - REPEAT 失败即结束本次按下并补发 UP（避免电视保留按下状态）。
 * - 松手 [end] / 导航离开 [releaseAll]：入队 Release，DOWN 在途时会在其成功后立即释放。
 * - 切设备/退后台/断线 [stopAll]：取消 worker，不补发 UP（由电视端随断连释放）。
 * - 不依赖 Android，可用真实协程作用域做单元测试。
 */
class KeyRepeatController(
    private val scope: CoroutineScope,
    private val sender: suspend (key: String, state: String, repeatCount: Int) -> ControllerSession.AckResult,
    private val initialDelayMs: Long = 400L,
    private val intervalMs: Long = 120L,
    private val onAck: (key: String, ack: ControllerSession.AckResult) -> Unit = { _, _ -> },
    private val onError: (Throwable) -> Unit = {},
) {
    private sealed interface Command {
        data object Press : Command
        data object Release : Command
    }

    private class Worker(val channel: Channel<Command>, val job: Job)

    private val workers = LinkedHashMap<String, Worker>()
    private val pressed = LinkedHashSet<String>()

    /** 入队一次按下（同一键的重复 begin 会被 worker 视为已在按下而忽略）。 */
    @Synchronized
    fun begin(key: String) {
        val worker = workers[key] ?: createWorker(key).also { workers[key] = it }
        worker.channel.trySend(Command.Press)
    }

    /** 松手/手势取消：入队释放。 */
    @Synchronized
    fun end(key: String) {
        workers[key]?.channel?.trySend(Command.Release)
    }

    /** 导航离开、仍需保活连接时：对已按下/在途的键入队释放并补发 UP。 */
    @Synchronized
    fun releaseAll() {
        workers.values.forEach { it.channel.trySend(Command.Release) }
    }

    /** 切设备/退后台/断线：停止所有 worker，不补发 UP。 */
    @Synchronized
    fun stopAll() {
        workers.values.forEach {
            it.job.cancel()
            it.channel.close()
        }
        workers.clear()
        pressed.clear()
    }

    @Synchronized
    fun pressedKeys(): List<String> = pressed.toList()

    @Synchronized
    fun isRepeating(key: String): Boolean = pressed.contains(key)

    private fun createWorker(key: String): Worker {
        val channel = Channel<Command>(Channel.UNLIMITED)
        val job = scope.launch {
            try {
                runWorker(key, channel)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onError(error)
            } finally {
                synchronized(this@KeyRepeatController) {
                    if (workers[key]?.job === coroutineContext[Job]) workers.remove(key)
                }
            }
        }
        return Worker(channel, job)
    }

    private suspend fun runWorker(key: String, channel: Channel<Command>) {
        // 取消由协程取消在挂起点抛出（receive/withTimeout），无需显式判活
        while (true) {
            val first = channel.receiveCatching().getOrNull() ?: return
            if (first is Command.Release) continue

            val down = sender(key, "DOWN", 0)
            onAck(key, down)
            if (!down.isSuccess) continue
            synchronized(this) { pressed.add(key) }

            // 重复阶段：等待间隔或 Release；超时则发 REPEAT
            var repeatCount = 1
            while (true) {
                val waitMs = if (repeatCount == 1) initialDelayMs else intervalMs
                val next = withTimeoutOrNull(waitMs) { channel.receiveCatching().getOrNull() }
                if (next is Command.Release) break
                if (next == null) {
                    // 超时（或通道关闭）：发送一次重复
                    val ack = sender(key, "REPEAT", repeatCount)
                    if (!ack.isSuccess) {
                        onAck(key, ack)
                        break // REPEAT 失败：结束本次按下并释放
                    }
                    repeatCount += 1
                }
                // next 为 Press（理论上持有期间不会出现）：忽略，继续重复
            }

            if (synchronized(this) { pressed.remove(key) }) {
                runCatching { sender(key, "UP", 0) }
            }
        }
    }
}
