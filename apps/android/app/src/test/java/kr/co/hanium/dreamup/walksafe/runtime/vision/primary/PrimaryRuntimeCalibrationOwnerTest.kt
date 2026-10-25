package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.inference.*
import org.junit.Assert.*
import org.junit.Test

class PrimaryRuntimeCalibrationOwnerTest {
    @Test fun constructionDoesNotReadClockOrNativeSessionOrScheduledEffects() {
        val f = Fixture()
        assertEquals(0, f.clockReads)
        assertTrue(f.events.isEmpty())
        assertEquals(0L, f.calibration.projection().generation)
        assertNull(f.calibration.projection().session)
    }

    @Test fun staleGenerationAndClosingDetectorSkipTheOriginalAuthorityAndDeadlineClock() {
        val f = Fixture(); val generation = f.calibration.beginPreparation()
        assertFalse(f.calibration.baselineIsCurrent(generation - 1L, f.baseline, 900L) { error("stale authority") })
        f.detector.fenceForClose()
        assertFalse(f.calibration.baselineIsCurrent(generation, f.baseline, 900L) { error("closing authority") })
        assertEquals(0, f.clockReads)
    }

    @Test fun baselineIdentityAndDeadlineRetainTheirOriginalStrictClockBoundary() {
        val f = Fixture(); val generation = f.calibration.beginPreparation()
        assertFalse(f.calibration.baselineIsCurrent(generation, NoopAndroidFrameDetector(), 900L) { true })
        assertEquals(0, f.clockReads)
        f.now = 899L
        assertTrue(f.calibration.baselineIsCurrent(generation, f.baseline, 900L) { true })
        f.now = 900L
        assertFalse(f.calibration.baselineIsCurrent(generation, f.baseline, 900L) { true })
        assertEquals(2, f.clockReads)
    }

    @Test fun cancellationAdvancesGenerationBeforeNativeCancelAndDoesNotInventPreparationSuccess() {
        val f = Fixture(); val generation = f.calibration.beginPreparation(); val session = Session(1)
        f.calibration.installSession(session)
        f.calibration.cancel("pause")
        assertEquals(generation + 1L, f.cancelGeneration)
        assertEquals(listOf("cancel:pause"), f.events)
        assertSame(session, f.calibration.projection().session)
        assertTrue(f.calibration.projection().preparing)
    }

    @Test fun nativeCancelFailureKeepsTheActualSessionAndAlreadyAdvancedGeneration() {
        val f = Fixture(); val generation = f.calibration.beginPreparation(); val session = Session(1)
        f.calibration.installSession(session); val failure = IllegalStateException("cancel")
        f.cancelFailure = failure
        assertSame(failure, runCatching { f.calibration.cancel("stop") }.exceptionOrNull())
        assertEquals(generation + 1L, f.calibration.projection().generation)
        assertSame(session, f.calibration.projection().session)
        assertTrue(f.calibration.projection().preparing)
    }

    @Test fun removingScheduledPreparationPrecedesClearAndFailurePreservesTheScheduleAndGeneration() {
        val f = Fixture(); val scheduled = Runnable {}
        PrimaryRuntimeCalibrationOwner::class.java.getDeclaredField("schedule").apply { isAccessible = true }.set(f.calibration, scheduled)
        val failure = IllegalStateException("remove")
        f.removeFailure = failure
        assertSame(failure, runCatching { f.calibration.cancel("stop") }.exceptionOrNull())
        assertSame(scheduled, f.calibration.projection().schedule)
        assertEquals(0L, f.calibration.projection().generation)
        assertEquals(listOf("remove"), f.events)
        f.removeFailure = null; f.calibration.clearScheduledPreparation()
        assertNull(f.calibration.projection().schedule)
    }

    @Test fun oldClosedSessionCannotClearANewerEqualLookingNativeSession() {
        val f = Fixture(); val previous = Session(1); val next = Session(1)
        f.calibration.installSession(next)
        assertFalse(f.calibration.sessionIsOwned(previous))
        f.calibration.clearClosedSessionIfOwned(previous)
        assertSame(next, f.calibration.projection().session)
        f.calibration.clearClosedSessionIfOwned(next)
        assertNull(f.calibration.projection().session)
        assertTrue(f.calibration.sessionIsOwned(null)) // preserves the original nullable constructor callback identity.
    }

    @Test fun anOwnedCalibrationDefersDetectorCloseWithoutQueryingTheAuxiliaryOwner() {
        val f = Fixture(); f.calibration.installSession(Session(1))
        assertTrue(f.calibration.deferDetectorCloseIfCalibrationPresent { error("owned primary skips auxiliary") })
        assertTrue(f.calibration.consumeDeferredDetectorClose())
        assertFalse(f.calibration.consumeDeferredDetectorClose())
    }

