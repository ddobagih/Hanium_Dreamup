package kr.co.hanium.dreamup.walksafe.navigation

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputEvent
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputKind
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with allowRouteSpeechTest=true: this component test plays actual offline Korean TTS.
 * Route coordinates, camera-facing observations and route-progress timestamps are synthetic;
 * no location or orientation provider is used. Risk actions are controlled test inputs, and
 * deferred-status retries are caller-controlled rather than MainActivity's scheduling.
 * It does not launch MainActivity or change accounts, preferences, permissions, or device settings.
 * A strict actuator completion verifies its TTS onDone path, not audibility or walking/Main E2E.
 */
@RunWith(AndroidJUnit4::class)
class RouteSpeechDeviceTest {
    @Test(timeout = 90_000L)
    fun simulatedRouteCueIsConsumedOnlyAfterActualKoreanSpeechCompletes() {
        val terminal = SpeechTerminal()
        withReadyActuator { instrumentation, actuator ->
            val navigator = navigator()
            instrumentation.runOnMainSync {
                val cue = update(navigator, 1_000L)
                assertEquals("route_guidance", cue.reason)
                assertEquals(1, cue.guideIndex)
                assertFalse(cue.instruction?.contains("전체 경로는 약 333m") == true)
                assertTrue(cue.infoUtterance?.message?.contains("전체 경로는 약 333m") == true)
                assertTrue(cue.instruction?.contains("9시 방향으로 꺾으세요") == true)
                dispatch(actuator, navigator, cue, terminal, terminalRouteTimeMs = 2_000L)

                // Android TTS callbacks are posted to this main looper, which is still occupied.
                assertEquals(0, terminal.completed.get())
                assertEquals(0, terminal.failed.get())
                assertFalse(navigator.reserveInstruction(cue))
                val pending = update(navigator, 1_100L)
                assertEquals("guidance_in_flight", pending.reason)
                assertNull(pending.instruction)
            }

            awaitTerminal(instrumentation, "route_completion", terminal)
            terminal.assertCompletedOnce()
            instrumentation.runOnMainSync {
                val duplicate = update(navigator, 10_000L)
                assertEquals("guidance_already_completed", duplicate.reason)
                assertNull(duplicate.instruction)
                assertFalse(actuator.cancelNavigationSpeech())
            }
        }
        terminal.assertCompletedOnce()
    }

    @Test(timeout = 90_000L)
    fun cancelledRouteCueFailsOnceAndItsRetryCompletesActualKoreanSpeech() {
        val cancelled = SpeechTerminal()
        val retryTerminal = SpeechTerminal()
        withReadyActuator { instrumentation, actuator ->
            val navigator = navigator()
            var originalCue: RouteNavigatorUpdate? = null
            instrumentation.runOnMainSync {
                val cue = update(navigator, 1_000L)
                originalCue = cue
                dispatch(actuator, navigator, cue, cancelled, terminalRouteTimeMs = 2_000L)

                // Cancel in the same main-loop turn, before an engine callback can complete it.
                // This asserts the actuator's cancellation terminal, not receipt of engine onStop.
                assertTrue(actuator.cancelNavigationSpeech())
                assertFalse(actuator.cancelNavigationSpeech())
            }
            awaitTerminal(instrumentation, "route_cancel", cancelled)
            cancelled.assertFailedOnce()

            instrumentation.runOnMainSync {
                val waiting = update(navigator, 2_500L)
                assertEquals("guidance_retry_wait", waiting.reason)
                assertNull(waiting.instruction)

                // Only the route clock advances; the actuator retries without an artificial sleep.
                val retry = update(navigator, 5_000L)
                assertEquals("route_guidance", retry.reason)
                assertEquals(checkNotNull(originalCue).instruction, retry.instruction)
                assertNotEquals(checkNotNull(originalCue).speechCueToken, retry.speechCueToken)
                dispatch(actuator, navigator, retry, retryTerminal, terminalRouteTimeMs = 6_000L)
                assertEquals("guidance_in_flight", update(navigator, 5_100L).reason)
            }
            awaitTerminal(instrumentation, "route_retry", retryTerminal)
            retryTerminal.assertCompletedOnce()
            instrumentation.runOnMainSync {
                val duplicate = update(navigator, 10_000L)
                assertEquals("guidance_already_completed", duplicate.reason)
                assertNull(duplicate.instruction)
                assertFalse(actuator.cancelNavigationSpeech())
            }
        }
        // The old engine callback and teardown must not deliver a second terminal for cancellation.
        cancelled.assertFailedOnce()
        retryTerminal.assertCompletedOnce()
    }

