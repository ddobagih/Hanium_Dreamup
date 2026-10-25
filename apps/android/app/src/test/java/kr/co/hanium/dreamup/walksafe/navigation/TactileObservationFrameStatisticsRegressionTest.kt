package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
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

class TactileObservationFrameStatisticsRegressionTest {
    @Test
    fun completedTactileFrameWithoutMapperReportsDepthFailureBeforeAnyTrackExists() {
        val result = process(coordinator(), snapshot(1))

        assertTrue(result.guidance.observationDiagnosticsText().contains("obsFrame=depth_insufficient"))
        assertTrue(result.guidance.observationDiagnosticsText().contains("depth_insufficient:1/1(100.0%)"))
        assertFalse(result.depthProcessingAttempted)
        assertTrue(result.guidance.outputs.isEmpty())
        assertNull(result.guidance.observation)
        assertEquals(LocalRouteMode.TMAP, result.guidance.decision.mode)
        assertNull(result.guidance.decision.steering)
    }

    @Test
    fun mismatchedCaptureReportsFrameFailureAndSharesOneFrameDenominator() {
        val coordinator = coordinator()
        process(coordinator, snapshot(1))
        val badIdentity = snapshot(2).let { it.copy(identity = it.identity.copy(captureFrameId = 999L)) }
        val result = process(coordinator, badIdentity)

        assertTrue(result.guidance.observationDiagnosticsText().contains("obsFrame=frame_mismatch"))
        assertTrue(result.guidance.observationDiagnosticsText().contains("obsFrames=2"))
        assertTrue(result.guidance.observationDiagnosticsText().contains("frame_mismatch:1/2(50.0%)"))
        assertTrue(result.guidance.observationDiagnosticsText().contains("depth_insufficient:1/2(50.0%)"))
        assertNull(result.matchedEvidence)
        assertFalse(result.depthProcessingAttempted)
        assertEquals(LocalRouteMode.TMAP, result.guidance.decision.mode)
    }

    @Test
    fun repeatedSnapshotAndItsLaterExpiryDoNotReclassifyTheCapturedFrame() {
        val coordinator = coordinator()
        val snapshot = snapshot(1)
        process(coordinator, snapshot)
        process(coordinator, snapshot)
        val stale = process(coordinator, snapshot, nowMs = 3_000L)

        assertTrue(stale.guidance.observationDiagnosticsText().contains("obsFrame=detection_stale"))
        assertEquals(1L, stale.guidance.observationStatistics?.candidateFrames)
        assertEquals(mapOf(TactileObservationRejection.DEPTH_INSUFFICIENT to 1L),
            stale.guidance.observationStatistics?.reasonFrames)
    }

    @Test
    fun partialResultIsDiagnosedButCountedOnlyWhenTheSameCaptureCompletes() {
        val coordinator = coordinator()
        val complete = snapshot(1)
        val partial = process(coordinator, complete.copy(partial = true))
        assertEquals(TactileObservationRejection.DETECTION_INCOMPLETE, partial.guidance.observationFrameRejection)
        assertEquals(0L, partial.guidance.observationStatistics?.candidateFrames)

        val result = process(coordinator, complete)
        assertEquals(1L, result.guidance.observationStatistics?.candidateFrames)
        assertFalse(requireNotNull(result.guidance.observationStatistics).reasonFrames
            .containsKey(TactileObservationRejection.DETECTION_INCOMPLETE))
    }

    @Test
    fun ordinaryDetectionIsNotPartOfTheTactileFrameDenominator() {
        val result = process(coordinator(), snapshot(1).copy(
            detections = listOf(DetectionCandidate("person", 0.9f, RectNorm(0.4f, 0.5f, 0.2f, 0.3f))),
        ))
        assertNull(result.guidance.observationFrameRejection)
        assertEquals(0L, result.guidance.observationStatistics?.candidateFrames)
    }

