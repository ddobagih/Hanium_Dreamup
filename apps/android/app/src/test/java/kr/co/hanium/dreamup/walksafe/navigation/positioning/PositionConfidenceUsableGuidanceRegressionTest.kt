package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.*
import org.junit.Test

/** Ordinary guidance accepts usable estimator evidence; precision-specific consumers remain separate. */
class PositionConfidenceUsableGuidanceRegressionTest {
    @Test fun initialLowIsUsableWithoutPretendingItIsMediumOrHigh() {
        val first = PositionConfidencePolicy().observe(PositionQuality.LOW, 1_000L)
        assertEquals(PositionQuality.LOW, first.quality)
        assertFalse(first.guidancePaused)
        assertNull(first.degradedEpisodeId)
        assertNull(first.announcementRequest)
        assertNull(first.degradationPendingSinceElapsedRealtimeMs)
    }

    @Test fun lowDuringWalkingNeverStartsADegradationEpisode() {
        val policy = PositionConfidencePolicy()
        assertFalse(policy.observe(PositionQuality.MEDIUM, 1_000L).guidancePaused)
        for (at in listOf(2_000L, 4_999L, 5_000L, 6_000L)) {
            val low = policy.observe(PositionQuality.LOW, at)
            assertEquals(PositionQuality.LOW, low.quality)
            assertFalse(low.guidancePaused)
            assertNull(low.degradedEpisodeId)
            assertNull(low.announcementRequest)
            assertNull(low.degradationPendingSinceElapsedRealtimeMs)
        }
    }

    @Test fun realCoordinatorEightMetreFirstFixIsLowAndUsableButNotPrecisionArrivalEvidence() {
        val coordinator = PositioningCoordinator()
        val first = coordinator.observeGnss(fix(1_000L, 8.0))
        assertEquals(GnssObservationDisposition.INITIALIZED, first.gnssUpdate?.disposition)
        assertEquals(PositionQuality.LOW, first.quality)
        assertEquals(8.0, requireNotNull(first.filtered).horizontalUncertaintyM, 1e-6)
        assertFalse(first.confidence.guidancePaused)
        assertFalse("Ordinary guidance must not forge precision arrival evidence", first.absoluteArrivalConfirmable)
        assertEquals(1_000L, first.raw?.elapsedRealtimeMs)
    }

    @Test fun realCoordinatorCoarseColdStartRecoversOnTwoLowFixesWithoutHighSeedOrReset() {
        val coordinator = PositioningCoordinator()
        val generation = coordinator.currentInputGeneration()
        val first = coordinator.observeGnss(fix(1_000L, 30.0))
        assertEquals(PositionQuality.UNAVAILABLE, first.quality)
        assertTrue(coordinator.evaluate(4_100L).confidence.guidancePaused)
        val recovering = coordinator.observeGnss(fix(5_000L, 10.0))
        val acquired = coordinator.observeGnss(fix(6_000L, 10.0))
        assertEquals(PositionQuality.LOW, recovering.quality)
        assertTrue(recovering.confidence.guidancePaused)
        assertEquals(PositionQuality.LOW, acquired.quality)
        assertRecovered(acquired.confidence)
        assertEquals(generation, coordinator.currentInputGeneration())
        assertEquals(6_000L, acquired.filtered?.elapsedRealtimeMs)
        assertEquals(6_000L, acquired.gnssUpdate?.estimateAtGnssMeasurement?.lastInformativeGnssAtElapsedRealtimeMs)
        assertFalse(acquired.absoluteArrivalConfirmable)
    }

    @Test fun aBriefHomeMediumThenConfirmedUnavailableCanRecoverOnNormalMedium() {
        val policy = establishedLoss()
        assertTrue(policy.observe(PositionQuality.MEDIUM, 6_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 7_000L))
    }

    @Test fun everyPairOfFreshInformativeUsableQualitiesCanConfirmRecovery() {
        for (first in USABLE) for (second in USABLE) {
            val policy = establishedLoss()
            val one = policy.observe(first, 6_000L)
            assertTrue(one.guidancePaused)
            assertEquals(PositionConfidenceTransition.RECOVERY_PENDING, one.transition)
            val recovered = policy.observe(second, 7_000L)
            assertEquals(second, recovered.quality)
            assertRecovered(recovered)
        }
    }

    @Test fun unavailableStillNeedsExactlyThreeSecondsAndLowClearsItsPendingInterval() {
        val policy = PositionConfidencePolicy()
        assertFalse(policy.observe(PositionQuality.LOW, 1_000L).guidancePaused)
        val first = policy.observe(PositionQuality.UNAVAILABLE, 2_000L)
        assertFalse(first.guidancePaused)
        assertEquals(2_000L, first.degradationPendingSinceElapsedRealtimeMs)
        assertFalse(policy.evaluate(4_999L).guidancePaused)
        val low = policy.observe(PositionQuality.LOW, 4_999L)
        assertFalse(low.guidancePaused)
        assertNull(low.degradationPendingSinceElapsedRealtimeMs)
        val next = policy.observe(PositionQuality.UNAVAILABLE, 5_000L)
        assertEquals(5_000L, next.degradationPendingSinceElapsedRealtimeMs)
        assertFalse(policy.evaluate(7_999L).guidancePaused)
        assertTrue(policy.evaluate(8_000L).guidancePaused)
    }

