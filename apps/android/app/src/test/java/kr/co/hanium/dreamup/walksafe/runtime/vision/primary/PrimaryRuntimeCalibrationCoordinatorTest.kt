package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class PrimaryRuntimeCalibrationCoordinatorTest {
    @Test fun constructionDoesNotObserveAuthorityClockProfilesOrSdk() {
        val f = Fixture(); assertTrue(f.events.isEmpty()); assertEquals(0, f.clockReads)
    }
    @Test fun staleActorStillReadsOriginalProfileEligibilityBeforeGuardButNeverCreatesSdkSession() {
        val f = Fixture(); f.authorized = false; f.prepare(); f.tuning.next()
        assertEquals(listOf("binding", "profile", "attempt", "recheck", "authority", "ui"), f.events)
        assertEquals(0, f.clockReads); f.ui.next(); assertFalse(f.state.projection().preparing)
    }
    @Test fun absentBindingSkipsOriginalAuthorityDeadlineAndProfileReads() {
        val f = Fixture(); f.binding = null; f.prepare(); f.tuning.next()
        assertEquals(listOf("binding", "ui"), f.events); assertEquals(0, f.clockReads)
    }
    @Test fun generationCancellationBeforeWorkerSkipsOriginalAuthorityAndDeadline() {
        val f = Fixture(); f.prepare(); f.state.cancel("pause"); f.tuning.next()
        assertFalse(f.events.contains("authority")); assertEquals(0, f.clockReads)
    }
    @Test fun strictDeadlineAtQueuedUiBoundarySkipsCameraWakeAndSdkFactory() {
        val f = Fixture(); f.prepare(); f.tuning.next(); f.now = f.deadline; f.ui.next()
        assertFalse(f.events.contains("camera")); assertFalse(f.events.contains("wake")); assertFalse(f.events.contains("session"))
        assertFalse(f.state.projection().preparing)
    }
    @Test fun actualCameraLeaseGuardPrecedesWakeAndSdkBudgetRead() {
        val f = Fixture(); f.camera = false; f.prepare(); f.tuning.next(); val reads = f.clockReads; f.ui.next()
        assertEquals(reads + 1, f.clockReads); assertTrue(f.events.contains("camera")); assertFalse(f.events.contains("wake"))
        assertFalse(f.events.contains("session"))
    }
    @Test fun primaryReleaseFailureAndAttemptCommitFailureDoNotStartSdkWork() {
        val f = Fixture(); f.release = false; f.prepare(); f.tuning.next(); f.ui.next()
        assertFalse(f.events.any { it.startsWith("started") }); assertNull(f.spec)
        val other = Fixture(); other.commit = false; other.prepare(); other.tuning.next(); other.ui.next()
        assertTrue(other.events.contains("started:INCOMPLETE_COMPARISON")); assertNull(other.spec)
    }
    @Test fun queuedPrimaryPreservesSourceBudgetCursorAndNullableConstructorIdentityThenInstallsBeforeStart() {
        val f = Fixture(); f.now = 120_000L; f.prepare(); f.tuning.next(); f.ui.next()
        assertEquals(60_000L, f.spec!!.budgetMs); assertSame(f.config, f.spec!!.config); assertSame(f.baseline, f.spec!!.baseline)
        assertTrue(f.constructorCurrent); assertSame(f.session, f.state.projection().session)
        assertEquals(listOf("wake", "clock", "session", "authority", "clock", "start:owned"), f.events.takeLast(6))
        assertEquals(f.candidate, f.spec!!.candidate)
    }
    @Test fun missingRecheckUsesTheSameAuxiliaryPeerAndOriginalCurrentnessClosure() {
        val f = Fixture(); f.recheck = false; f.prepare(); f.tuning.next()
        assertSame(f.baseline, f.auxiliaryPrimary); assertEquals(f.deadline, f.auxiliaryDeadline)
        assertNull(f.spec); assertTrue(f.auxiliaryCurrent!!.invoke()); f.authorized = false
        assertFalse(f.auxiliaryCurrent!!.invoke())
    }
    @Test fun manualRequestSkipsStoredRestoreAuxiliaryAbsenceAndLoad() {
        val f = Fixture(); f.profile = profile(RuntimeCandidate(RuntimeBackend.GPU, 2)); f.prepare(manual = true); f.tuning.next()
        assertFalse(f.events.contains("absent")); assertFalse(f.events.contains("load")); assertTrue(f.events.contains("release"))
    }
    @Test fun storedRestoreCopiesOnlyOriginalRuntimeOverridesAndCommitsAfterVerificationBeforePacingLoadStatus() {
        val f = Fixture(); val stored = profile(RuntimeCandidate(RuntimeBackend.GPU, 2)); f.profile = stored
        val replacement = NoopAndroidFrameDetector(); f.loaded = PrimaryCalibrationLoad(replacement, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, false)
        f.prepare(); f.tuning.next(); assertSame(f.baseline, f.detector.projection().detector)
        assertEquals("gpu", f.loadedConfig!!.unifiedWalksafe!!.runtime.delegate)
        assertEquals(2, f.loadedConfig!!.unifiedWalksafe!!.runtime.numThreads)
        assertFalse(f.loadedConfig!!.unifiedWalksafe!!.runtime.fallbackToCpu)
        assertNull(f.loadedConfig!!.fallbackModelKey); f.native.next()
        assertSame(replacement, f.detector.projection().detector); assertSame(stored, f.state.projection().activeProfile)
        assertSame(f.baseline, f.closed.single()); assertEquals(listOf("verify", "load-reset", "status", "close", "ui"), f.events.takeLast(5))
        assertTrue(f.state.projection().preparing); f.ui.next()
        assertFalse(f.state.projection().preparing); assertEquals(f.deadline, f.rescheduledDeadline)
    }
    @Test fun staleStoredReplacementIsClosedWithoutAdoptingAndClearsAttemptedBinding() {
        val f = Fixture(); f.profile = profile(RuntimeCandidate(RuntimeBackend.GPU, 2))
        val replacement = NoopAndroidFrameDetector(); f.loaded = PrimaryCalibrationLoad(replacement, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, false)
        f.prepare(); f.tuning.next(); f.authorized = false; f.native.next()
        assertSame(f.baseline, f.detector.projection().detector); assertSame(replacement, f.closed.single())
        assertNull(f.state.projection().restoreAttemptedBinding); f.ui.next(); assertFalse(f.state.projection().preparing)
    }
    @Test fun rejectedStoredLoadNativeEnqueueClosesReplacementAndFinishesWithoutInventingSuccess() {
        val f = Fixture(); f.profile = profile(RuntimeCandidate(RuntimeBackend.GPU, 2)); val replacement = NoopAndroidFrameDetector()
        f.loaded = PrimaryCalibrationLoad(replacement, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, false); f.native.reject = true
        f.prepare(); f.tuning.next(); assertSame(replacement, f.closed.single()); assertSame(f.baseline, f.detector.projection().detector)
        f.ui.next(); assertFalse(f.state.projection().preparing); assertEquals("binding", f.state.projection().restoreAttemptedBinding)
    }
    @Test fun storedLoadFallbackIsClosedThenExecutionRecoveryAttemptAndPrimaryRecheckUseOriginalOrder() {
        val f = Fixture(); f.profile = profile(RuntimeCandidate(RuntimeBackend.GPU, 2)); val replacement = NoopAndroidFrameDetector()
        f.loaded = PrimaryCalibrationLoad(replacement, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, true)
        f.prepare(); f.tuning.next(); assertSame(replacement, f.closed.single())
        assertTrue(f.events.indexOf("close") < f.events.indexOf("started:EXECUTION_RECOVERY"))
        assertTrue(f.events.indexOf("started:EXECUTION_RECOVERY") < f.events.indexOf("release"))
        f.ui.next(); assertNotNull(f.spec)
    }
    @Test fun actualOldNativeCloseFailureRetainsSessionAndFinishUntilAnActualAckWithoutReplay() {
        val f = Fixture(); f.start(); f.state.retainOldDetectorRelease(); f.callbacks!!.onClosed(null)
        assertSame(f.session, f.state.projection().session); assertEquals(0, f.tuning.pending)
        val failure = IllegalStateException("native close"); f.closeFailure = failure
        f.coordinator.closePrevious(f.baseline, f.state::oldDetectorReleaseAcknowledged)
        assertSame(f.session, f.state.projection().session); assertTrue(f.state.projection().oldDetectorReleasePending)
        assertEquals(1, f.closed.size); assertEquals(listOf("close", "release-failed"), f.events.takeLast(2))
        assertEquals(0, f.ui.pending); f.state.oldDetectorReleaseAcknowledged()
        assertNull(f.state.projection().session); assertEquals(2, f.tuning.pending); assertEquals(1, f.closed.size)
    }
    @Test fun closedOldSessionNeverClearsNewSessionButStillKeepsOriginalAttemptCompletion() {
        val f = Fixture(); f.start(); val newer = Session(1); f.state.installSession(newer); f.authorized = false
        f.callbacks!!.onClosed(null); assertSame(newer, f.state.projection().session); f.tuning.next()
        assertEquals(DeviceTuningRecheckReason.INCOMPLETE_COMPARISON, f.pendingReason); assertSame(newer, f.state.projection().session)
    }
    @Test fun closedSessionRebindsActualSelectedPrimaryAndCandidateForSameDeadlineAuxiliaryWork() {
        val f = Fixture(); f.start(); val selected = NoopAndroidFrameDetector(); val selectedCandidate = RuntimeCandidate(RuntimeBackend.GPU, 2)
        f.installDetector(selected); f.state.retainActiveCandidate(selectedCandidate); f.callbacks!!.onClosed(null)
        assertNull(f.state.projection().session); assertTrue(f.state.projection().preparing); f.tuning.next(); f.tuning.next()
        assertSame(selected, f.auxiliaryPrimary); assertEquals(selectedCandidate, f.auxiliaryCandidate)
        assertEquals(f.deadline, f.auxiliaryDeadline); assertTrue(f.auxiliaryCurrent!!.invoke())
    }
    @Test fun comparisonAtDeadlineRetainsConfirmedSettingsAndFinishesAttemptWithoutNewSdkWork() {
        val f = Fixture(); f.start(); f.now = f.deadline; f.callbacks!!.onClosed(null)
        assertEquals(1, f.tuning.pending); assertTrue(f.events.contains("expired")); f.tuning.next()
        assertEquals(DeviceTuningRecheckReason.INCOMPLETE_COMPARISON, f.pendingReason); assertNull(f.state.projection().session)
    }
    @Test fun deferredCloseIsConsumedBeforeFlowAuthorityAndNoAuxiliaryOrDeadlineRead() {
        val f = Fixture(); f.start(); assertTrue(f.state.deferDetectorCloseIfCalibrationPresent { error("owned") })
        f.events.clear(); val reads = f.clockReads; f.callbacks!!.onClosed(null)
        assertEquals(listOf("close-after"), f.events); assertEquals(reads, f.clockReads)
        assertFalse(f.state.projection().detectorCloseAfterCalibration); f.tuning.next()
    }
    @Test fun selectionProfileSavePrecedesNativeTransferAndOldCloseAcknowledgesOnlyAfterUiCallback() {
        val f = Fixture(); val selected = unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector
        val p = profile(RuntimeCandidate(RuntimeBackend.GPU, 2)); val load = AndroidDetectorLoadResult(selected, true, true,
            TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, false, "ok")
        val selection = RuntimeCalibrationSelection(p, load, false, { swap -> swap(load) }, { f.events += "reject" })
        f.coordinator.adopt(selection, f.baseline) { true }; assertFalse(f.events.contains("save")); f.tuning.next()
        assertEquals(listOf("save"), f.events); f.native.next()
        assertSame(selected, f.detector.projection().detector); assertSame(p, f.state.projection().activeProfile)
        assertTrue(f.state.projection().oldDetectorReleasePending); assertSame(f.baseline, f.closed.single())
        f.ui.next(); assertFalse(f.state.projection().oldDetectorReleasePending)
    }
    @Test fun staleSelectionAndFailedSaveRejectBeforeNativeEnqueueAndRetainActualPointer() {
        val f = Fixture(); val load = AndroidDetectorLoadResult(null, true, false, null, false, "none")
        val selection = RuntimeCalibrationSelection(profile(f.candidate), load, true, { error("adopt") }, { f.events += "reject" })
        f.coordinator.adopt(selection, f.baseline) { false }; f.tuning.next()
        assertEquals(listOf("reject"), f.events); assertEquals(0, f.native.pending)
        f.events.clear(); f.commit = false; f.coordinator.adopt(selection, f.baseline) { true }; f.tuning.next()
        assertEquals(listOf("save", "reject"), f.events); assertSame(f.baseline, f.detector.projection().detector)
    }
    @Test fun rejectedSelectionNativeEnqueueRejectsAfterSaveAndDoesNotRetainOldRelease() {
        val f = Fixture(); val load = AndroidDetectorLoadResult(null, true, false, null, false, "none")
        val selection = RuntimeCalibrationSelection(profile(f.candidate), load, false, { error("adopt") }, { f.events += "reject" })
        f.native.reject = true; f.coordinator.adopt(selection, f.baseline) { true }; f.tuning.next()
        assertEquals(listOf("save", "reject"), f.events); assertFalse(f.state.projection().oldDetectorReleasePending)
    }

    private data class Session(val id: Int)
    private class Lane : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>(); var reject = false
        val pending get() = tasks.size
        fun next() = tasks.removeFirst().run()
        override fun execute(command: Runnable) { if (reject) throw RejectedExecutionException(); tasks.addLast(command) }
        override fun shutdown() = Unit
        override fun shutdownNow() = emptyList<Runnable>()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
    }
    private class Fixture {
        val events = mutableListOf<String>(); var now = 0L; var clockReads = 0
        val tuning = Lane(); val native = Lane(); val ui = Lane()
        val baseline: AndroidFrameDetector = NoopAndroidFrameDetector()
        val candidate = RuntimeCandidate(RuntimeBackend.CPU, 1); val deadline = 300_000L
        val config = TwoModelRuntimeConfig("test", null, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "fallback",
            ModelRuntimeConfig("unified", "asset", 32, emptyList(), emptySet(), emptyMap()), null, null)
        var authorized = true; var binding: String? = "binding"; var recheck = true; var release = true; var commit = true; var camera = true
        var profile: RuntimeTuningProfile? = null; var loaded: PrimaryCalibrationLoad<AndroidFrameDetector>? = null
        var loadedConfig: TwoModelRuntimeConfig? = null; var spec: PrimaryCalibrationSessionSpec<AndroidFrameDetector>? = null
        var callbacks: PrimaryCalibrationSessionCallbacks? = null; val session = Session(1); var constructorCurrent = false
        var closeFailure: RuntimeException? = null; val closed = mutableListOf<AndroidFrameDetector>(); var rescheduledDeadline = -1L
        var auxiliaryPrimary: AndroidFrameDetector? = null; var auxiliaryCandidate: RuntimeCandidate? = null
        var auxiliaryDeadline = -1L; var auxiliaryCurrent: (() -> Boolean)? = null; var pendingReason: DeviceTuningRecheckReason? = null
        val detector = PrimaryDetectorExecutionOwner(Any(), native, tuning, { error("native clock") },
            object : PrimaryNativeFlightScheduler { override fun postDelayed(task: Runnable, delayMs: Long) = false; override fun remove(task: Runnable) = Unit },
            { false }, { false }, {}, { _, _ -> }, { it() })
        val clock = { clockReads += 1; events += "clock"; now }
        val state = PrimaryRuntimeCalibrationOwner<Session>(detector, clock, { _, reason -> events += "cancel:$reason" }, {})
        val coordinator = PrimaryRuntimeCalibrationCoordinator(state, detector,
            object : PrimaryCalibrationProfiles {
                override fun load(binding: String): RuntimeTuningProfile? { events += "profile"; return profile }
                override fun loadAttempt(binding: String): DeviceTuningAttempt? { events += "attempt"; return null }
                override fun needsRecheck(binding: String, manual: Boolean): Boolean { events += "recheck"; return recheck }
                override fun save(profile: RuntimeTuningProfile): Boolean { events += "save"; return commit }
                override fun markStarted(binding: String, reason: DeviceTuningRecheckReason, cursor: RuntimeCandidateQueueSnapshot?): Boolean {
                    events += "started:$reason"; return commit
                }
                override fun markFinished(binding: String, pending: DeviceTuningRecheckReason?, cursor: RuntimeCandidateQueueSnapshot?): Boolean {
                    events += "finished"; pendingReason = pending; return true
                }
            }, object : PrimaryCalibrationPlatform<AndroidFrameDetector, Session> {
                override fun createBinding(config: TwoModelRuntimeConfig, scope: RuntimeFeatureScope): String? { events += "binding"; return binding }
                override fun load(config: TwoModelRuntimeConfig): PrimaryCalibrationLoad<AndroidFrameDetector>? { events += "load"; loadedConfig = config; return loaded }
                override fun createSession(spec: PrimaryCalibrationSessionSpec<AndroidFrameDetector>, callbacks: PrimaryCalibrationSessionCallbacks): Session {
                    events += "session"; this@Fixture.spec = spec; this@Fixture.callbacks = callbacks; constructorCurrent = callbacks.isCurrent(); return session
                }
                override fun startSession(session: Session) { events += if (state.sessionIsOwned(session)) "start:owned" else "start:foreign" }
                override fun calibratableDetector(detector: AndroidFrameDetector): AndroidFrameDetector = detector
                override fun closeDetector(detector: AndroidFrameDetector) { events += "close"; closed += detector; closeFailure?.let { throw it } }
            }, object : PrimaryAuxiliaryCalibration<AndroidFrameDetector> {
                override fun isAbsent(): Boolean { events += "absent"; return true }
                override fun releaseBeforePrimary(): Boolean { events += "release"; return release }
                override fun prepare(config: TwoModelRuntimeConfig, primary: AndroidFrameDetector, candidate: RuntimeCandidate,
                    scope: RuntimeFeatureScope, manual: Boolean, deadlineMs: Long, current: () -> Boolean) {
                    events += "auxiliary"; auxiliaryPrimary = primary; auxiliaryCandidate = candidate; auxiliaryDeadline = deadlineMs; auxiliaryCurrent = current
                }
            }, object : PrimaryCalibrationEffects {
                override fun onUi(effect: () -> Unit) { events += "ui"; ui.execute(effect) }
                override fun cameraOwnerAvailable(): Boolean { events += "camera"; return camera }
                override fun cancelHomeWakeListening() { events += "wake" }
                override fun verifyRunningDetector(detector: AndroidFrameDetector, modelKey: String?, fallbackUsed: Boolean) { events += "verify" }
                override fun resetRuntimeLoadMeasurements() { events += "load-reset" }
                override fun measuredProfileStatus(profile: RuntimeTuningProfile) { events += "status" }
                override fun schedulePreparation(deadlineMs: Long) { events += "schedule"; rescheduledDeadline = deadlineMs }
                override fun closeDetectorAfterCalibration() { events += "close-after" }
                override fun comparisonDeadlineReached() { events += "expired" }
                override fun previousDetectorReleaseFailed(error: RuntimeException) { events += "release-failed" }
            }, clock)
        init { installDetector(baseline); state.retainActiveCandidate(candidate) }
        fun installDetector(owned: AndroidFrameDetector) = PrimaryDetectorExecutionOwner::class.java.getDeclaredField("detector").apply { isAccessible = true }.set(detector, owned)
        fun prepare(manual: Boolean = false) = coordinator.prepare(PrimaryCalibrationRequest(baseline, config, candidate,
            RuntimeFeatureScope.CAMERA_TRACKING, manual, deadline) { events += "authority"; authorized })
        fun start() { prepare(); tuning.next(); ui.next(); events.clear() }
    }
    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        private fun profile(candidate: RuntimeCandidate) = RuntimeTuningProfile("binding", candidate,
            RuntimeFeatureScope.CAMERA_TRACKING, "fixture-v", "fixture-hash", RuntimeSelectionPolicy.VERSION,
            100L, RuntimeConfirmedMeasurement("binding", RuntimeFeatureScope.CAMERA_TRACKING, candidate.backend,
                1, 1, 100.0, 90.0, 1.0, "fixture-v", "fixture-hash", 1, 1_000L))
    }
}
