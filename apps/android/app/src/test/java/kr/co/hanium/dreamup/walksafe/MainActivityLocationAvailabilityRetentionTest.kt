package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidencePolicy
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionQuality
import org.junit.Assert.*
import org.junit.Test

/** Accepted-fix retention only; callback permission/account ownership is not simulated here. */
class MainActivityLocationAvailabilityRetentionTest {
    private val main = MainActivity()
    private val position = TrustedLocation(37.0, 127.0, 3f, 1_000L)

    @Test fun transientAvailabilityLossKeepsAFreshAcceptedFixWithoutRenewingItsTime() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.HIGH, 1_000L)
        assertTrue(main.retainFreshPositionOnAvailabilityLoss(position, policy.evaluate(2_000L), 2_000L))
        assertTrue(main.retainFreshPositionOnAvailabilityLoss(position, policy.evaluate(11_000L), 11_000L))
        assertEquals(1_000L, position.elapsedRealtimeMs)
        assertFalse(main.retainFreshPositionOnAvailabilityLoss(position, policy.evaluate(11_001L), 11_001L))
    }

    @Test fun twoActualMediumRecoveryFixesPermitRetentionButOneDoesNot() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.UNAVAILABLE, 1_000L)
        // Confirm the existing three-second unavailable episode before measuring its recovery.
        val degraded = policy.evaluate(4_000L)
        assertTrue(degraded.guidancePaused)
        assertNotNull(degraded.degradedEpisodeId)
        val first = policy.observe(PositionQuality.MEDIUM, 5_000L)
        assertFalse(main.retainFreshPositionOnAvailabilityLoss(position, first, 5_000L))
        val second = policy.observe(PositionQuality.MEDIUM, 6_000L)
        assertTrue(main.retainFreshPositionOnAvailabilityLoss(position, second, 6_000L))
    }

    @Test fun absentFixAndUnavailableDoNotBecomeTrustedFromAvailabilityAlone() {
        for (quality in listOf(PositionQuality.UNAVAILABLE)) {
            val decision = PositionConfidencePolicy().observe(quality, 1_000L)
            assertFalse(main.retainFreshPositionOnAvailabilityLoss(position, decision, 1_000L))
        }
        val high = PositionConfidencePolicy().observe(PositionQuality.HIGH, 1_000L)
        assertFalse(main.retainFreshPositionOnAvailabilityLoss(null, high, 1_000L))
        assertFalse(main.retainFreshPositionOnAvailabilityLoss(position, high, 999L))
    }

    @Test fun lowQualityRetainsOnlyAnAlreadyFreshFixWithoutRenewingItsTime() {
        val decision = PositionConfidencePolicy().observe(PositionQuality.LOW, 1_000L)
        assertEquals(PositionQuality.LOW, decision.quality)
        assertTrue(main.retainFreshPositionOnAvailabilityLoss(position, decision, 1_000L))
        assertEquals(1_000L, position.elapsedRealtimeMs)
        assertFalse(main.retainFreshPositionOnAvailabilityLoss(null, decision, 1_000L))
        assertFalse(main.retainFreshPositionOnAvailabilityLoss(position, decision, 11_001L))
    }

    @Test fun clockRegressionStillInvalidatesRetentionEvenWithAFreshStoredFix() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.HIGH, 1_000L)
        policy.evaluate(2_000L)
        assertFalse(main.retainFreshPositionOnAvailabilityLoss(position, policy.evaluate(1_500L), 1_500L))
    }
}
