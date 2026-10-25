package kr.co.hanium.dreamup.walksafe.feedback

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.ObjectMotion
import kr.co.hanium.dreamup.walksafe.depth.ObjectMotionEstimate
import kr.co.hanium.dreamup.walksafe.depth.ObjectMovementDirection
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackBatch
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.depth.Vec3
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * allowUnknownObjectSpeechTest=true opts into actual offline Korean component speech.
 * Depth, approaching motion, frame identity and walk epoch are synthetic fixtures, not observations.
 * No MainActivity, account, settings, camera or location provider is opened or changed.
 * Strict onDone is not human audibility, physical start timing, detection accuracy or walking E2E.
 */
@RunWith(AndroidJUnit4::class)
class UnknownObjectSpeechDeviceTest {
    @Test(timeout = 90_000L)
    fun sourceExpiredBeforeDispatchIsSuppressedWithoutSpeechTerminal() {
        val terminal = SpeechTerminal()
        val validityCalls = AtomicInteger(0)
        withReadyActuator { instrumentation, actuator ->
            instrumentation.runOnMainSync {
                val capturedAtMs = SystemClock.elapsedRealtime() - UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS - 1L
                // This batch was admitted while fresh, then held past its source deadline.
                val fixture = fixture(capturedAtMs)
                assertTrue(SystemClock.elapsedRealtime() > fixture.action.validUntilMs)
                assertNull(UnknownObjectFeedbackPolicy().admit(
                    outputs = fixture.batch.outputs,
                    sourceFrameId = fixture.batch.sourceFrameId,
                    sourceTimestampMs = fixture.batch.sourceTimestampMs,
                    sourceCapturedAtElapsedRealtimeNs = fixture.batch.sourceCapturedAtElapsedRealtimeNs,
                    completedAtElapsedRealtimeMs = fixture.batch.completedAtElapsedRealtimeMs,
                    sourceEpoch = fixture.batch.sourceEpoch,
                    currentEpoch = fixture.batch.sourceEpoch,
                    nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                ))
                // The now-missing source withdraws the unclaimed reservation.
                assertNull(fixture.policy.evaluate(null, true, SystemClock.elapsedRealtime()))
                assertFalse(fixture.policy.claimFeedbackDelivery(
                    fixture.action.trackId, fixture.evaluatedAtMs, SystemClock.elapsedRealtime()))
                val result = actuator.emit(
                    action = fixture.action,
                    onSpeechCompleted = terminal::onCompleted,
                    onSpeechFailed = terminal::onFailed,
                    isStillValidAtStart = { validityCalls.incrementAndGet(); true },
                )
                assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, result.speech)
                assertFalse(result.vibrationAccepted)
                assertFalse(actuator.isAppSpeechActive())
                assertEquals("The expired action must be rejected before its start validator", 0, validityCalls.get())
            }
            assertFalse("A suppressed dispatch must not produce a speech terminal", terminal.received.await(200L, TimeUnit.MILLISECONDS))
            report(instrumentation, "case=expired_before_emit dispatch=SUPPRESSED completion=${terminal.completed.get()} failures=${terminal.failed.get()}")
        }
        terminal.assertCounts(completed = 0, failed = 0)
    }

    @Test(timeout = 90_000L)
    fun sourceDeadlineLimitsStartWhileActualKoreanSpeechCanFinishLater() {
        val terminal = SpeechTerminal()
        val start = StartValidation()
        var sourceDeadlineMs = 0L
        withReadyActuator { instrumentation, actuator ->
            warmUp(instrumentation, actuator)
            instrumentation.runOnMainSync {
                val fixture = fixture(SystemClock.elapsedRealtime())
                sourceDeadlineMs = fixture.action.validUntilMs
                emitAccepted(actuator, fixture, terminal, start)
            }
            awaitTerminal(instrumentation, "started_before_deadline", terminal)
            terminal.assertCounts(completed = 1, failed = 0)
            assertEquals("The expired source must not consume a claimed speech delivery", true, terminal.deliveryConfirmed.get())
            instrumentation.runOnMainSync {
                assertFalse(actuator.isAppSpeechActive())
                assertFalse(actuator.validatingStartedSpeech)
            }
            start.assertCompletedChecks()
            assertEquals("Expected the production onStart validator after dispatch", 1, start.afterDispatchCalls.get())
            assertTrue("The start callback must be validated before the source deadline", start.observedAtMs.get() in 0L..sourceDeadlineMs)
            assertTrue("The actual onDone must outlive the start deadline", terminal.observedAtMs.get() > sourceDeadlineMs)
            report(instrumentation, "case=started_before_deadline start_validation_ms=${start.observedAtMs.get()} source_deadline_ms=$sourceDeadlineMs actual_onDone_ms=${terminal.observedAtMs.get()}")
        }
        terminal.assertCounts(completed = 1, failed = 0)
    }

    @Test(timeout = 90_000L)
    fun delayedMainLoopPastSourceStartDeadlineFailsWithoutCompletion() {
        val terminal = SpeechTerminal()
        val start = StartValidation()
        var sourceDeadlineMs = 0L
        withReadyActuator { instrumentation, actuator ->
            warmUp(instrumentation, actuator)
            instrumentation.runOnMainSync {
                val fixture = fixture(SystemClock.elapsedRealtime())
                sourceDeadlineMs = fixture.action.validUntilMs
                emitAccepted(actuator, fixture, terminal, start)
                // Production TTS callbacks are posted to this same looper. This bounded delay
                // exercises late callback/start-watchdog rejection, not physical speech timing.
                val delayMs = sourceDeadlineMs + 75L - SystemClock.elapsedRealtime()
                assertTrue("The main-loop delay must remain bounded", delayMs in 1L..875L)
                SystemClock.sleep(delayMs)
                assertTrue(SystemClock.elapsedRealtime() > sourceDeadlineMs)
                terminal.assertCounts(completed = 0, failed = 0)
            }
            awaitTerminal(instrumentation, "delayed_main_start_deadline", terminal)
            terminal.assertCounts(completed = 0, failed = 1)
            assertEquals("The undelivered terminal must come from cancellation", 1, terminal.cancelled.get())
            assertEquals("Source expiry is not a speech-engine failure callback", 0, terminal.failureCallbacks.get())
            assertEquals("Cancellation must retire the exact claimed delivery", false, terminal.claimPresentAfterCancellation.get())
            assertNull("An unstarted cancellation must never acknowledge delivery", terminal.deliveryConfirmed.get())
            instrumentation.runOnMainSync { assertFalse(actuator.isAppSpeechActive()) }
            assertTrue("The failure must occur after the source deadline", terminal.observedAtMs.get() > sourceDeadlineMs)
            // The action-deadline check short-circuits the external validator after expiry.
            // A failure alone cannot distinguish that onStart branch from the start watchdog.
            assertEquals(0, start.afterDispatchCalls.get())
            start.assertNoValidNativeStartOrDone()
            report(instrumentation, "case=delayed_main_start_deadline source_deadline_ms=$sourceDeadlineMs failed_ms=${terminal.observedAtMs.get()} cancelled=${terminal.cancelled.get()} failure_callbacks=${terminal.failureCallbacks.get()} outcomes=${start.outcomesText()} exact_failure_branch=NOT_OBSERVABLE physical_start_time=NOT_TESTED")
        }
        terminal.assertCounts(completed = 0, failed = 1)
    }

    private fun withReadyActuator(block: (Instrumentation, AndroidFeedbackActuator) -> Unit) {
        assumeTrue(
            "Requires explicit allowUnknownObjectSpeechTest=true because this test plays Korean speech",
            InstrumentationRegistry.getArguments().getString("allowUnknownObjectSpeechTest") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val initialized = CountDownLatch(1)
        val readiness = AtomicReference<String?>(null)
        var actuator: AndroidFeedbackActuator? = null
        report(instrumentation, "scope=COMPONENT source_depth=SIMULATED object_approach=SIMULATED main_activity_launched=false human_audibility=NOT_TESTED")
        try {
            instrumentation.runOnMainSync {
                actuator = AndroidFeedbackActuator(
                    context = instrumentation.targetContext,
                    hapticAllowed = { false },
                    onOfflineKoreanSpeechReady = {
                        if (readiness.compareAndSet(null, "READY")) initialized.countDown()
                    },
                    onOfflineKoreanSpeechUnavailable = {
                        if (readiness.compareAndSet(null, "UNAVAILABLE")) initialized.countDown()
                    },
                )
            }
            val received = initialized.await(25_000L, TimeUnit.MILLISECONDS)
            report(instrumentation, "readiness=${readiness.get()} callback_received=$received")
            assertTrue("UNKNOWN_OBJECT_SPEECH_INIT_TIMEOUT", received)
            // After opt-in, unavailable Korean speech is a failure rather than an assumption skip.
            assertEquals("READY", readiness.get())
            block(instrumentation, checkNotNull(actuator))
        } finally {
            instrumentation.runOnMainSync { actuator?.close() }
        }
    }

    private fun warmUp(instrumentation: Instrumentation, actuator: AndroidFeedbackActuator) {
        val terminal = SpeechTerminal()
        instrumentation.runOnMainSync {
            val result = actuator.speakHomeCommandInteraction(
                message = "한국어 음성 안내 준비를 확인합니다.",
                onCompleted = terminal::onCompleted,
                onFailed = terminal::onFailed,
            )
            assertEquals("UNKNOWN_OBJECT_SPEECH_WARMUP_REJECTED", NavigationSpeechDispatchResult.ACCEPTED, result)
        }
        awaitTerminal(instrumentation, "warmup", terminal)
        terminal.assertCounts(completed = 1, failed = 0)
    }

    /** Called on the main looper, so a posted production onStart cannot run before emit returns. */
    private fun emitAccepted(
        actuator: AndroidFeedbackActuator,
        fixture: Fixture,
        terminal: SpeechTerminal,
        start: StartValidation,
    ) {
        assertTrue("UNKNOWN_OBJECT_DELIVERY_CLAIM_REJECTED", fixture.policy.claimFeedbackDelivery(
            fixture.action.trackId, fixture.evaluatedAtMs, SystemClock.elapsedRealtime(),
        ))
        start.bind(fixture.batch, fixture.action.validUntilMs)
        actuator.speechOutputListener = { event ->
            if (event.kind == SpeechOutputKind.RISK) start.observe(event.outcome)
        }
        val result = actuator.emit(
            action = fixture.action,
            onSpeechCompleted = {
                val deliveredAtMs = SystemClock.elapsedRealtime()
                if (!fixture.batch.isFreshAt(deliveredAtMs)) {
                    // Reconcile absent source evidence before consuming the actual terminal.
                    fixture.policy.evaluate(null, true, deliveredAtMs)
                }
                terminal.deliveryConfirmed.set(fixture.policy.confirmFeedbackDelivery(
                    fixture.action.trackId, fixture.evaluatedAtMs, deliveredAtMs,
                ))
                terminal.onCompleted()
            },
            onSpeechFailed = {
                fixture.policy.rejectUndeliveredFeedback(fixture.action.trackId, fixture.evaluatedAtMs)
                terminal.onFailed()
            },
            onSpeechCancelled = {
                fixture.policy.rejectUndeliveredFeedback(fixture.action.trackId, fixture.evaluatedAtMs)
                terminal.claimPresentAfterCancellation.set(fixture.policy.hasClaimedFeedbackDelivery(fixture.action))
                terminal.onCancelled()
            },
            isStillValidAtStart = { start.validate(fixture.batch, actuator.validatingStartedSpeech) },
        )
        start.dispatchReturned = true
        if (result.speech != NavigationSpeechDispatchResult.ACCEPTED) {
            fixture.policy.rejectUndeliveredFeedback(fixture.action.trackId, fixture.evaluatedAtMs)
        }
        assertEquals("UNKNOWN_OBJECT_SPEECH_DISPATCH_REJECTED", NavigationSpeechDispatchResult.ACCEPTED, result.speech)
        assertFalse(result.vibrationAccepted)
        assertTrue(actuator.isAppSpeechActive())
        start.assertSubmissionChecks()
    }

    private class StartValidation {
        var dispatchReturned = false
        val afterDispatchCalls = AtomicInteger(0)
        val observedAtMs = AtomicLong(-1L)
        private val trace = CopyOnWriteArrayList<String>()
        private val outcomes = CopyOnWriteArrayList<SpeechOutputOutcome>()
        private val checks = CopyOnWriteArrayList<SourceCheck>()
        private lateinit var originalBatch: UnknownObjectFeedbackBatch
        private var originalDeadlineMs = -1L

        private data class SourceCheck(val batch: UnknownObjectFeedbackBatch, val atMs: Long, val fresh: Boolean)

        fun bind(batch: UnknownObjectFeedbackBatch, deadlineMs: Long) {
            originalBatch = batch
            originalDeadlineMs = deadlineMs
        }

        fun observe(outcome: SpeechOutputOutcome) {
            outcomes += outcome
            trace += outcome.name
        }

        fun validate(batch: UnknownObjectFeedbackBatch, validatingNativeStart: Boolean): Boolean {
            val nowMs = SystemClock.elapsedRealtime()
            if (validatingNativeStart) {
                afterDispatchCalls.incrementAndGet()
                observedAtMs.compareAndSet(-1L, nowMs)
            }
            trace += when {
                validatingNativeStart -> "NATIVE_START_CHECK"
                dispatchReturned -> "WATCHDOG_CHECK"
                SpeechOutputOutcome.REQUESTED in outcomes -> "QUEUE_RELEASE_CHECK"
                else -> "PRE_REQUEST_CHECK"
            }
            val fresh = batch.isFreshAt(nowMs)
            checks += SourceCheck(batch, nowMs, fresh)
            return fresh
        }

        fun assertSubmissionChecks() {
            // The public marker cannot distinguish ingress from preflight: both precede REQUESTED.
            assertEquals(listOf("PRE_REQUEST_CHECK", "PRE_REQUEST_CHECK", "REQUESTED", "QUEUE_RELEASE_CHECK"), trace.toList())
            assertEquals(listOf(SpeechOutputOutcome.REQUESTED), outcomes.toList())
            assertEquals(0, afterDispatchCalls.get())
            assertSourceChecks()
        }

        fun assertCompletedChecks() {
            assertEquals(listOf("PRE_REQUEST_CHECK", "PRE_REQUEST_CHECK", "REQUESTED", "QUEUE_RELEASE_CHECK",
                "STARTED", "NATIVE_START_CHECK", "DONE"), trace.toList())
            assertEquals(listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE), outcomes.toList())
            assertEquals(1, afterDispatchCalls.get())
            assertSourceChecks()
        }

        fun assertNoValidNativeStartOrDone() {
            assertFalse("An expired source cannot reach the external native START validator", "NATIVE_START_CHECK" in trace)
            assertFalse("The expired action must not complete", SpeechOutputOutcome.DONE in outcomes)
            assertEquals(1, outcomes.count { it == SpeechOutputOutcome.EXPIRED_BEFORE_START })
            assertEquals(0, outcomes.count { it == SpeechOutputOutcome.ENGINE_ERROR })
        }

        private fun assertSourceChecks() {
            assertEquals(originalBatch.validUntilElapsedRealtimeMs, originalDeadlineMs)
            checks.forEach { check ->
                assertSame(originalBatch, check.batch)
                assertTrue(check.fresh)
                assertTrue(check.atMs in originalBatch.completedAtElapsedRealtimeMs..originalDeadlineMs)
            }
        }

        fun outcomesText(): String = outcomes.joinToString(",") { it.name }
    }

    private class SpeechTerminal {
        val received = CountDownLatch(1)
        val completed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val cancelled = AtomicInteger(0)
        val failureCallbacks = AtomicInteger(0)
        val observedAtMs = AtomicLong(-1L)
        val deliveryConfirmed = AtomicReference<Boolean?>(null)
        val claimPresentAfterCancellation = AtomicReference<Boolean?>(null)

        fun onCompleted() {
            observedAtMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
            completed.incrementAndGet()
            received.countDown()
        }

        fun onFailed() {
            failureCallbacks.incrementAndGet()
            finishUndelivered()
        }

        fun onCancelled() {
            cancelled.incrementAndGet()
            finishUndelivered()
        }

        /** Keep the legacy failed count as all undelivered terminals, separate from their cause. */
        private fun finishUndelivered() {
            observedAtMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
            failed.incrementAndGet()
            received.countDown()
        }

        fun assertCounts(completed: Int, failed: Int) {
            assertEquals("Unexpected strict TTS completion count", completed, this.completed.get())
            assertEquals("Unexpected speech failure count", failed, this.failed.get())
        }
    }

    private fun awaitTerminal(instrumentation: Instrumentation, label: String, terminal: SpeechTerminal) {
        val received = terminal.received.await(25_000L, TimeUnit.MILLISECONDS)
        report(instrumentation, "case=$label callback_received=$received actual_onDone=${terminal.completed.get()} failures=${terminal.failed.get()}")
        assertTrue("UNKNOWN_OBJECT_SPEECH_TERMINAL_TIMEOUT: $label", received)
    }

    private data class Fixture(
        val batch: UnknownObjectFeedbackBatch,
        val action: FeedbackAction,
        val policy: WalkSafeFeedbackPolicy,
        val evaluatedAtMs: Long,
    )

    private fun fixture(capturedAtMs: Long): Fixture {
        val sourceTimestampMs = 50_000L
        val sourceFrameId = sourceTimestampMs * 1_000_000L
        val epoch = WalkRuntimeEpoch("synthetic-unknown-object-speech", 0L)
        val output = TrackedObjectDepth(
            frameId = sourceFrameId,
            timestampMs = sourceTimestampMs,
            trackId = "synthetic-approaching-unknown",
            className = UnknownObjectFeedbackPolicy.CLASS_NAME,
            detectionConfidence = 0.95f,
            bboxNorm = RectNorm(0.3f, 0.3f, 0.4f, 0.5f),
            polygonNorm = emptyList(),
            maskAreaNorm = 0.2f,
            centerNorm = Point2(0.5f, 0.55f),
            bottomContactNorm = Point2(0.5f, 0.8f),
            source = DepthSource.ARCORE_RAW_DEPTH,
            zDistanceM = 1f,
            rayDistanceM = 1f,
            groundDistanceM = 1f,
            riskDistanceM = 1f,
            validSampleCount = 80,
            validSampleRatio = 0.9f,
            depthMedianM = 1f,
            depthP20M = 1f,
            depthIqrM = 0.05f,
            trend = Trend.APPROACHING,
            approachScore = 0.9f,
            approachSpeedMps = 0.6f,
            timeToCollisionMs = 1_667L,
            confidence = DepthConfidenceBreakdown(0.95f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            userFacing = UserFacingDepth(null, MessageLevel.NONE, null),
            trackAgeFrames = 10,
            trackStableMs = 1_000L,
            objectMotion = ObjectMotion.OBJECT_APPROACHING,
            motionEstimate = ObjectMotionEstimate(
                referenceId = 1L,
                observedAtMs = sourceTimestampMs,
                elapsedMs = 1_000L,
                objectVelocityInAnchorMps = Vec3(0f, 0f, -0.6f),
                relativeVelocityInAnchorMps = Vec3(0f, 0f, -0.6f),
                cameraVelocityInAnchorMps = Vec3(0f, 0f, 0f),
                objectSpeedMps = 0.6f,
                relativeClosingSpeedMps = 0.6f,
                direction = ObjectMovementDirection.TOWARD_USER,
                confidence = 0.95f,
            ),
        )
        val batch = checkNotNull(UnknownObjectFeedbackPolicy().admit(
            outputs = listOf(output),
            sourceFrameId = sourceFrameId,
            sourceTimestampMs = sourceTimestampMs,
            sourceCapturedAtElapsedRealtimeNs = capturedAtMs * 1_000_000L,
            completedAtElapsedRealtimeMs = capturedAtMs,
            sourceEpoch = epoch,
            currentEpoch = epoch,
            nowElapsedRealtimeMs = capturedAtMs,
        ))
        val candidate = checkNotNull(batch.outputs.single().toFeedbackCandidate())
        val policy = WalkSafeFeedbackPolicy()
        val action = checkNotNull(policy.evaluate(
            candidate = candidate,
            deviceGateAllowsAlerts = true,
            nowMs = capturedAtMs,
        ))
        assertEquals(MessageLevel.STOP, action.level)
        assertTrue(action.message.contains("물체"))
        assertTrue(action.message.contains("사용자 쪽으로 다가오는"))
        return Fixture(
            batch,
            action.copy(validUntilMs = minOf(action.validUntilMs, batch.validUntilElapsedRealtimeMs)),
            policy,
            capturedAtMs,
        )
    }

    private fun report(instrumentation: Instrumentation, message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "UNKNOWN_OBJECT_SPEECH_DEVICE $message\n") })
    }
}
