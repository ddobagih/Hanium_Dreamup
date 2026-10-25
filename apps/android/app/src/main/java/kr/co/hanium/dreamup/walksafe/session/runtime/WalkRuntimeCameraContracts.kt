package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal interface WalkRuntimeCameraAdmission {
    fun runtimeActive(): Boolean
    fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch): Boolean
    fun fallbackCleanupPending(): Boolean
    fun cameraEnabled(): Boolean
    fun cameraReleaseBlocked(): Boolean
    fun fallbackRequested(): Boolean
    fun fallbackRunning(): Boolean
    fun arSessionMissing(): Boolean
}
internal interface WalkRuntimeCameraEffects {
    fun cancelHandoff(epoch: WalkRuntimeEpoch? = null)
    fun releaseFallback(onReleased: () -> Unit)
    fun safetyStop(reason: String)
    fun completeResumeActivation(epoch: WalkRuntimeEpoch)
    fun syncScreenPolicy()
    fun bindFallback()
    fun clearPendingInstallStartIfSet()
    fun ensurePermissionsThenStart()
    fun startNavigation()
    fun startHandsFreeVoice()
    fun refreshReadinessUi()
    fun retainLocationCompassOrStop()
    fun resumeCameraLifecycleIfInitialized()
    fun resumeOrPauseSurfaceForCurrentArSession()
    fun startOrientationIfInitialized()
    fun pauseSurfaceIfInitialized()
}
