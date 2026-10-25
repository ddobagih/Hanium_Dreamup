package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackCandidate
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test

/** Accepted public depth producers with synthetic captures/points, not collision ground truth. */
class AndroidApproachCollisionRiskConsumerTest {
    @Test
    fun producedFarApproachRemainsUrgentThroughSelectionCandidateAndExactClaim() {
        val output = namedSequence(finalMillimeters = 4_000, stepMillimeters = 900)
        assertEquals(MessageLevel.STOP, output.userFacing.messageLevel)
        assertEquals(4f, requireNotNull(output.riskDistanceM), .001f)
        assertTrue(requireNotNull(output.approachTimeMs) in 1_332L..1_334L)
        val slowerNear = output.copy(trackId = "slower-near", riskDistanceM = .6f,
            timeToCollisionMs = 2_000L)
        assertSame(output, AndroidRiskSelectionPolicy.selectBestOutput(listOf(slowerNear, output)))
        assertEquals(listOf(output, slowerNear),
            AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(slowerNear, output)))
        val candidate = requireNotNull(output.toFeedbackCandidate())
        assertCarried(output, candidate)
        val now = requireNotNull(output.sourceObservedAtElapsedRealtimeMs)
        val policy = WalkSafeFeedbackPolicy()
        val action = requireNotNull(policy.evaluateCandidates(
            AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(slowerNear, output))
                .mapNotNull { it.toFeedbackCandidate() }, true, now))
        assertEquals(output.trackId, action.trackId)
        assertEquals(MessageLevel.STOP, action.level)
        assertTrue(policy.claimFeedbackDelivery(action, now, now))
        assertTrue(policy.hasClaimedFeedbackDelivery(action))
        assertTrue(policy.confirmFeedbackDelivery(action, now, now + 100L))
        assertFalse(policy.hasClaimedFeedbackDelivery(action))
    }

    @Test
    fun measuredNonClosingTrajectoryRetiresApproachButPreservesNearStopAndReference() {
        val closing = trackedSequence(lateralSpeed = 0f, finalX = 0f)
        val nonClosing = trackedSequence(lateralSpeed = 2f, finalX = 1.1f)
        assertTrue(requireNotNull(closing.approachTimeMs) in 1_999L..2_001L)
        assertTrue(requireNotNull(nonClosing.motionEstimate.relativeClosingSpeedMps) < -.05f)
        assertNull(nonClosing.approachTimeMs)
        for (output in listOf(closing, nonClosing)) {
            assertEquals(42L, output.motionEstimate.referenceId)
            assertEquals(output.timestampMs, output.motionEstimate.observedAtMs)
            assertEquals(2f, requireNotNull(output.riskDistanceM), .001f)
            assertSame(output, AndroidRiskSelectionPolicy.selectBestOutput(listOf(output)))
            val candidate = requireNotNull(output.toFeedbackCandidate())
            assertCarried(output, candidate)
            assertEquals(MessageLevel.STOP, candidate.level)
            assertEquals(MessageLevel.STOP, requireNotNull(WalkSafeFeedbackPolicy().evaluate(
                candidate, true, requireNotNull(output.sourceObservedAtElapsedRealtimeMs))).level)
        }
    }

    @Test
    fun producedEmergencyApproachDoesNotRequireCollisionProofOrNormalStableDuration() {
        val output = namedSequence(finalMillimeters = 600, stepMillimeters = 600, stepMs = 200L)
        val candidate = requireNotNull(output.toFeedbackCandidate())
        assertCarried(output, candidate)
        assertEquals(600L, candidate.trackStableMs)
        assertTrue(candidate.confidence >= .90f)
        assertTrue(requireNotNull(candidate.approachTimeMs) in 199L..201L)
        val now = requireNotNull(candidate.observedAtElapsedRealtimeMs)
        val policy = WalkSafeFeedbackPolicy()
        assertEquals(MessageLevel.STOP, requireNotNull(policy.evaluate(candidate, true, now)).level)
        // Same source lease and clock. More than half its original age budget is now consumed;
        // it cannot be retagged fresh just because collision assessment remains UNKNOWN.
        assertNull(WalkSafeFeedbackPolicy().evaluate(candidate, true, now + 401L))
    }

    @Test
    fun sameRankPublishedApproachWorseningRetainsItsOriginalReplayGuard() {
        // Two synthetic producer windows use the same tracker namespace and monotonic source
        // clocks. Only their final real outputs enter policy; no candidate time/lease is retagged.
        val old = namedSequence(finalMillimeters = 800, stepMillimeters = 300)
        val current = namedSequence(finalMillimeters = 800, stepMillimeters = 1_200, startMs = 1_800L)
        assertEquals(old.trackId, current.trackId)
        assertTrue(requireNotNull(old.approachTimeMs) in 799L..801L)
        assertTrue(requireNotNull(current.approachTimeMs) in 199L..201L)
        assertEquals(old.riskDistanceM, current.riskDistanceM)
        val before = requireNotNull(old.toFeedbackCandidate())
        val next = requireNotNull(current.toFeedbackCandidate())
        assertCarried(old, before)
        assertCarried(current, next)
        val firstAt = requireNotNull(before.observedAtElapsedRealtimeMs)
        val nextAt = requireNotNull(next.observedAtElapsedRealtimeMs)
        assertEquals(800L, nextAt - firstAt)
        val policy = WalkSafeFeedbackPolicy()
        val first = requireNotNull(policy.evaluate(before, true, firstAt))
        assertTrue(policy.claimFeedbackDelivery(first, firstAt, firstAt))
        assertTrue(policy.confirmFeedbackDelivery(first, firstAt, firstAt))
        val replay = requireNotNull(policy.evaluate(next, true, nextAt))
        assertEquals(MessageLevel.STOP, replay.level)
        assertTrue(first.deliveryOwner !== replay.deliveryOwner)
        assertTrue(policy.claimFeedbackDelivery(replay, nextAt, nextAt))
        assertTrue(policy.confirmFeedbackDelivery(replay, nextAt, nextAt))
    }

    @Test
    fun legacyConstructorCopyAndExpiredSourceKeepBackingAndOriginalClockBoundaries() {
        val output = namedSequence(finalMillimeters = 4_000, stepMillimeters = 900)
        val original = requireNotNull(output.toFeedbackCandidate())
        val legacy = FeedbackCandidate(original.trackId, original.message, original.level, original.source,
            original.confidence, original.timestampMs, original.trackAgeFrames, original.trackStableMs,
            original.stale, original.validUntilMs, original.timeToCollisionMs, original.riskDistanceM,
            original.observedAtElapsedRealtimeMs, original.observationMaxAgeMs)
        assertEquals(original, legacy)
        assertEquals(CollisionAssessment.UNKNOWN, legacy.collisionAssessment)
        assertEquals(original.observationMaxAgeMs, legacy.component14())
        assertNull(legacy.copy(timeToCollisionMs = null).approachTimeMs)
        assertEquals(-1L, legacy.copy(timeToCollisionMs = -1L).approachTimeMs)
        assertEquals(CollisionAssessment.UNKNOWN, legacy.copy(timeToCollisionMs = null).collisionAssessment)
        val noTime = output.copy(timeToCollisionMs = null, approachSpeedMps = null)
        val negativeTime = output.copy(timeToCollisionMs = -1L)
        assertNull(requireNotNull(noTime.toFeedbackCandidate()).approachTimeMs)
        assertSame(output, AndroidRiskSelectionPolicy.selectBestOutput(listOf(noTime, negativeTime, output)))
        val policy = WalkSafeFeedbackPolicy()
        assertNull(policy.evaluate(original, true, original.validUntilMs + 1L))
        assertNull(WalkSafeFeedbackPolicy().evaluate(original.copy(
            observedAtElapsedRealtimeMs = original.timestampMs), true,
            requireNotNull(original.observedAtElapsedRealtimeMs)))
    }

    private fun assertCarried(output: TrackedObjectDepth, candidate: FeedbackCandidate) {
        assertEquals(output.approachTimeMs, candidate.approachTimeMs)
        assertEquals(candidate.timeToCollisionMs, candidate.approachTimeMs)
        assertEquals(output.collisionAssessment, candidate.collisionAssessment)
        assertEquals(CollisionAssessment.UNKNOWN, candidate.collisionAssessment)
        assertEquals(output.timestampMs, candidate.timestampMs)
        assertEquals(output.sourceObservedAtElapsedRealtimeMs, candidate.observedAtElapsedRealtimeMs)
        assertEquals(output.sourceValidUntilElapsedRealtimeMs, candidate.validUntilMs)
        assertEquals(output.sourceMaxAgeMs, candidate.observationMaxAgeMs)
        assertEquals(output.userFacing.message, candidate.message)
    }

    private fun namedSequence(finalMillimeters: Int, stepMillimeters: Int,
        stepMs: Long = 300L, startMs: Long = 1_000L): TrackedObjectDepth {
        val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        var output: TrackedObjectDepth? = null
        for (i in 0..3) {
            val at = startMs + i * stepMs
            val frameId = at * 1_000_000L
            val cameraNs = 10_000_000_000_000L + frameId
            output = pipeline.process(snapshot(at, cameraNs, finalMillimeters + (3 - i) * stepMillimeters,
                pose = null), frameId, at, listOf(detection), mapper = mapper, mapperFrameId = frameId,
                capturedAtElapsedRealtimeMs = at + 50_000L).single()
        }
        return requireNotNull(output)
    }

    private fun trackedSequence(lateralSpeed: Float, finalX: Float): TrackedObjectDepth {
        val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        val pointId = VisualMaterialPointId()
        var output: TrackedObjectDepth? = null
        for (i in 0..4) {
            val at = 1_000L + i * 200L
            val cameraNs = 10_000_000_000_000L + at * 1_000_000L
            val key = VisualFrameKey(7L, at * 1_000_000L, cameraNs, at + 50_000L, 3L)
            val millimeters = 2_800 - i * 200
            val x = finalX - lateralSpeed * (4 - i) * .2f
            val point = Point2(.5f + .7f * x / (millimeters / 1_000f), .5f)
            val observation = knownMaterialPointObservation(key, key, detection, pointId, point)
            val pose = CameraPoseEvidence(42L, at, 0f, 0f, 0f, 0f, 0f, -1f,
                CameraImageProjection(SIZE, SIZE, 70f, 70f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f))
            output = pipeline.processTrackedObservation(snapshot(at, cameraNs, millimeters, pose), observation,
                mapper, key.frameId, key.capturedAtElapsedRealtimeMs).single()
        }
        return requireNotNull(output)
    }

    private fun snapshot(at: Long, cameraNs: Long, millimeters: Int, pose: CameraPoseEvidence?) =
        DepthFrameSnapshot(at * 1_000_000L, DepthImage16(SIZE, SIZE, IntArray(SIZE * SIZE) { millimeters }),
            ConfidenceImage8(SIZE, SIZE, ByteArray(SIZE * SIZE) { 255.toByte() }), null,
            rawDepthTimestampNs = cameraNs, rawConfidenceTimestampNs = cameraNs,
            cameraImageTimestampNs = cameraNs, cameraPoseEvidence = pose)

    private companion object {
        const val SIZE = 100
        val detection = DetectionCandidate("car", 1f, RectNorm(0f, 0f, 1f, 1f))
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(SIZE, SIZE, SIZE, SIZE, 1f, 0f, 0f),
            ImageSize(SIZE, SIZE))
    }
}
