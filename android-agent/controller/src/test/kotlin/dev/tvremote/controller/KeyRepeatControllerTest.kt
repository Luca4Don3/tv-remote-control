package dev.tvremote.controller

import dev.tvremote.controller.session.ControllerSession
import dev.tvremote.controller.session.KeyRepeatController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长按重复：DOWN → 递增 REPEAT → UP；松手/取消停止；失败停止；单路串行。 */
class KeyRepeatControllerTest {
    private fun success() = ControllerSession.AckResult(1L, "SUCCESS", null)
    private fun failure() = ControllerSession.AckResult(1L, "UNSUPPORTED", "no executor")

    @Test
    fun longPressSendsDownThenIncreasingRepeatsThenUp() = runBlocking {
        val events = mutableListOf<Triple<String, String, Int>>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { key, state, count ->
                events += Triple(key, state, count)
                success()
            },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        controller.begin("DPAD_UP")
        delay(120)
        controller.end("DPAD_UP")
        delay(80)

        assertEquals("DOWN", events.first().second)
        assertEquals("UP", events.last().second)
        val repeatCounts = events.filter { it.second == "REPEAT" }.map { it.third }
        assertTrue("should repeat at least twice: $repeatCounts", repeatCounts.size >= 2)
        assertEquals(1, repeatCounts.first())
        assertEquals("repeatCount 必须严格递增", repeatCounts.sorted(), repeatCounts)
        assertTrue(repeatCounts.distinct().size == repeatCounts.size)
        assertFalse(controller.isRepeating("DPAD_UP"))
    }

    @Test
    fun stopAllStopsWithoutSendingUp() = runBlocking {
        val events = mutableListOf<Triple<String, String, Int>>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { key, state, count -> events += Triple(key, state, count); success() },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        controller.begin("VOLUME_UP")
        delay(60)
        controller.stopAll()
        delay(60)
        assertFalse(events.any { it.second == "UP" })
        assertFalse(controller.isRepeating("VOLUME_UP"))
    }

    @Test
    fun repeatStopsWhenAckFails() = runBlocking {
        val events = mutableListOf<String>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { _, state, _ ->
                events += state
                if (state == "REPEAT") failure() else success()
            },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        controller.begin("MEDIA_NEXT")
        delay(120)
        val count = events.size
        delay(100)
        assertEquals("失败后不得继续 REPEAT", count, events.size)
        assertTrue(events.contains("DOWN"))
    }

    @Test
    fun downFailureDoesNotRepeat() = runBlocking {
        val events = mutableListOf<String>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { _, state, _ -> events += state; failure() },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        controller.begin("POWER")
        delay(80)
        assertEquals(listOf("DOWN"), events)
        assertFalse(controller.isRepeating("POWER"))
    }

    /** R03：REPEAT 失败后任务结束，松手仍必须补发 UP，并允许再次按下同键。 */
    @Test
    fun failedRepeatStillSendsUpOnReleaseAndAllowsRepress() = runBlocking {
        val events = mutableListOf<String>()
        var repeatsSucceed = true
        val controller = KeyRepeatController(
            scope = this,
            sender = { _, state, _ ->
                events += state
                when {
                    state == "REPEAT" && !repeatsSucceed -> failure()
                    else -> success()
                }
            },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        controller.begin("DPAD_UP")
        delay(30)
        repeatsSucceed = false
        delay(70)
        assertTrue("应发出 DOWN 与失败 REPEAT：$events", events.contains("DOWN") && events.contains("REPEAT"))
        assertFalse("失败后重复任务应结束", controller.isRepeating("DPAD_UP"))

        controller.end("DPAD_UP")
        delay(30)
        assertEquals("松手必须补发 UP", "UP", events.last())
        assertTrue(controller.pressedKeys().isEmpty())

        controller.begin("DPAD_UP")
        delay(20)
        controller.end("DPAD_UP")
        delay(20)
        assertEquals("同键应可再次按下", 2, events.count { it == "DOWN" })
    }

    /** 切设备/断线语义：stopAll 后旧控制器不得再发送；绑定到新会话的控制器独立工作。 */
    @Test
    fun stopAllFreezesOldControllerAndRebindingIsIndependent() = runBlocking {
        val oldEvents = mutableListOf<String>()
        val newEvents = mutableListOf<String>()
        val oldController = KeyRepeatController(
            scope = this,
            sender = { _, state, _ -> oldEvents += state; success() },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        val newController = KeyRepeatController(
            scope = this,
            sender = { _, state, _ -> newEvents += state; success() },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        oldController.begin("DPAD_UP")
        delay(70)
        oldController.stopAll()
        val frozen = oldEvents.size
        newController.begin("DPAD_DOWN")
        delay(90)
        newController.stopAll()
        delay(40)
        assertEquals("旧控制器 stopAll 后不得再发送", frozen, oldEvents.size)
        assertEquals("DOWN", newEvents.first())
        assertTrue(newEvents.count { it == "REPEAT" } >= 1)
        assertEquals(emptyList<String>(), oldController.pressedKeys())
    }

    @Test
    fun singleTaskPerKey() = runBlocking {
        val events = mutableListOf<String>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { _, state, _ -> events += state; success() },
            initialDelayMs = 100,
            intervalMs = 100,
        )
        controller.begin("DPAD_DOWN")
        controller.begin("DPAD_DOWN")
        delay(30)
        assertEquals(1, events.count { it == "DOWN" })
        controller.stopAll()
    }
}
