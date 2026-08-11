package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloEndToEndOutputParserTest {
    @Test
    fun parserConvertsPixelXyxyRowsToDetectionCandidates() {
        val parser = YoloEndToEndOutputParser(
            inputSize = 960,
            classNameForId = TwoModelClassMap::customTactileClassName,
            thresholdForClass = { 0.35f },
        )
        val output = FloatArray(300 * 6)
        row(output, row = 0, x1 = 96f, y1 = 192f, x2 = 480f, y2 = 672f, score = 0.80f, classId = 0f)

        val detections = parser.parse(output)

        assertEquals(1, detections.size)
        assertEquals("normal_tactile_block", detections.single().className)
        assertEquals(0.10f, detections.single().bboxNorm.x, 0.001f)
        assertEquals(0.20f, detections.single().bboxNorm.y, 0.001f)
        assertEquals(0.40f, detections.single().bboxNorm.width, 0.001f)
        assertEquals(0.50f, detections.single().bboxNorm.height, 0.001f)
    }

    @Test
    fun parserFiltersBelowThresholdAndCocoDisallowlist() {
        val parser = YoloEndToEndOutputParser(
            inputSize = 640,
            classNameForId = TwoModelClassMap::cocoClassName,
            thresholdForClass = { className -> if (className == "bench") 0.40f else 0.35f },
            allowClass = TwoModelClassMap::isAllowedCocoClass,
        )
        val output = FloatArray(300 * 6)
        row(output, row = 0, x1 = 10f, y1 = 10f, x2 = 200f, y2 = 300f, score = 0.90f, classId = 0f)
        row(output, row = 1, x1 = 10f, y1 = 10f, x2 = 200f, y2 = 300f, score = 0.20f, classId = 2f)
        row(output, row = 2, x1 = 10f, y1 = 10f, x2 = 200f, y2 = 300f, score = 0.90f, classId = 15f)

        val detections = parser.parse(output)

        assertEquals(1, detections.size)
        assertEquals("person", detections.single().className)
    }

    @Test
    fun parserAcceptsUnifiedCocoAllowlistAndTactileClasses() {
        val parser = YoloEndToEndOutputParser(
            inputSize = 640,
            classNameForId = TwoModelClassMap::unifiedWalksafeClassName,
            thresholdForClass = { 0.30f },
            allowClass = TwoModelClassMap::isAllowedUnifiedWalksafeClass,
        )
        val output = FloatArray(300 * 6)
        row(output, row = 0, x1 = 10f, y1 = 10f, x2 = 200f, y2 = 300f, score = 0.90f, classId = 2f)
        row(output, row = 1, x1 = 20f, y1 = 20f, x2 = 240f, y2 = 320f, score = 0.80f, classId = 7f)
        row(output, row = 2, x1 = 30f, y1 = 30f, x2 = 260f, y2 = 340f, score = 0.80f, classId = 8f)
        row(output, row = 3, x1 = 35f, y1 = 35f, x2 = 270f, y2 = 350f, score = 0.80f, classId = 12f)
        row(output, row = 4, x1 = 40f, y1 = 40f, x2 = 280f, y2 = 360f, score = 0.90f, classId = 15f)

        val detections = parser.parse(output)

        assertEquals(4, detections.size)
        assertEquals("car", detections[0].className)
        assertEquals("normal_tactile_block", detections[1].className)
        assertEquals("damaged_tactile_block", detections[2].className)
        assertEquals("e_scooter_obstruction", detections[3].className)
    }

    @Test
    fun parserAcceptsAlreadyNormalizedCoordinatesAndClampsBounds() {
        val parser = YoloEndToEndOutputParser(
            inputSize = 640,
            classNameForId = TwoModelClassMap::cocoClassName,
            thresholdForClass = { 0.35f },
            allowClass = TwoModelClassMap::isAllowedCocoClass,
        )
        val output = FloatArray(300 * 6)
        row(output, row = 0, x1 = -10f, y1 = -5f, x2 = 700f, y2 = 650f, score = 0.90f, classId = 0f)
        row(output, row = 1, x1 = 0.10f, y1 = 0.20f, x2 = 0.30f, y2 = 0.40f, score = 0.80f, classId = 1f)

        val detections = parser.parse(output)

        assertEquals(2, detections.size)
        assertEquals(0f, detections.first().bboxNorm.x, 0.001f)
        assertEquals(1f, detections.first().bboxNorm.width, 0.001f)
        assertEquals(0.10f, detections[1].bboxNorm.x, 0.001f)
        assertEquals(0.20f, detections[1].bboxNorm.y, 0.001f)
    }

    @Test
    fun parserTreatsSlightlyOutOfRangeNormalizedCoordinatesAsNormalized() {
        val parser = YoloEndToEndOutputParser(
            inputSize = 640,
            classNameForId = TwoModelClassMap::cocoClassName,
            thresholdForClass = { 0.35f },
            allowClass = TwoModelClassMap::isAllowedCocoClass,
        )
        val output = FloatArray(300 * 6)
        row(output, row = 0, x1 = -0.01f, y1 = 0.20f, x2 = 1.01f, y2 = 0.80f, score = 0.90f, classId = 0f)

        val detections = parser.parse(output)

        assertEquals(1, detections.size)
        assertEquals(0f, detections.single().bboxNorm.x, 0.001f)
        assertEquals(0.20f, detections.single().bboxNorm.y, 0.001f)
        assertEquals(1f, detections.single().bboxNorm.width, 0.001f)
        assertEquals(0.60f, detections.single().bboxNorm.height, 0.001f)
    }

    @Test
    fun parserKeepsRawClassIdForTensorShapeInspection() {
        val parser = YoloEndToEndOutputParser(
            inputSize = 640,
            classNameForId = { null },
            thresholdForClass = { 1f },
        )
        val output = FloatArray(300 * 6)
        row(output, row = 0, x1 = 0f, y1 = 0f, x2 = 100f, y2 = 100f, score = 0.55f, classId = 7f)

        val raw = parser.parseRaw(output)

        assertEquals(1, raw.size)
        assertEquals(7, raw.single().classId)
        assertTrue(raw.single().score > 0.5f)
    }

    private fun row(
        output: FloatArray,
        row: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        score: Float,
        classId: Float,
    ) {
        val offset = row * 6
        output[offset] = x1
        output[offset + 1] = y1
        output[offset + 2] = x2
        output[offset + 3] = y2
        output[offset + 4] = score
        output[offset + 5] = classId
    }
}
