package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.ConfidenceImage8
import kr.co.hanium.dreamup.walksafe.depth.DepthAgeBudget
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DepthImage16
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackSpeechRuntime
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackSpeechScheduler
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.feedback.ProgressToneEffects
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.navigation.LocalRouteMode
import kr.co.hanium.dreamup.walksafe.navigation.TactileFrameFeedbackDispatch
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import kr.co.hanium.dreamup.walksafe.navigation.TactileRouteDecision
import kr.co.hanium.dreamup.walksafe.navigation.TactileRouteGuidanceResult
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Main/coordinator/policy/actuator; only native effects, clock and callback scheduling vary. */
class MainActivitySpeechReadyRiskRegressionTest {
    @Test
    fun readyCallbackReevaluatesCurrentMetricCautionThroughMainPolicyAndNativeActuator() {
        assertCurrentRiskReevaluated(qualitativeMode = false)
    }

    @Test
    fun readyCallbackReevaluatesCurrentQualitativeCautionThroughMainPolicyAndNativeActuator() {
        assertCurrentRiskReevaluated(qualitativeMode = true)
    }

    @Test
    fun expiredStaleGateBlockedAndRetiredProducerInputsCannotReplayOnReady() {
        for (change in listOf("expired", "stale", "gate", "producer")) {
            val fixture = Fixture()
            fixture.rejectInitialRisk()
            when (change) {
                "expired" -> fixture.runtime.advanceBy(DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS + 1L)
                "stale" -> fixture.remember(stale = true)
                "gate" -> fixture.gateAllowsAlerts = false
                "producer" -> fixture.producerCurrent = false
            }
            fixture.runtime.ready()

            fixture.assertReadyCallbackHandled()
            assertTrue(change, fixture.runtime.spoken.isEmpty())
            assertEquals(change, 1, fixture.actions.size)
            assertEquals(1, fixture.rejected)
            assertEquals(0, fixture.completed)
            assertEquals(0, fixture.runtime.focusRequests)
            assertFalse(fixture.actuator.isAppSpeechActive())
        }
    }

    @Test
    fun latestEmptyFrameAndExplicitClearCannotRestoreThePreviouslyRejectedRisk() {
        for (clear in listOf(false, true)) {
            val fixture = Fixture()
            fixture.rejectInitialRisk()
            if (clear) fixture.main.clearSpeechReadyRiskInput()
            else fixture.remember(frame = frame(emptyList()))
            fixture.runtime.advanceBy(100L)
            fixture.runtime.ready()

            fixture.assertReadyCallbackHandled()
            assertTrue(fixture.runtime.spoken.isEmpty())
            assertEquals(1, fixture.actions.size)
            assertEquals(1, fixture.rejected)
            assertEquals(0, fixture.completed)
            assertFalse(fixture.actuator.isAppSpeechActive())
        }
    }

    @Test
    fun retiredLifecycleBackgroundAndReplacementActuatorRejectTheOldReadyCallback() {
        for (change in listOf("generation", "background", "actuator")) {
            val fixture = Fixture()
            fixture.rejectInitialRisk()
            when (change) {
                "generation" -> set(fixture.main, "feedbackLifecycleGeneration", GENERATION + 1)
                "background" -> set(fixture.main, "isActivityForeground", false)
                "actuator" -> set(fixture.main, "feedbackActuator", AndroidFeedbackActuator(runtime = FakeRuntime()))
            }
            fixture.runtime.advanceBy(100L)
            fixture.runtime.ready()

            assertEquals(1, fixture.readyCallbacks)
            assertNull(fixture.readyCallbackFailure)
            assertEquals(false, fixture.readyHandled)
            assertEquals(0, fixture.uiRefreshes)
            assertTrue(fixture.navigationRetries.isEmpty())
            assertTrue(fixture.runtime.spoken.isEmpty())
            assertEquals(1, fixture.actions.size)
            assertEquals(1, fixture.rejected)
        }
    }

