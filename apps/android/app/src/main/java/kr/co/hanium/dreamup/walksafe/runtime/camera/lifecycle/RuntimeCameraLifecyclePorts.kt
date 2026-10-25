package kr.co.hanium.dreamup.walksafe.runtime.camera.lifecycle

import com.google.ar.core.ArCoreApk
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightSession
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.runtime.camera.CameraFallbackStartReason
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Actual admission and support owners; getters keep the original short-circuit read points. */
internal interface RuntimeCameraAdmission {
    fun firstRunComplete(): Boolean
    fun runtimeActive(): Boolean
    fun requireStartupConfirmation(): Boolean
    fun startupConfirmed(): Boolean
    fun cameraPermission(): Boolean
    fun reporterRequired(reason: String): Boolean
    fun finishing(): Boolean
    fun destroyed(): Boolean
    fun feedbackGeneration(): Int
    fun otherCameraOwnerBlocksStart(): Boolean
    val fallbackRequested: Boolean
    val fallbackRunning: Boolean
    val fallbackCleanupPending: Boolean
    val fallbackHasCamera: Boolean
    val fallbackReleaseBlocked: Boolean
    var arCoreSupported: Boolean
    var depthSupported: Boolean
    fun detectorAvailable(): Boolean
    fun loadDetectorAfterCameraGate()
    fun loadedModel(): String?
    fun detectorFallbackUsed(): Boolean
    fun cameraAnalysisEnabled(): Boolean
}

/** Only SDK operations; a constructor must not ask Android or ARCore for anything. */
internal interface RuntimeArPlatform<S : Any, P : Any> {
    fun availability(): ArCoreApk.Availability
    fun availabilityAsync(callback: (ArCoreApk.Availability) -> Unit)
    fun requestInstall(userRequested: Boolean): ArCoreApk.InstallStatus
    fun consumeForcedCameraFallback(): Boolean
    fun createSession(): S
    fun createProvider(session: S): P
    fun configureDepth(provider: P): Boolean
    fun closeSession(session: S)
    fun closeProvider(provider: P)
    fun pauseSession(session: S)
    fun resumeBeforePublish(session: S): Boolean
}

internal interface RuntimeCameraRenderer {
    fun pauseForClose(): Boolean
    fun resumeAfterClose(shouldResume: Boolean)
    fun textureBound(value: Boolean)
    fun postActivationExpiry(task: () -> Unit, delayMs: Long)
    fun syncScreenPolicy()
    fun stopFallback(updateUi: Boolean, onReleased: () -> Unit)
    fun bindFallbackCurrent()
}

/** Real metric owner commands; invalidation continues through the independently owned wrapper. */
internal interface RuntimeCameraMetric {
    fun restoreCapability(available: Boolean?)
    fun invalidate(reason: String)
    fun retireRuntimeEvidence()
    fun revokeOutput()
    fun beginActivation(generation: Long, startedAtMs: Long)
    fun activationEvaluator(): RuntimeMetricPreflightSession?
    fun retainOrdinaryLoss(generation: Long, epoch: WalkRuntimeEpoch, observedAtMs: Long, evaluator: RuntimeMetricPreflightSession)
    fun handleLoss(generation: Long)
    fun postLoginSnapshot(): PostLoginDeviceCheckSnapshot
    fun deferredCameraChecks(): Boolean
    fun prepareStoredRefresh(snapshot: PostLoginDeviceCheckSnapshot, deferred: Boolean)
}

internal interface RuntimeCameraReadiness {
    fun decision(): WalkSafeStartupCapabilityDecision?
    fun allowsMetricDistance(decision: WalkSafeStartupCapabilityDecision): Boolean
    fun resolveDistanceLimited(): WalkSafeStartupCapabilityDecision
    fun confirmDecision(decision: WalkSafeStartupCapabilityDecision)
    fun enterForegroundRecheckAndCancelOutputs(reason: String)
    fun refreshUi()
    fun startWithoutCamera()
    fun startInitialNavigationIfCurrent(generation: Long, epoch: WalkRuntimeEpoch)
    fun startNavigationIfNeeded()
    fun setObstacleUnavailable()
    fun activateMountingRuntime()
    fun applyCurrentEnvironmentAssessment()
}

/** Widget and recording effects only; the coordinator owns their ordering and conditions. */
internal interface RuntimeCameraPresentation {
    fun startAction(text: String, enabled: Boolean)
    fun actionInitialized(): Boolean
    fun status(status: String, detail: String)
    fun guidanceStage(stage: String)
    fun localRouteState(value: String)
    fun reportStatus(value: String)
    fun recordEvent(name: String, facts: Map<String, Any?> = emptyMap())
    fun fieldLogInitialized(): Boolean
    fun previewVisible(visible: Boolean)
    fun previewInitialized(): Boolean
    fun disableMetadataUploadIfInitialized()
    fun clearOverlayOnUiIfInitialized()
    fun resetActionIfInitialized()
    fun updateUploadActionIfInitialized()
}

internal interface RuntimeCameraFrameRetirement {
    fun clearReportFrameCapture()
    fun retireDetectorGeneration()
    fun resetVisualTracking()
    fun clearDetectionSnapshot()
    fun resetPacing()
    fun clearMetadata()
    fun resetNonMetricPolicy()
}

internal data class PendingRuntimeCameraFallbackStart(
    val reason: CameraFallbackStartReason,
    val availability: ArCoreApk.Availability,
)
