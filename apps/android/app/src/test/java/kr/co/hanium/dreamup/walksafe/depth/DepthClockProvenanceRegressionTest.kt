package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frame, CPU Image and elapsed clocks intentionally have different origins (S11-34). */
class DepthClockProvenanceRegressionTest {
    private val detection = DetectionCandidate("person", .9f, RectNorm(.35f, .2f, .3f, .5f))
    private val mapper = LetterboxCoordinateMapper(
        ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100),
    )

    @Test
    fun freshRawUsesItsVerifiedFrameCaptureInsteadOfTheImageClockOrigin() {
        val pipeline = ObjectDepthRuntimePipeline()
        val target = key(1_040L)
        val output = process(pipeline, key(1_000L), target).single()

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(2f, output.riskDistanceM!!, .001f)
        assertEquals(false, output.depthReprojected)
        assertEquals(1_040L, output.depthObservedAtMs)
        assertTrue(DepthAgeBudget.admits(DepthUse.PROXIMITY_DISPLAY, output, target.frameId / 1_000_000L))
        assertEquals(51_000L, output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(51_800L, output.sourceValidUntilElapsedRealtimeMs)
    }

    @Test
    fun freshFullUsesItsVerifiedFrameCaptureInsteadOfTheImageClockOrigin() {
        val pipeline = ObjectDepthRuntimePipeline()
        val target = key(1_040L)
        val snapshot = depth(target).copy(
            rawDepth = null, rawConfidence = null, rawDepthTimestampNs = null, rawConfidenceTimestampNs = null,
            fullDepth = DepthImage16(100, 100, IntArray(10_000) { 2_000 }),
            fullDepthTimestampNs = target.cameraTimestampNs,
        )
        val output = process(pipeline, key(1_000L), target, snapshot).single()

        assertEquals(DepthSource.ARCORE_FULL_DEPTH, output.source)
        assertEquals(2f, output.riskDistanceM!!, .001f)
        assertEquals(false, output.depthReprojected)
        assertEquals(1_040L, output.depthObservedAtMs)
        assertTrue(DepthAgeBudget.admits(DepthUse.PROXIMITY_DISPLAY, output, target.frameId / 1_000_000L))
    }

    @Test
    fun knownReprojectedRawKeepsTheFirstCaptureAndCannotRenewItsElapsedLease() {
        val pipeline = ObjectDepthRuntimePipeline()
        (0..11).forEach { index ->
            process(pipeline, key(1_000L + (index / 5) * 500L), key(1_040L + index * 100L))
        }
        val original = key(1_940L)
        val target = key(2_240L)
        val snapshot = depth(target).copy(
            rawDepthTimestampNs = original.cameraTimestampNs,
            rawConfidenceTimestampNs = original.cameraTimestampNs,
        )
        val output = process(pipeline, key(2_000L), target, snapshot).single()

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(true, output.depthReprojected)
        assertEquals(1_940L, output.depthObservedAtMs)
        assertEquals(51_940L, output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(52_740L, output.sourceValidUntilElapsedRealtimeMs)
        val candidate = requireNotNull(output.copy(userFacing = UserFacingDepth(null, MessageLevel.STOP, "stop"))
            .toFeedbackCandidate())
        assertEquals(51_940L, candidate.observedAtElapsedRealtimeMs)
        assertEquals(52_740L, candidate.validUntilMs)
        assertFalse(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, output, 2_741L))
    }

    @Test
    fun unseenReprojectedRawDoesNotInventACaptureOrANewSourceLease() {
        val pipeline = ObjectDepthRuntimePipeline()
        (0..11).forEach { index ->
            process(pipeline, key(1_000L + (index / 5) * 500L), key(1_040L + index * 100L))
        }
        val target = key(2_240L)
        // This Image ID was never observed with its matching CPU capture in this pipeline.
        val unseenImageTimestamp = key(2_100L).cameraTimestampNs
        val snapshot = depth(target).copy(
            rawDepthTimestampNs = unseenImageTimestamp,
            rawConfidenceTimestampNs = unseenImageTimestamp,
        )
        val output = process(pipeline, key(2_000L), target, snapshot).single()

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(true, output.depthReprojected)
        assertNull(output.depthObservedAtMs)
        assertNull(output.sourceObservedAtElapsedRealtimeMs)
        assertFalse(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, output, output.timestampMs))
        assertTrue(requireNotNull(output.sourceValidUntilElapsedRealtimeMs) < target.capturedAtElapsedRealtimeMs)
    }

    @Test
    fun aStepLengthChangeKeepsTheOriginalDepthCaptureTimes() {
        val pipeline = ObjectDepthRuntimePipeline()
        (0..11).forEach { index ->
            process(pipeline, key(1_000L + (index / 5) * 500L), key(1_040L + index * 100L))
        }
        pipeline.setUserStepLength(.8f)
        val target = key(2_240L)
        val original = key(1_940L)
        val output = process(pipeline, key(2_000L), target, depth(target).copy(
            rawDepthTimestampNs = original.cameraTimestampNs,
            rawConfidenceTimestampNs = original.cameraTimestampNs,
        )).single()
        assertEquals(1_940L, output.depthObservedAtMs)
        assertEquals(51_940L, output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(52_740L, output.sourceValidUntilElapsedRealtimeMs)
    }

    @Test
    fun aLaterReceiptCannotTranslateTheSameOldDepthCaptureAgain() {
        val pipeline = ObjectDepthRuntimePipeline()
        (0..11).forEach { index ->
            process(pipeline, key(1_000L + (index / 5) * 500L), key(1_040L + index * 100L))
        }
        val original = key(1_940L)
        for ((frame, additionalDelay) in listOf(2_240L to 80L, 2_340L to 150L)) {
            val target = key(frame).let { it.copy(capturedAtElapsedRealtimeMs = it.capturedAtElapsedRealtimeMs + additionalDelay) }
            val output = process(pipeline, key(2_000L), target, depth(target).copy(
                rawDepthTimestampNs = original.cameraTimestampNs,
                rawConfidenceTimestampNs = original.cameraTimestampNs,
            )).single()
            assertEquals(1_940L, output.depthObservedAtMs)
            assertEquals(51_940L, output.sourceObservedAtElapsedRealtimeMs)
            assertEquals(52_740L, output.sourceValidUntilElapsedRealtimeMs)
        }
    }

    @Test
    fun sameCaptureClockOffsetStillSeedsAShortGapWithoutInventingMetricOrTtc() {
        val pipeline = ObjectDepthRuntimePipeline()
        (0..12).forEach { index ->
            process(pipeline, key(1_000L + (index / 5) * 500L), key(1_040L + index * 100L))
        }
        val target = key(2_340L)
        val gap = process(pipeline, key(2_000L), target, depth(target).copy(
            rawDepth = DepthImage16(100, 100, IntArray(10_000)),
        )).single()
        assertEquals(DepthAvailability.PREDICTED, gap.depthAvailability)
        assertEquals(2_240L, gap.depthObservedAtMs)
        assertEquals(100L, requireNotNull(gap.prediction).predictionAgeMs)
        assertNull(gap.riskDistanceM)
        assertNull(gap.timeToCollisionMs)
        assertFalse(gap.source.metric)
    }

    @Test
    fun evictedOrRepeatedImageIdsCannotSeedANewCaptureTime() {
        val clock = DepthCaptureClock()
        for (at in 1L..65L) {
            val capture = key(at)
            val times = clock.observe(depth(capture), at, capture.capturedAtElapsedRealtimeMs)
            assertEquals(at, requireNotNull(times.raw).frameTimestampMs)
        }
        val current = key(1_000L)
        val repeatedNewest = key(65L).cameraTimestampNs
        val newest = clock.observe(depth(current).copy(
            rawDepthTimestampNs = repeatedNewest, rawConfidenceTimestampNs = repeatedNewest,
            cameraImageTimestampNs = repeatedNewest,
        ), 1_000L, current.capturedAtElapsedRealtimeMs)
        assertEquals(65L, requireNotNull(newest.raw).frameTimestampMs)
        assertEquals(50_065L, requireNotNull(newest.raw).capturedAtElapsedRealtimeMs)
        val evicted = key(1L).cameraTimestampNs
        assertNull(clock.observe(depth(current).copy(
            rawDepthTimestampNs = evicted, rawConfidenceTimestampNs = evicted,
            cameraImageTimestampNs = evicted,
        ), 1_000L, current.capturedAtElapsedRealtimeMs).raw)
    }

    @Test
    fun aMissingFirstElapsedReceiptCannotBeFilledByALaterCapture() {
        val clock = DepthCaptureClock()
        val original = key(1_040L)
        assertNull(requireNotNull(clock.observe(depth(original), 1_040L, null).raw).capturedAtElapsedRealtimeMs)
        val current = key(1_140L)
        val later = clock.observe(depth(current).copy(
            rawDepthTimestampNs = original.cameraTimestampNs, rawConfidenceTimestampNs = original.cameraTimestampNs,
        ), 1_140L, current.capturedAtElapsedRealtimeMs)
        assertEquals(1_040L, requireNotNull(later.raw).frameTimestampMs)
        assertNull(requireNotNull(later.raw).capturedAtElapsedRealtimeMs)
    }

    @Test
    fun aRawPairMismatchOrAnotherFrameCannotEstablishCaptureAttribution() {
        val clock = DepthCaptureClock()
        val capture = key(1_040L)
        assertNull(clock.observe(depth(capture).copy(
            rawConfidenceTimestampNs = capture.cameraTimestampNs - 1L,
        ), 1_040L, capture.capturedAtElapsedRealtimeMs).raw)
        assertNull(clock.observe(depth(capture), 1_041L, capture.capturedAtElapsedRealtimeMs).raw)
        assertEquals(1_040L, requireNotNull(clock.observe(depth(capture), 1_040L,
            capture.capturedAtElapsedRealtimeMs).raw).frameTimestampMs)
    }

    @Test
    fun legacyCaptureKeepsItsFrozenElapsedAndIssuesItsOwnExpiry() {
        val pipeline = ObjectDepthRuntimePipeline()
        val target = key(1_040L)
        val output = legacyProcess(pipeline, target).single()

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(1_040L, output.depthObservedAtMs)
        assertEquals(51_040L, output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(51_840L, output.sourceValidUntilElapsedRealtimeMs)
    }

    @Test
    fun legacyDelayedReceiptCannotRenewTheFirstDepthLease() {
        val pipeline = ObjectDepthRuntimePipeline()
        (0..11).forEach { index -> legacyProcess(pipeline, key(1_040L + index * 100L)) }
        val original = key(1_940L)
        val target = key(2_240L).let { it.copy(capturedAtElapsedRealtimeMs = it.capturedAtElapsedRealtimeMs + 80L) }
        val output = legacyProcess(pipeline, target, depth(target).copy(
            rawDepthTimestampNs = original.cameraTimestampNs,
            rawConfidenceTimestampNs = original.cameraTimestampNs,
        )).single()

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(true, output.depthReprojected)
        assertEquals(1_940L, output.depthObservedAtMs)
        assertEquals(51_940L, output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(52_740L, output.sourceValidUntilElapsedRealtimeMs)
    }

    @Test
    fun legacyMissingFirstElapsedCannotAcquireALeaseFromALaterReceipt() {
        val pipeline = ObjectDepthRuntimePipeline()
        (0..11).forEach { index ->
            legacyProcess(pipeline, key(1_040L + index * 100L), capturedAtElapsedRealtimeMs = null)
        }
        val original = key(1_940L)
        val target = key(2_240L)
        val output = legacyProcess(pipeline, target, depth(target).copy(
            rawDepthTimestampNs = original.cameraTimestampNs,
            rawConfidenceTimestampNs = original.cameraTimestampNs,
        )).single()

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(1_940L, output.depthObservedAtMs)
        assertNull(output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(Long.MIN_VALUE, output.sourceValidUntilElapsedRealtimeMs)
    }

    private fun legacyProcess(
        pipeline: ObjectDepthRuntimePipeline,
        target: VisualFrameKey,
        snapshot: DepthFrameSnapshot = depth(target),
        capturedAtElapsedRealtimeMs: Long? = target.capturedAtElapsedRealtimeMs,
    ) = pipeline.process(snapshot, target.frameId, target.frameId / 1_000_000L,
        listOf(detection), mapper = mapper, mapperFrameId = target.frameId,
        capturedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs)

    private fun key(atMs: Long) = VisualFrameKey(
        7L, atMs * 1_000_000L, 10_000_000_000_000L + atMs * 1_000_000L,
        atMs + 50_000L, 3L,
    )

    private fun observation(source: VisualFrameKey, target: VisualFrameKey) = requireNotNull(
        CurrentTrackedFrameObservation.fromTrackingResult(
            VisualTrackingResult(source, target, listOf(VisualTrackingObservation(
                sourceIndex = 0, detectorSourceKey = source, trackedTargetKey = target,
                status = VisualTrackingStatus.TRACKED, geometry = detection, trackingQuality = .95f,
                failure = null, translationNorm = Point2(0f, 0f), originalFeatureCount = 8,
                survivingFeatureCount = 8, residualPx = 0f, polygonConstrained = false,
            )), VisualTrackingMetrics(0L, 1, 0L, false, 2, 0)),
            listOf(detection), true, target.capturedAtElapsedRealtimeMs,
        ),
    )

    private fun depth(target: VisualFrameKey) = DepthFrameSnapshot(
        frameTimestampNs = target.frameId,
        rawDepth = DepthImage16(100, 100, IntArray(10_000) { 2_000 }),
        rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
        fullDepth = null, rawDepthTimestampNs = target.cameraTimestampNs,
        rawConfidenceTimestampNs = target.cameraTimestampNs, cameraImageTimestampNs = target.cameraTimestampNs,
    )

    private fun process(
        pipeline: ObjectDepthRuntimePipeline,
        source: VisualFrameKey,
        target: VisualFrameKey,
        snapshot: DepthFrameSnapshot = depth(target),
    ) = pipeline.processTrackedObservation(snapshot, observation(source, target), mapper, target.frameId,
        target.capturedAtElapsedRealtimeMs)
}
