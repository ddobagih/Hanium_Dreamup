package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.*
import org.junit.Test

class FeedbackValidityRegressionTest {
    @Test fun missingObservationLeaseCannotBeRenewedByFalseStaleFlag() {
        val unleased = FeedbackCandidate("risk", "stop", MessageLevel.STOP,
            DepthSource.ARCORE_RAW_DEPTH, .8f, 1L, 3, 700L, false)

        assertNull(WalkSafeFeedbackPolicy().evaluate(unleased, true, 10_000L))
    }

    @Test fun unboundedObservationLeaseCannotAuthorizeRiskOrInformation() {
        listOf(MessageLevel.CAUTION, MessageLevel.WARNING, MessageLevel.STOP, MessageLevel.INFO).forEach { level ->
            val unbounded = candidate(deadline = Long.MAX_VALUE).copy(level = level, message = level.name)
            val policy = WalkSafeFeedbackPolicy()

            assertNull("Unbounded $level candidate cannot reserve output", policy.evaluate(unbounded, true, 10_000L))
            assertTrue(policy.activeRiskTrackIds(listOf(unbounded), true, 10_000L).isEmpty())
            assertTrue(policy.activeFeedbackDeliveryKeys(listOf(unbounded), true, 10_000L).isEmpty())
        }
    }

    @Test fun omittedActionLeaseCannotDefaultToUnlimitedRiskOutput() {
        val action = FeedbackAction("risk", "stop", MessageLevel.STOP,
            VibrationPatterns.forLevel(MessageLevel.STOP), "test")

        assertEquals(Long.MIN_VALUE, action.validUntilMs)
    }

    @Test fun expiredCandidateCannotBeRenewedByFalseStaleFlag() {
        assertNull(WalkSafeFeedbackPolicy().evaluate(candidate(deadline = 1_100L), true, 1_101L))
    }

    @Test fun activeQueriesRejectExpiredRiskButKeepValidDeadline() {
        val policy = WalkSafeFeedbackPolicy()
        listOf(MessageLevel.CAUTION, MessageLevel.WARNING, MessageLevel.STOP).forEach { level ->
            val risk = candidate(deadline = 1_100L).copy(level = level, message = level.name)
            val candidates = listOf(risk)
            assertEquals(setOf(risk.trackId), policy.activeRiskTrackIds(candidates, true, 1_100L))
            assertEquals(setOf(risk.deliveryKey), policy.activeFeedbackDeliveryKeys(candidates, true, 1_100L))
            assertTrue(policy.activeRiskTrackIds(candidates, true, 1_101L).isEmpty())
            assertTrue(policy.activeFeedbackDeliveryKeys(candidates, true, 1_101L).isEmpty())
        }
    }

    @Test fun queueLeaseCannotOutliveOriginalEvidence() {
        val result = requireNotNull(WalkSafeFeedbackPolicy().evaluate(candidate(deadline = 1_100L), true, 1_000L))
        assertEquals(1_100L, result.validUntilMs)
    }

    @Test fun claimedOutputKeepsCompletionOwnershipAfterStartDeadline() {
        val policy = WalkSafeFeedbackPolicy()
        val first = requireNotNull(policy.evaluate(candidate(deadline = 1_100L), true, 1_000L))
        assertTrue(policy.claimFeedbackDelivery(first.trackId, 1_000L, 1_000L))
        assertNull(policy.evaluate(candidate(deadline = 3_000L).copy(trackId = "fresh"), true, 1_101L))
        assertEquals(1_100L, first.validUntilMs)
        assertTrue(policy.confirmFeedbackDelivery(first.trackId, 1_000L, 1_102L))
        assertFalse(policy.confirmFeedbackDelivery(first.trackId, 1_000L, 1_103L))
    }

    @Test fun expiredUnclaimedReservationCannotBlockFreshRiskOrCommitLate() {
        val policy = WalkSafeFeedbackPolicy()
        val first = requireNotNull(policy.evaluate(candidate(deadline = 1_100L), true, 1_000L))
        assertNotNull(policy.evaluate(candidate(deadline = 3_000L).copy(trackId = "fresh"), true, 1_101L))
        assertFalse(policy.claimFeedbackDelivery(first.trackId, 1_000L, 1_101L))
        assertFalse(policy.confirmFeedbackDelivery(first.trackId, 1_000L, 1_102L))
    }

