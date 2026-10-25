package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import org.junit.Assert.*
import org.junit.Test

/** Actual existing calibration owners/profiles, queued worker and storage failures; no Android probes. */
class PrimaryPendingDegradationAttemptTest {
    @Test fun constructionAndIneligibleObservationsDoNotReadProfilesClocksOrSubmitWork() {
        val f = Fixture()
        f.observe(false)
        f.observe(true)
        f.state.retainSelectionBinding("A")
        f.observe(false)
        assertEquals(0, f.tuning.pending)
        assertTrue(f.writes.isEmpty())
    }

    @Test fun markerIsRetainedBeforeSubmissionAndEqualValueBindingsShareTheSameRecording() {
        val f = Fixture(); f.state.retainSelectionBinding("A")
        var recursive = false
        f.tuning.onSubmit = { if (!recursive) { recursive = true; f.observe(true) } }
        f.observe(true)
        assertEquals(1, f.tuning.pending)
        f.state.retainSelectionBinding(String(charArrayOf('A')))
        f.observe(true)
        assertEquals(1, f.tuning.pending)
        assertTrue(f.writes.isEmpty())
    }

    @Test fun storageRunsOnlyOnTheOriginalTuningLaneWithTheExactReasonAndNullCursor() {
        val f = Fixture(); f.state.retainSelectionBinding("A"); f.observe(true)
        assertTrue(f.writes.isEmpty())
        f.tuning.next()
        assertEquals(listOf(Write("A", DeviceTuningRecheckReason.PERSISTENT_DEGRADATION, null)), f.writes)
        f.observe(true)
        assertEquals(0, f.tuning.pending)
        assertEquals(1, f.writes.size)
    }

    @Test fun staleQueuedBindingSkipsStorageAndRetainsTheOriginalRecordedMarker() {
        val f = Fixture(); f.state.retainSelectionBinding("A"); f.observe(true)
        f.state.retainSelectionBinding("B"); f.tuning.next()
        assertTrue(f.writes.isEmpty())
        f.state.retainSelectionBinding("A"); f.observe(true)
        assertEquals(0, f.tuning.pending)
        f.state.retainSelectionBinding("B"); f.observe(true)
        assertEquals(1, f.tuning.pending)
        f.tuning.next(); assertEquals("B", f.writes.single().binding)
    }

    @Test fun failedStorageCommitClearsRecordingAndPermitsANewObservationToRetry() {
        val f = Fixture(); f.state.retainSelectionBinding("A"); f.commit = false
        f.observe(true); f.tuning.next()
        assertEquals(0, f.tuning.pending)
        f.commit = true; f.observe(true)
        assertEquals(1, f.tuning.pending)
        f.tuning.next(); assertEquals(2, f.writes.size)
        f.observe(true); assertEquals(0, f.tuning.pending)
    }

    @Test fun throwingStorageKeepsRecordingAndPropagatesTheSameFailureWithoutRetrying() {
        val f = Fixture(); f.state.retainSelectionBinding("A")
        val failure = IllegalStateException("storage"); f.storageFailure = failure
        f.observe(true)
        assertSame(failure, runCatching { f.tuning.next() }.exceptionOrNull())
        f.observe(true)
        assertEquals(0, f.tuning.pending)
        assertEquals(1, f.writes.size)
    }

    @Test fun rejectedEnqueueKeepsTheAlreadyRecordedMarkerAndPropagatesTheSameFailure() {
        val f = Fixture(); f.state.retainSelectionBinding("A")
        val failure = RejectedExecutionException("lane"); f.tuning.rejection = failure
        assertSame(failure, runCatching { f.observe(true) }.exceptionOrNull())
        f.tuning.rejection = null; f.observe(true)
        assertEquals(0, f.tuning.pending)
        assertTrue(f.writes.isEmpty())
    }

    @Test fun explicitLoadResetClearsOnlyRecordingWithoutRebindingTheProfileOrChangingCalibration() {
        val f = Fixture(); f.state.retainSelectionBinding("A"); f.observe(true); f.tuning.next()
        val before = f.state.projection()
        f.state.clearPendingDegradationRecording()
        assertEquals(before, f.state.projection())
        f.observe(true); assertEquals(1, f.tuning.pending)
    }

    @Test fun failedOldCommitRetainsTheOriginalUnconditionalClearPolicyAfterAReentrantBindingChange() {
        val f = Fixture(); f.state.retainSelectionBinding("A"); f.observe(true)
        f.onWrite = {
            f.state.retainSelectionBinding("B")
            f.observe(true)
        }
        f.commit = false; f.tuning.next()
        assertEquals(1, f.tuning.pending)
        f.onWrite = null; f.observe(true)
        assertEquals(2, f.tuning.pending) // Existing false-commit clearing is deliberately not converted to a CAS.
    }

