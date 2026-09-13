package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingFailure
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackedObjectDepthRuntimePipelineTest {
    private val detection = DetectionCandidate("person", 0.9f, RectNorm(0.1f, 0.2f, 0.2f, 0.5f))
    private val mapper = LetterboxCoordinateMapper(
        ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100),
    )

    @Test
    fun currentImageGeometrySamplesItsOwnDepthWithoutManufacturingDetectorConfirmations() {
        val tracker = ObjectTracker(trackIdPrefix = "visual-track-")
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker)
        val source = key(1_000L)
        val first = process(pipeline, observation(source, key(1_040L))).single()
        val moved = observation(source, key(1_140L), detection.copy(bboxNorm = RectNorm(0.35f, 0.2f, 0.2f, 0.5f)))
        val currentDepth = depth(moved.targetKey).copy(rawDepth = DepthImage16(100, 100, IntArray(10_000) { pixel ->
            if (pixel % 100 in 35..55) 1_200 else 2_000
        }))
        val current = process(pipeline, moved, currentDepth).single()

        assertEquals("visual-track-1", first.trackId)
        assertEquals(first.trackId, current.trackId)
        assertEquals(2f, first.zDistanceM!!, 0.001f)
        assertEquals(1.2f, current.zDistanceM!!, 0.001f)
        assertEquals(moved.targetKey.frameId, current.frameId)
        assertEquals(0.45f, current.centerNorm.x, 0.001f)
        assertEquals(1, tracker.activeTracks().single().ageFrames)
        assertFalse(tracker.activeTracks().single().stable)
    }

    @Test
    fun onlyUniqueDetectorSourcesAdvanceStabilityAcrossManyVisualFrames() {
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker)
        val source = key(1_000L)
        (0..7).forEach { process(pipeline, observation(source, key(1_040L + it * 50L))) }
        assertEquals(1, tracker.activeTracks().single().ageFrames)
        assertFalse(tracker.activeTracks().single().stable)

        process(pipeline, observation(key(1_500L), key(1_540L)))
        process(pipeline, observation(key(1_800L), key(1_840L)))
        assertEquals(3, tracker.activeTracks().single().ageFrames)
        assertTrue(tracker.activeTracks().single().stable)
    }

    @Test
    fun repeatedFrameAndRepeatedCpuCaptureDoNotAddEvidence() {
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker)
        val first = observation(key(1_000L), key(1_040L))
        val output = process(pipeline, first)
        val historySize = tracker.activeTracks().single().distanceHistory.size
        assertEquals(output, process(pipeline, first))
        assertEquals(historySize, tracker.activeTracks().single().distanceHistory.size)

        val duplicateCpu = observation(first.sourceKey, key(1_140L).copy(cameraTimestampNs = first.targetKey.cameraTimestampNs))
        assertTrue(process(pipeline, duplicateCpu).isEmpty())
        assertEquals(historySize, tracker.activeTracks().single().distanceHistory.size)
        assertEquals(1, tracker.activeTracks().single().ageFrames)
    }

    @Test
    fun currentDepthMapperAndCaptureMustMatchAndOriginalSourceMustRemainFresh() {
        val pipeline = ObjectDepthRuntimePipeline()
        val observation = observation(key(1_000L), key(1_040L))
        val snapshot = depth(observation.targetKey)
        listOf(
            snapshot.copy(frameTimestampNs = snapshot.frameTimestampNs - 1L),
            snapshot.copy(cameraImageTimestampNs = null),
            snapshot.copy(cameraImageTimestampNs = snapshot.cameraImageTimestampNs!! - 1L),
        ).forEach { assertTrue(process(pipeline, observation, it).isEmpty()) }
        assertTrue(pipeline.processTrackedObservation(
            snapshot, observation, mapper, observation.targetKey.frameId - 1L,
            observation.targetKey.capturedAtElapsedRealtimeMs,
        ).isEmpty())
        assertTrue(pipeline.processTrackedObservation(
            snapshot, observation, mapper, observation.targetKey.frameId,
            observation.sourceKey.capturedAtElapsedRealtimeMs + 801L,
        ).isEmpty())
    }

    @Test
    fun terminalVisualLossDoesNotFallBackToFrozenGeometryOrRecoverWithoutADetector() {
        val pipeline = ObjectDepthRuntimePipeline()
        val source = key(1_000L)
        val initial = process(pipeline, observation(source, key(1_040L))).single()
        assertTrue(process(pipeline, observation(source, key(1_140L), lost = true)).isEmpty())
        assertTrue(process(pipeline, observation(source, key(1_240L))).isEmpty())
        val rediscovered = process(pipeline, observation(key(1_300L), key(1_340L))).single()
        assertTrue(initial.trackId != rediscovered.trackId)
    }

    @Test
    fun unfinishedBackfillPublishesNothingAndDoesNotConsumeTheSourceConfirmation() {
        listOf(VisualTrackingFailure.WORK_BUDGET_EXCEEDED, VisualTrackingFailure.TIME_BUDGET_EXCEEDED).forEach { failure ->
            val tracker = ObjectTracker()
            val pipeline = ObjectDepthRuntimePipeline(tracker = tracker)
            val source = key(1_000L)
            assertTrue(process(pipeline, observation(source, key(1_040L), lost = true, failure = failure)).isEmpty())
            assertTrue(tracker.activeTracks().isEmpty())
            val initial = process(pipeline, observation(source, key(1_140L))).single()
            assertTrue(process(pipeline, observation(source, key(1_240L), lost = true, failure = failure)).isEmpty())
            val resumed = process(pipeline, observation(source, key(1_340L))).single()
            assertEquals(initial.trackId, resumed.trackId)
            assertEquals(1, tracker.activeTracks().single().ageFrames)
        }
    }

    @Test
    fun frozenReportAndCurrentVisualTracksHaveIndependentConfirmationsAndIds() {
        val reportTracker = ObjectTracker()
        val visualTracker = ObjectTracker(trackIdPrefix = "visual-track-")
        val reportPipeline = ObjectDepthRuntimePipeline(tracker = reportTracker)
        val visualPipeline = ObjectDepthRuntimePipeline(tracker = visualTracker)
        val source = key(1_000L)
        val report = reportPipeline.process(depth(source), source.frameId, 1_000L, listOf(detection)).single()
        val current = process(visualPipeline, observation(source, key(1_040L))).single()
        process(visualPipeline, observation(source, key(1_140L)))
        assertEquals("track-1", report.trackId)
        assertEquals("visual-track-1", current.trackId)
        assertEquals(1, reportTracker.activeTracks().single().ageFrames)
        assertEquals(1, visualTracker.activeTracks().single().ageFrames)
        assertEquals(1_000L, reportTracker.activeTracks().single().lastSeenAtMs)
        assertEquals(1_140L, visualTracker.activeTracks().single().lastSeenAtMs)
    }

    @Test
    fun bboxOnlyImageTrackingCanDistinguishStationaryAndApproachingObjectsWithMetricPoseEvidence() {
        fun sequence(cameraSpeedMps: Float, reprojectLastDepth: Boolean = false): TrackedObjectDepth {
            val pipeline = ObjectDepthRuntimePipeline()
            val centered = detection.copy(bboxNorm = RectNorm(0.35f, 0.35f, 0.30f, 0.30f))
            return (0..12).map { index ->
                val target = key(1_040L + index * 100L)
                val source = key(1_000L + (index / 5) * 500L)
                val observation = observation(source, target, centered, sourceDetection = centered, quality = 0.70f)
                val zMm = 4_000 - index * 100
                val snapshot = depth(target).copy(
                    rawDepth = DepthImage16(100, 100, IntArray(10_000) { zMm }),
                    rawDepthTimestampNs = if (reprojectLastDepth && index == 12) key(2_140L).cameraTimestampNs else target.cameraTimestampNs,
                    cameraPoseEvidence = CameraPoseEvidence(
                        referenceId = 7L, timestampMs = target.frameId / 1_000_000L,
                        positionX = 0f, positionY = 0f, positionZ = -cameraSpeedMps * index * 0.1f,
                        forwardX = 0f, forwardY = 0f, forwardZ = -1f,
                        imageProjection = CameraImageProjection(100, 100, 70f, 70f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f),
                    ),
                )
                process(pipeline, observation, snapshot).single()
            }.last()
        }
        val stationary = sequence(cameraSpeedMps = 1f)
        val approaching = sequence(cameraSpeedMps = 0f)
        assertEquals(ObjectMotion.USER_APPROACHING_STATIONARY, stationary.objectMotion)
        assertEquals(0f, stationary.motionEstimate.objectSpeedMps!!, 0.001f)
        assertEquals(ObjectMotion.OBJECT_APPROACHING, approaching.objectMotion)
        assertEquals(1f, approaching.motionEstimate.objectSpeedMps!!, 0.001f)
        assertEquals(detection.detectionConfidence, approaching.detectionConfidence, 0f)
        val reprojected = sequence(cameraSpeedMps = 0f, reprojectLastDepth = true)
        assertEquals(2.8f, reprojected.zDistanceM!!, 0.001f)
        assertTrue(reprojected.confidence.finalScore > 0f)
        assertNull(reprojected.approachSpeedMps)
        assertNull(reprojected.timeToCollisionMs)
        assertEquals(ObjectMotion.UNKNOWN, reprojected.objectMotion)
    }

    private fun key(atMs: Long) = VisualFrameKey(7L, atMs * 1_000_000L, atMs * 1_000_000L + 400_000L, atMs + 50_000L, 3L)

    private fun observation(
        source: VisualFrameKey,
        target: VisualFrameKey,
        geometry: DetectionCandidate = detection,
        lost: Boolean = false,
        failure: VisualTrackingFailure = VisualTrackingFailure.AMBIGUOUS_PATCH,
        sourceDetection: DetectionCandidate = detection,
        quality: Float = 0.95f,
    ): CurrentTrackedFrameObservation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
        VisualTrackingResult(source, target, listOf(VisualTrackingObservation(
            sourceIndex = 0, detectorSourceKey = source, trackedTargetKey = target,
            status = if (lost) VisualTrackingStatus.LOST else VisualTrackingStatus.TRACKED,
            geometry = geometry.takeUnless { lost }, trackingQuality = if (lost) 0f else quality,
            failure = failure.takeIf { lost }, translationNorm = Point2(0f, 0f),
            originalFeatureCount = 8, survivingFeatureCount = 8, residualPx = 0f, polygonConstrained = false,
        )), VisualTrackingMetrics(0L, 1, 0L, false, 2, 0)),
        listOf(sourceDetection), true, target.capturedAtElapsedRealtimeMs,
    ))

    private fun depth(key: VisualFrameKey) = DepthFrameSnapshot(
        frameTimestampNs = key.frameId,
        rawDepth = DepthImage16(100, 100, IntArray(10_000) { 2_000 }),
        rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
        fullDepth = null,
        rawDepthTimestampNs = key.cameraTimestampNs,
        cameraImageTimestampNs = key.cameraTimestampNs,
    )

    private fun process(
        pipeline: ObjectDepthRuntimePipeline,
        observation: CurrentTrackedFrameObservation,
        snapshot: DepthFrameSnapshot = depth(observation.targetKey),
    ) = pipeline.processTrackedObservation(
        snapshot, observation, mapper, observation.targetKey.frameId, observation.targetKey.capturedAtElapsedRealtimeMs,
    )
}
