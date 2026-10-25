package kr.co.hanium.dreamup.walksafe.runtime.metric

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.depth.DepthAcquisitionFailureReason
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual preflight/frame policies and lifecycle; native close, frame acquisition and UI are ports. */
class RuntimeMetricSessionControllerTest {
    @Test fun constructorDoesNotReadRuntimeOrNativeFacts() {
        val f = Fixture()
        assertEquals(0, f.reads)
        assertTrue(f.events.isEmpty())
        assertFalse(f.controller.outputAllowed)
        assertNull(f.controller.activationEvaluator)
    }
    @Test fun validActivationStartsNavigationOnceAfterCurrentUiDelivery() {
        val f = Fixture(active = true)
        f.activate()
        f.frame(1_000L)
        assertTrue(f.controller.outputAllowed)
        assertNull(f.controller.activationEvaluator)
        assertEquals(1_000L, f.controller.lastValidFrameAtMs)
        assertEquals(1, f.ui.size)
        f.ui.removeAt(0).invoke()
        assertEquals(1, f.navigationStarts)
        assertFalse(f.controller.initialNavigationStartPending)
        f.frame(1_100L)
        assertTrue(f.ui.isEmpty())
        assertEquals(1, f.navigationStarts)
    }
    @Test fun retiredGenerationCancelsQueuedInitialNavigation() {
        val f = Fixture(active = true); f.activate(); f.frame(1_000L)
        f.nativeGeneration++
        f.ui.removeAt(0).invoke()
        assertEquals(0, f.navigationStarts)
        assertTrue(f.controller.initialNavigationStartPending)
    }
    @Test fun staleAdmissionDoesNotObserveFrameAndReplacementEvaluatorCannotBeCompleted() {
        val f = Fixture(active = true); f.activate()
        f.foreground = false
        var observations = 0
        f.controller.updateRuntimeMetricFrameEvidence(f.nativeGeneration, f.epoch,
            observeFrameEvidence = { observations++; f.evidence(1_000L) })
        assertEquals(0, observations)
        f.foreground = true
        val original = f.controller.activationEvaluator
        f.controller.updateRuntimeMetricFrameEvidence(f.nativeGeneration, f.epoch,
            observeFrameEvidence = {
                observations++
                f.controller.beginRuntimeMetricActivation(f.nativeGeneration, 1_001L)
                f.evidence(1_000L)
            })
        assertNotSame(original, f.controller.activationEvaluator)
        assertEquals(1_001L, f.controller.activationEvaluator?.startedAtElapsedRealtimeMs)
        assertFalse(f.controller.outputAllowed)
        assertTrue(f.ui.isEmpty())
    }
    @Test fun ordinaryGapPreservesOwnedCameraAndOriginalValidClockButFatalFailureDoesNot() {
        val f = Fixture(active = true); f.activate(); f.frame(1_000L); f.ui.removeAt(0).invoke()
        val lease = f.nativeObservation()
        f.now = 1_100L; f.frame(1_100L, depth = false)
        assertFalse(f.controller.outputAllowed)
        assertEquals(1_000L, f.controller.lastValidFrameAtMs)
        assertTrue(f.controller.currentRuntimeVisualDepthProcessingAllowsWork(lease, 1_100_000_000L, true))
        f.now = 3_001L; f.frame(3_001L, depth = false)
        assertFalse(f.controller.currentRuntimeVisualDepthProcessingAllowsWork(lease, 3_001_000_000L, true))
        assertEquals(1_000L, f.controller.lastValidFrameAtMs)
        assertEquals(0, f.nativeDetaches)
        assertTrue(f.ui.isEmpty())
        assertTrue(f.events.contains("clear:runtime_metric_waiting_for_depth"))
        f.frame(3_002L, failure = DepthAcquisitionFailureReason.ILLEGAL_STATE)
        assertEquals(1, f.ui.size)
        f.ui.removeAt(0).invoke()
        assertEquals(listOf(f.nativeGeneration), f.losses)
        assertEquals(1_000L, f.controller.lastValidFrameAtMs)
    }
    @Test fun visualGapRequiresTheSameNativeReferencesAndCurrentCameraGates() {
        val f = Fixture(active = true); f.activate(); f.frame(1_000L); f.ui.removeAt(0).invoke()
        val lease = f.nativeObservation()
        f.now = 1_100L; f.frame(1_100L, depth = false)
        f.provider = Any()
        assertFalse(f.controller.currentRuntimeVisualDepthProcessingAllowsWork(lease, 1_100_000_000L, true))
        f.cameraAllowed = false
        assertFalse(f.controller.currentRuntimeVisualDepthProcessingAllowsWork(f.nativeObservation(), 1_100_000_000L, true))
        assertFalse(f.controller.currentRuntimeMetricOutputAllowsWork())
    }
    @Test fun permissionContinuationConsumesOnlyItsOriginalPreflightOwner() {
        val f = Fixture(); f.permission = false
        f.controller.beginRuntimeMetricPreflight()
        assertEquals(1, f.permissionRequests)
        assertEquals(1L, f.controller.preflightGeneration)
        f.feedbackGeneration++
        f.permission = true
        f.controller.handleMetricPreflightCameraPermissionResult()
        f.controller.handleMetricPreflightCameraPermissionResult()
        assertTrue(f.nativeStarts.isEmpty())
        assertEquals(RuntimeMetricPreflightOwner.DEVICE_CHECK, f.controller.preflightOwner)
    }
    @Test fun grantedPermissionStartsItsCapturedAttemptOnceAndRevokedLeaseOrBindingNeverStarts() {
        for (change in listOf("none", "lease", "binding", "cancel")) {
            val f = Fixture(); f.permission = false
            f.controller.beginRuntimeMetricPreflight()
            val generation = f.controller.preflightGeneration
            when (change) {
                "lease" -> f.firstRunLease = Any()
                "binding" -> f.controller.clearPreflightBinding()
                "cancel" -> f.controller.invalidateEvidence()
            }
            f.permission = true
            f.controller.handleMetricPreflightCameraPermissionResult()
            f.controller.handleMetricPreflightCameraPermissionResult()
            assertEquals(change, if (change == "none") listOf(generation to 1) else emptyList<Pair<Long, Int>>(),
                f.nativeStarts)
            assertEquals(1, f.permissionRequests)
        }
    }
    @Test fun missingFirstRunLeaseRetainsTheExistingPermissionFlowButCannotAuthorizeNativeWork() {
        val f = Fixture(); f.permission = false; f.firstRunLease = null
        f.controller.beginRuntimeMetricPreflight()
        assertEquals(1, f.permissionRequests)
        assertEquals(RuntimeMetricPreflightOwner.DEVICE_CHECK, f.controller.preflightOwner)
        assertFalse(f.controller.isRuntimeMetricPreflightAttemptCurrent(f.controller.preflightGeneration, 1))
        f.permission = true
        f.controller.handleMetricPreflightCameraPermissionResult()
        assertTrue(f.nativeStarts.isEmpty())
    }
    @Test fun storedRefreshUsesItsBindingWithoutAFirstRunLeaseAndRetiresBeforeSaving() {
        val f = Fixture(); f.firstRunLease = null; f.storedRefreshCurrent = true
        f.controller.beginStoredDepthRefresh(f.binding)
        assertEquals(RuntimeMetricPreflightOwner.STORED_DEPTH_REFRESH, f.controller.preflightOwner)
        assertEquals(listOf(1L to 1), f.nativeStarts)
        assertEquals(0, f.permissionRequests)
        assertTrue(f.controller.isRuntimeMetricPreflightAttemptCurrent(1L, 1))
        f.publishPreflight()
        f.controller.observePreflightFrame(f.nativeGeneration, f.epoch) { f.evidence(1_000L, generation = 1L) }
        f.ui.removeAt(0).invoke()
        assertEquals(listOf(true to f.binding), f.storedRefreshes)
        assertEquals(1, f.persisted.size)
        assertEquals(RuntimeMetricPreflightOwner.NONE, f.controller.preflightOwner)
        assertNull(f.controller.preflightBinding)
        assertTrue(f.events.indexOf("finish-native") < f.events.indexOf("stored-refresh:NONE"))
        assertFalse(f.events.any { it.startsWith("continue:") })
    }
    @Test fun replacingPermissionWaitWithStoredRefreshCannotConsumeAnOldPermissionOrFrameTerminal() {
        val f = Fixture(); f.permission = false
        f.controller.beginRuntimeMetricPreflight()
        f.storedRefreshCurrent = true
        f.controller.beginStoredDepthRefresh(f.binding)
        val generation = f.controller.preflightGeneration
        f.permission = true
        f.controller.handleMetricPreflightCameraPermissionResult()
        assertEquals(listOf(generation to 1), f.nativeStarts)
        f.publishPreflight()
        f.controller.observePreflightFrame(f.nativeGeneration, f.epoch) { f.evidence(1_000L, generation = generation) }
        val retiredTerminal = f.ui.removeAt(0)
        f.controller.invalidateEvidence(); f.purpose = Purpose.NONE; f.session = null; f.provider = null
        f.controller.beginRuntimeMetricPreflight(); f.publishPreflight()
        val replacement = f.controller.preflightEvaluator
        retiredTerminal()
        assertSame(replacement, f.controller.preflightEvaluator)
        assertEquals(0, f.nativeDetaches)
        assertTrue(f.storedRefreshes.isEmpty())
        assertTrue(f.persisted.isEmpty())
    }
    @Test fun bothOwnersRetireTheirSupportOnlyResultAndKeepTheOriginalFeedbackGeneration() {
        for (stored in listOf(false, true)) {
            val f = Fixture(); f.storedRefreshCurrent = stored
            if (stored) f.controller.beginStoredDepthRefresh(f.binding) else f.controller.beginRuntimeMetricPreflight()
            val generation = f.controller.preflightGeneration
            f.controller.finishDeviceMetricDepthSupportCheck(generation, 1,
                RuntimeMetricDepthSupport.SUPPORTED, "test-support")
            assertEquals(generation + 1L, f.controller.preflightGeneration)
            assertEquals(1, f.controller.preflightLifecycleGeneration)
            assertNull(f.controller.preflightBinding)
            assertEquals(RuntimeMetricPreflightOwner.NONE, f.controller.preflightOwner)
            assertEquals(0, f.nativeDetaches)
            assertTrue("API support is not a measured frame result", f.persisted.isEmpty())
            val events = f.events.toList()
            f.controller.finishDeviceMetricDepthSupportCheck(generation, 1,
                RuntimeMetricDepthSupport.UNSUPPORTED, "late-support")
            assertEquals(events, f.events)
            if (stored) assertEquals(listOf(true to f.binding), f.storedRefreshes)
            else assertEquals(true, f.controller.capabilityOverride)
        }
    }
    @Test fun nativeFinishFailureCannotSaveEitherOwnersSuccessfulFrameResult() {
        for (stored in listOf(false, true)) {
            val f = Fixture(); f.storedRefreshCurrent = stored; f.throwOnFinishClose = true
            if (stored) f.controller.beginStoredDepthRefresh(f.binding) else f.controller.beginRuntimeMetricPreflight()
            f.publishPreflight()
            f.controller.observePreflightFrame(f.nativeGeneration, f.epoch) {
                f.evidence(1_000L, generation = f.controller.preflightGeneration)
            }
            assertSame(f.closeFailure, runCatching { f.ui.removeAt(0).invoke() }.exceptionOrNull())
            assertEquals(1, f.nativeDetaches)
            assertEquals(RuntimeMetricPreflightOwner.NONE, f.controller.preflightOwner)
            assertNull(f.controller.preflightEvaluator)
            assertTrue(f.persisted.isEmpty())
            assertTrue(f.storedRefreshes.isEmpty())
            assertNull(f.controller.capabilityOverride)
        }
    }
    @Test fun replacementWhileObservingOldFrameKeepsTheNewAttemptsTerminalIndependent() {
        val f = Fixture(); f.controller.beginRuntimeMetricPreflight(); f.publishPreflight()
        val generation = f.controller.preflightGeneration
        f.controller.observePreflightFrame(f.nativeGeneration, f.epoch) {
            f.controller.invalidateEvidence(); f.purpose = Purpose.NONE; f.session = null; f.provider = null
            f.nativeGeneration++
            f.storedRefreshCurrent = true
            f.controller.beginStoredDepthRefresh(f.binding); f.publishPreflight()
            f.evidence(1_000L, generation = generation)
        }
        val replacement = f.controller.preflightEvaluator
        f.controller.observePreflightFrame(f.nativeGeneration, f.epoch) {
            f.evidence(1_100L, generation = f.controller.preflightGeneration)
        }
        assertEquals(2, f.ui.size)
        f.ui.removeAt(0).invoke()
        assertSame(replacement, f.controller.preflightEvaluator)
        assertEquals(0, f.nativeDetaches)
        f.ui.removeAt(0).invoke()
        assertEquals(1, f.nativeDetaches)
        assertEquals(1, f.persisted.size)
        assertEquals(listOf(true to f.binding), f.storedRefreshes)
    }
    @Test fun actualPreflightFrameTerminalIsPublishedOnceAndRetiresBeforeDeviceContinuation() {
        val f = Fixture(); f.controller.beginRuntimeMetricPreflight(); f.publishPreflight()
        val generation = f.controller.preflightGeneration
        f.controller.observePreflightFrame(f.nativeGeneration, f.epoch) { f.evidence(1_000L, generation = generation) }
        f.controller.observePreflightFrame(f.nativeGeneration, f.epoch) { f.evidence(1_001L, generation = generation) }
        assertEquals(1, f.ui.size)
        f.ui.removeAt(0).invoke()
        assertEquals(1, f.nativeDetaches)
        assertEquals(1, f.persisted.size)
        assertEquals(RuntimeMetricPreflightStatus.AVAILABLE, f.persisted.single().status)
        assertEquals(RuntimeMetricPreflightOwner.NONE, f.controller.preflightOwner)
        assertNull(f.controller.preflightEvaluator)
        assertTrue(f.controller.capabilityOverride == true)
        assertTrue(f.events.indexOf("detach") < f.events.indexOf("finish-native"))
        assertTrue(f.events.indexOf("finish-native") < f.events.indexOf("continue:NONE"))
    }
    @Test fun oldTerminalCannotDetachANewPreflightAndProviderCloseFailureDoesNotCommitSuccess() {
        val f = Fixture(); f.controller.beginRuntimeMetricPreflight(); f.publishPreflight()
        f.controller.observePreflightFrame(f.nativeGeneration, f.epoch) { f.evidence(1_000L, generation = f.controller.preflightGeneration) }
        f.controller.invalidateEvidence(); f.purpose = Purpose.NONE; f.session = null; f.provider = null
        f.controller.beginRuntimeMetricPreflight(); f.publishPreflight()
        val fresh = f.controller.preflightEvaluator
        f.ui.removeAt(0).invoke()
        assertSame(fresh, f.controller.preflightEvaluator)
        assertEquals(0, f.nativeDetaches)
        f.throwOnProviderClose = true
        f.controller.observePreflightFrame(f.nativeGeneration, f.epoch) { f.evidence(1_100L, generation = f.controller.preflightGeneration) }
        val result = runCatching { f.ui.removeAt(0).invoke() }
        assertTrue(result.exceptionOrNull() === f.closeFailure)
        assertNull(f.session)
        assertNotNull(f.provider)
        assertSame(fresh, f.controller.preflightEvaluator)
        assertTrue(f.persisted.isEmpty())
    }

