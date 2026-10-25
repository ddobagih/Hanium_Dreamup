package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.ConfidenceImage8
import kr.co.hanium.dreamup.walksafe.depth.CurrentTrackedFrameObservation
import kr.co.hanium.dreamup.walksafe.depth.DepthAvailability
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DepthImage16
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingFailure
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual current RGB/depth pipeline: healthy image depth outside the crosswalk, zero ROI samples. */
class CrosswalkDepthlessReferenceRegressionTest {
    private val detection = DetectionCandidate("crosswalk", .9f, RectNorm(.4f, .4f, .2f, .4f))
    private val progress = TactileActiveRouteProgress(1L, 0)

    @Test fun currentPipelineKeepsTheExactSourceIndexEvenWhenNoMetricSamplesExist() {
        val frames = stableFrames()
        val output = frames.last().output
        assertEquals(1, frames.map { it.output.trackId }.distinct().size)
        assertEquals(3, output.trackAgeFrames)
        assertTrue(output.trackStableMs >= 700L)
        assertNoMetric(output)
        assertEquals("The pipeline must carry its actual assignment, without a box-equality guess", 0,
            output.sourceDetectionIndex)
    }

    @Test fun stableCurrentCrosswalkWithZeroRoiDepthStillProducesADistanceFreeReference() {
        val frame = stableFrames().last()
        assertNoMetric(frame.output)
        val result = evaluate(frame)
        val notice = requireNotNull(result.crosswalkReferenceNotice)
        assertEquals(frame.output.trackId, notice.trackId)
        assertEquals(frame.observation.targetKey.frameId, notice.captureFrameId)
        assertEquals(frame.observation.targetKey.capturedAtElapsedRealtimeMs, notice.capturedAtElapsedRealtimeMs)
        assertEquals("route", notice.routeId)
        assertEquals(1L, notice.routeVersion)
        assertEquals(0, notice.routeSegmentIndex)
        assertEquals(CROSSWALK_REFERENCE_NOTICE_KO, notice.message)
        assertTrue(!notice.message.contains("미터") && !notice.message.contains("몇 보"))
        assertNoMovement(result)
    }

    @Test fun theCurrentCoordinatorCarriesTheAdmittedRgbObservationToTheReferenceProducer() {
        val pipeline = ObjectDepthRuntimePipeline()
        val coordinator = AndroidTactileFrameCoordinator(
            AndroidTactileRouteGuidance(), WalkSafeFeedbackPolicy(), TactileFrameFeedbackActuator {},
        )
        var last: TactileRouteGuidanceResult? = null
        for (atMs in listOf(1_000L, 1_400L, 1_800L)) {
            val source = key(atMs)
            val target = key(atMs + 40L)
            val current = evidence(target)
            val snapshot = DetectionSnapshot(
                detections = listOf(detection), identity = AndroidDetectionSnapshotFrameIdentity(source.frameId, atMs),
                capturedAtMs = source.capturedAtElapsedRealtimeMs, startedAtMs = source.capturedAtElapsedRealtimeMs,
                completedAtMs = source.capturedAtElapsedRealtimeMs, imageWidth = 100, imageHeight = 100,
                detectDurationMs = 0L, detectorTiming = null, partial = false,
                capturedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs, frameEvidence = evidence(source),
            )
            val admitted = requireNotNull(CurrentTrackedFrameEvidence.fromTrackingResult(
                snapshot, tracking(source, target), current, target.capturedAtElapsedRealtimeMs,
            ))
            last = coordinator.processTrackedObservation(
                admitted, target.capturedAtElapsedRealtimeMs, true, true, "route", progress,
            ) { observation, capture -> pipeline.processTrackedObservation(
                capture.depthSnapshot, observation, requireNotNull(capture.depthMapper), capture.frameId,
                target.capturedAtElapsedRealtimeMs,
            ) }.guidance
        }
        requireNotNull(requireNotNull(last).crosswalkReferenceNotice)
        assertNoMetric(requireNotNull(last).outputs.single())
        assertNoMovement(requireNotNull(last))
    }

    @Test fun noCurrentObservationOrAnUnconfirmedTrackCannotProduceAReference() {
        val frame = stableFrames().last()
        assertNull(evaluate(frame, observation = null).crosswalkReferenceNotice)
        assertNull(evaluate(frame, output = frame.output.copy(trackAgeFrames = 2)).crosswalkReferenceNotice)
        assertNull(evaluate(frame, output = frame.output.copy(trackStableMs = 699L)).crosswalkReferenceNotice)
    }

