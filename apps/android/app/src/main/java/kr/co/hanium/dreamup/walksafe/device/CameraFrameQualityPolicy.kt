package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

data class ApprovedCameraFrameQualityProfile(
    val profileId: String,
    val minimumNormalizedBrightness: Double,
    val maximumOccludedFraction: Double,
    val maximumAngularShakeDegreesPerSecond: Double,
    val minimumMountPitchDegrees: Double,
    val maximumMountPitchDegrees: Double,
    val maximumEvidenceAgeMs: Long,
) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(minimumNormalizedBrightness.isFinite()) {
            "minimumNormalizedBrightness must be finite"
        }
        require(minimumNormalizedBrightness in 0.0..1.0) {
            "minimumNormalizedBrightness must be normalized"
        }
        require(maximumOccludedFraction.isFinite()) {
            "maximumOccludedFraction must be finite"
        }
        require(maximumOccludedFraction in 0.0..1.0) {
            "maximumOccludedFraction must be normalized"
        }
        require(
            maximumAngularShakeDegreesPerSecond.isFinite() &&
                maximumAngularShakeDegreesPerSecond >= 0.0,
        ) {
            "maximumAngularShakeDegreesPerSecond must be finite and non-negative"
        }
        require(
            minimumMountPitchDegrees.isFinite() &&
                maximumMountPitchDegrees.isFinite() &&
                minimumMountPitchDegrees <= maximumMountPitchDegrees,
        ) {
            "mount pitch range must be finite and ordered"
        }
        require(maximumEvidenceAgeMs >= 0L) {
            "maximumEvidenceAgeMs must not be negative"
        }
    }
}

data class CameraFrameQualityObservation(
    val epoch: WalkRuntimeEpoch,
    val observedAtElapsedRealtimeMs: Long,
    val frameAvailable: Boolean?,
    val normalizedBrightness: Double?,
    val occludedFraction: Double?,
    val angularShakeDegreesPerSecond: Double?,
    val mountPitchDegrees: Double?,
)

enum class CameraFrameQualityReason {
    PASSED,
    PROFILE_NOT_APPROVED,
    MISSING_OBSERVATION,
    EPOCH_MISMATCH,
    STALE_OR_INVALID_TIMESTAMP,
    FRAME_STATE_UNKNOWN,
    FRAME_UNAVAILABLE,
    INVALID_MEASUREMENT,
    BRIGHTNESS_OUTSIDE_APPROVED_RANGE,
    OCCLUSION_OUTSIDE_APPROVED_RANGE,
    SHAKE_OUTSIDE_APPROVED_RANGE,
    MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE,
}

data class CameraFrameQualityAssessment(
    val epoch: WalkRuntimeEpoch?,
    val observedAtElapsedRealtimeMs: Long?,
    val profileId: String?,
    val maximumEvidenceAgeMs: Long?,
    val status: EnvironmentEvidenceStatus,
    val reason: CameraFrameQualityReason,
) {
    fun asMeasuredEnvironmentEvidence() = MeasuredEnvironmentEvidence(
        factor = OfficialEnvironmentFactor.CAMERA_QUALITY,
        epoch = epoch,
        observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
        status = status,
        measurementProfileId = profileId,
        maximumEvidenceAgeMs = maximumEvidenceAgeMs,
        detail = reason.name,
    )
}

object CameraFrameQualityPolicy {
    val productionProfile: ApprovedCameraFrameQualityProfile? = null

    fun assess(
        currentEpoch: WalkRuntimeEpoch,
        nowElapsedRealtimeMs: Long,
        observation: CameraFrameQualityObservation?,
        approvedProfile: ApprovedCameraFrameQualityProfile?,
    ): CameraFrameQualityAssessment {
        fun result(
            status: EnvironmentEvidenceStatus,
            reason: CameraFrameQualityReason,
        ) = CameraFrameQualityAssessment(
            epoch = observation?.epoch,
            observedAtElapsedRealtimeMs = observation?.observedAtElapsedRealtimeMs,
            profileId = approvedProfile?.profileId,
            maximumEvidenceAgeMs = approvedProfile?.maximumEvidenceAgeMs,
            status = status,
            reason = reason,
        )

        val profile = approvedProfile ?: return result(
            EnvironmentEvidenceStatus.UNKNOWN,
            CameraFrameQualityReason.PROFILE_NOT_APPROVED,
        )
        val measured = observation ?: return result(
            EnvironmentEvidenceStatus.UNKNOWN,
            CameraFrameQualityReason.MISSING_OBSERVATION,
        )
        if (measured.epoch != currentEpoch) {
            return result(
                EnvironmentEvidenceStatus.UNKNOWN,
                CameraFrameQualityReason.EPOCH_MISMATCH,
            )
        }
        if (!isFresh(
                measured.observedAtElapsedRealtimeMs,
                nowElapsedRealtimeMs,
                profile.maximumEvidenceAgeMs,
            )
        ) {
            return result(
                EnvironmentEvidenceStatus.UNKNOWN,
                CameraFrameQualityReason.STALE_OR_INVALID_TIMESTAMP,
            )
        }
        when (measured.frameAvailable) {
            null -> return result(
                EnvironmentEvidenceStatus.UNKNOWN,
                CameraFrameQualityReason.FRAME_STATE_UNKNOWN,
            )

            false -> return result(
                EnvironmentEvidenceStatus.FAIL,
                CameraFrameQualityReason.FRAME_UNAVAILABLE,
            )

            true -> Unit
        }

        val brightness = measured.normalizedBrightness
        val occlusion = measured.occludedFraction
        val shake = measured.angularShakeDegreesPerSecond
        val mountPitch = measured.mountPitchDegrees
        if (
            brightness == null ||
            !brightness.isFinite() ||
            brightness !in 0.0..1.0 ||
            occlusion == null ||
            !occlusion.isFinite() ||
            occlusion !in 0.0..1.0 ||
            shake == null ||
            !shake.isFinite() ||
            shake < 0.0 ||
            mountPitch == null ||
            !mountPitch.isFinite()
        ) {
            return result(
                EnvironmentEvidenceStatus.UNKNOWN,
                CameraFrameQualityReason.INVALID_MEASUREMENT,
            )
        }
        if (brightness < profile.minimumNormalizedBrightness) {
            return result(
                EnvironmentEvidenceStatus.FAIL,
                CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE,
            )
        }
        if (occlusion > profile.maximumOccludedFraction) {
            return result(
                EnvironmentEvidenceStatus.FAIL,
                CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE,
            )
        }
        if (shake > profile.maximumAngularShakeDegreesPerSecond) {
            return result(
                EnvironmentEvidenceStatus.FAIL,
                CameraFrameQualityReason.SHAKE_OUTSIDE_APPROVED_RANGE,
            )
        }
        if (mountPitch !in profile.minimumMountPitchDegrees..profile.maximumMountPitchDegrees) {
            return result(
                EnvironmentEvidenceStatus.FAIL,
                CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE,
            )
        }
        return result(
            EnvironmentEvidenceStatus.PASS,
            CameraFrameQualityReason.PASSED,
        )
    }

    private fun isFresh(
        observedAtElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
        maximumAgeMs: Long,
    ): Boolean {
        if (observedAtElapsedRealtimeMs < 0L || nowElapsedRealtimeMs < 0L) return false
        val ageMs = nowElapsedRealtimeMs - observedAtElapsedRealtimeMs
        return ageMs in 0L..maximumAgeMs
    }
}