    @Test(timeout = 90_000L)
    fun automaticStatusAndNonurgentCautionDoNotInterruptAnActuallyStartedRouteCue() {
        val routeTerminal = SpeechTerminal()
        val statusTerminal = SpeechTerminal()
        val cautionTerminal = SpeechTerminal()
        val trace = SpeechTrace()
        withReadyActuator(trace) { instrumentation, actuator ->
            val navigator = navigator()
            instrumentation.runOnMainSync {
                dispatch(actuator, navigator, update(navigator, 1_000L), routeTerminal, 2_000L)
            }
            awaitOutput(instrumentation, trace, SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.STARTED)
            instrumentation.runOnMainSync {
                assertEquals("Route must still be playing when the competing notices arrive", 0, routeTerminal.completed.get())
                assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, dispatchStatus(actuator, statusTerminal))
                val result = dispatchRisk(actuator, MessageLevel.CAUTION, cautionTerminal)
                assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, result)
                assertEquals(0, routeTerminal.failed.get())
                assertEquals("guidance_in_flight", update(navigator, 1_100L).reason)
            }
            awaitTerminal(instrumentation, "route_uninterrupted", routeTerminal)
            routeTerminal.assertCompletedOnce()
            assertEquals(0, trace.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.CANCELLED_BY_PRIORITY))
            assertEquals(0, trace.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.STOPPED))
            assertEquals(0, trace.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.INTERRUPTED))
            assertEquals(0, statusTerminal.completed.get())
            assertEquals(0, statusTerminal.failed.get())
            assertEquals(0, cautionTerminal.completed.get())
            assertEquals(0, cautionTerminal.failed.get())

            // Retry is deliberately caller-controlled here; Main's scheduling is not exercised.
            instrumentation.runOnMainSync {
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatchStatus(actuator, statusTerminal))
            }
            awaitTerminal(instrumentation, "status_after_route", statusTerminal)
            statusTerminal.assertCompletedOnce()
            trace.assertOrderedNativeCompletion(SpeechOutputKind.NAVIGATION)
            trace.assertOrderedNativeCompletion(SpeechOutputKind.INTERACTION)
            trace.assertNoEngineFailure()
            instrumentation.runOnMainSync {
                assertEquals("guidance_already_completed", update(navigator, 10_000L).reason)
            }
            report(instrumentation, "scenario=status_and_caution_during_route route_native_done=true status_native_done=true status_retry=CALLER_CONTROLLED")
        }
        routeTerminal.assertCompletedOnce()
        statusTerminal.assertCompletedOnce()
    }

    @Test(timeout = 90_000L)
    fun urgentWarningPreemptsActuallyStartedRouteAndItsCurrentRetryCompletes() {
        verifyUrgentPreemption(MessageLevel.WARNING, replaceRoute = false)
    }

    @Test(timeout = 90_000L)
    fun urgentStopPreemptsActuallyStartedRouteAndOnlyReplacementRouteCompletes() {
        verifyUrgentPreemption(MessageLevel.STOP, replaceRoute = true)
    }

    @Test(timeout = 90_000L)
    fun actualLateDoneFromReplacedRouteCannotConsumeTheNewReservedCue() {
        val oldTerminal = SpeechTerminal()
        val replacementTerminal = SpeechTerminal()
        val trace = SpeechTrace()
        withReadyActuator(trace) { instrumentation, actuator ->
            val navigator = navigator()
            lateinit var oldCue: RouteNavigatorUpdate
            lateinit var replacement: RouteNavigatorUpdate
            instrumentation.runOnMainSync {
                oldCue = update(navigator, 1_000L)
                dispatch(actuator, navigator, oldCue, oldTerminal, 3_000L)
            }
            awaitOutput(instrumentation, trace, SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.STARTED)
            instrumentation.runOnMainSync {
                assertEquals(0, oldTerminal.completed.get())
                // Deliberately delay output cancellation after a route replacement. The old native
                // utterance is allowed to really finish; no synthetic onDone is sent to the engine.
                navigator.setRoute(route(replacement = true))
                replacement = update(navigator, 4_000L)
                assertNotEquals(oldCue.speechCueToken, replacement.speechCueToken)
                assertTrue(navigator.reserveInstruction(replacement))
                assertFalse(navigator.isInstructionCompletionCurrent(oldCue, 4_001L))
                assertTrue(navigator.isInstructionCompletionCurrent(replacement, 4_001L))
            }
            awaitTerminal(instrumentation, "replaced_route_actual_late_done", oldTerminal)
            oldTerminal.assertCompletedOnce()
            instrumentation.runOnMainSync {
                assertEquals("guidance_in_flight", update(navigator, 5_000L).reason)
                assertTrue(navigator.isInstructionCompletionCurrent(replacement, 5_001L))
                dispatch(actuator, navigator, replacement, replacementTerminal, 6_000L, alreadyReserved = true)
                assertEquals(0, replacementTerminal.completed.get())
            }
            awaitTerminal(instrumentation, "replacement_route_completion", replacementTerminal)
            replacementTerminal.assertCompletedOnce()
            trace.assertOrderedNativeCompletion(SpeechOutputKind.NAVIGATION, expectedCompletions = 2)
            trace.assertNoEngineFailure()
            instrumentation.runOnMainSync {
                assertEquals("guidance_already_completed", update(navigator, 10_000L).reason)
            }
            report(instrumentation, "scenario=route_replaced_during_native_speech late_done_source=ACTUAL_NATIVE old_done_consumed_new_cue=false replacement_native_done=true")
        }
        oldTerminal.assertCompletedOnce()
        replacementTerminal.assertCompletedOnce()
    }

    private fun verifyUrgentPreemption(level: MessageLevel, replaceRoute: Boolean) {
        val cancelled = SpeechTerminal()
        val riskTerminal = SpeechTerminal()
        val retryTerminal = SpeechTerminal()
        val trace = SpeechTrace()
        withReadyActuator(trace) { instrumentation, actuator ->
            val navigator = navigator()
            lateinit var original: RouteNavigatorUpdate
            instrumentation.runOnMainSync {
                original = update(navigator, 1_000L)
                dispatch(actuator, navigator, original, cancelled, 2_000L)
            }
            awaitOutput(instrumentation, trace, SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.STARTED)
            instrumentation.runOnMainSync {
                assertEquals("Preemption must occur before the route naturally finishes", 0, cancelled.completed.get())
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatchRisk(actuator, level, riskTerminal))
            }
            awaitTerminal(instrumentation, "route_preempted_by_${level.name}", cancelled)
            cancelled.assertFailedOnce()
            assertEquals("Preemption uses cancellation, not an engine error", 1, cancelled.cancelled.get())
            awaitTerminal(instrumentation, "urgent_${level.name}", riskTerminal)
            riskTerminal.assertCompletedOnce()
            trace.assertOrderedNativeCompletion(SpeechOutputKind.RISK)
            assertEquals(1, trace.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.CANCELLED_BY_PRIORITY))
            instrumentation.runOnMainSync {
                if (replaceRoute) navigator.setRoute(route(replacement = true))
                val retry = update(navigator, 5_000L)
                assertEquals("route_guidance", retry.reason)
                assertNotEquals(original.speechCueToken, retry.speechCueToken)
                if (replaceRoute) {
                    assertNotEquals(original.instruction, retry.instruction)
                    assertFalse(navigator.reserveInstruction(original))
                } else {
                    assertEquals(original.instruction, retry.instruction)
                }
                // Controlled navigator-boundary fault, explicitly distinct from native callbacks.
                navigator.acknowledgeInstruction(original, spokenAtMs = 5_001L)
                dispatch(actuator, navigator, retry, retryTerminal, 6_000L)
                navigator.acknowledgeInstruction(original, spokenAtMs = 5_002L)
                navigator.releaseInstruction(original, failedAtMs = 5_003L)
                assertEquals("guidance_in_flight", update(navigator, 5_100L).reason)
            }
            awaitTerminal(instrumentation, "route_after_${level.name}", retryTerminal)
            retryTerminal.assertCompletedOnce()
            assertEquals(0, cancelled.completed.get())
            assertEquals(1, cancelled.failed.get())
            assertEquals(2, trace.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.STARTED))
            assertEquals(1, trace.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE))
            trace.assertNoEngineFailure()
            instrumentation.runOnMainSync {
                assertEquals("guidance_already_completed", update(navigator, 10_000L).reason)
                assertFalse(actuator.cancelNavigationSpeech())
            }
            report(instrumentation, "scenario=urgent_preemption level=${level.name} replacement_route=$replaceRoute late_ack=CONTROLLED_NAVIGATOR_BOUNDARY native_stop_ack=NOT_EXPOSED current_route_native_done=true")
        }
        cancelled.assertFailedOnce()
        riskTerminal.assertCompletedOnce()
        retryTerminal.assertCompletedOnce()
    }

    private fun dispatchStatus(actuator: AndroidFeedbackActuator, terminal: SpeechTerminal) =
        actuator.speakGuardedHomeCommandInteraction(
            message = "위치 정보를 다시 확인하고 있습니다. 잠시 기다려 주세요.",
            isStillValidAtStart = { true },
            onCompleted = terminal::complete,
            onFailed = terminal::fail,
            onCancelled = terminal::cancel,
            deferToCurrentSpeech = true,
        )

    private fun dispatchRisk(actuator: AndroidFeedbackActuator, level: MessageLevel, terminal: SpeechTerminal) =
        actuator.emit(
            action = FeedbackAction(
                trackId = "controlled-route-speech-${level.name}",
                message = if (level == MessageLevel.STOP) "멈추세요. 바로 앞에 장애물이 있습니다." else "앞에 장애물이 있습니다. 주의하세요.",
                level = level,
                vibrationPatternMs = null,
                reason = "CONTROLLED_DEVICE_SPEECH_SCENARIO",
                validUntilMs = SystemClock.elapsedRealtime() + 10_000L,
            ),
            onSpeechCompleted = terminal::complete,
            onSpeechFailed = terminal::fail,
            onSpeechCancelled = terminal::cancel,
            isStillValidAtStart = { true },
        ).speech

    private fun withReadyActuator(trace: SpeechTrace? = null, block: (Instrumentation, AndroidFeedbackActuator) -> Unit) {
        assumeTrue(
            "Requires explicit allowRouteSpeechTest=true because this test plays Korean speech",
            InstrumentationRegistry.getArguments().getString("allowRouteSpeechTest") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val initialized = CountDownLatch(1)
        val readiness = AtomicReference<String?>(null)
        var actuator: AndroidFeedbackActuator? = null
        report(instrumentation, "scope=COMPONENT route=SIMULATED route_clock=SIMULATED main_activity_launched=false human_audibility=NOT_TESTED")
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
                actuator?.speechOutputListener = trace?.let { recorder -> recorder::record }
            }
            val readyCallback = initialized.await(25_000L, TimeUnit.MILLISECONDS)
            report(instrumentation, "readiness=${readiness.get()} callback_received=$readyCallback")
            assertTrue("ROUTE_SPEECH_ACTUATOR_INIT_TIMEOUT", readyCallback)
            // An opted-in run with missing Korean TTS is a failed test, never an assumption skip.
            assertEquals("READY", readiness.get())
            block(instrumentation, checkNotNull(actuator))
        } finally {
            trace?.let { report(instrumentation, "native_owned_output=${it.summary()} native_stop_ack=NOT_EXPOSED") }
            instrumentation.runOnMainSync { actuator?.close() }
        }
    }

    /** Call only on the main thread; reservation alone never acknowledges delivery. */
    private fun dispatch(
        actuator: AndroidFeedbackActuator,
        navigator: RouteNavigator,
        cue: RouteNavigatorUpdate,
        terminal: SpeechTerminal,
        terminalRouteTimeMs: Long,
        alreadyReserved: Boolean = false,
    ) {
        if (!alreadyReserved) assertTrue("ROUTE_SPEECH_CUE_RESERVATION_REJECTED", navigator.reserveInstruction(cue))
        val unsuccessful: () -> Unit = {
            terminal.failed.incrementAndGet()
            navigator.releaseInstruction(cue, failedAtMs = terminalRouteTimeMs)
            terminal.received.countDown()
        }
        val result = actuator.speakNavigation(
            message = checkNotNull(cue.instruction),
            requiresExplicitTerminalCallback = true,
            onCompleted = {
                terminal.completed.incrementAndGet()
                navigator.acknowledgeInstruction(cue, spokenAtMs = terminalRouteTimeMs)
                terminal.received.countDown()
            },
            onFailed = unsuccessful,
            // Match Main's explicit cancellation consumer; cancellation is not an engine error.
            onCancelled = {
                terminal.cancelled.incrementAndGet()
                unsuccessful()
            },
        )
        if (result != NavigationSpeechDispatchResult.ACCEPTED) {
            navigator.releaseInstruction(cue, failedAtMs = terminalRouteTimeMs)
        }
        assertEquals("ROUTE_SPEECH_DISPATCH_REJECTED", NavigationSpeechDispatchResult.ACCEPTED, result)
    }

    private fun awaitTerminal(instrumentation: Instrumentation, label: String, terminal: SpeechTerminal) {
        val received = terminal.received.await(25_000L, TimeUnit.MILLISECONDS)
        report(instrumentation, "$label callback_received=$received actual_onDone=${terminal.completed.get()} failures=${terminal.failed.get()} human_audibility=NOT_TESTED")
        assertTrue("ROUTE_SPEECH_TERMINAL_TIMEOUT: $label", received)
    }

    private class SpeechTerminal {
        val received = CountDownLatch(1)
        val completed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val cancelled = AtomicInteger(0)

        fun complete() {
            completed.incrementAndGet()
            received.countDown()
        }

        fun fail() {
            failed.incrementAndGet()
            received.countDown()
        }

        fun cancel() {
            cancelled.incrementAndGet()
            fail()
        }

        fun assertCompletedOnce() {
            assertEquals("Expected exactly one actual TTS completion", 1, completed.get())
            assertEquals("Unexpected route speech failure", 0, failed.get())
        }

        fun assertFailedOnce() {
            assertEquals("Cancelled route speech must not complete", 0, completed.get())
            assertEquals("Expected exactly one cancellation failure", 1, failed.get())
        }
    }

    private fun awaitOutput(instrumentation: Instrumentation, trace: SpeechTrace, kind: SpeechOutputKind, outcome: SpeechOutputOutcome) {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (trace.count(kind, outcome) == 0 && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(25L)
        }
        report(instrumentation, "await_output kind=$kind outcome=$outcome observed=${trace.count(kind, outcome)}")
        assertTrue("ROUTE_SPEECH_NATIVE_EVENT_TIMEOUT: $kind/$outcome ${trace.summary()}", trace.count(kind, outcome) > 0)
    }

    private class SpeechTrace {
        private val events = mutableListOf<SpeechOutputEvent>()

        @Synchronized
        fun record(event: SpeechOutputEvent) { events += event }

        @Synchronized
        fun count(kind: SpeechOutputKind, outcome: SpeechOutputOutcome) =
            events.count { it.kind == kind && it.outcome == outcome }

        @Synchronized
        fun assertOrderedNativeCompletion(kind: SpeechOutputKind, expectedCompletions: Int = 1) {
            val native = events.filter { it.kind == kind && it.outcome in setOf(SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE) }
            assertEquals(List(expectedCompletions) { listOf(SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE) }.flatten(), native.map { it.outcome })
        }

        @Synchronized
        fun assertNoEngineFailure() {
            assertFalse("Unexpected native TTS failure: ${summary()}", events.any {
                it.outcome in setOf(SpeechOutputOutcome.ENGINE_ERROR, SpeechOutputOutcome.TERMINAL_TIMEOUT, SpeechOutputOutcome.FOCUS_DENIED, SpeechOutputOutcome.FOCUS_LOST)
            })
        }

        @Synchronized
        fun summary(): String = events.joinToString(",") { "${it.kind}.${it.outcome}@${it.atElapsedRealtimeMs}" }
    }

    private fun update(navigator: RouteNavigator, atMs: Long) = navigator.update(
        location = TrustedLocation(0.0, 0.00055, 2f, atMs),
        nowMs = atMs,
        requestInFlight = false,
        facingObservation = RouteFacingObservation(90.0, 5.0, atMs),
    )

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L)).apply {
        setRoute(route())
    }

    private fun route(replacement: Boolean = false) = WalkingRoute(
        priority = "STAIR_AVOID",
        providerRouteId = if (replacement) "controlled-replacement-route" else "controlled-original-route",
        summary = WalkingRouteSummary(333, 300),
        polyline = listOf(
            RoutePoint(0.0, 0.0),
            RoutePoint(0.0, 0.001),
            RoutePoint(if (replacement) -0.001 else 0.001, 0.001),
            RoutePoint(if (replacement) -0.001 else 0.001, 0.002),
        ),
        guidePoints = listOf(
            WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0), null, 0, 333, turnType = 200, pointType = "SP"),
            WalkingRouteGuidePoint(1, RoutePoint(0.0, 0.001), null, 111, 222, turnType = if (replacement) 13 else 12),
            WalkingRouteGuidePoint(2, RoutePoint(if (replacement) -0.001 else 0.001, 0.001), null, 222, 111, turnType = if (replacement) 12 else 13),
        ),
    )

    private fun report(instrumentation: Instrumentation, message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "ROUTE_SPEECH_DEVICE $message\n") })
    }
}
