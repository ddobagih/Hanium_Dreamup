package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.DepthAgeBudget
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackOutputRejection
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Actual production coordinator; no debugCamera replacement or reflection is used. */
class TactileFeedbackRejectionDispatchTest {
    @Test
    fun currentEmptyDispatchDoesNotCarryThePreviousLowConfidenceReason() {
        val policy = WalkSafeFeedbackPolicy()
        val coordinator = coordinator(policy)
        seedPreviousLowConfidence(coordinator, policy)
        val dispatch = coordinator.dispatchFeedback(frame(emptyList()), false, true, 1_100L)
        assertNull(dispatch.action)
        assertEquals(emptyMap<String, FeedbackOutputRejection>(), policy.lastOutputRejections())
        assertEquals(emptyMap<String, Set<FeedbackOutputRejection>>(),
            requireNotNull(dispatch.outputEvaluation).rejectionsByTrack)
    }

    @Test
    fun aLowConfidenceOnlyFrameWithAClosedDeviceGateDoesNotBorrowThePreviousFrameReason() {
        val policy = WalkSafeFeedbackPolicy()
        val coordinator = coordinator(policy)
        seedPreviousLowConfidence(coordinator, policy)
        // Main may close the gate as fresh_depth_object_missing for this single .50 output.
        val dispatch = coordinator.dispatchFeedback(frame(listOf(output("current-low", 1_100L, .50f))),
            false, false, 1_100L)
        assertNull(dispatch.action)
        assertFalse(dispatch.deviceGateAllowsAlerts)
        assertEquals(emptySet<String>(), dispatch.activeRiskTrackIds)
        assertFalse(policy.lastOutputRejections().containsKey("previous-low"))
        val snapshot = requireNotNull(dispatch.outputEvaluation)
        assertEquals(FeedbackOutputRejection.DEVICE_GATE_BLOCKED, snapshot.frameRejection)
        assertEquals(.55f, snapshot.minimumOutputConfidence, 0f)
        assertEquals(mapOf("current-low" to setOf(FeedbackOutputRejection.DEVICE_GATE_BLOCKED,
            FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT)), snapshot.rejectionsByTrack)
    }

    @Test
    fun currentStaleDispatchDoesNotCarryThePreviousLowConfidenceReason() {
        val policy = WalkSafeFeedbackPolicy()
        val coordinator = coordinator(policy)
        seedPreviousLowConfidence(coordinator, policy)
        val dispatch = coordinator.dispatchFeedback(frame(listOf(output("current-stale", 1_100L, .90f))),
            true, true, 1_100L)
        assertNull(dispatch.action)
        assertFalse(policy.lastOutputRejections().containsKey("previous-low"))
        assertEquals(mapOf("current-stale" to setOf(FeedbackOutputRejection.OBSERVATION_NOT_FRESH)),
            requireNotNull(dispatch.outputEvaluation).rejectionsByTrack)
    }

    @Test
    fun aProducerOutputWithoutACaptureLeaseDoesNotCarryThePreviousLowConfidenceReason() {
        val policy = WalkSafeFeedbackPolicy()
        val coordinator = coordinator(policy)
        seedPreviousLowConfidence(coordinator, policy)
        val unleased = output("current-unleased", 1_100L, .90f).copy(sourceValidUntilElapsedRealtimeMs = null)
        val dispatch = coordinator.dispatchFeedback(frame(listOf(unleased)), false, true, 1_100L)
        assertNull(dispatch.action)
        assertFalse(policy.lastOutputRejections().containsKey("previous-low"))
        assertEquals(mapOf("current-unleased" to setOf(FeedbackOutputRejection.MISSING_CAPTURE_LEASE)),
            requireNotNull(dispatch.outputEvaluation).rejectionsByTrack)
    }

