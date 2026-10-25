package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.DepthAvailability
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S12-18: a near measured dot may inform; neither a route nor a movement instruction follows. */
class TactileDotReferenceNoticeRegressionTest {
    @Test fun trustedNearDotProducesAReferenceEvenWithoutRouteGeometryAndKeepsMovementSilent() {
        val result = evaluate()
        val notice = requireNotNull(result.dotReferenceNotice)
        assertEquals("dot", notice.trackId)
        assertEquals(FRAME_NS, notice.captureFrameId)
        assertEquals(CAPTURE_MS, notice.capturedAtElapsedRealtimeMs)
        // Decision age 150 ms leaves 250 ms of the existing 400 ms detector-source budget.
        assertEquals(CAPTURE_MS + 300L, notice.validUntilElapsedRealtimeMs)
        assertEquals(TACTILE_DOT_REFERENCE_NOTICE_KO, notice.message)
        assertTrue(notice.isValidAt(CAPTURE_MS + 50L))
        assertFalse(notice.message.contains("횡단"))
        assertFalse(notice.message.contains("계단"))
        assertFalse(notice.message.contains("승강장"))
        assertFalse(notice.message.contains("미터"))
        assertFalse(notice.message.contains("몇 보"))
        assertNoMovement(result)
    }

    @Test fun damageAndOtherClassesDoNotBecomeADotReference() {
        for (name in listOf("damaged_dot_tactile_paving", "dot_tactile_block", "linear_tactile_paving", "person")) {
            assertNull(name, evaluate(output().copy(className = name)).dotReferenceNotice)
        }
    }

    @Test fun pseudoPredictedAndSpatiallyLimitedDepthDoNotProveANearDot() {
        val base = output()
        val rejected = listOf(
            base.copy(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH, depthAvailability = DepthAvailability.UNAVAILABLE),
            base.copy(depthAvailability = DepthAvailability.PREDICTED),
            base.copy(spatialCoverageLimited = true),
            base.copy(riskDistanceM = null),
            base.copy(riskDistanceM = Float.NaN),
            base.copy(riskDistanceM = 2.51f),
        )
        rejected.forEach { assertNull(evaluate(it).dotReferenceNotice) }
    }

    @Test fun weakOrUnstableRecognitionDoesNotProduceAReference() {
        val base = output()
        val rejected = listOf(
            base.copy(detectionConfidence = .1f),
            base.copy(detectionConfidence = 1.1f),
            base.copy(confidence = base.confidence.copy(hardGate = 0f)),
            base.copy(trackAgeFrames = 2),
            base.copy(trackStableMs = 699L),
            base.copy(validSampleCount = 0),
            base.copy(validSampleRatio = Float.NaN),
        )
        rejected.forEach { assertNull(evaluate(it).dotReferenceNotice) }
    }

    @Test fun missingExpiredOrDifferentCaptureEvidenceDoesNotProduceAReference() {
        val base = output()
        assertNull(evaluate(base.copy(sourceValidUntilElapsedRealtimeMs = null)).dotReferenceNotice)
        assertNull(evaluate(base.copy(sourceValidUntilElapsedRealtimeMs = CAPTURE_MS + 49L)).dotReferenceNotice)
        assertNull(evaluate(base.copy(frameId = FRAME_NS + 1L)).dotReferenceNotice)
        assertNull(evaluate(base, null).dotReferenceNotice)
        assertNull(evaluate(base, context().copy(cameraFrame = null)).dotReferenceNotice)
        assertNull(evaluate(base, context().copy(decisionElapsedRealtimeMs = null)).dotReferenceNotice)
        assertNull(evaluate(base, context().copy(decisionElapsedRealtimeMs = CAPTURE_MS + 101L)).dotReferenceNotice)
    }

    @Test fun invalidImageGeometryDoesNotBecomeAReference() {
        val base = output()
        for (box in listOf(RectNorm(Float.NaN, .5f, .2f, .3f), RectNorm(.4f, .5f, 0f, .3f))) {
            assertNull(evaluate(base.copy(bboxNorm = box)).dotReferenceNotice)
        }
    }

    @Test fun immutableNoticeExpiresWithoutRenewingItsCaptureOrSourceLease() {
        val notice = TactileDotReferenceNotice("dot", FRAME_NS, CAPTURE_MS, CAPTURE_MS + 400L)
        assertFalse(notice.isValidAt(CAPTURE_MS - 1L))
        assertTrue(notice.isValidAt(CAPTURE_MS + 400L))
        assertFalse(notice.isValidAt(CAPTURE_MS + 401L))
        assertFalse(notice.copy(capturedAtElapsedRealtimeMs = -1L).isValidAt(CAPTURE_MS))
        assertFalse(notice.copy(validUntilElapsedRealtimeMs = CAPTURE_MS - 1L).isValidAt(CAPTURE_MS))
        assertFalse(notice.copy(trackId = " ").isValidAt(CAPTURE_MS))
        assertFalse(notice.copy(captureFrameId = 0L).isValidAt(CAPTURE_MS))
        assertEquals(CAPTURE_MS + 400L, notice.validUntilElapsedRealtimeMs)
    }

    @Test fun existingNearRecognitionAndRawSupportBoundariesRemainInclusive() {
        val boundary = output().copy(riskDistanceM = 2.5f, detectionConfidence = .55f,
            trackAgeFrames = 3, trackStableMs = 700L, validSampleCount = 30, validSampleRatio = .25f)
        assertTrue(requireNotNull(evaluate(boundary).dotReferenceNotice).isValidAt(CAPTURE_MS + 50L))
        assertNull(evaluate(boundary.copy(validSampleCount = 29)).dotReferenceNotice)
        assertNull(evaluate(boundary.copy(validSampleRatio = .24f)).dotReferenceNotice)
    }

