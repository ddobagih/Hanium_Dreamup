package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingObservation
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorConfig
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteGuidePoint
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the actual actuator with deterministic platform effects, not a second speech policy. */
class AndroidFeedbackActuatorRuntimeRegressionTest {
    @Test
    fun longPracticeKeepsEverySentenceAndCompletesOnlyAfterAllChunks() {
        val message = kr.co.hanium.dreamup.walksafe.session.PriorityUserEducationPresentation.speechText(
            kr.co.hanium.dreamup.walksafe.session.PriorityUserEducationPlayback.APP_USAGE,
        )
        val chunks = trainingSpeechChunks(message)
        assertTrue(chunks.size > 1)
        assertEquals(message, chunks.joinToString(" "))
        assertTrue(chunks.all { it.length <= 240 && it.endsWith(".") })
        val (runtime, actuator) = readyActuator()
        val events = Events()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actuator.speakPriorityUserTraining(
            message, { events.completed++ }, { events.failed++ },
        ))
        var elapsed = 0L
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index + 1, runtime.spoken.size)
            assertEquals(chunk, runtime.spoken.last().message)
            assertEquals(0, events.completed)
            val id = runtime.spoken.last().id
            runtime.start(id)
            val duration = utteranceTerminalTimeoutMs(chunk.length) - 1
            runtime.advanceBy(duration)
            elapsed += duration
            runtime.done(id)
        }
        assertTrue(elapsed > 120_000L)
        assertEquals(1, events.completed)
        assertEquals(0, events.failed)
        assertFalse(actuator.isAppSpeechActive())
    }

    @Test
    fun cancelledPracticeDoesNotStartLaterChunksOrCompleteOnLateDone() {
        val (runtime, actuator) = readyActuator()
        var completed = 0
        var failed = 0
        val message = "첫 번째 설명입니다. ".repeat(30) + "마지막 설명입니다."
        actuator.speakPriorityUserTraining(message, { completed++ }, { failed++ })
        val id = runtime.spoken.single().id
        runtime.start(id)
        actuator.cancelPriorityUserTrainingFeedback()
        runtime.done(id)
        assertEquals(1, runtime.spoken.size)
        assertEquals(0, completed)
        assertEquals(1, failed)
    }

    @Test
    fun failedPracticeNeedsExplicitRetryAndDoesNotResumeTheOldReading() {
        val (runtime, actuator) = readyActuator()
        var completed = 0
        var failed = 0
        actuator.speakPriorityUserTraining("첫 설명입니다. ".repeat(30), { completed++ }, { failed++ })
        val old = runtime.spoken.single().id
        runtime.start(old)
        runtime.error(old, TextToSpeech.ERROR_SERVICE)
        runtime.advanceBy(150_000L)
        assertEquals("tts=init_failed", actuator.statusText())
        assertEquals(1, runtime.engines.size)
        assertEquals(1, failed)
        assertEquals(0, completed)
        actuator.retryFailedSpeechInitialization()
        actuator.retryFailedSpeechInitialization()
        assertEquals(2, runtime.engines.size)
        runtime.ready()
        runtime.done(old)
        assertEquals(1, runtime.spoken.size)
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actuator.speakPriorityUserTraining(
            "명시적으로 다시 듣습니다.", { completed++ }, { failed++ },
        ))
        runtime.start(runtime.spoken.last().id)
        runtime.done(runtime.spoken.last().id)
        assertEquals(1, completed)
        assertEquals(1, failed)
    }

    @Test
    fun initializingFalseNeverReplaysAnUnownedInteraction() {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime)
        assertFalse(actuator.speakInteraction("old screen"))
        assertFalse(actuator.isSpeechRecognitionBlocked())

        runtime.ready()
        assertTrue(runtime.spoken.isEmpty())
        assertTrue(actuator.speakInteraction("current screen"))
        assertEquals(listOf("current screen"), runtime.spoken.map { it.message })
    }

    @Test
    fun initializationCallbackAfterCloseCannotReplayOrBecomeReady() {
        val runtime = FakeRuntime()
        var ready = 0
        val actuator = AndroidFeedbackActuator(runtime = runtime, onOfflineKoreanSpeechReady = { ready += 1 })
        assertFalse(actuator.speakInteraction("rejected"))
        actuator.close()
        runtime.ready()
        runtime.advanceBy(20_500L)
        assertEquals("tts=closed", actuator.statusText())
        assertEquals(0, ready)
        assertTrue(runtime.spoken.isEmpty())
    }

    @Test
    fun validStartTimeoutSharesTheExistingSingleRecoveryBudget() {
        val (runtime, actuator) = readyActuator(TtsEngineRecoveryBudget(automaticRetries = 1))
        val first = Events()
        acceptNavigation(actuator, "first", first)
        runtime.advanceBy(8_000L)
        assertEquals("tts=initializing", actuator.statusText())
        assertEquals(1, first.failed)
        assertEquals(0, first.cancelled)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, actuator.speakNavigation("during recovery"))

        runtime.advanceBy(250L)
        assertEquals(2, runtime.engines.size)
        runtime.ready()
        val second = Events()
        acceptNavigation(actuator, "second", second)
        runtime.advanceBy(8_000L)
        assertEquals("tts=init_failed", actuator.statusText())
        assertEquals(1, second.failed)
        assertFalse(actuator.isAppSpeechActive())
        runtime.advanceBy(20_500L)
        assertEquals(2, runtime.engines.size)
    }

    @Test
    fun missingDoneAfterRealStartAlsoDegradesReadiness() {
        val (runtime, actuator) = readyActuator()
        val events = Events()
        val message = "terminal watchdog"
        acceptNavigation(actuator, message, events)
        runtime.start(runtime.spoken.last().id)
        runtime.advanceBy(utteranceTerminalTimeoutMs(message.length))
        // S13-12: interim budget — the first engine failure is final.
        assertEquals("tts=init_failed", actuator.statusText())
        assertEquals(1, events.failed)
        assertEquals(0, events.completed)
        assertFalse(actuator.isAppSpeechActive())
    }

    @Test
    fun revokedOwnerCancelsWithoutConsumingEngineRecovery() {
        val (runtime, actuator) = readyActuator(TtsEngineRecoveryBudget(automaticRetries = 1))
        var current = true
        val old = Events()
        acceptNavigation(actuator, "revoked", old) { current }
        current = false
        runtime.advanceBy(8_000L)
        assertEquals(1, old.cancelled)
        assertEquals(0, old.failed)
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, runtime.engines.size)

        val next = Events()
        acceptNavigation(actuator, "valid", next)
        runtime.advanceBy(8_000L)
        assertEquals("tts=initializing", actuator.statusText())
        runtime.advanceBy(250L)
        assertEquals(2, runtime.engines.size)
    }

    @Test
    fun sourceDeadlineIsInclusiveAndThenCancelsWithoutEngineFailure() {
        val (runtime, actuator) = readyActuator()
        val events = Events()
        val action = riskAction(runtime.now + 100L)
        val dispatch = actuator.emit(
            action = action,
            isStillValidAtStart = { true },
            onSpeechCompleted = { events.completed += 1 },
            onSpeechFailed = { events.failed += 1 },
            onSpeechCancelled = { events.cancelled += 1 },
        )
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch.speech)
        assertFalse(dispatch.vibrationAccepted)
        runtime.advanceBy(100L)
        assertTrue(actuator.isAppSpeechActive())
        runtime.advanceBy(1L)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(1, events.cancelled)
        assertEquals(0, events.failed)
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun falseRiskValidatorSuppressesSpeechAndVibrationBeforeDispatch() {
        val (runtime, actuator) = readyActuator()
        var validationCalls = 0
        val dispatch = actuator.emit(
            action = riskAction(runtime.now + 20_000L),
            onSpeechCompleted = null,
            onSpeechFailed = null,
            isStillValidAtStart = { validationCalls += 1; false },
        )

        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, dispatch.speech)
        assertFalse(dispatch.vibrationAccepted)
        assertEquals(1, validationCalls)
        assertTrue(runtime.spoken.isEmpty())
        assertFalse(actuator.isAppSpeechActive())
    }

    @Test
    fun startedRiskKeepsCompletionOwnershipAfterSourceDeadline() {
        val (runtime, actuator) = readyActuator()
        val events = Events()
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.emit(
                action = riskAction(runtime.now + 100L),
                isStillValidAtStart = { true },
                onSpeechCompleted = { events.completed += 1 },
                onSpeechFailed = { events.failed += 1 },
                onSpeechCancelled = { events.cancelled += 1 },
            ).speech,
        )
        val id = runtime.spoken.last().id
        runtime.start(id)
        runtime.advanceBy(101L)
        assertTrue(actuator.isAppSpeechActive())
        runtime.done(id)
        assertEquals(1, events.completed)
        assertEquals(0, events.cancelled)
        assertEquals(0, events.failed)
        assertEquals("tts=ready", actuator.statusText())
    }

    @Test
    fun explicitCancellationFencesLateDoneErrorAndWatchdogFromReplacement() {
        val (runtime, actuator) = readyActuator()
        val old = Events()
        acceptNavigation(actuator, "old route", old)
        val oldId = runtime.spoken.last().id
        assertTrue(actuator.cancelNavigationSpeech())
        assertEquals(1, old.cancelled)

        val replacement = Events()
        acceptNavigation(actuator, "new route", replacement)
        val replacementId = runtime.spoken.last().id
        runtime.done(oldId)
        runtime.error(oldId, TextToSpeech.ERROR_SERVICE)
        assertTrue(actuator.isAppSpeechActive())
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(0, old.completed)
        assertEquals(0, old.failed)
        runtime.start(replacementId)
        runtime.done(replacementId)
        runtime.advanceBy(8_000L)
        assertEquals(1, replacement.completed)
        assertEquals(0, replacement.failed)
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun guideOvershootCancelsQueuedTurnOnceWithoutAdvancingUntilPassThreshold() {
        fun point(eastM: Double) = RoutePoint(0.0, eastM / 111194.92664455874)
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L)).apply {
            setRoute(
                WalkingRoute(
                    priority = "TEST",
                    summary = WalkingRouteSummary(200, 200),
                    polyline = listOf(point(0.0), point(200.0)),
                    guidePoints = listOf(
                        WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 13),
                        WalkingRouteGuidePoint(1, point(160.0), null, 160, 40, turnType = 12),
                    ),
                ),
            )
        }
        fun guidanceAt(eastM: Double, atMs: Long) = point(eastM).let { position ->
            navigator.update(
                TrustedLocation(position.latitude, position.longitude, 5f, atMs),
                nowMs = atMs,
                requestInFlight = false,
                facingObservation = RouteFacingObservation(90.0, 5.0, atMs),
            )
        }
        val (runtime, actuator) = readyActuator()
        val old = guidanceAt(99.0, 1_000L)
        assertEquals(0, old.guideIndex)
        assertTrue(old.instruction?.contains("꺾으세요") == true)
        assertTrue(navigator.reserveInstruction(old))
        val oldEvents = Events()
        acceptNavigation(actuator, requireNotNull(old.instruction), oldEvents)
        val oldId = runtime.spoken.last().id

        val neutral = guidanceAt(101.0, 2_000L)
        assertEquals(0, neutral.guideIndex)
        assertEquals("route_guidance", neutral.reason)
        assertEquals("안내 지점 통과 여부를 확인 중입니다.", neutral.instruction)
        assertTrue(neutral.cancelStaleNavigationSpeech)
        assertTrue(old.speechCueToken != neutral.speechCueToken)
        assertFalse(navigator.reserveInstruction(old))
        if (neutral.cancelStaleNavigationSpeech) assertTrue(actuator.cancelNavigationSpeech())
        assertEquals(1, oldEvents.cancelled)
        assertTrue(navigator.reserveInstruction(neutral))
        val neutralEvents = Events()
        acceptNavigation(actuator, requireNotNull(neutral.instruction), neutralEvents)
        runtime.start(oldId)
        runtime.done(oldId)
        navigator.acknowledgeInstruction(old, 2_001L)
        assertEquals(0, oldEvents.completed)
        assertEquals(0, oldEvents.failed)
        assertEquals(0, neutralEvents.completed)
        assertTrue(actuator.isAppSpeechActive())

        val stillNeutral = guidanceAt(102.0, 3_000L)
        assertEquals("guidance_in_flight", stillNeutral.reason)
        assertFalse(stillNeutral.cancelStaleNavigationSpeech)
        val next = guidanceAt(105.0, 4_000L)
        assertEquals(1, next.guideIndex)
        assertTrue(next.instruction?.contains("안내 지점 통과 여부") == false)
    }

    @Test
    fun navigationCancellationStillStopsItsUnreplacedUtterance() {
        val (runtime, actuator) = readyActuator()
        val old = Events()
        acceptNavigation(actuator, "unreplaced route", old)
        val oldId = runtime.spoken.last().id
        val stopsBefore = runtime.stops

        assertTrue(actuator.cancelNavigationSpeech())

        assertEquals(stopsBefore + 1, runtime.stops)
        assertEquals(1, old.cancelled)
        assertEquals(0, old.completed)
        assertEquals(0, old.failed)
        runtime.done(oldId)
        assertEquals(0, old.completed)
        assertFalse(actuator.isAppSpeechActive())
    }

    @Test
    fun navigationStopCannotOvertakeReplacementDangerSpeech() {
        val (runtime, actuator) = readyActuator()
        val old = Events()
        val danger = Events()
        acceptNavigation(actuator, "old route", old)
        val oldId = runtime.spoken.last().id
        val stopsBefore = runtime.stops
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val dangerFocusRequested = CountDownLatch(1)
        runtime.onStop = {
            stopEntered.countDown()
            releaseStop.await(10, TimeUnit.SECONDS)
        }
        runtime.onFocusRequest = { dangerFocusRequested.countDown() }
        var cancellationResult: Boolean? = null
        var dangerDispatch: NavigationSpeechDispatchResult? = null
        val cancellationThread = Thread { cancellationResult = actuator.cancelNavigationSpeech() }
        val dangerThread = Thread {
            dangerDispatch = actuator.emit(
                action = riskAction(runtime.now + 20_000L),
                isStillValidAtStart = { true },
                onSpeechCompleted = { danger.completed += 1 },
                onSpeechFailed = { danger.failed += 1 },
                onSpeechCancelled = { danger.cancelled += 1 },
            ).speech
        }

        cancellationThread.start()
        try {
            assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
            dangerThread.start()
            assertTrue(dangerFocusRequested.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (dangerThread.isAlive && dangerThread.state != Thread.State.BLOCKED &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(1)
            }
            assertTrue(!dangerThread.isAlive || dangerThread.state == Thread.State.BLOCKED)
        } finally {
            releaseStop.countDown()
            cancellationThread.join(5_000)
            if (dangerThread.state != Thread.State.NEW) dangerThread.join(5_000)
        }

        assertFalse(cancellationThread.isAlive)
        assertFalse(dangerThread.isAlive)
        assertEquals(true, cancellationResult)
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dangerDispatch)
        assertFalse(runtime.spokeWhileStopping)
        assertEquals(stopsBefore + 1, runtime.stops)
        assertEquals(1, old.cancelled)
        assertEquals(0, old.failed)
        val dangerId = runtime.spoken.last().id
        assertTrue(dangerId != oldId)
        runtime.done(oldId)
        runtime.start(dangerId)
        runtime.done(dangerId)
        assertEquals(1, danger.completed)
        assertEquals(0, danger.failed)
        assertEquals(0, danger.cancelled)
    }

    @Test
    fun priorityTrainingCancellationStopsOldSpeechBeforeReplacementDanger() {
        val (runtime, actuator) = readyActuator()
        val risk = Events()
        var oldFailed = 0
        var oldCompleted = 0
        var stopsAtReplacement = -1
        var speechAtReplacement: String? = "not captured"
        var riskDispatch: NavigationSpeechDispatchResult? = null
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakPriorityUserTraining(
                message = "old priority training",
                onCompleted = { oldCompleted += 1 },
                onFailed = {
                    oldFailed += 1
                    stopsAtReplacement = runtime.stops
                    speechAtReplacement = runtime.activeSpeechId
                    riskDispatch = actuator.emit(
                        action = riskAction(runtime.now + 20_000L),
                        isStillValidAtStart = { true },
                        onSpeechCompleted = { risk.completed += 1 },
                        onSpeechFailed = { risk.failed += 1 },
                        onSpeechCancelled = { risk.cancelled += 1 },
                    ).speech
                },
            ),
        )
        val oldId = runtime.spoken.last().id
        val stopsBefore = runtime.stops
        assertEquals(oldId, runtime.activeSpeechId)

        actuator.cancelPriorityUserTrainingFeedback()

        assertEquals(stopsBefore + 1, stopsAtReplacement)
        assertEquals(null, speechAtReplacement)
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, riskDispatch)
        assertEquals(stopsBefore + 1, runtime.stops)
        assertEquals(1, oldFailed)
        assertEquals(0, oldCompleted)
        assertTrue(actuator.isAppSpeechActive())
        val riskId = runtime.spoken.last().id
        assertTrue(riskId != oldId)
        assertEquals(riskId, runtime.activeSpeechId)
        runtime.done(oldId)
        assertEquals(riskId, runtime.activeSpeechId)
        runtime.start(riskId)
        runtime.done(riskId)
        assertEquals(1, risk.completed)
        assertEquals(0, risk.failed)
        assertEquals(0, risk.cancelled)
        assertFalse(actuator.isAppSpeechActive())
    }

    @Test
    fun priorityTrainingCancellationStillStopsUnreplacedOrdinarySpeech() {
        val (runtime, actuator) = readyActuator()
        var oldFailed = 0
        var oldCompleted = 0
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakPriorityUserTraining(
                message = "ordinary priority training",
                onCompleted = { oldCompleted += 1 },
                onFailed = { oldFailed += 1 },
            ),
        )
        val oldId = runtime.spoken.last().id
        val stopsBefore = runtime.stops
        assertEquals(oldId, runtime.activeSpeechId)

        actuator.cancelPriorityUserTrainingFeedback()

        assertEquals(stopsBefore + 1, runtime.stops)
        assertEquals(null, runtime.activeSpeechId)
        assertEquals(1, oldFailed)
        assertEquals(0, oldCompleted)
        assertFalse(actuator.isAppSpeechActive())
        runtime.done(oldId)
        assertEquals(0, oldCompleted)
    }

    @Test
    fun sameRankRiskFromInternalCancellationCannotBecomeAGhostQueueOwner() {
        val (runtime, actuator) = readyActuator()
        val first = Events()
        val replacement = Events()
        val reentrant = Events()
        var reentrantDispatch: NavigationSpeechDispatchResult? = null
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.emit(
                action = riskAction(runtime.now + 20_000L, "first warning"),
                isStillValidAtStart = { true },
                onSpeechCompleted = { first.completed += 1 },
                onSpeechFailed = { first.failed += 1 },
                onSpeechCancelled = {
                    first.cancelled += 1
                    reentrantDispatch = actuator.emit(
                        action = riskAction(runtime.now + 20_000L, "reentrant stop", MessageLevel.STOP),
                        isStillValidAtStart = { true },
                        onSpeechCompleted = { reentrant.completed += 1 },
                        onSpeechFailed = { reentrant.failed += 1 },
                        onSpeechCancelled = { reentrant.cancelled += 1 },
                    ).speech
                },
            ).speech,
        )
        val firstId = runtime.spoken.last().id

        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.emit(
                action = riskAction(runtime.now + 20_000L, "replacement stop", MessageLevel.STOP),
                isStillValidAtStart = { true },
                onSpeechCompleted = { replacement.completed += 1 },
                onSpeechFailed = { replacement.failed += 1 },
                onSpeechCancelled = { replacement.cancelled += 1 },
            ).speech,
        )

        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, reentrantDispatch)
        assertEquals(2, runtime.spoken.size)
        assertEquals(1, first.cancelled)
        val replacementId = runtime.spoken.last().id
        assertEquals(replacementId, runtime.activeSpeechId)
        runtime.done(firstId)
        runtime.start(replacementId)
        runtime.done(replacementId)
        runtime.advanceBy(20_500L)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, replacement.completed)
        assertEquals(0, replacement.failed)
        assertEquals(0, replacement.cancelled)
        assertEquals(0, reentrant.completed)
        assertEquals(0, reentrant.failed)
        assertEquals(0, reentrant.cancelled)
    }

    @Test
    fun previousEngineCannotFailAnOwnedIdInTheReplacementEngine() {
        val (runtime, actuator) = readyActuator(TtsEngineRecoveryBudget(automaticRetries = 1))
        acceptNavigation(actuator, "old engine", Events())
        val previousListener = checkNotNull(runtime.engines.last().listener)
        runtime.advanceBy(8_000L)
        runtime.advanceBy(250L)
        runtime.ready()

        val current = Events()
        acceptNavigation(actuator, "replacement engine", current)
        val currentId = runtime.spoken.last().id
        previousListener.onError(currentId, TextToSpeech.ERROR_SERVICE)
        previousListener.onDone(currentId)
        runtime.runCurrent()
        assertEquals("tts=ready", actuator.statusText())
        assertTrue(actuator.isAppSpeechActive())
        assertEquals(0, current.completed)
        assertEquals(0, current.failed)
        runtime.done(currentId)
        assertEquals(1, current.completed)
    }

    @Test
    fun audioAttributesExceptionsUseTheSameBoundedInitializationRecovery() {
        val runtime = FakeRuntime().apply { attributesThrow = true }
        var unavailable = 0
        val actuator = AndroidFeedbackActuator(
            runtime = runtime,
            onOfflineKoreanSpeechUnavailable = { unavailable += 1 },
            recoveryBudget = TtsEngineRecoveryBudget(automaticRetries = 1),
        )
        runtime.ready()
        assertEquals("tts=initializing", actuator.statusText())
        runtime.advanceBy(250L)
        runtime.ready()
        assertEquals("tts=init_failed", actuator.statusText())
        assertEquals(1, unavailable)
        runtime.advanceBy(20_500L)
        assertEquals(2, runtime.engines.size)
        assertTrue(runtime.spoken.isEmpty())
    }

    @Test
    fun listenerRegistrationExceptionDoesNotLeaveEngineReady() {
        val runtime = FakeRuntime().apply { listenerThrows = true }
        val actuator = AndroidFeedbackActuator(runtime = runtime)
        runtime.ready()
        assertEquals("tts=init_failed", actuator.statusText())
        assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, actuator.speakNavigation("not ready"))
        assertFalse(actuator.isAppSpeechActive())
    }

    @Test
    fun synchronousSpeakExceptionRetiresPendingAndFocusWithoutDuplicateFailure() {
        val (runtime, actuator) = readyActuator()
        runtime.speakThrows = true
        val events = Events()
        assertEquals(
            NavigationSpeechDispatchResult.UNAVAILABLE,
            actuator.speakNavigation(
                message = "throws",
                onCompleted = { events.completed += 1 },
                onFailed = { events.failed += 1 },
                onCancelled = { events.cancelled += 1 },
            ),
        )
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(1, runtime.focusReleases)
        assertEquals(0, events.failed)
        assertEquals(0, events.completed)
        assertEquals("tts=init_failed", actuator.statusText())
    }

    @Test
    fun synchronousSpeakErrorAndThrownFailureUseTheSameOwnershipContract() {
        val (runtime, actuator) = readyActuator()
        runtime.speakResult = TextToSpeech.ERROR
        val events = Events()
        assertEquals(
            NavigationSpeechDispatchResult.UNAVAILABLE,
            actuator.speakNavigation("error result", onFailed = { events.failed += 1 }),
        )
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(1, runtime.focusReleases)
        assertEquals(0, events.failed)
        assertEquals("tts=init_failed", actuator.statusText())
    }

    @Test
    fun focusRequestExceptionCannotLeaveAPendingUtteranceOrSpendEngineRetry() {
        val (runtime, actuator) = readyActuator(TtsEngineRecoveryBudget(automaticRetries = 1))
        runtime.focusRequestThrows = true
        assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, actuator.speakNavigation("no focus"))
        assertFalse(actuator.isAppSpeechActive())
        assertTrue(runtime.spoken.isEmpty())
        assertEquals("tts=ready", actuator.statusText())
        runtime.focusRequestThrows = false
        acceptNavigation(actuator, "focus restored", Events())
        runtime.advanceBy(8_000L)
        runtime.advanceBy(250L)
        assertEquals(2, runtime.engines.size)
    }

    @Test
    fun stopExceptionCannotSkipCancellationOrLaterShutdown() {
        val (runtime, actuator) = readyActuator()
        val events = Events()
        acceptNavigation(actuator, "cancel with broken stop", events)
        val id = runtime.spoken.last().id
        runtime.stopThrows = true
        assertTrue(actuator.cancelNavigationSpeech())
        assertEquals(1, events.cancelled)
        assertFalse(actuator.isAppSpeechActive())
        runtime.done(id)
        assertEquals(0, events.completed)
        actuator.close()
        assertTrue(runtime.stops >= 2)
        assertEquals(1, runtime.shutdowns)
        assertEquals("tts=closed", actuator.statusText())
    }

    @Test
    fun closeContinuesAfterFocusStopAndShutdownExceptionsAndRejectsLateCallbacks() {
        val (runtime, actuator) = readyActuator()
        val events = Events()
        acceptNavigation(actuator, "close all", events)
        val id = runtime.spoken.last().id
        runtime.focusReleaseThrows = true
        runtime.stopThrows = true
        runtime.shutdownThrows = true
        actuator.close()
        val failuresAtClose = events.failed
        val cancellationsAtClose = events.cancelled
        assertEquals(1, runtime.focusReleases)
        assertEquals(1, runtime.stops)
        assertEquals(1, runtime.shutdowns)
        assertFalse(actuator.isAppSpeechActive())
        runtime.done(id)
        runtime.error(id, TextToSpeech.ERROR_SERVICE)
        runtime.advanceBy(20_500L)
        assertEquals("tts=closed", actuator.statusText())
        assertEquals(0, events.completed)
        assertEquals(failuresAtClose, events.failed)
        assertEquals(cancellationsAtClose, events.cancelled)
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun throwingRiskFailureConsumerStillReleasesOwnershipAndRecoversEngine() {
        val (runtime, actuator) = readyActuator(TtsEngineRecoveryBudget(automaticRetries = 1))
        var riskCompleted = 0
        var riskFailed = 0
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.emit(
                action = riskAction(runtime.now + 30_000L),
                isStillValidAtStart = { true },
                onSpeechCompleted = { riskCompleted += 1 },
                onSpeechFailed = {
                    riskFailed += 1
                    throw IllegalStateException("consumer")
                },
            ).speech,
        )
        val riskSpeakCalls = runtime.speakCalls
        val riskFocusRequests = runtime.focusRequests
        var suppressedCompleted = 0
        var suppressedFailed = 0
        assertEquals(
            NavigationSpeechDispatchResult.SUPPRESSED,
            actuator.speakExplicitConfirmation(
                "suppressed confirmation",
                { suppressedCompleted += 1 },
                { suppressedFailed += 1 },
            ),
        )
        assertEquals(riskSpeakCalls, runtime.speakCalls)
        assertEquals(riskFocusRequests, runtime.focusRequests)
        assertEquals(0, suppressedCompleted)
        assertEquals(0, suppressedFailed)
        assertTrue(actuator.isAppSpeechActive())
        runtime.advanceBy(8_000L)
        assertEquals(1, riskFailed)
        assertEquals(0, riskCompleted)
        assertEquals(0, suppressedCompleted)
        assertEquals(0, suppressedFailed)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals("tts=initializing", actuator.statusText())
        assertEquals(1, runtime.focusReleases)
        runtime.advanceBy(250L)
        assertEquals(2, runtime.engines.size)
        runtime.ready()
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, riskFailed)
        assertEquals(0, riskCompleted)
        assertEquals(0, suppressedCompleted)
        assertEquals(0, suppressedFailed)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(riskSpeakCalls, runtime.speakCalls)
        assertEquals(riskFocusRequests, runtime.focusRequests)
    }

    @Test
    fun cancellationCallbackClosingActuatorCannotRegisterReplacementOrAcquireFocus() {
        val (runtime, actuator) = readyActuator()
        var cancelledOwnerTerminals = 0
        var cancelledOwnerCompletions = 0
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakInteraction(
                message = "tracked owner",
                onCompleted = { cancelledOwnerCompletions += 1 },
                onFailed = {
                    cancelledOwnerTerminals += 1
                    actuator.close()
                    // A forbidden later submission would hit a closed engine and return ERROR.
                    runtime.speakResult = TextToSpeech.ERROR
                },
            ),
        )
        val oldId = runtime.spoken.last().id
        val callsBeforeReplacement = runtime.speakCalls
        val focusRequestsBeforeReplacement = runtime.focusRequests
        val replacement = Events()

        assertEquals(
            NavigationSpeechDispatchResult.UNAVAILABLE,
            actuator.speakInteraction(
                message = "replacement after close",
                onCompleted = { replacement.completed += 1 },
                onFailed = { replacement.failed += 1 },
            ),
        )
        assertEquals("tts=closed", actuator.statusText())
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(callsBeforeReplacement, runtime.speakCalls)
        assertEquals(focusRequestsBeforeReplacement, runtime.focusRequests)
        assertEquals(1, runtime.focusReleases)
        assertEquals(1, cancelledOwnerTerminals)
        assertEquals(0, cancelledOwnerCompletions)
        // A synchronously rejected replacement never acquired an async terminal callback.
        assertEquals(0, replacement.failed)
        assertEquals(0, replacement.completed)

        runtime.done(oldId)
        runtime.error(oldId, TextToSpeech.ERROR_SERVICE)
        runtime.advanceBy(20_500L)
        assertEquals(1, cancelledOwnerTerminals)
        assertEquals(0, cancelledOwnerCompletions)
        assertEquals(0, replacement.failed)
        assertEquals(0, replacement.completed)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(callsBeforeReplacement, runtime.speakCalls)
        assertEquals(focusRequestsBeforeReplacement, runtime.focusRequests)
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun throwingInteractionCancellationStillFlushesAndProtectsReplacement() {
        val (runtime, actuator) = readyActuator()
        var oldFailures = 0
        var oldCompletions = 0
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakInteraction(
                "throwing interaction owner",
                onCompleted = { oldCompletions += 1 },
                onFailed = {
                    oldFailures += 1
                    throw IllegalStateException("interaction cancellation consumer")
                },
            ),
        )
        val oldId = runtime.spoken.last().id
        val callsBefore = runtime.speakCalls
        val replacement = Events()
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakInteraction(
                "replacement interaction",
                onCompleted = { replacement.completed += 1 },
                onFailed = { replacement.failed += 1 },
            ),
        )
        val newId = runtime.spoken.last().id
        assertEquals(callsBefore + 1, runtime.speakCalls)
        assertEquals(TextToSpeech.QUEUE_FLUSH, runtime.spoken.last().queueMode)
        assertEquals(1, oldFailures)
        assertEquals(0, oldCompletions)
        assertTrue(actuator.isAppSpeechActive())
        assertEquals("tts=ready", actuator.statusText())
        runtime.done(oldId)
        runtime.error(oldId, TextToSpeech.ERROR_SERVICE)
        assertTrue(actuator.isAppSpeechActive())
        assertEquals(0, runtime.focusReleases)
        assertEquals(0, replacement.completed)
        assertEquals(0, replacement.failed)
        runtime.start(newId)
        runtime.done(newId)
        runtime.done(newId)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(1, runtime.focusReleases)
        assertEquals(1, replacement.completed)
        assertEquals(0, replacement.failed)
        assertEquals(1, oldFailures)
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun throwingAdvisoryCancellationStillStopsAndCompletesNavigation() {
        val (runtime, actuator) = readyActuator()
        var advisoryFailures = 0
        var advisoryCompletions = 0
        assertTrue(
            actuator.speakAdvisory(
                message = "throwing advisory owner",
                validUntilMs = runtime.now + 20_000L,
                isStillValid = { true },
                onCompleted = { advisoryCompletions += 1 },
                onFailed = {
                    advisoryFailures += 1
                    throw IllegalStateException("advisory cancellation consumer")
                },
            ),
        )
        val advisoryId = runtime.spoken.last().id
        val stopsBefore = runtime.stops
        val callsBefore = runtime.speakCalls
        val navigation = Events()
        acceptNavigation(actuator, "navigation after advisory", navigation)
        val navigationId = runtime.spoken.last().id
        assertEquals(stopsBefore + 1, runtime.stops)
        assertEquals(callsBefore + 1, runtime.speakCalls)
        assertEquals(TextToSpeech.QUEUE_ADD, runtime.spoken.last().queueMode)
        assertEquals(1, advisoryFailures)
        assertEquals(0, advisoryCompletions)
        runtime.done(advisoryId)
        runtime.error(advisoryId, TextToSpeech.ERROR_SERVICE)
        assertTrue(actuator.isAppSpeechActive())
        assertEquals(0, runtime.focusReleases)
        assertEquals(0, navigation.completed)
        assertEquals(0, navigation.failed)
        assertEquals(0, navigation.cancelled)
        runtime.start(navigationId)
        runtime.done(navigationId)
        runtime.done(navigationId)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(1, runtime.focusReleases)
        assertEquals(1, navigation.completed)
        assertEquals(0, navigation.failed)
        assertEquals(0, navigation.cancelled)
        assertEquals(1, advisoryFailures)
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun throwingCancellationAfterCloseCannotResurrectOutputOwnership() {
        val (runtime, actuator) = readyActuator()
        var oldFailures = 0
        var oldCompletions = 0
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakInteraction(
                "close then throw owner",
                onCompleted = { oldCompletions += 1 },
                onFailed = {
                    oldFailures += 1
                    actuator.close()
                    runtime.speakResult = TextToSpeech.ERROR
                    throw IllegalStateException("closed cancellation consumer")
                },
            ),
        )
        val oldId = runtime.spoken.last().id
        val callsBefore = runtime.speakCalls
        val focusRequestsBefore = runtime.focusRequests
        val replacement = Events()
        assertEquals(
            NavigationSpeechDispatchResult.UNAVAILABLE,
            actuator.speakInteraction(
                "must not be submitted",
                onCompleted = { replacement.completed += 1 },
                onFailed = { replacement.failed += 1 },
            ),
        )
        runtime.done(oldId)
        runtime.error(oldId, TextToSpeech.ERROR_SERVICE)
        runtime.advanceBy(20_500L)
        assertEquals("tts=closed", actuator.statusText())
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(callsBefore, runtime.speakCalls)
        assertEquals(focusRequestsBefore, runtime.focusRequests)
        assertEquals(1, runtime.focusReleases)
        assertEquals(1, oldFailures)
        assertEquals(0, oldCompletions)
        assertEquals(0, replacement.completed)
        assertEquals(0, replacement.failed)
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun externalRiskPreparationIsolatesThrowingAdvisoryCancellationAndAllowsRisk() {
        val (runtime, actuator) = readyActuator()
        val old = Events()
        var stopsAtFailure = -1
        var pendingAtFailure = true
        assertTrue(
            actuator.speakAdvisory(
                message = "advisory before external risk",
                validUntilMs = runtime.now + 20_000L,
                isStillValid = { true },
                onCompleted = { old.completed += 1 },
                onFailed = {
                    old.failed += 1
                    stopsAtFailure = runtime.stops
                    pendingAtFailure = actuator.isAppSpeechActive()
                    throw IllegalStateException("external risk advisory cancellation consumer")
                },
            ),
        )
        assertEquals(1, runtime.spoken.size)
        assertTrue(actuator.isAppSpeechActive())
        val oldId = runtime.spoken.last().id
        val stopsBefore = runtime.stops
        val releasesBefore = runtime.focusReleases
        val callsBefore = runtime.speakCalls

        actuator.prepareForExternalRiskAnnouncement()

        assertEquals(stopsBefore + 1, stopsAtFailure)
        assertFalse(pendingAtFailure)
        assertEquals(stopsBefore + 1, runtime.stops)
        assertEquals(releasesBefore + 1, runtime.focusReleases)
        assertEquals(callsBefore, runtime.speakCalls)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, old.failed)
        assertEquals(0, old.completed)

        val risk = Events()
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.emit(
                action = riskAction(runtime.now + 20_000L),
                isStillValidAtStart = { true },
                onSpeechCompleted = { risk.completed += 1 },
                onSpeechFailed = { risk.failed += 1 },
                onSpeechCancelled = { risk.cancelled += 1 },
            ).speech,
        )
        val riskId = runtime.spoken.last().id
        assertTrue(riskId != oldId)
        assertEquals(callsBefore + 1, runtime.speakCalls)
        runtime.done(oldId)
        runtime.error(oldId, TextToSpeech.ERROR_SERVICE)
        assertTrue(actuator.isAppSpeechActive())
        assertEquals(releasesBefore + 1, runtime.focusReleases)
        assertEquals(0, risk.completed)
        assertEquals(0, risk.failed)
        assertEquals(0, risk.cancelled)
        runtime.start(riskId)
        runtime.done(riskId)
        runtime.done(riskId)
        runtime.advanceBy(20_500L)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(releasesBefore + 2, runtime.focusReleases)
        assertEquals(1, old.failed)
        assertEquals(0, old.completed)
        assertEquals(1, risk.completed)
        assertEquals(0, risk.failed)
        assertEquals(0, risk.cancelled)
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun externalRiskPreparationIsolatesThrowingStrictCancellationAndAllowsRisk() {
        val (runtime, actuator) = readyActuator()
        val old = Events()
        var stopsAtFailure = -1
        var pendingAtFailure = true
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakInteraction(
                message = "strict interaction before external risk",
                onCompleted = { old.completed += 1 },
                onFailed = {
                    old.failed += 1
                    stopsAtFailure = runtime.stops
                    pendingAtFailure = actuator.isAppSpeechActive()
                    throw IllegalStateException("external risk strict cancellation consumer")
                },
            ),
        )
        assertEquals(1, runtime.spoken.size)
        assertTrue(actuator.isAppSpeechActive())
        val oldId = runtime.spoken.last().id
        val stopsBefore = runtime.stops
        val releasesBefore = runtime.focusReleases
        val callsBefore = runtime.speakCalls

        actuator.prepareForExternalRiskAnnouncement()

        assertEquals(stopsBefore + 1, stopsAtFailure)
        assertFalse(pendingAtFailure)
        assertEquals(stopsBefore + 1, runtime.stops)
        assertEquals(releasesBefore + 1, runtime.focusReleases)
        assertEquals(callsBefore, runtime.speakCalls)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, old.failed)
        assertEquals(0, old.completed)

        val risk = Events()
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.emit(
                action = riskAction(runtime.now + 20_000L),
                isStillValidAtStart = { true },
                onSpeechCompleted = { risk.completed += 1 },
                onSpeechFailed = { risk.failed += 1 },
                onSpeechCancelled = { risk.cancelled += 1 },
            ).speech,
        )
        val riskId = runtime.spoken.last().id
        assertTrue(riskId != oldId)
        assertEquals(callsBefore + 1, runtime.speakCalls)
        runtime.done(oldId)
        runtime.error(oldId, TextToSpeech.ERROR_SERVICE)
        assertTrue(actuator.isAppSpeechActive())
        assertEquals(releasesBefore + 1, runtime.focusReleases)
        assertEquals(0, risk.completed)
        assertEquals(0, risk.failed)
        assertEquals(0, risk.cancelled)
        runtime.start(riskId)
        runtime.done(riskId)
        runtime.done(riskId)
        runtime.advanceBy(20_500L)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(releasesBefore + 2, runtime.focusReleases)
        assertEquals(1, old.failed)
        assertEquals(0, old.completed)
        assertEquals(1, risk.completed)
        assertEquals(0, risk.failed)
        assertEquals(0, risk.cancelled)
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun progressToneNativeFailureDoesNotLeakFocusOrBlockFollowingSpeech() {
        for (failure in listOf("create", "start", "false", "release")) {
            val (runtime, actuator) = readyActuator()
            runtime.toneFactoryThrows = failure == "create"
            runtime.toneStartThrows = failure == "start"
            runtime.toneStartResult = failure != "false" && failure != "release"
            runtime.toneReleaseThrows = failure == "release"

            actuator.playProgressBeep(50)

            assertEquals(1, runtime.toneCreates)
            assertEquals(1, runtime.focusReleases)
            assertFalse(actuator.isAppSpeechActive())
            assertTrue(runtime.spoken.isEmpty())
            assertTrue(runtime.scheduledTasksSnapshot().isEmpty())
            assertEquals(if (failure == "create") 0 else 1, runtime.tones.size)
            runtime.tones.forEach { assertEquals(1, it.releases) }
            val next = Events()
            acceptNavigation(actuator, "after tone failure", next)
            completeToneReplacement(runtime, actuator, next, 1)
        }
    }

    @Test
    fun progressToneCreateOrStartReentryCannotRetireFollowingSpeech() {
        for (phase in listOf("create", "start")) {
            val outcomes = if (phase == "create") listOf("success", "throw") else
                listOf("success", "false", "throw")
            for (outcome in outcomes) {
                for (risk in listOf(false, true)) {
                    val (runtime, actuator) = readyActuator()
                    val next = Events()
                    var dispatch: NavigationSpeechDispatchResult? = null
                    var releasesAfterB = -1
                    val submitB = {
                        dispatch = dispatchToneReplacement(runtime, actuator, risk, next)
                        releasesAfterB = runtime.focusReleases
                    }
                    runtime.toneFactoryThrows = phase == "create" && outcome == "throw"
                    runtime.toneStartThrows = phase == "start" && outcome == "throw"
                    runtime.toneStartResult = outcome != "false"
                    if (phase == "create") runtime.onToneCreate = submitB else runtime.onToneStart = submitB

                    actuator.playProgressBeep(50)

                    assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch)
                    assertEquals(releasesAfterB, runtime.focusReleases)
                    assertEquals(if (risk) 2 else 1, runtime.focusRequests)
                    assertEquals(if (phase == "create" && outcome == "throw") 0 else 1, runtime.tones.size)
                    runtime.tones.forEach { assertEquals(1, it.releases) }
                    // Only B's start watchdog remains; stale A never schedules a success timer.
                    assertEquals(1, runtime.scheduledTasksSnapshot().size)
                    completeToneReplacement(runtime, actuator, next, releasesAfterB)
                }
            }
        }
    }

    @Test
    fun progressToneReleaseReentryCannotRetireFollowingSpeech() {
        for (phase in listOf("replace", "failedStart")) {
            for (risk in listOf(false, true)) {
                for (releaseThrows in listOf(false, true)) {
                    val (runtime, actuator) = readyActuator()
                    val oldTimer = if (phase == "replace") {
                        actuator.playProgressBeep(25)
                        runtime.scheduledTasksSnapshot().single()
                    } else {
                        runtime.toneStartResult = false
                        null
                    }
                    val next = Events()
                    var dispatch: NavigationSpeechDispatchResult? = null
                    var releasesAfterB = -1
                    runtime.toneReleaseThrows = releaseThrows
                    runtime.onToneRelease = {
                        dispatch = dispatchToneReplacement(runtime, actuator, risk, next)
                        releasesAfterB = runtime.focusReleases
                    }

                    actuator.playProgressBeep(50)

                    assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch)
                    assertEquals(1, runtime.toneCreates)
                    assertEquals(1, runtime.tones.single().releases)
                    assertEquals(releasesAfterB, runtime.focusReleases)
                    assertEquals(if (risk) 2 else 1, runtime.focusRequests)
                    if (oldTimer != null) runtime.advanceBy(oldTimer.due - runtime.now)
                    assertEquals(releasesAfterB, runtime.focusReleases)
                    completeToneReplacement(runtime, actuator, next, releasesAfterB)
                }
            }
        }
    }

    @Test
    fun progressToneOldTimerCannotRetireSpeechOrANewerBeep() {
        for (replacement in listOf("navigation", "risk", "beep")) {
            val (runtime, actuator) = readyActuator()
            actuator.playProgressBeep(50)
            val oldTimer = runtime.scheduledTasksSnapshot().single()
            if (replacement == "beep") {
                runtime.advanceBy(1L)
                actuator.playProgressBeep(50)
                val newTimer = runtime.scheduledTasksSnapshot().maxBy { it.due }
                runtime.advanceBy(oldTimer.due - runtime.now)
                assertEquals(0, runtime.focusReleases)
                runtime.advanceBy(newTimer.due - runtime.now)
                assertEquals(1, runtime.focusReleases)
                assertEquals(1, runtime.toneCreates)
                assertEquals(2, runtime.tones.single().starts)
            } else {
                val next = Events()
                assertEquals(
                    NavigationSpeechDispatchResult.ACCEPTED,
                    dispatchToneReplacement(runtime, actuator, replacement == "risk", next),
                )
                val releasesAfterB = runtime.focusReleases
                runtime.advanceBy(oldTimer.due - runtime.now)
                assertEquals(releasesAfterB, runtime.focusReleases)
                completeToneReplacement(runtime, actuator, next, releasesAfterB)
            }
        }
    }

    @Test
    fun progressToneSuccessKeepsItsExistingTimerAndCachedTone() {
        val (runtime, actuator) = readyActuator()
        actuator.playProgressBeep(50)
        val firstTimer = runtime.scheduledTasksSnapshot().single()
        // Existing 80 ms beep plus the existing 50 ms focus margin.
        assertEquals(80, runtime.tones.single().lastDurationMs)
        assertEquals(130L, firstTimer.due - runtime.now)
        runtime.advanceBy(firstTimer.due - runtime.now - 1L)
        assertEquals(0, runtime.focusReleases)
        runtime.advanceBy(1L)
        assertEquals(1, runtime.focusReleases)
        assertEquals(0, runtime.tones.single().releases)

        actuator.playProgressBeep(50)
        assertEquals(1, runtime.toneCreates)
        assertEquals(2, runtime.tones.single().starts)
        val secondTimer = runtime.scheduledTasksSnapshot().single()
        runtime.advanceBy(secondTimer.due - runtime.now)
        assertEquals(2, runtime.focusReleases)
        actuator.close()
        assertEquals(1, runtime.tones.single().releases)
        assertEquals(2, runtime.focusReleases)
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun progressToneCloseDuringNativeEffectsCannotPublishOrRestartOutput() {
        for (phase in listOf("create", "start", "release")) {
            val (runtime, actuator) = readyActuator()
            when (phase) {
                "create" -> runtime.onToneCreate = { actuator.close() }
                "start" -> runtime.onToneStart = { actuator.close() }
                else -> {
                    runtime.toneStartResult = false
                    runtime.onToneRelease = { actuator.close() }
                }
            }

            actuator.playProgressBeep(50)

            assertEquals("tts=closed", actuator.statusText())
            assertEquals(1, runtime.toneCreates)
            assertEquals(1, runtime.tones.single().releases)
            assertEquals(1, runtime.focusReleases)
            assertFalse(actuator.isAppSpeechActive())
            actuator.playProgressBeep(50)
            runtime.advanceBy(20_500L)
            assertEquals(1, runtime.toneCreates)
            assertEquals(1, runtime.focusRequests)
            assertEquals(1, runtime.focusReleases)
            assertEquals(1, runtime.engines.size)
        }
    }

    @Test
    fun progressToneNativeErrorsAreNotSwallowed() {
        val (runtime, actuator) = readyActuator()
        runtime.onToneCreate = { throw AssertionError("fatal tone fixture") }
        var escaped: AssertionError? = null
        try {
            actuator.playProgressBeep(50)
        } catch (error: AssertionError) {
            escaped = error
        }
        assertEquals("fatal tone fixture", escaped?.message)
        actuator.close()
        assertEquals(1, runtime.focusReleases)
    }

    private fun dispatchToneReplacement(
        runtime: FakeRuntime,
        actuator: AndroidFeedbackActuator,
        risk: Boolean,
        events: Events,
    ): NavigationSpeechDispatchResult = if (risk) {
        actuator.emit(
            action = riskAction(runtime.now + 20_000L),
            isStillValidAtStart = { true },
            onSpeechCompleted = { events.completed += 1 },
            onSpeechFailed = { events.failed += 1 },
            onSpeechCancelled = { events.cancelled += 1 },
        ).speech
    } else {
        actuator.speakNavigation(
            message = "navigation after progress tone",
            onCompleted = { events.completed += 1 },
            onFailed = { events.failed += 1 },
            onCancelled = { events.cancelled += 1 },
            requiresExplicitTerminalCallback = true,
        )
    }

    private fun completeToneReplacement(
        runtime: FakeRuntime,
        actuator: AndroidFeedbackActuator,
        events: Events,
        releasesBeforeCompletion: Int,
    ) {
        assertTrue(actuator.isAppSpeechActive())
        assertEquals(0, events.completed)
        assertEquals(0, events.failed)
        assertEquals(0, events.cancelled)
        val id = runtime.spoken.last().id
        runtime.start(id)
        runtime.done(id)
        runtime.done(id)
        assertEquals(1, events.completed)
        assertEquals(0, events.failed)
        assertEquals(0, events.cancelled)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(releasesBeforeCompletion + 1, runtime.focusReleases)
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun focusGrantReturningAfterCloseIsReleasedWithoutPublishingOrStartingTone() {
        val (runtime, actuator) = readyActuator()
        runtime.onFocusRequest = { actuator.close() }

        actuator.playProgressBeep(50)

        assertEquals("tts=closed", actuator.statusText())
        assertEquals(1, runtime.focusRequests)
        assertEquals(1, runtime.focusReleases)
        assertEquals(runtime.focusRegistrations.single().listener, runtime.releasedFocusListeners.single())
        assertTrue(runtime.grantedFocusListeners.isEmpty())
        assertEquals(0, runtime.toneCreates)
        assertTrue(runtime.spoken.isEmpty())
        assertTrue(runtime.scheduledTasksSnapshot().isEmpty())
        assertFalse(actuator.isAppSpeechActive())
        runtime.focusRegistrations.single().listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        runtime.runCurrent()
        actuator.playProgressBeep(50)
        runtime.advanceBy(20_500L)
        assertEquals(1, runtime.focusRequests)
        assertEquals(1, runtime.focusReleases)
        assertEquals(0, runtime.toneCreates)
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun nestedRiskFocusSurvivesLateOuterSuccessFalseAndException() {
        for (outcome in listOf("success", "false", "throw")) {
            val (runtime, actuator) = readyActuator()
            val next = Events()
            var dispatch: NavigationSpeechDispatchResult? = null
            runtime.onFocusRequest = {
                dispatch = dispatchToneReplacement(runtime, actuator, true, next)
                runtime.focusRequestResult = outcome != "false"
                runtime.focusRequestThrows = outcome == "throw"
            }

            actuator.playProgressBeep(50)

            val outer = runtime.focusRegistrations[0]
            val replacement = runtime.focusRegistrations[1]
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch)
            assertFalse(outer.isRisk)
            assertTrue(replacement.isRisk)
            assertFalse(outer.listener === replacement.listener)
            assertEquals(setOf(replacement.listener), runtime.grantedFocusListeners)
            val expectedReleases = if (outcome == "false") 0 else 1
            assertEquals(expectedReleases, runtime.focusReleases)
            assertFalse(runtime.releasedFocusListeners.contains(replacement.listener))
            assertEquals(0, runtime.toneCreates)
            assertEquals(1, runtime.scheduledTasksSnapshot().size)
            outer.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            runtime.runCurrent()
            assertEquals(expectedReleases, runtime.focusReleases)
            assertEquals(setOf(replacement.listener), runtime.grantedFocusListeners)
            runtime.focusRequestThrows = false
            runtime.focusRequestResult = true
            completeToneReplacement(runtime, actuator, next, expectedReleases)
            assertTrue(runtime.grantedFocusListeners.isEmpty())
        }
    }

    @Test
    fun rejectedOuterFocusLossCannotRetireRiskButCurrentLossCan() {
        val (runtime, actuator) = readyActuator()
        val next = Events()
        var dispatch: NavigationSpeechDispatchResult? = null
        runtime.onFocusRequest = {
            dispatch = dispatchToneReplacement(runtime, actuator, true, next)
        }
        actuator.playProgressBeep(50)
        val outer = runtime.focusRegistrations[0].listener
        val replacement = runtime.focusRegistrations[1].listener
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch)

        outer.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        runtime.runCurrent()

        assertTrue(actuator.isAppSpeechActive())
        assertEquals(0, next.failed)
        assertEquals(1, runtime.focusReleases)
        replacement.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        runtime.runCurrent()
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(1, next.failed)
        assertEquals(0, next.completed)
        assertEquals(0, next.cancelled)
        assertEquals(2, runtime.focusReleases)
        assertTrue(runtime.grantedFocusListeners.isEmpty())
        replacement.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        outer.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        runtime.advanceBy(20_500L)
        assertEquals(1, next.failed)
        assertEquals(2, runtime.focusReleases)
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun closeDuringPriorFocusReleasePreventsTheReservedReplacementRequest() {
        val (runtime, actuator) = readyActuator()
        actuator.playProgressBeep(50)
        runtime.onFocusRelease = { actuator.close() }

        val dispatch = dispatchToneReplacement(runtime, actuator, true, Events())

        assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, dispatch)
        assertEquals("tts=closed", actuator.statusText())
        assertEquals(1, runtime.focusRequests)
        assertEquals(1, runtime.focusReleases)
        assertTrue(runtime.grantedFocusListeners.isEmpty())
        assertFalse(actuator.isAppSpeechActive())
        assertTrue(runtime.spoken.isEmpty())
        assertTrue(runtime.scheduledTasksSnapshot().isEmpty())
        assertEquals(1, runtime.tones.single().releases)
    }

    @Test
    fun deniedProgressFocusKeepsFalseFailurePolicyAndAllowsFollowingSpeech() {
        for (throws in listOf(false, true)) {
            val (runtime, actuator) = readyActuator()
            runtime.focusRequestResult = false
            runtime.focusRequestThrows = throws

            actuator.playProgressBeep(50)

            val cleanupAttempts = if (throws) 1 else 0
            assertEquals(cleanupAttempts, runtime.focusReleases)
            assertTrue(runtime.grantedFocusListeners.isEmpty())
            assertEquals(0, runtime.toneCreates)
            assertTrue(runtime.scheduledTasksSnapshot().isEmpty())
            assertEquals("tts=ready", actuator.statusText())
            runtime.focusRequestResult = true
            runtime.focusRequestThrows = false
            val next = Events()
            acceptNavigation(actuator, "after denied progress focus", next)
            completeToneReplacement(runtime, actuator, next, cleanupAttempts)
        }
    }

    @Test
    fun staleInteractionFocusFailureCannotStopReentrantRiskSpeech() {
        for (outcome in listOf("success", "false", "throw")) {
            val (runtime, actuator) = readyActuator()
            val outer = Events()
            val next = Events()
            var dispatch: NavigationSpeechDispatchResult? = null
            var stopsAfterReplacement = -1
            runtime.onFocusRequest = {
                dispatch = dispatchToneReplacement(runtime, actuator, true, next)
                stopsAfterReplacement = runtime.stops
                runtime.focusRequestResult = outcome != "false"
                runtime.focusRequestThrows = outcome == "throw"
            }

            val outerDispatch = actuator.speakInteraction(
                message = "outer interaction focus",
                onCompleted = { outer.completed += 1 },
                onFailed = { outer.failed += 1 },
            )

            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, outerDispatch)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch)
            assertEquals(stopsAfterReplacement, runtime.stops)
            assertEquals(1, outer.failed)
            assertEquals(0, outer.completed)
            assertEquals(1, runtime.spoken.size)
            assertEquals(0, runtime.toneCreates)
            val replacement = runtime.focusRegistrations[1].listener
            assertEquals(setOf(replacement), runtime.grantedFocusListeners)
            runtime.focusRegistrations[0].listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            runtime.runCurrent()
            assertEquals(stopsAfterReplacement, runtime.stops)
            val cleanupAttempts = if (outcome == "false") 0 else 1
            assertEquals(cleanupAttempts, runtime.focusReleases)
            runtime.focusRequestThrows = false
            runtime.focusRequestResult = true
            completeToneReplacement(runtime, actuator, next, cleanupAttempts)
            assertEquals(1, outer.failed)
            assertTrue(runtime.grantedFocusListeners.isEmpty())
        }
    }

    @Test
    fun currentInteractionFocusFailureStillStopsItsOwnFlushAndRetiresPending() {
        for (throws in listOf(false, true)) {
            val (runtime, actuator) = readyActuator()
            val outer = Events()
            runtime.focusRequestResult = false
            runtime.focusRequestThrows = throws

            val dispatch = actuator.speakInteraction(
                message = "current interaction focus failure",
                onCompleted = { outer.completed += 1 },
                onFailed = { outer.failed += 1 },
            )

            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, dispatch)
            assertEquals(1, runtime.stops)
            assertEquals(0, outer.completed)
            assertEquals(0, outer.failed)
            assertFalse(actuator.isAppSpeechActive())
            assertTrue(runtime.spoken.isEmpty())
            assertTrue(runtime.grantedFocusListeners.isEmpty())
            assertTrue(runtime.scheduledTasksSnapshot().isEmpty())
            assertEquals("tts=ready", actuator.statusText())
            val cleanupAttempts = if (throws) 1 else 0
            assertEquals(cleanupAttempts, runtime.focusReleases)
            runtime.focusRequestResult = true
            runtime.focusRequestThrows = false
            val next = Events()
            acceptNavigation(actuator, "after current focus failure", next)
            completeToneReplacement(runtime, actuator, next, cleanupAttempts)
        }
    }

    @Test
    fun explicitOutputCancellationDuringFocusRequestRejectsLateGrant() {
        for (cancellation in listOf("recognition", "externalRisk")) {
            val (runtime, actuator) = readyActuator()
            var recognitionPrepared: Boolean? = null
            runtime.onFocusRequest = {
                if (cancellation == "recognition") {
                    recognitionPrepared = actuator.prepareForSpeechRecognition()
                } else {
                    actuator.prepareForExternalRiskAnnouncement()
                }
            }

            actuator.playProgressBeep(50)

            if (cancellation == "recognition") assertEquals(true, recognitionPrepared)
            assertEquals(1, runtime.focusRequests)
            assertEquals(1, runtime.focusReleases)
            val cancelledListener = runtime.focusRegistrations.single().listener
            assertEquals(listOf(cancelledListener), runtime.releasedFocusListeners)
            assertTrue(runtime.grantedFocusListeners.isEmpty())
            assertEquals(0, runtime.toneCreates)
            assertTrue(runtime.spoken.isEmpty())
            assertTrue(runtime.scheduledTasksSnapshot().isEmpty())
            assertFalse(actuator.isAppSpeechActive())
            assertEquals("tts=ready", actuator.statusText())
            cancelledListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            runtime.runCurrent()
            assertEquals(1, runtime.focusReleases)

            val next = Events()
            acceptNavigation(actuator, "after explicit output cancellation", next)
            assertEquals(2, runtime.focusRequests)
            completeToneReplacement(runtime, actuator, next, 1)
            assertTrue(runtime.grantedFocusListeners.isEmpty())
        }
    }

    @Test
    fun cancelledFocusRequestCannotRetireRiskStartedAfterCancellation() {
        for (cancellation in listOf("recognition", "externalRisk")) {
            val (runtime, actuator) = readyActuator()
            val next = Events()
            var recognitionPrepared: Boolean? = null
            var dispatch: NavigationSpeechDispatchResult? = null
            runtime.onFocusRequest = {
                if (cancellation == "recognition") {
                    recognitionPrepared = actuator.prepareForSpeechRecognition()
                } else {
                    actuator.prepareForExternalRiskAnnouncement()
                }
                dispatch = dispatchToneReplacement(runtime, actuator, true, next)
            }

            actuator.playProgressBeep(50)

            if (cancellation == "recognition") assertEquals(true, recognitionPrepared)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch)
            assertEquals(2, runtime.focusRequests)
            assertEquals(1, runtime.focusReleases)
            val cancelledListener = runtime.focusRegistrations[0].listener
            val replacement = runtime.focusRegistrations[1]
            assertTrue(replacement.isRisk)
            assertEquals(listOf(cancelledListener), runtime.releasedFocusListeners)
            assertEquals(setOf(replacement.listener), runtime.grantedFocusListeners)
            assertEquals(0, runtime.toneCreates)
            assertEquals(1, runtime.scheduledTasksSnapshot().size)
            cancelledListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            runtime.runCurrent()
            assertEquals(1, runtime.focusReleases)
            assertEquals(setOf(replacement.listener), runtime.grantedFocusListeners)
            completeToneReplacement(runtime, actuator, next, 1)
            assertTrue(runtime.grantedFocusListeners.isEmpty())
        }
    }

    @Test
    fun blockedRecognitionPreservesPendingAndGrantedRiskFocusOwnership() {
        val (runtime, actuator) = readyActuator()
        val next = Events()
        var recognitionPrepared: Boolean? = null
        runtime.onFocusRequest = {
            recognitionPrepared = actuator.prepareForSpeechRecognition()
        }

        val dispatch = dispatchToneReplacement(runtime, actuator, true, next)

        assertEquals(false, recognitionPrepared)
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch)
        assertEquals(1, runtime.focusRequests)
        assertEquals(0, runtime.focusReleases)
        val listener = runtime.focusRegistrations.single().listener
        assertEquals(setOf(listener), runtime.grantedFocusListeners)
        val stopsBeforeRecognition = runtime.stops
        assertFalse(actuator.prepareForSpeechRecognition())
        assertEquals(stopsBeforeRecognition, runtime.stops)
        assertTrue(actuator.isAppSpeechActive())
        assertEquals(0, runtime.focusReleases)
        assertEquals(setOf(listener), runtime.grantedFocusListeners)
        assertEquals(0, next.completed)
        assertEquals(0, next.failed)
        assertEquals(0, next.cancelled)

        // Rejected recognition must not stale the current registration's loss callback.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        runtime.runCurrent()
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(1, next.failed)
        assertEquals(0, next.completed)
        assertEquals(0, next.cancelled)
        assertEquals(1, runtime.focusReleases)
        assertTrue(runtime.grantedFocusListeners.isEmpty())
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        runtime.advanceBy(20_500L)
        assertEquals(1, next.failed)
        assertEquals(1, runtime.focusReleases)
        assertEquals(1, runtime.engines.size)
    }

    @Test
    fun stopCompletesBeforeDistanceIsReadFromLatestEvidence() {
        val (runtime, actuator) = readyActuator()
        var currentDistance = "old 2.5"
        var reads = 0
        var completed = 0
        val action = riskAction(runtime.now + 800, "멈추세요. 물체가 감지됐습니다.", MessageLevel.STOP)
        actuator.emit(action, { completed++ }, {}, { true }, distanceAfterWarning = {
            reads++
            ObstacleDistanceSpeech(currentDistance, runtime.now + 800) { true }
        })
        val stop = runtime.spoken.single()
        assertTrue(stop.message.startsWith("멈추세요."))
        assertEquals(0, reads)
        runtime.start(stop.id)
        runtime.advanceBy(2_000)
        currentDistance = "latest 1.2"
        runtime.done(stop.id)
        assertEquals(listOf(stop.message, "latest 1.2"), runtime.spoken.map { it.message })
        assertEquals(1, completed)
        assertEquals(1, reads)
        val detail = runtime.spoken.last().id
        runtime.start(detail)
        runtime.done(detail)
        assertEquals("Distance completion must not confirm the warning twice", 1, completed)
    }

    @Test
    fun distanceDoesNotPreemptAnotherWarningAndANewStopCanInterruptIt() {
        val (runtime, actuator) = readyActuator()
        actuator.emit(riskAction(runtime.now + 800, "first stop", MessageLevel.STOP), {}, {}, { true },
            distanceAfterWarning = { ObstacleDistanceSpeech("distance", runtime.now + 800) { true } })
        val first = runtime.spoken.single().id
        runtime.start(first)
        runtime.done(first)
        assertEquals("distance", runtime.spoken.last().message)
        runtime.start(runtime.spoken.last().id)
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, actuator.speakInteraction("command", {}, {}))
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, actuator.speakNavigation("route", isStillValidAtStart = { true }))
        actuator.emit(riskAction(runtime.now + 800, "second stop", MessageLevel.STOP), {}, {}, { true })
        assertEquals("second stop", runtime.spoken.last().message)
    }

    @Test
    fun expiredOrLostTargetDistanceCannotStartAfterWarning() {
        for (expired in listOf(false, true)) {
            val (runtime, actuator) = readyActuator()
            actuator.emit(riskAction(runtime.now + 800), {}, {}, { true }, distanceAfterWarning = {
                ObstacleDistanceSpeech("distance", if (expired) runtime.now - 1 else runtime.now + 800) { expired }
            })
            val id = runtime.spoken.single().id
            runtime.start(id)
            runtime.done(id)
            assertEquals(1, runtime.spoken.size)
        }
    }

    @Test
    fun interruptedWarningNeverRequestsDistanceAndDelayedDistanceStartExpires() {
        val (runtime, actuator) = readyActuator()
        var reads = 0
        actuator.emit(riskAction(runtime.now + 800), {}, {}, { true }, distanceAfterWarning = {
            reads++
            ObstacleDistanceSpeech("distance", runtime.now + 800) { true }
        })
        val warning = runtime.spoken.single().id
        runtime.start(warning)
        actuator.emit(riskAction(runtime.now + 800, "STOP", MessageLevel.STOP), {}, {}, { true })
        runtime.done(warning)
        assertEquals(0, reads)
        val (late, other) = readyActuator()
        other.emit(riskAction(late.now + 800), {}, {}, { true }, distanceAfterWarning = {
            ObstacleDistanceSpeech("distance", late.now + 10) { true }
        })
        val first = late.spoken.single().id
        late.start(first)
        late.done(first)
        val detail = late.spoken.last().id
        late.advanceBy(11)
        late.start(detail)
        assertFalse(other.isAppSpeechActive())
    }

    @Test
    fun distanceDoesNotEraseWarningDuplicateHistoryOrPreemptReentrantWarning() {
        val (runtime, actuator) = readyActuator()
        val action = riskAction(runtime.now + 800, "stop", MessageLevel.STOP)
        actuator.emit(action, {}, {}, { true }, distanceAfterWarning = {
            ObstacleDistanceSpeech("distance", runtime.now + 800) { true }
        })
        runtime.start(runtime.spoken.single().id)
        runtime.done(runtime.spoken.single().id)
        runtime.start(runtime.spoken.last().id)
        runtime.done(runtime.spoken.last().id)
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, actuator.emit(action, {}, {}, { true }).speech)
        assertEquals(2, runtime.spoken.size)

        val (otherRuntime, other) = readyActuator()
        other.emit(riskAction(otherRuntime.now + 800, "warning"), {
            other.emit(riskAction(otherRuntime.now + 800, "new stop", MessageLevel.STOP), {}, {}, { true })
        }, {}, { true }, distanceAfterWarning = {
            ObstacleDistanceSpeech("old distance", otherRuntime.now + 800) { true }
        })
        val id = otherRuntime.spoken.single().id
        otherRuntime.start(id)
        otherRuntime.done(id)
        assertEquals(listOf("warning", "new stop"), otherRuntime.spoken.map { it.message })
    }

    private fun readyActuator(
        recoveryBudget: TtsEngineRecoveryBudget = TtsEngineRecoveryBudget(),
    ): Pair<FakeRuntime, AndroidFeedbackActuator> {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime, recoveryBudget = recoveryBudget)
        runtime.ready()
        assertEquals("tts=ready", actuator.statusText())
        return runtime to actuator
    }

    private fun acceptNavigation(
        actuator: AndroidFeedbackActuator,
        message: String,
        events: Events,
        current: () -> Boolean = { true },
    ) {
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakNavigation(
                message = message,
                onCompleted = { events.completed += 1 },
                onFailed = { events.failed += 1 },
                onCancelled = { events.cancelled += 1 },
                isStillValidAtStart = current,
                requiresExplicitTerminalCallback = true,
            ),
        )
    }

    private fun riskAction(
        deadline: Long,
        message: String = "risk",
        level: MessageLevel = MessageLevel.WARNING,
    ) = FeedbackAction(
        trackId = "risk-owner",
        message = message,
        level = level,
        vibrationPatternMs = null,
        reason = "runtime-regression",
        validUntilMs = deadline,
    )

    private class Events {
        var completed = 0
        var failed = 0
        var cancelled = 0
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Engine(
            val initialized: (Int) -> Unit,
            var listener: FeedbackSpeechRuntime.Listener? = null,
        )
        data class Spoken(val message: String, val id: String, val queueMode: Int)
        data class Task(val due: Long, val order: Long, val runnable: Runnable)
        data class FocusRegistration(
            val isRisk: Boolean,
            val listener: AudioManager.OnAudioFocusChangeListener,
        )

        class Tone(private val runtime: FakeRuntime) : ProgressToneEffects {
            var starts = 0
            var releases = 0
            var lastDurationMs = 0
            override fun startTone(toneType: Int, durationMs: Int): Boolean {
                starts += 1
                lastDurationMs = durationMs
                val callback = runtime.onToneStart
                runtime.onToneStart = null
                callback?.invoke()
                if (runtime.toneStartThrows) throw IllegalStateException("tone start")
                return runtime.toneStartResult
            }
            override fun stopTone() = Unit
            override fun release() {
                releases += 1
                val callback = runtime.onToneRelease
                runtime.onToneRelease = null
                callback?.invoke()
                if (runtime.toneReleaseThrows) throw IllegalStateException("tone release")
            }
        }

        var now = 1_000L
        val engines = mutableListOf<Engine>()
        val spoken = mutableListOf<Spoken>()
        var activeSpeechId: String? = null
        val tones = mutableListOf<Tone>()
        var toneCreates = 0
        var toneFactoryThrows = false
        var toneStartThrows = false
        var toneStartResult = true
        var toneReleaseThrows = false
        var onToneCreate: (() -> Unit)? = null
        var onToneStart: (() -> Unit)? = null
        var onToneRelease: (() -> Unit)? = null
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        var attributesThrow = false
        var listenerThrows = false
        var speakThrows = false
        var speakResult = TextToSpeech.SUCCESS
        var focusRequestThrows = false
        var focusRequestResult = true
        var onFocusRequest: (() -> Unit)? = null
        var onFocusRelease: (() -> Unit)? = null
        var onStop: (() -> Unit)? = null
        val focusRegistrations = mutableListOf<FocusRegistration>()
        val grantedFocusListeners = mutableSetOf<AudioManager.OnAudioFocusChangeListener>()
        val releasedFocusListeners = mutableListOf<AudioManager.OnAudioFocusChangeListener>()
        var focusReleaseThrows = false
        var stopThrows = false
        var shutdownThrows = false
        var speakCalls = 0
        var focusRequests = 0
        var stops = 0
        @Volatile var spokeWhileStopping = false
        @Volatile private var stopInProgress = false
        var shutdowns = 0
        var focusReleases = 0

        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = engines.isNotEmpty()
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) {
            engines += Engine(onInitialized)
        }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() {
            if (attributesThrow) throw IllegalStateException("audio attributes")
        }
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            if (listenerThrows) throw IllegalStateException("listener")
            engines.last().listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            speakCalls += 1
            if (stopInProgress) spokeWhileStopping = true
            if (speakThrows) throw IllegalStateException("speak")
            if (speakResult == TextToSpeech.SUCCESS) {
                spoken += Spoken(message, utteranceId, queueMode)
                if (queueMode == TextToSpeech.QUEUE_FLUSH || activeSpeechId == null) {
                    activeSpeechId = utteranceId
                }
            }
            return speakResult
        }
        override fun stop() {
            stops += 1
            stopInProgress = true
            try {
                activeSpeechId = null
                onStop?.invoke()
                if (stopThrows) throw IllegalStateException("stop")
            } finally {
                stopInProgress = false
            }
        }
        override fun shutdown() {
            shutdowns += 1
            if (shutdownThrows) throw IllegalStateException("shutdown")
        }
        override fun requestAudioFocus(
            isRisk: Boolean,
            listener: AudioManager.OnAudioFocusChangeListener,
        ): Boolean {
            focusRequests += 1
            focusRegistrations += FocusRegistration(isRisk, listener)
            val callback = onFocusRequest
            onFocusRequest = null
            callback?.invoke()
            if (focusRequestThrows) throw IllegalStateException("request focus")
            if (focusRequestResult) grantedFocusListeners += listener
            return focusRequestResult
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) {
            focusReleases += 1
            releasedFocusListeners += listener
            grantedFocusListeners.remove(listener)
            val callback = onFocusRelease
            onFocusRelease = null
            callback?.invoke()
            if (focusReleaseThrows) throw IllegalStateException("release focus")
        }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects {
            toneCreates += 1
            val callback = onToneCreate
            onToneCreate = null
            callback?.invoke()
            if (toneFactoryThrows) throw IllegalStateException("tone factory")
            return Tone(this).also(tones::add)
        }
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) {
            tasks.removeAll { it.runnable === task }
        }
        override fun removeCallbacksAndMessages(token: Any?) {
            tasks.clear()
        }
        fun ready() {
            engines.last().initialized(TextToSpeech.SUCCESS)
            runCurrent()
        }
        fun start(id: String) {
            checkNotNull(engines.last().listener).onStart(id)
            runCurrent()
        }
        fun done(id: String) {
            checkNotNull(engines.last().listener).onDone(id)
            runCurrent()
        }
        fun error(id: String, code: Int) {
            checkNotNull(engines.last().listener).onError(id, code)
            runCurrent()
        }
        fun runCurrent() = advanceBy(0L)
        fun scheduledTasksSnapshot(): List<Task> = tasks.toList()
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }
                    .minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = next.due
                next.runnable.run()
            }
            now = target
        }
    }
}
