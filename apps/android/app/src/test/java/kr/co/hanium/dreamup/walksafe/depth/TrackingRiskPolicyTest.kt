package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingRiskPolicyTest {
    @Test
    fun rejectsRiskWhenOnlyOneTrackingStabilityGatePasses() {
        val policy = TrackingRiskPolicy()

        val framesOnly = policy.evaluate(approachingSignals(stableFrames = 3, stableMs = 699L))
        val timeOnly = policy.evaluate(approachingSignals(stableFrames = 2, stableMs = 900L))
        val stable = policy.evaluate(approachingSignals(stableFrames = 3, stableMs = 700L))

        assertFalse(framesOnly.alertable)
        assertFalse(timeOnly.alertable)
        assertTrue(stable.alertable)
    }

    @Test
    fun closeMetricDistanceCannotBypassTrackingStability() {
        val decision = TrackingRiskPolicy().evaluate(
            TrackRiskSignals(
                stableFrames = 1,
                stableMs = 0L,
                distance = DistanceObservation(
                    timestampMs = 1_000L,
                    distanceM = 1.0f,
                    source = DepthSource.ARCORE_RAW_DEPTH,
                    confidence = 0.9f,
                ),
                trend = Trend.STABLE,
                timeToCollision = TtcEstimate(null, 0f, TtcSource.NONE, "none"),
                areaGrowthRatio = 0f,
                areaGrowthPerSecond = 0f,
            ),
        )

        assertFalse(decision.alertable)
    }

    @Test
    fun nullTtcDoesNotBecomeCollisionSignalByItself() {
        val policy = TrackingRiskPolicy()

        val decision = policy.evaluate(
            TrackRiskSignals(
                stableFrames = 3,
                stableMs = 700L,
                distance = null,
                trend = Trend.APPROACHING,
                timeToCollision = TtcEstimate(null, 0f, TtcSource.NONE, "unavailable"),
                areaGrowthRatio = null,
                areaGrowthPerSecond = null,
            ),
        )

        assertFalse(decision.alertable)
    }

    @Test
    fun approachingAreaGrowthCanStillWarnWithoutTtc() {
        val policy = TrackingRiskPolicy()

        val decision = policy.evaluate(
            TrackRiskSignals(
                stableFrames = 3,
                stableMs = 700L,
                distance = null,
                trend = Trend.APPROACHING,
                timeToCollision = TtcEstimate(null, 0f, TtcSource.NONE, "unavailable"),
                areaGrowthRatio = 0.30f,
                areaGrowthPerSecond = null,
            ),
        )

        assertTrue(decision.alertable)
    }

    private fun approachingSignals(stableFrames: Int, stableMs: Long): TrackRiskSignals {
        return TrackRiskSignals(
            stableFrames = stableFrames,
            stableMs = stableMs,
            distance = null,
            trend = Trend.APPROACHING,
            timeToCollision = TtcEstimate(5_000L, 0.8f, TtcSource.BBOX_SCALE, "test"),
            areaGrowthRatio = 0.3f,
            areaGrowthPerSecond = 0.4f,
        )
    }
}
