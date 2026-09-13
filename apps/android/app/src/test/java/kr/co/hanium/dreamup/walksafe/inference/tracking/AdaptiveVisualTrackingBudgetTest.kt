package kr.co.hanium.dreamup.walksafe.inference.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveVisualTrackingBudgetTest {
    @Test
    fun sixCpuTimestampIntervalsAreRequiredBeforeIncreasingTheBudget() {
        val policy = AdaptiveVisualTrackingBudget()
        val frames = Frames()
        assertEquals(5_000_000L, policy.budgetForFrame(frames.current))
        repeat(5) {
            assertEquals(5_000_000L, policy.budgetForFrame(frames.next(30_000_000L)))
        }

        // Elapsed capture time advances by 100ms in this fixture. The policy
        // must estimate the 30ms CPU-image cadence, not that different clock.
        assertEquals(9_000_000L, policy.budgetForFrame(frames.next(30_000_000L)))
    }

    @Test
    fun cameraRatesRespectFiveToTenMillisecondBoundsAndTheExactGapLimit() {
        val cases = listOf(
            8_333_333L to 5_000_000L,
            16_666_667L to 5_000_000L,
            33_333_333L to 9_999_999L,
            50_000_000L to 10_000_000L,
            50_000_001L to 5_000_000L,
        )
        for ((intervalNs, expectedNs) in cases) {
            val policy = AdaptiveVisualTrackingBudget()
            val frames = Frames()
            policy.budgetForFrame(frames.current)
            var budget = 0L
            repeat(6) { budget = policy.budgetForFrame(frames.next(intervalNs)) }
            assertEquals("CPU interval $intervalNs ns", expectedNs, budget)
        }
    }

    @Test
    fun jitterUsesTheLowerQuartileInsteadOfMeanOrMedian() {
        val policy = AdaptiveVisualTrackingBudget()
        val frames = Frames()
        policy.budgetForFrame(frames.current)
        val intervalsMs = listOf(40L, 30L, 25L, 20L, 35L, 45L, 22L, 24L, 48L, 32L, 28L, 26L)
        intervalsMs.forEachIndexed { index, milliseconds ->
            val budget = policy.budgetForFrame(frames.next(milliseconds * 1_000_000L))
            if (index == 5) assertEquals(7_500_000L, budget) // Six samples: second smallest is 25ms.
            if (index == 11) assertEquals(7_200_000L, budget) // Twelve samples: third smallest is 24ms.
        }
    }

    @Test
    fun onlyTheMostRecentTwelveIntervalsInfluenceTheEstimate() {
        val policy = AdaptiveVisualTrackingBudget()
        val frames = Frames()
        policy.budgetForFrame(frames.current)
        repeat(6) { policy.budgetForFrame(frames.next(20_000_000L)) }
        repeat(6) { assertEquals(6_000_000L, policy.budgetForFrame(frames.next(40_000_000L))) }
        repeat(3) { assertEquals(6_000_000L, policy.budgetForFrame(frames.next(40_000_000L))) }

        // Only two 20ms intervals remain, so the third-smallest is now 40ms.
        assertEquals(10_000_000L, policy.budgetForFrame(frames.next(40_000_000L)))
    }

    @Test
    fun repeatedLongGapsEvictOldValidIntervalsAndFreshCadenceCanRecover() {
        val policy = AdaptiveVisualTrackingBudget()
        val frames = Frames()
        policy.budgetForFrame(frames.current)
        repeat(12) { policy.budgetForFrame(frames.next(30_000_000L)) }
        repeat(6) { index ->
            val gap = if (index % 2 == 0) 67_000_000L else 100_000_000L
            assertEquals(9_000_000L, policy.budgetForFrame(frames.next(gap)))
        }
        assertEquals(5_000_000L, policy.budgetForFrame(frames.next(67_000_000L)))
        repeat(5) { assertEquals(5_000_000L, policy.budgetForFrame(frames.next(25_000_000L))) }

        assertEquals(7_500_000L, policy.budgetForFrame(frames.next(25_000_000L)))
    }

    @Test
    fun duplicateAndBackwardFramesNeitherFillTheWindowNorMoveItsTimestampAnchor() {
        val policy = AdaptiveVisualTrackingBudget()
        val frames = Frames()
        policy.budgetForFrame(frames.current)
        repeat(5) { policy.budgetForFrame(frames.next(30_000_000L)) }
        repeat(20) {
            assertEquals(5_000_000L, policy.budgetForFrame(frames.current))
            assertEquals(5_000_000L, policy.budgetForFrame(frames.current.copy(
                frameId = frames.current.frameId + 1L,
                cameraTimestampNs = frames.current.cameraTimestampNs - 100_000_000L,
            )))
        }
        assertEquals(9_000_000L, policy.budgetForFrame(frames.next(20_000_000L)))
        repeat(20) {
            assertEquals(9_000_000L, policy.budgetForFrame(frames.current))
            assertEquals(9_000_000L, policy.budgetForFrame(frames.current.copy(frameId = frames.current.frameId - 1L)))
        }
        assertEquals(9_000_000L, policy.budgetForFrame(frames.next(30_000_000L)))
    }

    @Test
    fun newerGeometryOrEpochStartsAtFiveAndLateOldContextsCannotEraseTheNewEstimate() {
        val policy = AdaptiveVisualTrackingBudget()
        var frames = Frames(epoch = 2L, geometryVersion = 4L)
        policy.budgetForFrame(frames.current)
        repeat(6) { policy.budgetForFrame(frames.next(30_000_000L)) }
        for ((epoch, geometry) in listOf(2L to 5L, 3L to 5L)) {
            val old = frames.current
            frames = Frames(epoch, geometry)
            assertEquals(5_000_000L, policy.budgetForFrame(frames.current))
            repeat(5) { assertEquals(5_000_000L, policy.budgetForFrame(frames.next(30_000_000L))) }
            assertEquals(9_000_000L, policy.budgetForFrame(frames.next(30_000_000L)))
            assertEquals(5_000_000L, policy.budgetForFrame(old))
            assertEquals(9_000_000L, policy.budgetForFrame(frames.current))
        }
    }

    @Test
    fun explicitResetClearsCadenceButKeepsTheEpochAndGeometryFloor() {
        val policy = AdaptiveVisualTrackingBudget()
        val initial = Frames(epoch = 2L, geometryVersion = 4L)
        policy.budgetForFrame(initial.current)
        repeat(6) { policy.budgetForFrame(initial.next(30_000_000L)) }
        policy.reset()
        for ((epoch, geometry) in listOf(1L to 4L, 2L to 3L)) {
            val stale = Frames(epoch, geometry)
            repeat(8) { assertEquals(5_000_000L, policy.budgetForFrame(stale.next(30_000_000L))) }
        }
        val current = Frames(epoch = 2L, geometryVersion = 4L)
        assertEquals(5_000_000L, policy.budgetForFrame(current.current))
        repeat(5) { assertEquals(5_000_000L, policy.budgetForFrame(current.next(30_000_000L))) }

        assertEquals(9_000_000L, policy.budgetForFrame(current.next(30_000_000L)))
    }

    private class Frames(epoch: Long = 1L, geometryVersion: Long = 1L) {
        var current = VisualFrameKey(epoch, 0L, 1_000_000_000L, 10_000L, geometryVersion)
            private set

        fun next(intervalNs: Long): VisualFrameKey {
            current = current.copy(
                frameId = current.frameId + 1L,
                cameraTimestampNs = current.cameraTimestampNs + intervalNs,
                capturedAtElapsedRealtimeMs = current.capturedAtElapsedRealtimeMs + 100L,
            )
            return current
        }
    }
}
