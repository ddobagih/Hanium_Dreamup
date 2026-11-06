package kr.co.hanium.dreamup.walksafe.navigation

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputEvent
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputKind
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputOutcome
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HorizontalUncertaintyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * COMPONENT replay, explicitly opted in with allowRouteJourneyReplayTest=true.
 * Coordinates (equatorial 100 m east, then 100 m north), headings, filtered positions and
 * measurement/decision clocks are synthetic. The replay clock is held during each utterance;
 * only actual offline Korean TTS STARTED/DONE callbacks permit the next replay phase.
 * Default route safety thresholds are retained. Only three selected utterances are played;
 * intermediate progress observations and optional route information are not narrated.
 * No Activity, account, consent, preference, permission or location-provider state is changed.
 * This is not MainActivity E2E, real sensor freshness, microphone recognition, human listening
 * or an outdoor journey. Automatic arrival is committed through the real Navigator API;
 * Activity lifecycle termination after final speech is outside this component replay.
 */
@RunWith(AndroidJUnit4::class)
class RouteJourneyReplayDeviceTest {
    @Test(timeout = 180_000L)
    fun syntheticJourneyCompletesAutomaticArrivalWithActualSpeech() {
        assumeTrue(
            "Requires allowRouteJourneyReplayTest=true because three Korean utterances are played",
            InstrumentationRegistry.getArguments().getString("allowRouteJourneyReplayTest") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val initialized = CountDownLatch(1)
        val readiness = AtomicReference<String?>(null)
        val events = CopyOnWriteArrayList<SpeechOutputEvent>()
        val terminals = mutableListOf<SpeechTerminal>()
        val replay = JourneyReplay()
        var actuator: AndroidFeedbackActuator? = null
        report(instrumentation, "scope=COMPONENT coordinates=SIMULATED heading=SIMULATED " +
            "measurement_clock=SIMULATED decision_clock=SIMULATED main_activity_launched=false " +
            "provider_changed=false human_audibility=NOT_TESTED microphone_asr=NOT_TESTED")
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
                ).also { it.speechOutputListener = { event -> events.add(event) } }
            }
            assertTrue("JOURNEY_TTS_INIT_TIMEOUT", initialized.await(25, TimeUnit.SECONDS))
            assertEquals("Opted-in missing Korean TTS must fail, not skip", "READY", readiness.get())
            val output = checkNotNull(actuator)

            var start: RouteNavigatorUpdate? = null
            instrumentation.runOnMainSync {
                replay.install()
                assertTrue(replay.navigator.hasRoute())
                assertNull(replay.navigator.pendingDecisionToken())
                start = replay.sample(10.0, 0.0, 11_000L, 90.0)
                assertEquals("route_guidance", checkNotNull(start).reason)
                assertTrue(checkNotNull(start).instruction.orEmpty().startsWith("길안내를 시작합니다."))
            }
            terminals += speakGuidance(instrumentation, output, events, replay, checkNotNull(start), "start")

            var turn: RouteNavigatorUpdate? = null
            instrumentation.runOnMainSync {
                // Fresh, corroborated fixes approach the vertex at 1 m/s, without jumping guides.
                for (eastM in 20..90 step 10) replay.sample(eastM.toDouble(), 0.0, eastM * 1_000L + 1_000L, 90.0)
                // Play the approach cue 5 m before the turn. At the exact 100 m vertex,
                // projected progress can be fractionally beyond the integer guide distance;
                // production then correctly withholds the old command pending passage.
                turn = replay.sample(95.0, 0.0, 96_000L, 90.0)
                val cue = checkNotNull(turn)
                assertEquals("route_guidance", cue.reason)
                assertEquals(1, cue.guideIndex)
                assertEquals(
                    "바라보고 있는 방향 기준 약 12시 방향, 다음 안내 지점까지 경로를 따라 약 5미터입니다. 다음 안내 지점에서 경로 기준으로 9시 방향으로 꺾으세요.",
                    cue.instruction,
                )
                assertFalse(cue.arrivalCandidate)
                assertFalse(cue.arrived)
            }
            terminals += speakGuidance(instrumentation, output, events, replay, checkNotNull(turn), "left_turn")