    @Test fun noCalibrationDoesNotRetainADeferredCloseButActualAuxiliaryCalibrationDoes() {
        val f = Fixture()
        assertFalse(f.calibration.deferDetectorCloseIfCalibrationPresent { false })
        assertFalse(f.calibration.projection().detectorCloseAfterCalibration)
        assertTrue(f.calibration.deferDetectorCloseIfCalibrationPresent { true })
        assertTrue(f.calibration.projection().detectorCloseAfterCalibration)
    }

    @Test fun uncertainOldNativeReleaseRetainsCompletionUntilAnActualAcknowledgement() {
        val f = Fixture(); var finished = 0
        f.calibration.retainOldDetectorRelease()
        f.calibration.oldDetectorReleaseFailed()
        f.calibration.awaitOldDetectorReleaseOrFinish { finished += 1 }
        assertEquals(0, finished)
        assertTrue(f.calibration.projection().oldDetectorReleasePending)
        assertNotNull(f.calibration.projection().afterOldDetectorRelease)
        f.calibration.oldDetectorReleaseAcknowledged()
        assertEquals(1, finished)
        assertFalse(f.calibration.projection().oldDetectorReleasePending)
        assertNull(f.calibration.projection().afterOldDetectorRelease)
        f.calibration.oldDetectorReleaseAcknowledged()
        assertEquals(1, finished)
    }

    @Test fun successfulOldReleaseClearsPendingStateBeforeItsThrowingCompletionEffect() {
        val f = Fixture(); val failure = IllegalStateException("completion")
        f.calibration.retainOldDetectorRelease()
        f.calibration.awaitOldDetectorReleaseOrFinish { throw failure }
        assertSame(failure, runCatching { f.calibration.oldDetectorReleaseAcknowledged() }.exceptionOrNull())
        assertFalse(f.calibration.projection().oldDetectorReleasePending)
        assertNull(f.calibration.projection().afterOldDetectorRelease)
    }

    @Test fun manualRestoreGuardSkipsAuxiliaryQueryAndAStoredAttemptIsNotRepeated() {
        val f = Fixture(); val candidate = RuntimeCandidate(RuntimeBackend.CPU, 1)
        val selected = profile(RuntimeCandidate(RuntimeBackend.GPU, 1))
        assertFalse(f.calibration.beginStoredRestorationIfNeeded(true, { error("manual auxiliary query") }, selected, candidate, "binding"))
        assertTrue(f.calibration.beginStoredRestorationIfNeeded(false, { true }, selected, candidate, "binding"))
        assertFalse(f.calibration.beginStoredRestorationIfNeeded(false, { true }, selected, candidate, "binding"))
        f.calibration.storedRestorationDidNotAdopt()
        assertTrue(f.calibration.beginStoredRestorationIfNeeded(false, { true }, selected, candidate, "binding"))
        f.calibration.retainMeasuredProfile(selected)
        assertSame(selected, f.calibration.projection().activeProfile)
        assertEquals(selected.candidate, f.calibration.projection().activeCandidate)
    }

    private data class Session(val id: Int)
    private class Fixture {
        var now = 0L
        var clockReads = 0
        val events = mutableListOf<String>()
        var cancelGeneration = -1L
        var cancelFailure: RuntimeException? = null
        var removeFailure: RuntimeException? = null
        val detector = PrimaryDetectorExecutionOwner(Any(), Lane(), Lane(), { error("native constructor clock") },
            object : PrimaryNativeFlightScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) = error("native scheduler")
                override fun remove(task: Runnable) = Unit
            }, { false }, { false }, {}, { _, _ -> }, { it() })
        val baseline = detector.projection().detector
        val calibration: PrimaryRuntimeCalibrationOwner<Session> = PrimaryRuntimeCalibrationOwner<Session>(detector, { clockReads += 1; now },
            { _, reason -> events += "cancel:$reason"; cancelGeneration = calibration.projection().generation; cancelFailure?.let { throw it } },
            { events += "remove"; removeFailure?.let { throw it } })
    }
    private class Lane : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow() = emptyList<Runnable>()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
    }
    companion object {
        private fun profile(candidate: RuntimeCandidate) = RuntimeTuningProfile("binding", candidate,
            RuntimeFeatureScope.CAMERA_TRACKING, "fixture-v", "fixture-hash", RuntimeSelectionPolicy.VERSION,
            100L, RuntimeConfirmedMeasurement("binding", RuntimeFeatureScope.CAMERA_TRACKING, candidate.backend,
                1, 1, 100.0, 90.0, 1.0, "fixture-v", "fixture-hash", 1, 1_000L))
    }
}
