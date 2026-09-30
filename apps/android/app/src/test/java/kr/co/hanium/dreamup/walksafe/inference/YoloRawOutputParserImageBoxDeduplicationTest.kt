package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloRawOutputParserImageBoxDeduplicationTest {
    @Test
    fun nearIdenticalPortraitImageBoxesKeepOnlyTheHigherScore() {
        for (normalized in listOf(false, true)) {
            val parser = parser(normalized)
            val values = tensor(nearCollisionBoxes(), normalized)
            val beforeMapping = parser.parse(values)
            assertEquals(2, beforeMapping.size)
            assertEquals(0.56245f, boxIou(beforeMapping[0].bboxNorm, beforeMapping[1].bboxNorm), 0.0001f)
            val firstImage = requireNotNull(portraitTransform.modelDetectionToImageDetection(beforeMapping[0])).bboxNorm
            val secondImage = requireNotNull(portraitTransform.modelDetectionToImageDetection(beforeMapping[1])).bboxNorm
            assertTrue(firstImage.x != secondImage.x)
            assertTrue(boxIou(firstImage, secondImage) >= 0.999f)

            val result = parser.parse(values, portraitTransform)

            assertEquals(1, result.size)
            assertEquals(0.9f, result.single().detectionConfidence, 0f)
            assertCandidate(beforeMapping.first(), result.single())
        }
    }

    @Test
    fun nearIdenticalPortraitImageBoxesWithDifferentClassesArePreserved() {
        val boxes = nearCollisionBoxes().let { listOf(it[0], it[1].copy(classId = 1)) }
        for (normalized in listOf(false, true)) {
            val result = parser(normalized).parse(tensor(boxes, normalized), portraitTransform)

            assertEquals(listOf("person", "bicycle"), result.map { it.className })
        }
    }

    @Test
    fun exactImageDuplicatesKeepHighestConfidenceForPixelAndNormalizedOutputs() {
        for (normalized in listOf(false, true)) {
            val boxes = collisionBoxes()
            val values = tensor(boxes, normalized)
            val parser = parser(normalized)
            val beforeMapping = parser.parse(values)
            assertEquals(2, beforeMapping.size)
            val firstImage = requireNotNull(transform.modelDetectionToImageDetection(beforeMapping[0]))
            val secondImage = requireNotNull(transform.modelDetectionToImageDetection(beforeMapping[1]))
            assertBox(firstImage.bboxNorm, 0.15625f, 0f, 0.3125f, 1f)
            assertBox(secondImage.bboxNorm, 0.15625f, 0f, 0.3125f, 1f)

            val result = parser.parse(values, transform)

            assertEquals(1, result.size)
            assertEquals("person", result.single().className)
            assertEquals(0.9f, result.single().detectionConfidence, 0f)
            assertBox(result.single().bboxNorm, 0.15625f, 0f, 0.3125f, 0.6875f)
        }
    }

    @Test
    fun sameClassImageBoxesDifferingInAnyComponentAreNotMerged() {
        val first = RawBox(100f, 0f, 300f, 300f, 0.9f)
        val alternatives = listOf(
            RawBox(101f, 200f, 301f, 300f, 0.8f),
            RawBox(100f, 210f, 300f, 310f, 0.8f),
            RawBox(100f, 200f, 301f, 300f, 0.8f),
            RawBox(100f, 200f, 300f, 310f, 0.8f),
        )
        for (normalized in listOf(false, true)) {
            for (second in alternatives) {
                val result = parser(normalized).parse(tensor(listOf(first, second), normalized), transform)

                assertEquals(2, result.size)
                val firstImage = requireNotNull(transform.modelDetectionToImageDetection(result[0])).bboxNorm
                val secondImage = requireNotNull(transform.modelDetectionToImageDetection(result[1])).bboxNorm
                assertTrue(
                    firstImage.x != secondImage.x || firstImage.y != secondImage.y ||
                        firstImage.width != secondImage.width || firstImage.height != secondImage.height,
                )
            }
        }
    }

    @Test
    fun differentClassesWithTheSameImageBoxArePreserved() {
        for (normalized in listOf(false, true)) {
            val boxes = collisionBoxes().let { listOf(it[0], it[1].copy(classId = 1)) }
            val result = parser(normalized).parse(tensor(boxes, normalized), transform)

            assertEquals(listOf("person", "bicycle"), result.map { it.className })
            for (candidate in result) {
                assertBox(
                    requireNotNull(transform.modelDetectionToImageDetection(candidate)).bboxNorm,
                    0.15625f, 0f, 0.3125f, 1f,
                )
            }
        }
    }

    @Test
    fun distinctClassIdsArePreservedEvenWhenTheCallbackReturnsTheSameLabel() {
        val boxes = collisionBoxes().let { listOf(it[0], it[1].copy(classId = 1)) }
        val result = parser(normalized = false, labels = listOf("shared", "shared"))
            .parse(tensor(boxes, normalized = false), transform)

        assertEquals(2, result.size)
        assertEquals(listOf("shared", "shared"), result.map { it.className })
    }

    @Test
    fun equalConfidenceImageDuplicatesKeepTheFirstAnchorInEitherGeometryOrder() {
        for (normalized in listOf(false, true)) {
            val tied = collisionBoxes().map { it.copy(score = 0.9f) }
            for (boxes in listOf(tied, tied.reversed())) {
                val parser = parser(normalized)
                val values = tensor(boxes, normalized)
                val originalFirst = parser.parse(values).first()

                val result = parser.parse(values, transform)

                assertEquals(1, result.size)
                assertCandidate(originalFirst, result.single())
            }
        }
    }

    @Test
    fun absentTransformKeepsBothOriginalModelBoxes() {
        for (normalized in listOf(false, true)) {
            val result = parser(normalized).parse(tensor(collisionBoxes(), normalized), transform = null)

            assertEquals(2, result.size)
            assertEquals(listOf(0.9f, 0.8f), result.map { it.detectionConfidence })
            assertBox(result[0].bboxNorm, 0.15625f, 0f, 0.3125f, 0.6875f)
            assertBox(result[1].bboxNorm, 0.15625f, 0.3125f, 0.3125f, 0.6875f)
        }
    }

    @Test
    fun nullImageMappingsKeepOriginalModelDetections() {
        // An adversarial transform exercises the existing null-mapping contract, not a
        // production preprocessing configuration or a replacement rejection threshold.
        val unmappable = LetterboxTransform(
            imageWidth = 640,
            imageHeight = 240,
            modelSize = 640,
            scale = Float.POSITIVE_INFINITY,
            padX = 0f,
            padY = 0f,
            resizedWidth = 640,
            resizedHeight = 240,
        )
        for (normalized in listOf(false, true)) {
            val parser = parser(normalized)
            val values = tensor(collisionBoxes(), normalized)
            val original = parser.parse(values)
            for (candidate in original) {
                assertTrue(unmappable.intersectsImageContent(candidate.bboxNorm))
                assertNull(unmappable.modelDetectionToImageDetection(candidate))
            }

            val result = parser.parse(values, unmappable)

            assertEquals(original.size, result.size)
            original.zip(result).forEach { (expected, actual) -> assertCandidate(expected, actual) }
        }
    }

    @Test
    fun existingModelSpaceNmsStillSuppressesOverlappingNonidenticalImageBoxes() {
        val boxes = listOf(
            RawBox(100f, 0f, 300f, 400f, 0.8f),
            RawBox(100f, 20f, 300f, 420f, 0.9f),
        )
        for (normalized in listOf(false, true)) {
            val parser = parser(normalized)
            val firstOnly = parser.parse(tensor(listOf(boxes[0]), normalized), transform).single()
            val secondOnly = parser.parse(tensor(listOf(boxes[1]), normalized), transform).single()
            val firstImage = requireNotNull(transform.modelDetectionToImageDetection(firstOnly)).bboxNorm
            val secondImage = requireNotNull(transform.modelDetectionToImageDetection(secondOnly)).bboxNorm
            assertTrue(firstImage.height != secondImage.height)

            val result = parser.parse(tensor(boxes, normalized), transform)

            assertEquals(1, result.size)
            assertCandidate(secondOnly, result.single())
        }
    }

    @Test
    fun cappedDuplicatesAreNotRefilledAndLargerCapRetainsTheNextSelectedCandidate() {
        val third = RawBox(400f, 250f, 500f, 350f, 0.7f)
        for (normalized in listOf(false, true)) {
            val values = tensor(collisionBoxes() + third, normalized)
            val capTwo = parser(normalized, maxDetections = 2).parse(values, transform)
            val capThree = parser(normalized, maxDetections = 3).parse(values, transform)

            assertEquals(1, capTwo.size)
            assertEquals(listOf(0.9f), capTwo.map { it.detectionConfidence })
            assertEquals(2, capThree.size)
            assertEquals(listOf(0.9f, 0.7f), capThree.map { it.detectionConfidence })
            assertCandidate(capTwo.single(), capThree.first())
            assertBox(capThree.last().bboxNorm, 0.625f, 0.390625f, 0.15625f, 0.15625f)
        }
    }

    @Test
    fun deduplicationUsesImageCoordinatesOnlyAsKeysAndReturnsModelCoordinates() {
        for (normalized in listOf(false, true)) {
            val boxes = collisionBoxes().let { listOf(it[0].copy(score = 0.8f), it[1].copy(score = 0.9f)) }
            val result = parser(normalized).parse(tensor(boxes, normalized), transform).single()

            assertBox(result.bboxNorm, 0.15625f, 0.3125f, 0.3125f, 0.6875f)
            assertEquals(0.9f, result.detectionConfidence, 0f)
            val imageResult = requireNotNull(transform.modelDetectionToImageDetection(result))
            assertBox(imageResult.bboxNorm, 0.15625f, 0f, 0.3125f, 1f)
        }
    }

    private fun collisionBoxes() = listOf(
        RawBox(100f, 0f, 300f, 440f, 0.9f),
        RawBox(100f, 200f, 300f, 640f, 0.8f),
    )

    private fun nearCollisionBoxes() = listOf(
        RawBox(0f, 100f, 500f, 300f, 0.9f),
        RawBox(140.032f, 100f, 640f, 300f, 0.8f),
    )

    private fun parser(
        normalized: Boolean,
        maxDetections: Int = 300,
        labels: List<String> = listOf("person", "bicycle"),
    ) = YoloRawOutputParser(
        inputSize = 640,
        classCount = labels.size,
        classNameForId = labels::getOrNull,
        thresholdForClass = { 0.25f },
        maxDetections = maxDetections,
        normalizedCoordinates = normalized,
    )

    private fun tensor(boxes: List<RawBox>, normalized: Boolean): FloatArray {
        val anchors = listOf(8, 16, 32).sumOf { stride -> (640 / stride) * (640 / stride) }
        val values = FloatArray((4 + 2) * anchors)
        val divisor = if (normalized) 640f else 1f
        boxes.forEachIndexed { anchor, box ->
            values[anchor] = ((box.left + box.right) / 2f) / divisor
            values[anchors + anchor] = ((box.top + box.bottom) / 2f) / divisor
            values[2 * anchors + anchor] = (box.right - box.left) / divisor
            values[3 * anchors + anchor] = (box.bottom - box.top) / divisor
            values[(4 + box.classId) * anchors + anchor] = box.score
        }
        return values
    }

    private fun assertCandidate(expected: DetectionCandidate, actual: DetectionCandidate) {
        assertEquals(expected.className, actual.className)
        assertEquals(expected.detectionConfidence, actual.detectionConfidence, 0f)
        assertBox(
            actual.bboxNorm,
            expected.bboxNorm.x, expected.bboxNorm.y, expected.bboxNorm.width, expected.bboxNorm.height,
        )
    }

    private fun assertBox(actual: RectNorm, x: Float, y: Float, width: Float, height: Float) {
        assertEquals(x, actual.x, 0f)
        assertEquals(y, actual.y, 0f)
        assertEquals(width, actual.width, 0f)
        assertEquals(height, actual.height, 0f)
    }

    private fun boxIou(a: RectNorm, b: RectNorm): Float {
        val overlap = (minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)).coerceAtLeast(0f) *
            (minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        return overlap / (a.area + b.area - overlap)
    }

    private data class RawBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float,
        val classId: Int = 0,
    )

    private val transform = LetterboxTransform(
        imageWidth = 640,
        imageHeight = 240,
        modelSize = 640,
        scale = 1f,
        padX = 0f,
        padY = 200f,
    )

    private val portraitTransform = LetterboxTransform(
        imageWidth = 360,
        imageHeight = 640,
        modelSize = 640,
        scale = 1f,
        padX = 140f,
        padY = 0f,
    )
}
