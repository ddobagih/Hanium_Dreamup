package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.inference.ArgbImage
import org.junit.Assert.*
import org.junit.Test

class UprightCameraImageTest {
    @Test fun portraitImagePixelsAreUprightAndAllRotationsRoundTrip() {
        val source = ArgbImage(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val portrait = UprightCameraImage.rotate(source, 1)
        assertEquals(2, portrait.width)
        assertEquals(3, portrait.height)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), portrait.pixels)
        for (turns in 0..3) {
            val restored = UprightCameraImage.rotate(UprightCameraImage.rotate(source, turns), (4 - turns) % 4)
            assertEquals(source.width, restored.width)
            assertEquals(source.height, restored.height)
            assertArrayEquals(source.pixels, restored.pixels)
        }
    }

    @Test fun boxesReturnToTheSameSensorRegionForScreenAndDepth() {
        val original = RectNorm(0.1f, 0.2f, 0.3f, 0.4f)
        val rotated = listOf(original, RectNorm(0.4f, 0.1f, 0.4f, 0.3f),
            RectNorm(0.6f, 0.4f, 0.3f, 0.4f), RectNorm(0.2f, 0.6f, 0.4f, 0.3f))
        for (turns in 0..3) {
            val actual = UprightCameraImage.toSensor(rotated[turns], turns)
            assertEquals(original.x, actual.x, 0.00001f)
            assertEquals(original.y, actual.y, 0.00001f)
            assertEquals(original.width, actual.width, 0.00001f)
            assertEquals(original.height, actual.height, 0.00001f)
        }
        assertEquals(1, UprightCameraImage.quarterTurns(floatArrayOf(100f, 0f, 100f, 300f, 0f, 0f)))
        assertEquals(3, UprightCameraImage.quarterTurns(floatArrayOf(0f, 300f, 0f, 0f, 100f, 300f)))
    }
}
