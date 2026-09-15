package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.util.Random
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.CurrentTrackedFrameObservation
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.inference.YoloRawOutputParser
import org.junit.Assert.*
import org.junit.Test

class InterFrameTrackingAdmissionRegressionTest {
    private val pixels = ByteArray(192 * 192).also { Random(0x571a).nextBytes(it) }
    private val config = VisualTrackingConfig(maxFeaturesPerObject = 8, backend = VisualTrackingBackend.PATCH_DIAGNOSTIC)

    @Test fun fourthLowerConfidenceFrontObjectSurvivesAllFourCameraOrientationsWithinThreeFlowSlots() {
        val upright = listOf(
            detection("far-left", .99f, RectNorm(.05f, .1f, .18f, .2f)),
            detection("far-top", .98f, RectNorm(.35f, .05f, .25f, .2f)),
            detection("far-right", .97f, RectNorm(.75f, .1f, .2f, .2f)),
            detection("front-obstacle", .85f, RectNorm(.35f, .55f, .3f, .4f)),
        )
        for (turns in 0..3) {
            val tracker = tracker()
            val source = offer(tracker, 0)
            val candidates = upright.map { it.copy(bboxNorm = UprightCameraImage.toSensor(it.bboxNorm, turns)) }
            val direct = tracker.trackFrom(source, candidates, source, uprightQuarterTurns = turns)
            val current = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(direct, candidates, true, 1_000L))
            assertEquals(4, current.trackedObservations.size)
            assertEquals(0, direct.metrics.processedEdges)
            assertEquals(0L, direct.metrics.pixelComparisons)
            val target = offer(tracker, 1)
            val propagated = tracker.trackFrom(source, candidates, target, uprightQuarterTurns = turns)
            assertEquals("Quarter turn $turns", VisualTrackingStatus.TRACKED, propagated.observations[3].status)
            val geometry = requireNotNull(propagated.observations[3].geometry)
            assertEquals(candidates[3].className, geometry.className)
            assertEquals(candidates[3].detectionConfidence, geometry.detectionConfidence, 0f)
            assertEquals(candidates[3].bboxNorm.x, geometry.bboxNorm.x, .001f)
            assertEquals(candidates[3].bboxNorm.y, geometry.bboxNorm.y, .001f)
            assertEquals(1, propagated.observations.count { it.failure == VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED })
            assertTrue(propagated.metrics.processedEdges <= 3)
            assertTrue(propagated.metrics.pixelComparisons <= config.maxPixelComparisonsPerCall)
            assertEquals(5_000_000L, propagated.metrics.executionBudgetNs)
        }
    }

    @Test fun sourceDepthHazardsCanFillEverySlotWithoutEvictionByCoverageOrGeometricFallback() {
        val tracker = tracker()
        val candidates = List(6) { index -> detection("object-$index", .99f - index * .05f, RectNorm(.25f, .25f, .4f, .4f)) }
        val source = offer(tracker, 0)
        val target = offer(tracker, 1)
        val result = tracker.trackFrom(source, candidates, target, prioritySourceIndices = listOf(-1, 5, 5, 4, 3, 50))
        assertEquals(listOf(3, 4, 5), result.observations.filter { it.status == VisualTrackingStatus.TRACKED }.map { it.sourceIndex })
        assertEquals(candidates.indices.toList(), result.observations.map { it.sourceIndex })
        assertEquals(3, result.metrics.processedEdges)
        // Priorities are frozen with the detector revision; a repeat cannot silently switch identity.
        val repeated = tracker.trackFrom(source, candidates, target, prioritySourceIndices = listOf(0, 1, 2))
        assertEquals(result.observations, repeated.observations)
        assertTrue(repeated.metrics.cacheHit)
    }

    @Test fun newDetectorBatchesRotateCoverageWithoutIncreasingTheOpticalFlowBudget() {
        val tracker = tracker()
        val candidates = List(5) { index -> detection("object-$index", .99f - index * .05f, RectNorm(.25f, .25f, .4f, .4f)) }
        val coverage = mutableListOf<Int>()
        repeat(3) { batch ->
            val source = offer(tracker, batch * 2)
            val target = offer(tracker, batch * 2 + 1)
            val result = tracker.trackFrom(source, candidates, target)
            val indexes = result.observations.filter { it.status == VisualTrackingStatus.TRACKED }.map { it.sourceIndex }
            assertTrue(indexes.containsAll(listOf(0, 1)))
            coverage += indexes.single { it > 1 }
            assertEquals(3, result.metrics.processedEdges)
        }
        assertEquals(listOf(2, 3, 4), coverage)
    }

    @Test fun actualParserBatchesOf128129And300RetainEveryIndexAndExplicitLossAfterPropagation() {
        val parser = YoloRawOutputParser(768, 1, { "person" }, { .2f }, normalizedCoordinates = true)
        for (count in listOf(128, 129, 300)) {
            val anchors = 12_096
            val raw = FloatArray(5 * anchors)
            repeat(count) { index ->
                raw[index] = ((index % 20) + .5f) / 20f
                raw[anchors + index] = ((index / 20) + .5f) / 15f
                raw[2 * anchors + index] = .025f
                raw[3 * anchors + index] = .033f
                raw[4 * anchors + index] = .9f
            }
            val candidates = parser.parse(raw)
            assertEquals(count, candidates.size)
            val tracker = tracker()
            val source = offer(tracker, 0)
            val direct = tracker.trackFrom(source, candidates, source)
            assertNull(direct.batchFailure)
            assertEquals(candidates.indices.toList(), direct.observations.map { it.sourceIndex })
            assertEquals(candidates, direct.observations.map { it.geometry })
            assertNotNull(CurrentTrackedFrameObservation.fromTrackingResult(direct, candidates, true, 1_000L))
            val target = offer(tracker, 1)
            val result = tracker.trackFrom(source, candidates, target)
            assertNull(result.batchFailure)
            assertEquals(candidates.indices.toList(), result.observations.map { it.sourceIndex })
            // Tiny boxes can fail seeding; remaining work may try later source indexes.
            assertTrue(result.observations.count { it.status == VisualTrackingStatus.TRACKED } <= 3)
            result.observations.filter { it.status == VisualTrackingStatus.LOST }.forEach { assertNull(it.geometry); assertNotNull(it.failure) }
            assertNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result, candidates, true, 1_050L))
            assertTrue(result.metrics.processedEdges <= 3)
            assertTrue(result.metrics.pixelComparisons <= config.maxPixelComparisonsPerCall)
        }
    }

    @Test fun detectorOnlyGeometryNeverSurvivesExpiredSourceOrExceedsThe300InputLimit() {
        val tracker = tracker()
        val source = offer(tracker, 0)
        val candidates = List(4) { detection("person", .9f, RectNorm(.2f, .2f, .4f, .4f)) }
        val expired = tracker.trackFrom(source, candidates, source, observedAtElapsedRealtimeMs = 1_801L)
        assertTrue(expired.observations.all { it.failure == VisualTrackingFailure.SOURCE_EXPIRED && it.geometry == null })
        val oversized = tracker.trackFrom(source, List(301) { candidates[0] }, source)
        assertEquals(VisualTrackingFailure.INPUT_BUDGET_EXCEEDED, oversized.batchFailure)
        assertTrue(oversized.observations.isEmpty())
    }

    private fun tracker() = InterFrameDetectionTracker(config, clockNanos = { 0L })
    private fun detection(name: String, confidence: Float, rect: RectNorm) = DetectionCandidate(name, confidence, rect)
    private fun offer(tracker: InterFrameDetectionTracker, index: Int): VisualFrameKey {
        val elapsed = 1_000L + index * 50L
        val key = VisualFrameKey(1, elapsed * 1_000_000L, elapsed * 1_000_000L, elapsed, 1)
        assertTrue(tracker.offerFrame(GrayTrackingFrame.copyOf(key, 192, 192, pixels)).accepted)
        return key
    }
}
