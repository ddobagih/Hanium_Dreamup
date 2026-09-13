package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile

enum class WalkSafeEnvironmentProfileStatus {
    TEST_CANDIDATE,
    APPROVED,
}

data class WalkSafeEnvironmentProfileBundle(
    val bundleId: String,
    val revision: String,
    val status: WalkSafeEnvironmentProfileStatus,
    val cameraFrameQuality: ApprovedCameraFrameQualityProfile,
    val officialEnvironment: ApprovedOfficialEnvironmentProfile,
    val phoneMounting: ApprovedPhoneMountingProfile,
    val runtimeMetric: RuntimeMetricPreflightProfile,
) {
    init {
        require(bundleId.isNotBlank()) { "bundleId must not be blank" }
        require(revision.isNotBlank()) { "revision must not be blank" }
        require(
            officialEnvironment.cameraQualityProfileId == cameraFrameQuality.profileId,
        ) {
            "official environment profile must reference the bundled camera profile"
        }
        require(
            phoneMounting.cameraFrameQualityProfileId == cameraFrameQuality.profileId,
        ) {
            "phone mounting profile must reference the bundled camera profile"
        }
    }

    val approvedForProduction: Boolean
        get() = status == WalkSafeEnvironmentProfileStatus.APPROVED
}

object WalkSafeEnvironmentProfiles {
    /*
     * These values are a real-device test starting point, not field validation or release
     * approval. Missing measurements still fail closed. Promotion must reuse this same bundle;
     * approval status must never select a weaker policy or a different judgement path.
     */
    val testCandidate = WalkSafeEnvironmentProfileBundle(
        bundleId = "walksafe-environment-test-candidate-r001",
        revision = "20260831-r001",
        status = WalkSafeEnvironmentProfileStatus.TEST_CANDIDATE,
        cameraFrameQuality = ApprovedCameraFrameQualityProfile(
            profileId = "camera-test-candidate-r001",
            minimumNormalizedBrightness = 0.20,
            maximumOccludedFraction = 0.10,
            maximumAngularShakeDegreesPerSecond = 45.0,
            minimumMountPitchDegrees = -45.0,
            maximumMountPitchDegrees = 45.0,
            maximumEvidenceAgeMs = 5_000L,
        ),
        officialEnvironment = ApprovedOfficialEnvironmentProfile(
            profileId = "official-environment-test-candidate-r001",
            cameraQualityProfileId = "camera-test-candidate-r001",
            maximumGpsHorizontalAccuracyMeters = 15.0,
            maximumMeasuredEvidenceAgeMs = 10_000L,
            maximumRuntimeRetryAttempts = 2,
            runtimeRetryIntervalMs = 3_000L,
        ),
        phoneMounting = ApprovedPhoneMountingProfile(
            profileId = "phone-mounting-test-candidate-r001",
            cameraFrameQualityProfileId = "camera-test-candidate-r001",
            maximumEvidenceAgeMs = 5_000L,
            maximumRuntimeRetryAttempts = 2,
            maximumUserConfirmationAgeMs = 30L * 60L * 1_000L,
        ),
        runtimeMetric = RuntimeMetricPreflightPolicy.testCandidateProfile,
    )

    val productionApproved: WalkSafeEnvironmentProfileBundle? = null

    fun active(allowTestCandidate: Boolean): WalkSafeEnvironmentProfileBundle? =
        productionApproved ?: testCandidate.takeIf { allowTestCandidate }
}
