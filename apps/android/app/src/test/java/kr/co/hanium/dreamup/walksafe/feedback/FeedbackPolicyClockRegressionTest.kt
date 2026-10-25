package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.*
import org.junit.Test

class FeedbackPolicyClockRegressionTest {
    @Test fun unboundedLeaseIsRejectedEvenWithAFreshNormalizedObservation() {
        assertRejected(candidate().copy(validUntilMs = Long.MAX_VALUE), 1_000L)
    }

    @Test fun omittedCandidateLeaseIsExpiredEvenWithAFreshNormalizedObservation() {
        val unleased = FeedbackCandidate("risk", "stop", MessageLevel.STOP,
            DepthSource.ARCORE_RAW_DEPTH, .9f, 51_000L, 3, 700L, false,
            observedAtElapsedRealtimeMs = 1_000L, observationMaxAgeMs = 800L)
        assertEquals(Long.MIN_VALUE, unleased.validUntilMs)
        assertRejected(unleased, 1_000L)
    }

    @Test fun liveLeaseCannotRenewAnOldObservationWithFalseStaleFlag() {
        assertRejected(candidate().copy(validUntilMs = 10_000L), 1_801L)
    }

    @Test fun futureNormalizedObservationCannotAuthorizeOutput() {
        assertRejected(candidate().copy(observedAtElapsedRealtimeMs = 1_001L), 1_000L)
    }

    @Test fun missingOrInvalidNormalizedObservationCannotAuthorizeOutput() {
        listOf(null, -1L, Long.MIN_VALUE, Long.MAX_VALUE).forEach { observedAt ->
            assertRejected(candidate().copy(observedAtElapsedRealtimeMs = observedAt), 1_000L)
        }
    }

    @Test fun missingOrInvalidProducerAgeBudgetCannotAuthorizeOutput() {
        listOf(null, -1L, 0L, Long.MAX_VALUE).forEach { age ->
            assertRejected(candidate().copy(observationMaxAgeMs = age), 1_000L)
        }
    }

    @Test fun sourceAgeBoundaryIsInclusiveAndSharedByAllPolicyQueries() {
        val risk = candidate()
        val policy = WalkSafeFeedbackPolicy()
        assertNotNull(policy.evaluate(risk, true, 1_800L))
        assertEquals(setOf(risk.trackId), policy.activeRiskTrackIds(listOf(risk), true, 1_800L))
        assertEquals(setOf(risk.deliveryKey), policy.activeFeedbackDeliveryKeys(listOf(risk), true, 1_800L))
        assertRejected(risk, 1_801L)
    }

    @Test fun aSmallerActualProducerBudgetBoundsTheActionAndAllQueries() {
        val risk = candidate().copy(observationMaxAgeMs = 200L, validUntilMs = 10_000L)
        val action = requireNotNull(WalkSafeFeedbackPolicy().evaluate(risk, true, 1_100L))
        assertEquals(1_200L, action.validUntilMs)
        assertRejected(risk, 1_201L)
    }

    @Test fun producerBudgetCannotRaiseThePolicyAgeLimit() {
        val risk = candidate().copy(observationMaxAgeMs = 10_000L, validUntilMs = 10_000L)
        val action = requireNotNull(WalkSafeFeedbackPolicy().evaluate(risk, true, 1_700L))
        assertEquals(1_800L, action.validUntilMs)
        assertRejected(risk, 1_801L)
    }

