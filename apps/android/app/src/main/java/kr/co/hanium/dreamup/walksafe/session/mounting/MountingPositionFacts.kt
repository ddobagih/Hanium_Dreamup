package kr.co.hanium.dreamup.walksafe.session.mounting

import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingUserConfirmation

/** Original references and generation copied only at an explicitly requested capture point. */
internal data class MountingPositionFacts(
    val observationGeneration: Long,
    val confirmation: PhoneMountingUserConfirmation?,
    val explicitConfirmation: PhoneMountingUserConfirmation?,
    val cameraAssessment: CameraFrameQualityAssessment?,
)
