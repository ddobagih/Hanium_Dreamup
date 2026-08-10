package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectorRuntimeSupervisorTest {
    @Test
    fun repeatedUnifiedFailureTriggersOneLegacyTransition() {
        val supervisor = DetectorRuntimeSupervisor(maximumConsecutiveFailures = 3)

        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT, supervisor.onFailure("unified_walksafe", true))
        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT, supervisor.onFailure("unified_walksafe", true))
        assertEquals(DetectorRuntimeFailureAction.LOAD_LEGACY, supervisor.onFailure("unified_walksafe", true))
        supervisor.onSuccess()
        assertEquals(0, supervisor.consecutiveFailures)
    }

    @Test
    fun repeatedLegacyOrNoFallbackFailureBecomesUnavailable() {
        val legacy = DetectorRuntimeSupervisor(maximumConsecutiveFailures = 1)
        val unifiedWithoutFallback = DetectorRuntimeSupervisor(maximumConsecutiveFailures = 1)

        assertEquals(DetectorRuntimeFailureAction.DISABLE, legacy.onFailure("legacy_two_model", true))
        assertEquals(DetectorRuntimeFailureAction.DISABLE, unifiedWithoutFallback.onFailure("unified_walksafe", false))
    }
}
