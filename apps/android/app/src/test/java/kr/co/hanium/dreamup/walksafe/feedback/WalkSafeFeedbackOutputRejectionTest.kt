package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S12-21: a crosswalk reference message generated at 0.45 but refused by the 0.55 output gate (or
 * by a non-metric source) records why; thresholds are not lowered and nothing is marked delivered.
 */
class WalkSafeFeedbackOutputRejectionTest {
    @Test
    fun refusedCrosswalkReferenceRecordsTheOutputReason() {
        val policy = WalkSafeFeedbackPolicy()
        val lowConfidence = crosswalk("low", DepthSource.ARCORE_RAW_DEPTH, confidence = 0.50f)
        val pseudo = crosswalk("pseudo", DepthSource.POLYGON_TREND_PSEUDO_DEPTH, confidence = 0.90f)

        val action = policy.evaluateCandidates(listOf(lowConfidence, pseudo), deviceGateAllowsAlerts = true, nowMs = 1_000L)

        assertNull(action)
        assertEquals(
            mapOf(
                "low" to FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT,
                "pseudo" to FeedbackOutputRejection.NON_METRIC_SOURCE,
            ),
            policy.lastOutputRejections(),
        )
        assertEquals(
            mapOf(
                FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT to 1L,
                FeedbackOutputRejection.NON_METRIC_SOURCE to 1L,
            ),
            policy.outputRejectionCounts(),
        )
    }

    @Test
    fun eligibleMetricCrosswalkReferenceIsNotRecordedAsRefused() {
        val policy = WalkSafeFeedbackPolicy()
        val eligible = crosswalk("ok", DepthSource.ARCORE_RAW_DEPTH, confidence = 0.60f)

        val action = policy.evaluateCandidates(listOf(eligible), deviceGateAllowsAlerts = true, nowMs = 1_000L)

        assertEquals("ok", requireNotNull(action).trackId)
        assertTrue(policy.lastOutputRejections().isEmpty())
        assertTrue(policy.outputRejectionCounts().isEmpty())
        assertNull(policy.outputRejection(eligible))
    }

    @Test
    fun outputGateThresholdIsUnchanged() {
        val policy = WalkSafeFeedbackPolicy()

        assertEquals(
            FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT,
            policy.outputRejection(crosswalk("edge", DepthSource.ARCORE_RAW_DEPTH, confidence = 0.549f)),
        )
        assertNull(policy.outputRejection(crosswalk("edge", DepthSource.ARCORE_RAW_DEPTH, confidence = 0.55f)))
        assertEquals(
            FeedbackOutputRejection.NON_METRIC_SOURCE,
            policy.outputRejection(crosswalk("prior", DepthSource.OBJECT_SIZE_PRIOR, confidence = 0.95f)),
        )
    }

    @Test
    fun newWalkClearsRefusalAccounting() {
        val policy = WalkSafeFeedbackPolicy()
        policy.evaluateCandidates(
            listOf(crosswalk("low", DepthSource.ARCORE_RAW_DEPTH, confidence = 0.50f)),
            deviceGateAllowsAlerts = true,
            nowMs = 1_000L,
        )

        policy.resetForNewWalk()

        assertTrue(policy.outputRejectionCounts().isEmpty())
        assertTrue(policy.lastOutputRejections().isEmpty())
    }

    private fun crosswalk(trackId: String, source: DepthSource, confidence: Float) = FeedbackCandidate(
        trackId = trackId,
        message = "횡단보도 참고 안내",
        level = MessageLevel.INFO,
        source = source,
        confidence = confidence,
        timestampMs = 1_000L,
        trackAgeFrames = 5,
        trackStableMs = 1_000L,
        stale = false,
        validUntilMs = 2_000L,
    )
}
