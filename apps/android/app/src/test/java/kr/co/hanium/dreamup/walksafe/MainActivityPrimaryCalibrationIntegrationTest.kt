package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.*
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Calls actual Main adapters; field state lives exclusively in the injected actual owners. */
class MainActivityPrimaryCalibrationIntegrationTest {
    @Test fun realMainCoordinatorConstructionKeepsTheOriginalProfileStoreAndHandlerLazy() {
        val main = MainActivity()
        val profiles = field(main, "deviceTuningStore\$delegate") as Lazy<*>
        val releaseHandler = field(main, "cameraReleaseHandler\$delegate") as Lazy<*>
        assertFalse(profiles.isInitialized()); assertFalse(releaseHandler.isInitialized())
        val coordinator = field(main, "primaryRuntimeCalibrationCoordinator\$delegate") as Lazy<*>
        assertNotNull(coordinator.value)
        assertFalse(profiles.isInitialized()); assertFalse(releaseHandler.isInitialized())
    }
    @Test fun actualPrivatePreparationRetainsItsForegroundEarlyReturnBeforeCreatingCoordinatorOrReadingSdkClock() {
        val main = MainActivity()
        val coordinator = field(main, "primaryRuntimeCalibrationCoordinator\$delegate") as Lazy<*>
        call(main, "prepareDeviceRuntimeCalibration", arrayOf(Boolean::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!), true, 50L)
        assertFalse(coordinator.isInitialized())
    }
    @Test fun actualPrivateCancellationAdvancesTheActualOwnerBeforeCallingItsExactSessionAndKeepsNativeFailure() {
        val f = Fixture(); f.state.installSession(f.session); val before = f.state.beginPreparation()
        call(f.main, "cancelDeviceRuntimeCalibration", arrayOf(String::class.java), "pause")
        assertEquals(before + 1L, f.cancelGeneration); assertSame(f.session, f.state.projection().session)
        assertTrue(f.state.projection().preparing); assertEquals(listOf("cancel:pause"), f.events)
        val failure = IllegalStateException("cancel"); f.cancelFailure = failure
        assertSame(failure, runCatching { call(f.main, "cancelDeviceRuntimeCalibration", arrayOf(String::class.java), "stop") }.exceptionOrNull())
        assertEquals(before + 2L, f.state.projection().generation); assertSame(f.session, f.state.projection().session)
    }
    @Test fun actualAdoptionCallerRejectsStaleSelectionWithoutSavingOrInstallingASecondDetector() {
        val f = Fixture(); val load = AndroidDetectorLoadResult(null, false, false, null, false, "none")
        val profile = profile(); val selection = RuntimeCalibrationSelection(profile, load, false, { error("stale native") }, { f.events += "reject" })
        val current: () -> Boolean = { false }
        call(f.main, "adoptDeviceRuntimeSelection", arrayOf(RuntimeCalibrationSelection::class.java,
            TfliteAndroidFrameDetector::class.java, kotlin.jvm.functions.Function0::class.java), selection, f.baseline, current)
        assertEquals(listOf("reject"), f.events); assertFalse(f.state.projection().oldDetectorReleasePending)
        assertEquals(0L, f.detector.projection().loadGeneration)
    }
    @Test fun actualCloseCallerReleasesExactPointerBeforeTheAcknowledgementEffect() {
        val f = Fixture(); var completed = false
        val after: () -> Unit = { f.events += "ack"; completed = true }
        call(f.main, "closePreviousRuntimeDetector", arrayOf(TfliteAndroidFrameDetector::class.java,
            kotlin.jvm.functions.Function0::class.java), f.baseline, after)
        assertSame(f.baseline, f.closed); assertTrue(completed); assertEquals(listOf("close", "ui", "ack"), f.events)
    }
    @Test fun actualCloseFailureRetainsOwnerUncertaintyAndDoesNotRunCompletionOrReplayClose() {
        val f = Fixture(); var completed = false; f.closeFailure = IllegalStateException("close")
        val after: () -> Unit = { completed = true }
        call(f.main, "closePreviousRuntimeDetector", arrayOf(TfliteAndroidFrameDetector::class.java,
            kotlin.jvm.functions.Function0::class.java), f.baseline, after)
        assertFalse(completed); assertSame(f.baseline, f.closed); assertTrue(f.state.projection().oldDetectorReleasePending)
        assertEquals(listOf("close", "failed"), f.events)
    }
    private class Fixture {
        val main = MainActivity(); val events = mutableListOf<String>(); val session = Any()
        var cancelFailure: RuntimeException? = null; var cancelGeneration = -1L
        var closeFailure: RuntimeException? = null; var closed: AndroidFrameDetector? = null
        val baseline = unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector
        val detector = PrimaryDetectorExecutionOwner(Any(), Lane(), Lane(), { error("clock") },
            object : PrimaryNativeFlightScheduler { override fun postDelayed(task: Runnable, delayMs: Long) = false; override fun remove(task: Runnable) = Unit },
            { false }, { false }, {}, { _, _ -> }, { it() })
        val state: PrimaryRuntimeCalibrationOwner<Any> = PrimaryRuntimeCalibrationOwner<Any>(detector, { error("clock") }, { _, reason ->
            events += "cancel:$reason"; cancelGeneration = state.projection().generation; cancelFailure?.let { throw it }
        }, { error("no schedule") })
        val coordinator = PrimaryRuntimeCalibrationCoordinator(state, detector,
            object : PrimaryCalibrationProfiles {
                override fun load(binding: String) = error("profile load")
                override fun loadAttempt(binding: String) = error("attempt")
                override fun needsRecheck(binding: String, manual: Boolean) = error("recheck")
                override fun save(profile: RuntimeTuningProfile): Boolean { events += "save"; return true }
                override fun markStarted(binding: String, reason: DeviceTuningRecheckReason, cursor: RuntimeCandidateQueueSnapshot?) = error("started")
                override fun markFinished(binding: String, pending: DeviceTuningRecheckReason?, cursor: RuntimeCandidateQueueSnapshot?) = error("finished")
            }, object : PrimaryCalibrationPlatform<AndroidFrameDetector, Any> {
                override fun createBinding(config: TwoModelRuntimeConfig, scope: RuntimeFeatureScope) = error("binding")
                override fun load(config: TwoModelRuntimeConfig) = error("load")
                override fun createSession(spec: PrimaryCalibrationSessionSpec<AndroidFrameDetector>, callbacks: PrimaryCalibrationSessionCallbacks) = error("session")
                override fun startSession(session: Any) = error("start")
                override fun calibratableDetector(detector: AndroidFrameDetector) = error("calibratable")
                override fun closeDetector(detector: AndroidFrameDetector) { events += "close"; closed = detector; closeFailure?.let { throw it } }
            }, object : PrimaryAuxiliaryCalibration<AndroidFrameDetector> {
                override fun isAbsent() = error("auxiliary")
                override fun releaseBeforePrimary() = error("release")
                override fun prepare(config: TwoModelRuntimeConfig, primary: AndroidFrameDetector, candidate: RuntimeCandidate,
                    scope: RuntimeFeatureScope, manual: Boolean, deadlineMs: Long, current: () -> Boolean) = error("prepare")
            }, object : PrimaryCalibrationEffects {
                override fun onUi(effect: () -> Unit) { events += "ui"; effect() }
                override fun cameraOwnerAvailable() = error("camera")
                override fun cancelHomeWakeListening() = error("wake")
                override fun verifyRunningDetector(detector: AndroidFrameDetector, modelKey: String?, fallbackUsed: Boolean) = error("verify")
                override fun resetRuntimeLoadMeasurements() = error("reset")
                override fun measuredProfileStatus(profile: RuntimeTuningProfile) = error("status")
                override fun schedulePreparation(deadlineMs: Long) = error("schedule")
                override fun closeDetectorAfterCalibration() = error("close-after")
                override fun comparisonDeadlineReached() = error("deadline")
                override fun previousDetectorReleaseFailed(error: RuntimeException) { events += "failed" }
            }, { error("clock") })
        init {
            set(main, "primaryRuntimeCalibrationOwner\$delegate", lazyOf(state))
            set(main, "primaryRuntimeCalibrationCoordinator\$delegate", lazyOf(coordinator))
        }
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
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        private fun primaryAlias(target: Any, name: String): Pair<Any, java.lang.reflect.Field>? {
            if (target !is MainActivity || name != "primaryRuntimeCalibrationOwner\$delegate") return null
            val composition = target.javaClass.getDeclaredField("visionPrimaryComposition").apply { isAccessible = true }.get(target)
            return composition to composition.javaClass.getDeclaredField("calibration").apply { isAccessible = true }
        }
        private fun field(target: Any, name: String): Any? {
            val alias = primaryAlias(target, name)
                ?: return target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            val actual = alias.second.get(alias.first) as Lazy<*>
            val backing = try { target.javaClass.getDeclaredField(name).apply { isAccessible = true } } catch (_: NoSuchFieldException) { null }
            val observed = if (backing != null) backing.get(target) else {
                MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                    .findStatic(MainActivity::class.java, "getPrimaryRuntimeCalibrationOwner\$delegate",
                        MethodType.methodType(Any::class.java, MainActivity::class.java)).invokeWithArguments(target)
            }
            check(observed === actual) { "Main alias must be the actual composition Lazy: $name" }
            return actual
        }
        private fun set(target: Any, name: String, value: Any?) {
            val alias = primaryAlias(target, name)
            if (alias == null) {
                target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
                return
            }
            check(value is Lazy<*>)
            alias.second.set(alias.first, value)
            val backing = try { target.javaClass.getDeclaredField(name).apply { isAccessible = true } } catch (_: NoSuchFieldException) { null }
            backing?.set(target, value)
            check(field(target, name) === value) { "Fixture must retain the same actual Lazy" }
        }
        private fun call(target: Any, name: String, types: Array<Class<*>>, vararg args: Any?): Any? =
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup()).findVirtual(MainActivity::class.java,
                name, MethodType.methodType(Void.TYPE, types.toList())).bindTo(target).invokeWithArguments(args.toList())
        private fun profile(): RuntimeTuningProfile {
            val candidate = RuntimeCandidate(RuntimeBackend.CPU, 1)
            return RuntimeTuningProfile("binding", candidate, RuntimeFeatureScope.CAMERA_TRACKING, "v", "h", "p", 1L,
                RuntimeConfirmedMeasurement("binding", RuntimeFeatureScope.CAMERA_TRACKING, candidate.backend,
                    1, 1, 100.0, 90.0, 1.0, "v", "h", 1, 1_000L))
        }
    }
}
