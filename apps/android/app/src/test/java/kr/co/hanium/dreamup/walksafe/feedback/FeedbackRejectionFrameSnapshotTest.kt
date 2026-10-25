package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** S12-21: refusal reasons belong to the current evaluation, including early returns. */
class FeedbackRejectionFrameSnapshotTest {
    @Test
    fun anEmptyEvaluationDoesNotKeepThePreviousLowConfidenceReason() {
        val policy = policyWithPreviousLowConfidence()
        assertNull(policy.evaluateCandidates(emptyList(), true, 1_100L))
        assertEquals(emptyMap<String, FeedbackOutputRejection>(), policy.lastOutputRejections())
        assertEquals(emptyMap<String, Set<FeedbackOutputRejection>>(),
            requireNotNull(policy.lastOutputEvaluation()).rejectionsByTrack)
    }

    @Test
    fun aBlockedDeviceGateDoesNotReportThePreviousFramesConfidence() {
        val policy = policyWithPreviousLowConfidence()
        assertNull(policy.evaluate(candidate("current-gated", 1_100L), false, 1_100L))
        assertFalse(policy.lastOutputRejections().containsKey("previous-low"))
        val snapshot = requireNotNull(policy.lastOutputEvaluation())
        assertEquals(1_100L, snapshot.evaluatedAtMs)
        assertEquals(FeedbackOutputRejection.DEVICE_GATE_BLOCKED, snapshot.frameRejection)
        assertEquals(mapOf("current-gated" to setOf(FeedbackOutputRejection.DEVICE_GATE_BLOCKED)),
            snapshot.rejectionsByTrack)
    }

    @Test
    fun aStaleEvaluationDoesNotReportThePreviousFramesConfidence() {
        val policy = policyWithPreviousLowConfidence()
        assertNull(policy.evaluate(candidate("current-stale", 1_100L).copy(stale = true), true, 1_100L))
        assertFalse(policy.lastOutputRejections().containsKey("previous-low"))
        assertEquals(mapOf("current-stale" to setOf(FeedbackOutputRejection.OBSERVATION_NOT_FRESH)),
            requireNotNull(policy.lastOutputEvaluation()).rejectionsByTrack)
    }

    @Test
    fun aMissingLeaseDoesNotReportThePreviousFramesConfidence() {
        val policy = policyWithPreviousLowConfidence()
        assertNull(policy.evaluate(candidate("current-unleased", 1_100L).copy(validUntilMs = Long.MIN_VALUE),
            true, 1_100L))
        assertFalse(policy.lastOutputRejections().containsKey("previous-low"))
        assertEquals(mapOf("current-unleased" to setOf(FeedbackOutputRejection.MISSING_CAPTURE_LEASE)),
            requireNotNull(policy.lastOutputEvaluation()).rejectionsByTrack)
    }

    @Test
    fun theOutputBoundaryStaysAtPoint55AfterRecordingTheLowerGeneratedConfidence() {
        val policy = WalkSafeFeedbackPolicy()
        assertNull(policy.evaluate(candidate("generated", 1_000L).copy(confidence = .50f), true, 1_000L))
        val refused = requireNotNull(policy.lastOutputEvaluation())
        assertEquals(.55f, refused.minimumOutputConfidence, 0f)
        assertEquals(mapOf("generated" to setOf(FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT)),
            refused.rejectionsByTrack)
        assertNotNull(policy.evaluate(candidate("at-boundary", 1_100L).copy(confidence = .55f), true, 1_100L))
        assertTrue(requireNotNull(policy.lastOutputEvaluation()).rejectionsByTrack.isEmpty())
    }

    @Test
    fun aPreviousFrameSnapshotAndNestedCollectionsSurviveTheNextFrameAndWalkReset() {
        val policy = policyWithPreviousLowConfidence()
        val first = requireNotNull(policy.lastOutputEvaluation())
        assertReadOnly { (first.rejectionsByTrack as MutableMap<String, Set<FeedbackOutputRejection>>).clear() }
        assertReadOnly { (first.rejectionsByTrack.getValue("previous-low") as MutableSet<FeedbackOutputRejection>).clear() }
        policy.evaluateCandidates(emptyList(), true, 1_100L)
        assertTrue(requireNotNull(policy.lastOutputEvaluation()).rejectionsByTrack.isEmpty())
        assertEquals(setOf(FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT), first.rejectionsByTrack["previous-low"])
        policy.resetForNewWalk()
        assertNull(policy.lastOutputEvaluation())
        assertEquals(setOf(FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT), first.rejectionsByTrack["previous-low"])
    }

    @Test
    fun admissionDiagnosticsDoNotChangeTheExistingFreshOutputRejectionCounters() {
        val policy = policyWithPreviousLowConfidence()
        val counters = policy.outputRejectionCounts()
        policy.evaluate(candidate("gated", 1_100L).copy(confidence = .50f), false, 1_100L)
        policy.evaluate(candidate("stale", 1_200L).copy(confidence = .50f, stale = true), true, 1_200L)
        policy.evaluate(candidate("unleased", 1_300L).copy(confidence = .50f, validUntilMs = Long.MIN_VALUE),
            true, 1_300L)
        assertEquals(counters, policy.outputRejectionCounts())
    }

    private fun policyWithPreviousLowConfidence(): WalkSafeFeedbackPolicy {
        val policy = WalkSafeFeedbackPolicy()
        assertNull(policy.evaluate(candidate("previous-low", 1_000L).copy(confidence = .50f), true, 1_000L))
        assertEquals(mapOf("previous-low" to FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT),
            policy.lastOutputRejections())
        return policy
    }

    private fun candidate(id: String, now: Long) = FeedbackCandidate(
        trackId = id, message = "횡단보도가 보입니다.", level = MessageLevel.INFO,
        source = DepthSource.ARCORE_RAW_DEPTH, confidence = .90f, timestampMs = 51_000L,
        trackAgeFrames = 20, trackStableMs = 1_900L, stale = false, validUntilMs = now + 800L,
        observedAtElapsedRealtimeMs = now, observationMaxAgeMs = 800L,
    )

    private fun assertReadOnly(mutate: () -> Unit) {
        try {
            mutate()
            fail("Frame rejection snapshots must be immutable")
        } catch (_: UnsupportedOperationException) {
            // A delayed consumer cannot modify this or another frame's rejection facts.
        }
    }
}
