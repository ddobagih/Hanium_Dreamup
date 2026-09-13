package kr.co.hanium.dreamup.walksafe.inference

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BilinearLetterboxTest {
    @Test fun matchesOpenCvReferenceIncludingOddPadding() {
        val json = JSONObject(javaClass.getResourceAsStream("/bilinear-letterbox-opencv-reference.json")!!
            .bufferedReader().use { it.readText() })
        val pixels = json.getJSONArray("argb")
        val image = ArgbImage(json.getInt("width"), json.getInt("height"),
            IntArray(pixels.length()) { pixels.getLong(it).toInt() })
        val input = YuvImagePreprocessor().preprocessBilinear(image, json.getInt("size"))
        val expected = json.getJSONArray("rgb")
        for (index in 0 until expected.length()) {
            assertEquals("channel $index", expected.getInt(index) / 255f, input.inputBuffer.float, 0.000001f)
        }
        assertEquals(0f, input.transform.padX, 0f)
        assertEquals(1f, input.transform.padY, 0f)
    }
}
