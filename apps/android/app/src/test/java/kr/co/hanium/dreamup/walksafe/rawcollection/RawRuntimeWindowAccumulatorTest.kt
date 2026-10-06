package kr.co.hanium.dreamup.walksafe.rawcollection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #2 (slow tail beside the mean) and #4 (fixed counter meanings). */
class RawRuntimeWindowAccumulatorTest {
    @Test
    fun oneSnapshotRetainsBothCountersAfterProducerAdvancesAndResets() {
        val accumulator = window(listOf(10L, 20L))
        val first = accumulator.snapshot(0L, 30_000L, true, false)
        assertTrue(first.detection.windowIdentity === first.performance.windowIdentity)
        accumulator.resetWindow()
        accumulator.recordCompletedInference(WALK, 40_000L, 8, 90L, 2, "model-v1")
        val second = accumulator.snapshot(0L, 30_000L, true, false)
        assertTrue(first.detection.windowIdentity !== second.detection.windowIdentity)
        assertTrue(second.detection.windowIdentity!!.sequence > first.detection.windowIdentity!!.sequence)
        assertEquals(2, first.detection.processedFrameCount)
        assertEquals(2, first.performance.processedFrameCount)
        assertEquals(1, second.detection.processedFrameCount)
        assertEquals(1, second.performance.processedFrameCount)
        assertEquals(2, requireNotNull(first.detection.inferenceMsHistogram).sum())
    }

    @Test
    fun equalMeanInputsWithOneLongInferenceAreDistinguishedByMaxAndHistogram() {
        val steady = window(inferences = listOf(100L, 100L, 100L, 100L))
        val spiky = window(inferences = listOf(25L, 25L, 25L, 325L))
        val steadySample = steady.detectionSample(0L, 30_000L, detectorAvailable = true)
        val spikySample = spiky.detectionSample(0L, 30_000L, detectorAvailable = true)

        assertEquals(steadySample.averageInferenceMs, spikySample.averageInferenceMs)
        assertEquals(100L, steadySample.maxInferenceMs)
        assertEquals(325L, spikySample.maxInferenceMs)
        assertNotEquals(steadySample.inferenceMsHistogram, spikySample.inferenceMsHistogram)
        assertEquals(4, requireNotNull(spikySample.inferenceMsHistogram).sum())
        assertEquals(listOf(3, 0, 0, 0, 1, 0, 0), spikySample.inferenceMsHistogram)
    }

    @Test
    fun aLongStallIsAFrameIntervalNotAnInferenceTime() {
        val accumulator = RawRuntimeWindowAccumulator()
        listOf(0L, 100L, 200L, 1_700L).forEach { at ->
            accumulator.recordCompletedInference(WALK, at, detectionCount = 1, inferenceMs = 40L,
                busyDroppedSincePrevious = 0, modelRevision = "model-v1")
        }
        val detection = accumulator.detectionSample(0L, 30_000L, detectorAvailable = true)
        val performance = accumulator.performanceSample(0L, 30_000L, thermalThrottled = false)

        assertEquals(40L, detection.averageInferenceMs)
        assertEquals(3, performance.frameDurationSampleCount)
        assertEquals(566L, performance.averageFrameDurationMs)
        assertEquals(1_500L, performance.maxFrameDurationMs)
        assertEquals(1, requireNotNull(performance.frameDurationMsHistogram).last())
        // The two samples count the same completed-inference stage; busy drops are separate.
        assertEquals(detection.processedFrameCount, performance.processedFrameCount)
        assertNotEquals(detection.averageInferenceMs, performance.averageFrameDurationMs)
    }

    @Test
    fun busyDropsAreCountedSeparatelyFromCompletedInferences() {
        val accumulator = RawRuntimeWindowAccumulator()
        accumulator.recordCompletedInference(WALK, 0L, 0, 30L, busyDroppedSincePrevious = 2, modelRevision = null)
        accumulator.recordCompletedInference(WALK, 50L, 0, 30L, busyDroppedSincePrevious = 3, modelRevision = null)
        val performance = accumulator.performanceSample(0L, 30_000L, thermalThrottled = null)
        assertEquals(2, performance.processedFrameCount)
        assertEquals(5, performance.droppedFrameCount)
    }

    @Test
    fun aStallAcrossTheWindowBoundaryIsCountedInTheNextWindowButAResumeGapIsNot() {
        val accumulator = RawRuntimeWindowAccumulator()
        accumulator.recordCompletedInference(WALK, 29_900L, 0, 30L, 0, null)
        accumulator.resetWindow()
        accumulator.recordCompletedInference(WALK, 31_400L, 0, 30L, 0, null)
        assertEquals(31_400L, accumulator.windowStartedAtElapsedMs)
        assertEquals(1_500L, accumulator.performanceSample(0L, 1L, null).maxFrameDurationMs)

        accumulator.resetAll()
        accumulator.recordCompletedInference(WALK, 900_000L, 0, 30L, 0, null)
        val resumed = accumulator.performanceSample(0L, 1L, null)
        assertEquals(0, resumed.frameDurationSampleCount)
        assertEquals(0L, resumed.maxFrameDurationMs)
    }

    @Test
    fun aDifferentWalkStartsFromAnEmptyWindow() {
        val accumulator = RawRuntimeWindowAccumulator()
        accumulator.recordCompletedInference(WALK, 0L, 4, 30L, 1, "model-v1")
        accumulator.recordCompletedInference(OTHER_WALK, 10L, 1, 60L, 0, "model-v2")
        val detection = accumulator.detectionSample(0L, 30_000L, detectorAvailable = true)
        assertEquals(1, detection.processedFrameCount)
        assertEquals(1, detection.detectionCount)
        assertEquals("model-v2", detection.modelRevision)
        assertEquals(0, accumulator.performanceSample(0L, 30_000L, null).frameDurationSampleCount)
    }

    @Test
    fun bucketBoundariesAreExclusiveUpperBounds() {
        assertEquals(0, rawRuntimeLatencyBucket(32L))
        assertEquals(1, rawRuntimeLatencyBucket(33L))
        assertEquals(5, rawRuntimeLatencyBucket(799L))
        assertEquals(6, rawRuntimeLatencyBucket(800L))
        assertEquals(6, rawRuntimeLatencyBucket(1_500L))
        assertTrue(RAW_RUNTIME_LATENCY_BUCKET_COUNT == RAW_RUNTIME_LATENCY_BUCKET_UPPER_BOUNDS_MS.size + 1)
    }

    private fun window(inferences: List<Long>): RawRuntimeWindowAccumulator =
        RawRuntimeWindowAccumulator().also { accumulator ->
            inferences.forEachIndexed { index, inferenceMs ->
                accumulator.recordCompletedInference(WALK, index * 100L, 0, inferenceMs, 0, "model-v1")
            }
        }

    private companion object {
        const val WALK = "123e4567-e89b-42d3-a456-426614174001"
        const val OTHER_WALK = "123e4567-e89b-42d3-a456-426614174009"
    }
}
