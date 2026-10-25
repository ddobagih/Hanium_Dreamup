package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeLoadMonitorTest {
    @Test
    fun onlyThreeFullNonOverlappingDegradedWindowsAfterALiveBaselineSetPending() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L)
        assertEquals(RuntimeLoadWindowStatus.BASELINE_LEARNED, monitor.snapshot().lastWindowStatus)
        assertEquals(0, monitor.snapshot().consecutiveDegradedWindows)
        addWindow(monitor, 20_000L, 200L)
        addWindow(monitor, 40_000L, 200L)
        assertEquals(2, monitor.snapshot().consecutiveDegradedWindows)
        assertNull(monitor.snapshot().pendingReason)
        addSamples(monitor, 60_000L, 200L)
        monitor.observe(79_999L, KEY, true, inputObserved = true)
        assertNull(monitor.snapshot().pendingReason)

        monitor.observe(80_000L, KEY, true, inputObserved = true)

        assertEquals(RuntimeLoadPendingReason.SUSTAINED_LATENCY_DEGRADATION, monitor.snapshot().pendingReason)
        assertEquals(4L, monitor.snapshot().completedWindows)
        monitor.observe(80_000L, KEY, true, inputObserved = true)
        assertEquals(4L, monitor.snapshot().completedWindows)
    }

    @Test
    fun slowerAdmissionWithNormalLatencyIsNotPerformanceDegradation() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L, count = 40)
        repeat(4) { addWindow(monitor, (it + 1) * 20_000L, 100L, count = 10) }
        assertNull(monitor.snapshot().pendingReason)
        assertEquals(0, monitor.snapshot().consecutiveDegradedWindows)
    }

    @Test
    fun staleIncreaseNeedsThreeWindowsEvenIfLatencyIsStable() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L)
        repeat(3) { addWindow(monitor, (it + 1) * 20_000L, 100L, staleCount = 3) }
        assertEquals(RuntimeLoadPendingReason.SUSTAINED_STALE_OR_DROP_INCREASE, monitor.snapshot().pendingReason)
    }

    @Test
    fun insufficientSampleWindowBreaksTheDegradationStreak() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L)
        addWindow(monitor, 20_000L, 200L)
        addWindow(monitor, 40_000L, 200L)
        addWindow(monitor, 60_000L, 200L, count = 9)
        assertEquals(RuntimeLoadWindowStatus.INSUFFICIENT_SAMPLES, monitor.snapshot().lastWindowStatus)
        assertEquals(0, monitor.snapshot().consecutiveDegradedWindows)
        addWindow(monitor, 80_000L, 200L)
        assertNull(monitor.snapshot().pendingReason)
        assertEquals(1, monitor.snapshot().consecutiveDegradedWindows)
    }

    @Test
    fun normalWindowPauseAndInvalidEnvironmentEachBreakConsecutiveEvidence() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L)
        addWindow(monitor, 20_000L, 200L)
        addWindow(monitor, 40_000L, 100L)
        assertEquals(0, monitor.snapshot().consecutiveDegradedWindows)
        addWindow(monitor, 60_000L, 200L)
        monitor.pause()
        assertEquals(0, monitor.snapshot().consecutiveDegradedWindows)
        addWindow(monitor, 80_000L, 200L)
        monitor.observe(100_001L, KEY, normalEnvironment = false)
        assertEquals(0, monitor.snapshot().consecutiveDegradedWindows)
        assertEquals(RuntimeLoadWindowStatus.INACTIVE_ENVIRONMENT, monitor.snapshot().lastWindowStatus)
        assertNotNull(monitor.snapshot().baseline)
        assertNull(monitor.snapshot().pendingReason)
    }

    @Test
    fun differentActiveConfigurationMustLearnItsOwnLiveBaseline() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L)
        addWindow(monitor, 20_000L, 200L)
        addWindow(monitor, 40_000L, 200L, key = "CPU:2:tracking-depth")
        assertEquals(200.0, checkNotNull(monitor.snapshot().baseline).medianEndToEndMs, 0.001)
        assertEquals(0, monitor.snapshot().consecutiveDegradedWindows)
        assertNull(monitor.snapshot().pendingReason)
    }

    @Test
    fun zeroCompletionsWithOngoingInputIsDiagnosedWithoutBaseline() {
        val monitor = RuntimeLoadMonitor()
        monitor.observe(0L, KEY, true, inputObserved = true)
        monitor.observe(10_000L, KEY, true, inputObserved = true)
        monitor.observe(20_000L, KEY, true, inputObserved = true)
        assertEquals(RuntimeLoadPendingReason.NO_COMPLETIONS_WITH_INPUT, monitor.snapshot().pendingReason)
        assertNull(monitor.snapshot().baseline)
    }

    @Test
    fun idleCameraAndUnobservedTimeCannotInventAStuckWorker() {
        val monitor = RuntimeLoadMonitor()
        monitor.observe(0L, KEY, true)
        monitor.observe(20_000L, KEY, true)
        assertNull(monitor.snapshot().pendingReason)
        monitor.observe(200_000L, KEY, true, inputObserved = true)
        assertEquals(RuntimeLoadWindowStatus.OBSERVATION_GAP, monitor.snapshot().lastWindowStatus)
        assertNull(monitor.snapshot().pendingReason)
        assertEquals(1L, monitor.snapshot().completedWindows)
    }

    @Test
    fun allStaleCompletionsAreAValidResultInterruptionEvenBelowSampleMinimum() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 900L, count = 3, staleCount = 3)
        assertEquals(RuntimeLoadPendingReason.NO_FRESH_RESULTS_WITH_INPUT, monitor.snapshot().pendingReason)
        assertNull(monitor.snapshot().baseline)
    }

    @Test
    fun inputFirstObservedAtTheEndOfAWindowDoesNotInventTwentySecondsOfInterruptedResults() {
        val monitor = RuntimeLoadMonitor()
        monitor.observe(0L, KEY, true)
        monitor.observe(19_999L, KEY, true, inputObserved = true)
        monitor.observe(20_000L, KEY, true, inputObserved = true)
        assertNull(monitor.snapshot().pendingReason)
        monitor.observe(30_000L, KEY, true, inputObserved = true)
        monitor.observe(40_000L, KEY, true, inputObserved = true)
        assertEquals(RuntimeLoadPendingReason.NO_COMPLETIONS_WITH_INPUT, monitor.snapshot().pendingReason)
    }

    @Test
    fun lateFirstStaleResultAndRecentFreshResultBothDelayInterruptionDiagnostic() {
        val monitor = RuntimeLoadMonitor()
        monitor.observe(0L, KEY, true)
        monitor.observe(19_999L, KEY, true, true, RuntimeLoadCompletion(900L, staleOrDropped = true))
        monitor.observe(20_000L, KEY, true, true)
        assertNull(monitor.snapshot().pendingReason)
        monitor.observe(30_000L, KEY, true, true, RuntimeLoadCompletion(100L))
        monitor.observe(39_999L, KEY, true, true)
        monitor.observe(40_000L, KEY, true, true)
        assertNull(monitor.snapshot().pendingReason)
    }

    @Test
    fun sourceTimestampMismatchAndFixtureTimingCannotBecomeLivePerformanceEvidence() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L)
        addWindow(monitor, 20_000L, 200L)
        monitor.observe(40_001L, KEY, true, true, RuntimeLoadCompletion(10L, sourceTimestampMatches = false))
        assertEquals(1L, monitor.snapshot().sourceTimingMismatchSamples)
        assertEquals(RuntimeLoadWindowStatus.SOURCE_TIMING_MISMATCH, monitor.snapshot().lastWindowStatus)
        assertEquals(0, monitor.snapshot().consecutiveDegradedWindows)
        monitor.observe(40_002L, KEY, true, true, RuntimeLoadCompletion(10L, liveCameraInput = false))
        assertEquals(RuntimeLoadWindowStatus.INACTIVE_ENVIRONMENT, monitor.snapshot().lastWindowStatus)
        assertEquals(100.0, checkNotNull(monitor.snapshot().baseline).medianEndToEndMs, 0.001)
    }

    @Test
    fun regressingTimeBreaksContinuityAndCannotMoveTheClockBackwards() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L)
        addWindow(monitor, 20_000L, 200L)
        monitor.observe(39_999L, KEY, true, true, RuntimeLoadCompletion(200L))
        assertEquals(RuntimeLoadWindowStatus.INVALID_TIMING, monitor.snapshot().lastWindowStatus)
        assertEquals(0, monitor.snapshot().consecutiveDegradedWindows)
        monitor.observe(-1L, KEY, true)
        assertNull(monitor.snapshot().pendingReason)
    }

    @Test
    fun individualSpikeAndBaselineNoiseDoNotElectADegradation() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L)
        repeat(3) { window ->
            val startMs = (window + 1) * 20_000L
            addSamples(monitor, startMs, 100L, count = 9)
            monitor.observe(startMs + 19_000L, KEY, true, true, RuntimeLoadCompletion(2_000L, staleOrDropped = true))
            monitor.observe(startMs + 20_000L, KEY, true, true)
        }
        assertNull(monitor.snapshot().pendingReason)

        val noisy = RuntimeLoadMonitor()
        repeat(4) { window ->
            val startMs = window * 20_000L
            repeat(10) { sample ->
                val latency = if (sample % 2 == 0) 50L else 150L
                noisy.observe(startMs + sample * 1_900L, KEY, true, true,
                    RuntimeLoadCompletion(latency + if (window == 0) 0L else 50L))
            }
            noisy.observe(startMs + 20_000L, KEY, true, true)
        }
        assertNull(noisy.snapshot().pendingReason)
    }

    @Test
    fun pendingIsStickyAcrossPauseAndNormalRecoveryUntilExplicitReset() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L)
        repeat(3) { addWindow(monitor, (it + 1) * 20_000L, 200L) }
        monitor.pause()
        addWindow(monitor, 80_000L, 100L)
        assertEquals(RuntimeLoadPendingReason.SUSTAINED_LATENCY_DEGRADATION, monitor.snapshot().pendingReason)
        monitor.reset()
        assertNull(monitor.snapshot().pendingReason)
        assertNull(monitor.snapshot().baseline)
        assertEquals(0L, monitor.snapshot().completedWindows)
    }

    @Test
    fun nearMaximumMonotonicTimeDoesNotOverflowWindowBoundary() {
        val monitor = RuntimeLoadMonitor()
        val start = Long.MAX_VALUE - 20_000L
        addWindow(monitor, start, 100L)
        assertNotNull(monitor.snapshot().baseline)
        assertEquals(1L, monitor.snapshot().completedWindows)
    }

    @Test
    fun maximumIsUnknownUntilWindowClosesAndRawZeroRemainsAnObservation() {
        val monitor = RuntimeLoadMonitor()
        assertNull(monitor.snapshot().lastCompletedWindowMaxEndToEndMs)
        addSamples(monitor, 0L, 0L)
        assertNull(monitor.snapshot().lastCompletedWindowMaxEndToEndMs)

        val closed = monitor.observe(20_000L, KEY, true, inputObserved = true)

        assertEquals(0L, closed.lastCompletedWindowMaxEndToEndMs)
        assertEquals(RuntimeLoadWindowStatus.BASELINE_LEARNED, closed.lastWindowStatus)
        assertEquals(0.0, checkNotNull(closed.baseline).medianEndToEndMs, 0.0)
        monitor.observe(20_001L, KEY, true, true, RuntimeLoadCompletion(700L))
        assertEquals(0L, monitor.snapshot().lastCompletedWindowMaxEndToEndMs)
    }

    @Test
    fun completionAtWindowBoundaryBelongsToTheNextWindowMaximum() {
        val monitor = RuntimeLoadMonitor()
        addSamples(monitor, 0L, 100L)

        val first = monitor.observe(20_000L, KEY, true, true, RuntimeLoadCompletion(999L))

        assertEquals(100L, first.lastCompletedWindowMaxEndToEndMs)
        assertEquals(1L, first.completedWindows)
        val second = monitor.observe(40_000L, KEY, true, inputObserved = true)
        assertEquals(999L, second.lastCompletedWindowMaxEndToEndMs)
        assertEquals(2L, second.completedWindows)
        assertEquals(RuntimeLoadWindowStatus.INSUFFICIENT_SAMPLES, second.lastWindowStatus)
        assertNull(second.pendingReason)
    }

    @Test
    fun maximumPreservesRawLongBeyondTheMedianSampleCap() {
        val monitor = RuntimeLoadMonitor()
        repeat(1_024) { index ->
            monitor.observe(index.toLong(), KEY, true, true, RuntimeLoadCompletion(100L))
        }
        val tailMs = 9_007_199_254_740_993L
        monitor.observe(1_024L, KEY, true, true, RuntimeLoadCompletion(tailMs))

        val closed = monitor.observe(20_000L, KEY, true, inputObserved = true)

        assertEquals(tailMs, closed.lastCompletedWindowMaxEndToEndMs)
        assertEquals(RuntimeLoadBaseline(100.0, 0.0, 0.0, 1_024), closed.baseline)
        assertEquals(RuntimeLoadWindowStatus.BASELINE_LEARNED, closed.lastWindowStatus)
        assertNull(closed.pendingReason)
    }

    @Test
    fun observedMaximumDoesNotTurnInsufficientOrInterruptedWindowsIntoHealthyEvidence() {
        val insufficient = RuntimeLoadMonitor()
        addWindow(insufficient, 0L, 900L, count = 1)
        assertEquals(900L, insufficient.snapshot().lastCompletedWindowMaxEndToEndMs)
        assertEquals(RuntimeLoadWindowStatus.INSUFFICIENT_SAMPLES, insufficient.snapshot().lastWindowStatus)
        assertNull(insufficient.snapshot().baseline)
        assertNull(insufficient.snapshot().pendingReason)

        val stale = RuntimeLoadMonitor()
        addWindow(stale, 0L, 900L, count = 1, staleCount = 1)
        assertEquals(900L, stale.snapshot().lastCompletedWindowMaxEndToEndMs)
        assertEquals(RuntimeLoadWindowStatus.RESULT_INTERRUPTION, stale.snapshot().lastWindowStatus)
        assertEquals(RuntimeLoadPendingReason.NO_FRESH_RESULTS_WITH_INPUT, stale.snapshot().pendingReason)
        assertNull(stale.snapshot().baseline)

        val missing = RuntimeLoadMonitor()
        missing.observe(0L, KEY, true, inputObserved = true)
        missing.observe(20_000L, KEY, true, inputObserved = true)
        assertNull(missing.snapshot().lastCompletedWindowMaxEndToEndMs)
        assertEquals(RuntimeLoadPendingReason.NO_COMPLETIONS_WITH_INPUT, missing.snapshot().pendingReason)
    }

    @Test
    fun continuityBreakAndNewScopeClearThePreviousWindowMaximum() {
        val interruptions: List<Pair<RuntimeLoadWindowStatus, (RuntimeLoadMonitor) -> Unit>> = listOf(
            RuntimeLoadWindowStatus.INACTIVE_ENVIRONMENT to { it.observe(20_001L, KEY, false) },
            RuntimeLoadWindowStatus.INACTIVE_ENVIRONMENT to { it.observe(20_001L, "", true) },
            RuntimeLoadWindowStatus.COLLECTING to { it.observe(20_001L, "CPU:2:tracking-depth", true) },
            RuntimeLoadWindowStatus.INVALID_TIMING to { it.observe(19_999L, KEY, true) },
            RuntimeLoadWindowStatus.INVALID_TIMING to { it.observe(-1L, KEY, true) },
            RuntimeLoadWindowStatus.INVALID_TIMING to {
                it.observe(20_001L, KEY, true, true, RuntimeLoadCompletion(-1L))
            },
            RuntimeLoadWindowStatus.SOURCE_TIMING_MISMATCH to {
                it.observe(20_001L, KEY, true, true, RuntimeLoadCompletion(900L, sourceTimestampMatches = false))
            },
            RuntimeLoadWindowStatus.INACTIVE_ENVIRONMENT to {
                it.observe(20_001L, KEY, true, true, RuntimeLoadCompletion(900L, liveCameraInput = false))
            },
            RuntimeLoadWindowStatus.OBSERVATION_GAP to { it.observe(40_001L, KEY, true, inputObserved = true) },
            RuntimeLoadWindowStatus.INACTIVE_ENVIRONMENT to { it.pause() },
        )
        interruptions.forEach { (expectedStatus, interrupt) ->
            val monitor = RuntimeLoadMonitor()
            addWindow(monitor, 0L, 100L)
            assertEquals(100L, monitor.snapshot().lastCompletedWindowMaxEndToEndMs)

            interrupt(monitor)

            assertNull("Previous scope must not supply the maximum after $expectedStatus",
                monitor.snapshot().lastCompletedWindowMaxEndToEndMs)
            assertEquals(expectedStatus, monitor.snapshot().lastWindowStatus)
            assertEquals(1L, monitor.snapshot().completedWindows)
            assertEquals(0, monitor.snapshot().consecutiveDegradedWindows)
        }
    }

    @Test
    fun resetClearsMaximumAlongsideStickyPendingAndBaseline() {
        val monitor = RuntimeLoadMonitor()
        addWindow(monitor, 0L, 100L)
        repeat(3) { addWindow(monitor, (it + 1) * 20_000L, 200L) }
        assertEquals(200L, monitor.snapshot().lastCompletedWindowMaxEndToEndMs)
        assertNotNull(monitor.snapshot().pendingReason)

        monitor.reset()

        assertNull(monitor.snapshot().lastCompletedWindowMaxEndToEndMs)
        assertNull(monitor.snapshot().pendingReason)
        assertNull(monitor.snapshot().baseline)
        assertNull(monitor.snapshot().environmentKey)
        assertEquals(0L, monitor.snapshot().completedWindows)
        assertEquals(RuntimeLoadWindowStatus.COLLECTING, monitor.snapshot().lastWindowStatus)
    }

    private fun addWindow(
        monitor: RuntimeLoadMonitor,
        startMs: Long,
        endToEndMs: Long,
        count: Int = 10,
        staleCount: Int = 0,
        key: String = KEY,
    ) {
        addSamples(monitor, startMs, endToEndMs, count, staleCount, key)
        monitor.observe(startMs + 20_000L, key, true, inputObserved = true)
    }

    private fun addSamples(
        monitor: RuntimeLoadMonitor,
        startMs: Long,
        endToEndMs: Long,
        count: Int = 10,
        staleCount: Int = 0,
        key: String = KEY,
    ) {
        repeat(count) { index ->
            monitor.observe(startMs + index * (19_000L / count), key, true, inputObserved = true,
                completion = RuntimeLoadCompletion(endToEndMs, staleOrDropped = index < staleCount))
        }
    }

    companion object {
        private const val KEY = "GPU:4:tracking-depth"
    }
}
