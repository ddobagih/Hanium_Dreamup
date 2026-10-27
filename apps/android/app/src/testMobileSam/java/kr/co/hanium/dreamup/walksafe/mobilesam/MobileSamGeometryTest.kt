package kr.co.hanium.dreamup.walksafe.mobilesam

import org.junit.Assert.*
import org.junit.Test

class MobileSamGeometryTest {
    @Test fun `box scaling and padding restore keep the original photo coordinates`() {
        val box = SamBox(0.25f, 0.1f, 0.75f, 0.9f, "manual")
        assertArrayEquals(floatArrayOf(256f, 51.2f, 768f, 460.8f),
            MobileSamGeometry.prompt(box, 1000, 500), 0.001f)
        assertEquals(1024 to 577, MobileSamGeometry.resizedShape(1001, 564))
        // Right/bottom padding is positive, real image negative: padding must never leak in.
        val landscape = floatArrayOf(-1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f,
            100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f)
        assertFalse(MobileSamGeometry.restoreMask(landscape, 8, 4, 4).any { it })
        val portrait = FloatArray(16) { if (it % 4 < 2) -1f else 100f }
        assertFalse(MobileSamGeometry.restoreMask(portrait, 4, 8, 4).any { it })
        // Interpolate logits, not an already thresholded binary mask.
        assertArrayEquals(booleanArrayOf(false, true, true),
            MobileSamGeometry.restoreMask(floatArrayOf(-1f, 9f, 50f, 50f), 3, 1, 2))
    }

    @Test(expected = IllegalArgumentException::class) fun `reject inverted boxes`() {
        SamBox(0.8f, 0f, 0.2f, 1f, "invalid")
    }
}
