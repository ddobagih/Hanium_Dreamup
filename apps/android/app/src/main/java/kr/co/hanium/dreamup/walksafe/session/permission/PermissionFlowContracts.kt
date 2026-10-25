package kr.co.hanium.dreamup.walksafe.session.permission

import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckBinding
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.session.*

internal enum class PermissionRequestPurpose {
    INITIAL_APP_ENTRY, POST_LOGIN_DEVICE_CHECK, METRIC_PREFLIGHT_CAMERA,
    WALK_SESSION, RUNTIME_CAMERA, NAVIGATION, VOICE_COMMAND,
}

internal data class PermissionRequestLease<L>(
    val purpose: PermissionRequestPurpose,
    val epoch: WalkRuntimeEpoch,
    val generation: Long,
    val firstRunLease: L,
    val requestedPermissions: Set<ObservedPermission>,
    val requestedAndroidPermissions: Set<String>,
    val postLoginBinding: PostLoginDeviceCheckBinding?,
)

/** Queries stay live at each admission point; no copied account or device authority is stored. */
internal interface PermissionFlowAuthority<L> {
    fun foreground(): Boolean
    fun privacyInspectionComplete(): Boolean
    fun privacyInspectionDestroyed(): Boolean
    fun firstRunSnapshot(): FirstRunOnboardingSnapshot
    fun postLoginSnapshot(): PostLoginDeviceCheckSnapshot
    fun postLoginBindingLive(binding: PostLoginDeviceCheckBinding): Boolean
    fun firstRunLease(): L
    fun firstRunLeaseCurrent(lease: L): Boolean
    fun firstRunComplete(): Boolean
    fun metricPreflightAllowed(): Boolean
    fun lifecycleOrNull(): WalkSessionLifecycle?
    fun currentActorId(): String?
    fun gatewaySessionReady(session: kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession): Boolean
}

internal interface PermissionWalkReadiness {
    fun decision(): WalkSafeStartupCapabilityDecision?
    fun gatewayReady(): Boolean
    fun blockReason(decision: WalkSafeStartupCapabilityDecision): String?
    fun missingRequired(action: WalkSessionAction): List<String>
    fun missingForDecision(decision: WalkSafeStartupCapabilityDecision): List<String>
    fun runtimeActive(): Boolean
    fun locationServiceEnabled(): Boolean
    fun startupProbeReady(): Boolean
    fun hasDeviceRestrictions(): Boolean
    fun disabledFeatureText(): String
    fun cameraAnalysisEnabled(): Boolean
}

/** Permission loss effects use existing purpose owners; this port owns no runtime state. */
internal interface PermissionRuntimeActions {
    fun invalidateSpeechStorage()
    fun blockFieldSessionRestore()
    fun status(status: String, detail: String)
    fun navigationStatus(message: String)
    fun refreshStartup()
    fun renderMain()
    fun cancelPrewalk()
    fun safetyStop(reason: String)
    fun startAfterPermissionRecovery()
    fun recheckPrewalkReadiness()
    fun cancelCameraHandoff()
    fun stopDepth()
    fun stopCameraFallback()
    fun cancelDestinationLocation()
    fun clearReportLocationRequest()
    fun stopLocation()
    fun routeActive(): Boolean
    fun cancelRouteRequest()
    fun resetRoute()
    fun stopHandsFree()
    fun cancelVoiceRecognition()
    fun cancelReportDrain()
    fun stopSteps()
    fun activateMounting()
    fun applyEnvironment()
    fun startWithoutCamera()
    fun startNavigationServices()
    fun startHandsFreeIfAllowed()
    fun startLocationIfAllowed()
}

internal interface PermissionResultHandlers {
    fun postLogin(binding: PostLoginDeviceCheckBinding)
    fun metricCamera()
    fun runtimeCamera()
    fun navigation()
    fun voice()
}

internal interface PermissionPlatform {
    val sdkInt: Int
    fun finishing(): Boolean
    fun destroyed(): Boolean
    fun debugExitBypass(): Boolean
    fun cameraGranted(): Boolean
    fun preciseLocationGranted(): Boolean
    fun microphoneGranted(): Boolean
    fun activityRecognitionGranted(): Boolean
    fun notificationGranted(): Boolean
    fun coarseLocationGranted(): Boolean
    fun rationaleRecommended(permission: String): Boolean
    fun launchRequest(permissions: Array<String>, requestCode: Int)
}

internal interface PermissionFlowUi {
    fun panelReady(): Boolean
    fun hidePanel()
    fun renderProgress(awaitingResume: Boolean)
    fun renderDenial(message: String)
    fun renderResumeControl()
    fun postResumeFocus(stillAwaiting: () -> Boolean)
    fun requiredExitShowing(): Boolean
    fun showRequiredExit(missing: Set<String>)
    fun dismissRequiredExit()
}

internal interface PermissionFlowPreferences {
    fun ready(): Boolean
    fun restoreRecovery(): PermissionRecoveryGate
    fun persistRecovery(gate: PermissionRecoveryGate)
    fun initialStarted(): Boolean
    fun initialCompleted(): Boolean
    fun markInitialStarted(): Boolean
    fun clearInitialStarted()
    fun markInitialCompleted(): Boolean
}
