package kr.co.hanium.dreamup.walksafe.runtime.metric

import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.runtime.camera.lifecycle.RuntimeArPlatform
import kr.co.hanium.dreamup.walksafe.runtime.vision.ar.RuntimeArSessionOwner
import kr.co.hanium.dreamup.walksafe.session.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Actual metric/native/lifecycle state owners; SDK operations and View/renderer effects are ports. */
class RuntimeMetricPreflightCoordinatorTest {
    @Test fun constructionDoesNotQueryStateClockPlatformOrWidgets() {
        val f = Fixture(); assertTrue(f.events.isEmpty()); assertEquals(0, f.clockReads)
    }
    @Test fun staleAttemptClosesOnlySuppliedSessionWithoutClockRendererOrPublication() {
        val f = Fixture(); f.coordinator.startRuntimeMetricPreflightSession(f.session, f.provider, 7, 7)
        assertEquals(listOf("close"), f.events); assertNull(f.native.observe().session); assertEquals(0, f.clockReads)
    }
    @Test fun originalClockProfileResumeAndActualAtomicPublicationOrderIsPreserved() {
        val f = Fixture(); f.prepare()
        f.onTexture = { bound ->
            assertTrue(Thread.holdsLock(f.lock)); assertTrue(bound)
            assertSame(f.session, f.native.observe().session)
            assertEquals(f.native.observe().generation, f.metric.preflightArGeneration)
            assertSame(f.evaluatorAtTexture, f.metric.preflightEvaluator)
        }
        f.coordinator.startRuntimeMetricPreflightSession(f.session, f.provider, 1, 7)
        assertEquals(listOf("clock", "profile", "renderer-pause", "native-resume", "texture", "renderer-resume:true",
            "supported", "status", "refresh", "expiry"), f.events)
        assertEquals(1_000L, f.metric.preflightEvaluator!!.startedAtElapsedRealtimeMs)
        assertEquals(Purpose.PREFLIGHT, f.native.observe().purpose)
        assertSame(f.provider, f.native.observe().provider)
        assertEquals(RuntimeMetricPreflightPolicy.MAX_DURATION_MS, f.expiryDelay)
    }
    @Test fun resumeFailureRunsOriginalRendererFinallyWithoutInventingCloseOrPublication() {
        val f = Fixture(); f.prepare(); val error = IllegalStateException("resume")
        f.failures["native-resume"] = error
        assertSame(error, runCatching { f.coordinator.startRuntimeMetricPreflightSession(f.session, f.provider, 1, 7) }.exceptionOrNull())
        assertEquals(listOf("clock", "profile", "renderer-pause", "native-resume", "renderer-resume:true"), f.events)
        assertNull(f.native.observe().session); assertNull(f.metric.preflightEvaluator)
    }
    @Test fun publicationCallbackFailureRetainsOriginalCommittedStateAndSkipsLaterUi() {
        val f = Fixture(); f.prepare(); val error = IllegalStateException("texture commit")
        f.failures["texture"] = error
        assertSame(error, runCatching { f.coordinator.startRuntimeMetricPreflightSession(f.session, f.provider, 1, 7) }.exceptionOrNull())
        assertSame(f.session, f.native.observe().session); assertNotNull(f.metric.preflightEvaluator)
        assertTrue(f.events.contains("renderer-resume:true")); assertFalse(f.events.contains("supported"))
    }
    @Test fun staleExpiryCannotCancelReplacementActualEvaluator() {
        val f = Fixture(); f.prepare(); f.coordinator.startRuntimeMetricPreflightSession(f.session, f.provider, 1, 7)
        val oldExpiry = requireNotNull(f.expiry)
        f.metric.invalidateEvidence(); f.prepare()
        f.coordinator.startRuntimeMetricPreflightSession(f.session, f.provider, 3, 7)
        val current = f.metric.preflightEvaluator
        oldExpiry()
        assertSame(current, f.metric.preflightEvaluator)
        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, current!!.result().status)
    }
    @Test fun currentAttemptExpiryUsesOriginalElapsedClockAndActualDeviceContinuation() {
        val f = Fixture(); f.prepare(); f.coordinator.startRuntimeMetricPreflightSession(f.session, f.provider, 1, 7)
        f.now += RuntimeMetricPreflightPolicy.MAX_DURATION_MS + 1
        requireNotNull(f.expiry).invoke()
        assertTrue(f.ui.isEmpty())
        assertTrue(f.events.any { it.startsWith("complete-refresh:") })
        assertEquals(RuntimeMetricPreflightOwner.NONE, f.metric.preflightOwner)
    }
    @Test fun staleSupportContinuationReadsNoPermissionProbeOrClock() {
        val f = Fixture(); f.coordinator.continueRuntimeMetricPreflightStart(ArCoreApk.Availability.SUPPORTED_INSTALLED, 8, 7)
        assertTrue(f.events.isEmpty()); assertEquals(0, f.clockReads)
    }
    @Test fun permissionAndAvailabilityBranchesDoNotCreateSupportSessions() {
        listOf(false to ArCoreApk.Availability.SUPPORTED_INSTALLED,
            true to ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
            true to ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED).forEach { (permission, availability) ->
            val f = Fixture(); f.prepare(); f.permission = permission
            f.coordinator.continueRuntimeMetricPreflightStart(availability, 1, 7)
            assertFalse(f.events.contains("create")); assertEquals(0, f.clockReads)
            assertEquals(RuntimeMetricPreflightOwner.NONE, f.metric.preflightOwner)
            val completed = f.events.single { it.startsWith("complete-refresh:") }
            assertEquals(if (!permission || availability != ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE)
                "complete-refresh:null" else "complete-refresh:false", completed)
        }
    }
    @Test fun supportProbeAlwaysClosesItsExactTemporarySessionAndUsesOriginalResultReason() {
        listOf(true, false).forEach { supported ->
            val f = Fixture(); f.prepare(); f.supported = supported
            f.coordinator.continueRuntimeMetricPreflightStart(ArCoreApk.Availability.SUPPORTED_INSTALLED, 1, 7)
            assertTrue(f.events.indexOf("create") < f.events.indexOf("probe"))
            assertTrue(f.events.indexOf("probe") < f.events.indexOf("close"))
            assertTrue(f.events.indexOf("close") < f.events.indexOf("complete-refresh:$supported"))
            assertTrue(f.diagnostics.single().contains(if (supported) "DEPTH_MODE_AUTOMATIC_SUPPORTED" else "DEPTH_MODE_AUTOMATIC_UNSUPPORTED"))
        }
    }
    @Test fun probeOrCloseFailureClassifiesAfterFinallyAndDoesNotPublishAnArSlot() {
        listOf("probe", "close").forEach { stage ->
            val f = Fixture(); f.prepare(); f.failures[stage] = IllegalStateException(stage)
            f.coordinator.continueRuntimeMetricPreflightStart(ArCoreApk.Availability.SUPPORTED_INSTALLED, 1, 7)
            assertEquals(1, f.events.count { it == "close" })
            assertTrue(f.events.contains("complete-refresh:null"))
            assertTrue(f.diagnostics.single().contains("reason=IllegalStateException"))
            assertNull(f.native.observe().session)
        }
    }
    @Test fun unsupportedDeviceExceptionRemainsUnsupportedWhileCreateFailureHasNoClose() {
        val f = Fixture(); f.prepare(); f.failures["probe"] = UnavailableDeviceNotCompatibleException()
        f.coordinator.continueRuntimeMetricPreflightStart(ArCoreApk.Availability.SUPPORTED_INSTALLED, 1, 7)
        assertTrue(f.events.contains("close")); assertTrue(f.events.contains("complete-refresh:false"))
        assertTrue(f.diagnostics.single().contains("ARCORE_DEVICE_NOT_COMPATIBLE"))
        val failed = Fixture(); failed.prepare(); failed.failures["create"] = IllegalStateException("create")
        failed.coordinator.continueRuntimeMetricPreflightStart(ArCoreApk.Availability.SUPPORTED_INSTALLED, 1, 7)
        assertFalse(failed.events.contains("close")); assertTrue(failed.events.contains("complete-refresh:null"))
    }
    @Test fun pendingAvailabilityUiRechecksOriginalGenerationBeforeSupportProbe() {
        val f = Fixture(); f.prepare(); f.availability = ArCoreApk.Availability.UNKNOWN_CHECKING
        f.coordinator.startRuntimeMetricPreflight(1, 7)
        assertEquals(listOf("availability", "pending", "async"), f.events)
        f.metric.invalidateEvidence(); requireNotNull(f.availabilityCallback)(ArCoreApk.Availability.SUPPORTED_INSTALLED)
        assertEquals(1, f.ui.size); f.ui.removeAt(0).invoke()
        assertFalse(f.events.contains("permission")); assertFalse(f.events.contains("create"))
    }
    @Test fun pendingAvailabilitySuccessUsesOriginalPurposeAndFinishesOnce() {
        val f = Fixture(); f.prepare(); f.availability = ArCoreApk.Availability.UNKNOWN_CHECKING
        f.coordinator.startRuntimeMetricPreflight(1, 7)
        requireNotNull(f.availabilityCallback)(ArCoreApk.Availability.SUPPORTED_INSTALLED)
        f.ui.removeAt(0).invoke(); requireNotNull(f.availabilityCallback)(ArCoreApk.Availability.SUPPORTED_INSTALLED)
        f.ui.removeAt(0).invoke()
        assertEquals(1, f.events.count { it == "probe" }); assertEquals(2L, f.metric.preflightGeneration)
    }
    @Test fun lossRejectsForeignPurposeGenerationOrStartupWithoutSdkQuery() {
        listOf(Purpose.PREFLIGHT to 1L, Purpose.RUNTIME to 99L).forEach { (purpose, expected) ->
            val f = Fixture(); f.native.publish(f.session, f.provider, purpose)
            f.coordinator.handleRuntimeMetricLoss(expected); assertTrue(f.events.isEmpty())
        }
        val f = Fixture(); val generation = f.native.publish(f.session, f.provider, Purpose.RUNTIME)
        f.metricAllowed = false; f.coordinator.handleRuntimeMetricLoss(generation)
        assertEquals(listOf("metric-allowed"), f.events)
    }
    @Test fun admittedLossCapturesAvailabilityThenDiagnosticsThenOriginalStopAndFallback() {
        val f = Fixture(debug = true); val generation = f.native.publish(f.session, f.provider, Purpose.RUNTIME)
        f.failures["availability"] = IllegalStateException("availability")
        f.coordinator.handleRuntimeMetricLoss(generation)
        assertEquals(listOf("metric-allowed", "availability", "diagnostic", "stop-depth", "fallback:UNKNOWN_ERROR"), f.events)
        assertTrue(f.diagnostics.single().contains("activation=COMPLETE reason=FRAME_OUTPUT_LOST"))
    }
    @Test fun stopFailurePreventsFallbackAndDebugFailurePreventsStopAsOriginally() {
        val f = Fixture(); val gen = f.native.publish(f.session, f.provider, Purpose.RUNTIME); val fail = IllegalStateException("stop")
        f.failures["stop-depth"] = fail
        assertSame(fail, runCatching { f.coordinator.handleRuntimeMetricLoss(gen) }.exceptionOrNull())
        assertFalse(f.events.any { it.startsWith("fallback:") })
        val debug = Fixture(debug = true); val g = debug.native.publish(debug.session, debug.provider, Purpose.RUNTIME)
        debug.failures["diagnostic"] = fail
        assertSame(fail, runCatching { debug.coordinator.handleRuntimeMetricLoss(g) }.exceptionOrNull())
        assertFalse(debug.events.contains("stop-depth"))
    }
    @Test fun realMetricAdmissionRejectsBeforeSynchronousFrameObservation() {
        val f = Fixture(active = true); val generation = f.native.publish(f.session, f.provider, Purpose.RUNTIME)
        f.foreground = false
        f.coordinator.updateOutputGate("frame", f.depth, f.now, generation, f.lifecycle.snapshot().epoch)
        assertEquals(0, f.frameReads); assertFalse(f.metric.outputAllowed)
    }
    @Test fun admittedSynchronousFrameUsesOriginalScalarsAndActualMetricActivation() {
        val f = Fixture(active = true); val generation = f.native.publish(f.session, f.provider, Purpose.RUNTIME)
        f.metric.beginRuntimeMetricActivation(generation, f.now)
        f.coordinator.updateOutputGate("original-frame", f.depth, f.now, generation, f.lifecycle.snapshot().epoch,
            recognitionCameraTimestampNs = 77)
        assertEquals(1, f.frameReads); assertEquals("original-frame", f.seenFrame); assertEquals(77L, f.seenCameraStamp)
        assertTrue(f.metric.outputAllowed); assertEquals(f.now, f.metric.lastValidFrameAtMs)
    }

    @Test fun retiredActorBindingRejectsSdkStartAndClosesOnlyThePassedSession() {
        val f = Fixture(); f.prepare(); f.bindingLive = false
        f.coordinator.startRuntimeMetricPreflight(1, 7)
        assertTrue(f.events.isEmpty())
        f.coordinator.startRuntimeMetricPreflightSession(f.session, f.provider, 1, 7)
        assertEquals(listOf("close"), f.events)
        assertEquals(0, f.clockReads); assertNull(f.native.observe().session)
    }
    @Test fun temporaryCloseFailureKeepsOriginalFinallyPrecedenceOverProbeException() {
        val f = Fixture(); f.prepare()
        f.failures["probe"] = IllegalArgumentException("probe")
        f.failures["close"] = IllegalStateException("close")
        f.coordinator.continueRuntimeMetricPreflightStart(ArCoreApk.Availability.SUPPORTED_INSTALLED, 1, 7)
        assertTrue(f.diagnostics.single().contains("reason=IllegalStateException"))
        assertTrue(f.events.contains("complete-refresh:null"))
        assertEquals(1, f.events.count { it == "close" })
    }
    @Test fun synchronousFrameObservationFailurePropagatesWithoutPublishingOrRetrying() {
        val f = Fixture(active = true); val generation = f.native.publish(f.session, f.provider, Purpose.RUNTIME)
        f.metric.beginRuntimeMetricActivation(generation, f.now)
        val activation = f.metric.activationEvaluator; val failure = IllegalStateException("SDK observation")
        f.frameFailure = failure
        assertSame(failure, runCatching { f.coordinator.updateOutputGate("frame", f.depth, f.now,
            generation, f.lifecycle.snapshot().epoch) }.exceptionOrNull())
        assertEquals(1, f.frameReads); assertSame(activation, f.metric.activationEvaluator)
        assertFalse(f.metric.outputAllowed); assertSame(f.session, f.native.observe().session)
    }

    private enum class Purpose { NONE, PREFLIGHT, RUNTIME }
    private class Fixture(active: Boolean = false, debug: Boolean = false) {
        val lock = Any(); val session = Any(); val provider = Any(); val binding = PostLoginDeviceCheckBinding("actor", 1, 1)
        val events = mutableListOf<String>(); val diagnostics = mutableListOf<String>(); val failures = mutableMapOf<String, Throwable>()
        val ui = mutableListOf<() -> Unit>(); var expiry: (() -> Unit)? = null; var expiryDelay = -1L
        var availabilityCallback: ((ArCoreApk.Availability) -> Unit)? = null
        var availability = ArCoreApk.Availability.SUPPORTED_INSTALLED
        var now = 1_000L; var clockReads = 0; var permission = true; var supported = true; var metricAllowed = true; var foreground = true
        var bindingLive = true; var frameFailure: RuntimeException? = null
        var frameReads = 0; var seenFrame: String? = null; var seenCameraStamp: Long? = null
        var onTexture: (Boolean) -> Unit = {}; var evaluatorAtTexture: RuntimeMetricPreflightSession? = null
        val depth = DepthFrameSnapshot(1_000_000_000L, null, null, null)
        fun event(name: String) { events += name; failures[name]?.let { throw it } }
        val lifecycle = WalkSessionLifecycle({ "preflight-private" }, { now }, { now }).apply {
            handle(WalkSessionEvent.EnteredForeground)
            if (active) {
                val collector = WalkSessionReadinessCollector(snapshot().epoch, WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
                collector.plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(snapshot().epoch, it, WalkSessionReadinessStatus.READY)) }
                handle(WalkSessionEvent.InitialCheckCompleted(collector.build(now)))
                handle(WalkSessionEvent.StartRequested(requireNotNull(snapshot().confirmationToken)))
            }
        }
        val native = RuntimeArSessionOwner<Any, Any, Purpose>(lock, Purpose.NONE, { event("native-close") }, {}, RuntimeArSessionCleanupOwnership())
        val metric = RuntimeMetricSessionController<Any, Purpose, Any>(lock,
            proxy<RuntimeMetricScope> { n, _ -> when(n) {
                "getFeedbackGeneration" -> 7; "getForeground" -> foreground; "getFinishing", "getDestroyed" -> false
                "getRuntimeActive" -> active; "getStartupConfirmed", "getCameraOutputsAllowed", "getMountingOutputsAllowed" -> true
                "getMetricDistanceAllowed" -> true; "getLifecycle" -> lifecycle
                "getProfile" -> RuntimeMetricPreflightPolicy.testCandidateProfile
                else -> error("Unexpected scope $n")
            } },
            proxy<RuntimeMetricNativePort<Purpose, Any>> { n, _ -> when(n) {
                "observe" -> native.observe().let { RuntimeMetricArObservation(it.session, it.provider, it.purpose, it.generation) }
                "getPurpose" -> native.observe().purpose; "getGeneration" -> native.observe().generation
                "getSessionPresent" -> native.observe().session != null; "getProviderPresent" -> native.observe().provider != null
                "preflightDiagnostics" -> "test-observation"; "getCleanupPending" -> native.cleanupPending()
                "detachCompletedPreflight" -> native.detachCompletedPreflight()
                "finishDetachedPreflight" -> { event("finish-native"); Unit }
                else -> error("Unexpected native $n")
            } },
            proxy<RuntimeMetricDeviceChecks<Any>> { n, _ -> when(n) {
                "bindingLive" -> bindingLive
                "storedRefreshCurrent", "completeStoredRefresh" -> if(n == "completeStoredRefresh") {
                    error("Handled with arguments below")
                } else true
                "setPreflightStarted" -> Unit
                "profileMatch" -> ApprovedDeviceProfileMatch(false, null, null, ApprovedDeviceProfileMatchReason.NO_EXACT_MATCH)
                "persistResult" -> Unit
                else -> error("Unexpected device $n")
            } }.let { base -> proxy<RuntimeMetricDeviceChecks<Any>> { n, a ->
                if(n == "completeStoredRefresh") { event("complete-refresh:${a!![0]}"); true }
                else RuntimeMetricDeviceChecks::class.java.methods.single { it.name == n }.invoke(base, *(a ?: emptyArray()))
            } },
            proxy<RuntimeMetricEffects> { n, a -> when(n) {
                "startNativePreflight" -> Unit
                "info" -> { diagnostics += a!![1] as String; Unit }
                "status" -> Unit; "refreshStartupUi" -> Unit; "getDebug" -> false
                "postToUi" -> { @Suppress("UNCHECKED_CAST") ui += a!![0] as () -> Unit; Unit }
                "startNavigationServices" -> Unit
                else -> error("Unexpected metric effect $n")
            } }, Purpose.NONE, Purpose.PREFLIGHT, Purpose.RUNTIME, 1_500, { now })
        val platform = object : RuntimeArPlatform<Any, Any> {
            override fun availability(): ArCoreApk.Availability { event("availability"); return availability }
            override fun availabilityAsync(callback: (ArCoreApk.Availability) -> Unit) { event("async"); availabilityCallback = callback }
            override fun requestInstall(userRequested: Boolean) = error("unused install")
            override fun consumeForcedCameraFallback() = error("unused forced fallback")
            override fun createSession(): Any { event("create"); return session }
            override fun createProvider(session: Any) = error("unused provider create")
            override fun configureDepth(provider: Any) = error("unused configure")
            override fun closeSession(session: Any) { assertSame(this@Fixture.session, session); event("close") }
            override fun closeProvider(provider: Any) = error("unused provider close")
            override fun pauseSession(session: Any) = error("unused session pause")
            override fun resumeBeforePublish(session: Any): Boolean { assertSame(this@Fixture.session, session); event("native-resume"); return true }
        }
        val coordinator = RuntimeMetricPreflightCoordinator(lock, { metric }, { native }, platform,
            RuntimeMetricSupportProbe(platform) { assertSame(session, it); event("probe"); supported },
            RuntimeMetricPreflightFrameEvidence<String> { frame, snapshot, at, generation, stamp ->
                assertSame(depth, snapshot); assertFalse(Thread.holdsLock(lock)); frameReads++; frameFailure?.let { throw it }; seenFrame = frame; seenCameraStamp = stamp
                RuntimeMetricFrameEvidence(generation, 1_000_000_000L, at, true, true, 20)
            }, object : RuntimeMetricPreflightAdmission {
                override fun cameraPermission(): Boolean { event("permission"); return permission }
                override fun metricDistanceAllowed(): Boolean? { event("metric-allowed"); return metricAllowed }
                override fun profile(): RuntimeMetricPreflightProfile? { event("profile"); return RuntimeMetricPreflightPolicy.testCandidateProfile }
            }, object : RuntimeMetricPreflightRenderer {
                override fun pauseForClose(): Boolean { event("renderer-pause"); return true }
                override fun resumeAfterClose(resume: Boolean) { event("renderer-resume:$resume") }
                override fun textureBound(bound: Boolean) { evaluatorAtTexture = metric.preflightEvaluator; onTexture(bound); event("texture") }
                override fun publishSupported() { event("supported") }
            }, object : RuntimeMetricPreflightPresentation {
                override fun availabilityPending() { event("pending") }
                override fun onUi(action: () -> Unit) { ui += action }
                override fun status(title: String, detail: String) { assertEquals("미터 거리 기능 확인 중", title); event("status") }
                override fun refreshUi() { event("refresh") }
                override fun postExpiry(action: () -> Unit, delayMs: Long) { expiry = action; expiryDelay = delayMs; event("expiry") }
            }, object : RuntimeMetricLossLifecycle {
                override fun stopDepth() { event("stop-depth") }
                override fun requestFallback(availability: ArCoreApk.Availability) { event("fallback:${availability.name}") }
            }, Purpose.PREFLIGHT, Purpose.RUNTIME, { clockReads++; event("clock"); now }, debug,
            { message -> event("diagnostic"); diagnostics += message })
        fun prepare() { metric.beginStoredDepthRefresh(binding); events.clear(); diagnostics.clear() }
    }
    companion object {
        private inline fun <reified T> proxy(noinline handle: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, a -> handle(m.name, a) } as T
    }
}
