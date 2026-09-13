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

class CurrentTrackedFrameObservationTest {
    private val source = VisualFrameKey(7L, 1_000_900_000L, 1_001_300_000L, 1_000L, 3L)
    private val target = VisualFrameKey(7L, 1_400_900_000L, 1_401_300_000L, 1_400L, 3L)
    private val detection = DetectionCandidate("person", 0.9f, RectNorm(0.2f, 0.2f, 0.3f, 0.4f))

    @Test
    fun currentTrackingPreservesOriginalSourceAndExpiresAfterEightHundredMilliseconds() {
        val latest = target.copy(
            frameId = source.frameId + 800_000_000L,
            cameraTimestampNs = source.cameraTimestampNs + 800_000_000L,
            capturedAtElapsedRealtimeMs = 1_800L,
        )
        val result = result(targetKey = latest)
        val observation = requireNotNull(create(result, nowMs = 1_800L))

        assertEquals(source, observation.sourceKey)
        assertEquals(latest, observation.targetKey)
        assertEquals(listOf(detection), observation.sourceDetections)
        assertEquals(1_800L, observation.timestampMs)
        assertTrue(observation.isFreshAt(1_800L))
        assertFalse(observation.isFreshAt(1_801L))
        assertNull(create(result, nowMs = 1_801L))
    }

    @Test
    fun partialFutureRegressedAndMissingCameraCapturesAreRejected() {
        assertNull(create(completed = false))
        assertNull(create(nowMs = target.capturedAtElapsedRealtimeMs - 1L))
        listOf(
            result(sourceKey = source.copy(cameraTimestampNs = 0L)),
            result(targetKey = target.copy(cameraTimestampNs = 0L)),
            result(sourceKey = source.copy(frameId = 0L)),
            result(targetKey = target.copy(frameId = 0L)),
            result(sourceKey = source.copy(capturedAtElapsedRealtimeMs = -1L)),
            result(targetKey = target.copy(frameId = source.frameId - 1L)),
            result(targetKey = target.copy(cameraTimestampNs = source.cameraTimestampNs - 1L)),
            result(targetKey = target.copy(capturedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs - 1L)),
            result(targetKey = target.copy(frameId = source.frameId + 801_000_000L)),
        ).forEach { assertNull(create(it)) }
    }

    @Test
    fun trackingAcrossEpochOrGeometryChangesIsRejected() {
        listOf(
            target.copy(epoch = source.epoch + 1L),
            target.copy(geometryVersion = source.geometryVersion + 1L),
        ).forEach { assertNull(create(result(targetKey = it))) }
    }

    @Test
    fun everySourceIndexMustAppearExactlyOnce() {
        val detections = listOf(detection, detection.copy(className = "car"))
        val complete = result(observations = detections.mapIndexed { index, geometry ->
            tracked(index = index, geometry = geometry)
        })
        assertEquals(2, requireNotNull(create(complete, detections)).observations.size)
        listOf(
            emptyList(),
            complete.observations.take(1),
            listOf(complete.observations[0], complete.observations[0]),
            complete.observations.map { it.copy(sourceIndex = it.sourceIndex + 1) },
            complete.observations.map { it.copy(sourceIndex = it.sourceIndex - 1) },
        ).forEach { assertNull(create(complete.copy(observations = it), detections)) }
    }

    @Test
    fun trackedGeometryCannotChangeDetectorClassConfidenceOrClaimFailedTracking() {
        val tracked = tracked()
        listOf(
            tracked.copy(geometry = detection.copy(className = "car")),
            tracked.copy(geometry = detection.copy(detectionConfidence = 0.95f)),
            tracked.copy(geometry = null),
            tracked.copy(failure = VisualTrackingFailure.AMBIGUOUS_PATCH),
        ).plus(listOf(Float.NaN, 0f, -0.1f, 1.1f).map { tracked.copy(trackingQuality = it) })
            .forEach { assertNull(create(result(observations = listOf(it)))) }
    }

