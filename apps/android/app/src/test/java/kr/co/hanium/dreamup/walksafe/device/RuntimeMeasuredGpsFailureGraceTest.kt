package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class RuntimeMeasuredGpsFailureGraceTest {
    @Test fun admittedRecoverableMeasurementCausesKeepTheirQualificationAndOriginalDeadline() {
        RuntimeMeasuredQualityGate.GpsMeasurementFailure.entries.forEach { cause ->
            val gate = RuntimeMeasuredQualityGate(3_000L)
            val good = pass(6_000L)
            assertSame(good, gate.observe(good, 6_000L))
            val failed = recoverableFailure(6_100L, cause)
            val expectedDetail = if (cause == RuntimeMeasuredQualityGate.GpsMeasurementFailure.CORRUPT_JUMP)
                failed.detail else "GPS_${cause.name}"
            var selected: MeasuredEnvironmentEvidence? = null
            gate.withGpsMeasurementFailure(EPOCH, 6_100L, PROFILE, cause) {
                selected = gate.observe(failed, 6_100L)
            }
            assertSame(good, selected)
            assertEquals(9_100L, gate.pendingFailureDeadlineElapsedRealtimeMs())
            assertEquals(expectedDetail, gate.latestRawObservation()?.detail)
            assertEquals(EnvironmentEvidenceStatus.FAIL, gate.latestRawObservation()?.status)
            assertSame(good, gate.current(9_099L))
            val expired = gate.current(9_100L)
            assertEquals(EnvironmentEvidenceStatus.FAIL, expired?.status)
            assertEquals(expectedDetail, expired?.detail)
            assertEquals(6_100L, expired?.observedAtElapsedRealtimeMs)
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
            assertEquals(expired, gate.current(9_101L))
        }
    }

    @Test fun differentMeasurementFailuresAndDuplicateGoodCannotRenewTheEpisode() {
        val gate = RuntimeMeasuredQualityGate(3_000L)
        val good = pass(6_000L)
        gate.observe(good, 6_000L)
        classified(gate, 6_100L, RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY)
        classified(gate, 6_400L, RuntimeMeasuredQualityGate.GpsMeasurementFailure.CORRUPT_JUMP)
        val coarse = failure(7_000L).copy(detail = "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE")
        assertSame(good, gate.observe(coarse, 7_000L))
        assertSame(good, gate.observe(good, 7_500L))
        assertSame(good, gate.observe(pass(7_000L), 7_600L))
        assertEquals(9_100L, gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertSame(good, gate.current(9_099L))
        assertEquals(coarse, gate.current(9_100L))
    }

    @Test fun newerRealGoodImmediatelyRecoversAndTheNextEpisodeGetsItsOwnDeadline() {
        val gate = RuntimeMeasuredQualityGate(3_000L)
        gate.observe(pass(6_000L), 6_000L)
        classified(gate, 6_100L, RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY)
        val recovery = pass(6_400L)
        assertSame(recovery, gate.observe(recovery, 6_400L))
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertSame(recovery, gate.observe(recovery, 6_500L))
        assertEquals(6_400L, gate.current(6_500L)?.observedAtElapsedRealtimeMs)
        classified(gate, 9_000L, RuntimeMeasuredQualityGate.GpsMeasurementFailure.NUMERICAL_FAILURE)
        assertEquals(12_000L, gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertSame(recovery, gate.current(11_999L))
        assertEquals("GPS_NUMERICAL_FAILURE", gate.current(12_000L)?.detail)
    }

    @Test fun genericUnavailableAndMismatchedSourceMetadataKeepTheImmediateHardBoundary() {
        val original = failure(6_100L)
        listOf(
            original,
            original.copy(epoch = WalkRuntimeEpoch("other", 0L)),
            original.copy(epoch = EPOCH.copy(recoveryGeneration = 1L)),
            original.copy(observedAtElapsedRealtimeMs = 6_101L),
            original.copy(measurementProfileId = "other"),
            original.copy(detail = "STALE_OR_INVALID_TIMESTAMP"),
            original.copy(status = EnvironmentEvidenceStatus.UNKNOWN, detail = "TRUST_STATE_UNKNOWN"),
        ).forEachIndexed { index, failed ->
            val gate = RuntimeMeasuredQualityGate(3_000L)
            gate.observe(pass(6_000L), 6_000L)
            if (index == 0) assertEquals(failed, gate.observe(failed, 6_100L))
            else gate.withGpsMeasurementFailure(EPOCH, 6_100L, PROFILE, RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY) {
                assertEquals(failed, gate.observe(failed, 6_101L))
            }
            assertEquals(failed, gate.current(6_200L))
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
    }

    @Test fun invalidTimeNoPriorGoodAndGoodFreshnessDoNotBorrowOrExtendEvidence() {
        val cold = RuntimeMeasuredQualityGate(3_000L)
        classified(cold, 6_100L, RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY)
        assertEquals(EnvironmentEvidenceStatus.FAIL, cold.current(6_100L)?.status)
        assertNull(cold.pendingFailureDeadlineElapsedRealtimeMs())
        val gate = RuntimeMeasuredQualityGate(3_000L)
        val good = pass(6_000L).copy(maximumEvidenceAgeMs = 100L)
        gate.observe(good, 6_000L)
        classified(gate, 6_010L, RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY)
        assertSame(good, gate.current(6_100L))
        assertEquals(EnvironmentEvidenceStatus.FAIL, gate.current(6_101L)?.status)
        val future = RuntimeMeasuredQualityGate(3_000L)
        future.observe(pass(6_000L), 6_000L)
        future.withGpsMeasurementFailure(EPOCH, 6_200L, PROFILE, RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY) {
            assertEquals(EnvironmentEvidenceStatus.FAIL, future.observe(failure(6_200L), 6_100L).status)
        }
        assertNull(future.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun classificationDoesNotLeakIntoTheNextObservationAfterAnException() {
        val gate = RuntimeMeasuredQualityGate(3_000L)
        gate.observe(pass(6_000L), 6_000L)
        try {
            gate.withGpsMeasurementFailure(EPOCH, 6_100L, PROFILE, RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY) {
                error("source failed before recording")
            }
            fail("Expected the source exception")
        } catch (_: IllegalStateException) { }
        assertEquals(failure(6_100L), gate.observe(failure(6_100L), 6_100L))
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun admittedMissingAccuracyRetainsOnlyTheOriginalGoodWithoutChangingUnknownStatus() {
        val gate = RuntimeMeasuredQualityGate(3_000L)
        val good = pass(6_000L)
        gate.observe(good, 6_000L)
        val missing = failure(6_100L).copy(status = EnvironmentEvidenceStatus.UNKNOWN, detail = "INVALID_ACCURACY")
        gate.withGpsMeasurementFailure(EPOCH, 6_100L, PROFILE, RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY) {
            assertSame(good, gate.observe(missing, 6_100L))
        }
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, gate.latestRawObservation()?.status)
        assertEquals(6_100L, gate.latestRawObservation()?.observedAtElapsedRealtimeMs)
        assertEquals("GPS_INVALID_ACCURACY", gate.latestRawObservation()?.detail)
        assertSame(good, gate.current(9_099L))
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, gate.current(9_100L)?.status)
        assertEquals("GPS_INVALID_ACCURACY", gate.current(9_100L)?.detail)
        val generic = RuntimeMeasuredQualityGate(3_000L)
        generic.observe(good, 6_000L)
        assertEquals(missing, generic.observe(missing, 6_100L))
        assertNull(generic.pendingFailureDeadlineElapsedRealtimeMs())
    }

    private fun classified(gate: RuntimeMeasuredQualityGate, at: Long, cause: RuntimeMeasuredQualityGate.GpsMeasurementFailure) =
        gate.withGpsMeasurementFailure(EPOCH, at, PROFILE, cause) { gate.observe(recoverableFailure(at, cause), at) }

    private fun recoverableFailure(at: Long, cause: RuntimeMeasuredQualityGate.GpsMeasurementFailure) =
        // CORRUPT_JUMP alone is hard. This fixture represents the independently qualified single outlier.
        if (cause == RuntimeMeasuredQualityGate.GpsMeasurementFailure.CORRUPT_JUMP)
            failure(at).copy(detail = "GPS_SINGLE_OUTLIER_AGAINST_FRESH_ESTIMATE") else failure(at)

    private fun failure(at: Long) = pass(at).copy(status = EnvironmentEvidenceStatus.FAIL, detail = "TRUSTED_FIX_UNAVAILABLE")
    private fun pass(at: Long) = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.GPS_QUALITY, EPOCH, at,
        EnvironmentEvidenceStatus.PASS, PROFILE, 10_000L, "GPS_QUALITY_PASSED")

    private companion object {
        val EPOCH = WalkRuntimeEpoch("walk", 0L)
        const val PROFILE = "gps-test"
    }
}
