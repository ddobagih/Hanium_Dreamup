package kr.co.hanium.dreamup.walksafe.ui

import org.junit.Assert.*
import org.junit.Test

class NativeOverlayInsetsTest {
    @Test fun changingDensityIsReadAtEachOriginalBottomHorizontalTopStep() {
        val values = listOf(1.24f, 1.26f, 1.28f)
        var reads = 0
        val result = NativeOverlayInsets.read(20f, 20f, 20f) { values[reads++] }
        assertEquals(NativeOverlayInsets(bottom = 24, horizontal = 25, top = 26), result)
        assertEquals(3, reads)
    }

    @Test fun bottomTruncationRemainsDistinctFromTheOtherRoundedEdges() {
        val result = NativeOverlayInsets.read(20f, 20f, 20f) { 1.38f }
        assertEquals(27, result.bottom)
        assertEquals(28, result.horizontal)
        assertEquals(28, result.top)
    }

    @Test fun failedSecondSdkReadDoesNotPerformTheThirdRead() {
        var reads = 0
        try {
            NativeOverlayInsets.read(20f, 20f, 20f) {
                reads++
                if (reads == 2) throw IllegalStateException("resources changed")
                1.38f
            }
            fail("Expected SDK read failure")
        } catch (expected: IllegalStateException) {
            assertEquals("resources changed", expected.message)
        }
        assertEquals(2, reads)
    }
}
