package dev.lucasdone.tvremote.agent.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class QualityReductionControllerTest {
    @Test
    fun `thirty consecutive ordinary packet rejections request one reduction`() {
        val controller = QualityReductionController()
        repeat(29) { assertFalse(controller.onVideoResult(accepted = false, ordinary = true)) }
        assertTrue(controller.onVideoResult(accepted = false, ordinary = true))
        assertFalse(controller.onVideoResult(accepted = false, ordinary = true))
        assertEquals(QualityReductionController.State.PENDING, controller.snapshot())
    }

    @Test
    fun `successful ordinary packet resets rejection count`() {
        val controller = QualityReductionController(3)
        repeat(2) { assertFalse(controller.onVideoResult(accepted = false, ordinary = true)) }
        assertFalse(controller.onVideoResult(accepted = true, ordinary = true))
        repeat(2) { assertFalse(controller.onVideoResult(accepted = false, ordinary = true)) }
        assertTrue(controller.onVideoResult(accepted = false, ordinary = true))
    }

    @Test
    fun `successful key frame resets ordinary rejection count`() {
        val controller = QualityReductionController()
        repeat(29) { assertFalse(controller.onVideoResult(accepted = false, ordinary = true)) }
        assertFalse(controller.onVideoResult(accepted = true, ordinary = false))
        assertEquals(0, controller.rejectedPacketCount())
        repeat(29) { assertFalse(controller.onVideoResult(accepted = false, ordinary = true)) }
        assertTrue(controller.onVideoResult(accepted = false, ordinary = true))
    }

    @Test
    fun `severe thermal status requests reduction`() {
        val controller = QualityReductionController()
        assertFalse(controller.onThermalStatus(QualityReductionController.THERMAL_STATUS_SEVERE - 1))
        assertTrue(controller.onThermalStatus(QualityReductionController.THERMAL_STATUS_SEVERE))
    }

    @Test
    fun `concurrent triggers can request only once`() {
        val controller = QualityReductionController(1)
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (0 until 32).map {
            workers.submit<Boolean> {
                start.await()
                if (it % 2 == 0) controller.onVideoResult(accepted = false, ordinary = true)
                else controller.onThermalStatus(QualityReductionController.THERMAL_STATUS_SEVERE)
            }
        }
        start.countDown()
        assertEquals(1, results.count { it.get() })
        workers.shutdownNow()
    }

    @Test
    fun `reduction is one way and failure is terminal`() {
        val reduced = QualityReductionController(1)
        assertTrue(reduced.onVideoResult(accepted = false, ordinary = true))
        assertTrue(reduced.markReduced())
        assertFalse(reduced.onThermalStatus(QualityReductionController.THERMAL_STATUS_SEVERE))
        assertEquals(QualityReductionController.State.REDUCED, reduced.snapshot())

        val failed = QualityReductionController(1)
        assertTrue(failed.onVideoResult(accepted = false, ordinary = true))
        assertTrue(failed.markFailed())
        assertFalse(failed.onVideoResult(accepted = false, ordinary = true))
        assertEquals(QualityReductionController.State.FAILED, failed.snapshot())
    }
}
