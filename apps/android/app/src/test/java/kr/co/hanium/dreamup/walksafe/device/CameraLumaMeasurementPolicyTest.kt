package kr.co.hanium.dreamup.walksafe.device

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraLumaMeasurementPolicyTest {
    @Test
    fun `measures brightness and near-black fraction from packed plane`() {
        val result = CameraLumaMeasurementPolicy.measure(
            buffer = ByteBuffer.wrap(byteArrayOf(0, 10, 100, 255.toByte())),
            width = 2,
            height = 2,
            rowStride = 2,
            pixelStride = 1,
        )

        requireNotNull(result)
        assertEquals(365.0 / (4.0 * 255.0), result.normalizedBrightness, 0.000_001)
        assertEquals(0.5, result.darkOrOccludedFraction, 0.000_001)
    }

    @Test
    fun `honors row and pixel stride without reading padding`() {
        val plane = byteArrayOf(
            51, 99, 102, 99, 0, 0,
            153.toByte(), 99, 204.toByte(), 99, 0, 0,
        )

        val result = CameraLumaMeasurementPolicy.measure(
            buffer = ByteBuffer.wrap(plane),
            width = 2,
            height = 2,
            rowStride = 6,
            pixelStride = 2,
        )

        requireNotNull(result)
        assertEquals(0.5, result.normalizedBrightness, 0.000_001)
        assertEquals(0.0, result.darkOrOccludedFraction, 0.000_001)
    }

    @Test
    fun `respects a non-zero buffer position`() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99, 99, 0, 255.toByte()))
        buffer.position(2)

        val result = CameraLumaMeasurementPolicy.measure(
            buffer = buffer,
            width = 2,
            height = 1,
            rowStride = 2,
            pixelStride = 1,
        )

        requireNotNull(result)
        assertEquals(0.5, result.normalizedBrightness, 0.000_001)
        assertEquals(0.5, result.darkOrOccludedFraction, 0.000_001)
    }

    @Test
    fun `rejects invalid geometry or a truncated plane`() {
        assertNull(
            CameraLumaMeasurementPolicy.measure(
                buffer = ByteBuffer.allocate(1),
                width = 0,
                height = 1,
                rowStride = 1,
                pixelStride = 1,
            ),
        )
        assertNull(
            CameraLumaMeasurementPolicy.measure(
                buffer = ByteBuffer.allocate(3),
                width = 2,
                height = 2,
                rowStride = 2,
                pixelStride = 1,
            ),
        )
    }
}
