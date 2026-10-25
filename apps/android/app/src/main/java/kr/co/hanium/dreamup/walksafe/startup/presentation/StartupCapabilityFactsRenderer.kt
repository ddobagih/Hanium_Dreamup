package kr.co.hanium.dreamup.walksafe.startup.presentation

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.*

/** Builds presenter facts lazily in the existing SDK and current-owner observation order. */
internal class StartupCapabilityFactsRenderer(
    private val facts: StartupCapabilityPresentationFacts,
    private val effects: StartupCapabilityPresentationEffects,
) {
    fun renderStartupCapabilityFacts(
        decision: WalkSafeStartupCapabilityDecision,
        confirmed: Boolean,
        priorityUserDecision: PriorityUserOnboardingDecision,
        officialEnvironmentReady: Boolean,
        phoneMountingReady: Boolean,
    ) {
        val installableUnavailable = decision.unavailableRequirements
            .filter(USER_INSTALLABLE_REQUIREMENTS::contains)
        val checkSnapshot = facts.deviceSnapshot()
        val currentRestrictions = checkSnapshot.state == PostLoginDeviceCheckState.FULL && facts.hasCurrentRestrictions()
        val disabledText = if (currentRestrictions || checkSnapshot.state == PostLoginDeviceCheckState.LIMITED) facts.disabledFeatureText() else ""
        val failureMessage = if (checkSnapshot.state == PostLoginDeviceCheckState.FAIL) facts.failureMessage(checkSnapshot.failure) else ""
        val itemsMessage = facts.itemsMessage()
        val boundSessionPresent = facts.hasCurrentDeviceBinding()
        val deferredRetriable = facts.deferredChecksRetriable()
        val requiredPermissionsMissing = checkSnapshot.passesFeatureGate && facts.requiredPermissionsMissing()
        val locationServiceEnabled = !checkSnapshot.passesFeatureGate || requiredPermissionsMissing || facts.locationServiceEnabled()
        val walk = facts.walkSnapshot()
        val startupUiFacts = DeviceCheckPresenter.StartupFacts(
            capability = DeviceCheckPresenter.CapabilityFacts(
                noticeKo = decision.noticeKo, tier = decision.tier,
                mayConfirmAndStart = decision.mayConfirmAndStart,
                hasPendingRequirements = decision.pendingRequirements.isNotEmpty(), confirmed = confirmed,
                installableUnavailableLabels = installableUnavailable.map { it.labelKo },
                priorityNoticeKo = priorityUserDecision.noticeKo, priorityMayStartWalk = priorityUserDecision.mayStartWalk,
                officialEnvironmentReady = officialEnvironmentReady, phoneMountingReady = phoneMountingReady,
            ),
            check = DeviceCheckPresenter.CheckFacts(
                state = checkSnapshot.state, failure = checkSnapshot.failure,
                passesFeatureGate = checkSnapshot.passesFeatureGate, hasCurrentRestrictions = currentRestrictions,
                disabledFeaturesText = disabledText, itemsMessage = itemsMessage, failureMessage = failureMessage,
                hasBoundSession = boundSessionPresent, stage = facts.onboardingStage(),
                deferredChecksRetriable = deferredRetriable, storedBindingValidationPending = facts.storedBindingValidationPending(),
            ),
            settings = DeviceCheckPresenter.SettingsFacts(
                requiredPermissionsMissing = requiredPermissionsMissing, locationServiceEnabled = locationServiceEnabled,
                locationRequestFailed = facts.locationRequestFailed(),
                locationFixReason = facts.locationFixReason(),
                koreanSpeechSettingsButtonText = if (checkSnapshot.failure == PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE) facts.koreanSpeechSettingsButtonText() else null,
            ),
            wake = DeviceCheckPresenter.WakeFacts(
                signal = facts.wakeSignal(), modelReady = facts.voiceModelReady(),
                probeActive = facts.wakeProbeActive(), startScheduled = facts.wakeStartScheduled(),
            ),
            confirmation = DeviceCheckPresenter.ConfirmationFacts(
                walkState = walk.state, recoveryStage = walk.recoveryStage,
                confirmationTokenPresent = walk.confirmationToken != null,
                awaitingExplicitResume = facts.awaitingExplicitResume(),
                onboardingComplete = facts.onboardingComplete(),
                capabilityConfirmationPending = facts.capabilityConfirmationPending(),
                resumePromptPending = facts.resumePromptPending(),
                developmentQuickStartEnabled = facts.quickStartEnabled(),
                retryRequiresUserAction = facts.retryRequiresUserAction(),
            ),
        )
        if (effects.render(startupUiFacts)) effects.renderExplicitResume()
    }
}
