package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeLoadMonitorTailDiagnosticsRegressionTest {
    @Test
    fun equalCentralStatisticsWithDifferentAdmittedTailsRemainDistinguishable() {
        val firstTimings = List(9) { 100L } + 500L
        val secondTimings = List(9) { 100L } + 2_000L
        val first = completedWindow(firstTimings)
        val second = completedWindow(secondTimings)
        assertEquals(first.baseline, second.baseline)
        assertEquals(100.0, checkNotNull(first.baseline).medianEndToEndMs, 0.0)
        assertEquals(0.0, checkNotNull(first.baseline).medianAbsoluteDeviationMs, 0.0)
        assertEquals(0.0, checkNotNull(first.baseline).staleOrDropFraction, 0.0)
        assertEquals(10, checkNotNull(first.baseline).completedSamples)
        assertEquals(RuntimeLoadWindowStatus.BASELINE_LEARNED, first.lastWindowStatus)
        assertEquals(first.lastWindowStatus, second.lastWindowStatus)
        assertEquals(1L, first.completedWindows)
        assertEquals(first.completedWindows, second.completedWindows)
        assertEquals(0, first.consecutiveDegradedWindows)
        assertEquals(first.consecutiveDegradedWindows, second.consecutiveDegradedWindows)
        assertNull(first.pendingReason)
        assertNull(second.pendingReason)
        println("S14-46 original admitted inputs A=$firstTimings B=$secondTimings receipt_ms=0..17100 close=20000")
        println("S14-46 actual window snapshot A=$first")
        println("S14-46 actual window snapshot B=$second")
        assertNotEquals("Different admitted latency tails must not collapse into an indistinguishable snapshot", first, second)
    }

    private fun completedWindow(timings: List<Long>): RuntimeLoadSnapshot {
        val monitor = RuntimeLoadMonitor()
        timings.forEachIndexed { index, latency ->
            monitor.observe(index * 1_900L, "GPU:4:tracking-depth", true,
                inputObserved = true, completion = RuntimeLoadCompletion(latency))
        }
        return monitor.observe(20_000L, "GPU:4:tracking-depth", true, inputObserved = true)
    }
}
