package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.session.*

internal interface WalkTransitionEffects {
    fun beforePublication(previous: WalkSessionSnapshot, next: WalkSessionSnapshot)
    fun refreshCalibrationDiagnostics()
    fun recordSafetyStopTransition(transition: WalkSessionTransition)
    fun hidePermissionDenial()
    fun onEpochChanged()
    fun updateRouteSnapshotWalkOwnership(transition: WalkSessionTransition)
    fun invalidateGuidanceStopConfirmation()
    fun cancelDeviceRuntimeCalibration(reason: String)
    fun clearResumeActivation()
    fun stopHandsFreeVoiceService()
    fun invalidatePendingExplicitReport()
    fun refreshActiveSpeechStorageBinding()
}
/** Existing report/raw owners retain all queue and collection state. */
internal interface WalkTransitionReports {
    fun updateQueueDrain(transition: WalkSessionTransition)
    fun onWalkStateChanged(state: WalkSessionState)
    fun handleRawCollectionTransition(transition: WalkSessionTransition, event: WalkSessionEvent)
    fun maybeStartPostWalkQueueDrain()
}
internal interface WalkTransitionDiagnostics {
    fun available(): Boolean
    fun recordEvent(name: String, fields: Map<String, Any?>)
}
