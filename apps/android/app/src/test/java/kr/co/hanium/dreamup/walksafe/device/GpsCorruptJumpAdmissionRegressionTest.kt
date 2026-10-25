package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GpsCorruptJumpAdmissionRegressionTest {
    @Test
    fun anActuallyQualifiedSingleJumpKeepsItsOriginalQualificationAndBoundedGrace() {
        val gate = gate()
        val pass = evidence(NOW_MS)
        gate.observe(pass, NOW_MS)
        val single = evidence(NOW_MS + 100L, SINGLE_DETAIL)
        gate.withGpsMeasurementFailure(EPOCH, NOW_MS + 100L, PROFILE,
            RuntimeMeasuredQualityGate.GpsMeasurementFailure.CORRUPT_JUMP) {
            assertSame(pass, gate.observe(single, NOW_MS + 100L))
        }
        assertEquals(single, gate.latestRawObservation())
        assertEquals(NOW_MS + 100L + 18_000L, gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertSame(pass, gate.current(NOW_MS + 1_000L))
        assertEquals(NOW_MS, gate.current(NOW_MS + 1_000L)?.observedAtElapsedRealtimeMs)
    }

    @Test
    fun aNonSingleCorruptJumpImmediatelyEndsThePreviousQualifiedSingleGrace() {
        val gate = gate()
        val pass = evidence(NOW_MS)
        gate.observe(pass, NOW_MS)
        gate.withGpsMeasurementFailure(EPOCH, NOW_MS + 100L, PROFILE,
            RuntimeMeasuredQualityGate.GpsMeasurementFailure.CORRUPT_JUMP) {
            assertSame(pass, gate.observe(evidence(NOW_MS + 100L, SINGLE_DETAIL), NOW_MS + 100L))
        }
        val repeatedAt = NOW_MS + 200L
        val repeated = evidence(repeatedAt, "TRUSTED_FIX_UNAVAILABLE")
        val rejected = repeated.copy(detail = "GPS_CORRUPT_JUMP")
        gate.withGpsMeasurementFailure(EPOCH, repeatedAt, PROFILE,
            RuntimeMeasuredQualityGate.GpsMeasurementFailure.CORRUPT_JUMP) {
            assertEquals(rejected, gate.observe(repeated, repeatedAt))
        }
        assertEquals(rejected, gate.latestRawObservation())
        assertEquals(rejected, gate.current(repeatedAt + 1L))
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertNull(gate.gpsFailureStartedAtElapsedRealtimeMs())
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.UNAVAILABLE, gate.gpsRecoveryStage(repeatedAt + 1L))
    }

    @Test
    fun aRichCorruptCauseWithoutSingleQualificationCannotBorrowFreshEvidence() {
        val gate = gate()
        gate.observe(evidence(NOW_MS), NOW_MS)
        val corrupt = evidence(NOW_MS + 100L, "GPS_CORRUPT_JUMP")
        assertEquals(corrupt, gate.observe(corrupt, NOW_MS + 100L))
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        val recovered = evidence(NOW_MS + 200L)
        assertSame(recovered, gate.observe(recovered, NOW_MS + 200L))
        assertEquals(NOW_MS + 200L, gate.latestRawObservation()?.observedAtElapsedRealtimeMs)
    }

    private fun gate() = RuntimeMeasuredQualityGate(3_000L, gpsFailureConfirmationPeriods = 6)

    private fun evidence(time: Long, detail: String = "GPS_QUALITY_PASSED") = MeasuredEnvironmentEvidence(
        factor = OfficialEnvironmentFactor.GPS_QUALITY,
        epoch = EPOCH,
        observedAtElapsedRealtimeMs = time,
        status = if (detail == "GPS_QUALITY_PASSED") EnvironmentEvidenceStatus.PASS else EnvironmentEvidenceStatus.FAIL,
        measurementProfileId = PROFILE,
        maximumEvidenceAgeMs = 10_000L,
        detail = detail,
    )

    private companion object {
        const val NOW_MS = 10_000L
        const val PROFILE = "gps-field-approved-r001"
        const val SINGLE_DETAIL = "GPS_SINGLE_OUTLIER_AGAINST_FRESH_ESTIMATE"
        val EPOCH = WalkRuntimeEpoch("stationary-walk", 1L)
    }
}