    private enum class Purpose { NONE, PREFLIGHT, RUNTIME }
    private class Fixture(active: Boolean = false) {
        val lock = Any()
        var now = 1_000L; var reads = 0; var foreground = true; var feedbackGeneration = 1
        var permission = true; var cameraAllowed = true; var nativeGeneration = 5L
        var purpose = Purpose.NONE; var session: Any? = null; var provider: Any? = null
        var navigationStarts = 0; var permissionRequests = 0; var nativeDetaches = 0
        var throwOnProviderClose = false; var throwOnFinishClose = false
        val closeFailure = IllegalStateException("native close")
        val events = mutableListOf<String>(); val ui = mutableListOf<() -> Unit>()
        val nativeStarts = mutableListOf<Pair<Long, Int>>(); val losses = mutableListOf<Long>()
        val persisted = mutableListOf<RuntimeMetricPreflightResult>()
        var firstRunLease: Any? = Any()
        var storedRefreshCurrent = false
        val storedRefreshes = mutableListOf<Pair<Boolean?, PostLoginDeviceCheckBinding>>()
        val binding = PostLoginDeviceCheckBinding("actor", 1L, 1L)
        var checkSnapshot = PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.RUNNING, "actor", 1L, 1L)
        val lifecycle = WalkSessionLifecycle({ "metric-private" }, { now }, { now }).apply {
            handle(WalkSessionEvent.EnteredForeground)
            if (active) {
                val collector = WalkSessionReadinessCollector(snapshot().epoch,
                    WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
                collector.plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(
                    snapshot().epoch, it, WalkSessionReadinessStatus.READY)) }
                handle(WalkSessionEvent.InitialCheckCompleted(collector.build(now)))
                handle(WalkSessionEvent.StartRequested(requireNotNull(snapshot().confirmationToken)))
            }
        }
        val epoch get() = lifecycle.snapshot().epoch
        val controller: RuntimeMetricSessionController<Any, Purpose, Any> = RuntimeMetricSessionController(lock, object : RuntimeMetricScope {
            override val lifecycle get() = this@Fixture.lifecycle.also { reads++ }
            override val runtimeActive get() = lifecycle.currentRuntimeEpochOrNull() != null
            override val foreground get() = this@Fixture.foreground.also { reads++ }
            override val finishing = false
            override val destroyed = false
            override val onboardingComplete get() = true.also { reads++ }
            override val cameraOutputsAllowed get() = cameraAllowed
            override val mountingOutputsAllowed = true
            override val metricDistanceAllowed: Boolean? = true
            override val startupConfirmed = true
            override val profile get() = RuntimeMetricPreflightPolicy.testCandidateProfile.also { reads++ }
            override val feedbackGeneration get() = this@Fixture.feedbackGeneration
        }, object : RuntimeMetricNativePort<Purpose, Any> {
            override val purpose get() = this@Fixture.purpose.also { reads++ }
            override val generation get() = nativeGeneration
            override val sessionPresent get() = session != null
            override val providerPresent get() = provider != null
            override val cleanupPending = false
            override fun observe() = nativeObservation()
            override fun preflightDiagnostics() = "samples"
            override fun detachCompletedPreflight(): Any? {
                assertTrue("Detach and metric retirement use the original shared lock", Thread.holdsLock(lock))
                val old = session; session = null
                if (throwOnProviderClose) throw closeFailure
                provider = null; this@Fixture.purpose = Purpose.NONE; nativeGeneration++; nativeDetaches++; events += "detach"
                return old
            }
            override fun finishDetachedPreflight(session: Any?) {
                assertFalse("Native finish runs after the state transaction", Thread.holdsLock(lock))
                events += "finish-native"
                if (throwOnFinishClose) throw closeFailure
            }
        }, object : RuntimeMetricDeviceChecks<Any> {
            override val probeAvailable = true
            override val firstRunAllowsPreflight = true
            override val cameraResourceConflict = false
            override val fieldSessionActive = false
            override val snapshot get() = checkSnapshot
            override val onboardingMayEnterWalk = true
            override val depthState = PostLoginMetricDepthState.PENDING
            override val cameraChecksDeferred = false
            override val storedRefreshPending = false
            override fun startupInput() = WalkSafeStartupCapabilityInput(true, true, true, true, true, true, true, null, null, null)
            override fun bindingCurrent(binding: PostLoginDeviceCheckBinding) = binding == this@Fixture.binding
            override fun bindingLive(binding: PostLoginDeviceCheckBinding) = binding == checkSnapshot.bindingOrNull
            override fun firstRunLease() = firstRunLease
            override fun firstRunLeaseCurrent(lease: Any) = lease === firstRunLease
            override fun storedRefreshCurrent(binding: PostLoginDeviceCheckBinding) =
                storedRefreshCurrent && binding == this@Fixture.binding
            override fun setPreflightStarted(started: Boolean) { events += "started:$started" }
            override fun restoreDepth(state: PostLoginMetricDepthState) { events += "depth:$state" }
            override fun continueDeviceCheck(binding: PostLoginDeviceCheckBinding) { events += "continue:${controller.preflightOwner}" }
            override fun completeStoredRefresh(available: Boolean?, binding: PostLoginDeviceCheckBinding): Boolean {
                storedRefreshes += available to binding
                events += "stored-refresh:${controller.preflightOwner}"
                return storedRefreshCurrent
            }
            override fun profileMatch() = ApprovedDeviceProfileMatch(false, null, null, ApprovedDeviceProfileMatchReason.NO_EXACT_MATCH)
            override fun persistResult(result: RuntimeMetricPreflightResult, profileMatch: ApprovedDeviceProfileMatch) { persisted += result }
        }, object : RuntimeMetricEffects {
            override val cameraPermission get() = permission
            override val explainCameraPermission = true
            override val debug = false
            override fun clearStartupConfirmation() { events += "clear-confirmation" }
            override fun refreshStartupUi() { events += "refresh" }
            override fun requestCameraPermission() { permissionRequests++ }
            override fun startNativePreflight(generation: Long, lifecycleGeneration: Int) { nativeStarts += generation to lifecycleGeneration }
            override fun status(status: String, detail: String) { events += "status:$status" }
            override fun postToUi(action: () -> Unit) { ui += action }
            override fun startNavigationServices() { navigationStarts++ }
            override fun cameraFrameUnavailable(epoch: WalkRuntimeEpoch, observedAtMs: Long) { events += "camera-unavailable:$observedAtMs" }
            override fun runtimeMetricLost(expectedArGeneration: Long) { losses += expectedArGeneration }
            override fun clearReportFrame(reason: String) { events += "clear:$reason" }
            override fun debug(tag: String, message: String) = Unit
            override fun info(tag: String, message: String) = Unit
        }, Purpose.NONE, Purpose.PREFLIGHT, Purpose.RUNTIME, 2_000L, { now })
        fun nativeObservation() = RuntimeMetricArObservation(session, provider, purpose, nativeGeneration)
        fun activate() { purpose = Purpose.RUNTIME; session = Any(); provider = Any(); controller.beginRuntimeMetricActivation(nativeGeneration, now) }
        fun publishPreflight() {
            purpose = Purpose.PREFLIGHT; session = Any(); provider = Any()
            controller.publishPreflightEvaluator(RuntimeMetricPreflightSession(controller.preflightGeneration, now,
                RuntimeMetricDepthSupport.SUPPORTED, RuntimeMetricPreflightPolicy.testCandidateProfile), nativeGeneration)
        }
        fun evidence(at: Long, depth: Boolean = true, generation: Long = nativeGeneration) =
            RuntimeMetricFrameEvidence(generation, at * 1_000_000L, at, true, depth, if (depth) 1 else 0)
        fun frame(at: Long, depth: Boolean = true, failure: DepthAcquisitionFailureReason? = null) {
            controller.updateRuntimeMetricFrameEvidence(nativeGeneration, epoch, failure,
                observeFrameEvidence = { evidence(at, depth) })
        }
    }
}