    @Test
    fun missingCaptureIdentityKeepsTheReasonButDoesNotInventAFrameCount() {
        val result = process(coordinator(), snapshot(1).copy(capturedAtElapsedRealtimeMs = null))
        assertEquals(TactileObservationRejection.DEPTH_INSUFFICIENT, result.guidance.observationFrameRejection)
        assertEquals(0L, result.guidance.observationStatistics?.candidateFrames)
        assertTrue(result.guidance.observationDiagnosticsText().contains("obsFrameId=unavailable"))
    }

    @Test
    fun missingCaptureContextIsReportedBeforeAnyDepthOutputExists() {
        val result = process(coordinator(), snapshot(1).copy(frameEvidence = null))
        assertEquals(TactileObservationRejection.CAPTURE_CONTEXT_UNAVAILABLE, result.guidance.observationFrameRejection)
        assertEquals(mapOf(TactileObservationRejection.CAPTURE_CONTEXT_UNAVAILABLE to 1L),
            result.guidance.observationStatistics?.reasonFrames)
    }

    @Test
    fun currentProducerKeepsMapperFailureEvenWhenAdmissionReturnedNull() {
        val coordinator = coordinator()
        val source = visualSnapshot(1)
        val evidence = requireNotNull(visualSnapshot(2).frameEvidence)
        val diagnostics = TactileCurrentFrameDiagnostics.fromCapture(
            source, requireNotNull(evidence.visualFrameKey), evidence, null, null, 100L,
        )
        val result = coordinator.processTrackedObservation(
            frame = null, nowElapsedRealtimeMs = 100L,
            navigationActiveNow = true, tmapOnRouteNow = true, activeRouteId = "route",
            observationDiagnostics = diagnostics,
        ) { _, _ -> error("Rejected current evidence cannot enter depth processing") }

        assertEquals(TactileObservationRejection.DEPTH_INSUFFICIENT, result.guidance.observationFrameRejection)
        assertTrue(result.guidance.observationDiagnosticsText().contains("depth_insufficient:1/1(100.0%)"))
        assertTrue(result.guidance.outputs.isEmpty())
        assertNull(result.guidance.observation)
        assertEquals(LocalRouteMode.TMAP, result.guidance.decision.mode)
        assertNull(result.guidance.decision.steering)
    }

    @Test
    fun currentCaptureMismatchCountsAlongsideMissingDepthInOneCurrentCoordinator() {
        val coordinator = coordinator()
        val source = visualSnapshot(1)
        for (n in 2..3) {
            val evidence = requireNotNull(visualSnapshot(n).frameEvidence)
            val diagnostics = TactileCurrentFrameDiagnostics.fromCapture(
                source, evidence.visualFrameKey,
                if (n == 3) evidence.copy(frameId = 999L) else evidence, null, null, 100L,
            )
            val result = coordinator.processTrackedObservation(
                frame = null, nowElapsedRealtimeMs = 100L,
                navigationActiveNow = true, tmapOnRouteNow = true, activeRouteId = "route",
                observationDiagnostics = diagnostics,
            ) { _, _ -> error("Rejected capture cannot create a metric output") }
            if (n == 3) {
                assertTrue(result.guidance.observationDiagnosticsText().contains("frame_mismatch:1/2(50.0%)"))
                assertTrue(result.guidance.observationDiagnosticsText().contains("depth_insufficient:1/2(50.0%)"))
            }
        }
    }

