package kr.co.hanium.dreamup.walksafe.inference

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorLoadTimingTest {
    @Test fun monotonicIntervalsKeepSubMillisecondPrecisionAndMissingPhasesStayMissing() {
        val clock = AtomicLong(10_000_000L)
        val recorder = DetectorLoadTimingRecorder(clock::get)
        assertEquals(42, recorder.measure(AndroidDetectorLoadPhase.CONFIG_LOAD) {
            clock.addAndGet(1_234_567L)
            42
        })
        val timing = recorder.finishLoad(true)
        assertEquals(1_234_567L, timing.phases.single().elapsedNs)
        assertEquals(1.234567, timing.phases.single().elapsedMs, 0.000001)
        assertEquals(1.234567, requireNotNull(timing.loadElapsedMs), 0.000001)
        assertEquals(listOf(AndroidDetectorLoadPhase.CONFIG_LOAD), timing.phases.map { it.phase })
        assertTrue(timing.detectorAvailable == true)
    }

    @Test fun aFailedStageKeepsItsThrowableAndDoesNotInventLaterSuccesses() {
        val clock = AtomicLong(0L)
        val recorder = DetectorLoadTimingRecorder(clock::get)
        val failure = DetectorAssetIntegrityFailure(DetectorLoadFailureCode.HASH_MISMATCH, "test hash")
        assertSame(failure, assertThrows(DetectorAssetIntegrityFailure::class.java) {
            recorder.measure(AndroidDetectorLoadPhase.ASSET_INTEGRITY, "unified_walksafe") {
                clock.addAndGet(500_000L)
                throw failure
            }
        })
        val timing = recorder.finishLoad(false)
        assertFalse(requireNotNull(timing.detectorAvailable))
        val phase = timing.phases.single()
        assertEquals(AndroidDetectorPhaseOutcome.FAILED, phase.outcome)
        assertEquals("DetectorAssetIntegrityFailure", phase.failureType)
        assertEquals(500_000L, phase.elapsedNs)
        assertFalse(timing.phases.any { it.phase == AndroidDetectorLoadPhase.INTERPRETER_INIT })
    }

    @Test fun successfulCpuFallbackRetainsTheFailedGpuAttemptInTheSameGeneration() {
        val recorder = DetectorLoadTimingRecorder()
        assertThrows(IllegalStateException::class.java) {
            recorder.measure(AndroidDetectorLoadPhase.INTERPRETER_INIT, "unified_walksafe", "gpu") {
                throw IllegalStateException("gpu setup")
            }
        }
        recorder.measure(AndroidDetectorLoadPhase.INTERPRETER_INIT, "unified_walksafe", "cpu") { 42 }
        val timing = recorder.finishLoad(true)
        assertEquals(listOf("gpu", "cpu"), timing.phases.map { it.delegate })
        assertEquals(listOf(AndroidDetectorPhaseOutcome.FAILED, AndroidDetectorPhaseOutcome.SUCCEEDED),
            timing.phases.map { it.outcome })
        assertNull(timing.phases.last().failureType)
        assertTrue(timing.detectorAvailable == true)
    }

    @Test fun firstInferenceFallbackRecordsFailedGpuAndSuccessfulCpuAsSeparateNativeAttempts() {
        val recorder = DetectorLoadTimingRecorder()
        val events = mutableListOf<String>()
        val runtime = DelegateFallbackRuntime(ModelRuntimeOptions("gpu", 2)) { delegate ->
            recorder.measure(AndroidDetectorLoadPhase.INTERPRETER_INIT, "unified_walksafe", delegate) {
                events.add("create:$delegate")
                Closeable { events.add("close:$delegate") }
            }
        }
        recorder.finishLoad(true)
        try {
            assertEquals(42, runtime.run {
                val delegate = runtime.runtime.activeDelegate
                recorder.measure(AndroidDetectorLoadPhase.FIRST_INFERENCE, "unified_walksafe", delegate) {
                    events.add("run:$delegate")
                    if (delegate == "gpu") throw IllegalStateException("gpu invoke")
                    42
                }
            })
            assertEquals("cpu", runtime.runtime.activeDelegate)
            assertEquals(listOf("create:gpu", "run:gpu", "close:gpu", "create:cpu", "run:cpu"), events)
            val attempts = recorder.snapshot().phases.filter { it.phase == AndroidDetectorLoadPhase.FIRST_INFERENCE }
            assertEquals(listOf("gpu", "cpu"), attempts.map { it.delegate })
            assertEquals(listOf(AndroidDetectorPhaseOutcome.FAILED, AndroidDetectorPhaseOutcome.SUCCEEDED),
                attempts.map { it.outcome })
            assertEquals("IllegalStateException", attempts.first().failureType)
            assertNull(attempts.last().failureType)
        } finally { runtime.close() }
    }

    @Test fun completedLoadSnapshotStaysFixedWhileInferenceAndCloseAreRecordedLater() {
        val clock = AtomicLong(1_000_000L)
        val recorder = DetectorLoadTimingRecorder(clock::get)
        recorder.measure(AndroidDetectorLoadPhase.TENSOR_CHECK) { clock.addAndGet(2_000_000L) }
        val loaded = recorder.finishLoad(true)
        clock.addAndGet(10_000_000L)
        recorder.measure(AndroidDetectorLoadPhase.FIRST_INFERENCE, "unified_walksafe", "cpu") {
            clock.addAndGet(3_000_000L)
        }
        recorder.measure(AndroidDetectorLoadPhase.NATIVE_CLOSE) { clock.addAndGet(4_000_000L) }
        val closed = recorder.snapshot()
        assertEquals(loaded.loadGeneration, closed.loadGeneration)
        assertEquals(loaded.loadFinishedNs, closed.loadFinishedNs)
        assertEquals(loaded.loadElapsedMs, closed.loadElapsedMs)
        assertEquals(1, loaded.phases.size)
        assertEquals(3, closed.phases.size)
        assertTrue(closed.phases[1].startedNs > requireNotNull(loaded.loadFinishedNs))
        assertNotEquals(loaded.loadGeneration, DetectorLoadTimingRecorder(clock::get).snapshot().loadGeneration)
    }

    @Test fun failureThenAcceptedWorkAndReleaseKeepTheRealOwnerThreadAndOrder() {
        val owner = DetectorThreadOwner("timed-model-owner")
        val recorder = DetectorLoadTimingRecorder()
        try {
            val queuedNs = recorder.now()
            val failure = IllegalStateException("invoke failed")
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                owner.call {
                    recorder.record(AndroidDetectorLoadPhase.OWNER_QUEUE, queuedNs)
                    recorder.measure(AndroidDetectorLoadPhase.FIRST_INFERENCE, "unified_walksafe", "cpu") {
                        throw failure
                    }
                }
            })
            assertEquals(42, owner.call { 42 })
            val closeQueuedNs = recorder.now()
            owner.close {
                recorder.record(AndroidDetectorLoadPhase.CLOSE_QUEUE, closeQueuedNs)
                recorder.measure(AndroidDetectorLoadPhase.NATIVE_CLOSE) { Unit }
            }
            val timing = recorder.snapshot()
            assertEquals(listOf(AndroidDetectorLoadPhase.OWNER_QUEUE, AndroidDetectorLoadPhase.FIRST_INFERENCE,
                AndroidDetectorLoadPhase.CLOSE_QUEUE, AndroidDetectorLoadPhase.NATIVE_CLOSE), timing.phases.map { it.phase })
            assertEquals(AndroidDetectorPhaseOutcome.FAILED, timing.phases[1].outcome)
            assertTrue(timing.phases.all { it.threadName == "timed-model-owner" })
            assertTrue(timing.phases.all { it.threadId != timing.requestThreadId })
            assertTrue(timing.phases.all { it.finishedNs >= it.startedNs })
        } finally { owner.close {} }
    }

    @Test fun actualDetectorRepeatCloseDoesNotAppendNoOpCpuOrOwnerQueuePhases() {
        listOf<DetectorThreadOwner?>(null, DetectorThreadOwner("close-timing-owner")).forEach { owner ->
            val recorder = DetectorLoadTimingRecorder()
            val detector = emptyDetector(owner, recorder)
            try {
                detector.close()
                repeat(100) { detector.close() }
                assertEquals(listOf(AndroidDetectorLoadPhase.CLOSE_QUEUE, AndroidDetectorLoadPhase.NATIVE_CLOSE),
                    detector.lifecycleTiming().phases.map { it.phase })
                assertTrue(detector.lifecycleTiming().phases.all { it.outcome == AndroidDetectorPhaseOutcome.SUCCEEDED })
            } finally { detector.close() }
        }
    }

    @Test fun actualDetectorObservingAnAlreadyFailedOwnerCloseDoesNotInventANewQueueAttempt() {
        val owner = DetectorThreadOwner("failed-close-timing-owner")
        val failure = IllegalStateException("previous owner release failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) { owner.close { throw failure } })
        val recorder = DetectorLoadTimingRecorder()
        val detector = emptyDetector(owner, recorder)
        repeat(3) {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { detector.close() })
        }
        assertTrue(detector.lifecycleTiming().phases.isEmpty())
    }

    /** Exercises the production close method with zero native models; this is not native-release validation. */
    private fun emptyDetector(owner: DetectorThreadOwner?, recorder: DetectorLoadTimingRecorder): TfliteAndroidFrameDetector {
        val modelType = Class.forName("kr.co.hanium.dreamup.walksafe.inference.TfliteSingleModelDetector")
        val constructor = TfliteAndroidFrameDetector::class.java.getDeclaredConstructor(
            DetectorThreadOwner::class.java, DetectorLoadTimingRecorder::class.java,
            modelType, modelType,
            modelType, YuvImagePreprocessor::class.java,
            YuvPreprocessingStrategy::class.java, modelType,
        )
        constructor.isAccessible = true
        return constructor.newInstance(owner, recorder, null, null, null, YuvImagePreprocessor(),
            YuvPreprocessingStrategy.LEGACY_TWO_PASS, null) as TfliteAndroidFrameDetector
    }
}
