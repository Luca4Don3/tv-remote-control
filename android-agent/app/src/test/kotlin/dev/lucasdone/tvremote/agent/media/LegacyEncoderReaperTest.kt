package dev.lucasdone.tvremote.agent.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyEncoderReaperTest {
    @Test
    fun drainFinallyReleasesRetiredResourceExactlyOnce() {
        val releases = AtomicInteger()
        val started = CountDownLatch(1)
        val reaper = LegacyEncoderReaper<Any>(250, 5_000) { 0L }
        val drain = Thread {
            try {
                started.countDown()
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                // retire() uses interruption to stop the legacy drain.
            } finally {
                reaper.reap()
            }
        }.apply { isDaemon = true }

        drain.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(reaper.retire(Any(), drain, stop = {}, release = { releases.incrementAndGet() }))
        assertFalse(drain.isAlive)
        reaper.reap()
        assertEquals(1, releases.get())
    }

    @Test
    fun drainWhichAlreadyExitedIsStillReleased() {
        val releases = AtomicInteger()
        val reaper = LegacyEncoderReaper<Any>(250, 5_000) { 0L }
        val drain = Thread {}
        drain.start()
        drain.join()

        assertTrue(reaper.retire(Any(), drain, stop = {}, release = { releases.incrementAndGet() }))
        reaper.reap()
        assertEquals(1, releases.get())
    }

    @Test
    fun timedOutDrainIsReleasedWhenItEventuallyExits() {
        val releases = AtomicInteger()
        val started = CountDownLatch(1)
        val mayExit = AtomicBoolean(false)
        val reaper = LegacyEncoderReaper<Any>(1, 5_000) { 0L }
        val drain = Thread {
            try {
                started.countDown()
                while (!mayExit.get()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
                    Thread.interrupted()
                }
            } finally {
                reaper.reap()
            }
        }.apply { isDaemon = true }

        drain.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertFalse(reaper.retire(Any(), drain, stop = {}, release = { releases.incrementAndGet() }))
        assertEquals(0, releases.get())

        mayExit.set(true)
        LockSupport.unpark(drain)
        drain.join(1_000)
        assertFalse(drain.isAlive)
        assertEquals(1, releases.get())
        reaper.reap()
        assertEquals(1, releases.get())
    }
}
