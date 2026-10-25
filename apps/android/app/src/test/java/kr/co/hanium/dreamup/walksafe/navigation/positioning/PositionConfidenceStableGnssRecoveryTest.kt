package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recovery/acknowledgement tests isolate the confirmed-episode state with zero delay.
 * Production's default 3-second onset is exercised by PositionConfidenceDegradationGraceTest.
 */
class PositionConfidenceStableGnssRecoveryTest {
    @Test
    fun transientGnssLowRecoversOnTwoNewInformativeMediumMeasurements() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.MEDIUM, 1_000L)
        val low = policy.observe(PositionQuality.UNAVAILABLE, 2_000L)
        val first = policy.observe(PositionQuality.MEDIUM, 3_000L)
        val second = policy.observe(PositionQuality.MEDIUM, 4_000L)

        assertTrue(low.guidancePaused)
        assertEquals(PositionConfidenceAnnouncementKind.UNAVAILABLE, low.announcementRequest?.kind)
        assertTrue(first.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERY_PENDING, first.transition)
        assertRecovered(second)
        assertEquals(low.degradedEpisodeId, second.degradedEpisodeId)
        assertEquals(0, second.consecutiveHighRecoverySamples)
    }

    @Test
    fun transientMotionLowAlsoRecoversOnlyFromActualGnss() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.HIGH, 1_000L)
        val low = policy.observeMotion(PositionQuality.UNAVAILABLE, 2_000L)
        assertTrue(low.guidancePaused)

        assertTrue(policy.observe(PositionQuality.MEDIUM, 3_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 4_000L))
    }

    @Test
    fun highAndMediumCanConfirmLowRecoveryInEitherOrder() {
        listOf(
            listOf(PositionQuality.HIGH, PositionQuality.MEDIUM),
            listOf(PositionQuality.MEDIUM, PositionQuality.HIGH),
        ).forEach { qualities ->
            val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
            policy.observe(PositionQuality.UNAVAILABLE, 1_000L)

            assertTrue(policy.observe(qualities[0], 2_000L).guidancePaused)
            assertRecovered(policy.observe(qualities[1], 3_000L))
        }
    }

    @Test
    fun watchdogReadsDoNotManufactureRecoveryEvidenceOrDiscardTheFirstRealFix() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.UNAVAILABLE, 1_000L)
        policy.observe(PositionQuality.MEDIUM, 2_000L)

        listOf(2_001L, 2_500L, 3_000L).forEach { assertTrue(policy.evaluate(it).guidancePaused) }
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 3_100L))
    }

    @Test
    fun motionHighOrMediumNeverCountsAsASecondGnssRecoveryMeasurement() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.MEDIUM, 1_000L)
        policy.observeMotion(PositionQuality.UNAVAILABLE, 2_000L)
        policy.observe(PositionQuality.MEDIUM, 3_000L)

        assertTrue(policy.observeMotion(PositionQuality.MEDIUM, 3_100L).guidancePaused)
        assertTrue(policy.observeMotion(PositionQuality.HIGH, 3_200L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 4_000L))
    }

    @Test
    fun nonInformativeGnssDoesNotResumeGuidance() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.UNAVAILABLE, 1_000L)

        for (time in 2_000L..4_000L step 1_000L) {
            assertTrue(policy.observe(PositionQuality.MEDIUM, time, informativeGnss = false).guidancePaused)
        }
        assertTrue(policy.observe(PositionQuality.MEDIUM, 5_000L).guidancePaused)
        assertTrue(policy.observe(PositionQuality.HIGH, 5_500L, informativeGnss = false).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 6_000L))
    }

    @Test
    fun recoveryGapIncludesFiveSecondsButRestartsAfterTheBoundary() {
        val boundary = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L,
            maximumHighRecoverySampleGapMs = 5_000L))
        boundary.observe(PositionQuality.UNAVAILABLE, 0L)
        boundary.observe(PositionQuality.MEDIUM, 1_000L)
        assertRecovered(boundary.observe(PositionQuality.MEDIUM, 6_000L))

        val tooLate = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L,
            maximumHighRecoverySampleGapMs = 5_000L))
        tooLate.observe(PositionQuality.UNAVAILABLE, 0L)
        tooLate.observe(PositionQuality.MEDIUM, 1_000L)
        assertTrue(tooLate.observe(PositionQuality.MEDIUM, 6_001L).guidancePaused)
        assertRecovered(tooLate.observe(PositionQuality.MEDIUM, 7_000L))
    }

    @Test
    fun aNewLowBetweenUsableGnssSamplesRestartsConfirmation() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.MEDIUM, 1_000L)
        policy.observeMotion(PositionQuality.UNAVAILABLE, 2_000L)
        policy.observe(PositionQuality.MEDIUM, 3_000L)
        policy.observeMotion(PositionQuality.LOW, 3_500L)

        assertRecovered(policy.observe(PositionQuality.MEDIUM, 4_000L))
        assertFalse(policy.observe(PositionQuality.MEDIUM, 5_000L).guidancePaused)
    }

    @Test
    fun unavailableNeverRecoversOnMediumAndMediumStillBreaksHighConfirmation() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        assertFalse(policy.observe(PositionQuality.MEDIUM, 0L).guidancePaused)
        policy.observe(PositionQuality.UNAVAILABLE, 1_000L)

        assertTrue(policy.observe(PositionQuality.MEDIUM, 2_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 3_000L))
        assertFalse(policy.observe(PositionQuality.HIGH, 4_000L).guidancePaused)
        assertEquals(0, policy.observe(PositionQuality.MEDIUM, 5_000L).consecutiveHighRecoverySamples)
        assertFalse(policy.observe(PositionQuality.HIGH, 6_000L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.HIGH, 7_000L).guidancePaused)
    }

    @Test
    fun lowEscalationToUnavailablePermanentlyRevokesMediumRecoveryForThatEpisode() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        assertFalse(policy.observe(PositionQuality.MEDIUM, 0L).guidancePaused)
        policy.observe(PositionQuality.LOW, 1_000L)
        policy.observe(PositionQuality.MEDIUM, 2_000L)
        val unavailable = policy.observe(PositionQuality.UNAVAILABLE, 3_000L)
        policy.observe(PositionQuality.LOW, 4_000L)

        assertEquals(PositionConfidenceAnnouncementKind.UNAVAILABLE, unavailable.announcementRequest?.kind)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 5_000L))
        assertFalse(policy.observe(PositionQuality.MEDIUM, 6_000L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.HIGH, 7_000L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.HIGH, 8_000L).guidancePaused)
    }

    @Test
    fun unavailableFromMotionAlsoRequiresTwoHighGnssMeasurements() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.MEDIUM, 1_000L)
        policy.observeMotion(PositionQuality.UNAVAILABLE, 2_000L)

        assertTrue(policy.observe(PositionQuality.MEDIUM, 3_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 4_000L))
        assertFalse(policy.observe(PositionQuality.HIGH, 5_000L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.HIGH, 6_000L).guidancePaused)
    }

    @Test
    fun tenSecondFreshnessBoundaryIsUnchangedAndExpiryRequiresHighRecovery() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.MEDIUM, 1_000L)
        assertFalse(policy.evaluate(11_000L).guidancePaused)
        val stale = policy.evaluate(11_001L)

        assertEquals(PositionConfidenceFailureReason.STALE, stale.failureReason)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 12_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 13_000L))
        assertFalse(policy.observe(PositionQuality.HIGH, 14_000L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.HIGH, 15_000L).guidancePaused)
    }

    @Test
    fun duplicateAndOutOfOrderGnssCannotCompleteMediumRecovery() {
        listOf(1_500L, 2_000L).forEach { invalidSample ->
            val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
            assertFalse(policy.observe(PositionQuality.MEDIUM, 0L).guidancePaused)
            policy.observe(PositionQuality.UNAVAILABLE, 1_000L)
            policy.observe(PositionQuality.MEDIUM, 2_000L)
            val rejected = policy.observe(PositionQuality.MEDIUM, invalidSample, 3_000L)

            assertEquals(PositionConfidenceFailureReason.OUT_OF_ORDER, rejected.failureReason)
            assertTrue(rejected.guidancePaused)
            assertTrue(policy.observe(PositionQuality.MEDIUM, 4_000L).guidancePaused)
            assertRecovered(policy.observe(PositionQuality.MEDIUM, 5_000L))
            assertFalse(policy.observe(PositionQuality.HIGH, 6_000L).guidancePaused)
            assertFalse(policy.observe(PositionQuality.HIGH, 7_000L).guidancePaused)
        }
    }

    @Test
    fun invalidGnssTimestampAndReceiptOrderRevokeMediumRecovery() {
        listOf(-1L to 3_000L, 3_000L to 2_999L, 2_001L to 1_999L).forEach { (sample, receipt) ->
            val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
            assertFalse(policy.observe(PositionQuality.MEDIUM, 0L).guidancePaused)
            policy.observe(PositionQuality.UNAVAILABLE, 1_000L)
            policy.observe(PositionQuality.MEDIUM, 2_000L)
            val rejected = policy.observe(PositionQuality.MEDIUM, sample, receipt)

            assertEquals(PositionConfidenceFailureReason.INVALID_TIME, rejected.failureReason)
            assertTrue(policy.observe(PositionQuality.MEDIUM, 4_000L).guidancePaused)
            assertRecovered(policy.observe(PositionQuality.MEDIUM, 5_000L))
        }
    }

    @Test
    fun backwardWatchdogTimeRevokesMediumRecoveryRatherThanPreservingPartialCredit() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        assertFalse(policy.observe(PositionQuality.MEDIUM, 0L).guidancePaused)
        policy.observe(PositionQuality.UNAVAILABLE, 1_000L)
        policy.observe(PositionQuality.MEDIUM, 2_000L)
        val rejected = policy.evaluate(1_999L)

        assertEquals(PositionConfidenceFailureReason.INVALID_TIME, rejected.failureReason)
        assertTrue(rejected.guidancePaused)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 3_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 4_000L))
        assertFalse(policy.observe(PositionQuality.HIGH, 5_000L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.HIGH, 6_000L).guidancePaused)
    }

    @Test
    fun aStaleReceivedGnssSampleCannotCountForMediumRecovery() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        assertFalse(policy.observe(PositionQuality.MEDIUM, 0L).guidancePaused)
        policy.observe(PositionQuality.UNAVAILABLE, 1_000L)
        policy.observe(PositionQuality.MEDIUM, 2_000L)
        val stale = policy.observe(PositionQuality.MEDIUM, 3_000L, 13_001L)

        assertEquals(PositionConfidenceFailureReason.STALE, stale.failureReason)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 14_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 15_000L))
    }

    @Test
    fun noGnssInputRemainsPausedAndMotionOrTimerCannotRestoreIt() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        val waiting = policy.evaluate(1_000L)
        assertTrue(waiting.guidancePaused)
        assertNull(waiting.degradedEpisodeId)
        policy.observeMotion(PositionQuality.LOW, 2_000L)
        val expired = policy.evaluate(3_000L)

        assertEquals(PositionConfidenceFailureReason.STALE, expired.failureReason)
        assertTrue(policy.observeMotion(PositionQuality.MEDIUM, 4_000L).guidancePaused)
        assertTrue(policy.observeMotion(PositionQuality.HIGH, 5_000L).guidancePaused)
        assertTrue(policy.evaluate(6_000L).guidancePaused)
    }

    @Test
    fun resetAndNewEpisodeDiscardRecoveryEligibilityAndOldAnnouncements() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        val lowRequest = requireNotNull(policy.observe(PositionQuality.UNAVAILABLE, 1_000L).announcementRequest)
        policy.observe(PositionQuality.MEDIUM, 2_000L)
        policy.reset()
        assertFalse(policy.commitAnnouncementDelivered(lowRequest.token, 3_000L))
        assertTrue(policy.evaluate(3_000L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.MEDIUM, 3_500L).guidancePaused)
        policy.observe(PositionQuality.UNAVAILABLE, 4_000L)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 5_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 6_000L))
        policy.observe(PositionQuality.HIGH, 7_000L)
        assertFalse(policy.observe(PositionQuality.HIGH, 8_000L).guidancePaused)

        val nextLow = policy.observe(PositionQuality.UNAVAILABLE, 9_000L)
        assertNotNull(nextLow.degradedEpisodeId)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 10_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 11_000L))
    }

    @Test
    fun delayedBatchedGnssRecoveryUsesDistinctMeasurementTimes() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.MEDIUM, 1_000L)
        policy.observeMotion(PositionQuality.UNAVAILABLE, 5_000L)

        val first = policy.observe(PositionQuality.MEDIUM, 4_000L, 5_100L)
        val second = policy.observe(PositionQuality.MEDIUM, 4_500L, 5_100L)

        assertNull(first.failureReason)
        assertTrue(first.guidancePaused)
        assertNull(second.failureReason)
        assertRecovered(second)
    }

    @Test
    fun configuredRecoveryCountAlsoAppliesToMediumWithoutReducingIt() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(requiredHighRecoverySamples = 3, degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.UNAVAILABLE, 1_000L)

        assertTrue(policy.observe(PositionQuality.MEDIUM, 2_000L).guidancePaused)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 3_000L).guidancePaused)
        assertRecovered(policy.observe(PositionQuality.MEDIUM, 4_000L))
    }

    private fun assertRecovered(decision: PositionConfidenceDecision) {
        assertFalse(decision.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERED, decision.transition)
        assertNull(decision.announcementRequest) // These tests never confirm delivery of a stop notice.
        assertNull(decision.failureReason)
    }
}
