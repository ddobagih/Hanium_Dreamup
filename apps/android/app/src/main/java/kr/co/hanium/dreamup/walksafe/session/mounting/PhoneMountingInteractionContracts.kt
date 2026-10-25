package kr.co.hanium.dreamup.walksafe.session.mounting

import kr.co.hanium.dreamup.walksafe.session.*

internal interface PhoneMountingInteractionAdmission {
    fun developmentBypassEnabled(): Boolean
    fun isForeground(): Boolean
    fun lifecycleAvailable(): Boolean
    fun requireOnboarding(reason: String): Boolean
    fun cameraEnabled(): Boolean
    fun snapshot(): WalkSessionSnapshot
    fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch): Boolean
    fun officialReadiness(epoch: WalkRuntimeEpoch): Pair<WalkSessionReadinessStatus, String>
    fun locationFeatureEnabled(): Boolean
    fun quickStartEnabled(): Boolean
    fun profileAvailable(): Boolean
    fun onboardingComplete(): Boolean
    fun profileApprovedForProduction(): Boolean?
}
internal interface PhoneMountingInteractionPreflight {
    fun cameraActive(): Boolean
    fun cameraCleanupPending(): Boolean
    fun stopCamera(onReleased: () -> Unit)
    fun resetReadyGrace()
    fun resetGuidanceProgress()
    fun startCamera(epoch: WalkRuntimeEpoch): Boolean
    fun requestGps(epoch: WalkRuntimeEpoch, clearExistingEvidence: Boolean)
    fun markFailed()
}
internal interface PhoneMountingInteractionEffects {
    fun updateStatus(title: String, detail: String)
    fun speakInteraction(message: String)
    fun refreshReadinessUi()
}
internal interface PhoneMountingButtonView {
    var enabled: Boolean
    var text: CharSequence
    var contentDescription: CharSequence
}
internal interface PhoneMountingInteractionView {
    fun statusPublished(): Boolean
    fun chestPublished(): Boolean
    fun necklacePublished(): Boolean
    fun postFocusEnvironmentConfirmation()
    fun hideStatus()
    fun setChestVisible(visible: Boolean)
    fun hideNecklace()
    fun applyStatus(message: String)
    fun setStatusDescription(message: String)
    fun statusText(): String
    fun chestButton(): PhoneMountingButtonView
}
