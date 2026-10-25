package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.postlogin.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureClock
import kr.co.hanium.dreamup.walksafe.device.RuntimeArSessionCleanupOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring and actual Main close-action tests; ARCore Session/native close is not executed. */
class MainActivityArSessionCleanupOwnershipTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun sourceOnlyOwnedSessionCloseTargetIsRetainedBeforeClearingActiveSession() {
        val stop = MainActivityRefactorSource.function(source, "private fun stopDepthSession(")
        assertTrue(stop.contains("arOwner.detachForStop(closeSession)"))
        assertTrue(MainActivityRefactorSource.member(source, "stopDepthSession").contains("runtimeCameraLifecycle.stopDepthSession(closeSession)"))
        MainActivityRefactorSource.composition(source, "runtimeCameraLifecycle")
        val arBinding = MainActivityRefactorSource.property(source, "arSessionOwner")
        assertTrue(arBinding.contains("RuntimeArSessionOwner<Session, ArCoreFrameProvider, ArSessionPurpose>("))
        assertTrue(arBinding.contains("stateLock = runtimeMetricStateLock"))
        assertTrue(arBinding.contains("closeSession = { it.close() }"))
        val arSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/ar/RuntimeArSessionOwner.kt").readText()
        val detach = MainActivityRefactorSource.member(arSource, "detachForStop")
        val capture = detach.indexOf("val captured = observe()")
        val activeClear = detach.indexOf("clearActive()", capture)
        assertTrue("The cleanup must capture the original active session", capture >= 0)
        assertTrue("The active session is cleared after capturing its owner", activeClear > capture)
        val beforeActiveClear = detach.substring(capture, activeClear)
        val condition = beforeActiveClear.indexOf("closeNativeSession && target != null")
        val retained = beforeActiveClear.indexOf("retainClose { closeSession(target) }")
        assertTrue("Closing the captured session must be conditional", condition >= 0)
        assertTrue("Retain the original close target before clearing active state", retained > condition)
        assertTrue(beforeActiveClear.contains("return@synchronized null"))
        assertTrue(MainActivityRefactorSource.member(arSource, "clearActive").contains("session = null"))
    }

    @Test
    fun failedCloseActionLeavesItsActualMainCleanupTargetPending() {
        val activity = isolatedMain()
        val failure = IllegalStateException("AR session close failed")
        var closeCalls = 0
        val closeAction: () -> Unit = {
            closeCalls += 1
            throw failure
        }

        val result = runCatching { activity.closeRuntimeArSession(closeAction) }

        assertObservedCloseFailure(activity, result, failure)
        assertEquals(1, closeCalls)
        assertTrue("A failed close must retain its cleanup target", activity.runtimeArSessionCleanupPending())
        assertSame(closeAction, field(activity, "pendingRuntimeArSessionCloseAction"))
    }

    @Test
    fun pendingCloseTargetCannotBeReplacedByAnotherAction() {
        val activity = isolatedMain()
        val failure = IllegalStateException("original AR session close failed")
        var originalCloseCalls = 0
        val originalCloseAction: () -> Unit = {
            originalCloseCalls += 1
            throw failure
        }
        assertObservedCloseFailure(
            activity,
            runCatching { activity.closeRuntimeArSession(originalCloseAction) },
            failure,
        )
        assertEquals(1, originalCloseCalls)
        var replacementCloseCalls = 0

        val acknowledged = activity.closeRuntimeArSession { replacementCloseCalls += 1 }

        assertEquals("Another action cannot replace the failed close owner", 0, replacementCloseCalls)
        assertFalse("An unresolved original cleanup cannot acknowledge a different close", acknowledged)
        assertTrue(activity.runtimeArSessionCleanupPending())
        assertEquals(1, originalCloseCalls)

        assertFalse(activity.closeRuntimeArSession(originalCloseAction))
        assertFalse(activity.closeRetainedRuntimeArSession())
        assertEquals("An uncertain native close is not replayed", 1, originalCloseCalls)
        assertEquals(0, replacementCloseCalls)
        assertSame(originalCloseAction, field(activity, "pendingRuntimeArSessionCloseAction"))
        assertSame(failure, field(activity, "lastRuntimeArSessionCloseFailure"))
    }

    @Test
    fun acknowledgedCloseClearsPendingStateAndAllowsAnIndependentAction() {
        val activity = isolatedMain()
        var firstCloseCalls = 0

        assertTrue(activity.closeRuntimeArSession { firstCloseCalls += 1 })

        assertEquals(1, firstCloseCalls)
        assertFalse(activity.runtimeArSessionCleanupPending())
        var independentCloseCalls = 0

        assertTrue(activity.closeRuntimeArSession { independentCloseCalls += 1 })

        assertEquals(1, firstCloseCalls)
        assertEquals(1, independentCloseCalls)
        assertFalse(activity.runtimeArSessionCleanupPending())
    }

    @Test
    fun failedCloseBlocksActualCameraStartWhileAHealthyMainRemainsUnblocked() {
        val activity = isolatedMain()
        assertFalse(cameraStartBlocked(activity))
        val failure = IllegalStateException("camera owner close failed")

        assertObservedCloseFailure(
            activity,
            runCatching { activity.closeRuntimeArSession { throw failure } },
            failure,
        )

        assertTrue(activity.runtimeArSessionCleanupPending())
        assertTrue(cameraStartBlocked(activity))
    }

    @Test
    fun preCloseFailureKeepsTheOriginalCauseWithoutExecutingOrReplayingNativeClose() {
        val activity = isolatedMain()
        val failure = IllegalStateException("AR renderer pre-close failed")
        var nativeCloseCalls = 0
        var beforeCloseCalls = 0
        val closeAction: () -> Unit = { nativeCloseCalls += 1 }
        assertTrue(activity.retainRuntimeArSessionCloseOwner(closeAction))

        val result = runCatching {
            activity.closeRetainedRuntimeArSession {
                beforeCloseCalls += 1
                throw failure
            }
        }

        assertObservedCloseFailure(activity, result, failure)
        assertEquals(1, beforeCloseCalls)
        assertEquals(0, nativeCloseCalls)
        assertTrue(activity.runtimeArSessionCleanupPending())
        assertSame(closeAction, field(activity, "pendingRuntimeArSessionCloseAction"))

        assertFalse(activity.closeRetainedRuntimeArSession { beforeCloseCalls += 1 })
        assertFalse(activity.closeRuntimeArSession(closeAction))
        assertEquals(1, beforeCloseCalls)
        assertEquals(0, nativeCloseCalls)
        assertTrue(activity.runtimeArSessionCleanupPending())
        assertSame(failure, field(activity, "lastRuntimeArSessionCloseFailure"))
    }

    @Test
    fun reentrantCloseCallbackKeepsItsOwnerUntilAcknowledgementAndRejectsAnotherOwner() {
        val activity = isolatedMain()
        var originalCloseCalls = 0
        var replacementCloseCalls = 0
        val replacementCloseAction: () -> Unit = { replacementCloseCalls += 1 }
        lateinit var originalCloseAction: () -> Unit
        originalCloseAction = {
            originalCloseCalls += 1
            assertFalse(Thread.holdsLock(field(activity, "runtimeMetricStateLock")!!))
            assertTrue(activity.runtimeArSessionCleanupPending())
            assertSame(originalCloseAction, field(activity, "pendingRuntimeArSessionCloseAction"))
            assertFalse(activity.retainRuntimeArSessionCloseOwner(replacementCloseAction))
            assertTrue(activity.runtimeArSessionCleanupPending())
        }

        assertTrue(activity.closeRuntimeArSession(originalCloseAction))

        assertEquals(1, originalCloseCalls)
        assertEquals(0, replacementCloseCalls)
        assertFalse(activity.runtimeArSessionCleanupPending())
        assertNull(field(activity, "pendingRuntimeArSessionCloseAction"))
        assertNull(field(activity, "lastRuntimeArSessionCloseFailure"))
    }

    @Test
    fun sourceOnlyPendingCleanupBlocksPreflightAndOutputsAndFailedAckKeepsTheSafetyStageUncertain() {
        val metricSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricSessionController.kt").readText()
        val preflight = MainActivityRefactorSource.member(metricSource, "canBeginRuntimeMetricPreflight")
        assertTrue(preflight.contains("if (native.cleanupPending) return false"))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession").contains("override val cleanupPending get() = runtimeArSessionCleanupPending()"))
        assertTrue(MainActivityRefactorSource.member(metricSource, "currentRuntimeMetricOutputAllowsWork").contains("!native.cleanupPending"))
        assertTrue(MainActivityRefactorSource.member(source, "currentRuntimeMetricOutputAllowsWork").contains("runtimeMetricSession.currentRuntimeMetricOutputAllowsWork("))
        assertTrue(MainActivityRefactorSource.member(source, "currentArCoreQualitativeFeedbackAllowsWork").contains("!runtimeArSessionCleanupPending()"))
        val safetyStop = MainActivityRefactorSource.function(source, "private fun performWalkSessionSafetyStopCleanup(")
        val depthStageStart = safetyStop.indexOf("stage(\"depth_session\")")
        assertTrue(depthStageStart >= 0)
        val depthStage = safetyStop.substring(depthStageStart).substringBefore("stage(\"startup_ui\")")
        assertTrue(depthStage.contains("val stopped = native.stopDepthSession(closeSession = true)"))
        assertTrue(depthStage.contains("if (checkpoints != null && !stopped)"))
        assertTrue(depthStage.contains("throw IllegalStateException(\"depth_session_stop_failed\", native.lastDepthCloseFailure())"))
        val cleanup = MainActivityRefactorSource.composition(source, "walkSessionCleanupController")
        assertTrue(cleanup.contains("override fun stopDepthSession(closeSession: Boolean) = this@MainActivity.stopDepthSession(closeSession)"))
        assertTrue(cleanup.contains("override fun lastDepthCloseFailure() = arSessionOwner.cleanupFailureOrNull()"))
    }

    private fun assertObservedCloseFailure(
        activity: MainActivity,
        result: Result<Boolean>,
        expectedFailure: RuntimeException,
    ) {
        assertNull("A Boolean cleanup failure must let its caller continue", result.exceptionOrNull())
        assertEquals("A failed native close cannot acknowledge success", false, result.getOrNull())
        assertSame(expectedFailure, field(activity, "lastRuntimeArSessionCloseFailure"))
    }

    private fun cameraStartBlocked(activity: MainActivity): Boolean {
        val method = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(
                MainActivity::class.java,
                "runtimeCameraStartBlockedByAnotherOwner",
                MethodType.methodType(Boolean::class.javaPrimitiveType),
            )
        val holder = MainActivity::class.java.getDeclaredField("postLoginDeviceProbesHolder").run {
            isAccessible = true
            get(activity) as Lazy<*>
        }
        val firstQuery = !holder.isInitialized()
        val blocked = method.bindTo(activity).invokeWithArguments(emptyList<Any>()) as Boolean
        if (firstQuery && !blocked) {
            check(holder.isInitialized()) { "The healthy camera gate must query the actual native probe owner" }
            val probes = holder.value as AndroidPostLoginDeviceProbes
            check(MainActivity::class.java.getDeclaredField("postLoginDeviceProbesHolder").run {
                isAccessible = true; get(activity)
            } === holder)
            check(probes.javaClass.getDeclaredField("detectorExecutor").run {
                isAccessible = true; get(probes)
            } === field(activity, "detectorExecutor"))
        }
        return blocked
    }

    private fun isolatedMain(): MainActivity = MainActivity().also { activity ->
        MainActivityControllerTestFixture.field("runtimeArSessionCleanupOwnership")
            .set(activity, RuntimeArSessionCleanupOwnership())
        installIdleNativeProbeOwner(activity)
    }

    /** Actual pure owner defaults; native operations fail loudly outside this cleanup fixture. */
    private fun installIdleNativeProbeOwner(activity: MainActivity) {
        val holder = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val executor = field(activity, "detectorExecutor") as java.util.concurrent.Executor
            val clock = field(activity, "unknownCaptureClock") as UnknownCaptureClock
            AndroidPostLoginDeviceProbes(
                location = unusedNativePort(PostLoginLocationSource::class.java),
                camera = unusedNativePort(PostLoginCameraSource::class.java),
                wakeFactory = unusedNativePort(PostLoginWakeProbeFactory::class.java),
                hapticFactory = { unexpectedNativeQuery() },
                detector = { unexpectedNativeQuery() }, detectorExecutor = executor,
                dispatcher = unusedNativePort(PostLoginProbeDispatcher::class.java),
                cameraReleaseDispatcher = unusedNativePort(PostLoginProbeDispatcher::class.java),
                elapsedRealtimeMs = clock::elapsedRealtimeMs,
                environment = { unexpectedNativeQuery() }, currentBinding = { unexpectedNativeQuery() },
                isCurrent = { unexpectedNativeQuery() }, uiAlive = { unexpectedNativeQuery() },
                sharedReleaseBlocked = { unexpectedNativeQuery() }, markSharedReleaseBlocked = { unexpectedNativeQuery() },
                modelDirectory = { unexpectedNativeQuery() }, modelPreparationFailed = { unexpectedNativeQuery() },
                stopVoiceService = { unexpectedNativeQuery() },
                support = unusedNativePort(PostLoginProbeRuntimeSupport::class.java),
                observer = unusedNativePort(PostLoginProbeObserver::class.java),
            )
        }
        check(!holder.isInitialized())
        MainActivity::class.java.getDeclaredField("postLoginDeviceProbesHolder").apply {
            isAccessible = true
        }.set(activity, holder)
    }

    private fun unexpectedNativeQuery(): Nothing =
        error("Unexpected native operation in the AR cleanup state fixture")

    @Suppress("UNCHECKED_CAST")
    private fun <T> unusedNativePort(type: Class<T>): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> unexpectedNativeQuery() } as T

    private fun field(activity: MainActivity, name: String): Any? {
        val ownership = MainActivityControllerTestFixture.field("runtimeArSessionCleanupOwnership")
            .get(activity) as RuntimeArSessionCleanupOwnership
        return when (name) {
            "pendingRuntimeArSessionCloseAction" -> RuntimeArSessionCleanupOwnership::class.java
                .getDeclaredField("retainedAction").run { isAccessible = true; get(ownership) }
            else -> MainActivityControllerTestFixture.field(name).get(activity)
        }
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }
}
