package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal interface WalkAdmissionState {
    fun permissionRecoveryBlocksStart(): Boolean
    fun developmentBypassEnabled(): Boolean
    fun isForeground(): Boolean
    fun startupConfirmationPending(): Boolean
    fun startupRetryRequiresUserAction(): Boolean
    fun consumeDevelopmentResumeRequest(epoch: WalkRuntimeEpoch): Boolean
}
internal interface WalkAdmissionEffects {
    fun completePermissionRecoveryRecheck()
    fun persistInterruptionMarker(): Boolean
    fun maybeRequestMissingPermissions(decision: WalkSafeStartupCapabilityDecision)
    fun updateStatus(title: String, detail: String)
    fun refreshReadinessUi()
}
