package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameSynthesisController
import kr.co.hanium.dreamup.walksafe.depth.ConfidenceImage8
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DepthImage16
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.LetterboxCoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.ModelInputTransform
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Calls Main's actual synthesis owner; depth fixtures follow DepthClockProvenanceRegressionTest. */
class MainActivityLegacyFeedbackClockBindingTest {
    private val detection = DetectionCandidate("person", .9f, RectNorm(.35f, .2f, .3f, .5f))
    private val mapper = LetterboxCoordinateMapper(
        ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100),
    )

    @Test
    fun delayedCaptureCannotRenewOriginalMetricExpiryWhenOptionalExpiryIsAbsent() {
        val output = reprojectedMetric().copy(sourceValidUntilElapsedRealtimeMs = null)
        assertEquals(ORIGINAL_FRAME_MS, output.depthObservedAtMs)
        assertEquals(ORIGINAL_ELAPSED_MS, output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(SOURCE_MAX_AGE_MS, output.sourceMaxAgeMs)

        val bound = bind(MainActivity(), output, CURRENT_FRAME_MS, DELAYED_CAPTURE_ELAPSED_MS,
            DELAYED_CAPTURE_ELAPSED_MS)

        assertEquals(ORIGINAL_EXPIRY_MS, bound.sourceValidUntilElapsedRealtimeMs)
        assertEquals(ORIGINAL_ELAPSED_MS, bound.sourceObservedAtElapsedRealtimeMs)
        assertEquals(SOURCE_MAX_AGE_MS, bound.sourceMaxAgeMs)
        assertNull(output.sourceValidUntilElapsedRealtimeMs)
    }

    @Test
    fun missingOriginalElapsedCannotAcquireMetricLeaseFromLaterCaptureReceipt() {
        val output = reprojectedMetric(originalElapsedKnown = false)
            .copy(sourceValidUntilElapsedRealtimeMs = null)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(ORIGINAL_FRAME_MS, output.depthObservedAtMs)
        assertNull(output.sourceObservedAtElapsedRealtimeMs)

        val bound = bind(MainActivity(), output, CURRENT_FRAME_MS, DELAYED_CAPTURE_ELAPSED_MS,
            DELAYED_CAPTURE_ELAPSED_MS)

        assertEquals(Long.MIN_VALUE, bound.sourceValidUntilElapsedRealtimeMs)
        assertNull(bound.sourceObservedAtElapsedRealtimeMs)
        assertNull(bound.sourceMaxAgeMs)
    }

    @Test
    fun currentIndependentCaptureKeepsItsKnownObservationAndOriginalExpiry() {
        val output = process(ObjectDepthRuntimePipeline(), ORIGINAL_FRAME_MS, ORIGINAL_ELAPSED_MS)
            .copy(sourceValidUntilElapsedRealtimeMs = null)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(ORIGINAL_FRAME_MS, output.depthObservedAtMs)
        assertEquals(ORIGINAL_ELAPSED_MS, output.sourceObservedAtElapsedRealtimeMs)

        val bound = bind(MainActivity(), output, ORIGINAL_FRAME_MS, ORIGINAL_ELAPSED_MS,
            ORIGINAL_ELAPSED_MS)

        assertEquals(ORIGINAL_EXPIRY_MS, bound.sourceValidUntilElapsedRealtimeMs)
        assertEquals(ORIGINAL_ELAPSED_MS, bound.sourceObservedAtElapsedRealtimeMs)
        assertEquals(SOURCE_MAX_AGE_MS, bound.sourceMaxAgeMs)
    }

    @Test
    fun earlierExistingExpiryIsNotReplacedByCaptureOrProducerBudget() {
        val output = reprojectedMetric().copy(sourceValidUntilElapsedRealtimeMs = 52_600L)

        val bound = bind(MainActivity(), output, CURRENT_FRAME_MS, DELAYED_CAPTURE_ELAPSED_MS,
            DELAYED_CAPTURE_ELAPSED_MS)

        assertEquals(52_600L, bound.sourceValidUntilElapsedRealtimeMs)
        assertEquals(ORIGINAL_ELAPSED_MS, bound.sourceObservedAtElapsedRealtimeMs)
        assertEquals(SOURCE_MAX_AGE_MS, bound.sourceMaxAgeMs)
    }

    @Test
    fun processingClockChangesCannotExtendAnAlreadyIssuedOriginalExpiry() {
        val output = reprojectedMetric()
        val main = MainActivity()
        assertEquals(ORIGINAL_EXPIRY_MS, output.sourceValidUntilElapsedRealtimeMs)
        for (nowMs in listOf(DELAYED_CAPTURE_ELAPSED_MS, ORIGINAL_EXPIRY_MS, 53_740L)) {
            val bound = bind(main, output, CURRENT_FRAME_MS, DELAYED_CAPTURE_ELAPSED_MS, nowMs)
            assertEquals(ORIGINAL_EXPIRY_MS, bound.sourceValidUntilElapsedRealtimeMs)
            assertEquals(ORIGINAL_ELAPSED_MS, bound.sourceObservedAtElapsedRealtimeMs)
            assertEquals(SOURCE_MAX_AGE_MS, bound.sourceMaxAgeMs)
        }
        assertEquals(ORIGINAL_EXPIRY_MS, output.sourceValidUntilElapsedRealtimeMs)
    }

    @Test
    fun nonMetricCurrentCaptureRetainsItsCaptureBasedLeaseContract() {
        val output = reprojectedMetric().copy(
            source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
            riskDistanceM = null,
            depthObservedAtMs = null,
            sourceValidUntilElapsedRealtimeMs = null,
            sourceObservedAtElapsedRealtimeMs = null,
            sourceMaxAgeMs = null,
        )

        val bound = bind(MainActivity(), output, CURRENT_FRAME_MS, DELAYED_CAPTURE_ELAPSED_MS,
            DELAYED_CAPTURE_ELAPSED_MS)

        assertEquals(53_120L, bound.sourceValidUntilElapsedRealtimeMs)
        assertEquals(DELAYED_CAPTURE_ELAPSED_MS, bound.sourceObservedAtElapsedRealtimeMs)
        assertEquals(SOURCE_MAX_AGE_MS, bound.sourceMaxAgeMs)
    }

    @Test
    fun invalidOriginalMetricElapsedOrMissingNonPositiveBudgetFailsClosed() {
        val original = reprojectedMetric()
        val invalid = listOf(
            original.copy(sourceObservedAtElapsedRealtimeMs = null),
            original.copy(sourceObservedAtElapsedRealtimeMs = -1L),
            original.copy(sourceObservedAtElapsedRealtimeMs = DELAYED_CAPTURE_ELAPSED_MS + 1L),
            original.copy(sourceMaxAgeMs = null),
            original.copy(sourceMaxAgeMs = -1L),
            original.copy(sourceMaxAgeMs = 0L),
        )
        val main = MainActivity()
        for (output in invalid) {
            val bound = bind(main, output, CURRENT_FRAME_MS, DELAYED_CAPTURE_ELAPSED_MS,
                DELAYED_CAPTURE_ELAPSED_MS)
            assertEquals(Long.MIN_VALUE, bound.sourceValidUntilElapsedRealtimeMs)
            assertNull(bound.sourceObservedAtElapsedRealtimeMs)
            assertNull(bound.sourceMaxAgeMs)
        }
    }

    @Test
    fun missingNegativeOrFutureDepthCaptureAndMismatchedFrameFailClosed() {
        val original = reprojectedMetric()
        val invalid = listOf(
            original.copy(depthObservedAtMs = null),
            original.copy(depthObservedAtMs = -1L),
            original.copy(depthObservedAtMs = CURRENT_FRAME_MS + 1L),
            original.copy(frameId = original.frameId + 1L),
        )
        val main = MainActivity()
        for (output in invalid) {
            val bound = bind(main, output, CURRENT_FRAME_MS, DELAYED_CAPTURE_ELAPSED_MS,
                DELAYED_CAPTURE_ELAPSED_MS)
            assertEquals(Long.MIN_VALUE, bound.sourceValidUntilElapsedRealtimeMs)
            assertNull(bound.sourceObservedAtElapsedRealtimeMs)
            assertNull(bound.sourceMaxAgeMs)
        }
    }

    @Test
    fun sourceExpiryAtUnlimitedSentinelOrOverflowCannotIssueMetricLease() {
        val original = reprojectedMetric()
        val main = MainActivity()
        for (elapsedMs in listOf(Long.MAX_VALUE - SOURCE_MAX_AGE_MS, Long.MAX_VALUE - 400L)) {
            val output = original.copy(
                sourceValidUntilElapsedRealtimeMs = null,
                sourceObservedAtElapsedRealtimeMs = elapsedMs,
                sourceMaxAgeMs = SOURCE_MAX_AGE_MS,
            )
            val bound = bind(main, output, CURRENT_FRAME_MS, elapsedMs, Long.MAX_VALUE - 1L)
            assertEquals(Long.MIN_VALUE, bound.sourceValidUntilElapsedRealtimeMs)
            assertNull(bound.sourceObservedAtElapsedRealtimeMs)
            assertNull(bound.sourceMaxAgeMs)
        }
    }

    @Test
    fun smallerPositiveProducerBudgetKeepsOriginalDeadlineEvenAfterExpiry() {
        val original = reprojectedMetric()
        val main = MainActivity()
        for (budgetMs in listOf(1L, 200L, SOURCE_MAX_AGE_MS)) {
            val output = original.copy(sourceValidUntilElapsedRealtimeMs = null, sourceMaxAgeMs = budgetMs)
            val bound = bind(main, output, CURRENT_FRAME_MS, DELAYED_CAPTURE_ELAPSED_MS,
                DELAYED_CAPTURE_ELAPSED_MS)
            assertEquals(ORIGINAL_ELAPSED_MS + budgetMs, bound.sourceValidUntilElapsedRealtimeMs)
            assertEquals(ORIGINAL_ELAPSED_MS, bound.sourceObservedAtElapsedRealtimeMs)
            assertEquals(budgetMs, bound.sourceMaxAgeMs)
        }
    }

    private fun reprojectedMetric(originalElapsedKnown: Boolean = true): TrackedObjectDepth {
        val pipeline = ObjectDepthRuntimePipeline()
        (0..11).forEach { index ->
            val frameMs = 1_040L + index * 100L
            process(pipeline, frameMs, if (originalElapsedKnown) frameMs + 50_000L else null)
        }
        return process(pipeline, CURRENT_FRAME_MS, DELAYED_CAPTURE_ELAPSED_MS,
            depthFrameMs = ORIGINAL_FRAME_MS)
    }

    private fun process(
        pipeline: ObjectDepthRuntimePipeline,
        frameMs: Long,
        captureElapsedMs: Long?,
        depthFrameMs: Long = frameMs,
    ): TrackedObjectDepth = pipeline.process(
        snapshot = DepthFrameSnapshot(
            frameTimestampNs = frameMs * 1_000_000L,
            rawDepth = DepthImage16(100, 100, IntArray(10_000) { 2_000 }),
            rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
            fullDepth = null,
            rawDepthTimestampNs = imageTimestampNs(depthFrameMs),
            rawConfidenceTimestampNs = imageTimestampNs(depthFrameMs),
            cameraImageTimestampNs = imageTimestampNs(frameMs),
        ),
        frameId = frameMs * 1_000_000L,
        timestampMs = frameMs,
        detections = listOf(detection),
        mapper = mapper,
        mapperFrameId = frameMs * 1_000_000L,
        capturedAtElapsedRealtimeMs = captureElapsedMs,
    ).single()

    private fun bind(
        main: MainActivity,
        output: TrackedObjectDepth,
        sourceFrameMs: Long,
        capturedAtElapsedMs: Long?,
        nowElapsedMs: Long,
    ): TrackedObjectDepth {
        val owner = MainActivityControllerTestFixture.controller(main, "frameSynthesisHolder") as FrameSynthesisController
        return owner.bindLegacyFeedbackToCapture(output, sourceFrameMs * 1_000_000L, sourceFrameMs,
            capturedAtElapsedMs, nowElapsedMs)
    }

    private fun imageTimestampNs(frameMs: Long) = 10_000_000_000_000L + frameMs * 1_000_000L

    private companion object {
        const val ORIGINAL_FRAME_MS = 1_940L
        const val ORIGINAL_ELAPSED_MS = 51_940L
        const val CURRENT_FRAME_MS = 2_240L
        const val DELAYED_CAPTURE_ELAPSED_MS = 52_320L
        const val SOURCE_MAX_AGE_MS = 800L
        const val ORIGINAL_EXPIRY_MS = 52_740L
    }
}
