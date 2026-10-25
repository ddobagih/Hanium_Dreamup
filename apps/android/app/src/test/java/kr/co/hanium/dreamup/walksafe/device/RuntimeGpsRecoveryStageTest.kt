package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeGpsRecoveryStageTest {
    @Test fun recoveryStageAndPeriodRetainRawFailureAndOriginalEpisode() {
        val gate = RuntimeMeasuredQualityGate(3_000L, gpsFailureConfirmationPeriods = 3)
        gate.observe(pass(1_000L), 1_000L)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.FRESH, gate.gpsRecoveryStage(1_000L))
        val bad = pass(2_000L).copy(status = EnvironmentEvidenceStatus.FAIL,
            detail = "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE")
        gate.observe(bad, 2_000L)
        listOf(2_000L to 0L, 5_000L to 1L, 8_000L to 2L, 11_000L to 3L, 14_000L to 4L).forEach { (now, period) ->
            assertEquals(period, gate.gpsFailureEvaluationPeriod(now))
            assertEquals(2_000L, gate.gpsFailureStartedAtElapsedRealtimeMs())
            assertEquals(if (period < 3L) RuntimeMeasuredQualityGate.GpsRecoveryStage.RECHECKING
                else RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED, gate.gpsRecoveryStage(now))
            assertSame(bad, gate.latestRawObservation())
            assertEquals(if (period < 3L) 11_000L else null,
                gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        val recovered = pass(14_100L)
        assertSame(recovered, gate.observe(recovered, 14_100L))
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.FRESH, gate.gpsRecoveryStage(14_100L))
        assertNull(gate.gpsFailureEvaluationPeriod(14_100L))
    }

    @Test fun hardUnavailableAndMissingOwnerCannotBeReportedAsRechecking() {
        val gate = RuntimeMeasuredQualityGate(3_000L, gpsFailureConfirmationPeriods = 3)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.UNAVAILABLE, gate.gpsRecoveryStage(1_000L))
        gate.observe(pass(1_000L), 1_000L)
        gate.observe(pass(2_000L).copy(status = EnvironmentEvidenceStatus.FAIL,
            detail = "TRUSTED_FIX_UNAVAILABLE"), 2_000L)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.UNAVAILABLE, gate.gpsRecoveryStage(2_000L))
        assertNull(gate.gpsFailureStartedAtElapsedRealtimeMs())
        gate.reset()
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.UNAVAILABLE, gate.gpsRecoveryStage(2_001L))
    }

    private fun pass(at: Long) = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.GPS_QUALITY,
        WalkRuntimeEpoch("gps-stage", 0L), at, EnvironmentEvidenceStatus.PASS, "gps-test", 10_000L, "GPS_QUALITY_PASSED")
}
