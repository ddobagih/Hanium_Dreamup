package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackCandidate
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/** Synthetic measured points through the latest real pipeline; no passage/body collision claim. */
class AndroidPointClosestApproachRiskConsumerTest {
    @Test
    fun differentPublishedPointCpaKeepsOriginalApproachUnknownAndStopDelivery() {
        val direct = captures().last()
        val lateral = captures(lateralSpeed = 1f).last()
        val directCpa = requireNotNull(direct.pointClosestApproachAt(observedAt(direct)))
        val lateralCpa = requireNotNull(lateral.pointClosestApproachAt(observedAt(lateral)))
        assertEquals(2.0, requireNotNull(directCpa.signedTimeSeconds), .002)
        assertEquals(0.0, directCpa.distanceM, .002)
        assertEquals(1.0, requireNotNull(lateralCpa.signedTimeSeconds), .002)
        assertEquals(sqrt(2.0), lateralCpa.distanceM, .002)
        assertNotSame(directCpa.materialPointId, lateralCpa.materialPointId)
        for (output in listOf(direct, lateral)) {
            val now = observedAt(output)
            assertEquals(800L, output.trackStableMs)
            assertTrue(requireNotNull(output.approachTimeMs) in 1_999L..2_001L)
            assertSame(output.pointClosestApproach, output.pointClosestApproachAt(now))
            val candidate = selectedCandidate(output)
            assertEquals(MessageLevel.STOP, candidate.level)
            val policy = WalkSafeFeedbackPolicy()
            val action = requireNotNull(policy.evaluate(candidate, true, now))
            assertEquals(MessageLevel.STOP, action.level)
            assertTrue(policy.claimFeedbackDelivery(action, now, now))
            assertTrue(policy.hasClaimedFeedbackDelivery(action))
            assertTrue(policy.confirmFeedbackDelivery(action, now, now + 100L))
            assertFalse(policy.hasClaimedFeedbackDelivery(action))
        }
    }

    @Test
    fun realCaptureRetiresOnlyPointBasisAndOriginalLeaseKeepsClaimedOwner() {
        for (loseReference in listOf(false, true)) {
            val outputs = captures(frames = 6, retireLastPoint = !loseReference,
                retireLastReference = loseReference)
            val original = outputs[4]
            val retired = outputs[5]
            val originalNow = observedAt(original)
            val retiredNow = observedAt(retired)
            val cpa = requireNotNull(original.pointClosestApproachAt(originalNow))
            assertNull(retired.pointClosestApproachAt(retiredNow))
            assertEquals(original.trackId, retired.trackId)
            assertEquals(2f, requireNotNull(retired.riskDistanceM), .001f)
            val before = selectedCandidate(original)
            val after = selectedCandidate(retired)
            assertEquals(MessageLevel.STOP, after.level)
            val policy = WalkSafeFeedbackPolicy()
            val action = requireNotNull(policy.evaluate(before, true, originalNow))
            assertTrue(policy.claimFeedbackDelivery(action, originalNow, originalNow))
            assertNull(policy.evaluate(after, true, retiredNow))
            assertTrue(policy.hasClaimedFeedbackDelivery(action))

            val until = requireNotNull(original.sourceValidUntilElapsedRealtimeMs)
            assertSame(cpa, original.pointClosestApproachAt(until))
            assertNull(original.pointClosestApproachAt(until + 1L))
            for (retagged in listOf(
                original.copy(frameId = original.frameId + 1L), // Foreign full Frame, same ms.
                original.copy(sourceObservedAtElapsedRealtimeMs = originalNow + 1L),
                original.copy(sourceValidUntilElapsedRealtimeMs = until + 1L),
                original.copy(sourceMaxAgeMs = requireNotNull(original.sourceMaxAgeMs) + 1L),
            )) {
                assertNull(retagged.pointClosestApproachAt(originalNow))
                assertEquals(original.approachTimeMs, retagged.approachTimeMs)
                assertEquals(CollisionAssessment.UNKNOWN, retagged.collisionAssessment)
                assertEquals(MessageLevel.STOP, retagged.userFacing.messageLevel)
            }
            // A claimed policy receipt is retired by its terminal callback, not by point expiry.
            // This is no native START/physical delivery acknowledgement.
            assertNull(policy.evaluate(before, true, until + 1L))
            assertTrue(policy.hasClaimedFeedbackDelivery(action))
            assertTrue(policy.confirmFeedbackDelivery(action, originalNow, until + 1L))
            assertFalse(policy.hasClaimedFeedbackDelivery(action))

            val reserved = WalkSafeFeedbackPolicy()
            val pending = requireNotNull(reserved.evaluate(before, true, originalNow))
            assertFalse(reserved.claimFeedbackDelivery(pending, originalNow, until + 1L))
            assertFalse(reserved.hasClaimedFeedbackDelivery(pending))
            assertNull(WalkSafeFeedbackPolicy().evaluate(before, true, until + 1L))
        }
    }

