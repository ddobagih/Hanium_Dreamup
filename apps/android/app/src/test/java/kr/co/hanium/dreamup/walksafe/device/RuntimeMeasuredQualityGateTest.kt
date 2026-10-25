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
    fun unknownFrameStateMissingSensorsAndUntrustedLocationRevokePassImmediately() {
        val reasons = listOf(
            "FRAME_STATE_UNKNOWN", "INVALID_MEASUREMENT",
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
    fun futureFailureRevokesPassButOlderFailuresCannotReplaceNewerPass() {
        run {
            val gate = RuntimeMeasuredQualityGate()
            gate.observe(evidence(), NOW_MS)
            val failed = failure(NOW_MS + 2L)
            assertEquals(failed, gate.observe(failed, NOW_MS + 1L))
        }
        listOf(NOW_MS - 1L, NOW_MS - 10_000L).forEach { observedAtMs ->
            val gate = RuntimeMeasuredQualityGate()
            val pass = evidence()
            gate.observe(pass, NOW_MS)
            assertEquals(pass, gate.observe(failure(observedAtMs), NOW_MS + 1L))
            assertEquals(pass, gate.current(NOW_MS + 1L))
        }
    }

    @Test
    fun cameraImageQualityFactorsShareTheBoundedWindow() {
        listOf(
            CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE,
            CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE,
            CameraFrameQualityReason.SHAKE_OUTSIDE_APPROVED_RANGE,
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

    @Test
    fun olderFreshPassCannotReplaceLatestFailure() {
        val gate = RuntimeMeasuredQualityGate()
        val failed = failure(NOW_MS + 100L)
        assertEquals(failed, gate.observe(failed, NOW_MS + 100L))

        assertEquals(failed, gate.observe(evidence(), NOW_MS + 101L))
        assertEquals(failed, gate.current(NOW_MS + 102L))
    }

    @Test
    fun sameTimestampPassCannotClearFailureInEitherArrivalOrder() {
        listOf(false, true).forEach { passArrivesFirst ->
            val gate = RuntimeMeasuredQualityGate()
            val pass = evidence()
            val failed = failure(NOW_MS).copy(detail = "FRAME_UNAVAILABLE")
            if (passArrivesFirst) gate.observe(pass, NOW_MS)
            assertEquals(failed, gate.observe(failed, NOW_MS))

            assertEquals(failed, gate.observe(pass, NOW_MS))
            assertEquals(failed, gate.current(NOW_MS + 1L))
        }
    }

    @Test
    fun delayedOrSameTimestampPassCannotRestartTransientFailureWindow() {
        val gate = RuntimeMeasuredQualityGate()
        val pass = evidence()
        val failed = failure(NOW_MS + 100L)
        gate.observe(pass, NOW_MS)
        assertEquals(pass, gate.observe(failed, NOW_MS + 100L))
        val deadline = NOW_MS + 1_600L

        assertEquals(pass, gate.observe(evidence(NOW_MS + 50L), NOW_MS + 200L))
        assertEquals(deadline, gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(pass, gate.observe(evidence(NOW_MS + 100L), NOW_MS + 300L))
        assertEquals(deadline, gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(failed, gate.current(deadline))
        assertEquals(failed, gate.observe(pass, deadline + 1L))
    }

    @Test
    fun olderFailureCannotLowerThePassRecoveryWatermark() {
        val gate = RuntimeMeasuredQualityGate()
        val pass = evidence()
        gate.observe(pass, NOW_MS)
        val olderFailure = failure(NOW_MS - 1L)
        assertEquals(pass, gate.observe(olderFailure, NOW_MS + 1L))

        assertEquals(pass, gate.observe(evidence(NOW_MS - 1L), NOW_MS + 2L))
        assertEquals(pass, gate.observe(pass, NOW_MS + 3L))
        val recovered = evidence(NOW_MS + 1L)
        assertEquals(recovered, gate.observe(recovered, NOW_MS + 4L))
    }

    @Test
    fun epochProfileAndFactorChangesResetOrderingEvenWithoutAPass() {
        val failed = failure(NOW_MS + 100L)
        listOf(
            evidence().copy(epoch = WalkRuntimeEpoch("walk", 2L)),
            evidence().copy(epoch = WalkRuntimeEpoch("another", 1L)),
            evidence().copy(measurementProfileId = "another"),
            evidence().copy(factor = OfficialEnvironmentFactor.GPS_QUALITY),
        ).forEach { newContext ->
            val gate = RuntimeMeasuredQualityGate()
            gate.observe(failed, NOW_MS + 100L)

            assertEquals(newContext, gate.observe(newContext, NOW_MS + 101L))
            assertEquals(newContext, gate.current(NOW_MS + 102L))
        }
    }

    @Test
    fun explicitResetClearsFailureAndSourceOrdering() {
        val gate = RuntimeMeasuredQualityGate()
        gate.observe(failure(NOW_MS + 100L), NOW_MS + 100L)
        gate.reset()
        assertEquals(null, gate.current(NOW_MS + 101L))

        val pass = evidence()
        assertEquals(pass, gate.observe(pass, NOW_MS + 101L))
    }

    @Test
    fun newerPassRecoversWithoutRenewingItsTimestampOnDuplicateDelivery() {
        val gate = RuntimeMeasuredQualityGate()
        gate.observe(failure(NOW_MS), NOW_MS)
        val recovered = evidence(NOW_MS + 1L).copy(maximumEvidenceAgeMs = 100L)

        assertEquals(recovered, gate.observe(recovered, NOW_MS + 1L))
        assertEquals(recovered, gate.observe(recovered, NOW_MS + 100L))
        val expired = gate.current(NOW_MS + 102L)
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, expired?.status)
        assertEquals(NOW_MS + 1L, expired?.observedAtElapsedRealtimeMs)
    }

    @Test
    fun invalidFuturePassCannotEraseLatestFailureOrder() {
        val gate = RuntimeMeasuredQualityGate()
        val failed = failure(NOW_MS + 100L).copy(detail = "FRAME_UNAVAILABLE")
        assertEquals(failed, gate.observe(failed, NOW_MS + 100L))
        val future = evidence(NOW_MS + 200L)
        val unavailable = gate.observe(future, NOW_MS + 150L)
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, unavailable.status)
        assertEquals("STALE_OR_INVALID_TIMESTAMP", unavailable.detail)
        assertEquals(failed, gate.observe(evidence(NOW_MS + 100L), NOW_MS + 160L))
        assertEquals(failed, gate.current(NOW_MS + 200L))
        // The invalid future input must not impose a future recovery watermark either.
        val recovered = evidence(NOW_MS + 101L)
        assertEquals(recovered, gate.observe(recovered, NOW_MS + 201L))
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
