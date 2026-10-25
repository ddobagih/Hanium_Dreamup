package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeStopCleanupCheckpoints
import kr.co.hanium.dreamup.walksafe.network.AndroidNavigationCancellation

internal interface WalkCleanupAdmission {
    fun developmentBypassEnabled(): Boolean
    fun persistInterruptionMarker(): Boolean
    fun invalidateOfficialEnvironment(reason: String)
    fun invalidatePhoneMounting(reason: String)
    fun stopCleanupBlocksNewWalk(): Boolean
    fun stopCleanupExhausted(): Boolean
    fun showRestartNotice()
    fun resetTransientStateForNewWalk()
    fun updateStatus(title: String, detail: String)
    fun updateNavigationStatus(detail: String)
    fun refreshReadinessIfInitialized()
    fun clearStartupState()
    fun debug(tag: String, message: String)
}
internal interface WalkCleanupNative {
    fun stopCameraFallbackSession(updateUi: Boolean, onReleased: (() -> Unit)? = null, onReleaseFailed: (() -> Unit)? = null)
    fun stopDepthSession(closeSession: Boolean): Boolean
    fun lastDepthCloseFailure(): Throwable?
    fun invalidateArRecheck()
    fun stopVoiceService(): Boolean
    fun cancelRecognition()
    fun stopMountingProbe()
    fun moveCameraToCreated()
    fun pauseSurface()
    fun syncScreenPolicy()
}
internal interface WalkCleanupNavigation {
    fun cancelRequestsForPause(): AndroidNavigationCancellation
    fun clearDestinationSearchState(cancelled: Boolean)
    fun cancelWalkSessionRouteState(reason: String, checkpoints: WalkRuntimeStopCleanupCheckpoints?, cancellation: AndroidNavigationCancellation)
    fun invalidateFrameState()
    fun stopPositionFieldSession(): Boolean
    fun locationCollectionAllowsWork(): Boolean
    fun stopLocationUpdates()
    fun locationCleanupSucceeded(): Boolean
    fun runLocationStopCleanupCheckpoint(checkpoints: WalkRuntimeStopCleanupCheckpoints, onResult: (Boolean) -> Unit, stopTail: () -> Boolean): Boolean
    fun stopStepTracking()
    fun retainLocationCompassOrStop()
    fun startLocationUpdatesIfAllowed()
}
internal interface WalkCleanupFeedback {
    fun advanceGenerationAndResetReferences()
    fun cancelGuidancePreparation()
    fun cancelPriorityTraining()
    fun cancelPendingDeliveries()
    fun cancelTalkBack()
    fun cancelTerminal()
    fun resetDeliveryState()
    fun closeActuator()
}
internal interface WalkCleanupReports {
    fun invalidatePendingExplicitReport()
    fun cancelPrivacyUploads()
    fun cancelOriginalUploads()
    fun recordOutputsCancelled(reason: String)
}
