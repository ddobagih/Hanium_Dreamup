package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence

internal data class TactileSnapshotFrameResult(
    val matchedEvidence: DetectionFrameEvidence?,
    val depthDetections: List<DetectionCandidate>,
    val depthProcessingAttempted: Boolean,
    val guidance: TactileRouteGuidanceResult,
)

internal interface PreparedTactileFrameDispatch {
    val frame: TactileSnapshotFrameResult

    fun dispatchFeedback(
        stale: Boolean,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
    ): TactileFrameFeedbackDispatch
}
