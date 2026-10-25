package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public depth production and actual feedback admission, without synthesizing motion evidence. */
class S11EarlyProximityRuntimePipelineTest {
    @Test
    fun threeFreshNearCapturesDeliverStopBeforeMotionCanBeMeasured() {
        val fixture = Fixture()
        val policy = WalkSafeFeedbackPolicy()
        val outputs = listOf(1_000L, 1_300L, 1_700L).map { at ->
            val output = fixture.observe(at, 900)
            assertMeasuredNear(output, at)
            assertEquals(Trend.UNKNOWN, output.trend)
            assertNoMotion(output)
            val candidates = AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(output))
                .mapNotNull { it.toFeedbackCandidate() }
            assertEquals(1, candidates.size)
            val candidate = candidates.single()
            assertEquals(output.trackAgeFrames, candidate.trackAgeFrames)
            assertEquals(output.trackStableMs, candidate.trackStableMs)
            assertEquals(output.sourceObservedAtElapsedRealtimeMs, candidate.observedAtElapsedRealtimeMs)
            assertEquals(output.sourceValidUntilElapsedRealtimeMs, candidate.validUntilMs)
            assertNull(candidate.approachTimeMs)
            val action = policy.evaluateCandidates(candidates, true, fixture.captureElapsed(at))
            if (at < 1_700L) assertNull(action) else {
                assertEquals(MessageLevel.STOP, requireNotNull(action).level)
                assertEquals(output.trackId, action.trackId)
                assertEquals(output.userFacing.message, action.message)
                assertEquals(output.sourceValidUntilElapsedRealtimeMs, action.validUntilMs)
                assertTrue(policy.claimFeedbackDelivery(action.trackId, fixture.captureElapsed(at), fixture.captureElapsed(at)))
                assertTrue(policy.confirmFeedbackDelivery(action.trackId, fixture.captureElapsed(at), fixture.captureElapsed(at)))
            }
            println("S11-25 frame=$at age=${output.trackAgeFrames} stableMs=${output.trackStableMs} " +
                "distance=${output.riskDistanceM} trend=${output.trend} approachTime=${output.approachTimeMs} " +
                "motion=${output.motionEstimate.direction} delivered=${action?.level}")
            output
        }
        assertEquals(1, outputs.map { it.trackId }.distinct().size)
        assertEquals(3, outputs.last().trackAgeFrames)
        assertEquals(700L, outputs.last().trackStableMs)
        val track = fixture.tracker.activeTracks().single()
        assertEquals(3, track.distanceHistory.size)
        assertEquals(3, track.kinematicSamples().size)
        assertTrue(track.kinematicSamples().size < 4)
        assertEquals(listOf(1_000L, 1_300L, 1_700L), track.distanceHistory.map { it.timestampMs })
        assertEquals(listOf(1_000L, 1_300L, 1_700L).map { fixture.imageTimestamp(it) },
            track.distanceHistory.map { it.depthObservationTimestampNs })
    }

    @Test
    fun unpairedRawConfidenceCannotBecomeNearFeedback() {
        val fixture = Fixture()
        val policy = WalkSafeFeedbackPolicy()
        val outputs = listOf(1_000L, 1_300L, 1_700L).map { at ->
            fixture.observe(at, 900, confidenceTimestampNs = fixture.imageTimestamp(at) + 1L).also { output ->
                assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, output.source)
                assertNull(output.riskDistanceM)
                assertNoMotion(output)
                assertNull(output.userFacing.message)
                assertNull(dispatch(policy, output, fixture.captureElapsed(at)))
            }
        }
        assertEquals(3, outputs.last().trackAgeFrames)
        assertEquals(700L, outputs.last().trackStableMs)
        assertTrue(fixture.tracker.activeTracks().single().distanceHistory.isEmpty())
    }

    @Test
    fun singleUnconfirmedNearDepthJumpCannotBecomeNearFeedback() {
        val fixture = Fixture()
        val policy = WalkSafeFeedbackPolicy()
        val far = listOf(1_000L, 1_300L, 1_700L).map { at ->
            fixture.observe(at, 4_000).also { output ->
                assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
                assertEquals(4f, requireNotNull(output.riskDistanceM), .001f)
                assertNull(dispatch(policy, output, fixture.captureElapsed(at)))
            }
        }.last()
        val near = fixture.observe(1_800L, 900)
        assertEquals(far.trackId, near.trackId)
        assertTrue(near.trackAgeFrames >= 3 && near.trackStableMs >= 700L)
        assertNull(near.riskDistanceM)
        assertNoMotion(near)
        assertNull(near.userFacing.message)
        assertNull(dispatch(policy, near, fixture.captureElapsed(1_800L)))
        val track = fixture.tracker.activeTracks().single()
        assertTrue(track.lastDistancePendingNearerJump)
        assertFalse(track.metricDistanceReliable)
        assertEquals(4f, track.distanceHistory.last().distanceM, .001f)
        assertEquals(3, track.distanceHistory.size)
    }

    private fun assertMeasuredNear(output: TrackedObjectDepth, at: Long) {
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
        assertEquals(.9f, requireNotNull(output.riskDistanceM), .001f)
        assertFalse(output.spatialCoverageLimited)
        assertTrue(output.confidence.finalScore >= .55f)
        assertEquals(MessageLevel.STOP, output.userFacing.messageLevel)
        assertNotNull(output.userFacing.message)
        assertEquals(at, output.depthObservedAtMs)
        assertEquals(false, output.depthReprojected)
        assertEquals(0, output.sourceDetectionIndex)
        assertEquals(at + 50_000L, output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS, output.sourceMaxAgeMs)
        assertEquals(at + 50_000L + DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS, output.sourceValidUntilElapsedRealtimeMs)
    }

    private fun assertNoMotion(output: TrackedObjectDepth) {
        assertNull(output.approachSpeedMps)
        assertNull(output.approachTimeMs)
        assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
        assertEquals(ObjectMovementDirection.UNKNOWN, output.motionEstimate.direction)
        assertNull(output.motionEstimate.observedAtMs)
    }

    private fun dispatch(policy: WalkSafeFeedbackPolicy, output: TrackedObjectDepth, now: Long): FeedbackAction? =
        policy.evaluateCandidates(AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(output))
            .mapNotNull { it.toFeedbackCandidate() }, true, now)

    private class Fixture {
        val tracker = ObjectTracker()
        private val pipeline = ObjectDepthRuntimePipeline(tracker = tracker)
        // Controlled, already aligned RGB/depth grids; this is not an ARCore calibration proof.
        private val mapper = LetterboxCoordinateMapper(
            ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100),
        )
        private val detection = DetectionCandidate("person", 1f, RectNorm(.2f, .2f, .6f, .6f))

        fun imageTimestamp(at: Long): Long = at * 1_000_000L + 400_000L
        fun captureElapsed(at: Long): Long = at + 50_000L

        fun observe(at: Long, millimeters: Int, confidenceTimestampNs: Long = imageTimestamp(at)): TrackedObjectDepth {
            val frame = at * 1_000_000L
            val snapshot = DepthFrameSnapshot(
                frameTimestampNs = frame,
                rawDepth = DepthImage16(100, 100, IntArray(10_000) { millimeters }),
                rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
                fullDepth = null,
                rawDepthTimestampNs = imageTimestamp(at),
                cameraImageTimestampNs = imageTimestamp(at),
                rawConfidenceTimestampNs = confidenceTimestampNs,
            )
            assertEquals(imageTimestamp(at) == confidenceTimestampNs, snapshot.rawConfidenceMatchesRawDepth)
            return pipeline.process(snapshot, frame, at, listOf(detection), detectionSequenceId = frame,
                mapper = mapper, mapperFrameId = frame, capturedAtElapsedRealtimeMs = captureElapsed(at)).single()
        }
    }
}
