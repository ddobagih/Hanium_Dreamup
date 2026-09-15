package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.util.Random
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.*
import org.junit.Test

class InterFrameTrackingFallbackTest {
    private val candidates = List(4) { DetectionCandidate("person", .95f, RectNorm(.12f + it * .18f, .35f, .13f, .3f)) }
    private val config = VisualTrackingConfig(maxFeaturesPerObject = 8, backend = VisualTrackingBackend.PATCH_DIAGNOSTIC)

    @Test fun threeTerminalSeedFailuresFreeASlotForTheFourthCandidateInTheSameCall() {
        val tracker = InterFrameDetectionTracker(config, clockNanos = { 0L })
        val (source, target) = offerPair(tracker, blankCount = 3)
        val result = tracker.trackFrom(source, candidates, target, prioritySourceIndices = listOf(0, 1, 2))
        assertTrue(result.observations.take(3).all { it.failure == VisualTrackingFailure.TOO_FEW_FEATURES })
        assertEquals(VisualTrackingStatus.TRACKED, result.observations[3].status)
        assertEquals(1, result.metrics.processedEdges)
        assertTrue(result.metrics.pixelComparisons <= config.maxPixelComparisonsPerCall)
        val repeated = tracker.trackFrom(source, candidates, target)
        assertEquals(result.observations, repeated.observations)
        assertTrue(repeated.metrics.cacheHit)
        assertEquals(0L, repeated.metrics.pixelComparisons)
    }

    @Test fun fallbackNeverExceedsThreeSuccessfulObjects() {
        for (blankCount in listOf(0, 1)) {
            val tracker = InterFrameDetectionTracker(config, clockNanos = { 0L })
            val (source, target) = offerPair(tracker, blankCount)
            val result = tracker.trackFrom(source, candidates, target, prioritySourceIndices = listOf(0, 1, 2))
            assertEquals(3, result.observations.count { it.status == VisualTrackingStatus.TRACKED })
            assertEquals(3, result.metrics.processedEdges)
            assertTrue(result.metrics.pixelComparisons <= config.maxPixelComparisonsPerCall)
            if (blankCount == 0) assertEquals(VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED, result.observations[3].failure)
            else assertEquals(VisualTrackingStatus.TRACKED, result.observations[3].status)
        }
    }

    @Test fun fallbackSharesTheOriginalPixelBudgetAndPublishesNoUnfinishedGeometry() {
        val tracker = InterFrameDetectionTracker(config.copy(maxPixelComparisonsPerCall = 1L), clockNanos = { 0L })
        val (source, target) = offerPair(tracker, blankCount = 3)
        val result = tracker.trackFrom(source, candidates, target, prioritySourceIndices = listOf(0, 1, 2))
        assertTrue(result.observations.take(3).all { it.failure == VisualTrackingFailure.TOO_FEW_FEATURES })
        assertEquals(VisualTrackingFailure.WORK_BUDGET_EXCEEDED, result.observations[3].failure)
        assertTrue(result.observations.all { it.geometry == null })
        assertTrue(result.metrics.pixelComparisons <= 1L)
        assertEquals(0, result.metrics.processedEdges)
    }

    @Test fun noFallbackEstimatorStartsWhenTerminalFailuresConsumeTheRemainingTime() {
        var probeReads = 0
        val probe = InterFrameDetectionTracker(config, clockNanos = { probeReads++; 0L })
        val (source, target) = offerPair(probe, blankCount = 3)
        val result = probe.trackFrom(source, candidates.take(3), target, prioritySourceIndices = listOf(0, 1, 2))
        assertTrue(result.observations.all { it.failure == VisualTrackingFailure.TOO_FEW_FEATURES })
        var reads = 0
        val tracker = InterFrameDetectionTracker(config, clockNanos = { if (++reads >= probeReads) config.maxExecutionNs else 0L })
        offerPair(tracker, blankCount = 3)
        val exhausted = tracker.trackFrom(source, candidates, target, prioritySourceIndices = listOf(0, 1, 2))
        assertTrue(exhausted.observations.take(3).all { it.failure == VisualTrackingFailure.TOO_FEW_FEATURES })
        assertEquals(VisualTrackingFailure.TIME_BUDGET_EXCEEDED, exhausted.observations[3].failure)
        assertEquals(0, exhausted.observations[3].originalFeatureCount)
        assertEquals(0, exhausted.metrics.processedEdges)
        assertEquals(0L, exhausted.metrics.pixelComparisons)
        assertTrue(exhausted.observations.all { it.geometry == null })
    }

    private fun offerPair(tracker: InterFrameDetectionTracker, blankCount: Int): Pair<VisualFrameKey, VisualFrameKey> {
        val pixels = ByteArray(192 * 192).also { Random(0x571a).nextBytes(it) }
        for (y in 0 until 192) for (x in 0 until 192) if (candidates.take(blankCount).any { detection ->
            val box = detection.bboxNorm
            x / 192f in box.x - .02f..box.x + box.width + .02f && y / 192f in box.y - .02f..box.y + box.height + .02f
        }) pixels[y * 192 + x] = 0
        fun offer(at: Long): VisualFrameKey {
            val key = VisualFrameKey(1, at * 1_000_000L, at * 1_000_000L, at, 1)
            assertTrue(tracker.offerFrame(GrayTrackingFrame.copyOf(key, 192, 192, pixels)).accepted)
            return key
        }
        return offer(1_000L) to offer(1_050L)
    }
}
