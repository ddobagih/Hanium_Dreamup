package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRiskMotionSeparationTest {
    @Test
    fun stableStopIsDeliveredImmediatelyAndRemainsActiveOnTheNextCameraFrame() {
        val pipeline = ObjectDepthRuntimePipeline()
        val feedback = WalkSafeFeedbackPolicy()
        for (at in listOf(1_000L, 1_400L)) {
            val candidates = candidates(observe(pipeline, at, raw = true, mm = 1_000, pose = false))
            assertNull(feedback.evaluateCandidates(candidates, true, at))
        }
        val stable = observe(pipeline, 1_800L, raw = true, mm = 1_000, pose = false)
        assertEquals(800L, stable.trackStableMs)
        val action = requireNotNull(feedback.evaluateCandidates(candidates(stable), true, 1_800L))
        assertEquals(MessageLevel.STOP, action.level)
        assertTrue(feedback.claimFeedbackDelivery(action.trackId, 1_800L))

        val next = candidates(observe(pipeline, 1_833L, raw = true, mm = 1_000, pose = false))
        assertNull(feedback.evaluateCandidates(next, true, 1_833L))
        assertTrue(action.deliveryKey in feedback.activeFeedbackDeliveryKeys(next, true))
        assertTrue(feedback.confirmFeedbackDelivery(action.trackId, 1_800L, 1_833L))
        for (at in listOf(2_500L, 3_500L, 4_300L)) {
            val current = candidates(observe(pipeline, at, raw = true, mm = 1_000, pose = false))
            assertFalse(current.isEmpty())
            assertNull(feedback.evaluateCandidates(current, true, at))
        }
        assertNotNull(feedback.evaluateCandidates(
            candidates(observe(pipeline, 4_333L, raw = true, mm = 1_000, pose = false)), true, 4_333L,
        ))
    }

    @Test
    fun stepLengthChangePreservesCustomThresholdsAndQueueOwnedCooldown() {
        val policy = MessagePolicy(config = MessagePolicyConfig(warningDistanceM = 3.8f))
        val pipeline = ObjectDepthRuntimePipeline(messagePolicy = policy)
        val before = observe(pipeline, 1_000L, raw = true, mm = 3_000, pose = false)
        assertNotNull(before.userFacing.message)
        pipeline.setUserStepLength(0.8f)
        val after = observe(pipeline, 1_033L, raw = true, mm = 3_000, pose = false)
        assertNotNull(after.userFacing.message)
        assertEquals(before.userFacing.messageLevel, after.userFacing.messageLevel)
    }

    @Test
    fun standaloneMessagePolicyStillOwnsItsConfiguredCooldown() {
        val decision = MetricDepthDecision("person", DepthSource.ARCORE_RAW_DEPTH, 1f, Trend.STABLE, .95f)
        val policy = MessagePolicy()
        assertNotNull(policy.buildUserFacing(decision, 1_000L).message)
        assertNull(policy.buildUserFacing(decision, 1_033L).message)
        val resized = policy.withStepLength(0.8f)
        assertNull(resized.buildUserFacing(decision, 1_800L).message)
        assertNotNull(resized.buildUserFacing(decision, 3_500L).message)
    }

    @Test
    fun fullProximityBetweenTheSameRawCapturesPreservesRawVelocityAndTtc() {
        val pure = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        val tracker = ObjectTracker()
        val mixed = ObjectDepthRuntimePipeline(tracker = tracker, rawDepthMotionOnly = true)
        for (at in listOf(1_000L, 1_300L, 1_600L, 1_900L)) {
            val expected = observe(pure, at, raw = true)
            val actual = observe(mixed, at, raw = true)
            assertEquals(expected.approachSpeedMps, actual.approachSpeedMps)
            assertEquals(expected.timeToCollisionMs, actual.timeToCollisionMs)
            if (at == 1_900L) {
                assertEquals(1f, requireNotNull(actual.approachSpeedMps), .001f)
                assertEquals(3_100.0, requireNotNull(actual.timeToCollisionMs).toDouble(), 1.0)
            }
            val full = observe(mixed, at + 100L, raw = false)
            assertNoMotion(full)
            assertEquals(DepthSource.ARCORE_FULL_DEPTH, full.source)
            assertNotNull(full.riskDistanceM)
        }
        assertEquals(listOf(1_000L, 1_300L, 1_600L, 1_900L),
            tracker.activeTracks().single().distanceHistory.map { it.timestampMs })
        assertEquals(1f, requireNotNull(observe(mixed, 2_200L, raw = true).approachSpeedMps), .001f)
    }

    @Test
    fun fullProximityCannotMakeAnOldRawCaptureIndependentAgain() {
        val tracker = ObjectTracker()
        val pipeline = warmPipeline(tracker)
        assertNoMotion(observe(pipeline, 2_000L, raw = false))
        assertNoMotion(observe(pipeline, 2_100L, raw = true, rawTimestampNs = 1_900_000_000L))
        assertEquals(4, tracker.activeTracks().single().distanceHistory.size)
        assertEquals(1f, requireNotNull(observe(pipeline, 2_200L, raw = true).approachSpeedMps), .001f)
    }

    @Test
    fun missingIdentityDuringFullRequiresANewRawMotionHistory() {
        val tracker = ObjectTracker()
        val pipeline = warmPipeline(tracker)
        val previousId = tracker.activeTracks().single().trackId
        tracker.update(emptyList(), 2_000L)
        val full = observe(pipeline, 2_100L, raw = false)
        assertNoMotion(full)
        assertFalse(previousId == full.trackId)
        val raw = observe(pipeline, 2_200L, raw = true)
        assertEquals(full.trackId, raw.trackId)
        assertNoMotion(raw)
    }

    @Test
    fun fullFramesDoNotShortenARealRawObservationGap() {
        val tracker = ObjectTracker()
        val pipeline = warmPipeline(tracker)
        val trackId = tracker.activeTracks().single().trackId
        for (at in listOf(2_200L, 2_500L, 2_800L, 3_100L, 3_400L, 3_600L)) {
            assertNoMotion(observe(pipeline, at, raw = false))
        }
        val resumed = observe(pipeline, 3_700L, raw = true)
        assertEquals(trackId, resumed.trackId)
        assertNoMotion(resumed)
        assertEquals(listOf(3_700L), tracker.activeTracks().single().distanceHistory.map { it.timestampMs })
    }

    @Test
    fun anchorChangeDuringFullCannotDisappearWhenThePreviousRawAnchorReturns() {
        val tracker = ObjectTracker()
        val pipeline = warmPipeline(tracker)
        assertNoMotion(observe(pipeline, 2_000L, raw = false, referenceId = 8L))
        assertNoMotion(observe(pipeline, 2_100L, raw = false, referenceId = 7L))
        assertNoMotion(observe(pipeline, 2_200L, raw = true, referenceId = 7L))
        assertEquals(listOf(2_200L), tracker.activeTracks().single().distanceHistory.map { it.timestampMs })
    }

    @Test
    fun fullProximityCannotConfirmARawDepthLayerJump() {
        val tracker = ObjectTracker()
        val pipeline = warmPipeline(tracker)
        val jump = observe(pipeline, 2_200L, raw = true, mm = 1_000)
        assertEquals(0f, jump.confidence.finalScore, 0f)
        assertNoMotion(jump)
        assertNoMotion(observe(pipeline, 2_300L, raw = false, mm = 900))
        assertEquals(4, tracker.activeTracks().single().distanceHistory.size)
        val confirmed = observe(pipeline, 2_500L, raw = true, mm = 700)
        assertTrue(confirmed.confidence.finalScore > 0f)
        assertNoMotion(confirmed)
        assertEquals(listOf(2_200L, 2_500L), tracker.activeTracks().single().distanceHistory.map { it.timestampMs })
    }

    @Test
    fun fullProximityHasItsOwnJumpAndIndependentCaptureChecks() {
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker, rawDepthMotionOnly = true)
        listOf(1_000L, 1_300L, 1_600L).forEach { observe(pipeline, it, raw = false) }
        assertEquals(0f, observe(pipeline, 1_900L, raw = false, mm = 900).confidence.finalScore, 0f)
        assertTrue(observe(pipeline, 2_200L, raw = false, mm = 600).confidence.finalScore > 0f)
        assertTrue(tracker.activeTracks().single().distanceHistory.isEmpty())
    }

    private fun warmPipeline(tracker: ObjectTracker): ObjectDepthRuntimePipeline =
        ObjectDepthRuntimePipeline(tracker = tracker, rawDepthMotionOnly = true).also { pipeline ->
            listOf(1_000L, 1_300L, 1_600L, 1_900L).forEach { observe(pipeline, it, raw = true) }
        }

    private fun candidates(output: TrackedObjectDepth) =
        AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(output)).mapNotNull { it.toFeedbackCandidate() }

    private fun assertNoMotion(output: TrackedObjectDepth) {
        assertEquals(Trend.UNKNOWN, output.trend)
        assertNull(output.approachSpeedMps)
        assertNull(output.timeToCollisionMs)
        assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
        assertNull(output.motionEstimate.observedAtMs)
    }

    private fun observe(
        pipeline: ObjectDepthRuntimePipeline,
        at: Long,
        raw: Boolean,
        mm: Int = 4_000 - (at - 1_000L).toInt(),
        pose: Boolean = true,
        referenceId: Long = 7L,
        rawTimestampNs: Long = at * 1_000_000L,
    ): TrackedObjectDepth {
        val ns = at * 1_000_000L
        val snapshot = DepthFrameSnapshot(
            frameTimestampNs = ns,
            rawDepth = if (raw) DepthImage16(40, 40, IntArray(1_600) { mm }) else null,
            rawConfidence = if (raw) ConfidenceImage8(40, 40, ByteArray(1_600) { 255.toByte() }) else null,
            fullDepth = DepthImage16(40, 40, IntArray(1_600) { mm }),
            rawDepthTimestampNs = if (raw) rawTimestampNs else null,
            fullDepthTimestampNs = ns,
            cameraImageTimestampNs = ns,
            cameraPoseEvidence = if (pose) CameraPoseEvidence(referenceId, at, 0f, 0f, -(at - 1_000L) / 1_000f,
                0f, 0f, -1f, CameraImageProjection(1_000, 1_000, 700f, 700f, 500f, 500f,
                    1f, 0f, 0f, 0f, 1f, 0f)) else null,
        )
        return pipeline.process(snapshot, ns, at,
            detections = listOf(DetectionCandidate("person", .95f, RectNorm(.25f, .25f, .5f, .5f)))).single()
    }
}
