package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeGpsFailurePeriodsTest {
    @Test fun gpsRestrictsAtExactlyNineSecondsAndStopsSchedulingTheConfirmedDeadline() {
        val gate = gpsGate()
        val good = pass(1_000L)
        gate.observe(good, 1_000L)
        gate.observe(bad(2_000L), 2_000L)
        listOf(4_999L, 5_000L, 7_999L, 8_000L, 10_999L).forEach { now ->
            assertSame(good, gate.current(now))
            assertEquals(11_000L, gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        assertEquals(EnvironmentEvidenceStatus.FAIL, gate.current(11_000L)?.status)
        gate.observe(bad(11_001L), 11_001L)
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(EnvironmentEvidenceStatus.FAIL, gate.current(11_001L)?.status)
    }

    @Test fun badCallbacksAndWatchdogsCannotExtendTheFirstFailureDeadline() {
        val gate = gpsGate()
        val good = pass(1_000L)
        gate.observe(good, 1_000L)
        repeat(90) { i ->
            val now = 2_000L + i * 100L
            assertSame(good, gate.observe(bad(now), now))
            assertEquals(11_000L, gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        assertEquals(EnvironmentEvidenceStatus.FAIL, gate.observe(bad(11_000L), 11_000L).status)
    }

    @Test fun noGoodFixCanBeInventedDuringInitialCoarseAcquisition() {
        val gate = gpsGate()
        assertEquals(EnvironmentEvidenceStatus.FAIL, gate.observe(bad(1_000L), 1_000L).status)
        assertEquals(EnvironmentEvidenceStatus.FAIL, gate.current(4_000L)?.status)
        assertEquals(10_000L, gate.pendingFailureDeadlineElapsedRealtimeMs())
        val acquired = pass(5_000L)
        assertSame(acquired, gate.observe(acquired, 5_000L))
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun gpsPeriodsDoNotChangeTheCameraFailureWindow() {
        val gate = gpsGate()
        val good = pass(1_000L).copy(factor = OfficialEnvironmentFactor.CAMERA_QUALITY)
        val bad = good.copy(observedAtElapsedRealtimeMs = 2_000L, status = EnvironmentEvidenceStatus.FAIL,
            detail = CameraFrameQualityReason.SHAKE_OUTSIDE_APPROVED_RANGE.name)
        gate.observe(good, 1_000L)
        assertSame(good, gate.observe(bad, 2_000L))
        assertEquals(5_000L, gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(EnvironmentEvidenceStatus.FAIL, gate.current(5_000L)?.status)
    }

    @Test fun originalSourceFreshnessWinsEvenWhenNineSecondConfirmationIsStillPending() {
        val gate = gpsGate()
        gate.observe(pass(1_000L), 1_000L)
        gate.observe(bad(7_000L), 7_000L)
        assertEquals(EnvironmentEvidenceStatus.PASS, gate.current(11_000L)?.status)
        assertNotEquals(EnvironmentEvidenceStatus.PASS, gate.current(11_001L)?.status)
        assertEquals(16_000L, gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun onlyNewFreshGoodResetsTheEpisodeAndKeepsItsRealTimestamp() {
        val gate = gpsGate()
        val old = pass(1_000L)
        gate.observe(old, 1_000L)
        gate.observe(bad(2_000L), 2_000L)
        gate.observe(old, 5_000L)
        assertEquals(11_000L, gate.pendingFailureDeadlineElapsedRealtimeMs())
        val good = pass(5_100L)
        assertSame(good, gate.observe(good, 5_100L))
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        gate.observe(good, 6_000L)
        assertEquals(5_100L, gate.current(6_000L)?.observedAtElapsedRealtimeMs)
        gate.observe(bad(6_100L), 6_100L)
        assertEquals(15_100L, gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun hardFailureAndEpochChangeImmediatelyRetireTheGoodFixAndEpisode() {
        listOf("TRUSTED_FIX_UNAVAILABLE", "STALE_OR_INVALID_TIMESTAMP").forEach { detail ->
            val gate = gpsGate()
            gate.observe(pass(1_000L), 1_000L)
            gate.observe(bad(2_000L), 2_000L)
            val hard = bad(2_100L).copy(detail = detail)
            assertSame(hard, gate.observe(hard, 2_100L))
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        val gate = gpsGate()
        gate.observe(pass(1_000L), 1_000L)
        gate.observe(bad(2_000L), 2_000L)
        val other = bad(2_100L).copy(epoch = WalkRuntimeEpoch("new-owner", 0L))
        assertSame(other, gate.observe(other, 2_100L))
    }

    private fun gpsGate() = RuntimeMeasuredQualityGate(3_000L, gpsFailureConfirmationPeriods = 3)
    private fun pass(at: Long) = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.GPS_QUALITY,
        WalkRuntimeEpoch("gps", 0L), at, EnvironmentEvidenceStatus.PASS, "gps-test", 10_000L, "GPS_QUALITY_PASSED")
    private fun bad(at: Long) = pass(at).copy(status = EnvironmentEvidenceStatus.FAIL,
        detail = "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE")
}
