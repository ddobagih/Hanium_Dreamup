package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.ContactDepthEstimate
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S12-10: actual current coordinator/supplier, with independent camera and elapsed clocks. */
class TactileCaptureDecisionTimeRegressionTest {
    @Test fun cameraFreshAtCaptureExpiresAtTheDecisionAfterOneHundredMilliseconds() {
        val result = evaluate(context(), CAPTURE_MS + 101L)
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.CAMERA_FRAME_UNTRUSTED, result.observationRejections[TRACK_ID])
    }

    @Test fun orientationFreshAtCaptureExpiresAtTheDecisionAfterOneHundredFiftyMilliseconds() {
        val base = context()
        val capture = base.copy(orientation = requireNotNull(base.orientation).copy(
            observedAtElapsedRealtimeMs = CAPTURE_MS - 150L,
        ))
        val result = evaluate(capture, CAPTURE_MS + 1L)
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.ORIENTATION_UNTRUSTED, result.observationRejections[TRACK_ID])
    }

    @Test fun locationFreshAtCaptureExpiresAtTheDecisionAfterTwoSeconds() {
        val base = context(10_000L)
        val capture = base.copy(trustedLocation = requireNotNull(base.trustedLocation).copy(elapsedRealtimeMs = 8_000L))
        val result = evaluate(capture, 10_001L)
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.LOCATION_UNTRUSTED, result.observationRejections[TRACK_ID])
    }

    @Test fun cameraAtTheInclusiveDecisionBoundaryStillHasAnObservation() {
        val capture = context()
        val originalCamera = capture.cameraFrame
        val result = evaluate(capture, CAPTURE_MS + 100L)
        assertNotNull(result.observation)
        assertTrue(result.observationRejections.isEmpty())
        assertEquals(CAPTURE_MS, capture.nowElapsedRealtimeMs)
        assertEquals(originalCamera, capture.cameraFrame)
        assertEquals(CAPTURE_MS, capture.cameraFrame?.observedAtElapsedRealtimeMs)
    }

    @Test fun orientationAtTheInclusiveCaptureAndDecisionBoundaryStillHasAnObservation() {
        val base = context()
        val capture = base.copy(orientation = requireNotNull(base.orientation).copy(
            observedAtElapsedRealtimeMs = CAPTURE_MS - 150L,
        ))
        assertNotNull(evaluate(capture, CAPTURE_MS).observation)
    }

    @Test fun locationAtTheInclusiveCaptureAndDecisionBoundaryStillHasAnObservation() {
        val base = context(10_000L)
        val capture = base.copy(trustedLocation = requireNotNull(base.trustedLocation).copy(elapsedRealtimeMs = 8_000L))
        assertNotNull(evaluate(capture, 10_000L).observation)
    }

    @Test fun decisionTimeDoesNotMakeAnOrientationCapturedAfterTheImageEligible() {
        val base = context()
        val capture = base.copy(orientation = requireNotNull(base.orientation).copy(
            observedAtElapsedRealtimeMs = CAPTURE_MS + 50L,
        ))
        val result = evaluate(capture, CAPTURE_MS + 100L)
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.ORIENTATION_UNTRUSTED, result.observationRejections[TRACK_ID])
    }

    @Test fun legacyCompositionPassesTheSeparateDecisionToTheSameSupplier() {
        val result = coordinator().evaluate(compositionInput(context(), CAPTURE_MS + 101L))
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.CAMERA_FRAME_UNTRUSTED, result.observationRejections[TRACK_ID])
        assertNoMovement(result)
    }

    @Test fun missingLegacyDecisionDoesNotFallBackToTheFrozenCaptureTime() {
        val result = coordinator().evaluate(compositionInput(context(), null))
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.DECISION_TIME_UNAVAILABLE, result.observationRejections[TRACK_ID])
        assertNoMovement(result)
    }

    @Test fun legacyDecisionBeforeCaptureDoesNotAdmitAnObservation() {
        val result = coordinator().evaluate(compositionInput(context(), CAPTURE_MS - 1L))
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.DECISION_TIME_UNAVAILABLE, result.observationRejections[TRACK_ID])
        assertNoMovement(result)
    }

    @Test fun legacySnapshotUsesElapsedDecisionRatherThanWallOrCameraTime() {
        val capture = context()
        val evidence = admittedFrame(capture).currentEvidence
        val snapshot = DetectionSnapshot(
            detections = listOf(DetectionCandidate("normal_tactile_block", .9f, RectNorm(.4f, .5f, .2f, .3f))),
            identity = AndroidDetectionSnapshotFrameIdentity(TARGET_FRAME_NS, TARGET_FRAME_NS / 1_000_000L),
            capturedAtMs = 1_700_000_000_000L, startedAtMs = 1_700_000_000_000L, completedAtMs = 1_700_000_000_050L,
            imageWidth = 2, imageHeight = 2, detectDurationMs = 50L, detectorTiming = null, partial = false,
            capturedAtElapsedRealtimeMs = CAPTURE_MS, frameEvidence = evidence,
        )
        var depthCalls = 0
        val result = coordinator().processSnapshot(
            detectionSnapshot = snapshot, nowMs = 1_700_000_000_101L,
            currentFrameTimestampMs = TARGET_FRAME_NS / 1_000_000L,
            maxDetectionSourceAgeMs = 800L, maxDetectionFrameDeltaMs = 800L, detectionAgeMs = 101L,
            navigationActiveNow = true, tmapOnRouteNow = true, activeRouteId = "active",
            decisionElapsedRealtimeMs = CAPTURE_MS + 101L,
        ) { inputEvidence, _ ->
            assertEquals(capture, inputEvidence.tactileContext)
            depthCalls += 1
            listOf(output(CAPTURE_MS))
        }.guidance
        assertEquals(1, depthCalls)
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.CAMERA_FRAME_UNTRUSTED, result.observationRejections[TRACK_ID])
        assertNoMovement(result)
    }

    private fun compositionInput(capture: TactileProjectionContext, decisionMs: Long?) = AndroidTactileFrameComposition.input(
        outputs = listOf(output(capture.nowElapsedRealtimeMs)),
        detectionIdentity = AndroidDetectionSnapshotFrameIdentity(TARGET_FRAME_NS, TARGET_FRAME_NS / 1_000_000L),
        evidenceIdentity = AndroidTactileEvidenceFrameIdentity(
            TARGET_FRAME_NS, TARGET_FRAME_NS / 1_000_000L, TARGET_FRAME_NS, TARGET_FRAME_NS,
        ),
        context = capture, detectionAgeMs = 100L,
        navigationActiveAtCapture = true, tmapOnRouteAtCapture = true,
        navigationActiveNow = true, tmapOnRouteNow = true, activeRouteId = "active",
        decisionElapsedRealtimeMs = decisionMs,
    )

    private fun coordinator() = AndroidTactileFrameCoordinator(
        AndroidTactileRouteGuidance(), WalkSafeFeedbackPolicy(), TactileFrameFeedbackActuator {},
    )

    private fun evaluate(capture: TactileProjectionContext, decisionMs: Long): TactileRouteGuidanceResult {
        val frame = admittedFrame(capture)
        val output = output(capture.nowElapsedRealtimeMs)
        assertEquals(VisualTrackingStatus.TRACKED, frame.observation.observations.single().status)
        assertTrue(frame.observation.isFreshAt(decisionMs))
        assertTrue(frame.sourceAgeMs(decisionMs) in 0L..400L)
        assertTrue(decisionMs <= requireNotNull(output.sourceValidUntilElapsedRealtimeMs))
        var depthCalls = 0
        val coordinator = coordinator()
        val result = coordinator.processTrackedObservation(
            frame = frame, nowElapsedRealtimeMs = decisionMs,
            navigationActiveNow = true, tmapOnRouteNow = true, activeRouteId = "active",
        ) { _, evidence ->
            assertEquals(capture, evidence.tactileContext)
            depthCalls += 1
            listOf(output)
        }.guidance
        assertEquals(1, depthCalls)
        assertEquals(capture, frame.currentEvidence.tactileContext)
        assertNoMovement(result)
        return result
    }

    private fun assertNoMovement(result: TactileRouteGuidanceResult) {
        // Existing ground-axis protection stays authoritative even when an observation is admitted.
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.steering)
        assertNull(result.decision.instruction)
        result.outputs.forEach {
            assertEquals(MessageLevel.NONE, it.userFacing.messageLevel)
            assertNull(it.userFacing.message)
        }
    }

    private fun admittedFrame(context: TactileProjectionContext): CurrentTrackedFrameEvidence {
        val sourceKey = VisualFrameKey(1L, SOURCE_FRAME_NS, SOURCE_IMAGE_NS, context.nowElapsedRealtimeMs - 100L, 1L)
        val targetKey = VisualFrameKey(1L, TARGET_FRAME_NS, TARGET_IMAGE_NS, context.nowElapsedRealtimeMs, 1L)
        val detection = DetectionCandidate("normal_tactile_block", .9f, RectNorm(.4f, .5f, .2f, .3f))
        val sourceEvidence = DetectionFrameEvidence(
            SOURCE_FRAME_NS, SOURCE_FRAME_NS / 1_000_000L,
            DepthFrameSnapshot(SOURCE_FRAME_NS, null, null, null, cameraImageTimestampNs = SOURCE_IMAGE_NS),
            null, context.copy(captureFrameId = SOURCE_FRAME_NS), MotionContext(), true, true,
            visualFrameKey = sourceKey,
        )
        val source = DetectionSnapshot(
            listOf(detection), AndroidDetectionSnapshotFrameIdentity(SOURCE_FRAME_NS, SOURCE_FRAME_NS / 1_000_000L),
            capturedAtMs = 1_700_000_000_000L, startedAtMs = 1_700_000_000_000L, completedAtMs = 1_700_000_000_050L,
            imageWidth = 2, imageHeight = 2, detectDurationMs = 50L, detectorTiming = null, partial = false,
            capturedAtElapsedRealtimeMs = sourceKey.capturedAtElapsedRealtimeMs, frameEvidence = sourceEvidence,
        )
        val transform = requireNotNull(FrozenImageToDepthTransform.create(
            TARGET_FRAME_NS, listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f),
        ))
        val targetEvidence = DetectionFrameEvidence(
            TARGET_FRAME_NS, TARGET_FRAME_NS / 1_000_000L,
            DepthFrameSnapshot(TARGET_FRAME_NS, null, null, null, cameraImageTimestampNs = TARGET_IMAGE_NS),
            FrozenImageToTextureCoordinateMapper(TARGET_FRAME_NS, transform, ImageSize(2, 2)),
            context, MotionContext(), true, true, visualFrameKey = targetKey,
        )
        val tracking = VisualTrackingResult(sourceKey, targetKey, listOf(VisualTrackingObservation(
            0, sourceKey, targetKey, VisualTrackingStatus.TRACKED, detection, 1f,
            null, null, 0, 0, null, false,
        )), VisualTrackingMetrics(0L, 0, 0L, false, 2, 8))
        return requireNotNull(CurrentTrackedFrameEvidence.fromTrackingResult(
            source, tracking, targetEvidence, context.nowElapsedRealtimeMs,
        ))
    }

    private fun context(captureMs: Long = CAPTURE_MS) = TactileProjectionContext(
        captureFrameId = TARGET_FRAME_NS, expectedRouteId = "active",
        routeProjection = ActiveRouteProjection("active", 0,
            RoutePoint(36.9999, 127.0), RoutePoint(37.001, 127.0), 0.0, 0f),
        trustedLocation = TrustedLocation(37.0, 127.0, 2f, captureMs - 50L),
        orientation = DeviceEarthOrientation(RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            captureMs, 5f, EarthOrientationAccuracy.HIGH),
        magneticDeclinationDeg = 0f,
        cameraFrame = TactileCameraFrame(TARGET_FRAME_NS,
            RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            TactileCameraIntrinsics(1_000, 1_000, 500f, 500f, 500f, 500f), captureMs, true),
        detectionAgeMs = null, nowElapsedRealtimeMs = captureMs,
    )

    private fun output(captureMs: Long) = TrackedObjectDepth(
        frameId = TARGET_FRAME_NS, timestampMs = TARGET_FRAME_NS / 1_000_000L, trackId = TRACK_ID,
        className = "normal_tactile_block", detectionConfidence = .9f,
        bboxNorm = RectNorm(.4f, .5f, .2f, .3f),
        polygonNorm = listOf(Point2(.4f, .5f), Point2(.6f, .5f), Point2(.6f, .8f), Point2(.4f, .8f)),
        maskAreaNorm = .06f, centerNorm = Point2(.5f, .65f), bottomContactNorm = Point2(.5f, .7f),
        source = DepthSource.ARCORE_RAW_DEPTH, zDistanceM = 2f, rayDistanceM = null,
        groundDistanceM = null, riskDistanceM = 2f, validSampleCount = 80, validSampleRatio = .6f,
        depthMedianM = 2f, depthP20M = 1.9f, depthIqrM = .2f,
        trend = Trend.STABLE, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
        confidence = DepthConfidenceBreakdown(sourceQuality = .95f, sampleQuality = .9f,
            depthQuality = .9f, detectionQuality = .9f, trackingQuality = 1f,
            motionQuality = 1f, freshnessQuality = 1f, corridorQuality = 1f, hardGate = 1f),
        userFacing = UserFacingDepth(stepsAhead = 3, messageLevel = MessageLevel.NONE, message = null),
        trackAgeFrames = 4, trackStableMs = 900L, sourceValidUntilElapsedRealtimeMs = captureMs + 400L,
        contactDepth = ContactDepthEstimate(distanceM = 2f, spreadM = .05f, validSampleCount = 25),
    )

    private companion object {
        const val CAPTURE_MS = 1_000L
        const val SOURCE_FRAME_NS = 7_000_000_000L
        const val TARGET_FRAME_NS = 7_100_000_000L
        const val SOURCE_IMAGE_NS = 9_000_000_000L
        const val TARGET_IMAGE_NS = 9_100_000_000L
        const val TRACK_ID = "normal"
    }
}
