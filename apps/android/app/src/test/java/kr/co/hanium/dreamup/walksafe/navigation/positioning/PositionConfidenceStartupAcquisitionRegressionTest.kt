package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.*
import org.junit.Test

class PositionConfidenceStartupAcquisitionRegressionTest {
    @Test
    fun confirmedInitialUnavailableAcquiresOnTwoFreshInformativeMediumFixes() {
        val policy = confirmedInitialWait()
        val first = policy.observe(PositionQuality.MEDIUM, 5_000L)
        val acquired = policy.observe(PositionQuality.MEDIUM, 6_000L)

        assertTrue(first.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERY_PENDING, first.transition)
        assertAcquired(acquired)
        assertEquals(0, acquired.consecutiveHighRecoverySamples)
    }

    @Test
    fun actualCoordinatorColdCoarseFixThenMediumFixesAcquiresWithoutResetOrHigh() {
        val coordinator = PositioningCoordinator()
        val generation = coordinator.currentInputGeneration()
        val coarse = coordinator.observeGnss(fix(1_000L, 30.0))
        val confirmed = coordinator.evaluate(4_100L)
        val first = coordinator.observeGnss(fix(5_000L, 5.0))
        val acquired = coordinator.observeGnss(fix(6_000L, 5.0))

        assertEquals(PositionQuality.UNAVAILABLE, coarse.quality)
        assertEquals(PositionConfidenceTransition.DEGRADED, confirmed.confidence.transition)
        assertTrue(confirmed.confidence.guidancePaused)
        assertEquals(PositionQuality.MEDIUM, first.quality)
        assertTrue(first.confidence.guidancePaused)
        assertEquals(PositionQuality.MEDIUM, acquired.quality)
        assertAcquired(acquired.confidence)
        assertEquals(generation, coordinator.currentInputGeneration())
        assertEquals(6_000L, acquired.raw?.elapsedRealtimeMs)
        assertEquals(6_000L, acquired.gnssUpdate?.estimateAtGnssMeasurement?.lastInformativeGnssAtElapsedRealtimeMs)
        assertEquals(6_000L, acquired.filtered?.elapsedRealtimeMs)
        assertNotEquals(GnssObservationDisposition.HARD_REJECTED, acquired.gnssUpdate?.disposition)
    }

    @Test
    fun initialGraceStillLastsExactlyThreeSecondsAndDoesNotChangeQuality() {
        val policy = PositionConfidencePolicy()
        val initial = policy.observe(PositionQuality.UNAVAILABLE, 1_000L)
        val waiting = policy.evaluate(3_999L)
        val confirmed = policy.evaluate(4_000L)

        listOf(initial, waiting).forEach {
            assertEquals(PositionQuality.UNAVAILABLE, it.quality)
            assertTrue(it.guidancePaused)
            assertNull(it.degradedEpisodeId)
            assertNull(it.announcementRequest)
            assertEquals(1_000L, it.degradationPendingSinceElapsedRealtimeMs)
        }
        assertEquals(PositionConfidenceTransition.DEGRADED, confirmed.transition)
        assertNotNull(confirmed.degradedEpisodeId)
    }

    @Test
    fun noInputWatchdogAndShortInitialDipDoNotDelayFirstUsableFix() {
        val noInput = PositionConfidencePolicy()
        assertNull(noInput.evaluate(1_000L).degradedEpisodeId)
        assertNull(noInput.evaluate(20_000L).degradedEpisodeId)
        assertFalse(noInput.observe(PositionQuality.MEDIUM, 21_000L).guidancePaused)

        val shortDip = PositionConfidencePolicy()
        shortDip.observe(PositionQuality.UNAVAILABLE, 1_000L)
        val acquired = shortDip.observe(PositionQuality.MEDIUM, 2_000L)
        assertFalse(acquired.guidancePaused)
        assertNull(acquired.degradedEpisodeId)
        assertNull(acquired.announcementRequest)
    }

