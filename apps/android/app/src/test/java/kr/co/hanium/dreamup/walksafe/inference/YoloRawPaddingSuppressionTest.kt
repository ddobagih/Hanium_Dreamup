package kr.co.hanium.dreamup.walksafe.inference

import java.io.File
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloRawPaddingSuppressionTest {
    @Test fun paddingCannotSuppressAnEdgeDetectionForAnyQuarterTurnOrCoordinateUnit() {
        for (turns in 0..3) for (normalized in listOf(true, false)) {
            val transform = transform(turns)
            val parser = parser(normalized)
            val edge = rotate(RectNorm(.055f, .3f, .085f, .3f), turns)
            val padding = rotate(RectNorm(.05f, .3f, .075f, .3f), turns)
            val values = tensor()
            add(values, 1, edge, .8f, normalized)
            val alone = visible(parser, values, transform)
            assertEquals("edge alone, turns=$turns, normalized=$normalized", 1, alone.size)
            add(values, 0, padding, .9f, normalized)
            assertEquals(alone, visible(parser, values, transform))
            // Before the fix this path kept the padding box, then dropped both visible results.
            assertEquals(1, parser.parse(values).size)
            assertTrue(parser.parse(values).mapNotNull(transform::modelDetectionToImageDetection).isEmpty())
            val sensor = UprightCameraImage.toSensor(alone.single().bboxNorm, turns)
            assertBox(RectNorm(0f, .3f, .02f, .3f), sensor)
        }
    }

    @Test fun threeHundredPaddingCandidatesCannotConsumeTheVisibleDetectionLimit() {
        for (turns in 0..3) for (normalized in listOf(true, false)) {
            val transform = transform(turns)
            val parser = parser(normalized)
            val values = tensor()
            for (index in 0 until 300) {
                add(values, index, rotate(RectNorm(.002f + index % 10 * .01f,
                    .002f + index / 10 * .03f, .003f, .003f), turns), .9f, normalized)
            }
            val center = rotate(RectNorm(.4f, .4f, .1f, .1f), turns)
            add(values, 300, center, .8f, normalized)
            assertEquals(300, parser.parse(values).size)
            assertTrue(parser.parse(values).mapNotNull(transform::modelDetectionToImageDetection).isEmpty())
            val result = visible(parser, values, transform).single()
            assertEquals(.8f, result.detectionConfidence, 0f)
            assertBox(requireNotNull(transform.modelDetectionToImageDetection(
                DetectionCandidate("person", .8f, center))).bboxNorm, result.bboxNorm)
        }
    }

    @Test fun paddingTouchingEitherContentEdgeHasZeroIntersectionForAllQuarterTurns() {
        for (turns in 0..3) {
            val transform = transform(turns)
            for (padding in listOf(RectNorm(.0625f, .25f, .0625f, .25f),
                RectNorm(.875f, .25f, .0625f, .25f))) {
                val rotated = rotate(padding, turns)
                val values = tensor().apply { add(this, 0, rotated, .9f, true) }
                assertFalse(transform.intersectsImageContent(rotated))
                assertTrue(parser(true).parse(values, transform).isEmpty())
                assertNull(transform.modelDetectionToImageDetection(DetectionCandidate("person", .9f, rotated)))
            }
        }
    }

    @Test fun theNearestRepresentablePartialBoxesRemainVisibleAtBothContentEdges() {
        val portrait = transform(0)
        // Binary fractions keep these one-ULP intersections present in the input box endpoints.
        val leftCrossing = RectNorm(.0625f, .25f, Math.nextUp(.125f) - .0625f, .25f)
        val rightCrossing = RectNorm(Math.nextDown(.875f), .25f, .0625f, .25f)
        for (rect in listOf(leftCrossing, rightCrossing)) {
            assertTrue(portrait.intersectsImageContent(rect))
            val visible = requireNotNull(portrait.modelDetectionToImageDetection(DetectionCandidate("person", .9f, rect)))
            assertTrue(visible.bboxNorm.width > 0f)
        }
        for (normalized in listOf(true, false)) {
            for (center in listOf(Math.nextUp(Math.nextUp(.09375f)), Math.nextDown(.90625f))) {
                val values = tensor()
                val units = if (normalized) 1f else SIZE.toFloat()
                values[0] = center * units
                values[ANCHORS] = .5f * units
                values[2 * ANCHORS] = .0625f * units
                values[3 * ANCHORS] = .25f * units
                values[4 * ANCHORS] = .9f
                assertEquals("center=$center, normalized=$normalized", 1,
                    visible(parser(normalized), values, portrait).size)
            }
        }
    }

    @Test fun visiblePartialBoxesKeepUnclippedModelCoordinatesForNms() {
        val values = tensor()
        // These boxes have the same visible intersection, but their unclipped model IoU is 1/3.
        add(values, 0, RectNorm(-.5f, .25f, .75f, .25f), .9f, true)
        add(values, 1, RectNorm(0f, .25f, .25f, .25f), .8f, true)
        val result = visible(parser(true), values, transform(0))
        assertEquals(2, result.size)
        assertBox(result[0].bboxNorm, result[1].bboxNorm)
        assertEquals(listOf(.9f, .8f), result.map { it.detectionConfidence })
    }

    @Test fun sameClassSuppressionDifferentClassOverlapAndEqualScoreOrderArePreserved() {
        val values = FloatArray(6 * ANCHORS)
        val box = RectNorm(.4f, .4f, .2f, .2f)
        add(values, 0, box, .9f, true)
        add(values, 1, box, .9f, true)
        add(values, 2, box, .8f, true, classId = 1)
        val parser = YoloRawOutputParser(SIZE, 2, { if (it == 0) "person" else "passenger_car" },
            { .15f }, normalizedCoordinates = true)
        assertEquals(listOf("person", "passenger_car"),
            parser.parse(values, transform(0)).map { it.className })
        val tied = tensor()
        add(tied, 1, RectNorm(.6f, .4f, .1f, .1f), .9f, true)
        add(tied, 0, RectNorm(.3f, .4f, .1f, .1f), .9f, true)
        val first = parser(true, maxDetections = 1).parse(tied, transform(0)).single()
        assertEquals(.3f, first.bboxNorm.x, .000001f)
    }

    @Test fun allTwentyOneConfiguredClassesKeepTheirLastAnchorAndThresholdBoundaries() {
        val config = requireNotNull(TwoModelRuntimeConfig.parse(
            File("src/main/assets/model-config/two_model_runtime.json").readText()).unifiedWalksafe)
        assertEquals(21, config.classes.size)
        val parser = YoloRawOutputParser(SIZE, 21, config::classNameForId, config::thresholdForClass,
            config::isAllowedClass, config.nmsIouThreshold, config.maxDetections, normalizedCoordinates = true)
        val transform = transform(0)
        for ((classId, name) in config.classes.withIndex()) {
            val values = FloatArray(25 * ANCHORS)
            val threshold = config.thresholdForClass(name)
            add(values, ANCHORS - 1, RectNorm(.4f, .4f, .1f, .1f), threshold, true, classId)
            assertEquals(name, parser.parse(values, transform).single().className)
            values[(4 + classId) * ANCHORS + ANCHORS - 1] = Math.nextDown(threshold)
            assertTrue(parser.parse(values, transform).isEmpty())
        }
    }

    @Test fun theVisibleLimitStillKeepsThreeHundredDetectionsAndRejectsInvalidScores() {
        val transform = transform(0)
        val parser = parser(true)
        val values = tensor()
        for (index in 0 until 301) add(values, index,
            RectNorm(.2f + index % 20 * .025f, .05f + index / 20 * .05f, .01f, .01f),
            if (index == 300) .8f else .9f, true)
        assertEquals(300, parser.parse(values, transform).size)
        assertTrue(parser.parse(values, transform).all { it.detectionConfidence == .9f })
        for (score in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f)) {
            val invalid = tensor().apply { add(this, 0, RectNorm(.4f, .4f, .1f, .1f), score, true) }
            assertTrue(parser.parse(invalid, transform).isEmpty())
        }
    }

    @Test fun squareAndOddPaddingInputsKeepVisibleBoxesAndRejectUnrelatedTransforms() {
        val processor = YuvImagePreprocessor()
        for ((width, height) in listOf(640 to 640, 481 to 640, 640 to 481)) {
            val transform = processor.preprocessBilinear(ArgbImage(width, height, IntArray(width * height)), SIZE).transform
            val values = tensor().apply { add(this, 0, RectNorm(.3f, .3f, .4f, .4f), .9f, true) }
            assertEquals(1, visible(parser(true), values, transform).size)
            if (width == height) assertEquals(parser(true).parse(values), parser(true).parse(values, transform))
            else {
                // 481 * 1.2 is 577.2, but bilinear resizing writes only 577 content pixels.
                val touchingPadding = if (width < height) RectNorm(672f / SIZE, .25f, .05f, .25f)
                    else RectNorm(.25f, 672f / SIZE, .25f, .05f)
                assertFalse(transform.intersectsImageContent(touchingPadding))
                val padding = tensor().apply { add(this, 0, touchingPadding, .9f, true) }
                assertTrue(parser(true).parse(padding, transform).isEmpty())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser(true).parse(tensor(), LetterboxTransform(640, 640, 640, 1f, 0f, 0f))
        }
    }

    private fun transform(turns: Int): LetterboxTransform {
        val image = UprightCameraImage.rotate(ArgbImage(480, 640, IntArray(480 * 640)), turns)
        return YuvImagePreprocessor().preprocessBilinear(image, SIZE).transform
    }

    private fun parser(normalized: Boolean, maxDetections: Int = 300) = YoloRawOutputParser(
        SIZE, 1, { "person" }, { .15f }, maxDetections = maxDetections, normalizedCoordinates = normalized)

    private fun tensor() = FloatArray(5 * ANCHORS)

    private fun add(values: FloatArray, index: Int, box: RectNorm, score: Float,
        normalized: Boolean, classId: Int = 0) {
        val units = if (normalized) 1f else SIZE.toFloat()
        values[index] = (box.x + box.width / 2f) * units
        values[ANCHORS + index] = (box.y + box.height / 2f) * units
        values[2 * ANCHORS + index] = box.width * units
        values[3 * ANCHORS + index] = box.height * units
        values[(4 + classId) * ANCHORS + index] = score
    }

    private fun visible(parser: YoloRawOutputParser, values: FloatArray, transform: LetterboxTransform) =
        parser.parse(values, transform).mapNotNull(transform::modelDetectionToImageDetection)

    private fun rotate(rect: RectNorm, turns: Int): RectNorm = when (turns) {
        0 -> rect
        1 -> RectNorm(1f - (rect.y + rect.height), rect.x, rect.height, rect.width)
        2 -> RectNorm(1f - (rect.x + rect.width), 1f - (rect.y + rect.height), rect.width, rect.height)
        else -> RectNorm(rect.y, 1f - (rect.x + rect.width), rect.height, rect.width)
    }

    private fun assertBox(expected: RectNorm, actual: RectNorm) {
        assertEquals(expected.x, actual.x, .000001f)
        assertEquals(expected.y, actual.y, .000001f)
        assertEquals(expected.width, actual.width, .000001f)
        assertEquals(expected.height, actual.height, .000001f)
    }

    private companion object {
        const val SIZE = 768
        const val ANCHORS = 12096
    }
}