    @Test
    fun rememberedRiskKeepsIssuedMetadataWithoutRetainingRawDepthOrDetectorInputs() {
        val fixture = Fixture()
        val output = riskOutput()
        val pixels = DepthImage16(2, 2, intArrayOf(1_000, 1_000, 1_000, 1_000))
        val evidence = DetectionFrameEvidence(
            frameId = output.frameId,
            frameTimestampMs = output.timestampMs,
            depthSnapshot = DepthFrameSnapshot(output.frameId, pixels,
                ConfidenceImage8(2, 2, ByteArray(4) { 255.toByte() }), null),
            depthMapper = null,
            tactileContext = TactileProjectionContext(output.frameId, null, null, null, null,
                null, null, 0L, CAPTURED_AT),
            motionContext = MotionContext(),
            navigationActive = false,
            tmapOnRoute = false,
        )
        val original = frame(listOf(output)).copy(
            matchedEvidence = evidence,
            depthDetections = listOf(DetectionCandidate("person", .95f, output.bboxNorm)),
            depthProcessingAttempted = true,
        )

        fixture.remember(frame = original)

        val input = requireNotNull(field(fixture.main, "latestSpeechReadyRiskInput"))
        val stored = input.javaClass.getDeclaredField("frame").apply { isAccessible = true }
            .get(input) as TactileSnapshotFrameResult
        assertNotSame(original, stored)
        assertNull(stored.matchedEvidence)
        assertTrue(stored.depthDetections.isEmpty())
        assertEquals(output, stored.guidance.outputs.single())
        assertEquals(CAPTURED_AT, stored.guidance.outputs.single().sourceObservedAtElapsedRealtimeMs)
        assertEquals(SOURCE_DEADLINE, stored.guidance.outputs.single().sourceValidUntilElapsedRealtimeMs)
        assertSame(pixels, requireNotNull(original.matchedEvidence).depthSnapshot.rawDepth)
        fixture.main.clearSpeechReadyRiskInput()
        assertNull(field(fixture.main, "latestSpeechReadyRiskInput"))
        fixture.runtime.ready()
        fixture.assertReadyCallbackHandled()
        assertTrue(fixture.runtime.spoken.isEmpty())
    }

    private fun assertCurrentRiskReevaluated(qualitativeMode: Boolean) {
        val fixture = Fixture(qualitativeMode)
        val initiallyRejected = fixture.rejectInitialRisk()
        fixture.trace.clear()
        fixture.runtime.advanceBy(100L)

        fixture.runtime.ready() // Calls the real runtime initialization callback and Main READY helper.

        fixture.assertReadyCallbackHandled()
        assertEquals("READY must re-evaluate the current risk, without waiting for another camera frame",
            1, fixture.runtime.spoken.size)
        assertEquals(2, fixture.actions.size)
        val retried = fixture.actions.last()
        assertNotSame(initiallyRejected.deliveryOwner, retried.deliveryOwner)
        assertEquals(initiallyRejected.deliveryKey, retried.deliveryKey)
        assertEquals(SOURCE_DEADLINE, retried.validUntilMs)
        assertEquals(listOf(NavigationSpeechDispatchResult.UNAVAILABLE, NavigationSpeechDispatchResult.ACCEPTED),
            fixture.speechResults)
        assertEquals(fixture.output.userFacing.message, fixture.runtime.spoken.single().message)
        assertEquals(TextToSpeech.QUEUE_FLUSH, fixture.runtime.spoken.single().queueMode)
        assertEquals(0, fixture.completed)
        assertEquals(0, fixture.failed)
        assertEquals(0, fixture.cancelled)
        assertTrue(fixture.policy.hasClaimedFeedbackDelivery(retried))
        assertTrue("Current risk must be admitted before regenerating navigation",
            fixture.trace.indexOf("risk") < fixture.trace.indexOf("navigation"))

        val id = fixture.runtime.spoken.single().id
        fixture.runtime.start(id)
        fixture.runtime.advanceBy(900L)
        fixture.runtime.done(id)
        fixture.runtime.done(id)
        assertEquals(1, fixture.completed)
        assertEquals(1, fixture.confirmed)
        assertEquals(0, fixture.failed)
        assertEquals(0, fixture.cancelled)
        assertFalse(fixture.policy.hasClaimedFeedbackDelivery(retried))
        assertFalse(fixture.actuator.isAppSpeechActive())
        assertEquals(1, fixture.runtime.focusReleases)
    }

