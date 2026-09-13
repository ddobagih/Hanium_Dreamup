package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class YoloRawCoordinateUnitsTest {
    @Test fun normalizedExportAndPixelExportProduceTheSameVisibleBox() {
        val anchors = 12096
        fun parse(normalized: Boolean) = YoloRawOutputParser(768, 1, { "person" }, { 0.15f },
            normalizedCoordinates = normalized).parse(FloatArray(5 * anchors).apply {
            val scale = if (normalized) 1f else 768f
            this[0] = 0.5f * scale
            this[anchors] = 0.5f * scale
            this[2 * anchors] = 0.4f * scale
            this[3 * anchors] = 0.6f * scale
            this[4 * anchors] = 0.9f
        }).single().bboxNorm
        val normalized = parse(true)
        val pixels = parse(false)
        assertEquals(0.3f, normalized.x, 0.00001f)
        assertEquals(0.2f, normalized.y, 0.00001f)
        assertEquals(0.4f, normalized.width, 0.00001f)
        assertEquals(0.6f, normalized.height, 0.00001f)
        assertEquals(pixels.x, normalized.x, 0.00001f)
        assertEquals(pixels.y, normalized.y, 0.00001f)
        assertEquals(pixels.width, normalized.width, 0.00001f)
        assertEquals(pixels.height, normalized.height, 0.00001f)
    }
}