    @Test fun fullDepthKeepsItsOwnExistingFiftySampleGate() {
        val full = output().copy(source = DepthSource.ARCORE_FULL_DEPTH, validSampleCount = 50,
            confidence = output().confidence.copy(sourceQuality = .8f))
        assertTrue(requireNotNull(evaluate(full).dotReferenceNotice).isValidAt(CAPTURE_MS + 50L))
        assertNull(evaluate(full.copy(validSampleCount = 49)).dotReferenceNotice)
    }

    @Test fun aShorterExistingSourceLeaseRemainsTheDeadline() {
        val short = output().copy(sourceValidUntilElapsedRealtimeMs = CAPTURE_MS + 75L)
        val notice = requireNotNull(evaluate(short).dotReferenceNotice)
        assertEquals(CAPTURE_MS + 75L, notice.validUntilElapsedRealtimeMs)
        assertTrue(notice.isValidAt(CAPTURE_MS + 75L))
        assertFalse(notice.isValidAt(CAPTURE_MS + 76L))
    }

    @Test fun existingDetectorSourceAgeCannotBeRenewedByReferenceCreation() {
        assertNull(evaluate(context = context().copy(detectionAgeMs = null)).dotReferenceNotice)
        assertNull(evaluate(context = context().copy(detectionAgeMs = 401L)).dotReferenceNotice)
        val decision = CAPTURE_MS + 50L
        val notice = requireNotNull(evaluate(context = context().copy(detectionAgeMs = 400L)).dotReferenceNotice)
        assertEquals(decision, notice.validUntilElapsedRealtimeMs)
        assertTrue(notice.isValidAt(decision))
        assertFalse(notice.isValidAt(decision + 1L))
    }

    @Test fun cameraGateIsSharedWithProjectionWithoutRequiringRouteOrOrientation() {
        val base = context()
        assertTrue(requireNotNull(evaluate(context = base.copy(decisionElapsedRealtimeMs = CAPTURE_MS + 100L))
            .dotReferenceNotice).isValidAt(CAPTURE_MS + 100L))
        assertNull(evaluate(context = base.copy(decisionElapsedRealtimeMs = CAPTURE_MS + 101L)).dotReferenceNotice)
        assertNull(evaluate(context = base.copy(cameraFrame = requireNotNull(base.cameraFrame).copy(tracking = false)))
            .dotReferenceNotice)
        assertNull(evaluate(context = base.copy(cameraFrame = requireNotNull(base.cameraFrame).copy(frameId = FRAME_NS + 1L)))
            .dotReferenceNotice)
    }

    private fun evaluate(output: TrackedObjectDepth = output(), context: TactileProjectionContext? = context()) =
        AndroidTactileRouteGuidance().apply(listOf(output), context, true, true, activeRouteId = null)

    private fun assertNoMovement(result: TactileRouteGuidanceResult) {
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.observation)
        assertNull(result.decision.steering)
        assertNull(result.decision.instruction)
        val output = result.outputs.single()
        assertEquals(MessageLevel.NONE, output.userFacing.messageLevel)
        assertNull(output.userFacing.message)
        assertNull(output.userFacing.stepsAhead)
    }

    private fun context() = TactileProjectionContext(
        captureFrameId = FRAME_NS, expectedRouteId = null, routeProjection = null, trustedLocation = null,
        orientation = null, magneticDeclinationDeg = null,
        cameraFrame = TactileCameraFrame(FRAME_NS, RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            TactileCameraIntrinsics(1_000, 1_000, 500f, 500f, 500f, 500f), CAPTURE_MS, true),
        detectionAgeMs = 150L, nowElapsedRealtimeMs = CAPTURE_MS, decisionElapsedRealtimeMs = CAPTURE_MS + 50L,
    )

    private fun output() = TrackedObjectDepth(
        frameId = FRAME_NS, timestampMs = FRAME_NS / 1_000_000L, trackId = "dot",
        className = "dot_tactile_paving", detectionConfidence = .9f,
        bboxNorm = RectNorm(.4f, .5f, .2f, .3f),
        polygonNorm = listOf(Point2(.4f, .5f), Point2(.6f, .5f), Point2(.6f, .8f), Point2(.4f, .8f)),
        maskAreaNorm = .06f, centerNorm = Point2(.5f, .65f), bottomContactNorm = Point2(.5f, .7f),
        source = DepthSource.ARCORE_RAW_DEPTH, zDistanceM = 2f, rayDistanceM = null,
        groundDistanceM = null, riskDistanceM = 2f, validSampleCount = 80, validSampleRatio = .6f,
        depthMedianM = 2f, depthP20M = 1.9f, depthIqrM = .2f,
        trend = Trend.STABLE, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
        confidence = DepthConfidenceBreakdown(.95f, .9f, .9f, .9f, 1f, 1f, 1f, 1f, 1f),
        userFacing = UserFacingDepth(null, MessageLevel.NONE, null),
        trackAgeFrames = 4, trackStableMs = 900L, sourceValidUntilElapsedRealtimeMs = CAPTURE_MS + 400L,
    )

    private companion object {
        const val FRAME_NS = 7_100_000_000L
        const val CAPTURE_MS = 1_000L
    }
}
