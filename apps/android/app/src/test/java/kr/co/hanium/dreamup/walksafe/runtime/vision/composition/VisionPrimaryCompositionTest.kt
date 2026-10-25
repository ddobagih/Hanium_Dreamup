package kr.co.hanium.dreamup.walksafe.runtime.vision.composition

import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.device.AndroidRuntimeCalibrationSession
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownRuntimeServiceOwner
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class VisionPrimaryCompositionTest {
    @Test fun coldCompositionHasNoLockSdkClockHandlerAdmissionOrNativeQuery() {
        val f = Fixture(); assertTrue(f.events.isEmpty())
        assertFalse(f.composition.detector.isInitialized()); assertFalse(f.composition.calibration.isInitialized())
        assertFalse(f.composition.scheduling.isInitialized()); assertFalse(f.composition.invocation.isInitialized())
    }
    @Test fun eagerSupervisorCreationKeepsNativeHolderColdAndDoesNotReadAuthority() {
        val f = Fixture(); f.composition.createSupervisor { _, _ -> error("no constructor authority") }
        assertTrue(f.events.isEmpty()); assertFalse(f.composition.detector.isInitialized())
    }
    @Test fun actualNativeHolderInitializesOnceAtItsOriginalFirstReadAndClockRemainsLazy() {
        val f = Fixture(); val first = f.composition.detector.value
        assertSame(first, f.composition.detector.value); assertEquals(listOf("lock"), f.events)
        assertTrue(first.projection().detector is NoopAndroidFrameDetector)
        assertEquals(0, first.projection().frameGeneration); assertEquals(0L, first.projection().loadGeneration)
        assertFalse(f.composition.calibration.isInitialized()); assertFalse(f.composition.invocation.isInitialized())
    }
    @Test fun calibrationFirstSchedulingFirstAndInvocationFirstShareTheSameNativeOwnerAndLock() {
        val f = Fixture(); val calibration = f.composition.calibration.value; val scheduling = f.composition.scheduling.value
        val invocation = f.composition.invocation.value; val native = f.composition.detector.value
        assertSame(native, field(calibration, "detectorOwner")); assertSame(native, field(scheduling, "detectorOwner"))
        assertSame(native, field(invocation, "detectorOwner")); assertSame(f.lock, field(scheduling, "stateLock"))
        assertSame(f.lock, field(native, "stateLock")); assertEquals(listOf("lock", "lock"), f.events)
        assertSame(calibration, f.composition.calibration.value); assertSame(scheduling, f.composition.scheduling.value)
        assertSame(invocation, f.composition.invocation.value)
    }
    @Test fun lateInitializedMainLockIsNotReadUntilActualNativeOwnerMaterializes() {
        var initializedLock: Any? = null
        val composition = composition({ requireNotNull(initializedLock) })
        composition.createSupervisor { _, _ -> false }
        assertFalse(composition.detector.isInitialized()); initializedLock = Any()
        assertSame(initializedLock, field(composition.detector.value, "stateLock"))
    }
    @Test fun rejectedSupervisorAuthorityDoesNotForceNativeLazyAndWrongRunDoesNotQueryAuthority() {
        val f = Fixture(); var reads = 0; val supervisor = f.composition.createSupervisor { _, _ -> reads += 1; false }
        val epoch = WalkRuntimeEpoch("r9", 1L); val ticket = requireNotNull(AdaptiveInferencePacingPolicy(800L).tryStart(0L))
        assertFalse(supervisor.onDetectorOutput(DetectorWorkClaim(0, "wrong", ticket), AndroidDetectionResult.empty(), 0L, 1L).freshOutputAccepted)
        assertEquals(0, reads)
        assertFalse(supervisor.onDetectorOutput(DetectorWorkClaim(0, epoch, ticket), AndroidDetectionResult.empty(), 0L, 1L).freshOutputAccepted)
        assertEquals(1, reads); assertFalse(f.composition.detector.isInitialized()); assertTrue(f.events.isEmpty())
    }
    @Test fun liveWalkReplacementIsObservedAtCallbackAndNeverCapturedByCompositionOrSupervisor() {
        val f = Fixture(); var walk = WalkSessionLifecycle(elapsedRealtimeClock = { error("walk constructor clock") })
        val observed = mutableListOf<WalkSessionLifecycle>(); val supervisor = f.composition.createSupervisor { _, epoch ->
            observed += walk; walk.isRuntimeEpochCurrent(epoch)
        }
        val epoch = WalkRuntimeEpoch("retained", 1L); val ticket = requireNotNull(AdaptiveInferencePacingPolicy(800L).tryStart(0L))
        val previous = walk; supervisor.onDetectorOutput(DetectorWorkClaim(0, epoch, ticket), AndroidDetectionResult.empty(), 0L, 1L)
        walk = WalkSessionLifecycle(elapsedRealtimeClock = { error("placeholder clock") })
        supervisor.onDetectorOutput(DetectorWorkClaim(0, epoch, ticket), AndroidDetectionResult.empty(), 0L, 2L)
        assertSame(previous, observed[0]); assertSame(walk, observed[1]); assertNotSame(observed[0], observed[1])
        assertFalse(f.composition.detector.isInitialized())
    }
    @Test fun supervisorUsesTheSameActualPacingTicketAfterAdmissionAndRetirement() {
        val f = Fixture(); val epoch = WalkRuntimeEpoch("current", 2L); var current = true
        val supervisor = f.composition.createSupervisor { _, _ -> current }
        val native = f.composition.detector.value; val ticket = requireNotNull(native.tryStartWork(0L))
        assertTrue(supervisor.onDetectorOutput(DetectorWorkClaim(0, epoch, ticket), AndroidDetectionResult.empty(), 0L, 1L).freshOutputAccepted)
        current = false
        assertFalse(supervisor.onDetectorOutput(DetectorWorkClaim(0, epoch, ticket), AndroidDetectionResult.empty(), 0L, 2L).freshOutputAccepted)
    }
    @Test fun androidSchedulerConstructionDoesNotReadHandlerProvider() {
        AndroidVisionScheduler { error("no eager Handler") }
    }
    @Test fun calibrationSdkAndPlatformFactoryConstructionDoesNotReadContextActivityNativeOrSession() {
        val sessions = VisionCalibrationSessionFactory({ error("activity") }, { error("context") },
            { error("native") }, { error("speech") }, { error("ui") })
        val platform = VisionPrimaryCalibrationPlatform({ error("context") }, { error("sessions") })
        assertNull(platform.calibratableDetector(NoopAndroidFrameDetector()))
        assertNotNull(sessions)
    }
    @Test fun sdkSessionCreateObtainsActivityBeforeAnyDeferredRunnerContextNativeSpeechOrCallback() {
        val expected = IllegalStateException("actual SDK boundary")
        val sessions = VisionCalibrationSessionFactory({ throw expected }, { error("early context") },
            { error("early native") }, { error("early speech") }, { error("early dispatch") })
        val baseline = unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector
        val config = TwoModelRuntimeConfig("r9", null, "unified_walksafe", null, null, null, null)
        val spec = PrimaryCalibrationSessionSpec(config, baseline, RuntimeCandidate(RuntimeBackend.CPU, 1),
            "binding", RuntimeFeatureScope.CAMERA_TRACKING, 1L, false, null)
        val callbacks = PrimaryCalibrationSessionCallbacks({ error("early current") }, { error("early selection") }, { error("early closed") })
        assertSame(expected, runCatching { sessions.create(spec, callbacks) }.exceptionOrNull())
    }
    @Test fun auxiliaryAbsentUsesTheOriginalUninitializedFallbackWithoutMaterializingServiceOrClock() {
        var initialized = 0
        val service: Lazy<UnknownRuntimeServiceOwner> = lazy { initialized += 1; error("no eager service") }
        val adapter = VisionAuxiliaryCalibrationPeer(service)
        assertTrue(adapter.isAbsent()); assertEquals(0, initialized)
        val failure = runCatching { adapter.releaseBeforePrimary() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException); assertEquals(1, initialized); assertFalse(service.isInitialized())
    }
    @Test fun auxiliaryLiveServiceReferenceAndLazyAreNotCopiedOrReplaced() {
        val owned = unsafe.allocateInstance(UnknownRuntimeServiceOwner::class.java) as UnknownRuntimeServiceOwner
        val services = lazyOf(owned); val adapter = VisionAuxiliaryCalibrationPeer(services)
        assertSame(services, field(adapter, "services")); assertTrue(adapter.isAbsent())
        val fakeService = unsafe.allocateInstance(kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService::class.java)
        UnknownRuntimeServiceOwner::class.java.getDeclaredField("unknownRuntimeService").apply { isAccessible = true }.set(owned, fakeService)
        assertFalse(adapter.isAbsent())
        UnknownRuntimeServiceOwner::class.java.getDeclaredField("unknownRuntimeService").apply { isAccessible = true }.set(owned, null)
        assertTrue(adapter.isAbsent())
    }
    private class Fixture {
        val events = mutableListOf<String>(); val lock = Any()
        val composition = composition({ events += "lock"; lock }, events)
    }
    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        private fun composition(lock: () -> Any, events: MutableList<String> = mutableListOf()) = VisionPrimaryComposition(lock,
            { error("no eager elapsed") }, { error("no eager wall") }, object : PrimaryNativeFlightScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long): Boolean { events += "schedule"; return true }
                override fun remove(task: Runnable) { events += "remove-watchdog" }
            }, { events += "blocked"; false }, { events += "aux-copy"; false }, { events += "drop" },
            { _, _ -> events += "stall" }, { it() }, { events += "remove-calibration" }, 800L)
    }
}
