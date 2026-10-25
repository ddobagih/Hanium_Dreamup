package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S13-5: the actual policy and actuator, with only Android effects and time replaced. */
class AndroidFeedbackActuatorStartedRiskWorseningRegressionTest {
    @Test
    fun startedClaimedStopYieldsToTrustedTtcWorseningWithoutOldCallbacksOwningReplacement() {
        val runtime = FakeRuntime()
        val policy = WalkSafeFeedbackPolicy()
        AndroidFeedbackActuator(runtime = runtime).use { actuator ->
            runtime.ready()
            var current = risk(runtime.nowMs, ttcMs = 2_000L, distanceM = 1.1f)
            val oldAction = requireNotNull(policy.evaluate(current, true, runtime.nowMs))
            val oldEvaluatedAtMs = runtime.nowMs
            assertTrue(policy.claimFeedbackDelivery(oldAction.trackId, oldEvaluatedAtMs, runtime.nowMs))
            val old = DeliveryEvents()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, policy, runtime, oldAction, oldEvaluatedAtMs, old) { current })
            val oldId = runtime.spoken.single().id
            runtime.start(oldId)
            assertTrue(actuator.isAppSpeechActive())
            assertTrue(policy.hasClaimedFeedbackDelivery(oldAction.trackId, MessageLevel.STOP))

