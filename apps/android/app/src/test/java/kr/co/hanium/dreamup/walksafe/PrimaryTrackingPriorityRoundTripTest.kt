package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.ConfidenceImage8
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DepthImage16
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.MaskPolygonExtractor
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.inference.LetterboxTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S10-21 (originals/10.txt:226): the measured-risk hint is matched to the detector index by exact
 * class+bbox equality. The depth output bbox comes from MaskPolygonExtractor, so a parser image box
 * that is already inside [0,1] must survive that normalization bit-for-bit; otherwise the hint is lost.
 */
class PrimaryTrackingPriorityRoundTripTest {
    private val frameId = 1_000_000_000L
    // Landscape 1920x1080 (quarterTurns = 0) letterboxed to 768: the parser box is returned unrotated.
    private val transform = LetterboxTransform(1920, 1080, 768, 0.4f, 0f, 168f, 768, 432)

    private fun parserBoxes(): List<RectNorm> = buildList {
        for (i in 0 until 40) for (j in 0 until 10) {
            val model = RectNorm(0.013f + i * 0.0191f, 0.231f + j * 0.0277f, 0.071f + (i % 7) * 0.013f, 0.09f)
            val image = transform.modelRectToImageRect(model)
            if (image.width > 0f && image.x + image.width <= 1f && image.y + image.height <= 1f) add(image)
        }
    }

    @Test fun inRangeParserBoxesKeepExactIdentityThroughDepthGeometry() {
        val extractor = MaskPolygonExtractor()
        val boxes = parserBoxes()
        assertTrue(boxes.size > 100)
        val changed = boxes.count { box ->
            extractor.extract(DetectionCandidate("bollard", .5f, box)).bboxNorm != box
        }
        assertEquals("of ${boxes.size} in-range parser boxes", 0, changed)
    }

    @Test fun measuredRiskHintStillFindsItsLowerConfidenceDetectorIndex() {
        val extractor = MaskPolygonExtractor()
        val boxes = parserBoxes()
        var lost = 0
        for (start in 0 until boxes.size - 4 step 4) {
            val detections = (0 until 4).map { k ->
                DetectionCandidate("object-$k", 1f - k * .1f, boxes[start + k])
            }
            // Only the lowest-confidence fourth detection is a measured STOP hazard.
            val outputs = detections.mapIndexed { k, d ->
                output(d, extractor.extract(d).bboxNorm, if (k == 3) MessageLevel.STOP else MessageLevel.NONE)
            }
            if (PrimaryTrackingPriorityPolicy.sourceIndices(detections, outputs, frameId, 100L) != listOf(3)) lost++
        }
        assertEquals(0, lost)
    }

    @Test fun frozenDepthPipelineOutputKeepsTheDetectorBoxBitForBit() {
        val boxes = parserBoxes().filter { it.width >= .05f && it.height >= .05f }.take(60)
        assertTrue(boxes.size >= 30)
        var changed = 0
        boxes.forEachIndexed { index, box ->
            val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
            val cameraNs = (1_000L + index) * 1_000_000L
            val detection = DetectionCandidate("person", .9f, box)
            val output = pipeline.processPrealignedForTest(
                DepthFrameSnapshot(
                    frameTimestampNs = cameraNs,
                    rawDepth = DepthImage16(40, 40, IntArray(40 * 40) { 1_500 }),
                    rawConfidence = ConfidenceImage8(40, 40, ByteArray(40 * 40) { 255.toByte() }),
                    fullDepth = DepthImage16(40, 40, IntArray(40 * 40) { 1_500 }),
                    rawDepthTimestampNs = cameraNs, rawConfidenceTimestampNs = cameraNs,
                    fullDepthTimestampNs = cameraNs, cameraImageTimestampNs = cameraNs,
                ),
                frameId = cameraNs, timestampMs = cameraNs / 1_000_000L, detections = listOf(detection),
            ).single()
            if (output.bboxNorm != detection.bboxNorm) changed++
        }
        assertEquals("of ${boxes.size} pipeline outputs", 0, changed)
    }

    private fun output(detection: DetectionCandidate, depthBbox: RectNorm, level: MessageLevel) = TrackedObjectDepth(
        frameId, frameId / 1_000_000L, detection.className, detection.className, detection.detectionConfidence,
        depthBbox, emptyList(), depthBbox.area, depthBbox.center, null,
        DepthSource.ARCORE_RAW_DEPTH, 1f, 1f, null, 1f, 100, 1f,
        1f, 1f, .01f, Trend.STABLE, 0f, null, null,
        DepthConfidenceBreakdown(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
        UserFacingDepth(null, level, if (level == MessageLevel.NONE) null else "위험"), 4, 900L)
}