    @Test fun motionAndWatchdogQualityFlickerCannotEraseAnActualGnssRecoveryFix() {
        val policy = establishedLoss()
        assertTrue(policy.observe(PositionQuality.MEDIUM, 6_000L).guidancePaused)
        assertTrue(policy.observeMotion(PositionQuality.UNAVAILABLE, 6_100L).guidancePaused)
        assertTrue(policy.observeMotion(PositionQuality.LOW, 6_200L).guidancePaused)
        assertTrue(policy.evaluate(6_300L, PositionQuality.UNAVAILABLE).guidancePaused)
        assertTrue(policy.evaluate(6_400L, PositionQuality.LOW).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.LOW, 7_000L))
    }

    @Test fun motionWatchdogAndNonInformativeGnssCannotCreateRecoverySamples() {
        val policy = establishedLoss()
        for (at in 6_000L..9_000L step 1_000L) {
            assertTrue(policy.observeMotion(PositionQuality.HIGH, at).guidancePaused)
            assertTrue(policy.evaluate(at, PositionQuality.LOW).guidancePaused)
            assertTrue(policy.observe(PositionQuality.LOW, at, informativeGnss = false).guidancePaused)
        }
        assertTrue(policy.observe(PositionQuality.LOW, 10_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.LOW, 11_000L))
    }

    @Test fun realUnavailableGnssBetweenRecoveryFixesDiscardsThePartialConfirmation() {
        val policy = establishedLoss()
        policy.observe(PositionQuality.LOW, 6_000L)
        assertTrue(policy.observe(PositionQuality.UNAVAILABLE, 6_500L).guidancePaused)
        assertTrue(policy.observe(PositionQuality.LOW, 7_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 8_000L))
    }

    @Test fun duplicatePastFutureAndStaleSamplesFailClosedAndCannotCompleteRecovery() {
        for ((sample, received, reason) in listOf(
            Triple(6_000L, 7_000L, PositionConfidenceFailureReason.OUT_OF_ORDER),
            Triple(5_500L, 7_000L, PositionConfidenceFailureReason.OUT_OF_ORDER),
            Triple(-1L, 7_000L, PositionConfidenceFailureReason.INVALID_TIME),
            Triple(9_000L, 7_000L, PositionConfidenceFailureReason.INVALID_TIME),
            Triple(6_001L, 16_002L, PositionConfidenceFailureReason.STALE),
        )) {
            val policy = establishedLoss()
            assertTrue(policy.observe(PositionQuality.LOW, 6_000L).guidancePaused)
            val rejected = policy.observe(PositionQuality.LOW, sample, received)
            assertEquals(PositionQuality.UNAVAILABLE, rejected.quality)
            assertEquals(reason, rejected.failureReason)
            assertEquals(PositionConfidenceTransition.FAIL_CLOSED, rejected.transition)
            assertTrue(rejected.guidancePaused)
            assertTrue(policy.observe(PositionQuality.LOW, 17_000L).guidancePaused)
            assertRecovered(policy.observe(PositionQuality.LOW, 18_000L))
        }
    }

    @Test fun expiredOrInvalidMotionEvidenceStillDiscardsThePartialConfirmation() {
        for (invalidMotion in listOf(false, true)) {
            val policy = establishedLoss()
            policy.observe(PositionQuality.LOW, 6_000L)
            val failed = if (invalidMotion) policy.observeMotion(PositionQuality.LOW, 7_000L, 6_999L)
                else policy.evaluate(16_001L, PositionQuality.LOW)
            assertTrue(failed.guidancePaused)
            assertEquals(if (invalidMotion) PositionConfidenceFailureReason.INVALID_TIME else PositionConfidenceFailureReason.STALE,
                failed.failureReason)
            assertTrue(policy.observe(PositionQuality.LOW, 17_000L).guidancePaused)
            assertRecovered(policy.observe(PositionQuality.LOW, 18_000L))
        }
    }

    @Test fun motionAndNonInformativeFixesNeverRefreshTheOriginalTenSecondGnssFreshness() {
        val policy = PositionConfidencePolicy()
        assertFalse(policy.observe(PositionQuality.LOW, 1_000L).guidancePaused)
        assertFalse(policy.observeMotion(PositionQuality.LOW, 10_000L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.LOW, 10_500L, informativeGnss = false).guidancePaused)
        assertFalse(policy.evaluate(11_000L, PositionQuality.LOW).guidancePaused)
        val expired = policy.evaluate(11_001L, PositionQuality.LOW)
        assertEquals(PositionConfidenceFailureReason.STALE, expired.failureReason)
        assertEquals(PositionQuality.UNAVAILABLE, expired.quality)
        assertTrue(expired.guidancePaused)
    }

    @Test fun usableRecoveryKeepsTheOriginalFiveSecondSampleGap() {
        val historical = PositionConfidencePolicyConfig(maximumHighRecoverySampleGapMs = 5_000L)
        val boundary = establishedLoss(historical)
        boundary.observe(PositionQuality.LOW, 6_000L)
        assertRecovered(boundary.observe(PositionQuality.LOW, 11_000L))
        val tooLate = establishedLoss(historical)
        tooLate.observe(PositionQuality.LOW, 6_000L)
        assertTrue(tooLate.observe(PositionQuality.LOW, 11_001L).guidancePaused)
        assertRecovered(tooLate.observe(PositionQuality.LOW, 12_000L))
    }

    @Test fun noActualGnssInputCannotBecomeUsableFromMotionOrWatchdogAlone() {
        for (quality in USABLE) {
            val policy = PositionConfidencePolicy()
            assertTrue(policy.evaluate(500L).guidancePaused)
            assertTrue(policy.observeMotion(quality, 600L).guidancePaused)
            assertTrue(policy.evaluate(700L, quality).guidancePaused)
            assertTrue(policy.observe(quality, 1_000L, informativeGnss = false).guidancePaused)
            assertTrue(policy.observe(quality, 2_000L).guidancePaused)
            assertRecovered(policy.observe(quality, 3_000L))
        }
    }

    @Test fun actualCoordinatorStillRejectsMockAndOldGenerationWithoutPublishingNewFix() {
        val coordinator = PositioningCoordinator()
        assertFalse(coordinator.observeGnss(fix(1_000L, 8.0)).confidence.guidancePaused)
        val mock = coordinator.observeGnss(fix(2_000L, 8.0).copy(mock = true))
        assertEquals(GnssObservationDisposition.HARD_REJECTED, mock.gnssUpdate?.disposition)
        assertEquals(GnssHardRejectReason.MOCK, mock.gnssUpdate?.hardRejectReason)
        assertEquals("A mock must not refresh the previous accepted fix", 1_000L, mock.filtered?.elapsedRealtimeMs)
        assertEquals(1_000L, mock.gnssUpdate?.estimate?.lastInformativeGnssAtElapsedRealtimeMs)
        val generation = coordinator.currentInputGeneration()
        coordinator.reset()
        val old = coordinator.observeGnss(fix(3_000L, 8.0), generation)
        assertNull(old.filtered)
        assertTrue(old.confidence.guidancePaused)
        assertEquals(PositionConfidenceTransition.FAIL_CLOSED, old.confidence.transition)
        assertFalse(coordinator.observeGnss(fix(4_000L, 8.0)).confidence.guidancePaused)
    }

    @Test fun recoveryAnnouncementStillRequiresAnActuallyDeliveredInterruptionNotice() {
        for (delivered in listOf(false, true)) {
            val policy = establishedLoss()
            val notice = requireNotNull(policy.evaluate(5_000L).announcementRequest)
            if (delivered) assertTrue(policy.commitAnnouncementDelivered(notice.token, 5_100L))
            policy.observe(PositionQuality.LOW, 6_000L)
            val recovered = policy.observe(PositionQuality.LOW, 7_000L)
            assertFalse(recovered.guidancePaused)
            assertEquals(PositionConfidenceTransition.RECOVERED, recovered.transition)
            if (delivered) assertEquals(PositionConfidenceAnnouncementKind.RECOVERED, recovered.announcementRequest?.kind)
            else assertNull(recovered.announcementRequest)
        }
    }

    @Test fun configuredRecoveryConfirmationCountIsPreservedForLowAsWellAsHigh() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(requiredHighRecoverySamples = 3))
        policy.observe(PositionQuality.MEDIUM, 1_000L)
        policy.observe(PositionQuality.UNAVAILABLE, 2_000L)
        policy.evaluate(5_000L)
        assertTrue(policy.observe(PositionQuality.LOW, 6_000L).guidancePaused)
        assertTrue(policy.observe(PositionQuality.LOW, 7_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.LOW, 8_000L))
    }

    private fun establishedLoss(config: PositionConfidencePolicyConfig = PositionConfidencePolicyConfig()): PositionConfidencePolicy = PositionConfidencePolicy(config).also {
        assertFalse(it.observe(PositionQuality.MEDIUM, 1_000L).guidancePaused)
        assertFalse(it.observe(PositionQuality.UNAVAILABLE, 2_000L).guidancePaused)
        val confirmed = it.evaluate(5_000L)
        assertEquals(PositionConfidenceTransition.DEGRADED, confirmed.transition)
        assertTrue(confirmed.guidancePaused)
        assertEquals(PositionConfidenceAnnouncementKind.UNAVAILABLE, confirmed.announcementRequest?.kind)
    }

    private fun assertRecovered(decision: PositionConfidenceDecision) {
        assertFalse("Fresh usable GNSS confirmation must recover ordinary guidance", decision.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERED, decision.transition)
        assertNull(decision.failureReason)
    }

    private fun fix(atMs: Long, accuracyM: Double) = GnssPositionObservation(37.5, 127.0, accuracyM, atMs,
        receivedAtElapsedRealtimeMs = atMs + 100L)

    companion object {
        private val USABLE = listOf(PositionQuality.HIGH, PositionQuality.MEDIUM, PositionQuality.LOW)
    }
}
