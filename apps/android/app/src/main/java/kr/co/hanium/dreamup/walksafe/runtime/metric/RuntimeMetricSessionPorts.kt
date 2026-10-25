package kr.co.hanium.dreamup.walksafe.runtime.metric

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*

/** Borrowed native identities only. This model never owns or closes either resource. */
internal data class RuntimeMetricArObservation<Q>(
    val session: Any?, val provider: Any?, val purpose: Q, val generation: Long,
) {
    fun matchesLease(lease: RuntimeMetricArObservation<Q>): Boolean =
        lease.session != null && lease.provider != null &&
            session === lease.session && provider === lease.provider &&
            purpose == lease.purpose && generation == lease.generation
    fun hasPurposeAndGeneration(expectedPurpose: Q, expectedGeneration: Long): Boolean =
        purpose == expectedPurpose && generation == expectedGeneration
    fun allowsMetricLease(runtimePurpose: Q, expectedGeneration: Long?): Boolean =
        purpose == runtimePurpose && (expectedGeneration == null || generation == expectedGeneration) &&
            session != null && provider != null
}

internal enum class RuntimeMetricPreflightOwner { NONE, DEVICE_CHECK, STORED_DEPTH_REFRESH }

/** Properties are evaluated lazily at the original gate, never during construction. */
internal interface RuntimeMetricScope {
    val lifecycle: WalkSessionLifecycle
    val runtimeActive: Boolean
    val foreground: Boolean
    val finishing: Boolean
    val destroyed: Boolean
    val onboardingComplete: Boolean
    val cameraOutputsAllowed: Boolean
    val mountingOutputsAllowed: Boolean
    val metricDistanceAllowed: Boolean?
    val startupConfirmed: Boolean
    val profile: RuntimeMetricPreflightProfile?
    val feedbackGeneration: Int
}

internal interface RuntimeMetricNativePort<Q, S : Any> {
    val purpose: Q
    val generation: Long
    val sessionPresent: Boolean
    val providerPresent: Boolean
    val cleanupPending: Boolean
    fun observe(): RuntimeMetricArObservation<Q>
    fun preflightDiagnostics(): String
    /** Same shared lock: Session=null, then provider.close, then remaining native detach. */
    fun detachCompletedPreflight(): S?
    /** Original renderer/session-close effects after leaving the state transaction. */
    fun finishDetachedPreflight(session: S?)
}

internal interface RuntimeMetricDeviceChecks<L> {
    val probeAvailable: Boolean
    val firstRunAllowsPreflight: Boolean
    val cameraResourceConflict: Boolean
    val fieldSessionActive: Boolean
    val snapshot: PostLoginDeviceCheckSnapshot
    val onboardingMayEnterWalk: Boolean
    val depthState: PostLoginMetricDepthState
    val cameraChecksDeferred: Boolean
    val storedRefreshPending: Boolean
    fun startupInput(): WalkSafeStartupCapabilityInput
    fun bindingCurrent(binding: PostLoginDeviceCheckBinding): Boolean
    fun bindingLive(binding: PostLoginDeviceCheckBinding): Boolean
    fun firstRunLease(): L?
    fun firstRunLeaseCurrent(lease: L): Boolean
    fun storedRefreshCurrent(binding: PostLoginDeviceCheckBinding): Boolean
    fun setPreflightStarted(started: Boolean)
    fun restoreDepth(state: PostLoginMetricDepthState)
    fun continueDeviceCheck(binding: PostLoginDeviceCheckBinding)
    fun completeStoredRefresh(available: Boolean?, binding: PostLoginDeviceCheckBinding): Boolean
    fun profileMatch(): ApprovedDeviceProfileMatch
    fun persistResult(result: RuntimeMetricPreflightResult, profileMatch: ApprovedDeviceProfileMatch)
}

internal interface RuntimeMetricEffects {
    val cameraPermission: Boolean
    val explainCameraPermission: Boolean
    val debug: Boolean
    fun clearStartupConfirmation()
    fun refreshStartupUi()
    fun requestCameraPermission()
    fun startNativePreflight(generation: Long, lifecycleGeneration: Int)
    fun status(status: String, detail: String)
    fun postToUi(action: () -> Unit)
    fun startNavigationServices()
    fun cameraFrameUnavailable(epoch: WalkRuntimeEpoch, observedAtMs: Long)
    fun runtimeMetricLost(expectedArGeneration: Long)
    fun clearReportFrame(reason: String)
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
}
