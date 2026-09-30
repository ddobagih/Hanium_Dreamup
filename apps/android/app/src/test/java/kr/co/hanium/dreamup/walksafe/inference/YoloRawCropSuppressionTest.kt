package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloRawCropSuppressionTest {
    @Test
    fun highScoreExteriorCannotSuppressLowerScoreCrossingBoxAtAnyRotation() {
        for ((turns, orientation) in orientations.withIndex()) for (normalized in listOf(false, true)) {
            val crop = sourceCrop.toUpright(SOURCE_WIDTH, SOURCE_HEIGHT, turns)
            assertEquals("turns=$turns", orientation.crop, crop)
            val values = tensor()
            val left = orientation.modelLeft
            val top = orientation.modelTop
            add(values, 0, left - 100f, top + 40f, left, top + 100f, .95f, normalized)
            add(values, 1, left - 95f, top + 40f, left + 5f, top + 100f, .8f, normalized)

            assertEquals(.95f, parser(normalized).parse(values, orientation.transform).single().detectionConfidence)
            val result = parser(normalized).parse(values, orientation.transform, crop)
            assertEquals("turns=$turns, normalized=$normalized", 1, result.size)
            assertEquals(.8f, result.single().detectionConfidence)
            assertBox(RectNorm((left - 95f) / SIZE, (top + 40f) / SIZE, 100f / SIZE, 60f / SIZE),
                result.single().bboxNorm)
        }
    }

    @Test
    fun threeHundredExteriorCandidatesCannotConsumeTheCapBeforeAValidBox() {
        for ((turns, orientation) in orientations.withIndex()) for (normalized in listOf(false, true)) {
            val values = tensor()
            for (index in 0 until 300) {
                val x = 90f + index % 10 * 6f
                val y = 100f + index / 10 * 14f
                add(values, index, x, y, x + 4f, y + 4f, .9f, normalized)
            }
            val left = orientation.modelLeft
            val top = orientation.modelTop
            add(values, 300, left + 20f, top + 20f, left + 40f, top + 40f, .8f, normalized)

            val parser = parser(normalized)
            assertEquals(300, parser.parse(values, orientation.transform).size)
            val result = parser.parse(values, orientation.transform,
                sourceCrop.toUpright(SOURCE_WIDTH, SOURCE_HEIGHT, turns))
            assertEquals(1, result.size)
            assertEquals(.8f, result.single().detectionConfidence)
            assertBox(RectNorm((left + 20f) / SIZE, (top + 20f) / SIZE, 20f / SIZE, 20f / SIZE),
                result.single().bboxNorm)
        }
    }

    @Test
    fun touchingEveryCropBoundaryIsNotEvidenceButOnePixelCrossingIs() {
        for ((turns, orientation) in orientations.withIndex()) {
            val crop = sourceCrop.toUpright(SOURCE_WIDTH, SOURCE_HEIGHT, turns)
            val left = orientation.modelLeft
            val top = orientation.modelTop
            val right = orientation.modelRight
            val bottom = orientation.modelBottom
            val touching = listOf(
                floatArrayOf(left - 20f, top + 20f, left, top + 40f),
                floatArrayOf(right, top + 20f, right + 20f, top + 40f),
                floatArrayOf(left + 20f, top - 20f, left + 40f, top),
                floatArrayOf(left + 20f, bottom, left + 40f, bottom + 20f),
            )
            val crossing = listOf(
                floatArrayOf(left - 20f, top + 20f, left + 1f, top + 40f),
                floatArrayOf(right - 1f, top + 20f, right + 20f, top + 40f),
                floatArrayOf(left + 20f, top - 20f, left + 40f, top + 1f),
                floatArrayOf(left + 20f, bottom - 1f, left + 40f, bottom + 20f),
            )
            touching.forEach { box ->
                assertTrue("touching, turns=$turns", parseOne(box, orientation.transform, crop).isEmpty())
            }
            crossing.forEach { box ->
                assertEquals("crossing, turns=$turns", 1, parseOne(box, orientation.transform, crop).size)
            }
        }
    }

    @Test
    fun fullCropKeepsTheDefaultParserBehaviorAtEveryRotation() {
        for ((turns, orientation) in orientations.withIndex()) {
            val full = YuvCropRect.full(SOURCE_WIDTH, SOURCE_HEIGHT)
                .toUpright(SOURCE_WIDTH, SOURCE_HEIGHT, turns)
            assertTrue(full.isFull(orientation.transform.imageWidth, orientation.transform.imageHeight))
            val values = tensor().apply {
                add(this, 0, 100f, 200f, 130f, 230f, .9f, false)
                add(this, 1, 300f, 300f, 330f, 330f, .8f, false)
            }
            val parser = parser(false)
            assertEquals(parser.parse(values, orientation.transform),
                parser.parse(values, orientation.transform, full))
        }
    }

    private fun parseOne(box: FloatArray, transform: LetterboxTransform, crop: YuvCropRect) =
        parser(false).parse(tensor().apply {
            add(this, 0, box[0], box[1], box[2], box[3], .9f, false)
        }, transform, crop)

    private fun parser(normalized: Boolean) = YoloRawOutputParser(
        SIZE, 1, { "person" }, { .15f }, normalizedCoordinates = normalized,
    )

    private fun tensor() = FloatArray(5 * ANCHORS)

    private fun add(values: FloatArray, anchor: Int, left: Float, top: Float, right: Float,
        bottom: Float, score: Float, normalized: Boolean) {
        val divisor = if (normalized) SIZE.toFloat() else 1f
        values[anchor] = (left + right) / 2f / divisor
        values[ANCHORS + anchor] = (top + bottom) / 2f / divisor
        values[2 * ANCHORS + anchor] = (right - left) / divisor
        values[3 * ANCHORS + anchor] = (bottom - top) / divisor
        values[4 * ANCHORS + anchor] = score
    }

    private fun assertBox(expected: RectNorm, actual: RectNorm) {
        assertEquals(expected.x, actual.x, .000001f)
        assertEquals(expected.y, actual.y, .000001f)
        assertEquals(expected.width, actual.width, .000001f)
        assertEquals(expected.height, actual.height, .000001f)
    }

    private data class Orientation(
        val transform: LetterboxTransform,
        val crop: YuvCropRect,
        val modelLeft: Float,
        val modelTop: Float,
        val modelRight: Float,
        val modelBottom: Float,
    )

    private val sourceCrop = YuvCropRect(160, 80, 480, 320)
    // Literal crop/model coordinates are the oracle; no production inverse transform is used.
    private val orientations = listOf(
        Orientation(LetterboxTransform(640, 480, SIZE, 1f, 0f, 80f),
            YuvCropRect(160, 80, 480, 320), 160f, 160f, 480f, 400f),
        Orientation(LetterboxTransform(480, 640, SIZE, 1f, 80f, 0f),
            YuvCropRect(160, 160, 400, 480), 240f, 160f, 480f, 480f),
        Orientation(LetterboxTransform(640, 480, SIZE, 1f, 0f, 80f),
            YuvCropRect(160, 160, 480, 400), 160f, 240f, 480f, 480f),
        Orientation(LetterboxTransform(480, 640, SIZE, 1f, 80f, 0f),
            YuvCropRect(80, 160, 320, 480), 160f, 160f, 400f, 480f),
    )

    private companion object {
        const val SOURCE_WIDTH = 640
        const val SOURCE_HEIGHT = 480
        const val SIZE = 640
        const val ANCHORS = 8400
    }
}
