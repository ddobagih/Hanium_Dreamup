package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.*
import org.junit.Test

/** Real policy transitions from original GNSS timestamps. No recovery-counter/state injection. */
class PositionConfidenceSparseRecoveryRegressionTest {
    @Test fun twoActualUsableGnssMeasurementsSixSecondsApartRecoverWithoutAButtonOrNewTimer() {
        for(quality in listOf(PositionQuality.HIGH,PositionQuality.MEDIUM,PositionQuality.LOW)) {
            val policy=degradedByActualExpiry()
            val first=policy.observe(quality,12_000L)
            assertTrue(first.guidancePaused)
            assertEquals(PositionConfidenceTransition.RECOVERY_PENDING,first.transition)
            assertTrue("A watchdog is not the second GNSS observation",policy.evaluate(17_999L).guidancePaused)
            val second=policy.observe(quality,18_000L)
            assertEquals("Both original observations fit the unchanged 10 s validity window",
                PositionConfidenceTransition.RECOVERED,second.transition)
            assertFalse(second.guidancePaused)
            assertEquals("Recovery must not promote measured precision",quality,second.quality)
        }
    }

    @Test fun recoveryIncludesTheOriginalTenSecondBoundaryButDoesNotCrossIt() {
        val boundary=degradedByActualExpiry()
        boundary.observe(PositionQuality.HIGH,12_000L)
        assertEquals(PositionConfidenceTransition.RECOVERED,boundary.observe(PositionQuality.HIGH,22_000L).transition)

        val expired=degradedByActualExpiry()
        expired.observe(PositionQuality.HIGH,12_000L)
        val expiry=expired.evaluate(22_001L)
        assertEquals(PositionConfidenceFailureReason.STALE,expiry.failureReason)
        assertTrue(expiry.guidancePaused)
        assertTrue(expired.observe(PositionQuality.HIGH,23_000L).guidancePaused)
        assertFalse(expired.observe(PositionQuality.HIGH,24_000L).guidancePaused)
    }

    @Test fun predictionMotionAndNonInformativeGnssCannotCountAsTheSecondRecoveryFix() {
        val policy=degradedByActualExpiry()
        policy.observe(PositionQuality.MEDIUM,12_000L)
        assertTrue(policy.observeMotion(PositionQuality.HIGH,13_000L).guidancePaused)
        assertTrue(policy.observe(PositionQuality.HIGH,14_000L,informativeGnss=false).guidancePaused)
        assertTrue(policy.evaluate(15_000L,PositionQuality.HIGH).guidancePaused)
        val actual=policy.observe(PositionQuality.MEDIUM,18_000L)
        assertEquals(PositionConfidenceTransition.RECOVERED,actual.transition)
        assertFalse(actual.guidancePaused)
        assertEquals(PositionQuality.MEDIUM,actual.quality)
    }

    @Test fun duplicateOrBackwardOriginalGpsStillRetiresPartialRecovery() {
        for(invalidAt in listOf(11_999L,12_000L)) {
            val policy=degradedByActualExpiry()
            policy.observe(PositionQuality.HIGH,12_000L)
            val invalid=policy.observe(PositionQuality.HIGH,invalidAt,13_000L)
            assertEquals(PositionConfidenceFailureReason.OUT_OF_ORDER,invalid.failureReason)
            assertTrue(invalid.guidancePaused)
            assertTrue(policy.observe(PositionQuality.HIGH,14_000L).guidancePaused)
            assertFalse(policy.observe(PositionQuality.HIGH,15_000L).guidancePaused)
        }
    }

    @Test fun aCallerThatExplicitlySelectsAFiveSecondRecoveryGapKeepsItsOwnContract() {
        val policy=degradedByActualExpiry(PositionConfidencePolicyConfig(maximumHighRecoverySampleGapMs=5_000L))
        policy.observe(PositionQuality.HIGH,12_000L)
        val gap=policy.observe(PositionQuality.HIGH,18_000L)
        assertEquals(PositionConfidenceTransition.RECOVERY_PENDING,gap.transition)
        assertTrue(gap.guidancePaused)
        assertFalse(policy.observe(PositionQuality.HIGH,19_000L).guidancePaused)
    }

    @Test fun aLongerConfiguredRecoveryGapCannotExtendTheCallersRawValidityWindow() {
        val policy=PositionConfidencePolicy(PositionConfidencePolicyConfig(maximumSampleAgeMs=5_000L,
            maximumHighRecoverySampleGapMs=10_000L))
        assertFalse(policy.observe(PositionQuality.HIGH,1_000L).guidancePaused)
        assertEquals(PositionConfidenceFailureReason.STALE,policy.evaluate(6_001L).failureReason)
        assertTrue(policy.observe(PositionQuality.HIGH,7_000L).guidancePaused)
        assertTrue("The earlier recovery fix expired; a fresh current fix cannot renew its partial credit",
            policy.observe(PositionQuality.HIGH,13_000L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.HIGH,14_000L).guidancePaused)
    }

    private fun degradedByActualExpiry(config:PositionConfidencePolicyConfig=PositionConfidencePolicyConfig()):PositionConfidencePolicy {
        val policy=PositionConfidencePolicy(config)
        assertFalse(policy.observe(PositionQuality.HIGH,1_000L).guidancePaused)
        val expiry=policy.evaluate(11_001L)
        assertEquals(PositionConfidenceTransition.FAIL_CLOSED,expiry.transition)
        assertEquals(PositionConfidenceFailureReason.STALE,expiry.failureReason)
        assertTrue(expiry.guidancePaused)
        return policy
    }
}
