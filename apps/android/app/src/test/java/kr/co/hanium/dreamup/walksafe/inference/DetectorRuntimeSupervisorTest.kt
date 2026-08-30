package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectorRuntimeSupervisorTest {
    @Test
    fun repeatedUnifiedFailureDuringActiveWalkNeverLoadsLegacyLive() {
        val supervisor = DetectorRuntimeSupervisor(maximumConsecutiveFailures = 3)

        assertEquals(
            DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true),
        )
        assertEquals(
            DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true),
        )
        assertEquals(
            DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true),
        )
        supervisor.onSuccess()
        assertEquals(0, supervisor.consecutiveFailures)
    }

    @Test
    fun legacyCandidateCanOnlyBeSelectedOutsideAnActiveWalk() {
        val supervisor = DetectorRuntimeSupervisor(maximumConsecutiveFailures = 1)

        assertEquals(
            DetectorRuntimeFailureAction.LOAD_LEGACY_FOR_NEXT_WALK,
            supervisor.onFailure("unified_walksafe", true, activeWalk = false),
        )
    }

    @Test
    fun repeatedLegacyOrNoFallbackFailureBecomesUnavailable() {
        val legacy = DetectorRuntimeSupervisor(maximumConsecutiveFailures = 1)
        val unifiedWithoutFallback = DetectorRuntimeSupervisor(maximumConsecutiveFailures = 1)

        assertEquals(
            DetectorRuntimeFailureAction.DISABLE,
            legacy.onFailure("legacy_two_model", true, activeWalk = false),
        )
        assertEquals(
            DetectorRuntimeFailureAction.DISABLE,
            unifiedWithoutFallback.onFailure("unified_walksafe", false, activeWalk = false),
        )
    }
}
