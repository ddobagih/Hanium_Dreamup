package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S12-29: a displayable old Raw capture must not hide a usable current Full capture. */
class AgedRawFreshFullSelectionRegressionTest {
    @Test
    fun expiredRawFallsThroughToFreshFullAndReachesTheFeedbackConsumer() {
        val fixture = Fixture()
        fixture.warmOriginalRaw()
        val output = fixture.tracked(1_900L, full = fullDepth())
        printOutput("expiredRawFreshFull", output)
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, output.source)
        assertEquals(2f, requireNotNull(output.riskDistanceM), .001f)
        assertEquals(1_900L, output.depthObservedAtMs)
        assertEquals(51_900L, output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(52_700L, output.sourceValidUntilElapsedRealtimeMs)
        assertEquals(false, output.depthReprojected)
        assertTrue(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, output, 1_900L))
        assertNull(output.timeToCollisionMs)
        val candidates = AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(output))
            .mapNotNull { it.toFeedbackCandidate() }
        val action = WalkSafeFeedbackPolicy().evaluateCandidates(candidates, true, 51_900L)
        assertNotNull("fresh Full must remain consumable after expired Raw is refused", action)
        assertEquals(output.sourceValidUntilElapsedRealtimeMs, requireNotNull(action).validUntilMs)
        // Switching to Full never retags the retained Raw capture.
        assertDisplayOnlyOriginalRaw(fixture.tracked(2_000L))
    }

    @Test
    fun freshRawKeepsPriorityOverEquallyAccurateFreshFull() {
        val fixture = Fixture()
        fixture.warmOriginalRaw()
        val output = fixture.tracked(1_900L, rawTimestampNs = imageTimestamp(1_900L), full = fullDepth())
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(1_900L, output.depthObservedAtMs)
        assertEquals(false, output.depthReprojected)
        assertTrue(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, output, 1_900L))
    }

    @Test
    fun rawAtTheExistingRiskAgeBoundaryStillKeepsPriority() {
        val fixture = Fixture()
        fixture.warmOriginalRaw()
        val output = fixture.tracked(1_800L, full = fullDepth())
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(1_000L, output.depthObservedAtMs)
        assertEquals(51_800L, output.sourceValidUntilElapsedRealtimeMs)
        assertTrue(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, output, 1_800L))
    }

    @Test
    fun selectionAlsoUsesTheActualElapsedEvaluationTime() {
        val fixture = Fixture()
        fixture.warmOriginalRaw()
        // Frame age is exactly 800 ms, but evaluation is one millisecond past Raw's lease.
        val output = fixture.tracked(1_800L, full = fullDepth(), evaluationDelayMs = 1L)
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, output.source)
        assertEquals(1_800L, output.depthObservedAtMs)
        assertEquals(51_800L, output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(52_600L, output.sourceValidUntilElapsedRealtimeMs)
        assertEquals(false, output.depthReprojected)
    }

    @Test
    fun rawOneMillisecondPastTheRiskBoundaryFallsThroughToFreshFull() {
        val fixture = Fixture()
        fixture.warmOriginalRaw()
        val output = fixture.tracked(1_801L, full = fullDepth())
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, output.source)
        assertEquals(1_801L, output.depthObservedAtMs)
        assertEquals(51_801L, output.sourceObservedAtElapsedRealtimeMs)
    }

    @Test
    fun fullWithACaptureReceiptAfterEvaluationCannotProduceOutput() {
        val fixture = Fixture()
        fixture.warmOriginalRaw()
        assertTrue(fixture.trackedOutputs(1_900L, full = fullDepth(), evaluationDelayMs = -1L).isEmpty())
    }

    @Test
    fun originalRawStillUsesTheUnchangedDisplayBoundary() {
        val fixture = Fixture()
        fixture.warmOriginalRaw()
        val atBoundary = fixture.tracked(2_200L)
        assertDisplayOnlyOriginalRaw(atBoundary)
        val beyondBoundary = fixture.tracked(2_201L)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, beyondBoundary.source)
        assertEquals(1_000L, beyondBoundary.depthObservedAtMs)
        assertFalse(DepthAgeBudget.admits(DepthUse.PROXIMITY_DISPLAY, beyondBoundary, 2_201L))
        assertFalse(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, beyondBoundary, 2_201L))
    }

    @Test
    fun expiredRawRemainsDisplayableWhenFullIsAbsent() {
        val fixture = Fixture()
        fixture.warmOriginalRaw()
        assertDisplayOnlyOriginalRaw(fixture.tracked(1_900L))
    }

    @Test
    fun foreignFullClockCannotReplaceExpiredRaw() {
        val fixture = Fixture()
        fixture.warmOriginalRaw()
        assertDisplayOnlyOriginalRaw(fixture.tracked(1_900L, full = fullDepth(),
            fullTimestampNs = imageTimestamp(1_900L) + 1L))
    }

    @Test
    fun insufficientFullSamplesCannotReplaceExpiredRaw() {
        val fixture = Fixture()
        fixture.warmOriginalRaw()
        // Even all pixels of this Full image cannot meet its existing support requirement.
        assertDisplayOnlyOriginalRaw(fixture.tracked(1_900L,
            full = DepthImage16(7, 7, IntArray(49) { 2_000 })))
    }

    @Test
    fun matchingAnOldImageToALaterCpuReceiptCannotRenewEitherCapture() {
        val fixture = Fixture()
        fixture.direct(1_000L, rawPresent = true, fullPresent = true)
        val raw = fixture.direct(1_900L, rawPresent = true, fullPresent = true)
        assertDisplayOnlyOriginalRaw(raw)
        val full = fixture.direct(2_000L, rawPresent = false, fullPresent = true)
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, full.source)
        assertEquals(1_000L, full.depthObservedAtMs)
        assertEquals(51_000L, full.sourceObservedAtElapsedRealtimeMs)
        assertEquals(51_800L, full.sourceValidUntilElapsedRealtimeMs)
        assertEquals(true, full.depthReprojected)
        assertFalse(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, full, 2_000L))
        assertTrue(DepthAgeBudget.admits(DepthUse.PROXIMITY_DISPLAY, full, 2_000L))
    }

    private fun assertDisplayOnlyOriginalRaw(output: TrackedObjectDepth) {
        printOutput("displayOnlyOriginalRaw", output)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(2f, requireNotNull(output.riskDistanceM), .001f)
        assertEquals(1_000L, output.depthObservedAtMs)
        assertEquals(51_000L, output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(51_800L, output.sourceValidUntilElapsedRealtimeMs)
        assertEquals(true, output.depthReprojected)
        assertTrue(DepthAgeBudget.admits(DepthUse.PROXIMITY_DISPLAY, output, output.timestampMs))
        assertFalse(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, output, output.timestampMs))
        assertNull(output.timeToCollisionMs)
        val candidates = AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(output))
            .mapNotNull { it.toFeedbackCandidate() }
        assertNull(WalkSafeFeedbackPolicy().evaluateCandidates(candidates, true, output.timestampMs + 50_000L))
    }

    private fun printOutput(label: String, output: TrackedObjectDepth) {
        val action = WalkSafeFeedbackPolicy().evaluateCandidates(
            AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(output))
                .mapNotNull { it.toFeedbackCandidate() }, true, output.timestampMs + 50_000L)
        println("S12-29 $label frame=${output.timestampMs} source=${output.source} " +
            "depthAt=${output.depthObservedAtMs} elapsedAt=${output.sourceObservedAtElapsedRealtimeMs} " +
            "lease=${output.sourceValidUntilElapsedRealtimeMs} distance=${output.riskDistanceM} " +
            "confidence=${output.confidence.finalScore} reprojected=${output.depthReprojected} " +
            "samples=${output.validSampleCount} trackAge=${output.trackAgeFrames} " +
            "stableMs=${output.trackStableMs} level=${output.userFacing.messageLevel} delivered=${action?.level}")
    }

    private class Fixture {
        private val pipeline = ObjectDepthRuntimePipeline()
        private val detection = DetectionCandidate("person", 1f, RectNorm(.2f, .2f, .6f, .6f))
        private val mapper = LetterboxCoordinateMapper(
            ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100),
        )

        fun warmOriginalRaw() {
            val first = tracked(1_000L)
            assertEquals(DepthSource.ARCORE_RAW_DEPTH, first.source)
            assertEquals(1_000L, first.depthObservedAtMs)
            assertEquals(51_000L, first.sourceObservedAtElapsedRealtimeMs)
            assertEquals(false, first.depthReprojected)
            tracked(1_300L)
            tracked(1_700L)
        }

        fun tracked(at: Long, rawTimestampNs: Long = imageTimestamp(1_000L),
                    full: DepthImage16? = null, fullTimestampNs: Long = imageTimestamp(at),
                    evaluationDelayMs: Long = 0L): TrackedObjectDepth =
            trackedOutputs(at, rawTimestampNs, full, fullTimestampNs, evaluationDelayMs).single()

        fun trackedOutputs(at: Long, rawTimestampNs: Long = imageTimestamp(1_000L),
                           full: DepthImage16? = null, fullTimestampNs: Long = imageTimestamp(at),
                           evaluationDelayMs: Long = 0L): List<TrackedObjectDepth> {
            val key = VisualFrameKey(7L, at * 1_000_000L, imageTimestamp(at), at + 50_000L, 3L)
            val snapshot = DepthFrameSnapshot(
                frameTimestampNs = key.frameId, rawDepth = fullDepth(),
                rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
                fullDepth = full, rawDepthTimestampNs = rawTimestampNs,
                rawConfidenceTimestampNs = rawTimestampNs, cameraImageTimestampNs = key.cameraTimestampNs,
                fullDepthTimestampNs = fullTimestampNs.takeIf { full != null },
            )
            assertTrue(snapshot.rawConfidenceMatchesRawDepth)
            if (full != null && fullTimestampNs == key.cameraTimestampNs) {
                val geometry = MaskPolygonExtractor().extract(detection)
                val stats = DepthSampler().sampleObjectDepth(full, null, geometry.polygonNorm,
                    DepthSampleOptions(minConfidence = null))
                if (full.width == 100) {
                    assertTrue("fresh Full fixture must supply enough in-ROI samples", stats.validSampleCount >= 50)
                    assertEquals(1f, requireNotNull(stats.spatialCoverage), 0f)
                    assertFalse(stats.foregroundAmbiguous)
                } else assertTrue(stats.validSampleCount < 50)
            }
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
                VisualTrackingResult(key, key, listOf(VisualTrackingObservation(
                    0, key, key, VisualTrackingStatus.TRACKED, detection, 1f, null,
                    Point2(0f, 0f), 8, 8, 0f, false,
                )), VisualTrackingMetrics(0L, 1, 0L, false, 2, 0)),
                listOf(detection), true, key.capturedAtElapsedRealtimeMs,
            ))
            return pipeline.processTrackedObservation(snapshot, observation, mapper, key.frameId,
                key.capturedAtElapsedRealtimeMs + evaluationDelayMs)
        }

        fun direct(at: Long, rawPresent: Boolean, fullPresent: Boolean): TrackedObjectDepth {
            val imageId = imageTimestamp(1_000L)
            val snapshot = DepthFrameSnapshot(at * 1_000_000L,
                fullDepth().takeIf { rawPresent },
                ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }).takeIf { rawPresent },
                fullDepth().takeIf { fullPresent }, rawDepthTimestampNs = imageId,
                fullDepthTimestampNs = imageId, cameraImageTimestampNs = imageId,
                rawConfidenceTimestampNs = imageId)
            return pipeline.process(snapshot, at * 1_000_000L, at, listOf(detection),
                mapper = mapper, mapperFrameId = at * 1_000_000L,
                capturedAtElapsedRealtimeMs = at + 50_000L).single()
        }
    }

    private companion object {
        fun fullDepth() = DepthImage16(100, 100, IntArray(10_000) { 2_000 })
        fun imageTimestamp(at: Long) = at * 1_000_000L + 400_000L
    }
}