    @Test fun expiredQueuedClaimIsReleasedByCancellationNotByCompletion() {
        val policy = WalkSafeFeedbackPolicy()
        val first = requireNotNull(policy.evaluate(candidate(deadline = 1_100L), true, 1_000L))
        assertTrue(policy.claimFeedbackDelivery(first.trackId, 1_000L, 1_000L))
        assertNull(policy.evaluate(candidate(deadline = 3_000L), true, 1_101L))
        policy.rejectUndeliveredFeedback(first.trackId, 1_000L)
        assertNotNull(policy.evaluate(candidate(deadline = 3_000L), true, 1_101L))
        assertFalse(policy.confirmFeedbackDelivery(first.trackId, 1_000L, 1_102L))
    }

    @Test fun claimedInformationMayCompleteAfterItsStartDeadline() {
        val policy = WalkSafeFeedbackPolicy()
        val information = candidate(deadline = 1_100L).copy(level = MessageLevel.INFO, message = "path")
        val first = requireNotNull(policy.evaluate(information, true, 1_000L))
        assertTrue(policy.claimFeedbackDelivery(first.trackId, 1_000L, 1_000L))
        assertNull(policy.evaluate(information.copy(validUntilMs = 3_000L), true, 1_101L))
        assertTrue(policy.confirmFeedbackDelivery(first.trackId, 1_000L, 1_500L))
        assertFalse(policy.confirmFeedbackDelivery(first.trackId, 1_000L, 1_501L))
    }

    @Test fun permissionRevocationInvalidatesClaimedOutputs() {
        val policy = WalkSafeFeedbackPolicy()
        policy.evaluate(candidate(), true, 1_000L)
        assertTrue(policy.claimFeedbackDelivery("risk", 1_000L, 1_000L))
        policy.evaluate(null, false, 1_001L)
        assertFalse(policy.confirmFeedbackDelivery("risk", 1_000L))
    }

    @Test fun sameLevelUsesValidUrgencyBeforeInsertionOrder() {
        val first = candidate().copy(trackId = "later", timeToCollisionMs = 2_000L, riskDistanceM = 2f)
        val urgent = candidate().copy(trackId = "urgent", timeToCollisionMs = 500L, riskDistanceM = 1f)
        assertEquals("urgent", WalkSafeFeedbackPolicy().evaluateCandidates(listOf(first, urgent), true, 1_000L)?.trackId)
    }

    @Test fun vibrationOnlyChecksExpiryAndGeneration() {
        var now = 1_000L
        var outputs = 0
        val gate = FeedbackOutputGate { now }
        val generation = gate.generation()
        fun send(deadline: Long, current: Boolean = true) = gate.vibrate(longArrayOf(0, 180), 5,
            generation, deadline, { current }) { outputs++; true }
        assertFalse(send(999L))
        assertFalse(send(1_100L, false))
        assertTrue(send(1_100L))
        gate.close()
        now = 1_001L
        assertFalse(send(Long.MAX_VALUE))
        assertFalse(gate.isCurrent())
        assertEquals(1, outputs)
    }

    @Test fun callbackCrossingExactExpiryCannotVibrateButDeadlineItselfCan() {
        var now = 1_000L
        var outputs = 0
        val expiredGate = FeedbackOutputGate { now }
        assertFalse(expiredGate.vibrate(longArrayOf(0, 35), 5, expiredGate.generation(),
            1_000L, { now = 1_001L; true }) { outputs++; true })
        assertEquals(0, outputs)

        val onTimeGate = FeedbackOutputGate { 1_000L }
        assertTrue(onTimeGate.vibrate(longArrayOf(0, 35), 5, onTimeGate.generation(),
            1_000L, { true }) { outputs++; true })
        assertEquals(1, outputs)
    }