    private fun selectedCandidate(output: TrackedObjectDepth): FeedbackCandidate {
        val selected = requireNotNull(AndroidRiskSelectionPolicy.selectBestOutput(listOf(output)))
        assertSame(output, selected)
        assertSame(output, AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(output)).single())
        val candidate = requireNotNull(selected.toFeedbackCandidate())
        assertEquals(output.approachTimeMs, candidate.approachTimeMs)
        assertEquals(output.collisionAssessment, candidate.collisionAssessment)
        assertEquals(CollisionAssessment.UNKNOWN, candidate.collisionAssessment)
        assertEquals(output.sourceObservedAtElapsedRealtimeMs, candidate.observedAtElapsedRealtimeMs)
        assertEquals(output.sourceValidUntilElapsedRealtimeMs, candidate.validUntilMs)
        assertEquals(output.sourceMaxAgeMs, candidate.observationMaxAgeMs)
        assertEquals(output.timestampMs, candidate.timestampMs)
        assertEquals(output.trackId, candidate.trackId)
        return candidate
    }

    private fun observedAt(output: TrackedObjectDepth) =
        requireNotNull(output.sourceObservedAtElapsedRealtimeMs)

    private fun captures(frames: Int = 5, lateralSpeed: Float = 0f,
        retireLastPoint: Boolean = false, retireLastReference: Boolean = false): List<TrackedObjectDepth> {
        val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        val pointId = VisualMaterialPointId()
        return (0 until frames).map { i ->
            val at = 1_000L + i * 200L
            val key = VisualFrameKey(7L, at * 1_000_000L, CPU_BASE + at * 1_000_000L,
                at + 50_000L, 3L)
            val millimeters = 2_000 + (frames - 1 - i) * 200
            val x = -lateralSpeed * (frames - 1 - i) * .2f
            val point = Point2(.5f + .7f * x / (millimeters / 1_000f), .5f)
            val id = if (retireLastPoint && i == frames - 1) VisualMaterialPointId() else pointId
            val observation = knownMaterialPointObservation(key, key, detection, id, point)
            val reference = if (retireLastReference && i == frames - 1) 43L else 42L
            val pose = CameraPoseEvidence(reference, at, 0f, 0f, 0f, 0f, 0f, -1f,
                CameraImageProjection(SIZE, SIZE, 70f, 70f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f))
            val snapshot = DepthFrameSnapshot(key.frameId,
                DepthImage16(SIZE, SIZE, IntArray(SIZE * SIZE) { millimeters }),
                ConfidenceImage8(SIZE, SIZE, ByteArray(SIZE * SIZE) { 255.toByte() }), null,
                rawDepthTimestampNs = key.cameraTimestampNs, rawConfidenceTimestampNs = key.cameraTimestampNs,
                cameraImageTimestampNs = key.cameraTimestampNs, cameraPoseEvidence = pose)
            pipeline.processTrackedObservation(snapshot, observation, mapper, key.frameId,
                key.capturedAtElapsedRealtimeMs).single()
        }
    }

    private companion object {
        const val SIZE = 100
        const val CPU_BASE = 10_000_000_000_000L
        val detection = DetectionCandidate("car", .95f, RectNorm(0f, 0f, 1f, 1f))
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(SIZE, SIZE, SIZE, SIZE, 1f, 0f, 0f),
            ImageSize(SIZE, SIZE))
    }
}