    private data class Write(val binding: String, val pending: DeviceTuningRecheckReason?, val cursor: RuntimeCandidateQueueSnapshot?)
    private class Lane : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        var rejection: RuntimeException? = null
        var onSubmit: (() -> Unit)? = null
        var running = false
        val pending get() = tasks.size
        fun next() { running = true; try { tasks.removeFirst().run() } finally { running = false } }
        override fun execute(command: Runnable) { rejection?.let { throw it }; onSubmit?.invoke(); tasks.addLast(command) }
        override fun shutdown() = Unit
        override fun shutdownNow() = emptyList<Runnable>()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
    }
    private class Fixture {
        val tuning = Lane()
        val writes = mutableListOf<Write>()
        var commit = true
        var storageFailure: RuntimeException? = null
        var onWrite: (() -> Unit)? = null
        val detector = PrimaryDetectorExecutionOwner(Any(), Lane(), tuning, { error("native clock") },
            object : PrimaryNativeFlightScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) = error("native watchdog")
                override fun remove(task: Runnable) = error("native watchdog remove")
            }, { error("native authority") }, { error("auxiliary copy") }, { error("drop") },
            { _, _ -> error("native stall") }, { error("native publication") })
        val state = PrimaryRuntimeCalibrationOwner<Any>(detector, { error("calibration clock") },
            { _, _ -> error("native cancel") }, { error("schedule removal") })
        val coordinator = PrimaryRuntimeCalibrationCoordinator(state, detector,
            object : PrimaryCalibrationProfiles {
                override fun load(binding: String): RuntimeTuningProfile? = error("profile load")
                override fun loadAttempt(binding: String): DeviceTuningAttempt? = error("attempt load")
                override fun needsRecheck(binding: String, manual: Boolean): Boolean = error("profile recheck")
                override fun save(profile: RuntimeTuningProfile): Boolean = error("profile save")
                override fun markStarted(binding: String, reason: DeviceTuningRecheckReason, cursor: RuntimeCandidateQueueSnapshot?): Boolean = error("attempt start")
                override fun markFinished(binding: String, pending: DeviceTuningRecheckReason?, cursor: RuntimeCandidateQueueSnapshot?): Boolean {
                    check(tuning.running) { "storage must run on the original tuning lane" }
                    writes += Write(binding, pending, cursor)
                    onWrite?.invoke(); storageFailure?.let { throw it }
                    return commit
                }
            }, object : PrimaryCalibrationPlatform<AndroidFrameDetector, Any> {
                override fun createBinding(config: TwoModelRuntimeConfig, scope: RuntimeFeatureScope): String? = error("SDK binding")
                override fun load(config: TwoModelRuntimeConfig): PrimaryCalibrationLoad<AndroidFrameDetector>? = error("SDK load")
                override fun createSession(spec: PrimaryCalibrationSessionSpec<AndroidFrameDetector>, callbacks: PrimaryCalibrationSessionCallbacks): Any = error("SDK session")
                override fun startSession(session: Any) = error("SDK start")
                override fun calibratableDetector(detector: AndroidFrameDetector): AndroidFrameDetector? = error("SDK detector")
                override fun closeDetector(detector: AndroidFrameDetector) = error("SDK close")
            }, object : PrimaryAuxiliaryCalibration<AndroidFrameDetector> {
                override fun isAbsent(): Boolean = error("auxiliary status")
                override fun releaseBeforePrimary(): Boolean = error("auxiliary release")
                override fun prepare(config: TwoModelRuntimeConfig, primary: AndroidFrameDetector, candidate: RuntimeCandidate,
                    scope: RuntimeFeatureScope, manual: Boolean, deadlineMs: Long, current: () -> Boolean) = error("auxiliary preparation")
            }, object : PrimaryCalibrationEffects {
                override fun onUi(effect: () -> Unit) = error("UI publication")
                override fun cameraOwnerAvailable(): Boolean = error("camera")
                override fun cancelHomeWakeListening() = error("home wake")
                override fun verifyRunningDetector(detector: AndroidFrameDetector, modelKey: String?, fallbackUsed: Boolean) = error("detector verify")
                override fun resetRuntimeLoadMeasurements() = error("load reset")
                override fun measuredProfileStatus(profile: RuntimeTuningProfile) = error("profile status")
                override fun schedulePreparation(deadlineMs: Long) = error("preparation schedule")
                override fun closeDetectorAfterCalibration() = error("detector close")
                override fun comparisonDeadlineReached() = error("deadline")
                override fun previousDetectorReleaseFailed(error: RuntimeException) = error("native release failure")
            }, { error("worker clock") })
        fun observe(pending: Boolean) = state.recordPendingDegradationIfNeeded(pending, coordinator::recordPersistentDegradation)
    }
}