    @Test
    fun anEarlierDispatchConsumedAfterTheNextFrameStillIdentifiesItsOwnRejectedCandidate() {
        val firstPolicy = WalkSafeFeedbackPolicy()
        val emitted = mutableListOf<TactileFrameFeedbackDispatch>()
        val firstCoordinator = AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(), firstPolicy,
            TactileFrameFeedbackActuator { emitted += it })
        val first = firstCoordinator.dispatchFeedback(
            frame(listOf(output("healthy", 1_000L, .95f), output("first-low", 1_000L, .50f))),
            false, true, 1_000L)
        val differentOrigin = coordinator(WalkSafeFeedbackPolicy()).dispatchFeedback(
            frame(listOf(output("healthy", 1_000L, .95f), output("different-low", 1_000L, .50f))),
            false, true, 1_000L)
        assertNotNull(first.action)
        val firstAction = requireNotNull(first.action)
        val otherAction = requireNotNull(differentOrigin.action)
        assertEquals(firstAction.copy(deliveryOwner = null), otherAction.copy(deliveryOwner = null))
        assertNotSame(requireNotNull(firstAction.deliveryOwner), requireNotNull(otherAction.deliveryOwner))
        assertFalse("Another policy cannot claim this issued owner",
            firstPolicy.claimFeedbackDelivery(otherAction, 1_000L, 1_000L))
        assertTrue(firstPolicy.claimFeedbackDelivery(firstAction, 1_000L, 1_000L))
        // The production actuator can consume `first` after another frame was evaluated.
        firstCoordinator.dispatchFeedback(frame(listOf(output("healthy", 1_100L, .95f))),
            false, true, 1_100L)
        assertEquals(emptyMap<String, FeedbackOutputRejection>(), firstPolicy.lastOutputRejections())
        assertNotEquals("A dispatch must preserve its own rejection snapshot for a delayed consumer",
            first, differentOrigin.copy(action = firstAction))
        assertSame(first, emitted.first())
        assertEquals(mapOf("first-low" to setOf(FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT)),
            requireNotNull(emitted.first().outputEvaluation).rejectionsByTrack)
        assertEquals(1_000L, requireNotNull(first.outputEvaluation).evaluatedAtMs)
    }

    private fun coordinator(policy: WalkSafeFeedbackPolicy) = AndroidTactileFrameCoordinator(
        AndroidTactileRouteGuidance(), policy, TactileFrameFeedbackActuator { },
    )

    private fun seedPreviousLowConfidence(coordinator: AndroidTactileFrameCoordinator,
        policy: WalkSafeFeedbackPolicy) {
        // A fresh .95 object permits the gate; the .50 object's generated message is still refused.
        val dispatch = coordinator.dispatchFeedback(
            frame(listOf(output("healthy", 1_000L, .95f), output("previous-low", 1_000L, .50f))),
            false, true, 1_000L)
        assertNotNull(dispatch.action)
        assertEquals(mapOf("previous-low" to FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT),
            policy.lastOutputRejections())
    }

    private fun frame(outputs: List<TrackedObjectDepth>) = TactileSnapshotFrameResult(
        null, emptyList(), false,
        TactileRouteGuidanceResult(outputs, null, TactileRouteDecision(LocalRouteMode.TMAP, null, "test", null)),
    )

    private fun output(id: String, now: Long, confidence: Float): TrackedObjectDepth {
        val box = RectNorm(.1f, .3f, .2f, .4f)
        return TrackedObjectDepth(
            frameId = now * 1_000_000L, timestampMs = 51_000L, trackId = id,
            className = "crosswalk", detectionConfidence = .95f, bboxNorm = box,
            polygonNorm = emptyList(), maskAreaNorm = box.area, centerNorm = box.center, bottomContactNorm = null,
            source = DepthSource.ARCORE_RAW_DEPTH, zDistanceM = 1f, rayDistanceM = 1f,
            groundDistanceM = 1f, riskDistanceM = 1f, validSampleCount = 80, validSampleRatio = .9f,
            depthMedianM = 1f, depthP20M = 1f, depthIqrM = .05f, trend = Trend.STABLE,
            approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
            confidence = DepthConfidenceBreakdown(confidence, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            userFacing = UserFacingDepth(null, MessageLevel.INFO, "횡단보도가 보입니다."),
            trackAgeFrames = 20, trackStableMs = 1_900L,
            sourceValidUntilElapsedRealtimeMs = now + DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS,
            sourceObservedAtElapsedRealtimeMs = now, sourceMaxAgeMs = DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS,
        )
    }
}