    @Test
    fun guardedReadyRiskIsCancelledWhenLatestInputIsClearedBeforeNativeStart() {
        for (clear in listOf(false, true)) {
            val fixture = Fixture(guardedMode = true)
            fixture.rejectInitialRisk()
            fixture.runtime.advanceBy(100L)
            fixture.runtime.ready()
            fixture.assertReadyCallbackHandled()
            fixture.assertStartGuardDidNotThrow()
            val speech = fixture.runtime.spoken.single()
            val action = fixture.actions.last()
            assertTrue(fixture.policy.hasClaimedFeedbackDelivery(action))

            if (clear) fixture.main.clearSpeechReadyRiskInput()
            else fixture.remember(frame = frame(emptyList()))
            fixture.runtime.start(speech.id)
            fixture.runtime.done(speech.id)
            fixture.runtime.done(speech.id)

            fixture.assertStartGuardDidNotThrow()
            assertEquals(false, fixture.startGuardResults.last())
            assertEquals(1, fixture.runtime.spoken.size)
            assertEquals(1, fixture.runtime.stops)
            assertEquals(1, fixture.cancelled)
            assertEquals(0, fixture.failed)
            assertEquals(0, fixture.completed)
            assertEquals(0, fixture.confirmed)
            assertFalse(fixture.policy.hasClaimedFeedbackDelivery(action))
            assertFalse(fixture.actuator.isAppSpeechActive())
        }
    }

    @Test
    fun startedReadyRiskCompletesAfterLatestInputLossAndTheOriginalLeaseExpires() {
        for (loseInput in listOf(false, true)) {
            val fixture = Fixture(guardedMode = true)
            fixture.rejectInitialRisk()
            fixture.runtime.advanceBy(100L)
            fixture.runtime.ready()
            fixture.assertReadyCallbackHandled()
            fixture.assertStartGuardDidNotThrow()
            val speech = fixture.runtime.spoken.single()
            val action = fixture.actions.last()
            fixture.runtime.start(speech.id)

            if (loseInput) fixture.remember(frame = frame(emptyList()))
            fixture.runtime.advanceBy(900L)
            assertTrue(fixture.runtime.now > action.validUntilMs)
            fixture.runtime.done(speech.id)
            fixture.runtime.done(speech.id)

            fixture.assertStartGuardDidNotThrow()
            assertTrue(fixture.startGuardResults.all { it })
            assertEquals(1, fixture.completed)
            assertEquals(1, fixture.confirmed)
            assertEquals(0, fixture.failed)
            assertEquals(0, fixture.cancelled)
            assertEquals(0, fixture.runtime.stops)
            assertFalse(fixture.policy.hasClaimedFeedbackDelivery(action))
            assertFalse(fixture.actuator.isAppSpeechActive())
        }
    }

