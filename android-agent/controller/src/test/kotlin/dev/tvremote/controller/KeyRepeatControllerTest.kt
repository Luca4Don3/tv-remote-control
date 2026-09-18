package dev.tvremote.controller

import dev.tvremote.controller.session.ControllerSession
import dev.tvremote.controller.session.KeyRepeatController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长按重复（每键串行队列）：DOWN → 递增 REPEAT → UP；快速重按有序不丢；失败释放。 */
class KeyRepeatControllerTest {
    private fun success() = ControllerSession.AckResult(1L, "SUCCESS", null)
    private fun failure() = ControllerSession.AckResult(1L, "UNSUPPORTED", "no executor")

    @Test
    fun longPressSendsDownThenIncreasingRepeatsThenUp() = runBlocking {
        val events = mutableListOf<Triple<String, String, Int>>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { key, state, count -> events += Triple(key, state, count); success() },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        try {
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
            assertFalse(controller.isRepeating("DPAD_UP"))
        } finally {
            controller.stopAll()
        }
    }

    /** R1：快速 begin→end→begin 必须严格有序 DOWN, UP, DOWN（重按不丢、旧 UP 不落到新 DOWN 之后）。 */
    @Test
    fun fastRepressIsOrderedDownUpDown() = runBlocking {
        val events = mutableListOf<String>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { _, state, _ -> events += state; success() },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        try {
            controller.begin("DPAD_DOWN")
            controller.end("DPAD_DOWN")
            controller.begin("DPAD_DOWN")
            delay(120)
            controller.end("DPAD_DOWN")
            delay(60)
            assertTrue("至少两次 DOWN：$events", events.count { it == "DOWN" } >= 2)
            assertEquals(listOf("DOWN", "UP", "DOWN"), events.take(3))
        } finally {
            controller.stopAll()
        }
    }

    /** R1：DOWN 在途（慢 ack）时松手，DOWN 成功后必须补发 UP。 */
    @Test
    fun releaseDuringInFlightDownStillSendsUp() = runBlocking {
        val events = mutableListOf<String>()
        val downGate = CompletableDeferred<Unit>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { _, state, _ ->
                events += state
                if (state == "DOWN") downGate.await()
                success()
            },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        try {
            controller.begin("VOLUME_UP")
            controller.end("VOLUME_UP") // 此时 DOWN 尚未返回
            delay(60)
            downGate.complete(Unit)
            delay(120)
            assertEquals("DOWN", events.first())
            assertTrue("DOWN 成功后必须补发 UP：$events", events.drop(1).contains("UP"))
            assertEquals("UP", events.last())
        } finally {
            controller.stopAll()
        }
    }

    /** R1：DOWN 失败（UNSUPPORTED）不发 UP。 */
    @Test
    fun downFailureDoesNotSendUp() = runBlocking {
        val events = mutableListOf<String>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { _, state, _ -> events += state; failure() },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        try {
            controller.begin("POWER")
            delay(40)
            controller.end("POWER")
            delay(60)
            assertEquals(listOf("DOWN"), events)
            assertFalse(controller.isRepeating("POWER"))
        } finally {
            controller.stopAll()
        }
    }

    /** R1：REPEAT 失败自动释放一次（补发 UP），之后可再次按下。 */
    @Test
    fun repeatFailureAutoReleasesAndAllowsRepress() = runBlocking {
        val events = mutableListOf<String>()
        var repeatsSucceed = true
        val controller = KeyRepeatController(
            scope = this,
            sender = { _, state, _ ->
                events += state
                if (state == "REPEAT" && !repeatsSucceed) failure() else success()
            },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        try {
            controller.begin("DPAD_UP")
            delay(30)
            repeatsSucceed = false
            delay(80)
            assertTrue("应出现失败 REPEAT 后补发 UP：$events", events.contains("UP"))
            assertFalse(controller.isRepeating("DPAD_UP"))

            repeatsSucceed = true
            controller.begin("DPAD_UP")
            delay(20)
            controller.end("DPAD_UP")
            delay(60)
            assertEquals(2, events.count { it == "DOWN" })
        } finally {
            controller.stopAll()
        }
    }

    @Test
    fun stopAllStopsWithoutSendingUp() = runBlocking {
        val events = mutableListOf<String>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { _, state, _ -> events += state; success() },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        try {
            controller.begin("VOLUME_UP")
            delay(60)
            controller.stopAll()
            delay(60)
            assertFalse(events.any { it == "UP" })
            assertFalse(controller.isRepeating("VOLUME_UP"))
            assertTrue(controller.pressedKeys().isEmpty())
        } finally {
            controller.stopAll()
        }
    }

    @Test
    fun releaseAllSendsUpForPressedKeys() = runBlocking {
        val events = mutableListOf<String>()
        val controller = KeyRepeatController(
            scope = this,
            sender = { _, state, _ -> events += state; success() },
            initialDelayMs = 20,
            intervalMs = 20,
        )
        try {
            controller.begin("DPAD_LEFT")
            delay(60)
            controller.releaseAll()
            delay(60)
            assertTrue("releaseAll 应补发 UP：$events", events.contains("UP"))
            assertTrue(controller.pressedKeys().isEmpty())
        } finally {
            controller.stopAll()
        }
    }

    /** 切设备语义：stopAll 后旧控制器不再发送；新控制器独立工作。 */
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
        try {
            oldController.begin("DPAD_UP")
            delay(70)
            oldController.stopAll()
            delay(30) // 等待取消在挂起点生效（可能在途一次 REPEAT）
            val frozen = oldEvents.size
            newController.begin("DPAD_DOWN")
            delay(90)
            newController.stopAll()
            delay(40)
            assertEquals("旧控制器 stopAll 后不得再发送", frozen, oldEvents.size)
            assertEquals("DOWN", newEvents.first())
            assertTrue(newEvents.count { it == "REPEAT" } >= 1)
            assertEquals(emptyList<String>(), oldController.pressedKeys())
        } finally {
            oldController.stopAll()
            newController.stopAll()
        }
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
        try {
            controller.begin("DPAD_DOWN")
            controller.begin("DPAD_DOWN")
            delay(30)
            assertEquals(1, events.count { it == "DOWN" })
        } finally {
            controller.stopAll()
        }
    }
}
