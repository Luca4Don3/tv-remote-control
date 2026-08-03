package dev.lucasdone.tvremote.agent.media

import android.util.Log

internal class LegacyEncoderReaper<T>(
    private val joinTimeoutMs: Long,
    private val forcedReleaseTimeoutMs: Long,
    private val nowMs: () -> Long,
    private val diagnostic: (String) -> Unit = { Log.w("TVRC-Encoder", it) },
) {
    private val lock = Any()
    private val retired = mutableListOf<Retired<T>>()

    fun retire(
        resource: T,
        drain: Thread,
        stop: (T) -> Unit,
        release: (T) -> Unit,
    ): Boolean {
        val entry = Retired(resource, drain, release, nowMs())
        // Publish retirement before interrupting the drain. If the drain exits
        // immediately, its finally block can already observe and reap it.
        synchronized(lock) { retired += entry }
        drain.interrupt()
        runCatching { stop(resource) }.onFailure {
            diagnostic("legacy stop failed: ${it.javaClass.simpleName}")
        }
        if (drain !== Thread.currentThread()) runCatching { drain.join(joinTimeoutMs) }.onFailure {
            diagnostic("legacy drain join failed: ${it.javaClass.simpleName}")
        }
        reap()
        return synchronized(lock) { entry !in retired }
    }

    fun reap(wait: Boolean = false) {
        if (wait) {
            val drains = synchronized(lock) { retired.map { it.drain }.distinct() }
            drains.forEach { drain ->
                drain.interrupt()
                if (drain !== Thread.currentThread()) runCatching { drain.join(joinTimeoutMs) }.onFailure {
                    diagnostic("legacy drain join failed: ${it.javaClass.simpleName}")
                }
            }
        }
        val now = nowMs()
        synchronized(lock) {
            val iterator = retired.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val forced = now - entry.retiredAtMs >= forcedReleaseTimeoutMs
                if (!entry.drain.isAlive || entry.drain === Thread.currentThread() || forced) {
                    entry.releaseAttempts++
                    val released = runCatching { entry.release(entry.resource) }
                    if (released.isSuccess) {
                        iterator.remove()
                    } else {
                        diagnostic(
                            "legacy release failed (attempt ${entry.releaseAttempts}): " +
                                released.exceptionOrNull()!!.javaClass.simpleName,
                        )
                    }
                }
            }
        }
    }

    private class Retired<T>(
        val resource: T,
        val drain: Thread,
        val release: (T) -> Unit,
        val retiredAtMs: Long,
        var releaseAttempts: Int = 0,
    )
}