    @Test fun validatorCrossingExactExpiryCannotAuthorizeSpeechStart() {
        var now = 1_000L
        val gate = FeedbackOutputGate { now }
        var validations = 0
        assertFalse(gate.isCurrent(gate.generation(), 1_000L) {
            validations++
            now = 1_001L
            true
        })
        assertEquals(1, validations)

        now = 1_000L
        assertTrue(gate.isCurrent(gate.generation(), 1_000L) {
            validations++
            true
        })
        assertEquals(2, validations)
    }

    @Test fun stopVibrationCannotBeInterruptedByListeningCue() {
        var now = 1_000L
        val gate = FeedbackOutputGate { now }
        var outputs = 0
        fun send(pattern: LongArray?, rank: Int) = gate.vibrate(pattern, rank, gate.generation(),
            Long.MAX_VALUE, { true }) { outputs++; true }
        assertTrue(send(VibrationPatterns.forLevel(MessageLevel.STOP), MessageLevel.STOP.ordinal))
        now = 1_750L
        assertFalse(send(longArrayOf(0, 35), 0))
        assertFalse(send(null, 5))
        assertEquals(1, outputs)
        now = 1_900L
        assertTrue(send(longArrayOf(0, 35), 0))
    }

    @Test fun failedPhysicalVibrationDoesNotReservePriority() {
        val gate = FeedbackOutputGate { 1_000L }
        assertFalse(gate.vibrate(longArrayOf(0, 900), 5, 0L, 2_000L, { true }) { error("device unavailable") })
        assertTrue(gate.vibrate(longArrayOf(0, 35), 0, 0L, 2_000L, { true }) { true })
    }

    @Test fun explicitCancellationNeverUsesEngineFailureCallback() {
        val registry = UtteranceCallbackRegistry()
        var failure = 0
        var cancelled = 0
        registry.register("nav", null, { failure++ }, onCancelled = { cancelled++ })
        registry.takeTerminalCallback("nav", false, true, cancelled = true)?.invoke()
        registry.takeTerminalCallback("nav", false, true)?.invoke()
        assertEquals(0, failure)
        assertEquals(1, cancelled)
    }

    @Test fun cancelledOrReplacedNavigationCannotFallbackLater() {
        var current = true
        var failure: (() -> Unit)? = null
        var fallbacks = 0
        dispatchCurrentNavigationSpeech("turn", null, { current },
            { _, _, failed -> failure = failed; NavigationSpeechDispatchResult.ACCEPTED },
            { _, _ -> fallbacks++; true })
        current = false
        failure?.invoke()
        assertEquals(0, fallbacks)
    }

    @Test fun duplicateFailureAndLateCompletionCannotReplayFallback() {
        var failure: (() -> Unit)? = null
        var completion: (() -> Unit)? = null
        var fallbacks = 0
        var completed = 0
        dispatchCurrentNavigationSpeech("turn", { completed++ }, { true },
            { _, done, failed -> completion = done; failure = failed; NavigationSpeechDispatchResult.ACCEPTED },
            { _, done -> fallbacks++; done?.invoke(); true })
        failure?.invoke()
        failure?.invoke()
        completion?.invoke()
        assertEquals(1, fallbacks)
        assertEquals(1, completed)
    }

    @Test fun bothChannelsFailOnceAndLateSuccessCannotCommit() {
        var failure: (() -> Unit)? = null
        var completion: (() -> Unit)? = null
        var failures = 0
        var completed = 0
        dispatchCurrentNavigationSpeech("turn", { completed++ }, { true },
            { _, done, failed -> completion = done; failure = failed; NavigationSpeechDispatchResult.ACCEPTED },
            { _, _ -> false }, { failures++ })
        failure?.invoke()
        failure?.invoke()
        completion?.invoke()
        assertEquals(1, failures)
        assertEquals(0, completed)
    }

    private fun candidate(deadline: Long = 2_000L) = FeedbackCandidate("risk", "stop",
        MessageLevel.STOP, DepthSource.ARCORE_RAW_DEPTH, .8f, 1_000L, 3, 700L, false,
        validUntilMs = deadline, observedAtElapsedRealtimeMs = 1_000L, observationMaxAgeMs = 800L)
}
