package kr.co.hanium.dreamup.walksafe.startup.presentation

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.*

/** Owns presentation ordering; readiness, consent and account state stay with their actual owners. */
internal class FirstRunPresentationCoordinator(
    private val facts: FirstRunPresentationFacts,
    private val effects: FirstRunPresentationEffects,
    private val consentLabel: String,
    private val emailStageCount: Int,
    private val legacyStageCount: Int,
) {
    fun updateFirstRunOnboardingUi() {
        if (
            !facts.initialized ||
            !facts.published(NativeUiPublication.Target.FIRST_RUN_ONBOARDING_STATUS_TEXT)
        ) return
        val snapshot = facts.snapshot()
        val stageNumber = firstRunStageNumber(snapshot)
        val stageCount = firstRunStageCount(snapshot)
        effects.renderStage {
            FirstRunOnboardingPresenter.StageFacts(
                stage = snapshot.stage,
                stageNumber = stageNumber,
                stageCount = stageCount,
                emailAccountFlow = snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4,
                showPosture = snapshot.stage == FirstRunOnboardingStage.FP004_TRAINING && facts.showPhonePosture(),
                firstRunComplete = snapshot.stage == FirstRunOnboardingStage.COMPLETE && facts.complete(),
            )
        }
        effects.updateAccount(snapshot)
        effects.renderConsentStage {
            FirstRunOnboardingPresenter.ConsentStageFacts(
                stage = snapshot.stage,
                allGranted = facts.published(NativeUiPublication.Target.FIRST_RUN_CONSENT_ALL_BUTTON) &&
                    facts.allConsentGranted(),
                allConsentLabel = consentLabel,
                disclosureTextReady = facts.published(NativeUiPublication.Target.FIRST_RUN_INTEGRATED_CONSENT_DISCLOSURE_TEXT),
                disclosureToggleReady = facts.published(NativeUiPublication.Target.FIRST_RUN_DISCLOSURE_TOGGLE_BUTTON),
                countReady = facts.published(NativeUiPublication.Target.FIRST_RUN_CONSENT_COUNT_TEXT),
                allButtonReady = facts.published(NativeUiPublication.Target.FIRST_RUN_CONSENT_ALL_BUTTON),
                saveButtonReady = facts.published(NativeUiPublication.Target.FIRST_RUN_INTEGRATED_CONSENT_SAVE_BUTTON),
            )
        }
        effects.updateConsent()
        if (facts.published(NativeUiPublication.Target.FIRST_RUN_WAITING_CARD)) {
            effects.renderWaiting {
                FirstRunOnboardingPresenter.WaitingFacts(
                    text = firstRunWaitingTextOrNull(snapshot),
                    activityForeground = facts.foreground,
                    animationsEnabled = facts.animationsEnabled(),
                )
            }
        }
        val mayTrain = !facts.quickStartEnabled &&
            snapshot.stage == FirstRunOnboardingStage.FP004_TRAINING
        val showWalkPreparation = snapshot.isComplete ||
            (facts.quickStartEnabled && facts.complete())
        val showPhoneMountingPreparation = showWalkPreparation && facts.cameraAnalysisEnabled()
        val mayCheckDevice = !facts.quickStartEnabled &&
            snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
            facts.hasCurrentDeviceBinding() &&
            snapshot.stage in setOf(
                FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
                FirstRunOnboardingStage.DEVICE_CHECK,
                FirstRunOnboardingStage.FP004_TRAINING,
                FirstRunOnboardingStage.COMPLETE,
            )
        val mayUseWalk = facts.complete()
        val walkState = facts.walkStateOrReady()
        val walkScreenVisible = mayUseWalk && walkState in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)
        effects.bindRuntimeViews()
        effects.renderPreparation {
            FirstRunOnboardingPresenter.PreparationFacts(
                developmentQuickStartEnabled = facts.quickStartEnabled,
                developmentGuidanceStartBypassEnabled = facts.guidanceBypassEnabled,
                walkScreenVisible = walkScreenVisible,
                mayTrain = mayTrain,
                showWalkPreparation = showWalkPreparation,
                showPhoneMountingPreparation = showPhoneMountingPreparation,
                mayCheckDevice = mayCheckDevice,
                mayUseWalk = mayUseWalk,
                walkState = walkState,
                priorityControlsReady = facts.published(NativeUiPublication.Target.PRIORITY_USER_ONBOARDING_CONTROLS),
                environmentStatusReady = facts.published(NativeUiPublication.Target.OFFICIAL_ENVIRONMENT_STATUS_TEXT),
                startupTextReady = facts.published(NativeUiPublication.Target.STARTUP_CAPABILITY_TEXT),
                readinessControlsReady = facts.published(NativeUiPublication.Target.WALK_READINESS_CONTROLS),
            )
        }
        if (facts.published(NativeUiPublication.Target.WALK_READINESS_CONTROLS)) effects.updateReadiness()
        effects.renderRuntime {
            FirstRunOnboardingPresenter.RuntimeFacts(
                mayUseWalk = mayUseWalk,
                runtimeVisible = facts.published(NativeUiPublication.Target.RUNTIME_CONTROLS) && mayUseWalk &&
                    walkState == WalkSessionState.ACTIVE && !facts.permissionRecoveryBlocksStart(),
            )
        }
        if (facts.published(NativeUiPublication.Target.DESTINATION_QUERY_INPUT)) effects.updateFeatureAvailability()
        if (facts.quickStartEnabled && facts.developmentButtonPresent()) {
            effects.renderDevelopmentControl {
                FirstRunOnboardingPresenter.DevelopmentFacts(
                    enabled = !facts.quickStartRequested() && !facts.accountRequestInFlight() &&
                        !facts.permissionRequestInFlight(),
                    paused = walkState == WalkSessionState.PAUSED,
                )
            }
        }
        effects.refreshHome()
        effects.refreshNotice()
        if (facts.published(NativeUiPublication.Target.FIRST_RUN_PROGRESS_BAR)) {
            effects.renderProgress {
                FirstRunOnboardingPresenter.ProgressFacts(
                    stageNumber = stageNumber,
                    hidden = facts.quickStartEnabled || facts.complete() || facts.isAccountEntryScreen(),
                )
            }
        }
        effects.updatePrivacyVisibility()
        effects.renderMain()
    }

    fun firstRunWaitingTextOrNull(snapshot: FirstRunOnboardingSnapshot): String? {
        if (snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4) {
            return if (facts.accountRequestInFlight()) {
                "인증번호 요청, 계정 생성 또는 로그인을 처리하고 있습니다. 잠시 기다려 주세요."
            } else {
                null
            }
        }
        return when (snapshot.stage) {
            FirstRunOnboardingStage.VERIFIED_SMS ->
                "운영 공급자의 SMS 검증 증거를 기다리고 있습니다. " +
                    "이 화면에서 아무것도 입력할 필요가 없습니다."
            FirstRunOnboardingStage.GUARDIAN_APPROVAL ->
                "미성년 사용자에게 필요한 보호자 확인 증거를 기다리고 있습니다. " +
                    "이 화면에서 아무것도 입력할 필요가 없습니다."
            FirstRunOnboardingStage.ACCOUNT_ACTIVATION ->
                "운영 계정 활성화 증거를 기다리고 있습니다. " +
                    "이 화면에서 아무것도 입력할 필요가 없습니다."
            FirstRunOnboardingStage.VERIFIED_LOGIN ->
                "검증된 로그인 증거를 기다리고 있습니다. " +
                    "이 화면에서 아무것도 입력할 필요가 없습니다."
            else -> null
        }
    }

    fun firstRunStageNumber(snapshot: FirstRunOnboardingSnapshot): Int =
        if (snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4) {
            when (snapshot.stage) {
                FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT -> 1
                FirstRunOnboardingStage.ACCOUNT_CREATED -> 2
                FirstRunOnboardingStage.VERIFIED_LOGIN -> 3
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY -> 4
                FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION -> 5
                FirstRunOnboardingStage.DEVICE_CHECK -> 6
                FirstRunOnboardingStage.FP004_TRAINING -> 7
                FirstRunOnboardingStage.COMPLETE -> 7
                else -> 1
            }
        } else when (snapshot.stage) {
        FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT,
        FirstRunOnboardingStage.ACCOUNT_CREATED,
        -> 1
        FirstRunOnboardingStage.PURPOSE_AND_SAFETY -> 1
        FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED -> 2
        FirstRunOnboardingStage.INTEGRATED_CONSENT -> 3
        FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION -> 4
        FirstRunOnboardingStage.VERIFIED_SMS -> 5
        FirstRunOnboardingStage.GUARDIAN_APPROVAL -> 6
        FirstRunOnboardingStage.ACCOUNT_ACTIVATION -> 7
        FirstRunOnboardingStage.VERIFIED_LOGIN -> 8
        FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION -> 9
        FirstRunOnboardingStage.DEVICE_CHECK -> 10
        FirstRunOnboardingStage.FP004_TRAINING -> 11
        FirstRunOnboardingStage.COMPLETE -> 12
        FirstRunOnboardingStage.BLOCKED_UNDER_14 -> 2
        }

    fun firstRunStageCount(snapshot: FirstRunOnboardingSnapshot): Int =
        if (snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4) {
            emailStageCount
        } else {
            legacyStageCount
        }
}
