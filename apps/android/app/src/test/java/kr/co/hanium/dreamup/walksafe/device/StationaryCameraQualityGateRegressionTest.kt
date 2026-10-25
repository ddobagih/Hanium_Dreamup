package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationaryCameraQualityGateRegressionTest {
    @Test
    fun isolatedUnavailableFrameKeepsTheOriginalFreshPassUntilTheCameraGraceDeadline() {
        val gate = RuntimeMeasuredQualityGate(maximumTransientFailureMs = CAMERA_GRACE_MS)
        val pass = evidence(NOW_MS)
        gate.observe(pass, NOW_MS)
        val unavailable = evidence(NOW_MS + 100L, CameraFrameQualityReason.FRAME_UNAVAILABLE)

        assertEquals(pass, gate.observe(unavailable, NOW_MS + 100L))
        assertEquals(unavailable, gate.latestRawObservation())
        assertEquals(NOW_MS + 100L + CAMERA_GRACE_MS, gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(pass, gate.current(NOW_MS + 100L + CAMERA_GRACE_MS - 1L))
        assertEquals(unavailable, gate.current(NOW_MS + 100L + CAMERA_GRACE_MS))
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test
    fun aNewActualFrameRecoversWithoutRelabelingTheRetainedPassOrFailure() {
        val gate = RuntimeMeasuredQualityGate(maximumTransientFailureMs = CAMERA_GRACE_MS)
        val pass = evidence(NOW_MS)
        gate.observe(pass, NOW_MS)
        val unavailable = evidence(NOW_MS + 100L, CameraFrameQualityReason.FRAME_UNAVAILABLE)
        assertEquals(pass, gate.observe(unavailable, NOW_MS + 100L))

        val recovered = evidence(NOW_MS + 200L)
        assertEquals(recovered, gate.observe(recovered, NOW_MS + 200L))
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(recovered, gate.current(NOW_MS + 300L))
        assertEquals(NOW_MS + 200L, gate.current(NOW_MS + 300L)?.observedAtElapsedRealtimeMs)
    }

    @Test
    fun graceCannotExtendTheOriginalPassFreshnessOrBorrowAnAbsentPass() {
        val unavailable = evidence(NOW_MS + 100L, CameraFrameQualityReason.FRAME_UNAVAILABLE)
        val cold = RuntimeMeasuredQualityGate(maximumTransientFailureMs = CAMERA_GRACE_MS)
        assertEquals(unavailable, cold.observe(unavailable, NOW_MS + 100L))
        assertNull(cold.pendingFailureDeadlineElapsedRealtimeMs())

        val gate = RuntimeMeasuredQualityGate(maximumTransientFailureMs = CAMERA_GRACE_MS)
        val shortPass = evidence(NOW_MS).copy(maximumEvidenceAgeMs = 100L)
        gate.observe(shortPass, NOW_MS)
        assertEquals(shortPass, gate.observe(unavailable, NOW_MS + 100L))
        assertEquals(unavailable, gate.current(NOW_MS + 101L))
    }

    @Test
    fun unmeasuredInvalidTimestampAndWrongContextCannotReceiveFrameGrace() {
        val hardReasons = listOf(
            CameraFrameQualityReason.FRAME_STATE_UNKNOWN,
            CameraFrameQualityReason.INVALID_MEASUREMENT,
            CameraFrameQualityReason.STALE_OR_INVALID_TIMESTAMP,
            CameraFrameQualityReason.EPOCH_MISMATCH,
            CameraFrameQualityReason.PROFILE_NOT_APPROVED,
        )
        hardReasons.forEach { reason ->
            val gate = RuntimeMeasuredQualityGate(maximumTransientFailureMs = CAMERA_GRACE_MS)
            gate.observe(evidence(NOW_MS), NOW_MS)
            val rejected = evidence(NOW_MS + 100L, reason)
            assertEquals(reason.name, rejected.detail)
            assertEquals(rejected, gate.observe(rejected, NOW_MS + 100L))
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        listOf(
            evidence(NOW_MS + 100L, CameraFrameQualityReason.FRAME_UNAVAILABLE)
                .copy(epoch = WalkRuntimeEpoch("other-walk", 1L)),
            evidence(NOW_MS + 100L, CameraFrameQualityReason.FRAME_UNAVAILABLE)
                .copy(measurementProfileId = "other-camera"),
            evidence(NOW_MS + 101L, CameraFrameQualityReason.FRAME_UNAVAILABLE),
        ).forEach { rejected ->
            val gate = RuntimeMeasuredQualityGate(maximumTransientFailureMs = CAMERA_GRACE_MS)
            gate.observe(evidence(NOW_MS), NOW_MS)
            assertEquals(rejected, gate.observe(rejected, NOW_MS + 100L))
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
    }

    @Test
    fun repeatedFramesAndWatchdogsCannotMoveTheOriginalFailureDeadline() {
        val gate = RuntimeMeasuredQualityGate(maximumTransientFailureMs = CAMERA_GRACE_MS)
        val pass = evidence(NOW_MS)
        gate.observe(pass, NOW_MS)
        val firstBadAt = NOW_MS + 100L
        gate.observe(evidence(firstBadAt, CameraFrameQualityReason.FRAME_UNAVAILABLE), firstBadAt)
        val deadline = firstBadAt + CAMERA_GRACE_MS
        repeat(40) { index ->
            val now = firstBadAt + index * 100L
            val reason = if (index % 2 == 0) CameraFrameQualityReason.FRAME_UNAVAILABLE
                else CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE
            assertEquals(pass, gate.observe(evidence(now, reason), now))
            assertEquals(deadline, gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        assertEquals(EnvironmentEvidenceStatus.FAIL, gate.current(deadline)?.status)
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test
    fun realMountAngleFailureImmediatelyEndsAnExistingCameraQualityGrace() {
        val gate = RuntimeMeasuredQualityGate(maximumTransientFailureMs = CAMERA_GRACE_MS)
        val pass = evidence(NOW_MS)
        gate.observe(pass, NOW_MS)
        assertEquals(pass, gate.observe(evidence(NOW_MS + 100L, CameraFrameQualityReason.FRAME_UNAVAILABLE), NOW_MS + 100L))
        val physicalFault = evidence(NOW_MS + 200L, CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE)
        assertEquals(EnvironmentEvidenceStatus.FAIL, physicalFault.status)
        assertEquals(physicalFault, gate.observe(physicalFault, NOW_MS + 200L))
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    private fun evidence(time: Long, reason: CameraFrameQualityReason = CameraFrameQualityReason.PASSED) =
        MeasuredEnvironmentEvidence(
            factor = OfficialEnvironmentFactor.CAMERA_QUALITY,
            epoch = WalkRuntimeEpoch("stationary-walk", 1L),
            observedAtElapsedRealtimeMs = time,
            status = when (reason) {
                CameraFrameQualityReason.PASSED -> EnvironmentEvidenceStatus.PASS
                CameraFrameQualityReason.FRAME_UNAVAILABLE,
                CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE,
                CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE -> EnvironmentEvidenceStatus.FAIL
                else -> EnvironmentEvidenceStatus.UNKNOWN
            },
            measurementProfileId = "camera-field-approved-r001",
            maximumEvidenceAgeMs = 5_000L,
            detail = reason.name,
        )

    private companion object {
        const val NOW_MS = 10_000L
        const val CAMERA_GRACE_MS = 4_500L
    }
}
