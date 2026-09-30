package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-29: qualitative box-growth trend compares change over observation time, not sample count. */
class PseudoTrendFrameRateRegressionTest {
    private fun run(intervalMs: Long, growthPerSecond: Float, durationMs: Long = 2_000L): TrackedObjectDepth {
        val pipeline = ObjectDepthRuntimePipeline()
        var last: TrackedObjectDepth? = null
        var t = 0L
        while (t <= durationMs) {
            val w = 0.10f * exp(growthPerSecond * t / 1000f)
            val h = 2f * w
            val atMs = 10_000L + t
            last = pipeline.processPrealignedForTest(
                DepthFrameSnapshot(atMs * 1_000_000L, null, null, null),
                frameId = atMs * 1_000_000L,
                timestampMs = atMs,
                detections = listOf(
                    DetectionCandidate("person", 0.9f, RectNorm(0.5f - w / 2f, 0.5f - h / 2f, w, h)),
                ),
            ).single()
            t += intervalMs
        }
        return requireNotNull(last)
    }

    @Test
    fun sameGrowthPerSecondGivesTheSameQualitativeTrendAtEveryObservationRate() {
        // Before the fix: 300 ms -> APPROACHING, 100 ms and 33 ms -> STABLE for this approach.
        val outputs = listOf(300L, 100L, 33L).map { it to run(it, 0.15f) }

        outputs.forEach { (intervalMs, output) ->
            assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, output.source)
            assertEquals("interval=$intervalMs", Trend.APPROACHING, output.trend)
            assertEquals(MessageLevel.CAUTION, output.userFacing.messageLevel)
            // Still qualitative: no meters, speed or time-to-collision are manufactured.
            assertNull(output.riskDistanceM)
            assertNull(output.approachSpeedMps)
            assertNull(output.timeToCollisionMs)
        }
        val scores = outputs.map { it.second.approachScore }
        assertTrue("scores=$scores", scores.max() - scores.min() < 0.05f)
    }

    @Test
    fun threeHundredMsCadenceKeepsTheFormerFiveSampleScore() {
        // Unmodified tree, same input: 300 ms cadence scored 0.66317844 (logs/q-s1129-before.log).
        val output = run(300L, 0.15f)

        assertEquals(FORMER_300MS_SCORE, output.approachScore, 0.005f)
    }

    @Test
    fun steadyBoxStaysStableAtEveryObservationRate() {
        listOf(300L, 100L, 33L).forEach { intervalMs ->
            val output = run(intervalMs, 0f)
            assertEquals(Trend.STABLE, output.trend)
            assertEquals(MessageLevel.NONE, output.userFacing.messageLevel)
        }
    }

    @Test
    fun observationsCrowdedIntoAShortSpanAreUnknownInsteadOfStable() {
        // Three stable-track observations 40 ms apart cover only 80 ms of motion.
        val output = run(40L, 0f, durationMs = 80L)

        assertEquals(Trend.UNKNOWN, output.trend)
        assertEquals(0f, output.approachScore)
    }

    @Test
    fun observationsOlderThanTheRetentionAreNotCompared() {
        // One observation per second: only the latest two lie inside the 1.5 s retention.
        val output = run(1_000L, 0.6f, durationMs = 3_000L)

        assertEquals(Trend.UNKNOWN, output.trend)
        assertEquals(MessageLevel.NONE, output.userFacing.messageLevel)
    }

    private companion object {
        const val FORMER_300MS_SCORE = 0.66317844f
    }
}
