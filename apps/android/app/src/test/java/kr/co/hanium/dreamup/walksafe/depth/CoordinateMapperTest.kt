package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {
    @Test
    fun modelToImageAppliesRotationAndMirrorInModelInputSpace() {
        val modelPoint = Point2(0.2f, 0.7f)

        assertPointEquals(Point2(0.2f, 0.7f), mapper(rotationDeg = 0, mirrored = false).modelToImage(modelPoint))
        assertPointEquals(Point2(0.8f, 0.7f), mapper(rotationDeg = 0, mirrored = true).modelToImage(modelPoint))
        assertPointEquals(Point2(0.3f, 0.2f), mapper(rotationDeg = 90, mirrored = false).modelToImage(modelPoint))
        assertPointEquals(Point2(0.8f, 0.3f), mapper(rotationDeg = 180, mirrored = false).modelToImage(modelPoint))
        assertPointEquals(Point2(0.7f, 0.8f), mapper(rotationDeg = 270, mirrored = false).modelToImage(modelPoint))
        assertPointEquals(Point2(0.3f, 0.8f), mapper(rotationDeg = 90, mirrored = true).modelToImage(modelPoint))
    }

    @Test
    fun imageToModelRoundTripsRotationAndMirrorCases() {
        val modelPoints = listOf(
            Point2(0.2f, 0.7f),
            Point2(0.4f, 0.3f),
        )

        for (rotationDeg in listOf(0, 90, 180, 270, -90)) {
            for (mirrored in listOf(false, true)) {
                val mapper = mapper(rotationDeg = rotationDeg, mirrored = mirrored)
                for (modelPoint in modelPoints) {
                    val imagePoint = mapper.modelToImage(modelPoint)
                    assertPointEquals(modelPoint, mapper.imageToModel(imagePoint))
                }
            }
        }
    }

    private fun mapper(rotationDeg: Int, mirrored: Boolean): LetterboxCoordinateMapper {
        return LetterboxCoordinateMapper(
            transform = ModelInputTransform(
                imageWidth = 100,
                imageHeight = 100,
                modelWidth = 100,
                modelHeight = 100,
                scale = 1f,
                padX = 0f,
                padY = 0f,
                rotationDeg = rotationDeg,
                mirrored = mirrored,
            ),
            depthSize = ImageSize(10, 10),
        )
    }

    private fun assertPointEquals(expected: Point2, actual: Point2) {
        assertEquals(expected.x, actual.x, 0.0001f)
        assertEquals(expected.y, actual.y, 0.0001f)
    }
}
