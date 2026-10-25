package kr.co.hanium.dreamup.walksafe.startup.capability

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.device.environment.*

/** Each observation remains lazy at its original decision point. */
internal interface StartupCapabilityEvaluation {
    val metricOverride: Boolean?
    val quickStart: Boolean
    val bypass: Boolean
    val checkPassesFeatureGate: Boolean
    fun probeDecision(metricOverride: Boolean?): WalkSafeStartupCapabilityDecision
    fun disabledFeatures(): Set<PostLoginDeviceCheckFeature>
    fun activityRecognitionPermission(): Boolean
    fun disabledFeatureText(): String
}

/** Borrowed existing confirmation owner, also used by native notice delivery. */
internal interface StartupCapabilityConfirmation {
    var confirmed: WalkSafeStartupCapabilityDecision?
    var pending: Boolean
    var retryRequiresUserAction: Boolean
    fun isConfirmed(): Boolean
}

internal interface StartupCapabilityUi {
    fun ready(): Boolean
    fun persist(decision: WalkSafeStartupCapabilityDecision)
    fun priority(decision: WalkSafeStartupCapabilityDecision): PriorityUserOnboardingDecision
    fun updateOfficialEnvironment()
    fun officialReadiness(epoch: WalkRuntimeEpoch): WalkSessionReadinessStatus
    fun mountingReadiness(epoch: WalkRuntimeEpoch): WalkSessionReadinessStatus
    /** Main captures the existing DeviceCheckPresenter facts here, in the original order. */
    fun render(decision: WalkSafeStartupCapabilityDecision, confirmed: Boolean,
        priority: PriorityUserOnboardingDecision, officialReady: Boolean, mountingReady: Boolean)
    fun updatePrewalk(epoch: WalkRuntimeEpoch, officialReady: Boolean, mountingReady: Boolean)
    fun updatePriority(decision: WalkSafeStartupCapabilityDecision)
    fun updateFirstRun()
    fun updateMounting()
    fun updateFieldLog()
    fun applyActionButtons()
    fun advanceAfterCheck()
    fun updateSafetySummary()
}

internal interface ActiveReadinessPort {
    val debug: Boolean
    fun capture(decision: WalkSafeStartupCapabilityDecision): WalkSessionReadinessSnapshot
    fun transition(event: WalkSessionEvent): WalkSessionTransition
    fun diagnostic(message: String)
    fun persistInterruption()
    fun clearResume()
    fun invalidateEnvironment(reason: String)
    fun invalidateMounting(reason: String)
    fun cancelOutputs(reason: String)
    fun stopCameraFallback()
    fun stopDepth()
    fun navigationStatus(message: String)
    fun status(title: String, detail: String)
    fun speak(message: String)
}

internal interface OfficialEnvironmentDisplayReader {
    val profile: ApprovedOfficialEnvironmentProfile?
    val cameraProfile: ApprovedCameraFrameQualityProfile?
    val approvedForProduction: Boolean?
    val preflightActive: Boolean
    val phase: OfficialEnvironmentPreflightPhase
    val lifecycleAvailable: Boolean
    val foreground: Boolean
    val bypass: Boolean
    val cameraCleanupPending: Boolean
    fun runtimeEpochOrNull(): WalkRuntimeEpoch?
    fun snapshot(): WalkSessionSnapshot
    fun assessment(epoch: WalkRuntimeEpoch): OfficialEnvironmentAssessment
    fun locationFeatureEnabled(): Boolean
    fun cameraFeaturesEnabled(): Boolean
    fun onboardingComplete(): Boolean
}

internal interface OfficialEnvironmentDisplayView {
    fun ready(): Boolean
    fun currentMessage(): String
    fun showMessage(message: String)
    fun setConfirmEnabled(enabled: Boolean)
    fun setConfirmTextAndDescription(message: String)
}

internal interface PrewalkGuidancePort {
    val nativeStartPending: Boolean
    val preflightActive: Boolean
    val preflightGeneration: Long
    fun continueNativeStart()
    fun snapshot(): WalkSessionSnapshot
    fun claimReadyGrace(): Boolean
    fun scheduleReadyTimeout(epoch: WalkRuntimeEpoch, generation: Long, delayMs: Long)
    fun mountingTarget(): PrewalkGuidanceTarget
    fun startupTarget(): PrewalkGuidanceTarget
}
internal interface PrewalkGuidanceTarget {
    val shown: Boolean
    val enabled: Boolean
    fun describe(message: String)
    fun post(action: () -> Unit)
    /** Original View rectangle-scroll then accessibility-focus order. */
    fun focus()
}
