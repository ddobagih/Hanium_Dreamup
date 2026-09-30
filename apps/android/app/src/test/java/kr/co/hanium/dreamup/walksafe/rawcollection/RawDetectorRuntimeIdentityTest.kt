package kr.co.hanium.dreamup.walksafe.rawcollection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 16-17 #6: every RAW runtime window names the exact executed detector and never mixes two. */
class RawDetectorRuntimeIdentityTest {
    private val gpu = RawDetectorRuntimeIdentity("1".repeat(64), "2".repeat(64), "3".repeat(64), "gpu", false)
    private val cpuFallback = gpu.copy(activeDelegate = "cpu", delegateFallbackUsed = true)

    @Test
    fun aDelegateChangeInsideAWindowSplitsItInsteadOfMixingBothExecutions() {
        val accumulator = RawRuntimeWindowAccumulator()
        listOf(0L, 100L, 200L).forEach { at ->
            accumulator.recordCompletedInference(WALK, at, 1, 40L, 0, "model-v1", gpu)
        }
        listOf(300L, 400L).forEach { at ->
            accumulator.recordCompletedInference(WALK, at, 2, 90L, 0, "model-v1", cpuFallback)
        }

        val sample = accumulator.detectionSample(0L, 30_000L, detectorAvailable = true)
        assertEquals(1, accumulator.identitySplitCount)
        assertEquals(cpuFallback, sample.runtimeIdentity)
        assertEquals(2, sample.processedFrameCount)
        assertEquals(4, sample.detectionCount)
        assertEquals(90L, sample.averageInferenceMs)
        assertEquals(300L, accumulator.windowStartedAtElapsedMs)
    }

    @Test
    fun anUnidentifiedCompletionAlsoStartsANewWindow() {
        val accumulator = RawRuntimeWindowAccumulator()
        accumulator.recordCompletedInference(WALK, 0L, 1, 40L, 0, "model-v1", gpu)
        accumulator.recordCompletedInference(WALK, 100L, 1, 40L, 0, "model-v1", null)

        val sample = accumulator.detectionSample(0L, 30_000L, detectorAvailable = true)
        assertEquals(1, accumulator.identitySplitCount)
        assertNull(sample.runtimeIdentity)
        assertEquals(1, sample.processedFrameCount)
    }

    @Test
    fun theSameIdentityKeepsOneWindow() {
        val accumulator = RawRuntimeWindowAccumulator()
        listOf(0L, 100L, 200L).forEach { at ->
            accumulator.recordCompletedInference(WALK, at, 1, 40L, 0, "model-v1", gpu)
        }
        assertEquals(0, accumulator.identitySplitCount)
        assertEquals(3, accumulator.detectionSample(0L, 30_000L, true).processedFrameCount)
    }

    @Test
    fun missingOrMalformedPartsYieldNoIdentity() {
        assertNotNull(RawDetectorRuntimeIdentity.ofOrNull("1".repeat(64), "2".repeat(64), "3".repeat(64), "cpu", false))
        assertNull(RawDetectorRuntimeIdentity.ofOrNull(null, "2".repeat(64), "3".repeat(64), "cpu", false))
        assertNull(RawDetectorRuntimeIdentity.ofOrNull("1".repeat(64), null, "3".repeat(64), "cpu", false))
        assertNull(RawDetectorRuntimeIdentity.ofOrNull("1".repeat(64), "2".repeat(64), null, "cpu", false))
        assertNull(RawDetectorRuntimeIdentity.ofOrNull("1".repeat(64), "2".repeat(64), "3".repeat(64), null, false))
        assertNull(RawDetectorRuntimeIdentity.ofOrNull("1".repeat(64), "2".repeat(64), "3".repeat(64), "cpu", null))
        assertNull(RawDetectorRuntimeIdentity.ofOrNull("models/x.tflite", "2".repeat(64), "3".repeat(64), "cpu", false))
        assertNull(RawDetectorRuntimeIdentity.ofOrNull("1".repeat(64), "2".repeat(64), "3".repeat(64), "xnnpack", false))
    }

    private companion object {
        const val WALK = "123e4567-e89b-42d3-a456-426614174001"
    }
}
