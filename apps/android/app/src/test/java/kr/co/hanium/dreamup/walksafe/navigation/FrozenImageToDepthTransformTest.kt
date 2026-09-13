package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.Point2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrozenImageToDepthTransformTest {
    @Test
    fun pixelMatrixPreservesOutsideCropAndPixelCenterCoordinates() {
        val transform = requireNotNull(create(listOf(
            Point2(-0.25f, 0f), Point2(1.25f, 0f), Point2(-0.25f, 1f), Point2(1.25f, 1f),
        )))
        val matrix = requireNotNull(transform.imagePixelsToDepthUvMatrix(640, 480))
        assertEquals(-0.25, matrix[2], 0.0)
        assertEquals(1.25, matrix[0] * 640 + matrix[2], 0.0)
        assertEquals(0.125, matrix[0] * 160 + matrix[1] * 360 + matrix[2], 0.000001)
        assertEquals(0.75, matrix[3] * 160 + matrix[4] * 360 + matrix[5], 0.000001)
        assertEquals(-0.25 + 0.5 * 1.5 / 640, matrix[0] * 0.5 + matrix[2], 0.000001)
        matrix[2] = 999.0
        assertEquals(-0.25, requireNotNull(transform.imagePixelsToDepthUvMatrix(640, 480))[2], 0.0)
        assertNull(transform.imagePixelsToDepthUvMatrix(0, 480))
        assertNull(transform.imagePixelsToDepthUvMatrix(640, -1))
    }

    @Test
    fun pixelMatrixRetainsRotationAndReflection() {
        val transform = requireNotNull(create(listOf(
            Point2(0f, 1.25f), Point2(0f, -0.25f), Point2(1f, 1.25f), Point2(1f, -0.25f),
        )))
        val matrix = requireNotNull(transform.imagePixelsToDepthUvMatrix(640, 480))
        assertEquals(0.75, matrix[0] * 160 + matrix[1] * 360 + matrix[2], 0.000001)
        assertEquals(0.875, matrix[3] * 160 + matrix[4] * 360 + matrix[5], 0.000001)
        assertEquals(0.0, matrix[6], 0.0)
        assertEquals(0.0, matrix[7], 0.0)
        assertEquals(1.0, matrix[8], 0.0)
    }

    @Test
    fun horizontalDepthCropKeepsTheTransformAndRejectsOnlyOutOfDepthPoints() {
        val transform = requireNotNull(create(listOf(
            Point2(-0.25f, 0f), Point2(1.25f, 0f), Point2(-0.25f, 1f), Point2(1.25f, 1f),
        )))
        assertPoint(0.125f, 0.75f, transform.map(Point2(0.25f, 0.75f)))
        assertPoint(0.875f, 0.25f, transform.map(Point2(0.75f, 0.25f)))
        assertNull(transform.map(Point2(0f, 0.5f)))
        assertNull(transform.map(Point2(1f, 0.5f)))
    }

    @Test
    fun rotatedAndMirroredCropsRemainValid() {
        val rotated = requireNotNull(create(listOf(
            Point2(0f, 1.25f), Point2(0f, -0.25f), Point2(1f, 1.25f), Point2(1f, -0.25f),
        )))
        assertPoint(0.75f, 0.875f, rotated.map(Point2(0.25f, 0.75f)))
        assertNull(rotated.map(Point2(0f, 0.5f)))
        val mirrored = requireNotNull(create(listOf(
            Point2(1.25f, 0f), Point2(-0.25f, 0f), Point2(1.25f, 1f), Point2(-0.25f, 1f),
        )))
        assertPoint(0.875f, 0.75f, mirrored.map(Point2(0.25f, 0.75f)))
    }

    @Test
    fun anOffDepthImageCenterDoesNotDiscardAValidPartialOverlap() {
        val transform = requireNotNull(create(
            listOf(Point2(0.75f, 0f), Point2(1.75f, 0f), Point2(0.75f, 1f), Point2(1.75f, 1f)),
            center = Point2(1.25f, 0.5f),
        ))
        assertPoint(0.875f, 0.5f, transform.map(Point2(0.125f, 0.5f)))
        assertNull(transform.map(Point2(0.5f, 0.5f)))
    }

    @Test
    fun finiteAffineShearIsAcceptedWithoutRequiringAnAxisAlignedCrop() {
        val transform = requireNotNull(create(
            listOf(Point2(-0.2f, 0.1f), Point2(1f, 0.2f), Point2(-0.05f, 1f), Point2(1.15f, 1.1f)),
            center = Point2(0.475f, 0.6f),
        ))
        assertPoint(0.475f, 0.6f, transform.map(Point2(0.5f, 0.5f)))
    }

    @Test
    fun collapsedFoldedAndNonAffineTransformsAreRejectedEvenWhenTheirCentersAgree() {
        assertNull(create(List(4) { Point2(0.5f, 0.5f) }))
        assertNull(create(listOf(Point2(0f, 0f), Point2(0.5f, 0.5f), Point2(0.5f, 0.5f), Point2(1f, 1f))))
        assertNull(create(listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(1f, 1f), Point2(0f, 1f))))
        assertNull(create(
            listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(0.8f, 1f)),
            center = Point2(0.45f, 0.5f),
        ))
        assertNull(create(identityCorners, center = Point2(0.6f, 0.5f)))
    }

    @Test
    fun invalidCaptureAndNonFiniteCoordinatesRemainRejected() {
        assertNull(FrozenImageToDepthTransform.create(0L, identityCorners, Point2(0.5f, 0.5f)))
        assertNull(create(identityCorners.take(3)))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertNull(create(identityCorners.toMutableList().also { it[0] = Point2(invalid, 0f) }))
            assertNull(create(identityCorners, center = Point2(0.5f, invalid)))
        }
        val transform = requireNotNull(create(identityCorners))
        listOf(Point2(-0.01f, 0.5f), Point2(1.01f, 0.5f), Point2(Float.NaN, 0.5f)).forEach {
            assertNull(transform.map(it))
        }
    }

    private val identityCorners = listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f))

    private fun create(corners: List<Point2>, center: Point2 = Point2(0.5f, 0.5f)) =
        FrozenImageToDepthTransform.create(123L, corners, center)

    private fun assertPoint(x: Float, y: Float, actual: Point2?) {
        assertNotNull(actual)
        assertEquals(x, requireNotNull(actual).x, 0.0001f)
        assertEquals(y, actual.y, 0.0001f)
    }
}
