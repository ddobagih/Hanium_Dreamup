package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDetectorAdmissionPolicyTest {
    @Test
    fun detectorRunsOnlyWhenEveryCameraFrameQualityMeasurementPasses() {
        var detectorInvocations = 0

        val admission = admit(observation()) {
            detectorInvocations += 1
        }

        assertTrue(admission.detectorInvocationAllowed)
        assertEquals(EnvironmentEvidenceStatus.PASS, admission.frameQuality.status)
        assertEquals(1, detectorInvocations)
    }

    @Test
    fun eachAdverseMeasurementPreventsDetectorInvocation() {
        val adverseObservations = listOf(
            observation().copy(
                normalizedBrightness = PROFILE.minimumNormalizedBrightness - 0.01,
            ),
            observation().copy(
                occludedFraction = PROFILE.maximumOccludedFraction + 0.01,
            ),
            observation().copy(
                angularShakeDegreesPerSecond =
                    PROFILE.maximumAngularShakeDegreesPerSecond + 0.01,
            ),
            observation().copy(
                mountPitchDegrees = PROFILE.maximumMountPitchDegrees + 0.01,
            ),
            observation().copy(
                observedAtElapsedRealtimeMs = NOW_MS - MAX_AGE_MS - 1L,
            ),
        )
        var detectorInvocations = 0

        adverseObservations.forEach { adverse ->
            val admission = admit(adverse) {
                detectorInvocations += 1
            }

            assertFalse(admission.detectorInvocationAllowed)
        }
        assertEquals(0, detectorInvocations)
    }

    @Test
    fun productionAdmissionDefaultsToDenyWithoutAnApprovedProfile() {
        var detectorInvocations = 0

        val admission = CameraDetectorAdmissionPolicy.admit(
            currentEpoch = EPOCH,
            nowElapsedRealtimeMs = NOW_MS,
            observation = observation(),
        ) {
            detectorInvocations += 1
        }

        assertFalse(admission.detectorInvocationAllowed)
        assertEquals(
            CameraFrameQualityReason.PROFILE_NOT_APPROVED,
            admission.frameQuality.reason,
        )
        assertEquals(0, detectorInvocations)
    }

    private fun admit(
        observation: CameraFrameQualityObservation?,
        detector: () -> Unit,
    ) = CameraDetectorAdmissionPolicy.admit(
        currentEpoch = EPOCH,
        nowElapsedRealtimeMs = NOW_MS,
        observation = observation,
        approvedProfile = PROFILE,
        detector = detector,
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
