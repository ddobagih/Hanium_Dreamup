package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeMeasuredQualityGateTest {
    @Test
    fun isolatedPoorQualityKeepsOriginalPassAndRecoveryClearsTheFailureWindow() {
        val gate = RuntimeMeasuredQualityGate()
        val pass = evidence()
        gate.observe(pass, NOW_MS)
        assertEquals(pass, gate.observe(failure(NOW_MS + 100L), NOW_MS + 100L))
        val recovered = evidence(observedAtMs = NOW_MS + 1_000L)
        assertEquals(recovered, gate.observe(recovered, NOW_MS + 1_000L))
        assertEquals(recovered, gate.observe(failure(NOW_MS + 1_600L), NOW_MS + 1_600L))
    }

    @Test
    fun sustainedPoorQualityFailsAtElapsedTimeBoundaryRegardlessOfFrameRate() {
        val gate = RuntimeMeasuredQualityGate()
        val pass = evidence()
        gate.observe(pass, NOW_MS)
        repeat(1_500) { index ->
            val now = NOW_MS + 1L + index
            assertEquals(pass, gate.observe(failure(now), now))
        }
        val now = NOW_MS + 1_501L
        val failed = failure(now)
        assertEquals(failed, gate.observe(failed, now))
        val following = failure(now + 1L)
        assertEquals(following, gate.observe(following, now + 1L))
    }

    @Test
    fun unavailableFrameMissingSensorsAndUntrustedLocationRevokePassImmediately() {
        val reasons = listOf(
            "FRAME_UNAVAILABLE", "FRAME_STATE_UNKNOWN", "INVALID_MEASUREMENT",
            "STALE_OR_INVALID_TIMESTAMP", "TRUSTED_FIX_UNAVAILABLE", "INVALID_ACCURACY",
        )
        reasons.forEach { reason ->
            val gate = RuntimeMeasuredQualityGate()
            gate.observe(evidence(), NOW_MS)
            val failed = failure(NOW_MS + 1L).copy(detail = reason)
            assertEquals(failed, gate.observe(failed, NOW_MS + 1L))
            val qualityFailure = failure(NOW_MS + 2L)
            assertEquals(qualityFailure, gate.observe(qualityFailure, NOW_MS + 2L))
        }
    }

    @Test
    fun previousPassNeverOutlivesItsOwnFreshnessLimit() {
        val gate = RuntimeMeasuredQualityGate()
        val pass = evidence().copy(maximumEvidenceAgeMs = 100L)
        gate.observe(pass, NOW_MS)
        assertEquals(pass, gate.observe(failure(NOW_MS + 1L), NOW_MS + 1L))
        val failed = failure(NOW_MS + 101L)
        assertEquals(failed, gate.observe(failed, NOW_MS + 101L))
    }

    @Test
    fun noPriorPassOrDifferentEpochProfileAndFactorCannotBorrowAPass() {
        val variants = listOf(
            failure(NOW_MS + 1L),
            failure(NOW_MS + 1L).copy(epoch = WalkRuntimeEpoch("another", 1L)),
            failure(NOW_MS + 1L).copy(measurementProfileId = "another"),
            failure(NOW_MS + 1L).copy(factor = OfficialEnvironmentFactor.DRY_WEATHER),
        )
        variants.forEachIndexed { index, failed ->
            val gate = RuntimeMeasuredQualityGate()
            if (index != 0) gate.observe(evidence(), NOW_MS)
            assertEquals(failed, gate.observe(failed, NOW_MS + 1L))
        }
    }

    @Test
    fun poorGpsAccuracyCanBeDebouncedButUnknownEvidenceCannot() {
        val gate = RuntimeMeasuredQualityGate()
        val pass = evidence().copy(factor = OfficialEnvironmentFactor.GPS_QUALITY)
        gate.observe(pass, NOW_MS)
        val poor = pass.copy(
            status = EnvironmentEvidenceStatus.FAIL,
            detail = "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE",
            observedAtElapsedRealtimeMs = NOW_MS + 1L,
        )
        assertEquals(pass, gate.observe(poor, NOW_MS + 1L))
        val unknown = poor.copy(status = EnvironmentEvidenceStatus.UNKNOWN)
        assertEquals(unknown, gate.observe(unknown, NOW_MS + 1L))
    }

    @Test
    fun futureStaleAndOutOfOrderFailureMeasurementsCannotRetainAPass() {
        listOf(NOW_MS + 2L, NOW_MS - 1L, NOW_MS - 10_000L).forEach { observedAtMs ->
            val gate = RuntimeMeasuredQualityGate()
            gate.observe(evidence(), NOW_MS)
            val failed = failure(observedAtMs)
            assertEquals(failed, gate.observe(failed, NOW_MS + 1L))
        }
    }

    @Test
    fun allMeasuredCameraQualityFactorsShareTheBoundedWindow() {
        listOf(
            CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE,
            CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE,
            CameraFrameQualityReason.SHAKE_OUTSIDE_APPROVED_RANGE,
            CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE,
        ).forEach { reason ->
            val gate = RuntimeMeasuredQualityGate()
            val pass = evidence()
            gate.observe(pass, NOW_MS)
            val failed = failure(NOW_MS + 1L).copy(detail = reason.name)
            assertEquals(pass, gate.observe(failed, NOW_MS + 1L))
        }
    }

    @Test
    fun watchdogExpiresTransientWindowWithoutAnotherSensorCallback() {
        val gate = RuntimeMeasuredQualityGate()
        val pass = evidence()
        gate.observe(pass, NOW_MS)
        val failed = failure(NOW_MS + 1L)
        assertEquals(pass, gate.observe(failed, NOW_MS + 1L))
        val deadline = NOW_MS + 1_501L
        assertEquals(deadline, gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(pass, gate.current(deadline - 1L))
        assertEquals(failed, gate.current(deadline))
        assertEquals(failed, gate.current(deadline + 1L))
        assertEquals(null, gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test
    fun watchdogDoesNotReturnAStalePassWhenTheSensorStopsSendingSamples() {
        val gate = RuntimeMeasuredQualityGate()
        val pass = evidence().copy(maximumEvidenceAgeMs = 100L)
        gate.observe(pass, NOW_MS)
        val expired = gate.current(NOW_MS + 101L)
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, expired?.status)
        assertEquals(pass.observedAtElapsedRealtimeMs, expired?.observedAtElapsedRealtimeMs)
        assertEquals("STALE_OR_INVALID_TIMESTAMP", expired?.detail)
    }

    @Test
    fun resetRemovesEvidenceFromThePreviousRuntime() {
        val gate = RuntimeMeasuredQualityGate()
        gate.observe(evidence(), NOW_MS)
        gate.reset()
        val failed = failure(NOW_MS + 1L)
        assertEquals(failed, gate.observe(failed, NOW_MS + 1L))
    }

    private fun failure(observedAtMs: Long) = evidence(observedAtMs).copy(
        status = EnvironmentEvidenceStatus.FAIL,
        detail = CameraFrameQualityReason.SHAKE_OUTSIDE_APPROVED_RANGE.name,
    )

    private fun evidence(observedAtMs: Long = NOW_MS) = MeasuredEnvironmentEvidence(
        factor = OfficialEnvironmentFactor.CAMERA_QUALITY,
        epoch = WalkRuntimeEpoch("walk", 1L),
        observedAtElapsedRealtimeMs = observedAtMs,
        status = EnvironmentEvidenceStatus.PASS,
        measurementProfileId = "camera-test",
        maximumEvidenceAgeMs = 5_000L,
        detail = "PASSED",
    )

    private companion object {
        const val NOW_MS = 10_000L
    }
}
