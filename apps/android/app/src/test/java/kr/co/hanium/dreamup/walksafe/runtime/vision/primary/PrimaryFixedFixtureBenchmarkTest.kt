package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.inference.*
import org.junit.Assert.*
import org.junit.Test

class PrimaryFixedFixtureBenchmarkTest {
    @Test fun sdkPlatformConstructionNeverObtainsContextOrPerformsSdkLoad() {
        AndroidPrimaryLifecyclePlatform { error("no eager SDK context") }
    }
    @Test fun warmupAndFinalDelegateObservationDoNotCountAsMeasuredSamplesOrObjectOutputs() {
        val calls = mutableListOf<String>(); var n = 0
        val result = runPrimaryFixedFixtureBenchmark(listOf("a", "b", "c", "d")) { fixture ->
            calls += fixture; n += 1
            AndroidDetectionResult(List(if (n == 1 || n == 14) 100 else 1) { DetectionCandidate("person", 0.9f, RectNorm(0f, 0f, 1f, 1f)) },
                AndroidDetectorTiming(totalMs = n.toLong(), modelInferenceMs = n.toLong() * 2,
                    modelRuntime = AndroidDetectorRuntime("gpu", if (n == 14) "cpu" else "gpu", 2)))
        }
        assertEquals(listOf("a") + List(3) { listOf("a", "b", "c", "d") }.flatten() + listOf("a"), calls)
        assertEquals(12, result.samples); assertEquals(4, result.fixtureCount); assertEquals(8L, result.medianTotalMs)
        assertEquals(16L, result.medianInferenceMs); assertEquals("cpu", result.activeDelegate); assertEquals(12, result.totalDetectedObjects)
    }
    @Test fun emptyRequiredOrMissingMeasuredTimingStopsWithoutClaimingPerformanceCompletion() {
        assertTrue(runCatching { runPrimaryFixedFixtureBenchmark(emptyList<Int>()) { error("empty inference") } }.exceptionOrNull() is IllegalStateException)
        var calls = 0
        assertTrue(runCatching { runPrimaryFixedFixtureBenchmark(listOf(1)) {
            calls += 1; AndroidDetectionResult(emptyList(), if (calls == 1) AndroidDetectorTiming() else AndroidDetectorTiming(totalMs = 2L))
        } }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(2, calls)
    }
}