    @Test fun queueDeadlineIsBoundedByObservationAndOriginalSourceExpiry() {
        val sourceBound = candidate().copy(validUntilMs = 1_150L)
        assertEquals(1_150L, requireNotNull(WalkSafeFeedbackPolicy().evaluate(sourceBound, true, 1_100L)).validUntilMs)
        val observationBound = candidate().copy(validUntilMs = 10_000L)
        assertEquals(1_800L, requireNotNull(WalkSafeFeedbackPolicy().evaluate(observationBound, true, 1_100L)).validUntilMs)
        val shortQueue = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(pendingQueueTtlMs = 50L))
        assertEquals(1_150L, requireNotNull(shortQueue.evaluate(observationBound, true, 1_100L)).validUntilMs)
    }

    @Test fun lateReservationClaimCannotCrossTheObservationDeadline() {
        val policy = WalkSafeFeedbackPolicy()
        val action = requireNotNull(policy.evaluate(candidate().copy(validUntilMs = 10_000L), true, 1_700L))
        assertFalse(policy.claimFeedbackDelivery(action.trackId, 1_700L, 1_801L))
        assertFalse(policy.confirmFeedbackDelivery(action.trackId, 1_700L, 1_802L))
    }

    @Test fun cameraClockEpochCannotReplaceNormalizedAge() {
        listOf(1L, 51_000L, Long.MAX_VALUE).forEach { cameraAt ->
            val risk = candidate().copy(timestampMs = cameraAt)
            assertNotNull(WalkSafeFeedbackPolicy().evaluate(risk, true, 1_000L))
            assertRejected(risk.copy(validUntilMs = 10_000L), 1_801L)
        }
    }

    @Test fun informationAndQualitativeCautionUseTheSameObservationBoundary() {
        val information = candidate().copy(level = MessageLevel.INFO)
        val qualitative = candidate().copy(level = MessageLevel.CAUTION,
            source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH, riskDistanceM = null)
        listOf(information, qualitative).forEach { output ->
            assertNotNull(WalkSafeFeedbackPolicy().evaluate(output, true, 1_800L))
            assertRejected(output.copy(validUntilMs = 10_000L), 1_801L)
        }
    }

    @Test fun aLiveSourceLeaseCannotBypassTheEmergencyStopRemainingAge() {
        val emergency = candidate().copy(confidence = .95f, trackAgeFrames = 2, trackStableMs = 100L,
            timeToCollisionMs = 200L, riskDistanceM = .8f, validUntilMs = 10_000L)
        assertNotNull(WalkSafeFeedbackPolicy().evaluate(emergency, true, 1_399L))
        assertNull(WalkSafeFeedbackPolicy().evaluate(emergency, true, 1_400L))
    }

    @Test fun deadlineArithmeticStaysFiniteNearTheClockUpperBound() {
        val now = Long.MAX_VALUE - 100L
        val risk = candidate().copy(observedAtElapsedRealtimeMs = now,
            validUntilMs = Long.MAX_VALUE - 1L)
        assertEquals(Long.MAX_VALUE - 1L, requireNotNull(WalkSafeFeedbackPolicy().evaluate(risk, true, now)).validUntilMs)
    }

    @Test fun renewedExpiryAloneCannotRestoreTheSameOldObservation() {
        val policy = WalkSafeFeedbackPolicy()
        val first = requireNotNull(policy.evaluate(candidate(), true, 1_000L))
        policy.rejectUndeliveredFeedback(first.trackId, 1_000L)
        assertNull(policy.evaluate(candidate().copy(validUntilMs = 10_000L), true, 1_801L))
        assertNotNull(policy.evaluate(candidate().copy(observedAtElapsedRealtimeMs = 1_801L,
            validUntilMs = 2_601L), true, 1_801L))
    }

    private fun assertRejected(candidate: FeedbackCandidate, nowMs: Long) {
        val policy = WalkSafeFeedbackPolicy()
        assertNull(candidate.toString(), policy.evaluate(candidate, true, nowMs))
        assertTrue(policy.activeRiskTrackIds(listOf(candidate), true, nowMs).isEmpty())
        assertTrue(policy.activeFeedbackDeliveryKeys(listOf(candidate), true, nowMs).isEmpty())
    }

    private fun candidate() = FeedbackCandidate("risk", "stop", MessageLevel.STOP,
        DepthSource.ARCORE_RAW_DEPTH, .9f, 51_000L, 3, 700L, false,
        validUntilMs = 1_800L, observedAtElapsedRealtimeMs = 1_000L, observationMaxAgeMs = 800L)
}
