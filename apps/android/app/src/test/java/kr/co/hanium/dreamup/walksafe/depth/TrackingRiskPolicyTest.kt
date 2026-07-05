package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingRiskPolicyTest {
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
}