    @Test
    fun allLostCurrentCandidatesCountWithoutRetainingGeometryOrChangingFallback() {
        val coordinator = coordinator()
        val source = visualSnapshot(1)
        var calls = 0
        for (n in listOf(2, 3, 3)) {
            val targetEvidence = requireNotNull(visualSnapshot(n).frameEvidence).let { evidence ->
                evidence.copy(depthMapper = FrozenImageToTextureCoordinateMapper(
                    evidence.frameId,
                    requireNotNull(FrozenImageToDepthTransform.create(evidence.frameId,
                        listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f))),
                    ImageSize(2, 2),
                ))
            }
            val sourceKey = requireNotNull(source.frameEvidence?.visualFrameKey)
            val targetKey = requireNotNull(targetEvidence.visualFrameKey)
            val tracking = VisualTrackingResult(sourceKey, targetKey, listOf(VisualTrackingObservation(
                0, sourceKey, targetKey, VisualTrackingStatus.LOST, null, 0f,
                VisualTrackingFailure.FRAME_GAP, null, 0, 0, null, false,
            )), VisualTrackingMetrics(0L, 0, 0L, false, 2, 8))
            val admitted = requireNotNull(CurrentTrackedFrameEvidence.fromTrackingResult(source, tracking, targetEvidence, 100L))
            val diagnostics = TactileCurrentFrameDiagnostics.fromCapture(source, targetKey, targetEvidence, tracking, admitted, 100L)
            val result = coordinator.processTrackedObservation(
                frame = admitted, nowElapsedRealtimeMs = 100L,
                navigationActiveNow = true, tmapOnRouteNow = true, activeRouteId = "route",
                observationDiagnostics = diagnostics,
            ) { observation, _ ->
                calls += 1
                assertTrue(observation.trackedObservations.isEmpty())
                emptyList()
            }
            assertEquals(TactileObservationRejection.VISUAL_TRACKING_UNAVAILABLE, result.guidance.observationFrameRejection)
            assertTrue(result.guidance.outputs.isEmpty())
            assertEquals(LocalRouteMode.TMAP, result.guidance.decision.mode)
            assertEquals(if (n == 2) 1L else 2L, result.guidance.observationStatistics?.candidateFrames)
            assertEquals(if (n == 2) 1L else 2L,
                result.guidance.observationStatistics?.reasonFrames?.get(TactileObservationRejection.VISUAL_TRACKING_UNAVAILABLE))
        }
        assertEquals(3, calls)
    }

    @Test
    fun currentPartialAndOrdinarySourcesDoNotInflateTheFrameDenominator() {
        val coordinator = coordinator()
        val evidence = requireNotNull(visualSnapshot(2).frameEvidence)
        val sources = listOf(visualSnapshot(1).copy(partial = true), visualSnapshot(1).copy(detections = emptyList()))
        for (source in sources) {
            val result = coordinator.processTrackedObservation(
                frame = null, nowElapsedRealtimeMs = 100L,
                navigationActiveNow = true, tmapOnRouteNow = true, activeRouteId = "route",
                observationDiagnostics = TactileCurrentFrameDiagnostics.fromCapture(source, evidence.visualFrameKey, evidence, null, null, 100L),
            ) { _, _ -> error("No admitted current frame") }
            assertEquals(0L, result.guidance.observationStatistics?.candidateFrames)
        }
    }

    @Test
    fun currentExpiredSourceAndMissingContextAreExplicitRejections() {
        val source = visualSnapshot(1)
        val evidence = requireNotNull(visualSnapshot(2).frameEvidence)
        assertEquals(TactileObservationRejection.DETECTION_STALE,
            TactileCurrentFrameDiagnostics.fromCapture(source, evidence.visualFrameKey, evidence, null, null, 1_000L).rejection)
        assertEquals(TactileObservationRejection.CAPTURE_CONTEXT_UNAVAILABLE,
            TactileCurrentFrameDiagnostics.fromCapture(source, evidence.visualFrameKey, null, null, null, 100L).rejection)
    }

    @Test
    fun currentProducerUsesTheSameEvaluationInstantAtTheInclusiveSourceAgeBoundary() {
        val coordinator = coordinator()
        val source = visualSnapshot(1)
        val sourceKey = requireNotNull(source.frameEvidence?.visualFrameKey)
        var depthCalls = 0
        for ((n, nowMs) in listOf(2 to 810L, 3 to 811L)) {
            val evidence = requireNotNull(visualSnapshot(n).frameEvidence).let { raw ->
                raw.copy(depthMapper = FrozenImageToTextureCoordinateMapper(
                    raw.frameId,
                    requireNotNull(FrozenImageToDepthTransform.create(raw.frameId,
                        listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f))),
                    ImageSize(2, 2),
                ))
            }
            val targetKey = requireNotNull(evidence.visualFrameKey)
            val tracking = VisualTrackingResult(sourceKey, targetKey, listOf(VisualTrackingObservation(
                0, sourceKey, targetKey, VisualTrackingStatus.TRACKED, source.detections.single(), 1f,
                null, null, 0, 0, null, false,
            )), VisualTrackingMetrics(0L, 0, 0L, false, 2, 8))
            // Admission can precede evaluation; retain that valid frame to exercise expiry here.
            val admitted = requireNotNull(CurrentTrackedFrameEvidence.fromTrackingResult(source, tracking, evidence, 810L))
            val diagnostics = TactileCurrentFrameDiagnostics.fromCapture(source, targetKey, evidence, tracking, admitted, nowMs)
            val result = coordinator.processTrackedObservation(
                frame = admitted, nowElapsedRealtimeMs = nowMs, observationDiagnostics = diagnostics,
                navigationActiveNow = true, tmapOnRouteNow = true, activeRouteId = "route",
            ) { _, _ -> depthCalls += 1; emptyList() }

            assertEquals(n.toLong() - 1L, result.guidance.observationStatistics?.candidateFrames)
            assertEquals(LocalRouteMode.TMAP, result.guidance.decision.mode)
            assertTrue(result.guidance.outputs.isEmpty())
            if (nowMs == 810L) {
                assertNull(result.guidance.observationFrameRejection)
                assertTrue(requireNotNull(result.guidance.observationStatistics).reasonFrames.isEmpty())
            } else {
                assertEquals(TactileObservationRejection.DETECTION_STALE, result.guidance.observationFrameRejection)
                assertTrue(result.guidance.observationDiagnosticsText().contains("detection_stale:1/2(50.0%)"))
            }
        }
        assertEquals(1, depthCalls)
    }

    @Test
    fun eachReasonCountsOncePerFrameAndTheDenominatorIncludesFramesWithoutRejection() {
        val statistics = TactileObservationFrameStatistics()
        statistics.record(key(1), listOf(TactileObservationRejection.DEPTH_INSUFFICIENT,
            TactileObservationRejection.DEPTH_INSUFFICIENT, TactileObservationRejection.CONTACT_UNAVAILABLE))
        val result = statistics.record(key(2), emptyList())

        assertEquals(2L, result.candidateFrames)
        assertEquals(mapOf(TactileObservationRejection.DEPTH_INSUFFICIENT to 1L,
            TactileObservationRejection.CONTACT_UNAVAILABLE to 1L), result.reasonFrames)
        assertTrue(result.diagnosticsText().contains("depth_insufficient:1/2(50.0%)"))
        assertTrue(result.diagnosticsText().contains("contact_unavailable:1/2(50.0%)"))
    }

    @Test
    fun duplicateCannotAddANewRejectionToAnEarlierFrame() {
        val statistics = TactileObservationFrameStatistics()
        statistics.record(key(1), emptyList())
        val result = statistics.record(key(1), listOf(TactileObservationRejection.DETECTION_STALE))
        assertEquals(1L, result.candidateFrames)
        assertTrue(result.reasonFrames.isEmpty())
    }

    @Test
    fun lateDetectorFrameWithinRetainedWindowCountsExactlyOnce() {
        val statistics = TactileObservationFrameStatistics(retainedFrameLimit = 3)
        statistics.record(key(1), emptyList())
        statistics.record(key(3), emptyList())
        statistics.record(key(2), listOf(TactileObservationRejection.FRAME_MISMATCH))
        val result = statistics.record(key(2), listOf(TactileObservationRejection.FRAME_MISMATCH))
        assertEquals(3L, result.candidateFrames)
        assertEquals(1L, result.reasonFrames[TactileObservationRejection.FRAME_MISMATCH])
    }

    @Test
    fun replayOlderThanBoundedHistoryCannotInflateCumulativePercentages() {
        val statistics = TactileObservationFrameStatistics(retainedFrameLimit = 3)
        for (n in 1..100) statistics.record(key(n), listOf(TactileObservationRejection.DEPTH_INSUFFICIENT))
        val result = statistics.record(key(1), listOf(TactileObservationRejection.FRAME_MISMATCH))
        assertEquals(100L, result.candidateFrames)
        assertEquals(mapOf(TactileObservationRejection.DEPTH_INSUFFICIENT to 100L), result.reasonFrames)
    }

    @Test
    fun cameraFrameIdRestartWithLaterReceiptIsASeparateCapture() {
        val statistics = TactileObservationFrameStatistics()
        statistics.record(TactileObservationFrameKey(123L, 1_000L), emptyList())
        val result = statistics.record(TactileObservationFrameKey(123L, 2_000L), emptyList())
        assertEquals(2L, result.candidateFrames)
    }

    @Test
    fun missingOrInvalidIdentityIsExcludedAndPublishedSnapshotDoesNotMutate() {
        val statistics = TactileObservationFrameStatistics()
        statistics.record(null, listOf(TactileObservationRejection.FRAME_MISMATCH))
        statistics.record(TactileObservationFrameKey(-1L, 10L), emptyList())
        statistics.record(TactileObservationFrameKey(1L, -1L), emptyList())
        val before = statistics.snapshot()
        statistics.record(key(1), listOf(TactileObservationRejection.DEPTH_INSUFFICIENT))
        assertEquals(0L, before.candidateFrames)
        assertTrue(before.reasonFrames.isEmpty())
        assertEquals(1L, statistics.snapshot().candidateFrames)
    }

    private fun key(n: Int) = TactileObservationFrameKey(n * 1_000_000L, n * 10L)

    private fun visualSnapshot(n: Int): DetectionSnapshot {
        val snapshot = snapshot(n)
        val evidence = requireNotNull(snapshot.frameEvidence)
        val key = VisualFrameKey(1L, evidence.frameId, evidence.frameId, n * 10L, 1L)
        return snapshot.copy(frameEvidence = evidence.copy(
            visualFrameKey = key,
            depthSnapshot = evidence.depthSnapshot.copy(cameraImageTimestampNs = key.cameraTimestampNs),
        ))
    }

    private fun coordinator() = AndroidTactileFrameCoordinator(
        AndroidTactileRouteGuidance(), WalkSafeFeedbackPolicy(), TactileFrameFeedbackActuator {},
    )

    private fun process(
        coordinator: AndroidTactileFrameCoordinator,
        snapshot: DetectionSnapshot,
        nowMs: Long = 1_000L,
    ) = coordinator.processSnapshot(
        detectionSnapshot = snapshot,
        nowMs = nowMs,
        currentFrameTimestampMs = snapshot.frameTimestampMs ?: 0L,
        maxDetectionSourceAgeMs = 800L,
        maxDetectionFrameDeltaMs = 800L,
        detectionAgeMs = 10L,
        navigationActiveNow = true,
        tmapOnRouteNow = true,
        activeRouteId = "route",
        decisionElapsedRealtimeMs = nowMs,
    ) { _, _ -> error("Unavailable depth must not reach the depth pipeline") }

    private fun snapshot(n: Int): DetectionSnapshot {
        val frameId = n * 1_000_000L
        val context = TactileProjectionContext(
            captureFrameId = frameId, expectedRouteId = "route", routeProjection = null,
            trustedLocation = null, orientation = null, magneticDeclinationDeg = null,
            cameraFrame = null, detectionAgeMs = 10L, nowElapsedRealtimeMs = 1_000L,
        )
        return DetectionSnapshot(
            detections = listOf(DetectionCandidate("normal_tactile_block", 0.9f, RectNorm(0.4f, 0.5f, 0.2f, 0.3f))),
            identity = AndroidDetectionSnapshotFrameIdentity(frameId, n.toLong()),
            capturedAtMs = 1_000L, startedAtMs = 1_000L, completedAtMs = 1_000L,
            imageWidth = 640, imageHeight = 480, detectDurationMs = 0L, detectorTiming = null,
            partial = false, capturedAtElapsedRealtimeMs = n * 10L,
            frameEvidence = DetectionFrameEvidence(
                frameId, n.toLong(), DepthFrameSnapshot(frameId, null, null, null), null,
                context, MotionContext(), navigationActive = true, tmapOnRoute = true,
            ),
        )
    }
}
