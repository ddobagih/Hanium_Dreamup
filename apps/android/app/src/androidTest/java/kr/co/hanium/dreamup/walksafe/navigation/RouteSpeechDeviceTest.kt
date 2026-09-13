package kr.co.hanium.dreamup.walksafe.navigation

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
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
 * Route coordinates and route-progress timestamps are synthetic; no location provider is used.
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
                assertTrue(cue.instruction?.contains("전체 경로는 약 333m") == true)
                assertTrue(cue.instruction?.contains("왼쪽으로 꺾으세요") == true)
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

    private fun withReadyActuator(block: (Instrumentation, AndroidFeedbackActuator) -> Unit) {
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
            }
            val readyCallback = initialized.await(25_000L, TimeUnit.MILLISECONDS)
            report(instrumentation, "readiness=${readiness.get()} callback_received=$readyCallback")
            assertTrue("ROUTE_SPEECH_ACTUATOR_INIT_TIMEOUT", readyCallback)
            // An opted-in run with missing Korean TTS is a failed test, never an assumption skip.
            assertEquals("READY", readiness.get())
            block(instrumentation, checkNotNull(actuator))
        } finally {
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
    ) {
        assertTrue("ROUTE_SPEECH_CUE_RESERVATION_REJECTED", navigator.reserveInstruction(cue))
        val result = actuator.speakNavigation(
            message = checkNotNull(cue.instruction),
            requiresExplicitTerminalCallback = true,
            onCompleted = {
                terminal.completed.incrementAndGet()
                navigator.acknowledgeInstruction(cue, spokenAtMs = terminalRouteTimeMs)
                terminal.received.countDown()
            },
            onFailed = {
                terminal.failed.incrementAndGet()
                navigator.releaseInstruction(cue, failedAtMs = terminalRouteTimeMs)
                terminal.received.countDown()
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

        fun assertCompletedOnce() {
            assertEquals("Expected exactly one actual TTS completion", 1, completed.get())
            assertEquals("Unexpected route speech failure", 0, failed.get())
        }

        fun assertFailedOnce() {
            assertEquals("Cancelled route speech must not complete", 0, completed.get())
            assertEquals("Expected exactly one cancellation failure", 1, failed.get())
        }
    }

    private fun update(navigator: RouteNavigator, atMs: Long) = navigator.update(
        location = TrustedLocation(0.0, 0.00055, 2f, atMs),
        nowMs = atMs,
        requestInFlight = false,
    )

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L)).apply {
        setRoute(
            WalkingRoute(
                priority = "STAIR_AVOID",
                summary = WalkingRouteSummary(333, 300),
                polyline = listOf(
                    RoutePoint(0.0, 0.0),
                    RoutePoint(0.0, 0.001),
                    RoutePoint(0.001, 0.001),
                    RoutePoint(0.001, 0.002),
                ),
                guidePoints = listOf(
                    WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0), null, 0, 333, turnType = 200, pointType = "SP"),
                    WalkingRouteGuidePoint(1, RoutePoint(0.0, 0.001), null, 111, 222, turnType = 12),
                    WalkingRouteGuidePoint(2, RoutePoint(0.001, 0.001), null, 222, 111, turnType = 13),
                ),
            ),
        )
    }

    private fun report(instrumentation: Instrumentation, message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "ROUTE_SPEECH_DEVICE $message\n") })
    }
}