    @Test fun aDifferentRouteRevisionOrSegmentCannotUseTheCapturedReference() {
        val frame = stableFrames().last()
        assertNull(evaluate(frame, activeRouteId = "other").crosswalkReferenceNotice)
        assertNull(evaluate(frame, routeProgress = progress.copy(routeVersion = 2L)).crosswalkReferenceNotice)
        assertNull(evaluate(frame, routeProgress = progress.copy(segmentIndex = 1)).crosswalkReferenceNotice)
        assertNull(evaluate(frame, routeProgress = null).crosswalkReferenceNotice)
        assertNull(evaluate(frame, context = context(frame.observation.targetKey).copy(routeProjection = null)).crosswalkReferenceNotice)
    }

    @Test fun missingOrStaleLocationAndCameraContextCannotProduceAReference() {
        val frame = stableFrames().last()
        val context = context(frame.observation.targetKey)
        assertNull(evaluate(frame, context = context.copy(trustedLocation = null)).crosswalkReferenceNotice)
        assertNull(evaluate(frame, context = context.copy(trustedLocation = requireNotNull(context.trustedLocation)
            .copy(accuracyM = 5.01f))).crosswalkReferenceNotice)
        assertNull(evaluate(frame, context = context.copy(trustedLocation = requireNotNull(context.trustedLocation)
            .copy(elapsedRealtimeMs = context.nowElapsedRealtimeMs - 2_001L))).crosswalkReferenceNotice)
        assertNull(evaluate(frame, context = context.copy(cameraFrame = null)).crosswalkReferenceNotice)
        assertNull(evaluate(frame, context = context.copy(decisionElapsedRealtimeMs = context.nowElapsedRealtimeMs + 101L))
            .crosswalkReferenceNotice)
    }

    @Test fun anExpiredSourceOrMismatchedFrameCannotProduceAReference() {
        val frame = stableFrames().last()
        assertNull(evaluate(frame, output = frame.output.copy(frameId = frame.output.frameId + 1L)).crosswalkReferenceNotice)
        assertNull(evaluate(frame, context = context(frame.observation.targetKey).copy(detectionAgeMs = 401L))
            .crosswalkReferenceNotice)
    }

    @Test fun anOldMetricLeaseCannotDenyAStillCurrentRgbReference() {
        val frame = stableFrames().last()
        // The original visual capture is still current. A former depth lease is not RGB authority.
        val oldDepthLease = frame.output.copy(sourceValidUntilElapsedRealtimeMs =
            frame.observation.targetKey.capturedAtElapsedRealtimeMs - 1L)
        requireNotNull(evaluate(frame, output = oldDepthLease).crosswalkReferenceNotice)
    }

    @Test fun aMetricCrosswalkInfoUsesOnlyTheReferenceChannelAndNeverTheCommonRiskChannel() {
        val frame = stableFrames().last()
        val metricInfo = frame.output.copy(source = DepthSource.ARCORE_RAW_DEPTH,
            depthAvailability = DepthAvailability.MEASURED, riskDistanceM = 2f,
            userFacing = kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth(null, MessageLevel.INFO,
                CROSSWALK_REFERENCE_NOTICE_KO))
        val result = evaluate(frame, output = metricInfo)
        requireNotNull(result.crosswalkReferenceNotice)
        assertEquals(MessageLevel.NONE, result.outputs.single().userFacing.messageLevel)
    }

