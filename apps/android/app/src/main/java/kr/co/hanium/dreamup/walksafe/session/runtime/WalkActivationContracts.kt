package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyDecision
import kr.co.hanium.dreamup.walksafe.session.*

internal interface WalkActivationAdmission {
    fun runtimeActive(): Boolean
    fun runtimeEpochOrNull(): WalkRuntimeEpoch?
    fun snapshot(): WalkSessionSnapshot
    fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch): Boolean
    fun rejectUnsafeDetector(epoch: WalkRuntimeEpoch, state: WalkSessionState, onUnsafe: (WalkRuntimeEpoch) -> Unit): Boolean
    fun beginSafetyEpoch(epoch: WalkRuntimeEpoch): WalkRuntimeSafetyDecision
    fun stopCleanupExhausted(): Boolean
    fun developmentBypassEnabled(): Boolean
    fun isForeground(): Boolean
    fun navigationOutputsAllowed(): Boolean
    fun permissionBlocksAutomaticStart(): Boolean
    fun nowMs(): Long
}
internal interface WalkActivationEffects {
    fun authorityLost(reason: String)
    fun safetyStop(reason: String)
    fun persistInterruptionMarker(): Boolean
    fun foregroundRecheck(reason: String)
    fun updateStatus(title: String, detail: String)
    fun showRestartNotice()
}
internal interface WalkActivationServices {
    fun ensureResourceMonitoring()
    fun resourceMonitoringBlocksReadiness(): Boolean
    fun observeResourceSafety(epoch: WalkRuntimeEpoch): Boolean
    fun activateOfficialEnvironment(): Boolean
    fun activatePhoneMounting(): Boolean
    fun startNavigation()
    fun startHandsFreeVoice()
    fun recordGuidancePreparationStage(stage: String)
    fun refreshReadinessUi()
}
internal interface WalkActivationCamera {
    fun analysisFeaturesEnabled(): Boolean
    fun beginHandoff(epoch: WalkRuntimeEpoch)
    fun cancelHandoff()
    fun stopEnvironmentPreflight(releaseMountingSensor: Boolean, onReleased: () -> Unit)
    fun postAfterRelease(action: () -> Unit)
    fun startRuntimeAfterRelease(epoch: WalkRuntimeEpoch)
}
