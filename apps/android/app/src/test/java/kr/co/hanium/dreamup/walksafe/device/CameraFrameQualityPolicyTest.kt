package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFrameQualityPolicyTest {
    @Test
    fun productionHasNoUnapprovedCameraThresholdProfile() {
        assertNull(CameraFrameQualityPolicy.productionProfile)
    }

    @Test
    fun everyMeasurementMustPassTheInjectedApprovedProfile() {
        val assessment = assess(observation())

        assertEquals(EnvironmentEvidenceStatus.PASS, assessment.status)
        assertEquals(CameraFrameQualityReason.PASSED, assessment.reason)
        assertEquals(PROFILE.profileId, assessment.profileId)
        assertEquals(
            OfficialEnvironmentFactor.CAMERA_QUALITY,
            assessment.asMeasuredEnvironmentEvidence().factor,
        )
    }

    @Test
    fun eachAdverseMeasurementFails() {
        val adverse = listOf(
            observation().copy(
                normalizedBrightness = PROFILE.minimumNormalizedBrightness - 0.01,
            ) to CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE,
            observation().copy(
                occludedFraction = PROFILE.maximumOccludedFraction + 0.01,
            ) to CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE,
            observation().copy(
                angularShakeDegreesPerSecond =
                    PROFILE.maximumAngularShakeDegreesPerSecond + 0.01,
            ) to CameraFrameQualityReason.SHAKE_OUTSIDE_APPROVED_RANGE,
            observation().copy(
                mountPitchDegrees = PROFILE.maximumMountPitchDegrees + 0.01,
            ) to CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE,
        )

        adverse.forEach { (observation, expectedReason) ->
            val assessment = assess(observation)

            assertEquals(EnvironmentEvidenceStatus.FAIL, assessment.status)
            assertEquals(expectedReason, assessment.reason)
        }
    }

    @Test
    fun missingUnavailableStaleAndWrongEpochFramesFailClosed() {
        val missing = assess(null)
        val unavailable = assess(observation().copy(frameAvailable = false))
        val unknownAvailability = assess(observation().copy(frameAvailable = null))
        val stale = assess(
            observation().copy(observedAtElapsedRealtimeMs = NOW_MS - MAX_AGE_MS - 1L),
        )
        val future = assess(observation().copy(observedAtElapsedRealtimeMs = NOW_MS + 1L))
        val wrongEpoch = assess(observation().copy(epoch = OTHER_EPOCH))

        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, missing.status)
        assertEquals(EnvironmentEvidenceStatus.FAIL, unavailable.status)
        listOf(unknownAvailability, stale, future, wrongEpoch).forEach {
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN, it.status)
        }
    }

    @Test
    fun nanInfinityAndOutOfDomainMeasurementsAreUnknown() {
        val invalid = listOf(
            observation().copy(normalizedBrightness = Double.NaN),
            observation().copy(occludedFraction = Double.POSITIVE_INFINITY),
            observation().copy(angularShakeDegreesPerSecond = Double.NEGATIVE_INFINITY),
            observation().copy(mountPitchDegrees = Double.NaN),
            observation().copy(normalizedBrightness = -0.1),
            observation().copy(occludedFraction = 1.1),
            observation().copy(angularShakeDegreesPerSecond = -0.1),
        )

        invalid.forEach {
            val assessment = assess(it)

            assertEquals(EnvironmentEvidenceStatus.UNKNOWN, assessment.status)
            assertEquals(CameraFrameQualityReason.INVALID_MEASUREMENT, assessment.reason)
        }
    }

    @Test
    fun noApprovedProfileIsUnknownEvenWhenMeasurementsLookGood() {
        val assessment = CameraFrameQualityPolicy.assess(
            currentEpoch = EPOCH,
            nowElapsedRealtimeMs = NOW_MS,
            observation = observation(),
            approvedProfile = null,
        )

        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, assessment.status)
        assertEquals(CameraFrameQualityReason.PROFILE_NOT_APPROVED, assessment.reason)
        assertNull(assessment.profileId)
    }

    private fun assess(
        observation: CameraFrameQualityObservation?,
    ) = CameraFrameQualityPolicy.assess(
        currentEpoch = EPOCH,
        nowElapsedRealtimeMs = NOW_MS,
        observation = observation,
        approvedProfile = PROFILE,
    )

    private fun observation() = CameraFrameQualityObservation(
        epoch = EPOCH,
        observedAtElapsedRealtimeMs = NOW_MS,
        frameAvailable = true,
        normalizedBrightness = PROFILE.minimumNormalizedBrightness,
        occludedFraction = PROFILE.maximumOccludedFraction,
        angularShakeDegreesPerSecond = PROFILE.maximumAngularShakeDegreesPerSecond,
        mountPitchDegrees = PROFILE.minimumMountPitchDegrees,
    )

    private companion object {
        const val NOW_MS = 10_000L
        const val MAX_AGE_MS = 2_000L
        val EPOCH = WalkRuntimeEpoch("walk-1", 1L)
        val OTHER_EPOCH = WalkRuntimeEpoch("walk-2", 1L)
        val PROFILE = ApprovedCameraFrameQualityProfile(
            profileId = "camera-field-approved-r001",
            minimumNormalizedBrightness = 0.4,
            maximumOccludedFraction = 0.2,
            maximumAngularShakeDegreesPerSecond = 8.0,
            minimumMountPitchDegrees = 25.0,
            maximumMountPitchDegrees = 65.0,
            maximumEvidenceAgeMs = MAX_AGE_MS,
        )
    }
}