    @Test fun exactCurrentTrackedGeometryAndQualityAreRequiredAfterThePositivePathIsAvailable() {
        val frame = stableFrames().last()
        assertNull(evaluate(frame, output = frame.output.copy(sourceDetectionIndex = null)).crosswalkReferenceNotice)
        assertNull(evaluate(frame, output = frame.output.copy(sourceDetectionIndex = 1)).crosswalkReferenceNotice)
        val source = frame.observation.sourceKey
        val target = frame.observation.targetKey
        val lowQuality = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
            tracking(source, target, quality = .64f), listOf(detection), true, target.capturedAtElapsedRealtimeMs))
        val lost = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
            tracking(source, target, lost = true), listOf(detection), true, target.capturedAtElapsedRealtimeMs))
        assertNull(evaluate(frame, observation = lowQuality).crosswalkReferenceNotice)
        assertNull(evaluate(frame, observation = lost).crosswalkReferenceNotice)
        assertNull(evaluate(frame, output = frame.output.copy(imageQuarterTurns = null)).crosswalkReferenceNotice)
        assertNull(evaluate(frame, output = frame.output.copy(bboxNorm = RectNorm(Float.NaN, .4f, .2f, .4f)))
            .crosswalkReferenceNotice)
    }

    @Test fun theActualSourceReceiptRejectsA401msSourceEvenWhenContextClaimsOnly40ms() {
        val frame = laterFrame(stableFrames().last(), 2_201L)
        assertEquals(401L, frame.observation.targetKey.capturedAtElapsedRealtimeMs -
            frame.observation.sourceKey.capturedAtElapsedRealtimeMs)
        assertEquals(3, frame.output.trackAgeFrames)
        assertNull(evaluate(frame, context = context(frame.observation.targetKey).copy(detectionAgeMs = 40L))
            .crosswalkReferenceNotice)
    }

    @Test fun anotherTargetAndAQueueNeverRenewTheOriginalVisualSourceDeadline() {
        val original = stableFrames().last()
        val firstNotice = requireNotNull(evaluate(original).crosswalkReferenceNotice)
        val later = laterFrame(original, 1_940L)
        val secondNotice = requireNotNull(evaluate(later, context = context(later.observation.targetKey)
            .copy(detectionAgeMs = 140L)).crosswalkReferenceNotice)
        val deadline = original.observation.sourceKey.capturedAtElapsedRealtimeMs + 400L
        assertEquals(deadline, firstNotice.validUntilElapsedRealtimeMs)
        assertEquals(deadline, secondNotice.validUntilElapsedRealtimeMs)
        assertTrue(firstNotice.isValidAt(deadline))
        assertTrue(!firstNotice.isValidAt(deadline + 1L))
        assertTrue(firstNotice.isValidForRoute("route", progress))
        assertTrue(!firstNotice.isValidForRoute("route", progress.copy(routeVersion = 2L)))
        assertTrue(!firstNotice.isValidForRoute("route", progress.copy(segmentIndex = 1)))
        assertTrue(!firstNotice.isValidForRoute("other", progress))
    }

    @Test fun theRemainingCapturedPositionBudgetCanShortenButCannotExtendTheNotice() {
        val frame = stableFrames().last()
        val context = context(frame.observation.targetKey)
        val almostExpiredPosition = context.copy(trustedLocation = requireNotNull(context.trustedLocation)
            .copy(elapsedRealtimeMs = context.nowElapsedRealtimeMs - 1_990L))
        val notice = requireNotNull(evaluate(frame, context = almostExpiredPosition).crosswalkReferenceNotice)
        assertEquals(context.nowElapsedRealtimeMs + 10L, notice.validUntilElapsedRealtimeMs)
        assertTrue(notice.isValidAt(context.nowElapsedRealtimeMs + 10L))
        assertTrue(!notice.isValidAt(context.nowElapsedRealtimeMs + 11L))
    }

    @Test fun existingSourceCameraPositionAndTrackingLimitsKeepTheirInclusiveBoundaries() {
        val frame = stableFrames().last()
        val context = context(frame.observation.targetKey)
        val boundaryContext = context.copy(decisionElapsedRealtimeMs = context.nowElapsedRealtimeMs + 100L,
            detectionAgeMs = 140L, trustedLocation = requireNotNull(context.trustedLocation)
                .copy(accuracyM = 5f, elapsedRealtimeMs = context.nowElapsedRealtimeMs - 1_900L))
        val boundaryTracking = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
            tracking(frame.observation.sourceKey, frame.observation.targetKey, quality = .65f),
            listOf(detection), true, frame.observation.targetKey.capturedAtElapsedRealtimeMs))
        requireNotNull(evaluate(frame, observation = boundaryTracking, context = boundaryContext).crosswalkReferenceNotice)
        val sourceBoundary = laterFrame(stableFrames().last(), 2_200L)
        val sourceNotice = requireNotNull(evaluate(sourceBoundary, context = context(sourceBoundary.observation.targetKey)
            .copy(detectionAgeMs = 400L)).crosswalkReferenceNotice)
        assertEquals(sourceBoundary.observation.targetKey.capturedAtElapsedRealtimeMs, sourceNotice.validUntilElapsedRealtimeMs)
    }

    private data class Frame(val observation: CurrentTrackedFrameObservation, val output: TrackedObjectDepth,
        val pipeline: ObjectDepthRuntimePipeline)

    private fun stableFrames(): List<Frame> {
        val pipeline = ObjectDepthRuntimePipeline()
        return listOf(1_000L, 1_400L, 1_800L).map { atMs ->
            val target = key(atMs + 40L)
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
                tracking(key(atMs), target), listOf(detection), true, target.capturedAtElapsedRealtimeMs,
            ))
            val evidence = evidence(target)
            Frame(observation, pipeline.processTrackedObservation(evidence.depthSnapshot, observation,
                requireNotNull(evidence.depthMapper), target.frameId, target.capturedAtElapsedRealtimeMs).single(), pipeline)
        }
    }

    private fun laterFrame(frame: Frame, targetAtMs: Long): Frame {
        val target = key(targetAtMs)
        val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
            tracking(frame.observation.sourceKey, target), listOf(detection), true, target.capturedAtElapsedRealtimeMs))
        val evidence = evidence(target)
        return Frame(observation, frame.pipeline.processTrackedObservation(evidence.depthSnapshot, observation,
            requireNotNull(evidence.depthMapper), target.frameId, target.capturedAtElapsedRealtimeMs).single(), frame.pipeline)
    }

    private fun evaluate(
        frame: Frame, output: TrackedObjectDepth = frame.output,
        observation: CurrentTrackedFrameObservation? = frame.observation,
        context: TactileProjectionContext = context(frame.observation.targetKey),
        activeRouteId: String? = "route", routeProgress: TactileActiveRouteProgress? = progress,
    ) = AndroidTactileRouteGuidance().apply(listOf(output), context, true, true,
        activeRouteId, routeProgress, observation)

    private fun assertNoMetric(output: TrackedObjectDepth) {
        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, output.source)
        assertEquals(DepthAvailability.UNAVAILABLE, output.depthAvailability)
        assertEquals(0, output.validSampleCount)
        assertNull(output.riskDistanceM)
        assertNull(output.timeToCollisionMs)
        assertNull(output.prediction)
        assertEquals(MessageLevel.NONE, output.userFacing.messageLevel)
    }

    private fun assertNoMovement(result: TactileRouteGuidanceResult) {
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.steering)
        assertNull(result.decision.instruction)
        assertNull(result.observation)
        assertEquals(MessageLevel.NONE, result.outputs.single().userFacing.messageLevel)
        assertNull(result.outputs.single().userFacing.stepsAhead)
    }

    private fun key(atMs: Long) = VisualFrameKey(7L, atMs * 1_000_000L,
        atMs * 1_000_000L + 400_000L, atMs + 50_000L, 3L)

    private fun tracking(source: VisualFrameKey, target: VisualFrameKey, quality: Float = .95f, lost: Boolean = false) =
        VisualTrackingResult(source, target, listOf(VisualTrackingObservation(0, source, target,
            if (lost) VisualTrackingStatus.LOST else VisualTrackingStatus.TRACKED, detection.takeUnless { lost },
            if (lost) 0f else quality, VisualTrackingFailure.AMBIGUOUS_PATCH.takeIf { lost },
            Point2(0f, 0f), 8, 8, 0f, false)), VisualTrackingMetrics(0L, 1, 0L, false, 2, 0))

    private fun context(key: VisualFrameKey) = TactileProjectionContext(
        key.frameId, "route", ActiveRouteProjection("route", 0, RoutePoint(37.0, 127.0),
            RoutePoint(37.001, 127.0), 0.0, 0f, 1L),
        TrustedLocation(37.0, 127.0, 2f, key.capturedAtElapsedRealtimeMs), null, null,
        TactileCameraFrame(key.frameId, RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            TactileCameraIntrinsics(100, 100, 70f, 70f, 50f, 50f), key.capturedAtElapsedRealtimeMs, true),
        40L, key.capturedAtElapsedRealtimeMs, key.capturedAtElapsedRealtimeMs,
    )

    private fun evidence(key: VisualFrameKey): DetectionFrameEvidence {
        val depth = DepthFrameSnapshot(key.frameId,
            DepthImage16(100, 100, IntArray(10_000) { pixel ->
                if (pixel % 100 in 35..65 && pixel / 100 in 35..85) 0 else 2_000
            }), ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
            DepthImage16(100, 100, IntArray(10_000) { pixel ->
                if (pixel % 100 in 35..65 && pixel / 100 in 35..85) 0 else 2_000
            }), key.cameraTimestampNs, fullDepthTimestampNs = key.cameraTimestampNs,
            cameraImageTimestampNs = key.cameraTimestampNs, rawConfidenceTimestampNs = key.cameraTimestampNs)
        val transform = requireNotNull(FrozenImageToDepthTransform.create(key.frameId,
            listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
        return DetectionFrameEvidence(key.frameId, key.frameId / 1_000_000L, depth,
            FrozenImageToTextureCoordinateMapper(key.frameId, transform, ImageSize(100, 100)),
            context(key), MotionContext(), true, true, visualFrameKey = key)
    }
}
