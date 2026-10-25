package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal data class PrimaryLifecycleMetadata(
    val configLoaded: Boolean, val reportModelKey: String?, val loadedModelKey: String?,
    val fallbackUsed: Boolean, val loadReason: String?, val loadAttempted: Boolean,
    val loadInFlight: Boolean, val statusText: String, val fixedFixtureRunning: Boolean,
)
internal data class PrimaryFixedFixtureResult(val fixtureCount: Int, val samples: Int,
    val medianTotalMs: Long, val medianInferenceMs: Long, val activeDelegate: String,
    val totalDetectedObjects: Int)
internal interface PrimaryLifecycleCurrentness {
    fun destroyed(): Boolean
    fun foreground(): Boolean
    fun frameIsCurrent(generation: Int, epoch: WalkRuntimeEpoch): Boolean
    fun epochIsCurrent(epoch: WalkRuntimeEpoch): Boolean
}
internal interface PrimaryModelConfiguration {
    fun loadOrCurrent(): TwoModelRuntimeConfig?
    fun current(): TwoModelRuntimeConfig?
    fun sha256(): String?
}
internal interface PrimaryLifecyclePlatform {
    fun loadDefault(): AndroidDetectorLoadResult
    fun select(config: TwoModelRuntimeConfig, allowComparison: Boolean, current: () -> Boolean,
        progress: (String) -> Unit): AndroidDetectorLoadResult
    fun logPreparationFailure(error: Throwable)
    fun close(detector: TfliteAndroidFrameDetector)
    fun firstSuccessfulDelegate(detector: TfliteAndroidFrameDetector): String?
    fun availableProcessors(): Int
    fun sample(detector: TfliteAndroidFrameDetector): AndroidDetectionResult
    fun performance(detector: TfliteAndroidFrameDetector): PrimaryFixedFixtureResult
}
internal interface PrimaryPreparationInteraction {
    fun metricDistanceEnabled(): Boolean
    fun walkRuntimeActive(): Boolean
    fun featureGatePassed(): Boolean
    fun progress(message: String)
    fun modelUnavailable(metricDistanceWasEnabled: Boolean)
    fun modelVerified()
    fun loaded()
    fun disableUnverifiedCapabilities()
    fun activeOrPausedWalk(): Boolean
    fun showVerificationFailure(walking: Boolean)
}
internal interface PrimaryLifecyclePresentation {
    fun onUi(effect: () -> Unit)
    fun status(status: String, detail: String)
    fun clearOverlay()
}
internal interface PrimaryLifecycleAuxiliary {
    fun calibrationPresent(): Boolean
    fun cancelCalibration(reason: String)
    fun releaseLoadingIfInitialized()
    fun closeActiveOrInvalidateObservations()
}
internal interface PrimaryDetectorFailureEffects {
    fun developmentBypassEnabled(): Boolean
    fun observeSafetyFailure(epoch: WalkRuntimeEpoch, observedAtMs: Long): Boolean
    fun resetVisualTracking()
    fun enterSafetyStop()
    fun stopCameraFallback()
    fun stopDepthSession()
    fun blockReportCandidates()
    fun showDetectorUnavailable()
}
