package kr.co.hanium.dreamup.walksafe.runtime.metric

import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckBinding

/** Read at the original invalidation/refresh boundary; startup state remains with its owner. */
internal interface RuntimeMetricInvalidationStartup {
    fun currentBinding(): PostLoginDeviceCheckBinding?
    fun clearConfirmation()
    fun invalidateAvailabilityRecheck()
    fun refreshPublishedForegroundUi()
}

internal interface RuntimeMetricInvalidationFallback {
    fun hasActivity(): Boolean
    fun clearContinuationIfInitialized()
    fun clearPendingStart()
    fun stop()
    fun moveToCreatedIfInitialized()
}

/** Each source stop remains an independent failure stage, retaining its own source/epoch owner. */
internal interface RuntimeMetricInvalidationNavigation {
    fun safetyStopAndPersist()
    fun cancelAndClearRequests()
    fun resetRoute()
    fun invalidateFrame()
    fun stopLocation()
    fun stopSteps()
    fun stopOrientationIfInitialized()
}

internal interface RuntimeMetricInvalidationVoice {
    fun advanceFeedbackGeneration()
    fun cancelRecognition()
    fun removeTalkBackIfPublished(task: Runnable?)
    fun cancelTerminalResolution()
}

internal interface RuntimeMetricInvalidationReports {
    fun cancelPrivacyCalls()
    fun clearExplicitFrame(reason: String)
    fun recordInvalidationIfInitialized(reason: String, wasPreflight: Boolean)
}

/** SDK/View effects only; the AR slot and uncertain close receipt remain in RuntimeArSessionOwner. */
internal interface RuntimeMetricInvalidationRenderer {
    fun pauseForSessionClose(): Boolean
    fun resumeAfterSessionClose(resume: Boolean)
    fun clearNativePresentation()
    fun pauseForSafetyStopIfInitialized()
}
