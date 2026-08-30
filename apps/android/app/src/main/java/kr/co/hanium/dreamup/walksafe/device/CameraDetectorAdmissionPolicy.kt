package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

data class CameraDetectorAdmission(
    val detectorInvocationAllowed: Boolean,
    val frameQuality: CameraFrameQualityAssessment,
)

object CameraDetectorAdmissionPolicy {
    fun admit(
        currentEpoch: WalkRuntimeEpoch,
        nowElapsedRealtimeMs: Long,
        observation: CameraFrameQualityObservation?,
        approvedProfile: ApprovedCameraFrameQualityProfile? =
            CameraFrameQualityPolicy.productionProfile,
        detector: () -> Unit,
    ): CameraDetectorAdmission {
        val frameQuality = CameraFrameQualityPolicy.assess(
            currentEpoch = currentEpoch,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            observation = observation,
            approvedProfile = approvedProfile,
        )
        val detectorInvocationAllowed =
            frameQuality.status == EnvironmentEvidenceStatus.PASS
        if (detectorInvocationAllowed) {
            detector()
        }
        return CameraDetectorAdmission(
            detectorInvocationAllowed = detectorInvocationAllowed,
            frameQuality = frameQuality,
        )
    }
}
