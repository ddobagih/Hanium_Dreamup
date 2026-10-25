package kr.co.hanium.dreamup.walksafe.runtime.metric

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.delivery.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.ar.RuntimeArSessionOwner
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.output.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Actual metric, native-close, lifecycle, speech and risk owners; platform effects are bounded ports. */
class RuntimeMetricInvalidationControllerTest {
    @Test fun constructorReadsNoLiveOwnerClockOrPlatformFact() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.clockReads)
        assertEquals(0, f.lifecycleReads)
        assertNull(f.native.observe().session)
    }

    @Test fun actualMetricCommitOccursBetweenNativeRetentionAndDetachUnderSameLock() {
        val f = Fixture(); f.publish(Purpose.RUNTIME); f.metric.restoreCapability(true)
        val generation = f.native.observe().generation
        val binding = f.binding
        f.onFallbackRead = {
            assertTrue(Thread.holdsLock(f.lock))
            assertTrue(f.native.cleanupPending())
            assertSame(f.session, f.native.observe().session)
            assertEquals(0L, f.metric.preflightGeneration)
            // Binding changes after the original pre-lock capture must invalidate retention.
            f.checkSnapshot = PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL, "other", 2L, 2L)
        }
        f.controller.invalidate("app_paused")
        assertEquals(1L, f.metric.preflightGeneration)
        assertEquals(generation + 1L, f.native.observe().generation)
        assertNull(f.native.observe().session)
        assertNull(f.metric.capabilityOverride)
        assertEquals(0, f.lifecycleReads)
        assertEquals(listOf("provider-close", "feedback-generation", "startup-clear", "fallback-pending",
            "speech", "risk", "availability", "recognition", "privacy", "navigation", "route", "frame",
            "report:runtime_metric_unavailable", "fallback-stop", "location", "steps", "orientation", "risk",
            "talkback", "terminal", "speech", "record:app_paused", "renderer-pause", "session-pause",
            "session-close", "native-presentation", "renderer-resume:true", "startup-refresh"), f.effects())
        assertSame(binding, f.capturedBinding)
    }

    @Test fun activeRuntimeFailureRequestsSafetyStopBeforeCancellationAndKeepsOriginalTail() {
        val f = Fixture(active = true); f.publish(Purpose.RUNTIME)
        f.controller.invalidate("runtime_frame_failed")
        assertEquals(1, f.lifecycleReads)
        assertTrue(f.events.indexOf("safety-stop") < f.events.indexOf("feedback-generation"))
        assertTrue(f.events.indexOf("fallback-created") < f.events.indexOf("surface-pause"))
        assertTrue(f.events.indexOf("surface-pause") < f.events.indexOf("renderer-pause"))
        assertEquals(WalkSessionState.SAFE_STOP, f.lifecycle.snapshot().state)
        assertTrue(f.events.contains("interruption"))
    }

    @Test fun nonActiveRuntimeFailureDoesNotRequestStopOrPauseSafetySurface() {
        val f = Fixture(); f.publish(Purpose.RUNTIME)
        f.controller.invalidate("runtime_unavailable")
        assertEquals(1, f.lifecycleReads)
        assertFalse(f.events.contains("safety-stop"))
        assertFalse(f.events.contains("surface-pause"))
        assertFalse(f.events.contains("fallback-created"))
    }

    @Test fun preflightUsesCapturedEvaluatorClockOnceWithoutRetiringRuntimeOutputs() {
        val f = Fixture(); f.publish(Purpose.PREFLIGHT)
        val evaluator = RuntimeMetricPreflightSession(7L, 100L, RuntimeMetricDepthSupport.SUPPORTED,
            RuntimeMetricPreflightPolicy.testCandidateProfile)
        f.metric.publishPreflightEvaluator(evaluator, f.native.observe().generation)
        f.controller.invalidate("app_paused")
        assertNull(f.metric.preflightEvaluator)
        assertEquals(1, f.clockReads)
        assertEquals(RuntimeMetricPreflightReason.LIFECYCLE_CANCELLED, evaluator.result().reason)
        assertEquals(1_000L, evaluator.result().completedAtElapsedRealtimeMs)
        assertFalse(f.events.contains("feedback-generation"))
        assertFalse(f.events.any { it.startsWith("record:") })
        assertEquals(1, f.sessionCloses)
        assertEquals(1, f.providerCloses)
    }

    @Test fun currentAvailableEvidenceIsReadAfterFallbackAtOriginalTransactionPoint() {
        val f = Fixture()
        f.onFallbackRead = {
            assertTrue(Thread.holdsLock(f.lock))
            f.metric.restoreCapability(true)
        }
        f.controller.invalidate("app_paused")
        assertTrue(f.events.contains("feedback-generation"))
        // The pre-lock previous capability was null, so retention must not invent success.
        assertNull(f.metric.capabilityOverride)
        assertEquals(0, f.sessionCloses)
    }

    @Test fun missingLifecycleCannotTurnARuntimeReasonIntoASafetyStop() {
        val f = Fixture(active = true); f.lifecycleAvailable = false; f.publish(Purpose.RUNTIME)
        f.controller.invalidate("runtime_failed")
        assertEquals(1, f.lifecycleReads)
        assertFalse(f.events.contains("safety-stop"))
        assertFalse(f.events.contains("surface-pause"))
        assertTrue(f.events.contains("feedback-generation"))
    }

    @Test fun retainedCurrentDepthApiSupportIsRestoredAtOriginalCleanupPoint() {
        val f = Fixture(); f.publish(Purpose.RUNTIME); f.metric.restoreCapability(true)
        f.controller.invalidate("app_paused")
        assertEquals(true, f.metric.capabilityOverride)
        assertTrue(f.events.indexOf("feedback-generation") < f.events.indexOf("device:getOnboardingMayEnterWalk"))
        assertTrue(f.events.indexOf("device:getOnboardingMayEnterWalk") < f.events.indexOf("startup-clear"))
        assertEquals(0, f.clockReads)
    }

    @Test fun fallbackAndAvailableEvidenceEachRetireOutputsWithoutAnOwnedArSession() {
        listOf(true to false, false to true).forEach { (fallback, available) ->
            val f = Fixture(); f.fallbackActive = fallback
            f.metric.restoreCapability(available.takeIf { it })
            f.controller.invalidate("app_paused")
            assertTrue(f.events.contains("feedback-generation"))
            assertTrue(f.events.contains("location"))
            assertFalse(f.events.contains("renderer-pause"))
            assertEquals(0, f.sessionCloses)
            assertEquals(0, f.providerCloses)
            assertEquals(0, f.clockReads)
        }
    }

    @Test fun unrelatedPurposeDoesNotDetachCloseOrInventOutputRetirement() {
        val f = Fixture(); f.publish(Purpose.OTHER)
        val generation = f.native.observe().generation
        f.controller.invalidate("app_paused")
        assertSame(f.session, f.native.observe().session)
        assertEquals(generation, f.native.observe().generation)
        assertEquals(1L, f.metric.preflightGeneration)
        assertFalse(f.events.contains("feedback-generation"))
        assertEquals(0, f.sessionCloses)
        assertEquals(0, f.providerCloses)
        assertTrue(f.events.contains("fallback-continuation"))
        assertTrue(f.events.contains("startup-refresh"))
    }

    @Test fun deniedCloseRetentionKeepsNativeIdentityWhileMetricReceiptIsInvalidated() {
        val f = Fixture(); f.publish(Purpose.RUNTIME)
        val outsider = Any()
        assertTrue(f.cleanup.retain(outsider) {})
        val generation = f.native.observe().generation
        f.controller.invalidate("app_paused")
        assertSame(f.session, f.native.observe().session)
        assertEquals(generation, f.native.observe().generation)
        assertEquals(1L, f.metric.preflightGeneration)
        assertEquals(0, f.sessionCloses)
        assertEquals(0, f.providerCloses)
        assertFalse(f.events.contains("renderer-pause"))
        assertFalse(f.events.contains("native-presentation"))
        assertTrue(f.native.cleanupPending())
    }

    @Test fun failedNativeCloseRetainsSameReceiptAndIsNeverReplayed() {
        val f = Fixture(); f.publish(Purpose.RUNTIME)
        val failure = IllegalStateException("native close")
        f.failures["session-close"] = failure
        f.controller.invalidate("app_paused")
        assertEquals(1, f.sessionCloses)
        assertSame(failure, f.native.cleanupFailureOrNull())
        assertTrue(f.native.cleanupPending())
        assertTrue(f.events.indexOf("session-close") < f.events.indexOf("startup-refresh"))
        assertTrue(f.events.contains("native-presentation"))
        f.controller.invalidate("app_paused")
        assertEquals(1, f.sessionCloses)
        assertSame(failure, f.native.cleanupFailureOrNull())
        assertTrue(f.native.cleanupPending())
    }

    @Test fun providerFailureStillRunsIndependentTailButPreventsUncertainNativeClose() {
        val f = Fixture(); f.publish(Purpose.RUNTIME)
        val providerFailure = IllegalStateException("provider")
        val navigationFailure = IllegalArgumentException("navigation")
        val refreshFailure = IllegalStateException("ui")
        f.failures["provider-close"] = providerFailure
        f.failures["navigation"] = navigationFailure
        f.failures["startup-refresh"] = refreshFailure
        f.controller.invalidate("app_paused")
        assertEquals(0, f.sessionCloses)
        assertEquals(1, f.sessionPauses)
        assertTrue(f.events.contains("steps"))
        assertTrue(f.events.contains("native-presentation"))
        assertSame(providerFailure, f.native.cleanupFailureOrNull())
        assertTrue(providerFailure.suppressed.any { it === navigationFailure })
        assertTrue(providerFailure.suppressed.any { it === refreshFailure })
        assertTrue(f.native.cleanupPending())
    }

    @Test fun safetyTransitionFailureSkipsItsMarkerButContinuesOtherStages() {
        val f = Fixture(active = true); f.publish(Purpose.RUNTIME)
        val failure = IllegalStateException("transition")
        f.failures["safety-stop"] = failure
        f.controller.invalidate("runtime_failed")
        assertFalse(f.events.contains("interruption"))
        assertTrue(f.events.contains("feedback-generation"))
        assertTrue(f.events.contains("surface-pause"))
        assertSame(failure, f.native.cleanupFailureOrNull())
    }

    @Test fun sessionPauseThrowableIsSwallowedBeforeExactlyOneClose() {
        val f = Fixture(); f.publish(Purpose.RUNTIME)
        f.failures["session-pause"] = AssertionError("pause")
        f.controller.invalidate("app_paused")
        assertEquals(1, f.sessionPauses)
        assertEquals(1, f.sessionCloses)
        assertFalse(f.native.cleanupPending())
    }

    @Test fun talkBackCancellationReceivesExactDetachedRunnableFromActualSpeechOwner() {
        val f = Fixture(); f.publish(Purpose.RUNTIME)
        val task = Runnable {}
        SpeechOutputController::class.java.getDeclaredField("pendingTalkBackInteraction").apply {
            isAccessible = true; set(f.speech, task)
        }
        f.controller.invalidate("app_paused")
        assertSame(task, f.removedTalkBack)
        assertNull(f.speech.detachPendingTalkBackInteraction())
    }

    @Test fun metricRetirementNotifiesTheAutomaticSourceOnlyAfterActualDetachOutsideLock() {
        val f = Fixture(); f.publish(Purpose.RUNTIME)
        val generation = f.native.observe().generation
        f.onSourceChanged = {
            assertFalse(Thread.holdsLock(f.lock))
            assertNull(f.native.observe().session)
            assertNull(f.native.observe().provider)
            assertEquals(generation + 1L, f.native.observe().generation)
            assertTrue(f.native.cleanupPending())
            f.events += "source_changed"
        }
        f.controller.invalidate("app_paused")
        assertTrue(f.events.indexOf("source_changed") < f.events.indexOf("provider-close"))
        assertEquals(1, f.sessionCloses)
    }

    @Test fun auxiliaryRetirementFailureCannotSkipMetricNativeCleanup() {
        val f = Fixture(); f.publish(Purpose.RUNTIME)
        val failure = IllegalStateException("auxiliary close")
        f.onSourceChanged = { assertNull(f.native.observe().session); throw failure }
        f.controller.invalidate("app_paused")
        assertEquals(1, f.providerCloses)
        assertTrue(f.events.contains("startup-refresh"))
        assertTrue(f.events.contains("native-presentation"))
        assertSame(failure, f.native.cleanupFailureOrNull())
        assertTrue(f.native.cleanupPending())
    }

    @Test fun cleanupAggregatorKeepsFirstIdentityAndDoesNotDuplicateSelfSuppression() {
        val first = IllegalStateException("first"); val second = IllegalArgumentException("second")
        var tail = false
        val result = runCatching { runMetricInvalidationCleanupStages(
            { throw first }, { throw first }, { throw second }, { tail = true }) }
        assertSame(first, result.exceptionOrNull())
        assertArrayEquals(arrayOf(second), first.suppressed)
        assertTrue(tail)
    }

    @Test fun cleanupAggregatorDoesNotConvertErrorsIntoRecoverableRuntimeFailures() {
        val error = AssertionError("fatal"); var tail = false
        assertSame(error, runCatching { runMetricInvalidationCleanupStages(
            { throw error }, { tail = true }) }.exceptionOrNull())
        assertFalse(tail)
    }

    private enum class Purpose { NONE, PREFLIGHT, RUNTIME, OTHER }
    private class Fixture(active: Boolean = false) {
        var onSourceChanged: () -> Unit = {}
        val lock = Any(); val cleanup = RuntimeArSessionCleanupOwnership()
        val session = Any(); val provider = Any(); val binding = PostLoginDeviceCheckBinding("actor", 1L, 1L)
        var checkSnapshot = PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL, "actor", 1L, 1L)
        var capturedBinding: PostLoginDeviceCheckBinding? = null
        val events = mutableListOf<String>(); val failures = mutableMapOf<String, Throwable>()
        var lifecycleAvailable = true
        var clockReads = 0; var lifecycleReads = 0; var sessionCloses = 0; var providerCloses = 0; var sessionPauses = 0
        var fallbackActive = false; var onFallbackRead: () -> Unit = {}; var removedTalkBack: Runnable? = null
        fun effect(name: String) { events += name; failures[name]?.let { throw it } }
        fun effects() = events.filter { !it.startsWith("device:") && it !in setOf("binding", "fallback-facts", "fallback-continuation") }
        val lifecycle = WalkSessionLifecycle({ "invalidation-private" }, { 1_000L }, { 1_000L }).apply {
            handle(WalkSessionEvent.EnteredForeground)
            if (active) {
                val collector = WalkSessionReadinessCollector(snapshot().epoch,
                    WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
                collector.plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(
                    snapshot().epoch, it, WalkSessionReadinessStatus.READY)) }
                handle(WalkSessionEvent.InitialCheckCompleted(collector.build(1_000L)))
                handle(WalkSessionEvent.StartRequested(requireNotNull(snapshot().confirmationToken)))
            }
        }
        val native = RuntimeArSessionOwner<Any, Any, Purpose>(lock, Purpose.NONE,
            { assertFalse(Thread.holdsLock(lock)); sessionCloses++; effect("session-close") },
            { providerCloses++; effect("provider-close") }, cleanup)
        fun publish(purpose: Purpose) { native.publish(session, provider, purpose) }
        val metric = RuntimeMetricSessionController<Any, Purpose, Any>(lock,
            proxy<RuntimeMetricScope> { name, _ -> when(name) {
                "getOnboardingComplete" -> true
                else -> error("Unexpected metric scope $name")
            } },
            proxy<RuntimeMetricNativePort<Purpose, Any>> { name, _ -> error("Unexpected metric native $name") },
            proxy<RuntimeMetricDeviceChecks<Any>> { name, _ ->
                events += "device:$name"
                when(name) {
                    "getOnboardingMayEnterWalk" -> true
                    "getSnapshot" -> checkSnapshot
                    "bindingLive" -> true
                    "getDepthState" -> PostLoginMetricDepthState.SUPPORTED
                    "getCameraChecksDeferred", "getStoredRefreshPending" -> false
                    else -> error("Unexpected device $name")
                }
            },
            proxy<RuntimeMetricEffects> { name, _ -> when(name) {
                "getDebug" -> false
                else -> error("Unexpected metric effect $name")
            } }, Purpose.NONE, Purpose.PREFLIGHT, Purpose.RUNTIME, 1_500L, { error("Unexpected metric clock") })
        val speech = SpeechOutputController(proxy(), proxy(), proxy(), proxy(), proxy(), proxy(), proxy()) {}
        val risk = RiskFeedbackDeliveryController<Any>(WalkSafeFeedbackPolicy(), Any(), Any(),
            proxy(), proxy(), proxy(), proxy(), proxy())
        val controller = RuntimeMetricInvalidationController(lock, { metric }, { native },
            { lifecycleReads++; lifecycle.takeIf { lifecycleAvailable } }, { effect("speech"); speech }, { effect("risk"); risk },
            object : RuntimeMetricInvalidationStartup {
                override fun currentBinding(): PostLoginDeviceCheckBinding { effect("binding"); capturedBinding = binding; return binding }
                override fun clearConfirmation() { effect("startup-clear") }
                override fun invalidateAvailabilityRecheck() { effect("availability") }
                override fun refreshPublishedForegroundUi() { effect("startup-refresh") }
            },
            object : RuntimeMetricInvalidationFallback {
                override fun hasActivity(): Boolean { effect("fallback-facts"); onFallbackRead(); return fallbackActive }
                override fun clearContinuationIfInitialized() { effect("fallback-continuation") }
                override fun clearPendingStart() { effect("fallback-pending") }
                override fun stop() { effect("fallback-stop") }
                override fun moveToCreatedIfInitialized() { effect("fallback-created") }
            },
            object : RuntimeMetricInvalidationNavigation {
                override fun safetyStopAndPersist() { effect("safety-stop"); lifecycle.handle(WalkSessionEvent.SafetyStopRequested); effect("interruption") }
                override fun cancelAndClearRequests() { effect("navigation") }
                override fun resetRoute() { effect("route") }
                override fun invalidateFrame() { effect("frame") }
                override fun stopLocation() { effect("location") }
                override fun stopSteps() { effect("steps") }
                override fun stopOrientationIfInitialized() { effect("orientation") }
            },
            object : RuntimeMetricInvalidationVoice {
                override fun advanceFeedbackGeneration() { effect("feedback-generation") }
                override fun cancelRecognition() { effect("recognition") }
                override fun removeTalkBackIfPublished(task: Runnable?) { removedTalkBack = task; effect("talkback") }
                override fun cancelTerminalResolution() { effect("terminal") }
            },
            object : RuntimeMetricInvalidationReports {
                override fun cancelPrivacyCalls() { effect("privacy") }
                override fun clearExplicitFrame(reason: String) { effect("report:$reason") }
                override fun recordInvalidationIfInitialized(reason: String, wasPreflight: Boolean) { if (!wasPreflight) effect("record:$reason") }
            },
            object : RuntimeMetricInvalidationRenderer {
                override fun pauseForSessionClose(): Boolean { effect("renderer-pause"); return true }
                override fun resumeAfterSessionClose(resume: Boolean) { effect("renderer-resume:$resume") }
                override fun clearNativePresentation() { effect("native-presentation") }
                override fun pauseForSafetyStopIfInitialized() { effect("surface-pause") }
            }, Purpose.PREFLIGHT, Purpose.RUNTIME,
            { assertSame(provider, it); assertFalse(Thread.holdsLock(lock)); assertNull(native.observe().session)
                assertEquals(1L, metric.preflightGeneration); providerCloses++; effect("provider-close") },
            { assertSame(session, it); sessionPauses++; effect("session-pause") },
            { clockReads++; 1_000L }, false, { error("Unexpected debug") }, sourceChanged = { onSourceChanged() })
    }

    companion object {
        private inline fun <reified T> proxy(noinline handle: (String, Array<out Any?>?) -> Any? =
            { name, _ -> error("Unexpected collaborator query $name") }): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                handle(method.name, args)
            } as T
    }
}