            var question: RouteNavigatorUpdate? = null
            var decisionToken: RouteNavigatorDecisionToken? = null
            instrumentation.runOnMainSync {
                replay.sample(100.0, 0.0, 101_000L, 90.0)
                for (northM in 10..80 step 10) {
                    val progress = replay.sample(100.0, northM.toDouble(), 101_000L + northM * 1_000L, 0.0)
                    assertFalse("Premature arrival at north=$northM", progress.arrivalCandidate)
                    assertFalse(progress.arrived)
                }
                // Endpoint evidence needs the existing full 3 s dwell; no default is relaxed.
                for (atMs in 201_000L..203_000L step 1_000L) {
                    val waiting = replay.sample(100.0, 100.0, atMs, 0.0)
                    assertFalse("Arrival dwell at $atMs: ${waiting.reason}", waiting.arrivalCandidate)
                    assertFalse(waiting.arrived)
                }
                question = replay.sample(100.0, 100.0, 204_000L, 0.0)
                assertEquals("arrival_confirmation_required", checkNotNull(question).reason)
                assertTrue(checkNotNull(question).arrivalCandidate)
                assertFalse(checkNotNull(question).arrived)
                decisionToken = replay.navigator.pendingDecisionToken()
                assertNotNull(decisionToken)
                assertTrue(replay.navigator.hasRoute())
            }
            val token = checkNotNull(decisionToken)
            var end: RouteNavigatorUpdate? = null
            instrumentation.runOnMainSync {
                assertTrue(replay.navigator.hasRoute())
                assertEquals("도착했습니다. 안내를 종료합니다", checkNotNull(question).instruction)
                assertTrue(replay.navigator.canCompleteDetectedArrival(token))
                val stale = replay.navigator.completeDetectedArrival(token.copy(decisionRevision = token.decisionRevision - 1L))
                assertFalse(stale.arrived)
                assertEquals("arrival_confirmation_stale", stale.reason)
                assertTrue(replay.navigator.hasRoute())
                assertEquals(token, replay.navigator.pendingDecisionToken())
                end = replay.navigator.completeDetectedArrival(token)
                assertEquals("arrival_confirmed", checkNotNull(end).reason)
                assertTrue(checkNotNull(end).arrived)
                assertFalse(replay.navigator.hasRoute())
                assertNull(replay.navigator.currentRouteId())
                assertNull(replay.navigator.pendingDecisionToken())
            }
            terminals += speakAndAwait(instrumentation, output, events, "end", SpeechOutputKind.INTERACTION,
                message = checkNotNull(checkNotNull(end).instruction),
                current = { !replay.navigator.hasRoute() && replay.navigator.pendingDecisionToken() == null },
            )
            instrumentation.runOnMainSync {
                assertFalse("An old arrival response cannot finish another journey", replay.navigator.completeDetectedArrival(token).arrived)
                assertFalse(replay.navigator.hasRoute())
                assertNull(replay.navigator.currentGuidance(replay.atMs))
                assertFalse(output.cancelNavigationSpeech())
            }
            report(instrumentation, "journey=AUTOMATIC_ARRIVAL_AND_CLEARED utterances=3 " +
                "actual_started=${events.count { it.outcome == SpeechOutputOutcome.STARTED }} " +
                "actual_done=${events.count { it.outcome == SpeechOutputOutcome.DONE }} outdoor_walk=NOT_TESTED")
        } finally {
            instrumentation.runOnMainSync { actuator?.close() }
        }
        // Late callbacks and close must not acknowledge an already finished phase twice.
        assertEquals(3, terminals.size)
        terminals.forEach { it.assertCompletedOnce() }
        assertEquals(3, events.count { it.outcome == SpeechOutputOutcome.STARTED })
        assertEquals(3, events.count { it.outcome == SpeechOutputOutcome.DONE })
    }

    private fun speakGuidance(
        instrumentation: Instrumentation,
        actuator: AndroidFeedbackActuator,
        events: List<SpeechOutputEvent>,
        replay: JourneyReplay,
        cue: RouteNavigatorUpdate,
        phase: String,
    ): SpeechTerminal {
        instrumentation.runOnMainSync {
            assertTrue("$phase reservation", replay.navigator.reserveInstruction(cue))
            assertFalse("$phase cannot be reserved twice", replay.navigator.reserveInstruction(cue))
        }
        val terminal = speakAndAwait(instrumentation, actuator, events, phase, SpeechOutputKind.NAVIGATION,
            message = checkNotNull(cue.instruction),
            current = { replay.navigator.isInstructionCurrent(cue, replay.atMs, replay.facing) },
            onCompleted = { replay.navigator.acknowledgeInstruction(cue, replay.atMs) },
            onFailed = { replay.navigator.releaseInstruction(cue, replay.atMs) },
        )
        instrumentation.runOnMainSync {
            assertFalse("$phase actual completion consumed its cue", replay.navigator.reserveInstruction(cue))
            // A pending reservation also rejects reserveInstruction, so prove the DONE ACK
            // consumed this cue while its synthetic evidence and clock are still unchanged.
            assertFalse("$phase actual completion invalidated its cue",
                replay.navigator.isInstructionCurrent(cue, replay.atMs, replay.facing))
            assertTrue(replay.navigator.hasRoute())
        }
        return terminal
    }

    private fun speakAndAwait(
        instrumentation: Instrumentation,
        actuator: AndroidFeedbackActuator,
        events: List<SpeechOutputEvent>,
        phase: String,
        kind: SpeechOutputKind,
        message: String,
        current: () -> Boolean,
        onCompleted: () -> Unit = {},
        onFailed: () -> Unit = {},
    ): SpeechTerminal {
        val terminal = SpeechTerminal()
        val firstEvent = events.size
        instrumentation.runOnMainSync {
            val completed: () -> Unit = {
                onCompleted()
                terminal.completed.incrementAndGet()
                terminal.received.countDown()
            }
            val unsuccessful: () -> Unit = {
                onFailed()
                terminal.failed.incrementAndGet()
                terminal.received.countDown()
            }
            val result = if (kind == SpeechOutputKind.NAVIGATION) {
                actuator.speakNavigation(message, completed, unsuccessful,
                    requiresExplicitTerminalCallback = true, onCancelled = unsuccessful, isStillValidAtStart = current)
            } else {
                actuator.speakGuardedInteraction(message, current, completed, unsuccessful, unsuccessful)
            }
            assertEquals("$phase dispatch", NavigationSpeechDispatchResult.ACCEPTED, result)
            // Engine callbacks are posted to this occupied main looper; enqueue is not DONE.
            assertEquals(0, terminal.completed.get())
        }
        val received = terminal.received.await(30, TimeUnit.SECONDS)
        report(instrumentation, "phase=$phase callback_received=$received " +
            "actual_onDone=${terminal.completed.get()} failures=${terminal.failed.get()}")
        assertTrue("JOURNEY_SPEECH_TIMEOUT: $phase", received)
        terminal.assertCompletedOnce()
        instrumentation.runOnMainSync {
            val phaseEvents = events.drop(firstEvent)
            assertTrue("$phase output channel", phaseEvents.all { it.kind == kind })
            assertEquals("$phase must start then finish exactly once, with no cancellation/error",
                listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE),
                phaseEvents.map { it.outcome })
            assertTrue("$phase actual callback chronology",
                phaseEvents.zipWithNext().all { (first, second) -> first.atElapsedRealtimeMs <= second.atElapsedRealtimeMs })
        }
        return terminal
    }

    private class SpeechTerminal {
        val received = CountDownLatch(1)
        val completed = AtomicInteger()
        val failed = AtomicInteger()

        fun assertCompletedOnce() {
            assertEquals("Exactly one real terminal completion", 1, completed.get())
            assertEquals("No failure/cancellation may advance the replay", 0, failed.get())
        }
    }

    private class JourneyReplay {
        var atMs = 1_000L
            private set
        var facing = RouteFacingObservation(90.0, 5.0, atMs)
            private set
        val navigator = RouteNavigator(nowElapsedRealtimeMs = { atMs })

        fun install() {
            navigator.setRoute(WalkingRoute("STAIR_AVOID", WalkingRouteSummary(200, 200),
                listOf(point(0.0, 0.0), point(100.0, 0.0), point(100.0, 100.0)),
                listOf(
                    WalkingRouteGuidePoint(0, point(0.0, 0.0), null, 0, 200, turnType = 200, pointType = "SP"),
                    WalkingRouteGuidePoint(1, point(100.0, 0.0), null, 100, 100, turnType = 12),
                )), destination = point(100.0, 100.0), origin = fix(0.0, 0.0))
        }

        fun sample(eastM: Double, northM: Double, timeMs: Long, headingDeg: Double): RouteNavigatorUpdate {
            atMs = timeMs
            facing = RouteFacingObservation(headingDeg, 5.0, atMs)
            val location = fix(eastM, northM)
            val update = navigator.update(location, atMs, false,
                filteredPosition = FilteredRoutePosition(point(eastM, northM), 3.0, atMs,
                    uncertaintyKind = HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA),
                facingObservation = facing)
            assertNotNull("Synthetic on-route fix must pass production match gate at $atMs: ${update.reason}",
                navigator.currentAcceptedRouteMatchFor(atMs))
            assertFalse("Unexpected deviation at $atMs: ${update.reason}", update.offRoute)
            assertFalse("Unexpected reroute at $atMs: ${update.reason}", update.shouldReroute)
            return update
        }

        private fun fix(eastM: Double, northM: Double) = point(eastM, northM).let {
            TrustedLocation(it.latitude, it.longitude, 3f, atMs, HorizontalUncertaintyKind.ANDROID_RADIUS_68)
        }

        private fun point(eastM: Double, northM: Double) =
            RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)
    }

    private fun report(instrumentation: Instrumentation, message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "ROUTE_JOURNEY_REPLAY $message\n") })
    }
}