    @Test
    fun guardedReadyRiskRechecksProducerGateAndActorAtNativeStartWithoutRetiringTheNewActor() {
        for (change in listOf("producer", "gate", "actuator")) {
            val fixture = Fixture(guardedMode = true)
            fixture.rejectInitialRisk()
            fixture.runtime.advanceBy(100L)
            fixture.runtime.ready()
            fixture.assertReadyCallbackHandled()
            fixture.assertStartGuardDidNotThrow()
            val speech = fixture.runtime.spoken.single()
            val action = fixture.actions.last()
            val replacementRuntime = FakeRuntime()
            var replacementDone = 0
            var replacement: AndroidFeedbackActuator? = null
            when (change) {
                "producer" -> fixture.producerCurrent = false
                "gate" -> fixture.gateAllowsAlerts = false
                "actuator" -> {
                    val current = AndroidFeedbackActuator(runtime = replacementRuntime, hapticAllowed = { false })
                    replacement = current
                    set(fixture.main, "feedbackActuator", current)
                    replacementRuntime.ready()
                    assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                        current.speakNavigation("새 엔진의 현재 길안내", onCompleted = { replacementDone += 1 },
                            requiresExplicitTerminalCallback = true))
                    replacementRuntime.start(replacementRuntime.spoken.single().id)
                }
            }

            fixture.runtime.start(speech.id)
            fixture.runtime.done(speech.id)

            fixture.assertStartGuardDidNotThrow()
            assertEquals(false, fixture.startGuardResults.last())
            assertEquals(change, 1, fixture.runtime.stops)
            assertEquals(change, 1, fixture.cancelled)
            assertEquals(0, fixture.failed)
            assertEquals(0, fixture.completed)
            assertEquals(0, fixture.confirmed)
            assertFalse(fixture.policy.hasClaimedFeedbackDelivery(action))
            assertFalse(fixture.actuator.isAppSpeechActive())
            replacement?.let { current ->
                assertEquals(0, replacementRuntime.stops)
                assertTrue(current.isAppSpeechActive())
                assertEquals(0, replacementDone)
                replacementRuntime.done(replacementRuntime.spoken.single().id)
                assertEquals(1, replacementDone)
                assertFalse(current.isAppSpeechActive())
            }
        }
    }

    @Test
    fun reentrantProducerAndGateChecksCannotSubmitRiskFromTheReplacedReadyInput() {
        for (change in listOf("producer", "gate")) {
            val fixture = Fixture(guardedMode = true)
            fixture.rejectInitialRisk()
            var reentrantChecks = 0
            fixture.beforeGuardedDelivery = {
                // Arm after Main has evaluated/reserved the action, before the real actor's validation.
                if (change == "producer") {
                    fixture.onProducerCheck = {
                        reentrantChecks += 1
                        fixture.main.clearSpeechReadyRiskInput()
                    }
                } else {
                    fixture.onGateCheck = {
                        reentrantChecks += 1
                        fixture.remember(frame = frame(emptyList()))
                    }
                }
            }
            fixture.runtime.advanceBy(100L)

            fixture.runtime.ready()

            fixture.assertReadyCallbackHandled()
            fixture.assertStartGuardDidNotThrow()
            assertEquals("The source/gate callback must actually replace the slot", 1, reentrantChecks)
            assertNull(fixture.onProducerCheck)
            assertNull(fixture.onGateCheck)
            assertTrue(fixture.startGuardResults.any { !it })
            assertEquals(2, fixture.actions.size)
            assertEquals(listOf(NavigationSpeechDispatchResult.UNAVAILABLE, NavigationSpeechDispatchResult.SUPPRESSED),
                fixture.speechResults)
            assertTrue(fixture.runtime.spoken.isEmpty())
            assertEquals(0, fixture.runtime.focusRequests)
            assertEquals(2, fixture.rejected)
            assertEquals(0, fixture.completed)
            assertEquals(0, fixture.confirmed)
            assertEquals(0, fixture.failed)
            assertEquals(0, fixture.cancelled)
            assertFalse(fixture.policy.hasClaimedFeedbackDelivery(fixture.actions.last()))
            assertFalse(fixture.actuator.isAppSpeechActive())
        }
    }

    @Test
    fun readyReevaluationAdmitsOnlyCautionWarningAndStopInsteadOfInformation() {
        for (level in listOf(MessageLevel.INFO, MessageLevel.CAUTION, MessageLevel.WARNING, MessageLevel.STOP)) {
            val fixture = Fixture(guardedMode = true)
            val output = riskOutput().copy(userFacing = UserFacingDepth(null, level, "현재 위험 $level"))
            fixture.remember(frame = frame(listOf(output)))
            fixture.runtime.advanceBy(100L)

            fixture.runtime.ready()

            fixture.assertReadyCallbackHandled()
            fixture.assertStartGuardDidNotThrow(expectInvoked = level != MessageLevel.INFO)
            if (level == MessageLevel.INFO) {
                assertTrue(fixture.startGuardResults.isEmpty())
                assertTrue(fixture.actions.isEmpty())
                assertTrue(fixture.runtime.spoken.isEmpty())
                assertEquals(0, fixture.runtime.focusRequests)
            } else {
                val action = fixture.actions.single()
                assertEquals(level, action.level)
                assertEquals(SOURCE_DEADLINE, action.validUntilMs)
                assertEquals(listOf(NavigationSpeechDispatchResult.ACCEPTED), fixture.speechResults)
                val speech = fixture.runtime.spoken.single()
                fixture.runtime.start(speech.id)
                fixture.runtime.done(speech.id)
                fixture.assertStartGuardDidNotThrow()
                assertTrue(fixture.startGuardResults.all { it })
                assertEquals(1, fixture.completed)
                assertEquals(1, fixture.confirmed)
                assertEquals(0, fixture.cancelled)
                assertFalse(fixture.policy.hasClaimedFeedbackDelivery(action))
            }
        }
    }

    @Test
    fun sourceOnlyProductionReadyGuardReachesOffMainAndNativeStartWithoutChangingCompletionOwnership() {
        // SOURCE_ONLY: direct tests above execute the Main-issued guard; this checks its private UI wiring.
        val source = ReportStaticSourceInspector.read("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")
        val frame = ReportStaticSourceInspector.read("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePublicationController.kt")
        val guardAnchor = "delivery = { dispatch, current ->"
        val productionMemos = Regex(Regex.escape(guardAnchor)).findAll(frame).toList()
        assertEquals(2, productionMemos.size)
        productionMemos.forEach { memo ->
            val block = ReportStaticSourceInspector.blockAfter(frame.substring(memo.range.first), "delivery =")
            assertTrue(block.contains("risk.emitFeedback(dispatch, producer, current, input.sourceIsCurrent)"))
        }
        val riskPorts = MainActivityRefactorSource.property(source, "frameRiskOutputs")
        assertTrue(riskPorts.contains("emitTactileFrameFeedback(dispatch, scope, startIsCurrent, producerIsCurrent)"))
        assertTrue(riskPorts.contains("warningContext, delivery, receipt)"))
        val delivery = ReportStaticSourceInspector.read("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/RiskFeedbackDeliveryController.kt")
        val intake = MainActivityRefactorSource.member(delivery, "emitTactileFrameFeedback")
        assertTrue(intake.contains("emitFeedbackAction(it, elapsedRealtimeMs, startIsCurrent)"))
        val emit = MainActivityRefactorSource.member(delivery, "emitFeedbackAction")
        val offMain = emit.substringBefore("// A rejected replacement")
        assertTrue(offMain.contains("runOnUiThread"))
        assertTrue(offMain.contains("emitFeedbackAction(action, policyEvaluatedAtMs, startIsCurrent)"))
        assertTrue(offMain.contains("startIsCurrent?.invoke() == false"))
        val completion = emit.substringAfter("val completionCurrent: () -> Boolean =")
            .substringBefore("val outputCurrent: () -> Boolean =")
        assertTrue(completion.contains("feedbackPolicy.hasClaimedFeedbackDelivery(action)"))
        assertFalse(completion.contains("startIsCurrent"))
        val outputCurrent = emit.substringAfter("val outputCurrent: () -> Boolean =")
            .substringBefore("val cancelDelivery: () -> Unit =")
        assertTrue(outputCurrent.contains("startIsCurrent?.invoke() != false"))
        assertTrue(emit.contains("isStillValidAtStart = outputCurrent"))
    }

    private class Fixture(val qualitativeMode: Boolean = false, private val guardedMode: Boolean = false) {
        val main = MainActivity()
        val runtime = FakeRuntime()
        val policy = field(main, "feedbackPolicy") as WalkSafeFeedbackPolicy
        val output = riskOutput(qualitativeMode)
        val inputFrame = frame(listOf(output))
        val actions = mutableListOf<FeedbackAction>()
        val speechResults = mutableListOf<NavigationSpeechDispatchResult>()
        val navigationRetries = mutableListOf<Long>()
        val trace = mutableListOf<String>()
        var producerCurrent = true
        var gateAllowsAlerts = true
        var readyCallbacks = 0
        var readyHandled: Boolean? = null
        var readyCallbackFailure: Throwable? = null
        var uiRefreshes = 0
        var rejected = 0
        var completed = 0
        var confirmed = 0
        var failed = 0
        var cancelled = 0
        var beforeGuardedDelivery: (() -> Unit)? = null
        var onProducerCheck: (() -> Unit)? = null
        var onGateCheck: (() -> Unit)? = null
        val startGuardResults = mutableListOf<Boolean>()
        private val startGuardFailures = mutableListOf<Throwable>()
        private var readyStartGuard: () -> Boolean = { true }
        val actuator = AndroidFeedbackActuator(
            runtime = runtime,
            hapticAllowed = { false },
            onOfflineKoreanSpeechReady = { onReady() },
        )

        init {
            set(main, "isActivityForeground", true)
            set(main, "feedbackLifecycleGeneration", GENERATION)
            set(main, "feedbackActuator", actuator)
            main.tactileFrameFeedbackDelivery = ::deliver
        }

        fun remember(frame: TactileSnapshotFrameResult = inputFrame, stale: Boolean = false) {
            if (!guardedMode) {
                // Preserve the original six tests' default clock and configured delivery contract.
                main.rememberSpeechReadyRiskInput(
                    frame = frame,
                    stale = stale,
                    qualitativeMode = qualitativeMode,
                    producerIsCurrent = { producerCurrent },
                    currentGateAllowsAlerts = { gateAllowsAlerts },
                )
                return
            }
            main.rememberSpeechReadyRiskInput(
                frame = frame,
                stale = stale,
                qualitativeMode = qualitativeMode,
                producerIsCurrent = {
                    val callback = onProducerCheck
                    onProducerCheck = null
                    callback?.invoke()
                    producerCurrent
                },
                currentGateAllowsAlerts = {
                    val callback = onGateCheck
                    onGateCheck = null
                    callback?.invoke()
                    gateAllowsAlerts
                },
                guardedFeedbackDelivery = { dispatch, stillCurrent ->
                    val callback = beforeGuardedDelivery
                    beforeGuardedDelivery = null
                    callback?.invoke()
                    val previousGuard = readyStartGuard
                    readyStartGuard = {
                        try {
                            stillCurrent().also { startGuardResults += it }
                        } catch (failure: Throwable) {
                            // Keep the actor's fail-closed behavior, but make swallowed exceptions observable.
                            startGuardFailures += failure
                            throw failure
                        }
                    }
                    try {
                        main.tactileFrameFeedbackDelivery(dispatch)
                    } finally {
                        readyStartGuard = previousGuard
                    }
                },
                nowElapsedRealtimeMs = runtime::elapsedRealtime,
            )
        }

        fun assertStartGuardDidNotThrow(expectInvoked: Boolean = true) {
            assertEquals("A swallowed validator exception must not count as a valid cancellation",
                emptyList<String>(), startGuardFailures.map { "${it.javaClass.name}: ${it.message}" })
            if (expectInvoked) assertTrue("The Main-issued start guard must actually execute", startGuardResults.isNotEmpty())
        }

        fun rejectInitialRisk(): FeedbackAction {
            remember()
            val dispatch = main.dispatchTactileFrameFeedback(
                frame = inputFrame,
                stale = false,
                deviceGateAllowsAlerts = true,
                nowMs = runtime.now,
            )
            val action = requireNotNull(dispatch.action)
            assertEquals(MessageLevel.CAUTION, action.level)
            assertSame(action, actions.single())
            assertEquals(listOf(NavigationSpeechDispatchResult.UNAVAILABLE), speechResults)
            assertEquals(1, rejected)
            assertEquals(0, completed)
            assertFalse(policy.hasClaimedFeedbackDelivery(action))
            assertTrue(runtime.spoken.isEmpty())
            assertEquals(0, runtime.focusRequests)
            assertEquals("tts=initializing", actuator.statusText())
            assertFalse(actuator.isAppSpeechActive())
            return action
        }

        fun assertReadyCallbackHandled() {
            assertEquals(1, readyCallbacks)
            assertNull("A swallowed initialization callback error is not a behavioral RED", readyCallbackFailure)
            assertEquals(true, readyHandled)
            assertEquals(1, uiRefreshes)
            assertEquals(listOf(runtime.now), navigationRetries)
            assertEquals("tts=ready", actuator.statusText())
        }

        private fun onReady() {
            readyCallbacks += 1
            try {
                readyHandled = main.handleOfflineSpeechReady(
                    expectedActuator = actuator,
                    expectedLifecycleGeneration = GENERATION,
                    nowMs = runtime.now,
                    refreshReadyUi = { uiRefreshes += 1; trace += "ui" },
                    retryCurrentNavigation = { navigationRetries += it; trace += "navigation" },
                )
            } catch (failure: Throwable) {
                readyCallbackFailure = failure
            }
        }

        private fun deliver(dispatch: TactileFrameFeedbackDispatch) {
            val action = dispatch.action ?: return
            val startGuard = readyStartGuard
            actions += action
            trace += "risk"
            assertTrue(policy.claimFeedbackDelivery(action, dispatch.policyEvaluatedAtMs, runtime.now))
            val speech = actuator.emit(
                action = action,
                isStillValidAtStart = {
                    (guardedMode || (producerCurrent && gateAllowsAlerts)) && runtime.now <= action.validUntilMs &&
                        policy.hasClaimedFeedbackDelivery(action) && startGuard()
                },
                onSpeechCompleted = {
                    completed += 1
                    if (policy.confirmFeedbackDelivery(action, dispatch.policyEvaluatedAtMs, runtime.now)) confirmed += 1
                },
                onSpeechFailed = {
                    failed += 1
                    policy.rejectUndeliveredFeedback(action, dispatch.policyEvaluatedAtMs)
                },
                onSpeechCancelled = {
                    cancelled += 1
                    policy.rejectUndeliveredFeedback(action, dispatch.policyEvaluatedAtMs)
                },
            )
            speechResults += speech.speech
            if (speech.speech != NavigationSpeechDispatchResult.ACCEPTED && !speech.vibrationAccepted) {
                rejected += 1
                policy.rejectUndeliveredFeedback(action, dispatch.policyEvaluatedAtMs)
            }
        }
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String, val queueMode: Int)
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)
        var now = CAPTURED_AT
        val spoken = mutableListOf<Spoken>()
        var focusRequests = 0
        var focusReleases = 0
        var stops = 0
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId, queueMode)
            return TextToSpeech.SUCCESS
        }
        override fun stop() { stops += 1 }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            focusRequests += 1
            return true
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) { focusReleases += 1 }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("risk READY must not create a tone")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() {
            requireNotNull(initialized).invoke(TextToSpeech.SUCCESS)
            advanceBy(0L)
        }
        fun start(id: String) { requireNotNull(listener).onStart(id); advanceBy(0L) }
        fun done(id: String) { requireNotNull(listener).onDone(id); advanceBy(0L) }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }
                    .minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = maxOf(now, next.due)
                next.runnable.run()
            }
            now = target
        }
    }

    private companion object {
        const val GENERATION = 7
        const val CAPTURED_AT = 1_000L
        const val SOURCE_DEADLINE = CAPTURED_AT + DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS

        fun field(main: MainActivity, name: String): Any? =
            MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)

        fun set(main: MainActivity, name: String, value: Any?) {
            MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
        }

        fun frame(outputs: List<TrackedObjectDepth>) = TactileSnapshotFrameResult(
            null, emptyList(), false,
            TactileRouteGuidanceResult(outputs, null, TactileRouteDecision(LocalRouteMode.TMAP, null, "ready-test", null)),
        )

        fun riskOutput(qualitativeMode: Boolean = false): TrackedObjectDepth {
            val box = RectNorm(.1f, .3f, .2f, .4f)
            val distance = 1f.takeUnless { qualitativeMode }
            return TrackedObjectDepth(
                frameId = 51_000_000_000L, timestampMs = 51_000L, trackId = "current-risk", className = "person",
                detectionConfidence = .95f, bboxNorm = box, polygonNorm = emptyList(), maskAreaNorm = box.area,
                centerNorm = box.center, bottomContactNorm = null,
                source = if (qualitativeMode) DepthSource.POLYGON_TREND_PSEUDO_DEPTH else DepthSource.ARCORE_RAW_DEPTH,
                zDistanceM = distance, rayDistanceM = distance, groundDistanceM = distance, riskDistanceM = distance,
                validSampleCount = if (qualitativeMode) 0 else 80, validSampleRatio = if (qualitativeMode) 0f else .9f,
                depthMedianM = distance, depthP20M = distance, depthIqrM = .05f.takeUnless { qualitativeMode },
                trend = if (qualitativeMode) Trend.APPROACHING else Trend.STABLE,
                approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
                confidence = DepthConfidenceBreakdown(if (qualitativeMode) .25f else .95f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                userFacing = UserFacingDepth(null, MessageLevel.CAUTION, "앞에 장애물이 있습니다. 주의하세요."),
                trackAgeFrames = 20, trackStableMs = 1_900L,
                depthObservedAtMs = 51_000L.takeUnless { qualitativeMode },
                sourceValidUntilElapsedRealtimeMs = SOURCE_DEADLINE,
                sourceObservedAtElapsedRealtimeMs = CAPTURED_AT,
                sourceMaxAgeMs = DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS,
            )
        }
    }
}
