package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.*

/** Access to the same Activity/process-retained machine. No cached snapshot is introduced. */
internal interface WalkSessionRuntimeAccess {
    fun available(): Boolean
    fun snapshot(): WalkSessionSnapshot
    fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch): Boolean
    fun transition(event: WalkSessionEvent): WalkSessionTransition
}
internal interface WalkResumeReadiness {
    fun decision(): WalkSafeStartupCapabilityDecision?
    fun blockReason(decision: WalkSafeStartupCapabilityDecision): String?
    fun capture(decision: WalkSafeStartupCapabilityDecision, action: WalkSessionAction,
        recordResourceDiagnostics: Boolean = false): WalkSessionReadinessSnapshot
    fun isConfirmed(): Boolean
}
internal interface WalkResumeEnvironment {
    fun isForeground(): Boolean
    fun runtimeActive(): Boolean
    fun navigationOutputsAllowed(): Boolean
    fun cameraHandoffPending(epoch: WalkRuntimeEpoch): Boolean
    fun voiceConfirmationAvailable(): Boolean
    fun feedbackGeneration(): Int
    fun isFeedbackCurrent(generation: Int): Boolean
    fun activeLeaseOrNull(epoch: WalkRuntimeEpoch): GatewayWalkLease?
    fun permissionAllowsAutomaticStart(): Boolean
    fun gatewayRevalidationPending(): Boolean
}
internal interface WalkResumeEffects {
    fun runOnUiThread(action: () -> Unit)
    fun updateStatus(title: String, detail: String)
    fun enableResumeConfirmationButton()
    fun speakResume(message: String, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean
    fun startResumeRecognition(): Boolean
    fun acceptsControlConfidence(confidence: Float): Boolean
    fun activateRuntime(): Boolean
    fun persistInterruptionMarker(): Boolean
    fun foregroundRecheck(reason: String)
    fun authorityLost(reason: String)
    fun publishResumeCompletion()
    fun requestNavigation(point: RoutePoint, label: String, confirmed: Boolean, evidence: DestinationCoordinateEvidence?)
    fun completeAdmittedResume(epoch: WalkRuntimeEpoch, publishCompletion: () -> Unit,
        requestNavigation: (RoutePoint, String, Boolean, DestinationCoordinateEvidence?) -> Unit)
}
