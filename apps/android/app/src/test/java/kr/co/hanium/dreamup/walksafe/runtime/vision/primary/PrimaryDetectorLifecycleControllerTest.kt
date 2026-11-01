package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.io.Closeable
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.AndroidFixedFixtureRuntimeSelector
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeTestFixtures
import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import kr.co.hanium.dreamup.walksafe.inference.composite.AndroidJointRuntimeLoader
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeReleaseFence
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortBusyException
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionReleaseReceipt
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class PrimaryDetectorLifecycleControllerTest {
    @Test fun constructionAndMetadataNeverReadSdkAuthorityClockFrameOrNative() {
        val f = Fixture(); assertTrue(f.events.isEmpty()); assertEquals(0, f.clockReads)
        assertEquals("detector=load_pending_after_camera_gate", f.lifecycle.metadata().statusText)
        assertFalse(f.lifecycle.metadata().loadAttempted); assertFalse(f.lifecycle.metadata().configLoaded)
        assertNull(f.lifecycle.verifiedIdentity()); assertFalse(f.lifecycle.blocksWalkEntry()); assertFalse(f.lifecycle.verifiedForOutput())
        assertTrue(f.events.isEmpty())
    }
    @Test fun loadAttemptedEarlyReturnNeverQueriesDestroyedMetricOrWalkAgain() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess(); f.events.clear(); f.lifecycle.loadForCurrentProcess()
        assertTrue(f.events.isEmpty()); assertEquals(1, f.tuning.pending)
    }
    @Test fun closingEarlyReturnSkipsDestroyedAndAllLaterFacts() {
        val f = Fixture(); f.execution.fenceForClose(); f.lifecycle.loadForCurrentProcess()
        assertTrue(f.events.isEmpty()); assertEquals(0, f.tuning.pending); assertFalse(f.lifecycle.metadata().loadAttempted)
    }
    @Test fun nativeLoadPointerIsAdoptedBeforeUiAvailabilityAndMetadataAndVerification() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess(); assertEquals(listOf("destroyed", "metric"), f.events)
        assertTrue(f.lifecycle.metadata().loadInFlight); f.tuning.next()
        assertSame(f.original, f.execution.projection().detector); f.native.next()
        assertSame(f.instance, f.execution.projection().detector); assertFalse(f.execution.projection().available)
        assertFalse(f.lifecycle.metadata().configLoaded); f.ui.next()
        assertTrue(f.execution.projection().available); assertFalse(f.lifecycle.metadata().loadInFlight)
        assertTrue(f.lifecycle.metadata().configLoaded); assertEquals("unified_walksafe", f.lifecycle.metadata().reportModelKey)
        assertEquals(RuntimeCandidate(RuntimeBackend.GPU, 2), f.calibration.projection().activeCandidate)
        assertEquals(RunningDetectorVerificationState.PENDING, f.lifecycle.verificationState(f.instance))
        assertEquals(listOf("delegate", "config-current", "processors", "clock", "timer:15000", "gate", "loaded"), f.events.takeLast(7))
    }
    @Test fun defaultLoadKeepsOriginalNullConfigPathAndNoSelectorProgress() {
        val f = Fixture(); f.config = null; f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        assertEquals(listOf("config-load", "default"), f.events.takeLast(2)); assertNull(f.progress)
    }
    @Test fun explicitDeviceCheckAllowsComparisonWhileOrdinaryLoadingDoesNot() {
        val ordinary = Fixture(); ordinary.lifecycle.loadForCurrentProcess(); ordinary.tuning.next()
        assertTrue(ordinary.events.contains("select:false"))
        val check = Fixture(); check.lifecycle.loadForCurrentProcess(allowComparison = true); check.tuning.next()
        assertTrue(check.events.contains("select:true"))
        assertFalse(check.events.contains("select:false"))
        check.lifecycle.loadForCurrentProcess(allowComparison = true)
        assertEquals(0, check.tuning.pending)
    }
    @Test fun baselineAccuracyRefusalRemainsSeparateFromActualInstanceVerification() {
        val f = Fixture()
        f.selectWork = { AndroidDetectorLoadResult(f.instance, true, true, "unified_walksafe", false,
            AndroidFixedFixtureRuntimeSelector.BASELINE_REJECTED_REASON) }
        f.lifecycle.loadForCurrentProcess(allowComparison = true)
        f.tuning.next(); f.native.next(); f.ui.next()
        assertEquals(AndroidFixedFixtureRuntimeSelector.BASELINE_REJECTED_REASON, f.lifecycle.metadata().loadReason)
        assertTrue(f.execution.projection().available)
        assertEquals(RunningDetectorVerificationState.PENDING, f.lifecycle.verificationState(f.instance))
        assertFalse(f.lifecycle.verifiedForOutput())
        assertFalse(f.lifecycle.blocksWalkEntry())
        f.native.next(); f.ui.next()
        assertEquals(RunningDetectorVerificationState.VERIFIED, f.lifecycle.verificationState(f.instance))
        assertTrue(f.lifecycle.verifiedForOutput())
        assertEquals(AndroidFixedFixtureRuntimeSelector.BASELINE_REJECTED_REASON, f.lifecycle.metadata().loadReason)
    }
    @Test fun explicitCheckAfterOrdinaryCompletionDrainsAndClosesTheOldOwnerBeforeSelection() {
        val f = Fixture(); f.completeLoad()
        val oldGeneration = f.execution.projection().loadGeneration
        val replacement = unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector
        f.selectWork = {
            assertEquals(listOf(f.instance), f.closed)
            assertTrue(f.execution.projection().detector is NoopAndroidFrameDetector)
            AndroidDetectorLoadResult(replacement, true, true, "unified_walksafe", false, "loaded")
        }
        f.events.clear(); f.lifecycle.loadForCurrentProcess(allowComparison = true)
        assertEquals(0, f.tuning.pending); assertEquals(1, f.native.pending)
        assertTrue(f.lifecycle.metadata().loadInFlight)
        f.native.next()
        assertEquals(oldGeneration + 1L, f.execution.projection().loadGeneration)
        assertFalse(f.execution.projection().available); assertNull(f.lifecycle.verifiedIdentity())
        assertEquals(1, f.tuning.pending)
        f.tuning.next(); assertTrue(f.events.contains("select:true"))
        f.native.next(); f.ui.next()
        assertSame(replacement, f.execution.projection().detector)
        assertEquals(RunningDetectorVerificationState.PENDING, f.lifecycle.verificationState(replacement))
        assertFalse(f.lifecycle.verifiedForOutput())
        f.native.next(); f.ui.next(); assertTrue(f.lifecycle.verifiedForOutput())
        f.events.clear(); f.lifecycle.loadForCurrentProcess(allowComparison = true)
        assertTrue(f.events.isEmpty()); assertEquals(0, f.tuning.pending); assertEquals(0, f.native.pending)
        assertEquals(listOf(f.instance), f.closed)
    }
    @Test fun explicitCheckRequestedDuringOrdinaryLoadingIsNotLostAtUiAdmission() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess()
        f.lifecycle.loadForCurrentProcess(allowComparison = true)
        f.tuning.next(); assertTrue(f.events.contains("select:false"))
        f.native.next(); f.ui.next()
        assertTrue(f.lifecycle.metadata().loadInFlight)
        f.native.next() // Original sample drains before the retirement barrier.
        f.native.next(); assertEquals(listOf(f.instance), f.closed)
        f.tuning.next(); assertTrue(f.events.contains("select:true"))
    }
    @Test fun cancelledUpgradeCannotCloseTheRetainedOwnerOrLeakItOnTheNextRequest() {
        val f = Fixture(); f.completeLoad()
        f.lifecycle.loadForCurrentProcess(allowComparison = true); f.lifecycle.cancelPendingLoad()
        f.native.next(); assertTrue(f.closed.isEmpty()); assertSame(f.instance, f.execution.projection().detector)
        f.lifecycle.loadForCurrentProcess(allowComparison = true)
        f.native.next(); assertEquals(listOf(f.instance), f.closed)
        f.tuning.next(); assertTrue(f.events.contains("select:true"))
    }
    @Test fun failedOldNativeReleaseNeverStartsSelectionOrAuthorizesARetry() {
        val f = Fixture(); f.completeLoad(); f.closeFailure = IllegalStateException("native release")
        f.events.clear(); f.lifecycle.loadForCurrentProcess(allowComparison = true); f.native.next(); f.ui.next()
        assertTrue(f.execution.projection().closing); assertFalse(f.execution.projection().available)
        assertFalse(f.lifecycle.metadata().configLoaded); assertEquals(0, f.tuning.pending)
        f.lifecycle.allowLoadRetryIfUnavailable(); f.lifecycle.loadForCurrentProcess(allowComparison = true)
        assertEquals(0, f.tuning.pending); assertEquals(0, f.native.pending)
        assertFalse(f.events.contains("select:true")); assertEquals(listOf(f.instance), f.closed)
    }
    @Test fun rejectedReloadBarrierRetainsTheOldOwnerWithoutStartingAnotherSelector() {
        val f = Fixture(); f.completeLoad(); f.native.reject = true; f.events.clear()
        f.lifecycle.loadForCurrentProcess(allowComparison = true); f.ui.next()
        assertSame(f.instance, f.execution.projection().detector); assertTrue(f.closed.isEmpty())
        assertFalse(f.lifecycle.metadata().configLoaded); assertFalse(f.lifecycle.metadata().loadInFlight)
        assertEquals(0, f.tuning.pending); assertFalse(f.events.contains("select:true"))
    }
    @Test fun cancellingAfterOrdinaryAdoptionCancelsOnlyTheQueuedComparisonIntent() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess(); f.tuning.next(); f.native.next()
        f.lifecycle.loadForCurrentProcess(allowComparison = true); f.lifecycle.cancelPendingLoad()
        f.ui.next(); assertFalse(f.lifecycle.metadata().loadInFlight)
        f.native.next(); f.ui.next(); assertTrue(f.lifecycle.verifiedForOutput())
        assertFalse(f.events.contains("select:true")); assertTrue(f.closed.isEmpty())
    }
    @Test fun upgradeCannotReplaceAnActiveWalkOrCalibrationOwner() {
        val walking = Fixture(); walking.completeLoad(); walking.walking = true
        walking.lifecycle.loadForCurrentProcess(allowComparison = true)
        assertEquals(0, walking.native.pending); assertTrue(walking.closed.isEmpty())
        walking.walking = false; walking.lifecycle.loadForCurrentProcess(allowComparison = true)
        assertEquals(1, walking.native.pending)
        val calibrating = Fixture(); calibrating.completeLoad(); calibrating.calibrationPresent = true
        calibrating.lifecycle.loadForCurrentProcess(allowComparison = true)
        assertEquals(0, calibrating.native.pending); assertTrue(calibrating.closed.isEmpty())
    }
    @Test fun aWalkStartingBeforeTheRetirementBarrierPreventsTheUpgrade() {
        val f = Fixture(); f.completeLoad(); f.lifecycle.loadForCurrentProcess(allowComparison = true)
        f.walking = true; f.native.next()
        assertSame(f.instance, f.execution.projection().detector); assertTrue(f.closed.isEmpty())
        assertTrue(f.lifecycle.verifiedForOutput()); assertFalse(f.lifecycle.metadata().loadInFlight)
        assertEquals(0, f.tuning.pending)
        f.walking = false; f.lifecycle.loadForCurrentProcess(allowComparison = true)
        f.native.next(); assertEquals(listOf(f.instance), f.closed)
    }
    @Test fun selectorCurrentnessKeepsDestroyedBeforeClosingBeforeGenerationWithoutNewClock() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess(); f.tuning.next(); assertTrue(f.selectorCurrent!!.invoke())
        f.execution.fenceForClose(); f.events.clear(); assertFalse(f.selectorCurrent!!.invoke())
        assertEquals(listOf("destroyed"), f.events); assertEquals(0, f.clockReads)
    }
    @Test fun progressReadsVisibilityOnlyAfterDestroyedAndActualInFlightAndRecordsStatusBeforeEffect() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess(); f.tuning.next(); f.progress!!.invoke("loading")
        f.destroyed = true; f.events.clear(); f.ui.next(); assertEquals(listOf("destroyed"), f.events)
        assertEquals("detector=load_pending_after_camera_gate", f.lifecycle.metadata().statusText)
        f.destroyed = false; f.progress!!.invoke("ready"); f.ui.next()
        assertEquals("ready", f.lifecycle.metadata().statusText); assertEquals("progress:ready", f.events.last())
    }
    @Test fun preparationFailureBecomesOriginalUnavailableLoadAndUiCapabilityEffectsKeepMetricSnapshot() {
        val f = Fixture(); f.loadFailure = IllegalArgumentException("model"); f.lifecycle.loadForCurrentProcess()
        f.tuning.next(); f.native.next(); f.ui.next()
        assertEquals("IllegalArgumentException", f.lifecycle.metadata().loadReason)
        assertFalse(f.execution.projection().available); assertFalse(f.lifecycle.metadata().configLoaded)
        assertTrue(f.events.contains("log:IllegalArgumentException")); assertEquals(listOf("gate", "unavailable:true", "loaded"), f.events.takeLast(3))
    }
    @Test fun logFailureKeepsOriginalPartialLoadFlagsAndDoesNotQueueNativeAdoption() {
        val f = Fixture(); f.loadFailure = IllegalStateException("load"); val failure = IllegalArgumentException("log")
        f.logFailure = failure; f.lifecycle.loadForCurrentProcess()
        assertSame(failure, runCatching { f.tuning.next() }.exceptionOrNull())
        assertTrue(f.lifecycle.metadata().loadAttempted); assertTrue(f.lifecycle.metadata().loadInFlight); assertEquals(0, f.native.pending)
    }
    @Test fun rejectedOrStaleNativeLoadClosesOnlyOwnedCandidateAndDoesNotAdmitMetadata() {
        val f = Fixture(); f.native.reject = true; f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        assertSame(f.instance, f.closed.single()); assertTrue(f.lifecycle.metadata().loadInFlight); assertEquals(0, f.ui.pending)
        val stale = Fixture(); stale.lifecycle.loadForCurrentProcess(); stale.tuning.next(); stale.execution.fenceForClose(); stale.native.next()
        assertSame(stale.instance, stale.closed.single()); assertSame(stale.original, stale.execution.projection().detector)
    }
    @Test fun uiStaleLoadClearsInFlightBeforeGuardButKeepsOriginalMetadataAndNativePointer() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess(); f.tuning.next(); f.native.next(); f.execution.fenceForClose(); f.ui.next()
        assertFalse(f.lifecycle.metadata().loadInFlight); assertFalse(f.lifecycle.metadata().configLoaded)
        assertSame(f.instance, f.execution.projection().detector); assertEquals(0, f.native.pending)
    }
    @Test fun recordRejectsWrongInstanceOrGenerationBeforeCallingProvidedClock() {
        val f = Fixture(); f.install(f.instance); f.lifecycle.beginVerification(f.instance, 0L)
        val evidence = RunningDetectorLoadEvidence(true, true, true)
        assertEquals(RunningDetectorVerificationStep.STALE, f.lifecycle.recordVerification(NoopAndroidFrameDetector(), null, evidence, 0L) { error("foreign clock") })
        assertEquals(RunningDetectorVerificationStep.STALE, f.lifecycle.recordVerification(f.instance, null, evidence, 1L) { error("generation clock") })
        assertEquals(0, f.clockReads)
    }
    @Test fun sameInstanceSuccessfulSampleVerifiesExactConfigAndGenerationAndOnlyThenQueuesUi() {
        val f = Fixture(); f.install(f.instance); f.lifecycle.scheduleVerification(f.instance, "unified_walksafe", false)
        assertEquals(listOf("clock", "timer:15000"), f.events); assertNull(f.lifecycle.verifiedIdentity())
        f.native.next(); assertNotNull(f.lifecycle.verifiedIdentity()); assertEquals(1, f.ui.pending)
        assertTrue(f.lifecycle.verificationAllows(f.instance, f.lifecycle.verifiedIdentity()))
        assertFalse(f.lifecycle.verificationAllows(unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector, f.lifecycle.verifiedIdentity()))
        f.ui.next(); assertEquals("verified", f.events.last()); assertEquals(2, f.clockReads)
    }
    @Test fun staleQueuedVerificationSkipsSdkSampleConfigurationHashAndAttemptClock() {
        val f = Fixture(); f.lifecycle.scheduleVerification(f.instance, "unified_walksafe", false); f.events.clear(); f.native.next()
        assertTrue(f.events.isEmpty()); assertEquals(1, f.clockReads); assertNull(f.lifecycle.verifiedIdentity())
    }
    @Test fun failedSampleRetriesOriginalGateWithoutResettingDeadlineThenDisablesExactInstance() {
        val f = Fixture(); f.install(f.instance); f.sample = AndroidDetectionResult.failed(); f.lifecycle.scheduleVerification(f.instance, "unified_walksafe", false)
        f.native.next(); assertEquals(2, f.timers.size); assertEquals(1000L, f.timers.last().second)
        f.timers.last().first.run(); f.native.next(); f.timers.last().first.run(); f.native.next()
        assertEquals(1, f.ui.pending); assertEquals(1, f.timers.count { it.second == 15000L })
        f.ui.next(); assertFalse(f.execution.projection().available); assertTrue(f.lifecycle.blocksWalkEntry())
        assertEquals(listOf("frame", "aux-calibration", "gate", "disable-capability", "walking", "verification-failure:false", "verified"), f.events.takeLast(7))
        f.native.next(); assertSame(f.instance, f.closed.single())
    }
    @Test fun rejectedVerificationFailsPendingGateAndDefersNativeCloseWithoutInlineReplay() {
        val f = Fixture(); f.install(f.instance); f.native.reject = true; f.lifecycle.scheduleVerification(f.instance, "unified_walksafe", false)
        assertEquals(RunningDetectorVerificationState.FAILED, f.lifecycle.verificationState(f.instance)); f.ui.next()
        assertTrue(f.lifecycle.blocksWalkEntry()); assertTrue(f.closed.isEmpty()); assertFalse(f.execution.projection().available)
    }
    @Test fun destroyedDeadlineSkipsOriginalClockAndDoesNotExpireOrDisable() {
        val f = Fixture(); f.install(f.instance); f.lifecycle.beginVerification(f.instance, 0L); f.destroyed = true
        f.lifecycle.expireVerification(f.instance); assertEquals(0, f.clockReads)
        assertEquals(RunningDetectorVerificationState.PENDING, f.lifecycle.verificationState(f.instance))
    }
    @Test fun exactDeadlineDisablesAndKeepsCalibrationCancellationBeforeQueuedCloseAndUiCapabilities() {
        val f = Fixture(); f.install(f.instance); f.calibration.installSession(Any()); f.lifecycle.beginVerification(f.instance, 0L)
        f.now = 15000L; f.lifecycle.expireVerification(f.instance)
        assertTrue(f.events.indexOf("cancel:detector_verification_failed") < f.events.indexOf("disable-capability"))
        assertFalse(f.events.contains("aux-calibration")); assertTrue(f.closed.isEmpty()); f.native.next(); assertSame(f.instance, f.closed.single())
    }
    @Test fun fixedCheckPublishesActualResultOnlyAfterOriginalGenerationAndPointerGuard() {
        val f = Fixture(); f.install(f.instance); f.lifecycle.fixedFixturePerformanceCheck()
        assertTrue(f.lifecycle.metadata().fixedFixtureRunning); assertTrue(f.statuses.last().first.contains("확인 중"))
        f.native.next(); assertTrue(f.lifecycle.metadata().fixedFixtureRunning); f.ui.next()
        assertFalse(f.lifecycle.metadata().fixedFixtureRunning); assertTrue(f.statuses.last().second.contains("측정 12회"))
        assertTrue(f.statuses.last().second.contains("전체 25ms")); assertEquals(0, f.clockReads)
        val other = Fixture(); other.install(other.instance); other.lifecycle.fixedFixturePerformanceCheck(); other.native.next(); other.execution.fenceForClose(); other.ui.next()
        assertFalse(other.lifecycle.metadata().fixedFixtureRunning); assertEquals(1, other.statuses.size)
    }
    @Test fun fixedCheckRejectedEnqueueKeepsOriginalRunningAndDoesNotInventCompletedResult() {
        val f = Fixture(); f.install(f.instance); f.native.reject = true
        assertTrue(runCatching { f.lifecycle.fixedFixturePerformanceCheck() }.exceptionOrNull() is RejectedExecutionException)
        assertTrue(f.lifecycle.metadata().fixedFixtureRunning); assertEquals(1, f.statuses.size)
    }
    @Test fun runtimeStaleFailureReadsNoConfigOrClockAndDoesNotChangeSupervisorStreak() {
        val f = Fixture(); f.authorized = false; f.lifecycle.runtimeFailure(IllegalStateException(), 0, f.epoch)
        assertEquals(listOf("current"), f.events); assertEquals(0, f.clockReads); assertEquals(0, f.supervisor.consecutiveFailures)
    }
    @Test fun runtimeKeepCurrentUsesSameSupervisorAndOriginalSecondCurrentnessAndStaleReset() {
        val f = Fixture(); f.lifecycle.runtimeFailure(IllegalStateException(), 0, f.epoch)
        assertEquals(1, f.supervisor.consecutiveFailures); assertTrue(f.lifecycle.metadata().statusText.startsWith("detector=runtime_failure_1"))
        f.currentCalls = 0; f.revokeAfterFirstCurrent = true; f.lifecycle.runtimeFailure(IllegalStateException(), 0, f.epoch)
        assertEquals(0, f.supervisor.consecutiveFailures); assertEquals(0, f.ui.pending)
    }
    @Test fun nativeRuntimeCloseFailurePreservesActualPointerGenerationAndOldMetadataAndDoesNotQueueOutputs() {
        val f = Fixture(); val failure = IllegalStateException("native"); val native = f.nativeDetector(failure); f.install(native)
        assertSame(failure, runCatching { f.lifecycle.disableAfterFailure(IllegalStateException(), 0, f.epoch) }.exceptionOrNull())
        assertSame(native, f.execution.projection().detector); assertEquals(0, f.execution.projection().frameGeneration)
        assertEquals("detector=load_pending_after_camera_gate", f.lifecycle.metadata().statusText); assertEquals(0, f.ui.pending)
    }
    @Test fun safetyHandledFailureKeepsFrameClearingAndStopsSourcesInOriginalOrderWithoutDuplicateSafetyOutput() {
        val f = Fixture(); f.install(f.nativeDetector()); f.safetyHandled = true
        f.lifecycle.runtimeFailure(DetectorNativeCleanupFailure(IllegalStateException()), 0, f.epoch)
        assertEquals(2, f.clockReads); assertEquals("runtime_failed_unavailable", f.lifecycle.metadata().loadReason)
        f.ui.next(); f.ui.next()
        assertFalse(f.events.contains("safety-stop")); assertEquals(listOf("stop-camera", "stop-depth", "block-report", "show-unavailable"), f.events.takeLast(4))
    }
    @Test fun bypassFailureSkipsSafetyObservationAndClockButPreservesResourceStopAfterUiGuard() {
        val f = Fixture(); f.install(f.nativeDetector()); f.bypass = true
        f.lifecycle.runtimeFailure(DetectorNativeCleanupFailure(IllegalStateException()), 0, f.epoch)
        assertEquals(1, f.clockReads); assertFalse(f.events.contains("observe-safety")); f.ui.next(); f.ui.next()
        assertTrue(f.events.contains("safety-stop")); assertEquals(listOf("stop-camera", "stop-depth", "block-report", "show-unavailable"), f.events.takeLast(4))
    }
    @Test fun closeDuringPrimaryCalibrationReleasesLoadingFirstAndRetainsDeferredNativeClose() {
        val f = Fixture(); f.calibration.installSession(Any()); f.lifecycle.closeAsync()
        assertEquals(listOf("release-loading", "cancel:detector_close"), f.events)
        assertTrue(f.calibration.projection().detectorCloseAfterCalibration); assertFalse(f.execution.projection().closing)
        assertEquals(0, f.native.pending)
    }
    @Test fun nativeCloseFollowsActualAuxiliaryDrainAndRemainsSameLaneNotInline() {
        val f = Fixture(); f.install(f.nativeDetector()); f.lifecycle.closeAsync()
        assertEquals(listOf("release-loading", "aux-calibration", "close-aux"), f.events)
        assertTrue(f.execution.projection().closing); assertEquals(1, f.execution.projection().loadGeneration)
        assertEquals(0, f.native.pending); assertEquals(1, f.tuning.pending)
        f.tuning.next(); assertEquals(1, f.native.pending)
        f.native.next(); assertTrue(f.execution.projection().detector is NoopAndroidFrameDetector)
        assertEquals("native-close", f.events.last())
        assertTrue(f.execution.closedFuture().isDone)
    }

    @Test fun cancelledLoadImmediatelyClosesCurrentnessAndPreservesMetadataForRetry() {
        val f = Fixture(); val before = f.lifecycle.metadata()
        f.lifecycle.loadForCurrentProcess(); f.tuning.next(); assertTrue(f.selectorCurrent!!.invoke())
        f.events.clear(); f.lifecycle.cancelPendingLoad()
        assertTrue(f.events.isEmpty()); assertFalse(f.selectorCurrent!!.invoke())
        assertFalse(f.lifecycle.metadata().loadInFlight); assertFalse(f.lifecycle.metadata().loadAttempted)
        assertEquals(before.statusText, f.lifecycle.metadata().statusText)
        f.native.next(); assertSame(f.original, f.execution.projection().detector)
        assertEquals(listOf(f.instance), f.closed); assertEquals(0, f.ui.pending)
        f.lifecycle.loadForCurrentProcess(); f.tuning.next(); f.native.next(); f.ui.next()
        assertTrue(f.execution.projection().available); assertTrue(f.lifecycle.metadata().configLoaded)
    }
    @Test fun cancelledQueuedLoadDoesNotStartComparisonAndLateProgressCannotChangeStatus() {
        val queued = Fixture(); queued.lifecycle.loadForCurrentProcess(); queued.lifecycle.cancelPendingLoad(); queued.events.clear(); queued.tuning.next()
        assertTrue(queued.events.isEmpty()); assertEquals(0, queued.native.pending); assertEquals(0, queued.ui.pending)
        val late = Fixture(); late.lifecycle.loadForCurrentProcess(); late.tuning.next(); val progress = late.progress!!
        late.lifecycle.cancelPendingLoad(); late.lifecycle.loadForCurrentProcess(); val before = late.lifecycle.metadata()
        progress("retired comparison progress"); late.ui.next()
        assertEquals(before.statusText, late.lifecycle.metadata().statusText); assertTrue(late.lifecycle.metadata().loadInFlight)
        assertFalse(late.events.contains("progress:retired comparison progress"))
    }
    @Test fun cancellationIsNotPreparationFailureOrUnavailableAndAllowsNewRequest() {
        val f = Fixture(); f.loadFailure = java.util.concurrent.CancellationException("fixed load cancelled")
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        assertFalse(f.events.any { it.startsWith("log:") }); assertEquals(0, f.native.pending); assertEquals(0, f.ui.pending)
        assertNull(f.lifecycle.metadata().loadReason); assertFalse(f.lifecycle.metadata().loadAttempted); assertFalse(f.lifecycle.metadata().loadInFlight)
        f.loadFailure = null; f.lifecycle.loadForCurrentProcess(); f.tuning.next(); f.native.next(); f.ui.next()
        assertTrue(f.execution.projection().available)
    }
    @Test fun automaticProcessLoadUsesCacheOnlySelectionAndKeepsVerificationAdmission() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        assertTrue(f.events.contains("select:false")); assertFalse(f.events.contains("walk"))
        f.native.next(); f.ui.next(); assertTrue(f.execution.projection().available)
        assertEquals(RunningDetectorVerificationState.PENDING, f.lifecycle.verificationState(f.instance))
        f.native.next(); f.ui.next(); assertTrue(f.lifecycle.verifiedForOutput())
    }
    @Test fun cancelAfterNativeOwnershipCommitPreservesPointerAndOriginalUiVerification() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess(); f.tuning.next(); f.native.next()
        f.events.clear(); f.lifecycle.cancelPendingLoad()
        assertTrue(f.events.isEmpty()); assertTrue(f.lifecycle.metadata().loadAttempted); assertTrue(f.lifecycle.metadata().loadInFlight)
        assertSame(f.instance, f.execution.projection().detector); assertTrue(f.closed.isEmpty())
        f.ui.next(); assertTrue(f.execution.projection().available)
        f.native.next(); f.ui.next(); assertTrue(f.lifecycle.verifiedForOutput())
    }
    @Test fun cancelWithNoPendingLoadPreservesAlreadyVerifiedRunningDetector() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess(); f.tuning.next(); f.native.next(); f.ui.next(); f.native.next(); f.ui.next()
        val metadata = f.lifecycle.metadata(); val identity = f.lifecycle.verifiedIdentity()
        f.events.clear(); f.lifecycle.cancelPendingLoad()
        assertTrue(f.events.isEmpty()); assertEquals(metadata, f.lifecycle.metadata()); assertSame(identity, f.lifecycle.verifiedIdentity())
        assertTrue(f.lifecycle.verifiedForOutput()); assertSame(f.instance, f.execution.projection().detector); assertTrue(f.closed.isEmpty())
    }
    @Test fun pendingNativeCallFinishesNaturallyThenCancelledLoadReleasesWaitingCameraWorker() {
        val f = Fixture(); val selectorLock = Any(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        val cameraWaiting = CountDownLatch(1); val cameraReady = CountDownLatch(1)
        val nativeCalls = AtomicInteger(); val failure = AtomicReference<Throwable?>()
        f.selectWork = { current -> synchronized(selectorLock) {
            nativeCalls.incrementAndGet(); started.countDown(); check(release.await(3, TimeUnit.SECONDS))
            if (!current()) throw CancellationException("retired after native call")
            repeat(25) { nativeCalls.incrementAndGet() }
            AndroidDetectorLoadResult(f.instance, true, true, "unified_walksafe", false, "loaded")
        } }
        f.lifecycle.loadForCurrentProcess()
        val tuning = Thread { try { f.tuning.next() } catch (error: Throwable) { failure.set(error) } }
        val camera = Thread { cameraWaiting.countDown(); synchronized(selectorLock) { cameraReady.countDown() } }
        tuning.start()
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS)); camera.start(); assertTrue(cameraWaiting.await(3, TimeUnit.SECONDS))
            f.lifecycle.cancelPendingLoad()
            assertEquals(1L, cameraReady.count); assertFalse(f.selectorCurrent!!.invoke())
            assertFalse(f.lifecycle.metadata().loadInFlight); assertFalse(f.lifecycle.metadata().loadAttempted)
            release.countDown(); assertTrue(cameraReady.await(3, TimeUnit.SECONDS)); tuning.join(3_000); camera.join(3_000)
            assertFalse(tuning.isAlive); assertFalse(camera.isAlive); assertNull(failure.get()); assertEquals(1, nativeCalls.get())
            assertEquals(0, f.native.pending); assertEquals(0, f.ui.pending)
            assertNull(f.lifecycle.metadata().loadReason); assertFalse(f.events.any { it.startsWith("log:") })
        } finally { release.countDown(); tuning.join(3_000); if (camera.isAlive) camera.join(3_000) }
    }

    @Test fun compositeReleaseWaitsForPriorNativeWorkAndCloseAcknowledgementBeforeCallback() {
        val f = Fixture(); f.completeLoad()
        val released = mutableListOf<Boolean>(); val order = mutableListOf<String>()
        f.closeHook = {
            assertFalse(Thread.holdsLock(f.lock))
            assertTrue(f.execution.projection().closing)
            assertFalse(f.execution.projection().available)
            assertTrue(released.isEmpty()); assertEquals(0, f.ui.pending)
            assertEquals(0, f.tuning.pending)
            order += "close acknowledged"
        }
        f.execution.nativeLane.execute { order += "prior native work drained" }
        f.lifecycle.releaseForCompositeCheck({ true }) { released += it }
        assertTrue(f.lifecycle.metadata().loadInFlight)
        assertTrue(released.isEmpty()); assertTrue(f.closed.isEmpty())
        f.native.next()
        assertEquals(listOf("prior native work drained"), order)
        assertTrue(f.closed.isEmpty()); assertTrue(released.isEmpty())
        f.native.next()
        assertEquals(listOf("prior native work drained", "close acknowledged"), order)
        assertEquals(listOf(f.instance), f.closed)
        assertTrue(released.isEmpty()); assertEquals(1, f.ui.pending)
        assertNull(f.lifecycle.verifiedIdentity()); assertFalse(f.lifecycle.verifiedForOutput())
        f.ui.next()
        assertEquals(listOf(true), released)
        // The existing device check continues to own the pending state until explicit finish.
        assertTrue(f.lifecycle.metadata().loadInFlight)
        assertFalse(f.execution.projection().available)
        f.lifecycle.loadForCurrentProcess()
        assertEquals(0, f.tuning.pending); assertEquals(0, f.native.pending)
    }

    @Test fun cancelledCompositeReleaseCannotCloseTheRetainedPrimaryAtTheNativeBarrier() {
        val f = Fixture(); f.completeLoad(); val generation = f.execution.projection().loadGeneration
        var current = true; val released = mutableListOf<Boolean>()
        f.lifecycle.releaseForCompositeCheck({ current }) { released += it }
        current = false
        f.native.next(); f.ui.next()
        assertEquals(listOf(false), released)
        assertTrue(f.closed.isEmpty()); assertSame(f.instance, f.execution.projection().detector)
        assertEquals(generation, f.execution.projection().loadGeneration)
        assertFalse(f.execution.projection().closing); assertTrue(f.execution.projection().available)
        assertEquals(0, f.tuning.pending)

        val alreadyCancelled = Fixture(); alreadyCancelled.completeLoad()
        val earlyRelease = mutableListOf<Boolean>()
        alreadyCancelled.lifecycle.releaseForCompositeCheck({ false }) { earlyRelease += it }
        assertEquals(0, alreadyCancelled.native.pending)
        alreadyCancelled.ui.next()
        assertEquals(listOf(false), earlyRelease); assertTrue(alreadyCancelled.closed.isEmpty())
    }

    @Test fun compositeCloseFailureKeepsTheFenceAndCannotReloadEvenWhenFinishRequestsIt() {
        val f = Fixture(); f.completeLoad(); f.closeFailure = IllegalStateException("native release")
        val released = mutableListOf<Boolean>()
        f.lifecycle.releaseForCompositeCheck({ true }) { released += it }
        f.native.next(); assertTrue(released.isEmpty()); f.ui.next()
        assertEquals(listOf(false), released)
        assertTrue(f.execution.projection().closing); assertFalse(f.execution.projection().available)
        assertNull(f.lifecycle.verifiedIdentity()); assertFalse(f.lifecycle.verifiedForOutput())
        f.events.clear()
        f.lifecycle.finishCompositeCheck(reload = true)
        f.lifecycle.allowLoadRetryIfUnavailable(); f.lifecycle.loadForCurrentProcess()
        assertEquals(0, f.tuning.pending); assertEquals(0, f.native.pending)
        assertTrue(f.execution.projection().closing)
        assertFalse(f.lifecycle.metadata().configLoaded)
        assertFalse(f.events.any { it.startsWith("select:") })
        assertEquals(listOf(f.instance), f.closed)
    }

    @Test fun compositeFinishRestoresObservedRuntimeAndCadenceButWaitsForNewInstanceVerification() {
        val f = Fixture(); f.completeLoad()
        f.lifecycle.releaseForCompositeCheck({ true }) { assertTrue(it) }
        f.native.next(); f.ui.next()
        val replacement = unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector
        val observed = RuntimeCandidate(RuntimeBackend.GPU, 3)
        assertEquals(2, f.config!!.unifiedWalksafe!!.runtime.numThreads)
        f.observedCandidate = observed; f.measuredIntervalMs = 400L
        f.selectWork = { AndroidDetectorLoadResult(replacement, true, true, "unified_walksafe", false, "joint measured") }
        f.events.clear()
        f.lifecycle.finishCompositeCheck(reload = true)
        assertTrue(f.lifecycle.metadata().loadInFlight)
        assertFalse(f.lifecycle.verifiedForOutput()); assertFalse(f.execution.projection().available)
        f.tuning.next(); assertFalse(f.execution.projection().available)
        f.native.next()
        assertSame(replacement, f.execution.projection().detector)
        assertFalse(f.execution.projection().available); assertFalse(f.lifecycle.verifiedForOutput())
        f.ui.next()
        assertTrue(f.execution.projection().available)
        assertEquals(observed, f.calibration.projection().activeCandidate)
        assertEquals(400L, f.execution.pacingSnapshot().targetIntervalMs)
        assertEquals(RunningDetectorVerificationState.PENDING, f.lifecycle.verificationState(replacement))
        assertFalse(f.lifecycle.verifiedForOutput()); assertFalse(f.events.contains("verified"))
        f.native.next(); f.ui.next()
        assertEquals(RunningDetectorVerificationState.VERIFIED, f.lifecycle.verificationState(replacement))
        assertTrue(f.lifecycle.verifiedForOutput()); assertTrue(f.events.contains("verified"))
        assertEquals(listOf(f.instance), f.closed)
    }

    @Test fun cancelledLateAllocationDrainsItsRejectedAdoptionAndCloseBeforeCallbackOrAnotherLoad() {
        val f = Fixture(); val drained = mutableListOf<Boolean>(); val order = mutableListOf<String>()
        f.selectWork = {
            // Simulate cancellation after native allocation began, before the selector can return it.
            f.lifecycle.cancelPendingLoadAfterDrain { released ->
                assertEquals(listOf("allocation completed", "native close", "close returned"), order)
                drained += released
            }
            f.lifecycle.loadForCurrentProcess()
            assertEquals(1, f.tuning.pending) // Only the drain barrier, never another allocation.
            order += "allocation completed"
            AndroidDetectorLoadResult(f.instance, true, true, "unified_walksafe", false, "late allocation")
        }
        f.closeHook = { assertTrue(drained.isEmpty()); assertEquals(0, f.ui.pending); order += "native close" }
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        assertSame(f.original, f.execution.projection().detector)
        assertTrue(drained.isEmpty()); assertEquals(1, f.native.pending)
        f.tuning.next() // The tuning barrier queues behind the late native-adoption/close task.
        assertEquals(2, f.native.pending)
        f.lifecycle.loadForCurrentProcess(); assertEquals(0, f.tuning.pending)
        f.native.next()
        order += "close returned"
        assertEquals(listOf(f.instance), f.closed); assertTrue(drained.isEmpty())
        assertSame(f.original, f.execution.projection().detector)
        f.lifecycle.loadForCurrentProcess(); assertEquals(0, f.tuning.pending)
        f.native.next()
        assertTrue(drained.isEmpty()); assertEquals(1, f.ui.pending)
        f.lifecycle.loadForCurrentProcess(); assertEquals(0, f.tuning.pending)
        f.ui.next()
        assertEquals(listOf(true), drained)
        f.lifecycle.loadForCurrentProcess()
        assertEquals(1, f.tuning.pending) // Only successful drain delivery releases the load fence.
    }

    @Test fun failedCloseOfCancelledLateAllocationReportsUnconfirmedDrainAndKeepsNewLoadsFenced() {
        val f = Fixture(); val drained = mutableListOf<Boolean>()
        val failure = IllegalStateException("late allocation close")
        f.closeFailure = failure
        f.selectWork = {
            f.lifecycle.cancelPendingLoadAfterDrain { drained += it }
            AndroidDetectorLoadResult(f.instance, true, true, "unified_walksafe", false, "late allocation")
        }
        f.lifecycle.loadForCurrentProcess(); f.tuning.next(); f.tuning.next()
        assertSame(failure, runCatching { f.native.next() }.exceptionOrNull())
        assertTrue(drained.isEmpty()); assertEquals(listOf(f.instance), f.closed)
        assertTrue(f.execution.projection().closing); assertFalse(f.execution.projection().available)
        f.native.next(); f.ui.next()
        assertEquals(listOf(false), drained)
        f.closeFailure = null
        f.lifecycle.finishCompositeCheck(reload = true)
        f.lifecycle.allowLoadRetryIfUnavailable(); f.lifecycle.loadForCurrentProcess()
        assertEquals(0, f.tuning.pending); assertEquals(0, f.native.pending)
        assertTrue(f.execution.projection().closing)
        assertSame(f.original, f.execution.projection().detector)
        assertEquals(listOf(f.instance), f.closed)
    }

    @Test fun explicitUnsavedChoiceAppliesOnceAndWaitsForVerificationBeforeOutputAdmission() {
        val f = Fixture()
        val choice = JointRuntimeTestFixtures.choice(JointRuntimeTestFixtures.candidate.copy(
            primary = RuntimeCandidate(RuntimeBackend.GPU, 3), primaryIntervalMs = 400L))
        f.observedCandidate = choice.candidate.primary; f.measuredIntervalMs = choice.candidate.primaryIntervalMs
        f.sample = f.sample.copy(timing = f.sample.timing.copy(modelRuntime = AndroidDetectorRuntime("gpu", "gpu", 3)))
        f.measuredWork = { config, selected, current ->
            assertSame(f.config, config); assertSame(choice, selected); assertTrue(current())
            AndroidDetectorLoadResult(f.instance, true, true, "unified_walksafe", false, "DeviceCheckJointRuntimeChoice")
        }
        f.lifecycle.finishCompositeCheck(reload = true, choiceOverride = choice)
        f.lifecycle.finishCompositeCheck(reload = true, choiceOverride = choice)
        assertEquals(1, f.tuning.pending)
        f.tuning.next(); assertFalse(f.events.any { it.startsWith("select:") || it == "default" })
        f.native.next(); f.ui.next()
        assertEquals(choice.candidate.primary, f.calibration.projection().activeCandidate)
        assertEquals(400L, f.execution.pacingSnapshot().targetIntervalMs)
        assertEquals(RunningDetectorVerificationState.PENDING, f.lifecycle.verificationState(f.instance))
        assertFalse(f.lifecycle.verifiedForOutput())
        f.native.next(); f.ui.next(); assertTrue(f.lifecycle.verifiedForOutput())
        f.lifecycle.finishCompositeCheck(reload = true, choiceOverride = choice)
        assertEquals(0, f.tuning.pending); assertEquals(0, f.native.pending)
        assertEquals(1, f.events.count { it == "prepare-measured" })
    }

    @Test fun explicitChoiceWithoutItsConfigCannotFallBackToDefaultOrStoredSelection() {
        val f = Fixture(); f.config = null
        f.lifecycle.finishCompositeCheck(reload = true, choiceOverride = JointRuntimeTestFixtures.choice())
        f.tuning.next(); f.native.next(); f.ui.next()
        assertFalse(f.events.any { it.startsWith("select:") || it == "default" || it == "prepare-measured" })
        assertFalse(f.execution.projection().available); assertFalse(f.lifecycle.verifiedForOutput())
        assertFalse(f.lifecycle.metadata().configLoaded)
    }

    @Test fun cancelledMemoryChoiceCannotAdoptItsLateAllocationOrStartAnotherSelection() {
        val f = Fixture(); val choice = JointRuntimeTestFixtures.choice()
        f.measuredWork = { _, selected, _ ->
            assertSame(choice, selected); f.lifecycle.cancelPendingLoad()
            AndroidDetectorLoadResult(f.instance, true, true, "unified_walksafe", false, "DeviceCheckJointRuntimeChoice")
        }
        f.lifecycle.finishCompositeCheck(reload = true, choiceOverride = choice)
        f.tuning.next(); f.native.next()
        assertEquals(listOf(f.instance), f.closed)
        assertSame(f.original, f.execution.projection().detector)
        assertFalse(f.lifecycle.verifiedForOutput()); assertFalse(f.lifecycle.metadata().configLoaded)
        assertEquals(0, f.ui.pending); assertEquals(0, f.tuning.pending)
        assertFalse(f.events.any { it.startsWith("select:") || it == "default" })
    }

    @Test fun cancelledAllocationWithFailedCloseStillBlocksTheLaterNativeDrainBarrier() {
        val f = Fixture(); val choice = JointRuntimeTestFixtures.choice(); val drained = mutableListOf<Boolean>()
        val fence = JointRuntimeReleaseFence()
        f.measuredWork = { _, selected, current ->
            AndroidJointRuntimeLoader.prepare(JointRuntimeTestFixtures.config, selected, current,
                selected.bindingHash, 8, true,
                create = { _, _ ->
                    f.lifecycle.cancelPendingLoad()
                    AndroidDetectorLoadResult(f.instance, true, true, "unified_walksafe", false, "late allocation")
                }, sample = { error("Cancelled allocation must not be sampled") },
                close = { f.closed += it; throw IllegalStateException("late native close") }, fence = fence).load
        }
        f.lifecycle.finishCompositeCheck(reload = true, choiceOverride = choice)
        f.tuning.next()
        assertTrue(f.execution.projection().closing)
        assertEquals(listOf(f.instance), f.closed)
        assertNull(f.lifecycle.metadata().loadReason) // The retired request cannot replace current metadata.
        assertTrue(runCatching { fence.ensureConfirmed() }.exceptionOrNull() is DetectorNativeCleanupFailure)
        f.lifecycle.cancelPendingLoadAfterDrain { drained += it }
        f.tuning.next(); f.native.next(); f.ui.next()
        assertEquals(listOf(false), drained)
        f.lifecycle.allowLoadRetryIfUnavailable(); f.lifecycle.loadForCurrentProcess()
        assertEquals(0, f.tuning.pending); assertEquals(0, f.native.pending)
        f.lifecycle.closeAsync(); f.tuning.next(); f.native.next()
        assertTrue(f.execution.closedFuture().isCompletedExceptionally)
    }

    @Test fun destroyWaitsForAcceptedCreationAndLateRetiredCloseBeforeTerminalAcknowledgement() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess()
        f.destroyed = true; f.execution.fenceForClose(); f.lifecycle.closeAsync()
        assertEquals(2, f.tuning.pending)
        assertFalse(f.execution.closedFuture().isDone)
        f.tuning.next() // Already accepted creation returns only after destruction.
        assertEquals(1, f.native.pending); assertFalse(f.execution.closedFuture().isDone)
        f.tuning.next() // Queue terminal close after that creation's native adoption/retirement.
        assertEquals(2, f.native.pending)
        f.native.next()
        assertEquals(listOf(f.instance), f.closed); assertFalse(f.execution.closedFuture().isDone)
        f.native.next()
        assertTrue(f.execution.closedFuture().isDone)
        assertFalse(f.execution.closedFuture().isCompletedExceptionally)
        assertEquals(0, f.ui.pending); assertFalse(f.lifecycle.verifiedForOutput())
    }

    @Test fun failedRetiredCloseAfterDestroyCannotBeOverwrittenBySuccessfulTerminalNoopClose() {
        val f = Fixture(); f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        val failure = IllegalStateException("retired native close")
        f.closeFailure = failure; f.destroyed = true; f.lifecycle.closeAsync(); f.tuning.next()
        assertSame(failure, runCatching { f.native.next() }.exceptionOrNull())
        assertFalse(f.execution.closedFuture().isDone)
        f.native.next()
        assertTrue(f.execution.closedFuture().isCompletedExceptionally)
        assertSame(failure, runCatching { f.execution.closedFuture().join() }.exceptionOrNull()!!.cause)
        assertEquals(listOf(f.instance), f.closed)
        assertEquals(0, f.ui.pending); assertEquals(0, f.tuning.pending)
    }

    @Test fun failedOpenWithoutAReturnedDetectorStillRetainsItsPhysicalReleaseReceipt() {
        val f = Fixture(); val physical = CompletableFuture<Unit>()
        f.selectWork = { AndroidDetectorLoadResult(null, true, false, null, false,
            "DetectorCohortRetired", actualReleasedFuture = physical) }
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        while (f.native.pending > 0) f.native.next()
        while (f.ui.pending > 0) f.ui.next()
        assertEquals("DetectorCohortRetired", f.lifecycle.metadata().loadReason)
        assertFalse(f.execution.projection().available)
        f.lifecycle.closeAsync(); f.tuning.next(); f.native.next()
        assertFalse("No detector pointer does not prove failed OPEN native release", f.execution.closedFuture().isDone)
        physical.complete(Unit)
        assertEquals(Unit, f.execution.closedFuture().getNow(null))
    }

    @Test fun aCancelledLateOpenFailureCannotPublishMetadataOrLoseItsPhysicalReceipt() {
        val f = Fixture(); val physical = CompletableFuture<Unit>()
        f.selectWork = {
            f.lifecycle.cancelPendingLoad()
            AndroidDetectorLoadResult(null, true, false, null, false, "DetectorCohortRetired", actualReleasedFuture = physical)
        }
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        while (f.native.pending > 0) f.native.next()
        while (f.ui.pending > 0) f.ui.next()
        assertNull(f.lifecycle.metadata().loadReason)
        assertFalse(f.lifecycle.metadata().loadInFlight)
        f.lifecycle.closeAsync(); f.tuning.next(); f.native.next()
        assertFalse(f.execution.closedFuture().isDone)
        physical.complete(Unit)
        assertEquals(Unit, f.execution.closedFuture().getNow(null))
    }

    @Test fun aThrownRemoteFailurePreservesTheTypedReasonAndLateReleaseReceipt() {
        val f = Fixture(); val physical = CompletableFuture<Unit>()
        f.loadFailure = DetectorCohortRetired(IllegalStateException("OPEN timeout"), physical)
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        while (f.native.pending > 0) f.native.next()
        while (f.ui.pending > 0) f.ui.next()
        assertEquals("DetectorCohortRetired", f.lifecycle.metadata().loadReason)
        assertFalse(f.lifecycle.metadata().loadInFlight)
        f.lifecycle.closeAsync(); f.tuning.next(); f.native.next()
        assertFalse(f.execution.closedFuture().isDone)
        physical.complete(Unit)
        assertEquals(Unit, f.execution.closedFuture().getNow(null))
    }

    @Test fun busyAvailabilityRetriesTheSameLiveAttemptWithoutPublishingAPerformanceFailure() {
        val f = Fixture(); val available = CompletableFuture<VisionReleaseReceipt>()
        f.loadFailure = VisionCohortBusyException(available)
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        assertTrue(f.lifecycle.metadata().loadInFlight)
        assertNull(f.lifecycle.metadata().loadReason)
        assertFalse(f.execution.projection().closing)
        assertEquals(0, f.native.pending)
        f.loadFailure = null
        available.complete(VisionReleaseReceipt("old", 234))
        assertEquals(1, f.tuning.pending)
        f.tuning.next(); f.native.next(); f.ui.next()
        assertEquals("loaded", f.lifecycle.metadata().loadReason)
        assertEquals(2, f.events.count { it == "select:false" })
        assertFalse(f.events.any { it.startsWith("unavailable:") })
    }

    @Test fun aCancelledBusyAvailabilityCannotRestartOrModifyANewerAttempt() {
        val f = Fixture(); val available = CompletableFuture<VisionReleaseReceipt>()
        f.loadFailure = VisionCohortBusyException(available)
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        f.lifecycle.cancelPendingLoad()
        f.loadFailure = null
        f.lifecycle.loadForCurrentProcess()
        assertEquals(1, f.tuning.pending)
        available.complete(VisionReleaseReceipt("old", 234))
        assertEquals(1, f.tuning.pending)
        f.tuning.next(); f.native.next(); f.ui.next()
        assertEquals("loaded", f.lifecycle.metadata().loadReason)
        assertEquals(2, f.events.count { it == "select:false" })
        assertFalse(f.execution.projection().closing)
    }

    @Test fun availabilityAtTheExactWaitDeadlineOrAfterClockRollbackCannotRetry() {
        for (now in listOf(15_000L, -1L)) {
            val f = Fixture(); val available = CompletableFuture<VisionReleaseReceipt>()
            f.loadFailure = VisionCohortBusyException(available)
            f.lifecycle.loadForCurrentProcess(); f.tuning.next()
            f.now = now; f.loadFailure = null
            available.complete(VisionReleaseReceipt("old", 234))
            assertEquals(0, f.tuning.pending)
            assertEquals(0, f.native.pending)
            assertFalse(f.lifecycle.metadata().loadInFlight)
            assertNull(f.lifecycle.metadata().loadReason)
            assertFalse(f.execution.projection().closing)
        }
    }

    @Test fun aQueuedAvailabilityRetryCannotOpenAfterTheOriginalWaitDeadline() {
        val f = Fixture(); val available = CompletableFuture<VisionReleaseReceipt>()
        f.loadFailure = VisionCohortBusyException(available)
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        f.now = 14_999L; f.loadFailure = null
        available.complete(VisionReleaseReceipt("old", 234))
        assertEquals(1, f.tuning.pending)
        f.now = 15_000L; f.tuning.next()
        assertEquals(1, f.events.count { it == "select:false" })
        assertEquals(0, f.native.pending)
        assertFalse(f.lifecycle.metadata().loadInFlight)
        assertNull(f.lifecycle.metadata().loadReason)
    }

    @Test fun anEarlyAvailabilityTimerRearmsOnlyTheRemainingBudgetAndDoesNotPublishLimitedSuccess() {
        val f = Fixture(); val available = CompletableFuture<VisionReleaseReceipt>()
        f.loadFailure = VisionCohortBusyException(available)
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        assertEquals(15_000L, f.timers.single().second)
        f.now = 1_000L; f.timers.single().first.run()
        assertTrue(f.lifecycle.metadata().loadInFlight)
        assertEquals(14_000L, f.timers.last().second)
        f.now = 15_000L; f.timers.last().first.run()
        assertFalse(f.lifecycle.metadata().loadInFlight)
        assertFalse(f.lifecycle.metadata().loadAttempted)
        assertEquals("이전 인식 작업 대기 만료", f.lifecycle.metadata().statusText)
        assertFalse(f.events.any { it.startsWith("unavailable:") || it == "loaded" })
        available.complete(VisionReleaseReceipt("old", 234))
        assertEquals(0, f.tuning.pending)
    }

    @Test fun aSynchronousEarlyAvailabilityTimerCannotRecurseOrOpenANativeModel() {
        val f = Fixture(); val available = CompletableFuture<VisionReleaseReceipt>()
        f.loadFailure = VisionCohortBusyException(available)
        f.timerAction = { timeout, _ -> timeout.run(); true }
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        assertEquals(1, f.timers.size)
        assertFalse(f.lifecycle.metadata().loadInFlight)
        assertEquals("이전 인식 작업 대기 중단", f.lifecycle.metadata().statusText)
        available.complete(VisionReleaseReceipt("old", 234))
        assertEquals(0, f.tuning.pending); assertEquals(0, f.native.pending)
        assertFalse(f.execution.projection().closing)
    }

    @Test fun backgroundAvailabilityNeedsAnExplicitCurrentForegroundRequestToRetry() {
        val f = Fixture(); val available = CompletableFuture<VisionReleaseReceipt>()
        f.loadFailure = VisionCohortBusyException(available)
        f.lifecycle.loadForCurrentProcess(); f.tuning.next()
        f.foreground = false; f.loadFailure = null
        available.complete(VisionReleaseReceipt("old", 234))
        assertEquals(0, f.tuning.pending); assertFalse(f.lifecycle.metadata().loadInFlight)
        assertNull(f.lifecycle.metadata().loadReason)
        f.foreground = true; f.lifecycle.loadForCurrentProcess()
        assertEquals(1, f.tuning.pending)
        f.tuning.next(); f.native.next(); f.ui.next()
        assertEquals("loaded", f.lifecycle.metadata().loadReason)
    }

    private class Lane : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>(); var reject = false; var closed = false
        val pending get() = tasks.size
        fun next() = tasks.removeFirst().run()
        override fun execute(command: Runnable) { if (reject || closed) throw RejectedExecutionException(); tasks += command }
        override fun shutdown() { closed = true }
        override fun shutdownNow() = emptyList<Runnable>()
        override fun isShutdown() = closed
        override fun isTerminated() = closed
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
    }
    private class Fixture {
        val events = mutableListOf<String>(); var now = 0L; var clockReads = 0
        val lock = Any(); val native = Lane(); val tuning = Lane(); val ui = Lane()
        val epoch = WalkRuntimeEpoch("r7", 1L); var authorized = true; var destroyed = false; var foreground = true
        var currentCalls = 0; var revokeAfterFirstCurrent = false; var bypass = false; var safetyHandled = false
        var walking = false; var calibrationPresent = false; var closeFailure: RuntimeException? = null
        var closeHook: (() -> Unit)? = null
        var observedCandidate: RuntimeCandidate? = null; var measuredIntervalMs: Long? = null
        val instance = unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector
        val execution = PrimaryDetectorExecutionOwner(lock, native, tuning, { error("native watchdog clock") },
            object : PrimaryNativeFlightScheduler { override fun postDelayed(task: Runnable, delayMs: Long) = false; override fun remove(task: Runnable) = Unit },
            { false }, { false }, {}, { _, _ -> }, { it() })
        val original = execution.projection().detector
        val calibration = PrimaryRuntimeCalibrationOwner<Any>(execution, { error("calibration clock") }, { _, _ -> error("explicit auxiliary composition") }, {})
        val supervisor = DetectorRuntimeSupervisor()
        val frames = FrameSynthesisController(lock, object : FrameSynthesisAuthority {
            override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch) = true
            override fun userStepLengthM() = 0.65f
            override fun retireUnstartedFeedback(trackPrefix: String) = Unit
        }, { error("frame clock") }, InterFrameDetectionTracker(VisualTrackingConfig(backend = VisualTrackingBackend.PATCH_DIAGNOSTIC)))
        var config: TwoModelRuntimeConfig? = TwoModelRuntimeConfig("r7", null, "unified_walksafe", null,
            ModelRuntimeConfig("unified_walksafe", "model", 32, listOf("person"), setOf("person"), mapOf("person" to 0.5f), artifactSha256 = "a".repeat(64),
                runtime = ModelRuntimeOptions(numThreads = 2)), null, null)
        var loadFailure: Throwable? = null; var logFailure: Throwable? = null
        var progress: ((String) -> Unit)? = null; var selectorCurrent: (() -> Boolean)? = null
        var selectWork: ((() -> Boolean) -> AndroidDetectorLoadResult)? = null
        var measuredWork: ((TwoModelRuntimeConfig, ValidatedJointRuntimeChoice, () -> Boolean) -> AndroidDetectorLoadResult)? = null
        val closed = mutableListOf<TfliteAndroidFrameDetector>(); val statuses = mutableListOf<Pair<String, String>>()
        val timers = mutableListOf<Pair<Runnable, Long>>()
        var timerAction: ((Runnable, Long) -> Boolean)? = null
        var sample = AndroidDetectionResult(emptyList(), AndroidDetectorTiming(modelRuntime = AndroidDetectorRuntime("gpu", "gpu", 2)))
        val lifecycle = PrimaryDetectorLifecycleController(lock, execution, calibration, { events += "frame"; frames }, supervisor,
            object : PrimaryLifecycleCurrentness {
                override fun destroyed(): Boolean { events += "destroyed"; return destroyed }
                override fun foreground(): Boolean { events += "foreground"; return foreground }
                override fun frameIsCurrent(generation: Int, epoch: WalkRuntimeEpoch): Boolean {
                    events += "current"; currentCalls += 1
                    return authorized && generation == execution.projection().frameGeneration && (!revokeAfterFirstCurrent || currentCalls == 1)
                }
                override fun epochIsCurrent(epoch: WalkRuntimeEpoch): Boolean { events += "epoch"; return authorized }
            }, object : PrimaryModelConfiguration {
                override fun loadOrCurrent(): TwoModelRuntimeConfig? { events += "config-load"; return config }
                override fun current(): TwoModelRuntimeConfig? { events += "config-current"; return config }
                override fun sha256(): String { events += "config-hash"; return "b".repeat(64) }
            }, object : PrimaryLifecyclePlatform {
                fun load(): AndroidDetectorLoadResult { loadFailure?.let { throw it }; return AndroidDetectorLoadResult(instance, true, true, "unified_walksafe", false, "loaded") }
                override fun loadDefault(): AndroidDetectorLoadResult { events += "default"; return load() }
                override fun select(config: TwoModelRuntimeConfig, allowComparison: Boolean, current: () -> Boolean, progress: (String) -> Unit): AndroidDetectorLoadResult {
                    events += "select:$allowComparison"; selectorCurrent = current; this@Fixture.progress = progress; return selectWork?.invoke(current) ?: load()
                }
                override fun prepareMeasuredChoice(config: TwoModelRuntimeConfig, choice: ValidatedJointRuntimeChoice,
                    current: () -> Boolean): AndroidDetectorLoadResult {
                    events += "prepare-measured"
                    val work = requireNotNull(measuredWork) { "Unexpected measured preparation" }
                    return work(config, choice, current)
                }
                override fun logPreparationFailure(error: Throwable) { events += "log:${error.javaClass.simpleName}"; logFailure?.let { throw it } }
                override fun close(detector: TfliteAndroidFrameDetector) { events += "close"; closed += detector; closeHook?.invoke(); closeFailure?.let { throw it } }
                override fun firstSuccessfulDelegate(detector: TfliteAndroidFrameDetector): String { events += "delegate"; return "gpu" }
                override fun observedCandidate(detector: TfliteAndroidFrameDetector) = this@Fixture.observedCandidate
                override fun measuredPrimaryIntervalMs(detector: TfliteAndroidFrameDetector) = measuredIntervalMs
                override fun availableProcessors(): Int { events += "processors"; return 4 }
                override fun sample(detector: TfliteAndroidFrameDetector): AndroidDetectionResult { events += "sample"; return sample }
                override fun performance(detector: TfliteAndroidFrameDetector): PrimaryFixedFixtureResult { events += "performance"; return PrimaryFixedFixtureResult(4, 12, 25L, 20L, "gpu", 6) }
            }, object : PrimaryPreparationInteraction {
                override fun metricDistanceEnabled(): Boolean { events += "metric"; return true }
                override fun walkRuntimeActive(): Boolean { events += "walk"; return false }
                override fun featureGatePassed(): Boolean { events += "gate"; return true }
                override fun progress(message: String) { events += "progress:$message" }
                override fun modelUnavailable(metricDistanceWasEnabled: Boolean) { events += "unavailable:$metricDistanceWasEnabled" }
                override fun modelVerified() { events += "verified" }
                override fun loaded() { events += "loaded" }
                override fun disableUnverifiedCapabilities() { events += "disable-capability" }
                override fun activeOrPausedWalk(): Boolean { events += "walking"; return walking }
                override fun showVerificationFailure(walking: Boolean) { events += "verification-failure:$walking" }
            }, object : PrimaryLifecyclePresentation {
                override fun onUi(effect: () -> Unit) { ui.execute(effect) }
                override fun status(status: String, detail: String) { statuses += status to detail }
                override fun clearOverlay() { events += "overlay-clear" }
            }, object : PrimaryLifecycleAuxiliary {
                override fun calibrationPresent(): Boolean { events += "aux-calibration"; return calibrationPresent }
                override fun cancelCalibration(reason: String) { events += "cancel:$reason" }
                override fun releaseLoadingIfInitialized() { events += "release-loading" }
                override fun closeActiveOrInvalidateObservations() { events += "close-aux" }
            }, object : PrimaryDetectorFailureEffects {
                override fun developmentBypassEnabled(): Boolean { events += "bypass"; return bypass }
                override fun observeSafetyFailure(epoch: WalkRuntimeEpoch, observedAtMs: Long): Boolean { events += "observe-safety"; return safetyHandled }
                override fun resetVisualTracking() { events += "reset-visual" }
                override fun enterSafetyStop() { events += "safety-stop" }
                override fun stopCameraFallback() { events += "stop-camera" }
                override fun stopDepthSession() { events += "stop-depth" }
                override fun blockReportCandidates() { events += "block-report" }
                override fun showDetectorUnavailable() { events += "show-unavailable" }
            }, object : PrimaryNativeFlightScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
                    events += "timer:$delayMs"; timers += task to delayMs
                    return timerAction?.invoke(task, delayMs) ?: true
                }
                override fun remove(task: Runnable) = Unit
            }, { events += "clock"; clockReads += 1; now })
        fun completeLoad() {
            lifecycle.loadForCurrentProcess(); tuning.next(); native.next(); ui.next(); native.next(); ui.next()
            assertTrue(lifecycle.verifiedForOutput())
        }
        fun install(value: AndroidFrameDetector) {
            PrimaryDetectorExecutionOwner::class.java.getDeclaredField("detector").apply { isAccessible = true }.set(execution, value)
            PrimaryDetectorExecutionOwner::class.java.getDeclaredField("available").apply { isAccessible = true }.set(execution, true)
        }
        fun nativeDetector(failure: RuntimeException? = null): AndroidFrameDetector = object : AndroidFrameDetector by NoopAndroidFrameDetector(), Closeable {
            override fun close() { events += "native-close"; failure?.let { throw it } }
        }
    }
    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    }
}