    @Test
    fun eachObjectMustRetainTheBatchSourceAndTargetIdentities() {
        listOf(
            tracked().copy(detectorSourceKey = source.copy(frameId = source.frameId + 1L)),
            tracked().copy(trackedTargetKey = target.copy(cameraTimestampNs = target.cameraTimestampNs + 1L)),
        ).forEach { assertNull(create(result(observations = listOf(it)))) }
    }

    @Test
    fun explicitLostObjectsAccountForTheirSourceWithoutProvidingGeometry() {
        val lost = tracked().copy(
            status = VisualTrackingStatus.LOST,
            geometry = null,
            trackingQuality = 0f,
            failure = VisualTrackingFailure.TOO_FEW_FEATURES,
        )
        val observation = requireNotNull(create(result(observations = listOf(lost))))

        assertEquals(listOf(lost), observation.observations)
        assertTrue(observation.trackedObservations.isEmpty())
        assertNull(create(result(observations = listOf(lost.copy(geometry = detection)))))
    }

    @Test
    fun depthRequiresExactTargetCpuTimestampAndPoseTimeFromTheArFrame() {
        val observation = requireNotNull(create())
        val pose = CameraPoseEvidence(1L, 1_400L, 0f, 0f, 0f, 0f, 0f, -1f)
        val snapshot = DepthFrameSnapshot(
            frameTimestampNs = target.frameId,
            rawDepth = null,
            rawConfidence = null,
            fullDepth = null,
            cameraPoseEvidence = pose,
            cameraImageTimestampNs = target.cameraTimestampNs,
        )

        assertTrue(observation.matchesDepthSnapshot(snapshot))
        assertTrue(observation.matchesDepthSnapshot(snapshot.copy(cameraPoseEvidence = null)))
        listOf(null, 0L, target.frameId, target.cameraTimestampNs - 1L, target.cameraTimestampNs + 1L)
            .forEach { assertFalse(observation.matchesDepthSnapshot(snapshot.copy(cameraImageTimestampNs = it))) }
        assertFalse(observation.matchesDepthSnapshot(snapshot.copy(frameTimestampNs = target.frameId + 1L)))
        assertFalse(observation.matchesDepthSnapshot(snapshot.copy(
            cameraPoseEvidence = pose.copy(timestampMs = target.cameraTimestampNs / 1_000_000L),
        )))
    }

    private fun create(
        result: VisualTrackingResult = result(),
        detections: List<DetectionCandidate> = listOf(detection),
        completed: Boolean = true,
        nowMs: Long = 1_400L,
    ): CurrentTrackedFrameObservation? = CurrentTrackedFrameObservation.fromTrackingResult(
        result = result,
        sourceDetections = detections,
        sourceDetectionCompleted = completed,
        nowElapsedRealtimeMs = nowMs,
    )

    private fun result(
        sourceKey: VisualFrameKey = source,
        targetKey: VisualFrameKey = target,
        observations: List<VisualTrackingObservation> = listOf(tracked(sourceKey = sourceKey, targetKey = targetKey)),
    ): VisualTrackingResult = VisualTrackingResult(
        detectorSourceKey = sourceKey,
        trackedTargetKey = targetKey,
        observations = observations,
        metrics = VisualTrackingMetrics(0L, 1, 0L, false, 2, 0),
    )

    private fun tracked(
        index: Int = 0,
        geometry: DetectionCandidate = detection,
        sourceKey: VisualFrameKey = source,
        targetKey: VisualFrameKey = target,
    ): VisualTrackingObservation = VisualTrackingObservation(
        sourceIndex = index,
        detectorSourceKey = sourceKey,
        trackedTargetKey = targetKey,
        status = VisualTrackingStatus.TRACKED,
        geometry = geometry,
        trackingQuality = 0.8f,
        failure = null,
        translationNorm = Point2(0f, 0f),
        originalFeatureCount = 8,
        survivingFeatureCount = 8,
        residualPx = 0f,
        polygonConstrained = false,
    )
}
