package kr.co.hanium.dreamup.walksafe.runtime.vision.composition

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.RuntimeCalibrationResult
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.*
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class VisionUnknownCompositionTest {
    @Test fun coldConstructorReadsNoSdkClockLockAuthorityCalibrationOrCapturePolicy() {
        val f = Fixture(); assertTrue(f.events.isEmpty())
        assertFalse(f.composition.services.isInitialized()); assertFalse(f.composition.capture.isInitialized())
    }
    @Test fun serviceFirstReadsOriginalNativeAuthorityClockTuningOrderAndKeepsCaptureCold() {
        val f = Fixture(); val services = f.composition.services.value
        assertEquals(listOf("primary", "authority", "clock", "primary"), f.events)
        assertFalse(f.composition.capture.isInitialized()); assertNull(services.currentService)
        assertSame(services, f.composition.services.value)
        assertSame(f.primary.nativeLane, field(field(services, "factory")!!, "primaryExecutor"))
        assertSame(f.primary.tuningLane, field(services, "deviceTuningExecutor")); assertSame(f.clock, field(services, "clock"))
    }
    @Test fun captureFirstPreservesArgumentOrderAndBuildsServiceWithoutForcingCaptureAgain() {
        val f = Fixture(); val capture = f.composition.capture.value
        assertEquals(listOf("lock", "primary", "scope", "clock", "observer", "primary", "authority", "clock", "primary"), f.events)
        assertSame(f.lock, field(capture, "frameStateLock")); assertSame(f.primary.nativeLane, field(capture, "detectorExecutor"))
        assertSame(f.scope, field(capture, "scopeReader")); assertSame(f.observer, field(capture, "observer"))
        assertSame(f.clock, field(capture, "clock")); assertSame(f.composition.services.value, field(capture, "services"))
        assertSame(capture, f.composition.capture.value)
    }
    @Test fun lateMainLockAndClockAreReadOnlyWhenTheirOriginalLazyConstructorRuns() {
        val f = Fixture(); f.lockReady = false; f.clockReady = false
        assertFalse(f.composition.services.isInitialized()); assertFalse(f.composition.capture.isInitialized())
        f.lockReady = true; f.clockReady = true
        assertSame(f.lock, field(f.composition.capture.value, "frameStateLock"))
    }
    @Test fun failedScopeCreationDoesNotReadClockObserverOrForceServiceAndRetainsOriginalLazyRetry() {
        val f = Fixture(); f.scopeFailure = IllegalStateException("scope unavailable")
        assertSame(f.scopeFailure, runCatching { f.composition.capture.value }.exceptionOrNull())
        assertEquals(listOf("lock", "primary", "scope"), f.events)
        assertFalse(f.composition.capture.isInitialized()); assertFalse(f.composition.services.isInitialized())
        f.scopeFailure = null; f.composition.capture.value; assertTrue(f.composition.capture.isInitialized())
    }
    @Test fun serviceDiscardCallbackMaterializesExactCaptureAtDeliveryAndFixtureEarlyGuardSkipsClock() {
        val f = Fixture(); val services = f.composition.services.value; f.events.clear()
        @Suppress("UNCHECKED_CAST") val discard = field(services, "onDiscard") as (FastSamFrameToken, String) -> Unit
        discard(FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE, 1, 0, 0, 0, 1, "fixture", 1, 1), "old")
        assertTrue(f.composition.capture.isInitialized())
        assertEquals(listOf("lock", "primary", "scope", "clock", "observer"), f.events)
        assertSame(services, field(f.composition.capture.value, "services"))
    }
    @Test fun serviceResultCallbackRetainsActualCaptureAndOriginalCpuClockBeforeAttachmentGuard() {
        val f = Fixture(); val services = f.composition.services.value; f.events.clear()
        @Suppress("UNCHECKED_CAST") val result = field(services, "onResult") as (FastSamResult<UnknownRuntimeAttachment>) -> Unit
        @Suppress("UNCHECKED_CAST") val sample = unsafe.allocateInstance(FastSamResult::class.java) as FastSamResult<UnknownRuntimeAttachment>
        result(sample)
        assertEquals(listOf("lock", "primary", "scope", "clock", "observer", "cpu"), f.events)
        val capture = f.composition.capture.value; f.events.clear(); result(sample)
        assertSame(capture, f.composition.capture.value); assertEquals(listOf("cpu"), f.events)
    }
    @Test fun preparationFinishedChangesOnlyActualCalibrationOwnerAndWillStartDoesNotForceIt() {
        val f = Fixture(); val services = f.composition.services.value; f.events.clear()
        f.calibration.beginPreparation(); f.emit(services, UnknownRuntimeServiceEvent.PreparationFinished)
        assertFalse(f.calibration.projection().preparing); assertEquals(listOf("calibration"), f.events)
        f.events.clear(); f.emit(services, UnknownRuntimeServiceEvent.CalibrationWillStart)
        assertEquals(listOf("cancel-wake"), f.events); assertFalse(f.composition.capture.isInitialized())
    }
    @Test fun deferredDetectorCloseIsConsumedBeforeCloseAndSkipsEveryReadinessQueryEvenOnFailure() {
        val f = Fixture(); val services = f.composition.services.value
        assertTrue(f.calibration.deferDetectorCloseIfCalibrationPresent { true }); f.events.clear()
        f.closeFailure = IllegalStateException("close effect")
        assertSame(f.closeFailure, runCatching { f.emit(services, UnknownRuntimeServiceEvent.CalibrationClosed(null)) }.exceptionOrNull())
        assertEquals(listOf("calibration", "close"), f.events); assertFalse(f.calibration.projection().detectorCloseAfterCalibration)
        assertFalse(f.composition.capture.isInitialized())
    }
    @Test fun readinessGateIsLiveAcrossActivityRetirementAndNoClosedEventFactsAreCached() {
        val f = Fixture(); val services = f.composition.services.value; f.events.clear(); f.mayRefresh = false
        f.emit(services, UnknownRuntimeServiceEvent.CalibrationClosed(null))
        assertEquals(listOf("calibration", "may-refresh"), f.events)
        assertEquals("추가 물체 인식 준비 전", services.status)
        f.events.clear(); f.mayRefresh = true; f.emit(services, UnknownRuntimeServiceEvent.CalibrationClosed(null))
        assertEquals(listOf("calibration", "may-refresh", "refresh"), f.events)
        assertEquals("추가 물체 인식 점검 미완료 · 설정에서 다시 확인 가능", services.status)
    }
    @Test fun adoptedEventRefreshesThenSpeaksAndIncompleteEventUpdatesSameActualServiceStatus() {
        val f = Fixture(); val services = f.composition.services.value; f.events.clear()
        f.emit(services, UnknownRuntimeServiceEvent.CalibrationClosed(result(true)))
        assertEquals(listOf("calibration", "may-refresh", "refresh", "speak"), f.events)
        assertEquals("추가 물체 인식 준비 전", services.status)
        f.events.clear(); f.emit(services, UnknownRuntimeServiceEvent.CalibrationClosed(result(false)))
        assertEquals(listOf("calibration", "may-refresh", "refresh"), f.events)
        assertEquals("추가 물체 인식 점검 미완료 · 설정에서 다시 확인 가능", services.status)
    }
    @Test fun refreshFailureRetainsOriginalTailSuppressionWithoutSpeakingOrUpdatingStatus() {
        val f = Fixture(); val services = f.composition.services.value; f.events.clear()
        f.refreshFailure = IllegalStateException("refresh failed")
        assertSame(f.refreshFailure, runCatching { f.emit(services, UnknownRuntimeServiceEvent.CalibrationClosed(result(true))) }.exceptionOrNull())
        assertEquals(listOf("calibration", "may-refresh", "refresh"), f.events)
        assertEquals("추가 물체 인식 준비 전", services.status)
    }
    private class Fixture {
        val events = mutableListOf<String>(); val lock = Any(); var lockReady = true; var clockReady = true
        var scopeFailure: RuntimeException? = null; var closeFailure: RuntimeException? = null; var refreshFailure: RuntimeException? = null
        var mayRefresh = true
        val primary = PrimaryDetectorExecutionOwner(stateLock = lock, elapsedRealtimeMs = { error("no elapsed query") },
            nativeScheduler = object : PrimaryNativeFlightScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) = error("no schedule")
                override fun remove(task: Runnable) = error("no remove")
            }, rejectBlockedWork = { error("no native admission") }, auxiliaryCopyInFlight = { error("no auxiliary read") },
            onDueButInFlightDrop = { error("no drop") }, onNativeStall = { _, _ -> error("no native stall") },
            dispatchOnMain = { error("no main effect") }, sourceAgeMs = 800)
        val calibration = PrimaryRuntimeCalibrationOwner<Any>(primary, { error("no calibration clock") },
            { _, _ -> error("no cancel") }, { error("no schedule") })
        val authority = proxy<UnknownRuntimeServiceAuthority>(); val scope = proxy<UnknownCaptureScopeReader>()
        val observer = proxy<UnknownCaptureObserver>()
        val clock = object : UnknownCaptureClock {
            override fun elapsedRealtimeMs(): Long { events += "elapsed"; return 0 }
            override fun elapsedRealtimeNanos(): Long { events += "nanos"; return 0 }
            override fun cpuNanos(): Long { events += "cpu"; return 0 }
        }
        val composition = VisionUnknownComposition({ error("no eager SDK context") }, { error("no eager SDK Activity") },
            { events += "primary"; primary }, { events += "calibration"; calibration },
            { events += "lock"; check(lockReady); lock }, { events += "clock"; check(clockReady); clock },
            { events += "authority"; authority }, { events += "scope"; scopeFailure?.let { throw it }; scope },
            { events += "observer"; observer }, { error("no dispatcher") }, { error("no invalidation") },
            object : VisionUnknownPreparationInteraction {
                override fun cancelHomeWakeListening() { events += "cancel-wake" }
                override fun closeDetector() { events += "close"; closeFailure?.let { throw it } }
                override fun mayRefreshReadiness(): Boolean { events += "may-refresh"; return mayRefresh }
                override fun refreshReadiness() { events += "refresh"; refreshFailure?.let { throw it } }
                override fun speakPreparationCompleted() { events += "speak" }
            })
        @Suppress("UNCHECKED_CAST") fun emit(service: UnknownRuntimeServiceOwner, event: UnknownRuntimeServiceEvent) =
            (field(service, "onEvent") as (UnknownRuntimeServiceEvent) -> Unit)(event)
    }
    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        private inline fun <reified T> proxy(): T = Proxy.newProxyInstance(T::class.java.classLoader,
            arrayOf(T::class.java)) { _, method, _ -> error("no policy query: " + method.name) } as T
        private fun result(adopted: Boolean) = RuntimeCalibrationResult(RuntimeSelectionStatus.CONFIRMED,
            "composition", null, null, adopted, false, null)
    }
}
