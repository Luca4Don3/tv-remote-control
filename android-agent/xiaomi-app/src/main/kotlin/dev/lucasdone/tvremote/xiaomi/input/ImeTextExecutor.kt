package dev.lucasdone.tvremote.xiaomi.input

import android.os.Handler
import android.os.Looper
import dev.lucasdone.tvremote.agent.command.TextCommandExecutor
import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.TextAction
import dev.lucasdone.tvremote.agent.model.TextCommand
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ImeTextExecutor : TextCommandExecutor {
    override fun supportsText() = true
    override fun executeText(command: TextCommand): AckStatus {
        if (command.action != TextAction.COMMIT) return AckStatus.UNSUPPORTED
        val session = RemoteInputMethodService.session
        val ticket = session.ticket() ?: return AckStatus.PERMISSION_DENIED
        if (Looper.myLooper() == Looper.getMainLooper()) return session.submit(ticket, command.text)

        val state = AtomicInteger(PENDING)
        val done = CountDownLatch(1)
        var result = AckStatus.EXECUTION_FAILED
        val handler = Handler(Looper.getMainLooper())
        val action = Runnable {
            // Commit only while still pending. A timeout moves the state to ABANDONED first, so a late
            // run cannot deliver text after the caller already reported failure (at-most-once).
            if (state.compareAndSet(PENDING, COMMITTING)) {
                try {
                    result = session.submit(ticket, command.text)
                } catch (_: RuntimeException) {
                    result = AckStatus.EXECUTION_FAILED
                }
            }
            done.countDown()
        }
        if (!handler.post(action)) return AckStatus.EXECUTION_FAILED

        val finished = try {
            done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            state.compareAndSet(PENDING, ABANDONED)
            handler.removeCallbacks(action)
            Thread.currentThread().interrupt()
            return AckStatus.EXECUTION_FAILED
        }
        if (finished) return result
        if (state.compareAndSet(PENDING, ABANDONED)) {
            handler.removeCallbacks(action)
            return AckStatus.EXECUTION_FAILED
        }
        // The action already started committing; report its real result rather than guessing.
        return try {
            if (done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) result else AckStatus.EXECUTION_FAILED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            AckStatus.EXECUTION_FAILED
        }
    }

    private companion object {
        const val PENDING = 0
        const val COMMITTING = 1
        const val ABANDONED = 2
        const val TIMEOUT_MS = 1500L
    }
}