    @Test
    fun startupAcquisitionDoesNotCountMotionWatchdogOrNonInformativeFixes() {
        val policy = confirmedInitialWait()
        assertTrue(policy.observe(PositionQuality.MEDIUM, 5_000L).guidancePaused)
        assertTrue(policy.observeMotion(PositionQuality.MEDIUM, 5_100L).guidancePaused)
        assertTrue(policy.evaluate(5_200L).guidancePaused)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 5_300L, informativeGnss = false).guidancePaused)
        assertTrue(policy.observeMotion(PositionQuality.HIGH, 5_400L).guidancePaused)
        assertAcquired(policy.observe(PositionQuality.MEDIUM, 6_000L))
    }

    @Test
    fun startupAcquisitionKeepsFiveSecondRecoveryGap() {
        val historical = PositionConfidencePolicyConfig(maximumHighRecoverySampleGapMs = 5_000L)
        val boundary = confirmedInitialWait(historical)
        assertTrue(boundary.observe(PositionQuality.MEDIUM, 5_000L).guidancePaused)
        assertAcquired(boundary.observe(PositionQuality.MEDIUM, 10_000L))

        val tooLate = confirmedInitialWait(historical)
        assertTrue(tooLate.observe(PositionQuality.MEDIUM, 5_000L).guidancePaused)
        assertTrue(tooLate.observe(PositionQuality.MEDIUM, 10_001L).guidancePaused)
        assertAcquired(tooLate.observe(PositionQuality.MEDIUM, 11_000L))
    }

    @Test
    fun anotherInitialBadFixClearsTheFirstAcquisitionSample() {
        val policy = confirmedInitialWait()
        policy.observe(PositionQuality.MEDIUM, 5_000L)
        assertTrue(policy.observe(PositionQuality.UNAVAILABLE, 5_500L).guidancePaused)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 6_000L).guidancePaused)
        assertAcquired(policy.observe(PositionQuality.MEDIUM, 7_000L))
    }

    @Test
    fun initialInvalidTimeOrStaleInputFailsClosedButDoesNotPoisonFutureFreshAcquisition() {
        listOf(
            Triple(-1L, 1_000L, PositionConfidenceFailureReason.INVALID_TIME),
            Triple(2_000L, 1_999L, PositionConfidenceFailureReason.INVALID_TIME),
            Triple(1_000L, 11_001L, PositionConfidenceFailureReason.STALE),
        ).forEach { (sample, received, reason) ->
            val policy = PositionConfidencePolicy()
            val failed = policy.observe(PositionQuality.MEDIUM, sample, received)
            assertEquals(PositionQuality.UNAVAILABLE, failed.quality)
            assertEquals(PositionConfidenceTransition.FAIL_CLOSED, failed.transition)
            assertEquals(reason, failed.failureReason)
            assertTrue(failed.guidancePaused)
            assertEquals(0, failed.consecutiveHighRecoverySamples)
            assertTrue(policy.observe(PositionQuality.MEDIUM, 12_000L).guidancePaused)
            assertAcquired(policy.observe(PositionQuality.MEDIUM, 13_000L))
        }
    }

    @Test
    fun initialDuplicateCannotServeAsTheSecondAcquisitionFix() {
        val policy = confirmedInitialWait()
        assertTrue(policy.observe(PositionQuality.MEDIUM, 5_000L).guidancePaused)
        val duplicate = policy.observe(PositionQuality.MEDIUM, 5_000L, 5_100L)
        assertEquals(PositionConfidenceFailureReason.OUT_OF_ORDER, duplicate.failureReason)
        assertTrue(duplicate.guidancePaused)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 6_000L).guidancePaused)
        assertAcquired(policy.observe(PositionQuality.MEDIUM, 7_000L))
    }

    @Test
    fun actualCoordinatorRejectsColdCachedStaleAndMockFixesWithoutPublishingThem() {
        listOf(
            fix(1_000L, 3.0).copy(receivedAtElapsedRealtimeMs = 12_000L) to GnssHardRejectReason.STALE,
            fix(1_000L, 3.0).copy(mock = true) to GnssHardRejectReason.MOCK,
        ).forEach { (invalid, reason) ->
            val coordinator = PositioningCoordinator()
            val rejected = coordinator.observeGnss(invalid)
            assertEquals(GnssObservationDisposition.HARD_REJECTED, rejected.gnssUpdate?.disposition)
            assertEquals(reason, rejected.gnssUpdate?.hardRejectReason)
            assertNull(rejected.filtered)
            assertTrue(rejected.confidence.guidancePaused)
            val fresh = coordinator.observeGnss(fix(13_000L, 5.0))
            assertEquals(PositionQuality.MEDIUM, fresh.quality)
            assertFalse(fresh.confidence.guidancePaused)
            assertEquals(13_000L, fresh.raw?.elapsedRealtimeMs)
        }
    }

    @Test
    fun establishedUnavailableLossStillRequiresTwoHighFixes() {
        val policy = establishedUnavailableLoss()
        assertTrue(policy.observe(PositionQuality.MEDIUM, 6_000L).guidancePaused)
        assertAcquired(policy.observe(PositionQuality.MEDIUM, 7_000L))
        assertFalse(policy.observe(PositionQuality.HIGH, 8_000L).guidancePaused)
        val recovered = policy.observe(PositionQuality.HIGH, 9_000L)
        assertFalse(recovered.guidancePaused)
        assertEquals(PositionConfidenceTransition.STABLE, recovered.transition)
    }

    @Test
    fun establishedStaleInvalidAndDuplicateLossStillRequireHighAndDiscardRecoverySamples() {
        listOf(
            Triple(-1L, 2_000L, PositionConfidenceFailureReason.INVALID_TIME),
            Triple(1_000L, 2_000L, PositionConfidenceFailureReason.OUT_OF_ORDER),
            Triple(2_000L, 12_001L, PositionConfidenceFailureReason.STALE),
        ).forEach { (sample, received, reason) ->
            val policy = PositionConfidencePolicy()
            assertFalse(policy.observe(PositionQuality.MEDIUM, 1_000L).guidancePaused)
            val rejected = policy.observe(PositionQuality.MEDIUM, sample, received)
            assertTrue(rejected.guidancePaused)
            assertEquals(reason, rejected.failureReason)
            assertTrue(policy.observe(PositionQuality.MEDIUM, 13_000L).guidancePaused)
            assertAcquired(policy.observe(PositionQuality.MEDIUM, 14_000L))
            assertFalse(policy.observe(PositionQuality.HIGH, 15_000L).guidancePaused)
            assertFalse(policy.observe(PositionQuality.HIGH, 16_000L).guidancePaused)
        }
    }

    @Test
    fun acquiredStartupPositionIsAnEstablishedPositionForTheNextLoss() {
        val policy = confirmedInitialWait()
        policy.observe(PositionQuality.MEDIUM, 5_000L)
        assertAcquired(policy.observe(PositionQuality.MEDIUM, 6_000L))
        policy.observe(PositionQuality.UNAVAILABLE, 7_000L)
        assertTrue(policy.evaluate(10_000L).guidancePaused)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 11_000L).guidancePaused)
        assertAcquired(policy.observe(PositionQuality.MEDIUM, 12_000L))
        assertFalse(policy.observe(PositionQuality.HIGH, 13_000L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.HIGH, 14_000L).guidancePaused)
    }

    private fun confirmedInitialWait(config: PositionConfidencePolicyConfig = PositionConfidencePolicyConfig()): PositionConfidencePolicy = PositionConfidencePolicy(config).also {
        val first = it.observe(PositionQuality.UNAVAILABLE, 1_000L)
        assertTrue(first.guidancePaused)
        assertNull(first.degradedEpisodeId)
        assertEquals(PositionConfidenceTransition.DEGRADED, it.evaluate(4_000L).transition)
    }

    private fun establishedUnavailableLoss(): PositionConfidencePolicy = PositionConfidencePolicy().also {
        assertFalse(it.observe(PositionQuality.MEDIUM, 1_000L).guidancePaused)
        assertFalse(it.observe(PositionQuality.UNAVAILABLE, 2_000L).guidancePaused)
        assertTrue(it.evaluate(5_000L).guidancePaused)
    }

    private fun assertAcquired(decision: PositionConfidenceDecision) {
        assertFalse("Initial acquisition must not remain paused after fresh usable confirmation", decision.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERED, decision.transition)
        assertNull(decision.announcementRequest)
        assertNull(decision.failureReason)
    }

    private fun fix(atMs: Long, accuracyM: Double) = GnssPositionObservation(
        latitude = 37.5,
        longitude = 127.0,
        horizontalAccuracyM = accuracyM,
        elapsedRealtimeMs = atMs,
        receivedAtElapsedRealtimeMs = atMs + 100L,
    )
}
