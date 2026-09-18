package dev.tvremote.agent.service

import java.util.concurrent.atomic.AtomicLong

/**
 * Hands out a monotonically increasing token to each service instance. A late write from a previous
 * instance (e.g. a backend probe or onDestroy completing after a fast restart) is dropped because its
 * token is no longer current, so shared UI state cannot be overwritten by a stopped instance.
 */
internal class GenerationGate {
    private val current = AtomicLong(0)

    fun begin(): Long = current.incrementAndGet()

    fun isCurrent(token: Long): Boolean = current.get() == token
}
