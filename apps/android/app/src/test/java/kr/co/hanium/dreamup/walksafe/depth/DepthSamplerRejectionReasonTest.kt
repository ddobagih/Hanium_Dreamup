package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-8: a missing measurement, a too-near/too-far value and low confidence are different gaps. */
class DepthSamplerRejectionReasonTest {
    private val roi = listOf(Point2(0.1f, 0.1f), Point2(0.9f, 0.1f), Point2(0.9f, 0.9f), Point2(0.1f, 0.9f))

    private fun stats(mm: Int, confidence: Int = 255): DepthStats = DepthSampler().sampleObjectDepth(
        depthImage = DepthImage16(20, 20, IntArray(20 * 20) { mm }),
        confidenceImage = ConfidenceImage8(20, 20, ByteArray(20 * 20) { confidence.toByte() }),
        polygonDepthNorm = roi,
    )

    @Test
    fun zeroTooNearTooFarAndLowConfidenceRegionsRemainDistinguishable() {
        val zero = stats(0)
        val tooNear = stats(100)
        val tooFar = stats(9_000)
        val lowConfidence = stats(1_000, confidence = 10)

        listOf(zero, tooNear, tooFar, lowConfidence).forEach {
            assertNull(it.medianM)
            assertEquals(0, it.validSampleCount)
        }
        assertEquals(4, setOf(zero, tooNear, tooFar, lowConfidence).size)

        val candidates = zero.rejectedSamples.invalidZero
        assertTrue(candidates > 0)
        // A zero pixel is missing depth, never evidence of a very near object.
        assertEquals(DepthSampleRejections(invalidZero = candidates), zero.rejectedSamples)
        assertEquals(DepthSampleRejections(tooNear = candidates), tooNear.rejectedSamples)
        assertEquals(DepthSampleRejections(tooFar = candidates), tooFar.rejectedSamples)
        assertEquals(DepthSampleRejections(lowConfidence = candidates), lowConfidence.rejectedSamples)
    }

    @Test
    fun lowConfidenceNearNoiseIsNotCountedAsTooNearEvidence() {
        val noisyNear = stats(100, confidence = 10)
        assertNull(noisyNear.medianM)
        assertEquals(0, noisyNear.rejectedSamples.tooNear)
        assertTrue(noisyNear.rejectedSamples.lowConfidence > 0)
    }

    @Test
    fun supportedObjectKeepsItsStatisticsAndReportsRemovedPixelsSeparately() {
        val width = 20
        val mm = IntArray(width * width) { index ->
            when (index % width) {
                in 0..5 -> 0
                6 -> 100
                7 -> 9_000
                else -> 1_500
            }
        }
        val baseline = DepthSampler().sampleObjectDepth(
            DepthImage16(width, width, IntArray(width * width) { if (it % width < 8) 0 else 1_500 }),
            ConfidenceImage8(width, width, ByteArray(width * width) { 255.toByte() }),
            roi,
        )
        val mixed = DepthSampler().sampleObjectDepth(
            DepthImage16(width, width, mm),
            ConfidenceImage8(width, width, ByteArray(width * width) { 255.toByte() }),
            roi,
        )

        assertEquals(1.5f, mixed.medianM!!, 0.001f)
        assertEquals(baseline.validSampleCount, mixed.validSampleCount)
        assertEquals(baseline.medianM, mixed.medianM)
        assertTrue(mixed.rejectedSamples.invalidZero > 0)
        assertTrue(mixed.rejectedSamples.tooNear > 0)
        assertTrue(mixed.rejectedSamples.tooFar > 0)
        assertEquals(0, mixed.rejectedSamples.lowConfidence)
        assertEquals(
            baseline.rejectedSamples.invalidZero,
            mixed.rejectedSamples.invalidZero + mixed.rejectedSamples.tooNear + mixed.rejectedSamples.tooFar,
        )
    }
}
