package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingFailure
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTrackedFrameEvidenceTest {
    private val source = VisualFrameKey(7L, 1_000_900_000L, 1_001_300_000L, 1_000L, 3L)
    private val target = VisualFrameKey(7L, 1_400_900_000L, 1_401_300_000L, 1_400L, 3L)
    private val detection = DetectionCandidate("person", 0.9f, RectNorm(0.2f, 0.2f, 0.3f, 0.4f))
    private val jpeg = byteArrayOf(1, 2, 3)
    private val coordinator = createProductionAndroidTactileFrameCoordinator(WalkSafeFeedbackPolicy()) { }

    @Test
    fun sourceSnapshotMustMatchTheOriginalFrameCpuTimeElapsedTimeAndVisualKey() {
        val snapshot = sourceSnapshot()
        val evidence = requireNotNull(snapshot.frameEvidence)
        val invalidEvidence = listOf(
            evidence.copy(frameId = source.frameId + 1L),
            evidence.copy(frameTimestampMs = 1_001L),
            evidence.copy(depthSnapshot = evidence.depthSnapshot.copy(frameTimestampNs = source.frameId + 1L)),
            evidence.copy(depthSnapshot = evidence.depthSnapshot.copy(cameraImageTimestampNs = null)),
            evidence.copy(depthSnapshot = evidence.depthSnapshot.copy(cameraImageTimestampNs = source.cameraTimestampNs + 1L)),
            evidence.copy(visualFrameKey = null),
            evidence.copy(visualFrameKey = source.copy(epoch = source.epoch + 1L)),
            evidence.copy(visualFrameKey = source.copy(geometryVersion = source.geometryVersion + 1L)),
        )
        val invalidSnapshots = listOf(
            snapshot.copy(identity = snapshot.identity.copy(captureFrameId = source.frameId + 1L)),
            snapshot.copy(identity = snapshot.identity.copy(frameTimestampMs = 1_001L)),
            snapshot.copy(capturedAtElapsedRealtimeMs = null),
            snapshot.copy(capturedAtElapsedRealtimeMs = 1_001L),
            snapshot.copy(frameEvidence = null),
        ) + invalidEvidence.map { snapshot.copy(frameEvidence = it) }

        invalidSnapshots.forEach { assertNull(create(snapshot = it)) }
    }

    @Test
    fun currentFrameMapperContextAndDepthMustBelongToTheTrackedTarget() {
        val current = evidence(target)
        listOf(
            current.copy(visualFrameKey = null),
            current.copy(visualFrameKey = target.copy(geometryVersion = target.geometryVersion + 1L)),
            current.copy(frameId = source.frameId),
            current.copy(frameTimestampMs = 1_000L),
            current.copy(depthMapper = null),
            current.copy(depthMapper = evidence(source).depthMapper),
            current.copy(tactileContext = current.tactileContext.copy(captureFrameId = source.frameId)),
            current.copy(depthSnapshot = current.depthSnapshot.copy(frameTimestampNs = source.frameId)),
            current.copy(depthSnapshot = current.depthSnapshot.copy(cameraImageTimestampNs = target.cameraTimestampNs + 1L)),
        ).forEach { assertNull(create(current = it)) }
    }

    @Test
    fun currentDepthProcessingPreservesTheOriginalJpegAndCaptureTimes() {
        val snapshot = sourceSnapshot()
        val current = evidence(target)
        val frame = requireNotNull(create(snapshot = snapshot, current = current))
        var calls = 0

        val output = coordinator.processTrackedObservation(frame, 1_400L, false, false, null) { observation, evidence ->
            calls += 1
            assertSame(current, evidence)
            assertEquals(source, observation.sourceKey)
            assertEquals(target, observation.targetKey)
            emptyList()
        }

        assertEquals(1, calls)
        assertSame(current, output.matchedEvidence)
        assertTrue(output.depthProcessingAttempted)
        assertEquals(listOf(detection), output.depthDetections)
        assertEquals(400L, frame.sourceAgeMs(1_400L))
        assertEquals(source.frameId, snapshot.captureFrameId)
        assertEquals(20_000L, snapshot.capturedAtMs)
        assertEquals(1_000L, snapshot.capturedAtElapsedRealtimeMs)
        assertSame(jpeg, snapshot.reportImage)
        assertArrayEquals(byteArrayOf(1, 2, 3), snapshot.reportImage)
    }

    @Test
    fun sourceAgeOfEightHundredAndOneMillisecondsPreventsCurrentDepthProcessing() {
        val frame = requireNotNull(create())
        val output = coordinator.processTrackedObservation(frame, 1_801L, false, false, null) { _, _ ->
            error("Expired source must not reach the depth pipeline")
        }

        assertNull(output.matchedEvidence)
        assertFalse(output.depthProcessingAttempted)
        assertTrue(output.depthDetections.isEmpty())
        assertTrue(output.guidance.outputs.isEmpty())
    }

    @Test
    fun allLostObservationStillReachesTheDepthPipelineForTrackInvalidation() {
        val tracked = trackingResult()
        val lost = tracked.observations.single().copy(
            status = VisualTrackingStatus.LOST, geometry = null, trackingQuality = 0f,
            failure = VisualTrackingFailure.TOO_FEW_FEATURES,
        )
        val frame = requireNotNull(create(result = tracked.copy(observations = listOf(lost))))
        var calls = 0
        val output = coordinator.processTrackedObservation(frame, 1_400L, false, false, null) { observation, _ ->
            calls += 1
            assertEquals(listOf(lost), observation.observations)
            assertTrue(observation.trackedObservations.isEmpty())
            emptyList()
        }

        assertEquals(1, calls)
        assertTrue(output.depthDetections.isEmpty())
        assertTrue(output.guidance.outputs.isEmpty())
    }

    private fun create(
        snapshot: MainActivity.DetectionSnapshot = sourceSnapshot(),
        current: MainActivity.DetectionFrameEvidence = evidence(target),
        result: VisualTrackingResult = trackingResult(),
    ): CurrentTrackedFrameEvidence? = CurrentTrackedFrameEvidence.fromTrackingResult(snapshot, result, current, 1_400L)

    private fun sourceSnapshot(): MainActivity.DetectionSnapshot = MainActivity.DetectionSnapshot(
        detections = listOf(detection),
        identity = AndroidDetectionSnapshotFrameIdentity(source.frameId, source.frameId / 1_000_000L),
        capturedAtMs = 20_000L, startedAtMs = 20_001L, completedAtMs = 20_200L,
        imageWidth = 640, imageHeight = 480, detectDurationMs = 199L, detectorTiming = null,
        reportImage = jpeg, partial = false, frameEvidence = evidence(source),
        capturedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs,
    )

    private fun evidence(key: VisualFrameKey): MainActivity.DetectionFrameEvidence {
        val transform = requireNotNull(FrozenImageToDepthTransform.create(
            frameId = key.frameId,
            mappedCorners = listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)),
            mappedCenter = Point2(0.5f, 0.5f),
        ))
        return MainActivity.DetectionFrameEvidence(
            frameId = key.frameId, frameTimestampMs = key.frameId / 1_000_000L,
            depthSnapshot = DepthFrameSnapshot(key.frameId, null, null, null, cameraImageTimestampNs = key.cameraTimestampNs),
            depthMapper = FrozenImageToTextureCoordinateMapper(key.frameId, transform, ImageSize(2, 2)),
            tactileContext = TactileProjectionContext(
                captureFrameId = key.frameId, expectedRouteId = null, routeProjection = null,
                trustedLocation = null, orientation = null, magneticDeclinationDeg = null,
                cameraFrame = null, detectionAgeMs = 0L, nowElapsedRealtimeMs = key.capturedAtElapsedRealtimeMs,
            ),
            motionContext = MotionContext(), navigationActive = false, tmapOnRoute = false, visualFrameKey = key,
        )
    }

    private fun trackingResult(): VisualTrackingResult = VisualTrackingResult(
        detectorSourceKey = source, trackedTargetKey = target,
        observations = listOf(VisualTrackingObservation(
            sourceIndex = 0, detectorSourceKey = source, trackedTargetKey = target,
            status = VisualTrackingStatus.TRACKED, geometry = detection, trackingQuality = 0.8f, failure = null,
            translationNorm = Point2(0f, 0f), originalFeatureCount = 8, survivingFeatureCount = 8,
            residualPx = 0f, polygonConstrained = false,
        )),
        metrics = VisualTrackingMetrics(0L, 1, 0L, false, 2, 0),
    )
}
