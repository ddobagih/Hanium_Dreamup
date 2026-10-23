package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownProducerFixture
import org.junit.Assert.*
import org.junit.Test

class ObstacleDistanceBriefingTest {
    private fun observed(): TrackedObjectDepth {
        val fixture = UnknownProducerFixture()
        return (0..5).map { fixture.process(it * 200L, 1_000) }.last().result.objects.single()
            .copy(sourceValidUntilElapsedRealtimeMs = 101_800L)
    }

    @Test fun actualMeasuredSurfaceReachesBriefingWithoutChangingRiskIdentity() {
        val output = observed()
        val policy = WalkSafeFeedbackPolicy()
        val action = requireNotNull(policy.evaluate(output.toFeedbackCandidate(), true, 101_050L))
        assertTrue(action.message.startsWith("멈추세요."))
        assertEquals("카메라에서 감지된 표면까지 약 1.0미터입니다.", output.distanceBriefingFor(action, 101_050L))
        val updated = output.copy(measuredSurfaceRangeM = 1.25f)
        assertEquals(output.toFeedbackCandidate()!!.deliveryKey, updated.toFeedbackCandidate()!!.deliveryKey)
        assertEquals(output.riskDistanceM, updated.riskDistanceM)
        assertEquals(output.timeToCollisionMs, updated.timeToCollisionMs)
        assertTrue(updated.distanceBriefingFor(action, 101_050L)!!.contains("1.3미터"))
        assertNull(policy.evaluate(updated.toFeedbackCandidate(), true, 101_051L))
    }

    @Test fun unavailableSurfaceNeverBorrowsAnotherDistanceAndExpiredOrForeignEvidenceCannotBeUsed() {
        val output = observed()
        val action = requireNotNull(WalkSafeFeedbackPolicy().evaluate(output.toFeedbackCandidate(), true, 101_050L))
        assertNotNull(output.riskDistanceM)
        assertNotNull(output.rayDistanceM)
        assertNull(output.copy(measuredSurfaceRangeM = null).distanceBriefingFor(action, 101_050L))
        assertNull(output.copy(measuredSurfaceRangeM = Float.NaN).distanceBriefingFor(action, 101_050L))
        assertNull(output.copy(trackId = "other").distanceBriefingFor(action, 101_050L))
        assertNull(output.copy(depthAvailability = DepthAvailability.PREDICTED).distanceBriefingFor(action, 101_050L))
        assertNull(output.copy(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH).distanceBriefingFor(action, 101_050L))
        assertNull(output.distanceBriefingFor(action, 101_801L))
        assertNotNull(output.distanceBriefingFor(action, 101_800L))
        // Original warning still exists when there is no numeric evidence.
        assertEquals(action.message, output.copy(measuredSurfaceRangeM = null).toFeedbackCandidate()!!.message)
    }

    @Test fun sharedSurfaceRuleIgnoresIsolatedNearOutlierButDoesNotUseMedian() {
        assertEquals(1.8f, nearestCoherentMeasuredRange(listOf(.4f, 1.8f, 1.82f, 1.85f, 3f, 3f, 3f))!!, .00001f)
        assertNull(nearestCoherentMeasuredRange(listOf(1f, 2f, 3f, Float.NaN)))
    }
}