            // The same stable, trusted target enters the existing imminent TTC band and halves
            // its TTC. Distance stays in the same band and moves by less than the .25 m margin.
            // The global 700 ms interval and native risk-message 800 ms interval have elapsed.
            runtime.advanceBy(800L)
            current = risk(runtime.nowMs, ttcMs = 250L, distanceM = 1.0f)
            val replacement = policy.evaluate(current, true, runtime.nowMs)
            assertNotNull("started CLAIMED STOP must not hide a trusted same-target TTC worsening", replacement)
            val replacementAction = requireNotNull(replacement)
            val replacementEvaluatedAtMs = runtime.nowMs
            assertEquals(oldAction.deliveryKey, replacementAction.deliveryKey)
            assertTrue(policy.claimFeedbackDelivery(replacementAction.trackId,
                replacementEvaluatedAtMs, runtime.nowMs))
            val next = DeliveryEvents()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, policy, runtime, replacementAction, replacementEvaluatedAtMs, next) { current })
            assertEquals(2, runtime.spoken.size)
            val replacementId = runtime.spoken.last().id
            assertEquals(TextToSpeech.QUEUE_FLUSH, runtime.spoken.last().queueMode)
            assertEquals(replacementId, runtime.activeSpeechId)
            assertEquals(1, old.cancelled)
            assertEquals(0, old.completed + old.failed)
            assertFalse(policy.confirmFeedbackDelivery(oldAction.trackId, oldEvaluatedAtMs, runtime.nowMs))

            // Native callbacks for the retired engine utterance cannot finish or cancel the new
            // (trackId, evaluatedAtMs) policy claim, despite its identical STOP deliveryKey.
            runtime.start(oldId)
            runtime.done(oldId)
            runtime.error(oldId, TextToSpeech.ERROR_SERVICE)
            runtime.stopped(oldId)
            assertEquals(1, old.cancelled)
            assertEquals(0, old.completed + old.failed)
            assertTrue(old.confirmations.isEmpty())
            assertEquals(0, next.completed + next.failed + next.cancelled)
            assertEquals(replacementId, runtime.activeSpeechId)
            assertTrue(actuator.isAppSpeechActive())
            assertTrue(policy.hasClaimedFeedbackDelivery(replacementAction.trackId, MessageLevel.STOP))
            assertEquals("tts=ready", actuator.statusText())

            runtime.start(replacementId)
            runtime.done(replacementId)
            runtime.advanceBy(20_000L)
            assertEquals(1, next.completed)
            assertEquals(listOf(true), next.confirmations)
            assertEquals(0, next.failed + next.cancelled)
            assertFalse(policy.hasClaimedFeedbackDelivery(replacementAction.trackId, MessageLevel.STOP))
            assertFalse(actuator.isAppSpeechActive())
            assertEquals(1, runtime.engineCount)
            assertEquals("tts=ready", actuator.statusText())
        }
    }

    @Test
    fun expiredUnadmittedProposalKeepsTheStartedOwnersActualCompletion() {
        for (claimed in listOf(false, true)) {
            StartedFixture().use { fixture ->
                val proposal = fixture.propose()
                if (claimed) fixture.claim(proposal)
                fixture.runtime.advanceBy(801L)
                if (claimed) {
                    assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, fixture.emit(proposal, DeliveryEvents()))
                    fixture.policy.rejectUndeliveredFeedback(proposal, 1_800L)
                } else {
                    assertFalse(fixture.policy.claimFeedbackDelivery(proposal, 1_800L, fixture.runtime.nowMs))
                }
                fixture.assertOldCanComplete()
            }
        }
    }

    @Test
    fun nativeDuplicateRejectionKeepsTheStartedOwnersActualCompletion() {
        StartedFixture().use { fixture ->
            // Policy spacing is 700 ms; the unchanged native duplicate window is 800 ms.
            val proposal = fixture.propose(atMs = 1_700L)
            fixture.claim(proposal)
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, fixture.emit(proposal, DeliveryEvents()))
            fixture.policy.rejectUndeliveredFeedback(proposal, 1_700L)
            fixture.assertOldCanComplete()
        }
    }

    @Test
    fun oldActualDoneBeforeNativeAdmissionConfirmsOldAndRevokesTheProposal() {
        StartedFixture().use { fixture ->
            val proposal = fixture.propose()
            fixture.claim(proposal)
            fixture.runtime.done(fixture.oldId)
            assertEquals(listOf(true), fixture.old.confirmations)
            assertFalse(fixture.policy.hasClaimedFeedbackDelivery(proposal))
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, fixture.emit(proposal, DeliveryEvents()))
            assertEquals(1, fixture.runtime.spoken.size)
            assertEquals(0, fixture.old.cancelled + fixture.old.failed)
        }
    }

    @Test
    fun revokedCurrentStartValidatorKeepsTheStartedOwnersActualCompletion() {
        StartedFixture().use { fixture ->
            val proposal = fixture.propose()
            fixture.claim(proposal)
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, fixture.actuator.emit(
                action = proposal,
                onSpeechCompleted = null,
                onSpeechFailed = null,
                isStillValidAtStart = { false },
            ).speech)
            fixture.policy.rejectUndeliveredFeedback(proposal, 1_800L)
            fixture.assertOldCanComplete()
        }
    }

    @Test
    fun oldDoneDuringNewValidatorCannotBeOvertakenByStaleAdmission() {
        StartedFixture().use { fixture ->
            val proposal = fixture.propose()
            fixture.claim(proposal)
            var completedOld = false
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, fixture.actuator.emit(
                action = proposal,
                onSpeechCompleted = null,
                onSpeechFailed = null,
                isStillValidAtStart = {
                    if (!completedOld) {
                        completedOld = true
                        fixture.runtime.done(fixture.oldId)
                    }
                    // Represents a caller's pre-read value, not permission to use a retired owner.
                    true
                },
            ).speech)
            assertEquals(listOf(true), fixture.old.confirmations)
            assertEquals(1, fixture.runtime.spoken.size)
            assertFalse(fixture.policy.hasClaimedFeedbackDelivery(proposal))
            assertFalse(fixture.actuator.isAppSpeechActive())
        }
    }

    @Test
    fun copiedIssuedOwnerCannotAuthorizeADifferentAction() {
        StartedFixture().use { fixture ->
            val proposal = fixture.propose()
            fixture.claim(proposal)
            val copied = listOf(
                proposal.copy(trackId = "other-risk"),
                proposal.copy(message = "a different target"),
                proposal.copy(level = MessageLevel.WARNING),
                proposal.copy(validUntilMs = proposal.validUntilMs + 1L),
                proposal.copy(reason = "another-source-purpose"),
                proposal.copy(deliveryOwner = fixture.oldAction.deliveryOwner),
            )
            for (action in copied) {
                assertFalse(fixture.policy.hasClaimedFeedbackDelivery(action))
                assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, fixture.emit(action, DeliveryEvents()))
            }
            assertEquals(1, fixture.runtime.spoken.size)
            assertEquals(0, fixture.old.cancelled)
            val next = DeliveryEvents()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, fixture.emit(proposal, next))
            fixture.runtime.start(fixture.runtime.spoken.last().id)
            fixture.runtime.done(fixture.runtime.spoken.last().id)
            assertEquals(listOf(true), next.confirmations)
        }
    }

    @Test
    fun anotherPolicysIssuedReplacementCannotRetireTheActualNativeOwner() {
        StartedFixture().use { fixture ->
            fixture.runtime.advanceBy(800L)
            val otherPolicy = WalkSafeFeedbackPolicy()
            val otherOld = requireNotNull(otherPolicy.evaluate(risk(1_000L, 2_000L, 1.1f), true, 1_000L))
            assertTrue(otherPolicy.claimFeedbackDelivery(otherOld, 1_000L, 1_000L))
            val current = risk(1_800L, 250L, 1.0f)
            val proposal = requireNotNull(otherPolicy.evaluate(current, true, 1_800L))
            assertTrue(otherPolicy.claimFeedbackDelivery(proposal, 1_800L, 1_800L))
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,
                emit(fixture.actuator, otherPolicy, fixture.runtime, proposal, 1_800L, DeliveryEvents()) { current })
            otherPolicy.rejectUndeliveredFeedback(proposal, 1_800L)
            fixture.assertOldCanComplete()
        }
    }

    @Test
    fun trustedDistanceWorseningAndExpiredOldStartLeaseUseTheSameIssuedReplacement() {
        StartedFixture(initialDistanceM = 1.2f).use { fixture ->
            // The old source's inclusive start deadline was 1800. Its already-started speech
            // still owns completion, while only the new observation has to be fresh now.
            val proposal = fixture.propose(atMs = 1_900L, ttcMs = 2_000L, distanceM = .7f)
            fixture.claim(proposal)
            val next = DeliveryEvents()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, fixture.emit(proposal, next))
            assertEquals(1, fixture.old.cancelled)
            fixture.runtime.start(fixture.runtime.spoken.last().id)
            fixture.runtime.done(fixture.runtime.spoken.last().id)
            assertEquals(listOf(true), next.confirmations)
        }
    }

    @Test
    fun stableEqualRiskAndUntrustedOrNonCurrentWorseningKeepTheStartedOwner() {
        val current = risk(1_800L, 250L, 1.0f)
        val candidates = listOf(
            current.copy(timeToCollisionMs = 2_000L),
            current.copy(timeToCollisionMs = 400L), // Halved, but still the same TTC band.
            current.copy(confidence = .54f),
            current.copy(source = DepthSource.ARCORE_FULL_DEPTH),
            current.copy(timeToCollisionMs = null),
            current.copy(timeToCollisionMs = -1L),
            current.copy(timestampMs = 51_000L),
            current.copy(validUntilMs = 1_800L), // No new source lease compared with the old one.
            current.copy(stale = true),
            current.copy(trackId = "another-target"),
        )
        for (candidate in candidates) {
            StartedFixture().use { fixture ->
                fixture.runtime.advanceBy(800L)
                fixture.current = candidate
                assertNull("$candidate", fixture.policy.evaluate(candidate, true, fixture.runtime.nowMs))
                fixture.assertOldCanComplete()
            }
        }
    }

    @Test
    fun pendingSameStopHapticCompletesItsOriginalPatternDuringSpeechReplacement() {
        StartedFixture(haptics = true).use { fixture ->
            assertEquals(listOf(1_000L), fixture.runtime.vibratedAtMs)
            val proposal = fixture.propose()
            fixture.claim(proposal)
            val next = DeliveryEvents()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, fixture.emit(proposal, next))
            assertEquals(fixture.oldAction.deliveryKey, proposal.deliveryKey)
            assertEquals(listOf(1_000L), fixture.runtime.vibratedAtMs)
            fixture.runtime.advanceBy(100L)
            assertTrue(fixture.actuator.vibrateRiskOnly(proposal) { true })
            assertEquals(listOf(1_000L, 1_900L), fixture.runtime.vibratedAtMs)
            fixture.runtime.start(fixture.runtime.spoken.last().id)
            fixture.runtime.done(fixture.runtime.spoken.last().id)
            assertEquals(listOf(true), next.confirmations)
        }
    }

    @Test(timeout = 10_000L)
    fun oldCancellationCanJoinAnotherThreadsExactPolicyAndNavigationQueries() {
        var onCancelled: (() -> Unit)? = null
        StartedFixture(oldCancellation = { onCancelled?.invoke() }).use { fixture ->
            val proposal = fixture.propose()
            fixture.claim(proposal)
            val outcome = AtomicReference<Pair<Boolean, NavigationSpeechDispatchResult>?>()
            val failure = AtomicReference<Throwable?>()
            var blocked = false
            onCancelled = {
                val worker = Thread({
                    try {
                        outcome.set(fixture.policy.hasClaimedFeedbackDelivery(proposal) to
                            fixture.actuator.speakNavigation("joining route", isStillValidAtStart = { true }))
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                }, "started-risk-owner-query").apply { isDaemon = true }
                worker.start()
                worker.join(2_000L)
                blocked = worker.isAlive
            }
            val next = DeliveryEvents()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, fixture.emit(proposal, next))
            assertFalse("terminal callback held a policy, speech or output-gate lock", blocked)
            assertNull(failure.get())
            assertEquals(true to NavigationSpeechDispatchResult.SUPPRESSED, outcome.get())
            fixture.runtime.start(fixture.runtime.spoken.last().id)
            fixture.runtime.done(fixture.runtime.spoken.last().id)
            assertEquals(listOf(true), next.confirmations)
        }
    }

    @Test
    fun closeFromOldCancellationPreventsNativeReplacementOutput() {
        var onCancelled: (() -> Unit)? = null
        StartedFixture(oldCancellation = { onCancelled?.invoke() }).use { fixture ->
            val proposal = fixture.propose()
            fixture.claim(proposal)
            onCancelled = { fixture.actuator.close() }
            val next = DeliveryEvents()
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, fixture.emit(proposal, next))
            fixture.policy.rejectUndeliveredFeedback(proposal, 1_800L)
            assertEquals(1, fixture.runtime.spoken.size)
            assertEquals(1, fixture.old.cancelled)
            assertEquals(0, next.completed + next.failed + next.cancelled)
            assertFalse(fixture.policy.hasClaimedFeedbackDelivery(proposal))
            assertEquals("tts=closed", fixture.actuator.statusText())
        }
    }

    @Test
    fun retiringAdmittedReplacementFromOldCancellationStopsNativeOldSpeechAndClearsPendingOwner() {
        var onCancelled: (() -> Unit)? = null
        StartedFixture(oldCancellation = { onCancelled?.invoke() }).use { fixture ->
            val proposal = fixture.propose()
            fixture.claim(proposal)
            onCancelled = { fixture.policy.rejectUndeliveredFeedback(proposal, 1_800L) }
            val next = DeliveryEvents()
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, fixture.emit(proposal, next))

            assertNull("retired replacement must stop the cancelled old native utterance", fixture.runtime.activeSpeechId)
            assertFalse("retired replacement must not remain registered", fixture.actuator.isAppSpeechActive())
            assertEquals(1, fixture.old.cancelled)
            assertEquals(0, fixture.old.completed + fixture.old.failed)
            assertEquals(1, next.cancelled)
            assertEquals(0, next.completed + next.failed)
            assertFalse(fixture.policy.hasClaimedFeedbackDelivery(proposal))
            assertEquals(1, fixture.runtime.spoken.size)

            fixture.runtime.start(fixture.oldId)
            fixture.runtime.done(fixture.oldId)
            fixture.runtime.error(fixture.oldId, TextToSpeech.ERROR_SERVICE)
            fixture.runtime.stopped(fixture.oldId)
            fixture.runtime.advanceBy(20_000L)
            assertEquals(1, fixture.old.cancelled)
            assertEquals(1, next.cancelled)
            assertEquals(0, fixture.old.completed + fixture.old.failed + next.completed + next.failed)
            assertEquals(1, fixture.runtime.engineCount)
            assertEquals("tts=ready", fixture.actuator.statusText())
        }
    }

    @Test
    fun retiringRegularStopEscalationFromOldCancellationStopsNativeWarningAndCancelsNewOwner() {
        var onCancelled: (() -> Unit)? = null
        StartedFixture(initialLevel = MessageLevel.WARNING,
            oldCancellation = { onCancelled?.invoke() }).use { fixture ->
            assertEquals(MessageLevel.WARNING, fixture.oldAction.level)
            val proposal = fixture.propose()
            assertEquals(MessageLevel.STOP, proposal.level)
            assertNotNull(proposal.deliveryOwner)
            assertNull("regular escalation has no same-level replacement receipt", proposal.replacesDeliveryOwner)
            fixture.claim(proposal)
            onCancelled = { fixture.policy.rejectUndeliveredFeedback(proposal, 1_800L) }
            val next = DeliveryEvents()
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, fixture.emit(proposal, next))

            assertNull("retired regular escalation must stop the cancelled native warning", fixture.runtime.activeSpeechId)
            assertFalse(fixture.actuator.isAppSpeechActive())
            assertEquals(1, fixture.old.cancelled)
            assertEquals(0, fixture.old.completed + fixture.old.failed)
            assertEquals(1, next.cancelled)
            assertEquals(0, next.completed + next.failed)
            assertFalse(fixture.policy.hasClaimedFeedbackDelivery(proposal))
            assertEquals(1, fixture.runtime.spoken.size)
            fixture.runtime.start(fixture.oldId)
            fixture.runtime.done(fixture.oldId)
            fixture.runtime.error(fixture.oldId, TextToSpeech.ERROR_SERVICE)
            fixture.runtime.stopped(fixture.oldId)
            fixture.runtime.advanceBy(20_000L)
            assertEquals(1, fixture.old.cancelled)
            assertEquals(1, next.cancelled)
            assertEquals(0, fixture.old.completed + fixture.old.failed + next.completed + next.failed)
            assertEquals(1, fixture.runtime.engineCount)
            assertEquals("tts=ready", fixture.actuator.statusText())
        }
    }

    @Test
    fun retiringReplacementInLastStartValidatorStopsNativeOldSpeechAfterNativeFenceSkipsSpeak() {
        StartedFixture().use { fixture ->
            val proposal = fixture.propose()
            fixture.claim(proposal)
            val next = DeliveryEvents()
            var checks = 0
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, fixture.actuator.emit(
                action = proposal,
                onSpeechCompleted = { next.completed += 1 },
                onSpeechFailed = { next.failed += 1 },
                onSpeechCancelled = { next.cancelled += 1 },
                isStillValidAtStart = {
                    checks += 1
                    if (checks == 3) fixture.policy.rejectUndeliveredFeedback(proposal, 1_800L)
                    // Admission was read before retirement; the native owner fence must win.
                    true
                },
            ).speech)
            assertEquals(3, checks)
            assertNull("native skip must not leave the cancelled old utterance playing", fixture.runtime.activeSpeechId)
            assertFalse(fixture.actuator.isAppSpeechActive())
            assertEquals(1, fixture.old.cancelled)
            assertEquals(0, fixture.old.completed + fixture.old.failed)
            assertEquals(1, next.cancelled)
            assertEquals(0, next.completed + next.failed)
            assertFalse(fixture.policy.hasClaimedFeedbackDelivery(proposal))
            assertEquals(1, fixture.runtime.spoken.size)
            fixture.runtime.advanceBy(20_000L)
            assertEquals(1, next.cancelled)
            assertEquals(1, fixture.runtime.engineCount)
            assertEquals("tts=ready", fixture.actuator.statusText())
        }
    }

    @Test
    fun retiredReplacementCleanupCannotStopAReentrantWinningRiskOwner() {
        var onCancelled: (() -> Unit)? = null
        StartedFixture(oldCancellation = { onCancelled?.invoke() }).use { fixture ->
            val proposal = fixture.propose()
            fixture.claim(proposal)
            val winner = DeliveryEvents()
            var winnerDispatch: NavigationSpeechDispatchResult? = null
            onCancelled = {
                fixture.policy.rejectUndeliveredFeedback(proposal, 1_800L)
                winnerDispatch = fixture.actuator.emit(
                    action = FeedbackAction("winning-risk", "current winning stop", MessageLevel.STOP,
                        null, "callback-risk-request", 2_600L),
                    onSpeechCompleted = { winner.completed += 1 },
                    onSpeechFailed = { winner.failed += 1 },
                    onSpeechCancelled = { winner.cancelled += 1 },
                    isStillValidAtStart = { true },
                ).speech
            }
            val next = DeliveryEvents()
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, fixture.emit(proposal, next))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, winnerDispatch)
            assertEquals(2, fixture.runtime.spoken.size)
            assertEquals("current winning stop", fixture.runtime.spoken.last().message)
            val winnerId = fixture.runtime.spoken.last().id
            assertEquals(winnerId, fixture.runtime.activeSpeechId)
            assertTrue(fixture.actuator.isAppSpeechActive())
            assertEquals(1, fixture.old.cancelled)
            assertEquals(1, next.cancelled)
            assertEquals(0, next.completed + next.failed + winner.completed + winner.failed + winner.cancelled)
            fixture.runtime.start(winnerId)
            fixture.runtime.done(winnerId)
            assertEquals(1, winner.completed)
            assertEquals(0, winner.failed + winner.cancelled)
        }
    }

    @Test
    fun sameClockKeyReuseCannotLetAnOldTypedCallbackConfirmOrRejectTheNewOwner() {
        StartedFixture(config = FeedbackPolicyConfig(globalIntervalMs = 0L, perTrackIntervalMs = 0L)).use { fixture ->
            val current = risk(1_000L, 250L, 1.0f).copy(timestampMs = 51_001L, validUntilMs = 1_801L)
            assertNull(fixture.policy.evaluate(current, true, 1_000L))
            fixture.runtime.done(fixture.oldId)
            assertEquals(listOf(true), fixture.old.confirmations)
            val next = requireNotNull(fixture.policy.evaluate(current, true, 1_000L))
            assertTrue(fixture.policy.claimFeedbackDelivery(next, 1_000L, 1_000L))
            assertFalse(fixture.policy.confirmFeedbackDelivery(fixture.oldAction, 1_000L, 1_000L))
            fixture.policy.rejectUndeliveredFeedback(fixture.oldAction, 1_000L)
            assertTrue(fixture.policy.hasClaimedFeedbackDelivery(next))
            fixture.policy.rejectUndeliveredFeedback(next, 1_000L)
        }
    }

    private inner class StartedFixture(
        config: FeedbackPolicyConfig = FeedbackPolicyConfig(),
        initialDistanceM: Float = 1.1f,
        initialLevel: MessageLevel = MessageLevel.STOP,
        haptics: Boolean = false,
        oldCancellation: (() -> Unit)? = null,
    ) : AutoCloseable {
        val runtime = FakeRuntime()
        val policy = WalkSafeFeedbackPolicy(config)
        val actuator = AndroidFeedbackActuator(runtime = runtime, hapticAllowed = { haptics })
        var current = risk(1_000L, 2_000L, initialDistanceM).copy(level = initialLevel)
        val oldAction: FeedbackAction
        val oldId: String
        val old = DeliveryEvents()

        init {
            runtime.ready()
            oldAction = requireNotNull(policy.evaluate(current, true, runtime.nowMs))
            assertTrue(policy.claimFeedbackDelivery(oldAction, 1_000L, runtime.nowMs))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                this@AndroidFeedbackActuatorStartedRiskWorseningRegressionTest.emit(
                    actuator, policy, runtime, oldAction, 1_000L, old, oldCancellation) { current })
            oldId = runtime.spoken.single().id
            runtime.start(oldId)
        }

        fun propose(atMs: Long = 1_800L, ttcMs: Long = 250L, distanceM: Float = 1.0f): FeedbackAction {
            runtime.advanceBy(atMs - runtime.nowMs)
            current = risk(atMs, ttcMs, distanceM)
            return requireNotNull(policy.evaluate(current, true, atMs))
        }

        fun claim(action: FeedbackAction) {
            assertTrue(policy.claimFeedbackDelivery(action, requireNotNull(action.deliveryOwner).evaluatedAtMs,
                runtime.nowMs))
        }

        fun emit(action: FeedbackAction, events: DeliveryEvents): NavigationSpeechDispatchResult =
            this@AndroidFeedbackActuatorStartedRiskWorseningRegressionTest.emit(
                actuator, policy, runtime, action, requireNotNull(action.deliveryOwner).evaluatedAtMs,
                events) { current }

        fun assertOldCanComplete() {
            assertEquals(1, runtime.spoken.size)
            assertEquals(oldId, runtime.activeSpeechId)
            assertTrue(policy.hasClaimedFeedbackDelivery(oldAction))
            assertEquals(0, old.completed + old.failed + old.cancelled)
            runtime.done(oldId)
            assertEquals(listOf(true), old.confirmations)
            assertEquals(0, old.failed + old.cancelled)
        }

        override fun close() { actuator.close() }
    }

    private fun emit(
        actuator: AndroidFeedbackActuator,
        policy: WalkSafeFeedbackPolicy,
        runtime: FakeRuntime,
        action: FeedbackAction,
        evaluatedAtMs: Long,
        events: DeliveryEvents,
        onCancelled: (() -> Unit)? = null,
        current: () -> FeedbackCandidate,
    ): NavigationSpeechDispatchResult = actuator.emit(
        action = action,
        isStillValidAtStart = {
            policy.hasClaimedFeedbackDelivery(action) && runtime.nowMs <= action.validUntilMs && action.deliveryKey in
                policy.activeFeedbackDeliveryKeys(listOf(current()), true, runtime.nowMs)
        },
        onSpeechCompleted = {
            events.completed += 1
            events.confirmations += policy.confirmFeedbackDelivery(action, evaluatedAtMs, runtime.nowMs)
        },
        onSpeechFailed = {
            events.failed += 1
            policy.rejectUndeliveredFeedback(action, evaluatedAtMs)
        },
        onSpeechCancelled = {
            events.cancelled += 1
            policy.rejectUndeliveredFeedback(action, evaluatedAtMs)
            onCancelled?.invoke()
        },
    ).speech

    private fun risk(atMs: Long, ttcMs: Long, distanceM: Float) = FeedbackCandidate(
        trackId = "same-risk",
        message = "위험합니다. 멈추세요.",
        level = MessageLevel.STOP,
        source = DepthSource.ARCORE_RAW_DEPTH,
        confidence = .9f,
        timestampMs = atMs + 50_000L,
        trackAgeFrames = 20,
        trackStableMs = 4_000L,
        stale = false,
        validUntilMs = atMs + 800L,
        timeToCollisionMs = ttcMs,
        riskDistanceM = distanceM,
        observedAtElapsedRealtimeMs = atMs,
        observationMaxAgeMs = 800L,
    )

    private class DeliveryEvents {
        var completed = 0
        var failed = 0
        var cancelled = 0
        val confirmations = mutableListOf<Boolean>()
    }

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
        val vibratedAtMs = mutableListOf<Long>()
        var activeSpeechId: String? = null
            private set
        var engineCount = 0
            private set
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = nowMs
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
            return TextToSpeech.SUCCESS
        }
        override fun stop() { activeSpeechId = null }
        override fun shutdown() { activeSpeechId = null }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun vibrate(patternMs: LongArray): Boolean {
            vibratedAtMs += nowMs
            return true
        }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("unused")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(nowMs + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() {
            checkNotNull(initialized)(TextToSpeech.SUCCESS)
            advanceBy(0L)
        }
        fun start(id: String) { listener.onStart(id); advanceBy(0L) }
        fun done(id: String) {
            if (activeSpeechId == id) activeSpeechId = null
            listener.onDone(id)
            advanceBy(0L)
        }
        fun error(id: String, code: Int) { listener.onError(id, code); advanceBy(0L) }
        fun stopped(id: String) { listener.onStop(id, interrupted = true); advanceBy(0L) }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val targetMs = nowMs + deltaMs
            while (true) {
                val next = tasks.filter { it.dueMs <= targetMs }
                    .minWithOrNull(compareBy<Task> { it.dueMs }.thenBy { it.order }) ?: break
                tasks.remove(next)
                nowMs = next.dueMs
                next.runnable.run()
            }
            nowMs = targetMs
        }
    }
}
