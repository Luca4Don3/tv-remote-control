package dev.lucasdone.tvremote.controller.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 长按重复控制器：DOWN → 固定间隔串行 REPEAT（repeatCount 递增）→ UP。
 *
 * - 同一按键只允许一个任务（单路串行，避免积压）。
 * - 「重复任务」与「已成功按下的键」分开记录：即使 REPEAT 失败导致任务结束，
 *   松手 [end] 仍会补发 UP，避免电视端保留按下状态。
 * - 松手/取消调用 [end]：停止重复并尽力发送 UP。
 * - 退后台/换设备/断线调用 [stopAll]：停止重复并清空按下集合（UP 由调用方按会话可用性决定）。
 * - 不依赖 Android，可用真实协程作用域做单元测试。
 *
 * 线程安全：`jobs`/`pressed` 由本实例的监视器保护；发送在锁外进行，避免持有锁跨挂起点。
 */
class KeyRepeatController(
    private val scope: CoroutineScope,
    private val sender: suspend (key: String, state: String, repeatCount: Int) -> ControllerSession.AckResult,
    private val initialDelayMs: Long = 400L,
    private val intervalMs: Long = 120L,
    private val onAck: (key: String, ack: ControllerSession.AckResult) -> Unit = { _, _ -> },
    private val onError: (Throwable) -> Unit = {},
) {
    private val jobs = LinkedHashMap<String, Job>()
    private val pressed = LinkedHashSet<String>()

    @Synchronized
    fun begin(key: String) {
        if (jobs.containsKey(key)) return
        jobs[key] = scope.launch {
            try {
                val down = sender(key, "DOWN", 0)
                onAck(key, down)
                if (!down.isSuccess) return@launch
                synchronized(this@KeyRepeatController) { pressed.add(key) }
                var repeatCount = 1
                delay(initialDelayMs)
                while (isActive) {
                    val ack = sender(key, "REPEAT", repeatCount)
                    if (!ack.isSuccess) {
                        // 重复失败：结束重复，但保持“已按下”状态，等待松手补发 UP
                        onAck(key, ack)
                        break
                    }
                    repeatCount += 1
                    delay(intervalMs)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onError(error)
            } finally {
                synchronized(this@KeyRepeatController) {
                    if (jobs[key] === coroutineContext[Job]) jobs.remove(key)
                }
            }
        }
    }

    /** 松手/手势取消：停重复并尽力补发 UP（即使重复任务已因失败结束）。 */
    @Synchronized
    fun end(key: String) {
        jobs.remove(key)?.cancel()
        if (pressed.remove(key)) {
            scope.launch {
                runCatching { sender(key, "UP", 0) }
            }
        }
    }

    @Synchronized
    fun stopAll() {
        val pending = jobs.values.toList()
        jobs.clear()
        pending.forEach { it.cancel() }
        pressed.clear()
    }

    @Synchronized
    fun pressedKeys(): List<String> = pressed.toList()

    @Synchronized
    fun isRepeating(key: String): Boolean = jobs.containsKey(key)
}
