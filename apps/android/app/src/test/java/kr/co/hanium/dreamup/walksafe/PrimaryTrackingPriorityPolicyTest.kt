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
}
