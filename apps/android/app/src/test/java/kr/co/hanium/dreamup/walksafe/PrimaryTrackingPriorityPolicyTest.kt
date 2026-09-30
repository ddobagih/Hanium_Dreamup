package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.*
import org.junit.Assert.*
import org.junit.Test

class PrimaryTrackingPriorityPolicyTest {
    private val frameId = 1_000_000_000L
    private fun detection(index: Int) = DetectionCandidate("object-$index", 1f - index * .1f,
        RectNorm(.1f + index * .15f, .4f, .1f, .2f))
    private fun output(detection: DetectionCandidate, level: MessageLevel, distance: Float) = TrackedObjectDepth(
        frameId, frameId / 1_000_000L, detection.className, detection.className, detection.detectionConfidence,
        detection.bboxNorm, emptyList(), detection.bboxNorm.area, detection.bboxNorm.center, null,
        DepthSource.ARCORE_RAW_DEPTH, distance, distance, null, distance, 100, 1f,
        distance, distance, .01f, Trend.STABLE, 0f, null, null,
        DepthConfidenceBreakdown(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
        UserFacingDepth(null, level, if (level == MessageLevel.NONE) null else "위험"), 4, 900L)

    @Test fun lowerConfidenceFourthDetectionGetsMeasuredRiskPriority() {
        val detections = (0..3).map(::detection)
        val outputs = detections.mapIndexed { i, d -> output(d,
            if (i == 3) MessageLevel.STOP else MessageLevel.NONE, if (i == 3) 1f else 8f) }
        assertEquals(listOf(3), PrimaryTrackingPriorityPolicy.sourceIndices(detections, outputs, frameId, 100L))
    }

    @Test fun staleForeignFrameAndUnmeasuredOutputDoNotScheduleAsKnownDanger() {
        val d = detection(0); val valid = output(d, MessageLevel.STOP, 1f)
        assertTrue(PrimaryTrackingPriorityPolicy.sourceIndices(listOf(d), listOf(valid), frameId, 801L).isEmpty())
        assertTrue(PrimaryTrackingPriorityPolicy.sourceIndices(listOf(d), listOf(valid.copy(frameId = frameId + 1)), frameId, 100L).isEmpty())
        assertTrue(PrimaryTrackingPriorityPolicy.sourceIndices(listOf(d), listOf(valid.copy(source = DepthSource.UNKNOWN)), frameId, 100L).isEmpty())
        assertTrue(PrimaryTrackingPriorityPolicy.sourceIndices(listOf(d), listOf(valid.copy(riskDistanceM = Float.NaN)), frameId, 100L).isEmpty())
    }

    @Test fun priorityRetainsAllUrgentIndicesAndDoesNotGuessDuplicateIdentity() {
        val detections = (0..3).map(::detection)
        val outputs = detections.mapIndexed { i, d -> output(d, MessageLevel.STOP, 4f - i) }
        assertEquals(listOf(3, 2, 1, 0), PrimaryTrackingPriorityPolicy.sourceIndices(detections, outputs, frameId, 100L))
        val d = detections[0]
        assertTrue(PrimaryTrackingPriorityPolicy.sourceIndices(listOf(d, d), listOf(outputs[0]), frameId, 100L).isEmpty())
    }

    // ---- S10-21 (originals/10.txt:226-232): candidate ID instead of float box equality; distance-uncertain tier ----

    @Test fun aSlightlyReconstructedBoxKeepsItsRiskHintThroughTheSourceIndex() {
        val detections = (0..3).map(::detection)
        val shifted = detections[3].bboxNorm.let { RectNorm(it.x + 1e-6f, it.y, it.width - 1e-6f, it.height) }
        val risky = output(detections[3], MessageLevel.STOP, 1f).copy(bboxNorm = shifted)
        // Without the candidate ID the hint is lost to float inequality (the old behaviour).
        assertTrue(PrimaryTrackingPriorityPolicy.sourceIndices(detections, listOf(risky), frameId, 100L).isEmpty())
        assertEquals(listOf(3), PrimaryTrackingPriorityPolicy.sourceIndices(
            detections, listOf(risky.copy(sourceDetectionIndex = 3)), frameId, 100L))
        // An index naming another class or out of range is not trusted; box equality stays the fallback.
        assertTrue(PrimaryTrackingPriorityPolicy.sourceIndices(
            detections, listOf(risky.copy(sourceDetectionIndex = 2)), frameId, 100L).isEmpty())
        assertTrue(PrimaryTrackingPriorityPolicy.sourceIndices(
            detections, listOf(risky.copy(sourceDetectionIndex = 9)), frameId, 100L).isEmpty())
        // The candidate ID also resolves duplicate boxes that equality cannot tell apart.
        val d = detections[0]
        assertEquals(listOf(1), PrimaryTrackingPriorityPolicy.sourceIndices(
            listOf(d, d), listOf(output(d, MessageLevel.STOP, 1f).copy(sourceDetectionIndex = 1)), frameId, 100L))
    }

    @Test fun aCentralObstacleWithoutDepthIsScheduledAfterEveryMeasuredRisk() {
        val measured = DetectionCandidate("person", .9f, RectNorm(.1f, .4f, .1f, .2f))
        val scooter = DetectionCandidate("abandoned_e_scooter", .5f, RectNorm(.45f, .5f, .1f, .3f))
        val sideScooter = DetectionCandidate("abandoned_e_scooter", .8f, RectNorm(.85f, .5f, .1f, .3f))
        val paving = DetectionCandidate("linear_tactile_paving", .9f, RectNorm(.4f, .7f, .2f, .3f))
        val detections = listOf(measured, scooter, sideScooter, paving)
        fun noDepth(d: DetectionCandidate, corridor: Float, index: Int) = output(d, MessageLevel.NONE, 1f).copy(
            source = DepthSource.UNKNOWN, riskDistanceM = null, zDistanceM = null, rayDistanceM = null,
            confidence = DepthConfidenceBreakdown(0f, 0f, 0f, 1f, 1f, 1f, 1f, corridor),
            sourceDetectionIndex = index,
        )
        val outputs = listOf(
            noDepth(scooter, 1f, 1), noDepth(sideScooter, .45f, 2), noDepth(paving, 1f, 3),
            output(measured, MessageLevel.WARNING, 2f).copy(sourceDetectionIndex = 0),
        )
        // Measured risk first, then the corridor obstacle whose distance is unknown; the side object
        // and the tactile paving keep the tracker's screen-geometry ranking.
        assertEquals(listOf(0, 1), PrimaryTrackingPriorityPolicy.schedulingIndices(detections, outputs, frameId, 100L))
        // The measured-risk list itself never contains an unmeasured object (not a known danger).
        assertEquals(listOf(0), PrimaryTrackingPriorityPolicy.sourceIndices(detections, outputs, frameId, 100L))
        // Uncertain objects of a stale or foreign capture are not scheduled either.
        assertTrue(PrimaryTrackingPriorityPolicy.schedulingIndices(detections, outputs, frameId, 801L).isEmpty())
        assertTrue(PrimaryTrackingPriorityPolicy.schedulingIndices(
            detections, outputs.map { it.copy(frameId = frameId + 1) }, frameId, 100L).isEmpty())
        // A metric far object is measured and not risky: it is not "distance uncertain".
        val farScooter = output(scooter, MessageLevel.NONE, 8f).copy(sourceDetectionIndex = 1)
        assertTrue(PrimaryTrackingPriorityPolicy.schedulingIndices(detections, listOf(farScooter), frameId, 100L).isEmpty())
    }

    @Test fun thePipelineBindsEachOutputToItsOriginalCandidateIndex() {
        val pipeline = ObjectDepthRuntimePipeline()
        val snapshot = DepthFrameSnapshot(frameTimestampNs = frameId, rawDepth = null, rawConfidence = null, fullDepth = null)
        val detections = listOf(
            DetectionCandidate("person", .9f, RectNorm(.1f, .3f, .2f, .4f)),
            // Below the pipeline's detection floor: dropped, but later indices keep their positions.
            DetectionCandidate("bollard", .05f, RectNorm(.4f, .3f, .1f, .4f)),
            DetectionCandidate("abandoned_e_scooter", .7f, RectNorm(.6f, .3f, .2f, .4f)),
        )
        val outputs = pipeline.processPrealignedForTest(snapshot, frameId, frameId / 1_000_000L, detections)
        assertEquals(mapOf("person" to 0, "abandoned_e_scooter" to 2),
            outputs.associate { it.className to it.sourceDetectionIndex })
        // A replay of the same completed detection keeps the same binding.
        val replay = pipeline.processPrealignedForTest(snapshot, frameId, frameId / 1_000_000L, detections)
        assertEquals(outputs.map { it.sourceDetectionIndex }, replay.map { it.sourceDetectionIndex })
    }
}
