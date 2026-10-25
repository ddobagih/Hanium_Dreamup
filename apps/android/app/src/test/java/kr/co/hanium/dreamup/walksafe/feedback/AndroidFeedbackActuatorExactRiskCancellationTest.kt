package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S13-7's actuator boundary: confirmed invalidity is supplied by the caller, not inferred here. */
class AndroidFeedbackActuatorExactRiskCancellationTest {
    @Test
    fun acceptedExactCancellationHasOneLogicalObservationEvenWithoutNativeStopAcknowledgement() {
        Fixture(haptics = true).use { fixture ->
            val delivery = fixture.issue()
            val observations = fixture.observe()
            fixture.runtime.advanceBy(200L)
            fixture.runtime.throwOnStop = true
            assertTrue(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals(delivery.id, fixture.runtime.activeSpeechId)
            assertEquals(1, fixture.runtime.stops)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(0, delivery.events.completed + delivery.events.failed)
            println("acceptedExactCancellationOutcomes=${observations.map { it.outcome.name }}")
            assertEquals("the accepted logical cancellation must be observable despite native stop failure",
                listOf("CANCELLED_RISK"), observations.map { it.outcome.name })
            assertEquals(SpeechOutputKind.RISK, observations.single().kind)
            assertEquals(1_200L, observations.single().atElapsedRealtimeMs)
            assertNull(observations.single().errorCode)
            assertFalse(observations.single().toString().contains(delivery.id))
            assertFalse(observations.single().toString().contains(delivery.action.message))
            fixture.runtime.callbacksFor(delivery.id)
            assertFalse(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals(1, observations.size)
            assertEquals(0, fixture.runtime.vibrationCancellations)
            assertEquals("tts=ready", fixture.actuator.statusText())
        }
    }

    @Test
    fun cancellationObservationCanWaitForANewOwnerOutsideLocksAfterSynchronousNativeCallbacks() {
        Fixture().use { fixture ->
            val delivery = fixture.issue()
            val observations = mutableListOf<SpeechOutputEvent>()
            val order = mutableListOf<String>()
            var duplicateDuringStop: Boolean? = null
            var observerWhileStopping: Boolean? = null
            var observerJoined = false
            val newOwnerResult = AtomicReference<NavigationSpeechDispatchResult>()
            fixture.runtime.inlinePosts = true
            fixture.runtime.onStop = { id ->
                fixture.runtime.callbacksFor(requireNotNull(id))
                duplicateDuringStop = fixture.actuator.cancelRiskSpeech(delivery.action)
            }
            delivery.events.onCancelled = { order += "upper" }
            fixture.actuator.speechOutputListener = { event ->
                synchronized(observations) { observations += event }
                if (event.outcome.name == "CANCELLED_RISK") {
                    order += "observer"
                    observerWhileStopping = fixture.runtime.stopping
                    val request = thread {
                        newOwnerResult.set(fixture.actuator.emit(unownedWinner(), null, null, { true }).speech)
                    }
                    request.join(2_000L)
                    observerJoined = !request.isAlive
                }
            }
            assertTrue(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals(false, duplicateDuringStop)
            assertEquals(false, observerWhileStopping)
            assertTrue("the observer must not hold pending or engine admission locks", observerJoined)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, newOwnerResult.get())
            assertEquals(listOf("upper", "observer"), order)
            val newId = fixture.runtime.spoken.last().id
            assertEquals(newId, fixture.runtime.activeSpeechId)
            fixture.runtime.callbacksFor(delivery.id)
            assertEquals(newId, fixture.runtime.activeSpeechId)
            assertEquals(1, fixture.runtime.stops)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(0, delivery.events.completed + delivery.events.failed)
            assertEquals(1, observations.count { it.outcome.name == "CANCELLED_RISK" })
            assertEquals(0, observations.count { it.outcome.name in listOf("DONE", "STOPPED", "INTERRUPTED") })
        }
    }

    @Test
    fun throwingCancellationObserverCannotChangeTheAcceptedCancellationOrCreateAReplay() {
        Fixture().use { fixture ->
            val delivery = fixture.issue()
            var observed = 0
            fixture.actuator.speechOutputListener = { event ->
                if (event.outcome.name == "CANCELLED_RISK") {
                    observed += 1
                    error("diagnostic observer failed")
                }
            }
            assertTrue(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals(1, observed)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(0, delivery.events.completed + delivery.events.failed)
            assertEquals(1, fixture.runtime.stops)
            assertFalse(fixture.actuator.cancelRiskSpeech(delivery.action))
            fixture.runtime.callbacksFor(delivery.id)
            assertEquals(1, observed)
            val retry = fixture.issue()
            assertEquals(retry.id, fixture.runtime.activeSpeechId)
            assertEquals("tts=ready", fixture.actuator.statusText())
        }
    }

    @Test
    fun absentCancellationObserverKeepsTheExistingClockReadBudget() {
        Fixture().use { fixture ->
            val delivery = fixture.issue()
            fixture.actuator.speechOutputListener = null
            val before = fixture.runtime.clockCalls
            assertTrue(fixture.actuator.cancelRiskSpeech(delivery.action))
            val cancellationClockCalls = fixture.runtime.clockCalls - before
            println("absentExactCancellationClockCalls=$cancellationClockCalls")
            // Accepted 611's same path reads twice in the gate and once for the quiet period.
            assertEquals("no observer must add no diagnostic clock read", 3, cancellationClockCalls)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(1, fixture.runtime.stops)
        }
    }

    @Test
    fun exactStartedOwnerCancelsSpeechOnceAndKeepsStopHapticsAndReadyEngine() {
        Fixture(haptics = true).use { fixture ->
            val delivery = fixture.issue()
            assertEquals(1, fixture.runtime.vibrations)
            assertTrue(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertNull(fixture.runtime.activeSpeechId)
            assertEquals(1, fixture.runtime.stops)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(0, delivery.events.completed + delivery.events.failed)
            assertEquals(0, fixture.runtime.vibrationCancellations)
            assertFalse(fixture.actuator.isAppSpeechActive())
            assertEquals("tts=ready", fixture.actuator.statusText())
            assertEquals(1, fixture.runtime.engineCount)
            fixture.runtime.callbacksFor(delivery.id)
            fixture.runtime.advanceBy(20_000L)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(0, delivery.events.completed + delivery.events.failed)
            assertEquals(1, fixture.runtime.stops)
        }
    }

    @Test
    fun exactRegisteredUnstartedOwnerCanBeDiscardedWithoutWaitingForStart() {
        Fixture().use { fixture ->
            val delivery = fixture.issue(start = false)
            assertTrue(fixture.actuator.cancelRiskSpeech(delivery.action))
            fixture.runtime.callbacksFor(delivery.id)
            assertEquals(1, fixture.runtime.stops)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(0, delivery.events.completed + delivery.events.failed)
            assertFalse(fixture.actuator.isAppSpeechActive())
        }
    }

    @Test
    fun matchingTextTrackAndEvaluationTimeCannotForgeTheRegisteredOwner() {
        Fixture().use { fixture ->
            val delivery = fixture.issue()
            val observations = fixture.observe()
            val action = delivery.action
            val actualOwner = requireNotNull(action.deliveryOwner)
            val fabricatedOwner = FeedbackDeliveryOwner(action.trackId, actualOwner.evaluatedAtMs,
                action.message, action.level, action.validUntilMs, null, action.reason)
            assertTrue(fabricatedOwner.markClaimed())
            val otherPolicy = WalkSafeFeedbackPolicy()
            val otherAction = requireNotNull(otherPolicy.evaluate(fixture.current, true, fixture.runtime.nowMs))
            assertTrue(otherPolicy.claimFeedbackDelivery(otherAction, fixture.runtime.nowMs, fixture.runtime.nowMs))
            assertEquals(actualOwner.evaluatedAtMs, requireNotNull(otherAction.deliveryOwner).evaluatedAtMs)
            for (impostor in listOf(
                action.copy(deliveryOwner = null),
                action.copy(deliveryOwner = fabricatedOwner),
                otherAction,
                action.copy(trackId = "another-risk"),
                action.copy(message = "another message"),
                action.copy(level = MessageLevel.WARNING),
                action.copy(validUntilMs = action.validUntilMs + 1L),
                action.copy(reason = "another reason"),
            )) assertFalse(fixture.actuator.cancelRiskSpeech(impostor))
            assertEquals(0, fixture.runtime.stops)
            assertEquals(delivery.id, fixture.runtime.activeSpeechId)
            assertEquals(0, delivery.events.cancelled + delivery.events.failed + delivery.events.completed)
            assertTrue(observations.isEmpty())
            fixture.runtime.done(delivery.id)
            assertEquals(1, delivery.events.completed)
        }
    }

    @Test
    fun policyRevocationBeforeCancellationStillFindsTheExactNativeRegistration() {
        Fixture().use { fixture ->
            val delivery = fixture.issue()
            fixture.policy.rejectUndeliveredFeedback(delivery.action, delivery.evaluatedAtMs)
            assertFalse(requireNotNull(delivery.action.deliveryOwner).isClaimed())
            assertTrue(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals(1, fixture.runtime.stops)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(0, delivery.events.completed + delivery.events.failed)
        }
    }

    @Test
    fun duplicateAndCompletedCancellationCannotStopAFollowingNavigationOwner() {
        Fixture().use { fixture ->
            val delivery = fixture.issue()
            val observations = fixture.observe()
            assertTrue(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertFalse(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                fixture.actuator.speakNavigation("다음 안내입니다."))
            val navigationId = fixture.runtime.spoken.last().id
            assertFalse(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals(navigationId, fixture.runtime.activeSpeechId)
            assertEquals(1, fixture.runtime.stops)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(1, observations.count { it.outcome.name == "CANCELLED_RISK" })
        }
        Fixture().use { fixture ->
            val completed = fixture.issue()
            fixture.runtime.done(completed.id)
            val observations = fixture.observe()
            assertFalse(fixture.actuator.cancelRiskSpeech(completed.action))
            assertEquals(0, fixture.runtime.stops)
            assertEquals(1, completed.events.completed)
            assertEquals(0, completed.events.cancelled + completed.events.failed)
            assertTrue(observations.isEmpty())
        }
    }

    @Test
    fun cancelledSameSentenceCanBeRetriedImmediatelyWithANewIssuedOwner() {
        Fixture().use { fixture ->
            val old = fixture.issue()
            assertTrue(fixture.actuator.cancelRiskSpeech(old.action))
            val retry = fixture.issue()
            assertEquals(old.action.message, retry.action.message)
            assertTrue(old.action.deliveryOwner !== retry.action.deliveryOwner)
            assertEquals(old.evaluatedAtMs, retry.evaluatedAtMs)
            assertFalse(fixture.actuator.cancelRiskSpeech(old.action))
            fixture.runtime.callbacksFor(old.id)
            assertEquals(retry.id, fixture.runtime.activeSpeechId)
            assertEquals(0, retry.events.cancelled + retry.events.failed + retry.events.completed)
            fixture.runtime.done(retry.id)
            assertEquals(1, retry.events.completed)
            assertEquals(1, fixture.runtime.stops)
        }
    }

    @Test
    fun delayedOldCancellationCannotStopAnAdmittedSameRankReplacement() {
        Fixture().use { fixture ->
            val old = fixture.issue()
            fixture.runtime.advanceBy(800L)
            fixture.current = risk(fixture.runtime.nowMs, ttcMs = 250L, distanceM = 1.0f)
            val replacement = fixture.issue()
            assertTrue(requireNotNull(replacement.action.deliveryOwner).replacesDeliveryOwner === old.action.deliveryOwner)
            assertEquals(1, old.events.cancelled)
            val observations = fixture.observe()
            assertFalse(fixture.actuator.cancelRiskSpeech(old.action))
            fixture.runtime.callbacksFor(old.id)
            assertTrue(observations.isEmpty())
            assertEquals(replacement.id, fixture.runtime.activeSpeechId)
            assertEquals(0, fixture.runtime.stops)
            assertEquals(0, replacement.events.cancelled + replacement.events.failed + replacement.events.completed)
            fixture.runtime.done(replacement.id)
            assertEquals(1, replacement.events.completed)
        }
    }

    @Test
    fun oldCancelWaitingForNativeReplacementAdmissionCannotAcquireTheNewOwner() {
        Fixture().use { fixture ->
            val old = fixture.issue()
            fixture.runtime.advanceBy(800L)
            fixture.current = risk(fixture.runtime.nowMs, ttcMs = 250L, distanceM = 1.0f)
            val proposal = fixture.reserve()
            val enteredSpeak = CountDownLatch(1)
            val releaseSpeak = CountDownLatch(1)
            fixture.runtime.onSpeak = {
                enteredSpeak.countDown()
                check(releaseSpeak.await(2, TimeUnit.SECONDS))
            }
            val replacementResult = AtomicReference<NavigationSpeechDispatchResult>()
            val replacementEvents = Events()
            val replacementThread = thread {
                replacementResult.set(fixture.emit(proposal, replacementEvents))
            }
            assertTrue(enteredSpeak.await(2, TimeUnit.SECONDS))
            val cancelEntered = CountDownLatch(1)
            val cancelResult = AtomicReference<Boolean>()
            val cancellationThread = thread {
                cancelEntered.countDown()
                cancelResult.set(fixture.actuator.cancelRiskSpeech(old.action))
            }
            assertTrue(cancelEntered.await(2, TimeUnit.SECONDS))
            releaseSpeak.countDown()
            replacementThread.join(2_000L)
            cancellationThread.join(2_000L)
            assertFalse(replacementThread.isAlive)
            assertFalse(cancellationThread.isAlive)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, replacementResult.get())
            assertEquals(false, cancelResult.get())
            assertEquals(0, fixture.runtime.stops)
            assertEquals(1, old.events.cancelled)
            val replacementId = fixture.runtime.spoken.last().id
            fixture.runtime.start(replacementId)
            fixture.runtime.done(replacementId)
            assertEquals(1, replacementEvents.completed)
            assertEquals(0, replacementEvents.cancelled + replacementEvents.failed)
        }
    }

    @Test
    fun startedSpeechSurvivesItsStartTtlAndOneFrameMissWithoutAnExplicitCancelRequest() {
        Fixture().use { fixture ->
            val delivery = fixture.issue()
            assertTrue(fixture.policy.activeFeedbackDeliveryKeys(emptyList(), true, fixture.runtime.nowMs).isEmpty())
            fixture.runtime.advanceBy(801L)
            assertEquals(delivery.id, fixture.runtime.activeSpeechId)
            assertTrue(fixture.actuator.isAppSpeechActive())
            assertEquals(0, fixture.runtime.stops)
            assertEquals(0, delivery.events.cancelled + delivery.events.failed + delivery.events.completed)
            fixture.runtime.done(delivery.id)
            assertEquals(1, delivery.events.completed)
            assertEquals(listOf(true), delivery.events.confirmations)
        }
    }

    @Test
    fun synchronousStopCallbacksAndNativeReentryCannotBeatTheOneCancellationConsumer() {
        Fixture().use { fixture ->
            val delivery = fixture.issue()
            fixture.runtime.inlinePosts = true
            var duplicateCancellation: Boolean? = null
            var nativeReentry: NavigationSpeechDispatchResult? = null
            var callbackWhileStopping: Boolean? = null
            val callbackThreadResult = AtomicReference<NavigationSpeechDispatchResult>()
            var callbackThreadJoined = false
            fixture.runtime.onStop = { id ->
                fixture.runtime.callbacksFor(requireNotNull(id))
                duplicateCancellation = fixture.actuator.cancelRiskSpeech(delivery.action)
                nativeReentry = fixture.actuator.emit(unownedWinner(), null, null, { true }).speech
            }
            delivery.events.onCancelled = {
                callbackWhileStopping = fixture.runtime.stopping
                val request = thread {
                    callbackThreadResult.set(fixture.actuator.emit(unownedWinner(), null, null, { true }).speech)
                }
                request.join(2_000L)
                callbackThreadJoined = !request.isAlive
            }
            assertTrue(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals(false, duplicateCancellation)
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, nativeReentry)
            assertEquals(false, callbackWhileStopping)
            assertTrue("upper callback must be able to wait for a request on another thread", callbackThreadJoined)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, callbackThreadResult.get())
            val winnerId = fixture.runtime.spoken.last().id
            assertEquals(winnerId, fixture.runtime.activeSpeechId)
            assertEquals(1, fixture.runtime.stops)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(0, delivery.events.completed + delivery.events.failed)
            fixture.runtime.callbacksFor(delivery.id)
            assertEquals(winnerId, fixture.runtime.activeSpeechId)
            assertEquals("tts=ready", fixture.actuator.statusText())
        }
    }

    @Test
    fun platformStopExceptionIsALogicalCancellationWithoutAPlaybackAcknowledgement() {
        Fixture().use { fixture ->
            val delivery = fixture.issue()
            fixture.runtime.throwOnStop = true
            assertTrue(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals("fake platform did not acknowledge a stopped output", delivery.id, fixture.runtime.activeSpeechId)
            assertFalse(fixture.actuator.isAppSpeechActive())
            assertEquals(1, fixture.runtime.stops)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(0, delivery.events.completed + delivery.events.failed)
            assertFalse(fixture.actuator.cancelRiskSpeech(delivery.action))
            fixture.runtime.done(delivery.id)
            assertEquals(1, delivery.events.cancelled)
            assertEquals(0, delivery.events.completed + delivery.events.failed)
            assertEquals("tts=ready", fixture.actuator.statusText())
            assertEquals(1, fixture.runtime.engineCount)
        }
    }

    @Test
    fun riskCancellationCannotCancelAnInteractionOrAnUnregisteredReservedRisk() {
        Fixture().use { fixture ->
            val proposal = fixture.reserve()
            assertTrue(fixture.actuator.speakInteraction("확인했습니다."))
            val interactionId = fixture.runtime.spoken.last().id
            val observations = fixture.observe()
            assertFalse(fixture.actuator.cancelRiskSpeech(proposal))
            assertEquals(interactionId, fixture.runtime.activeSpeechId)
            assertEquals(0, fixture.runtime.stops)
            assertTrue(observations.isEmpty())
        }
    }

    @Test
    fun registeredRiskCancellationFromRetiredNavigationCallbackClearsOnlyTheRetiredNativePredecessor() {
        Fixture().use { fixture ->
            val risk = fixture.reserve()
            val riskEvents = Events()
            var navigationCancelled = 0
            var cancellationAccepted: Boolean? = null
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, fixture.actuator.speakNavigation(
                "기존 길안내입니다.",
                onCancelled = {
                    navigationCancelled += 1
                    cancellationAccepted = fixture.actuator.cancelRiskSpeech(risk)
                },
                requiresExplicitTerminalCallback = true,
            ))
            val navigationId = fixture.runtime.spoken.single().id
            fixture.runtime.start(navigationId)
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, fixture.emit(risk, riskEvents))
            assertEquals(true, cancellationAccepted)
            assertEquals(1, navigationCancelled)
            assertEquals(1, riskEvents.cancelled)
            assertEquals(0, riskEvents.completed + riskEvents.failed)
            // Navigation's owner is already retired by risk admission. B's cancellation must
            // drain this stale native A even though the native B speak has not been submitted.
            assertEquals(listOf(navigationId), fixture.runtime.stoppedSpeechIds)
            assertNull(fixture.runtime.activeSpeechId)
            assertEquals(1, fixture.runtime.spoken.size)
            fixture.runtime.callbacksFor(navigationId)
            assertEquals(1, navigationCancelled)
            assertEquals(1, riskEvents.cancelled)
            assertEquals("tts=ready", fixture.actuator.statusText())
        }
    }

    @Test
    fun registeredRiskCancellationDuringFocusRequestClearsItsAlreadyRetiredNativeInteraction() {
        Fixture().use { fixture ->
            val risk = fixture.reserve()
            val riskEvents = Events()
            val interactionEvents = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, fixture.actuator.speakInteraction(
                "기존 상호작용입니다.",
                onCompleted = { interactionEvents.completed += 1 },
                onFailed = { interactionEvents.failed += 1 },
                onCancelled = { interactionEvents.cancelled += 1 },
            ))
            val interactionId = fixture.runtime.spoken.single().id
            fixture.runtime.start(interactionId)
            var cancellationAccepted: Boolean? = null
            var interactionRetiredBeforeFocus = false
            fixture.runtime.onFocus = { isRisk ->
                if (isRisk) {
                    interactionRetiredBeforeFocus = interactionEvents.cancelled == 1
                    cancellationAccepted = fixture.actuator.cancelRiskSpeech(risk)
                }
            }
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, fixture.emit(risk, riskEvents))
            assertEquals(true, cancellationAccepted)
            assertTrue(interactionRetiredBeforeFocus)
            assertEquals(1, interactionEvents.cancelled)
            assertEquals(0, interactionEvents.completed + interactionEvents.failed)
            assertEquals(1, riskEvents.cancelled)
            assertEquals(0, riskEvents.completed + riskEvents.failed)
            assertEquals(listOf(interactionId), fixture.runtime.stoppedSpeechIds)
            assertNull(fixture.runtime.activeSpeechId)
            assertEquals(1, fixture.runtime.spoken.size)
            fixture.runtime.callbacksFor(interactionId)
            assertEquals(1, interactionEvents.cancelled)
            assertEquals(1, riskEvents.cancelled)
            assertEquals("tts=ready", fixture.actuator.statusText())
        }
    }

    @Test
    fun closedOrRecoveredEngineCannotBeStoppedByItsFormerOwner() {
        // Existing replacement-engine fixtures inject one retry; the product interim is zero.
        Fixture(recoveryBudget = TtsEngineRecoveryBudget(automaticRetries = 1)).use { fixture ->
            val delivery = fixture.issue()
            fixture.runtime.error(delivery.id, TextToSpeech.ERROR_SERVICE)
            fixture.runtime.advanceBy(250L)
            fixture.runtime.ready()
            val stopsAfterRecovery = fixture.runtime.stops
            val observations = fixture.observe()
            assertFalse(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals(stopsAfterRecovery, fixture.runtime.stops)
            assertTrue(observations.isEmpty())
            assertEquals("tts=ready", fixture.actuator.statusText())
            assertEquals(2, fixture.runtime.engineCount)
        }
        Fixture().use { fixture ->
            val delivery = fixture.issue()
            fixture.actuator.close()
            val stopsAfterClose = fixture.runtime.stops
            val observations = fixture.observe()
            assertFalse(fixture.actuator.cancelRiskSpeech(delivery.action))
            assertEquals(stopsAfterClose, fixture.runtime.stops)
            assertTrue(observations.isEmpty())
        }
    }

    private inner class Fixture(
        haptics: Boolean = false,
        recoveryBudget: TtsEngineRecoveryBudget = TtsEngineRecoveryBudget(),
    ) : AutoCloseable {
        val runtime = FakeRuntime()
        val policy = WalkSafeFeedbackPolicy()
        val actuator = AndroidFeedbackActuator(runtime = runtime, hapticAllowed = { haptics },
            recoveryBudget = recoveryBudget)
        var current = risk(1_000L)
        init { runtime.ready() }

        fun observe(): MutableList<SpeechOutputEvent> = mutableListOf<SpeechOutputEvent>().also { events ->
            actuator.speechOutputListener = { event -> synchronized(events) { events += event } }
        }

        fun reserve(): FeedbackAction {
            val action = requireNotNull(policy.evaluate(current, true, runtime.nowMs))
            assertTrue(policy.claimFeedbackDelivery(action, requireNotNull(action.deliveryOwner).evaluatedAtMs,
                runtime.nowMs))
            return action
        }

        fun issue(start: Boolean = true): Delivery {
            val action = reserve()
            val events = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, emit(action, events))
            val delivery = Delivery(action, runtime.spoken.last().id, events)
            if (start) runtime.start(delivery.id)
            return delivery
        }

        fun emit(action: FeedbackAction, events: Events): NavigationSpeechDispatchResult = actuator.emit(
            action,
            onSpeechCompleted = {
                events.completed += 1
                events.confirmations += policy.confirmFeedbackDelivery(action,
                    requireNotNull(action.deliveryOwner).evaluatedAtMs, runtime.nowMs)
            },
            onSpeechFailed = {
                events.failed += 1
                policy.rejectUndeliveredFeedback(action, requireNotNull(action.deliveryOwner).evaluatedAtMs)
            },
            onSpeechCancelled = {
                events.cancelled += 1
                policy.rejectUndeliveredFeedback(action, requireNotNull(action.deliveryOwner).evaluatedAtMs)
                events.onCancelled?.invoke()
            },
            isStillValidAtStart = {
                policy.hasClaimedFeedbackDelivery(action) && runtime.nowMs <= action.validUntilMs &&
                    action.deliveryKey in policy.activeFeedbackDeliveryKeys(listOf(current), true, runtime.nowMs)
            },
        ).speech

        override fun close() { actuator.close() }
    }

    private data class Delivery(val action: FeedbackAction, val id: String, val events: Events) {
        val evaluatedAtMs: Long get() = requireNotNull(action.deliveryOwner).evaluatedAtMs
    }
    private class Events {
        var completed = 0
        var failed = 0
        var cancelled = 0
        val confirmations = mutableListOf<Boolean>()
        var onCancelled: (() -> Unit)? = null
    }

    private fun risk(atMs: Long, ttcMs: Long = 2_000L, distanceM: Float = 1.1f) = FeedbackCandidate(
        trackId = "same-risk", message = "위험합니다. 멈추세요.", level = MessageLevel.STOP,
        source = DepthSource.ARCORE_RAW_DEPTH, confidence = .9f, timestampMs = atMs + 50_000L,
        trackAgeFrames = 20, trackStableMs = 4_000L, stale = false, validUntilMs = atMs + 800L,
        timeToCollisionMs = ttcMs, riskDistanceM = distanceM,
        observedAtElapsedRealtimeMs = atMs, observationMaxAgeMs = 800L,
    )

    private fun unownedWinner() = FeedbackAction("winner", "새로운 위험 안내", MessageLevel.STOP,
        null, "test-native-reentry", 2_000L)

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String, val queueMode: Int)
        private data class Task(val dueMs: Long, val order: Long, val runnable: Runnable)
        private var initialized: ((Int) -> Unit)? = null
        private lateinit var listener: FeedbackSpeechRuntime.Listener
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        var nowMs = 1_000L
            private set
        val spoken = mutableListOf<Spoken>()
        var activeSpeechId: String? = null
            private set
        var engineCount = 0
            private set
        var stops = 0
            private set
        val stoppedSpeechIds = mutableListOf<String?>()
        var vibrations = 0
            private set
        var vibrationCancellations = 0
            private set
        var stopping = false
            private set
        var inlinePosts = false
        var clockCalls = 0
        var throwOnStop = false
        var onStop: ((String?) -> Unit)? = null
        var onSpeak: (() -> Unit)? = null
        var onFocus: ((Boolean) -> Unit)? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long { clockCalls += 1; return nowMs }
        override fun initialize(onInitialized: (Int) -> Unit) {
            engineCount += 1
            initialized = onInitialized
        }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId, queueMode)
            if (queueMode == TextToSpeech.QUEUE_FLUSH || activeSpeechId == null) activeSpeechId = utteranceId
            onSpeak?.invoke()
            return TextToSpeech.SUCCESS
        }
        override fun stop() {
            stops += 1
            stoppedSpeechIds += activeSpeechId
            if (throwOnStop) throw IllegalStateException("simulated native stop error")
            stopping = true
            try {
                onStop?.invoke(activeSpeechId)
                activeSpeechId = null
            } finally {
                stopping = false
            }
        }
        override fun shutdown() { activeSpeechId = null }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            onFocus?.invoke(isRisk)
            return true
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun vibrate(patternMs: LongArray): Boolean { vibrations += 1; return true }
        override fun cancelVibration() { vibrationCancellations += 1 }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("unused")
        override fun post(task: Runnable): Boolean {
            if (inlinePosts) task.run() else postDelayed(task, 0L)
            return true
        }
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            synchronized(tasks) { tasks += Task(nowMs + delayMs.coerceAtLeast(0L), sequence++, task) }
            return true
        }
        override fun removeCallbacks(task: Runnable) { synchronized(tasks) { tasks.removeAll { it.runnable === task } } }
        override fun removeCallbacksAndMessages(token: Any?) { synchronized(tasks) { tasks.clear() } }
        fun ready() { checkNotNull(initialized)(TextToSpeech.SUCCESS); advanceBy(0L) }
        fun start(id: String) { listener.onStart(id); advanceBy(0L) }
        fun done(id: String) {
            if (activeSpeechId == id) activeSpeechId = null
            listener.onDone(id)
            advanceBy(0L)
        }
        fun error(id: String, code: Int) { listener.onError(id, code); advanceBy(0L) }
        fun callbacksFor(id: String) {
            listener.onStart(id)
            listener.onDone(id)
            listener.onError(id, TextToSpeech.ERROR_SERVICE)
            listener.onStop(id, interrupted = true)
            advanceBy(0L)
        }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val targetMs = nowMs + deltaMs
            while (true) {
                val next = synchronized(tasks) {
                    tasks.filter { it.dueMs <= targetMs }
                        .minWithOrNull(compareBy<Task> { it.dueMs }.thenBy { it.order })
                        ?.also(tasks::remove)
                } ?: break
                nowMs = next.dueMs
                next.runnable.run()
            }
            nowMs = targetMs
        }
    }
}
