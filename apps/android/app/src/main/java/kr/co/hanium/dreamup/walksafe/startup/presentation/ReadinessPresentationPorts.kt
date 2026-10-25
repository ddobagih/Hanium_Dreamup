package kr.co.hanium.dreamup.walksafe.startup.presentation

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.*

/** Live presentation observations only; no underlying owner state is copied. */
internal interface FirstRunPresentationFacts {
    val initialized: Boolean
    val foreground: Boolean
    val quickStartEnabled: Boolean
    val guidanceBypassEnabled: Boolean
    fun published(target: NativeUiPublication.Target): Boolean
    fun snapshot(): FirstRunOnboardingSnapshot
    fun complete(): Boolean
    fun showPhonePosture(): Boolean
    fun allConsentGranted(): Boolean
    fun animationsEnabled(): Boolean
    fun cameraAnalysisEnabled(): Boolean
    fun hasCurrentDeviceBinding(): Boolean
    fun walkStateOrReady(): WalkSessionState
    fun permissionRecoveryBlocksStart(): Boolean
    fun developmentButtonPresent(): Boolean
    fun quickStartRequested(): Boolean
    fun accountRequestInFlight(): Boolean
    fun permissionRequestInFlight(): Boolean
    fun isAccountEntryScreen(): Boolean
}

/** Facts suppliers preserve the original presenter-receiver-before-arguments evaluation. */
internal interface FirstRunPresentationEffects {
    fun renderStage(facts: () -> FirstRunOnboardingPresenter.StageFacts)
    fun updateAccount(snapshot: FirstRunOnboardingSnapshot)
    fun renderConsentStage(facts: () -> FirstRunOnboardingPresenter.ConsentStageFacts)
    fun updateConsent()
    fun renderWaiting(facts: () -> FirstRunOnboardingPresenter.WaitingFacts)
    fun bindRuntimeViews()
    fun renderPreparation(facts: () -> FirstRunOnboardingPresenter.PreparationFacts)
    fun updateReadiness()
    fun renderRuntime(facts: () -> FirstRunOnboardingPresenter.RuntimeFacts)
    fun updateFeatureAvailability()
    fun renderDevelopmentControl(facts: () -> FirstRunOnboardingPresenter.DevelopmentFacts)
    fun refreshHome()
    fun refreshNotice()
    fun renderProgress(facts: () -> FirstRunOnboardingPresenter.ProgressFacts)
    fun updatePrivacyVisibility()
    fun renderMain()
}

internal interface WalkReadinessPresentationFacts {
    fun published(target: NativeUiPublication.Target): Boolean
    fun walkStateOrReady(): WalkSessionState
    fun complete(): Boolean
    fun devicePassesFeatureGate(): Boolean
    fun capabilityMayStart(): Boolean
    fun capabilityConfirmed(): Boolean
    fun hasCurrentRestrictions(): Boolean
    fun cameraAnalysisEnabled(): Boolean
    fun disabledFeatureText(): String
}

/** Each property resolves the original live view binding at its original access point. */
internal interface WalkReadinessPresentationView {
    var controlsVisibility: Int
    var summaryVisibility: Int
    var toggleVisibility: Int
    var toggleText: CharSequence
    var toggleDescription: CharSequence?
    var summaryText: CharSequence
    var summaryDescription: CharSequence?
}

internal interface StartupCapabilityPresentationFacts {
    fun deviceSnapshot(): PostLoginDeviceCheckSnapshot
    fun hasCurrentRestrictions(): Boolean
    fun disabledFeatureText(): String
    fun failureMessage(failure: PostLoginDeviceCheckFailure?): String
    fun itemsMessage(): String
    fun hasCurrentDeviceBinding(): Boolean
    fun deferredChecksRetriable(): Boolean
    fun requiredPermissionsMissing(): Boolean
    fun locationServiceEnabled(): Boolean
    fun walkSnapshot(): WalkSessionSnapshot
    fun onboardingStage(): FirstRunOnboardingStage
    fun storedBindingValidationPending(): Boolean
    fun locationRequestFailed(): Boolean
    fun locationFixReason(): DeviceCheckLocationFixReason?
    fun koreanSpeechSettingsButtonText(): String
    fun wakeSignal(): PostLoginDeviceCheckSignal
    fun voiceModelReady(): Boolean
    fun wakeProbeActive(): Boolean
    fun wakeStartScheduled(): Boolean
    fun awaitingExplicitResume(): Boolean
    fun onboardingComplete(): Boolean
    fun capabilityConfirmationPending(): Boolean
    fun resumePromptPending(): Boolean
    fun quickStartEnabled(): Boolean
    fun retryRequiresUserAction(): Boolean
}

internal interface StartupCapabilityPresentationEffects {
    fun render(facts: DeviceCheckPresenter.StartupFacts): Boolean
    fun renderExplicitResume()
}
