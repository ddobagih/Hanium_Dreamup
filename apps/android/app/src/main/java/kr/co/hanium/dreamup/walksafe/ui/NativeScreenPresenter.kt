package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidanceResumeAction
import kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidancePresentation

import android.content.Context
import android.graphics.Typeface
import android.view.accessibility.AccessibilityNodeInfo
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_EMPHASIS
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_GROUND
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_FILL
import kotlin.math.roundToInt

/** Applies captured screen facts to views; navigation, permissions and preparation stay in Main. */
internal class NativeScreenPresenter(private val style: WalkSafeViewStyle) : NativePagePresentationController.FrameTarget {
    data class Panels(
        val account: AccountAccessPanel,
        val onboarding: OnboardingReadinessPanel,
        val settings: SettingsPrivacyPanel,
        val destination: DestinationGuidancePanel,
        val home: HomePanel,
    )

    data class RuntimeViews(
        val privacySectionToggleButton: View,
        val gatewaySessionControlsToggleButton: View,
        val walkStatusSection: View,
        val runtimeSectionDivider: View?,
    )

    data class FrameFacts(
        val pageName: String,
        val homeAvailable: Boolean,
        val homeVisible: Boolean,
        val settingsVisible: Boolean,
        val featureVisible: Boolean,
        val forceSettings: Boolean,
        val deletionRecovery: Boolean,
    )

    /** These booleans are display decisions, never approval to enter a feature. */
    data class PreviewFacts(
        val homeAvailable: Boolean,
        val homeVisible: Boolean,
        val settingsVisible: Boolean,
        val forceSettings: Boolean,
        val accountVisible: Boolean,
        val purpose: Boolean,
        val training: Boolean,
        val checking: Boolean,
        val guidanceVisible: Boolean,
        val deviceScreen: Boolean,
        val deviceDetails: Boolean,
        val preparing: Boolean,
        val showPhysicalPreparation: Boolean,
        val showMountingPreparation: Boolean,
        val limitedHome: Boolean,
        val deletionPhase: AccountDeletionPhase,
    ) {
        val deletionConfirm: Boolean get() = settingsVisible && deletionPhase == AccountDeletionPhase.CONFIRM_REQUIRED
        val deletionIdle: Boolean get() = deletionPhase == AccountDeletionPhase.IDLE
    }

    data class AccountPreviewFacts(val logoutVisible: Boolean, val noticeTone: String?)

    data class ReadinessPreviewFacts(
        val phonePostureNotice: String,
        val deviceFailed: Boolean,
        val deviceFailureMessage: String?,
        val deviceValues: Map<String, String>,
        val wakeProbeActive: Boolean,
        val wakeReady: Boolean,
        val hapticReady: Boolean,
        val devicePassesFeatureGate: Boolean,
        val needsKoreanVoice: Boolean,
        val voiceActionLabel: String,
        val voiceActionContentDescription: String,
    )

    data class PaneFacts(
        val pageName: String,
        val homeAvailable: Boolean,
        val settingsVisible: Boolean,
        val cameraVisible: Boolean,
        val firstRunHeading: CharSequence?,
        val educationHeading: CharSequence?,
    )

    data class OverlayLayout(val horizontalDp: Float, val verticalDp: Float, val centered: Boolean)
    data class HomeGeometry(val height: Int, val childHeight: Int, val childWeight: Float)

    private lateinit var panels: Panels
    private lateinit var runtime: RuntimeViews
    private var previewStylesApplied = false

    private lateinit var publishedVoice: DestinationGuidancePanel
    private lateinit var publishedSearch: DestinationGuidancePanel
    private lateinit var publishedGuidance: DestinationGuidancePanel
    fun publishVoiceTargets(panel: DestinationGuidancePanel) { publishedVoice = panel }
    fun publishSearchTargets(panel: DestinationGuidancePanel) { publishedSearch = panel }
    fun publishGuidanceTargets(panel: DestinationGuidancePanel) { publishedGuidance = panel }
    fun voiceBinding(): DestinationGuidancePanel = publishedVoice
    fun searchBinding(): DestinationGuidancePanel = publishedSearch
    fun guidanceBinding(): DestinationGuidancePanel = publishedGuidance

    fun clearSearchFocusAndHideKeyboard(context: Context) {
        searchBinding().destinationQueryInput.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(searchBinding().destinationQueryInput.windowToken, 0)
    }

    fun pageFocusTarget(
        page: NativeUiPage,
        home: () -> NativeHomeUiPresenter,
        settings: () -> SettingsPrivacyPresenter,
    ): NativeWorkflowController.FocusTarget? {
        val target = when (page) {
            NativeUiPage.HOME -> home().gridTarget().getChildAt(0)
            NativeUiPage.DESTINATION_SEARCH -> searchBinding().destinationQueryInput
            NativeUiPage.DESTINATION_CONFIRM -> guidanceBinding().nativeDestinationStartButton
            NativeUiPage.GUIDANCE -> when {
                searchBinding().routeDeviationNewRouteButton.isShown -> searchBinding().routeDeviationNewRouteButton
                searchBinding().routeDeviationRecheckButton.isShown -> searchBinding().routeDeviationRecheckButton
                guidanceBinding().nativeGuidanceRetryButton.isShown -> guidanceBinding().nativeGuidanceRetryButton
                else -> guidanceBinding().nativeGuidanceCancelButton
            }
            NativeUiPage.VOICE_COMMAND -> voiceBinding().voiceReportButton
            NativeUiPage.SETTINGS -> if (settings().mobilePreferenceTarget().isShown)
                settings().mobilePreferenceTarget() else settings().homeActionTarget()
        } ?: return null
        return object : NativeWorkflowController.FocusTarget {
            override val isShown: Boolean get() = target.isShown
            override fun post(action: Runnable) { target.post(action) }
            override fun requestFocus() { target.requestFocus() }
            override fun requestAccessibilityFocus() { target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        }
    }

    fun destinationInputEnabled(): Boolean = searchBinding().destinationQueryInput.isEnabled
    fun voiceInputEnabled(): Boolean = voiceBinding().voiceReportButton.isEnabled

    fun bindPanels(views: Panels) { panels = views }
    fun bindRuntimeViews(views: RuntimeViews) { runtime = views }

    override fun beginFrame(facts: FrameFacts) = with(panels) {
        show(home.homeCardGrid, facts.homeVisible)
        show(destination.runtimeControls, facts.featureVisible)
        show(destination.nativeDestinationControls, facts.featureVisible && facts.pageName == "DESTINATION_SEARCH")
        show(destination.nativeDestinationConfirmationControls, facts.featureVisible && facts.pageName == "DESTINATION_CONFIRM")
        show(destination.nativeGuidanceControls, facts.featureVisible && facts.pageName == "GUIDANCE")
        show(destination.nativeVoiceControls, facts.featureVisible && facts.pageName == "VOICE_COMMAND")
        show(destination.gatewayVoiceStatusText, false)
    }

    override fun renderDestinationConfirmationMessage(message: String) =
        with(panels.destination) {
            nativeDestinationConfirmationText.text = style.nativePanelMessage("선택한 목적지", message)
            nativeDestinationStartButton.text = "안내 시작"
        }

    override fun renderDestinationConfirmationActionDescription(message: String) {
        panels.destination.nativeDestinationStartButton.contentDescription = "$message. 안내 시작"
    }

    override fun renderDestinationStartEnabled(enabled: Boolean) { panels.destination.nativeDestinationStartButton.isEnabled = enabled }

    override fun renderGuidanceDestination(message: String) {
        val text = style.nativePanelMessage("안내 화면", message)
        val view = panels.destination.nativeGuidanceDestinationText
        if (view.text?.toString() != text.toString()) view.text = text
    }

    override fun renderGuidanceActions(presentation: DestinationGuidancePresentation, activityForeground: Boolean) = with(panels.destination) {
        val resume = presentation.resumeAction
        val available = presentation.retryAvailable || resume != DestinationGuidanceResumeAction.NONE
        show(nativeGuidanceRetryButton, available)
        nativeGuidanceRetryButton.isEnabled = available && activityForeground &&
            resume !in setOf(DestinationGuidanceResumeAction.PREPARING, DestinationGuidanceResumeAction.PENDING)
        val retryLabel = when (resume) {
            DestinationGuidanceResumeAction.PREPARING -> "재개 준비 중"
            DestinationGuidanceResumeAction.NOTICE -> "안전 제한 안내 확인"
            DestinationGuidanceResumeAction.CONFIRM -> "보행 안내 재개 확인"
            DestinationGuidanceResumeAction.PENDING -> "재개 확인 중"
            DestinationGuidanceResumeAction.NONE -> "다시 시도"
        }
        if (nativeGuidanceRetryButton.text?.toString() != retryLabel) nativeGuidanceRetryButton.text = retryLabel
        val label = "안내 종료"
        if (nativeGuidanceCancelButton.text?.toString() != label) nativeGuidanceCancelButton.text = label
        if (nativeGuidanceCancelButton.contentDescription?.toString() != label) {
            nativeGuidanceCancelButton.contentDescription = label
        }
        nativeGuidanceCancelButton.isEnabled = true
    }

    override fun finishFrame(facts: FrameFacts) = with(panels) {
        show(runtime.privacySectionToggleButton, false)
        show(settings.privacyControls, facts.settingsVisible)
        show(settings.privacySettingsControls, facts.settingsVisible && facts.homeAvailable && !facts.deletionRecovery)
        settings.privacyConsentStatusText.visibility = settings.privacySettingsControls.visibility
        show(settings.accountDeletionControls, facts.settingsVisible)
        show(settings.nativeSettingsLogoutButton, facts.settingsVisible && facts.homeAvailable && !facts.forceSettings)
        show(settings.nativeSettingsHomeButton, facts.settingsVisible && facts.homeAvailable && !facts.forceSettings)
        show(settings.gatewaySessionControls, facts.deletionRecovery)
        show(runtime.gatewaySessionControlsToggleButton, false)
        runtime.runtimeSectionDivider?.let { show(it, false) }
        if (facts.homeAvailable || facts.forceSettings) {
            show(onboarding.firstRunOnboardingControls, false)
            show(onboarding.firstRunNoticeToggleButton, false)
            show(onboarding.productPurposeText, false)
            show(onboarding.walkReadinessSummaryText, false)
            show(onboarding.walkReadinessToggleButton, false)
        }
    }

    override fun renderFramePreparation(facts: FrameFacts, preparing: Boolean) {
        val visible = preparing && !facts.settingsVisible && facts.pageName != "GUIDANCE"
        show(panels.onboarding.walkReadinessControls, visible)
        show(runtime.walkStatusSection, visible)
    }

    /** Main captures the account snapshot after this method, at its original point in the render. */
    fun beginPreview(facts: PreviewFacts) = with(panels) {
        with(onboarding) {
            show(firstRunProgressBar, false)
            show(firstRunNoticeToggleButton, false)
            show(firstRunWaitingCard, false)
            show(walkReadinessSummaryText, false)
            show(walkReadinessToggleButton, false)
            show(runtime.walkStatusSection, false)
            show(firstRunOnboardingControls, facts.accountVisible || facts.purpose || facts.deviceDetails)
            val heading = when {
                facts.purpose -> "앱의 한계와 안전 안내"
                facts.deviceDetails -> "기기 점검"
                else -> null
            }
            firstRunOnboardingStatusText.text = heading
            firstRunOnboardingStatusText.contentDescription = heading
            firstRunOnboardingStatusText.textSize = 26f
            firstRunOnboardingStatusText.typeface = style.wsTypeface(Typeface.NORMAL, medium = true)
            firstRunOnboardingStatusText.setTextColor(WS_COLOR_EMPHASIS)
            show(firstRunOnboardingStatusText, heading != null)
            show(productPurposeText, facts.purpose)
        }
        show(account.accountAccessControls, facts.accountVisible)
    }

    /** The existing education presenter runs immediately after this phase. */
    fun renderPreviewAccountAndPreparation(facts: PreviewFacts, accountFacts: AccountPreviewFacts) = with(panels) {
        with(account) {
            show(accountSessionLogoutButton, accountFacts.logoutVisible)
            if (!facts.accountVisible) show(accountAccessStatusText, false)
            if (facts.accountVisible && accountAccessStatusText.visibility == View.VISIBLE) {
                accountFacts.noticeTone?.let { style.applyNativeNoticeStyle(accountAccessStatusText, it) }
            }
            accountSignupBackButton.text = "로그인으로"
            accountSignupBackButton.contentDescription = accountSignupBackButton.text
        }
        with(onboarding) {
            show(firstRunPurposeButton, facts.purpose)
            show(walkReadinessControls, !facts.guidanceVisible && !facts.settingsVisible &&
                !facts.accountVisible && !facts.purpose && (facts.training || facts.deviceScreen || facts.preparing))
            show(officialEnvironmentStatusText, facts.showPhysicalPreparation)
            show(officialEnvironmentConfirmButton, facts.showPhysicalPreparation)
            show(phoneMountingStatusText, facts.showMountingPreparation)
            show(phoneMountingChestConfirmButton, facts.showMountingPreparation)
            show(phoneMountingNecklaceConfirmButton, false)
            show(priorityUserOnboardingControls, !facts.guidanceVisible && facts.training &&
                !facts.deviceScreen && !facts.settingsVisible)
        }
    }

    /** Main has already captured messages; this phase neither runs probes nor grants permissions. */
    fun renderPreviewReadiness(facts: PreviewFacts, readiness: ReadinessPreviewFacts) = with(panels) {
        with(onboarding) {
            priorityUserOnboardingStatusText.textSize = 26f
            priorityUserOnboardingStatusText.typeface = style.wsTypeface(Typeface.NORMAL, medium = true)
            priorityUserOnboardingStatusText.setTextColor(WS_COLOR_EMPHASIS)
            firstRunPhonePostureText.text = readiness.phonePostureNotice + "\n\n" +
                "착용 방법과 사용 환경을 확인하고 안내를 들으며 앱 조작을 익혀 주세요."
            firstRunPhonePostureText.contentDescription = firstRunPhonePostureText.text
            show(startupCapabilityText, false)
            show(nativeDeviceCheckPanel, facts.deviceDetails)
            show(postLoginDeviceCheckLiveStatusText, facts.deviceScreen && readiness.deviceFailed)
            if (facts.deviceScreen && readiness.deviceFailed) {
                postLoginDeviceCheckLiveStatusText.text = readiness.deviceFailureMessage
                style.applyNativeNoticeStyle(postLoginDeviceCheckLiveStatusText, "error")
            }
            if (facts.deviceDetails) {
                nativeDeviceCheckValues.forEach { (label, view) ->
                    view.text = if (label == "호출어" && readiness.wakeProbeActive) "듣는 중"
                        else readiness.deviceValues[label] ?: "확인 중"
                    view.contentDescription = "$label: ${view.text}"
                }
            }
            show(startupMetricPreflightButton, facts.deviceScreen && !facts.checking && !readiness.devicePassesFeatureGate)
            startupMetricPreflightButton.text = if (readiness.deviceFailed) "기기 점검 다시 시도" else "기기 점검 시작"
            startupMetricPreflightButton.contentDescription = startupMetricPreflightButton.text
            show(voiceDataInstallButton, facts.deviceScreen && readiness.needsKoreanVoice)
            voiceDataInstallButton.text = readiness.voiceActionLabel
            voiceDataInstallButton.contentDescription = readiness.voiceActionContentDescription
            if (readiness.needsKoreanVoice) startupMetricPreflightButton.isEnabled = false
            show(postLoginDeviceCheckSettingsButton, facts.deviceScreen && readiness.deviceFailed &&
                postLoginDeviceCheckSettingsButton.visibility == View.VISIBLE)
            show(postLoginDeviceCheckWakePhraseInstructionText, facts.deviceScreen && facts.checking)
            postLoginDeviceCheckWakePhraseInstructionText.text = "호출어 시험을 누른 뒤 “길라잡이”라고 말해 주세요."
            postLoginDeviceCheckWakePhraseInstructionText.contentDescription = postLoginDeviceCheckWakePhraseInstructionText.text
            show(postLoginDeviceCheckWakePhraseStartButton, facts.deviceScreen && facts.checking)
            postLoginDeviceCheckWakePhraseStartButton.text = when {
                readiness.wakeProbeActive -> "길라잡이 듣는 중"
                readiness.wakeReady -> "호출어 다시 시험"
                else -> "호출어 시험"
            }
            postLoginDeviceCheckWakePhraseStartButton.contentDescription = postLoginDeviceCheckWakePhraseStartButton.text
            show(postLoginDeviceCheckHapticQuestionText, false)
            show(postLoginDeviceCheckHapticRejectButton, false)
            show(postLoginDeviceCheckHapticConfirmButton, false)
            postLoginDeviceCheckHapticConfirmButton.text = if (readiness.hapticReady) "진동 확인 완료" else "진동 확인"
            postLoginDeviceCheckHapticConfirmButton.contentDescription = postLoginDeviceCheckHapticConfirmButton.text
            if (facts.guidanceVisible || !facts.preparing || facts.deviceScreen) show(startupCapabilityConfirmButton, false)
        }
        with(settings) {
            show(nativeSettingsVoiceDataButton, facts.settingsVisible && facts.homeAvailable &&
                !facts.forceSettings && facts.deletionIdle && readiness.needsKoreanVoice)
            nativeSettingsVoiceDataButton.text = readiness.voiceActionLabel
            nativeSettingsVoiceDataButton.contentDescription = readiness.voiceActionContentDescription
        }
        with(home) {
            show(homeCardGrid, facts.homeVisible && !facts.deviceScreen)
            show(nativeHomeRestrictionText, facts.limitedHome)
            show(nativeHomeCompassText, (facts.homeVisible || facts.guidanceVisible) && !facts.deviceScreen)
            show(nativeHomeRouteStatusText, facts.homeVisible && !facts.deviceScreen)
        }
    }

    /** Called after the existing compass refresh, before voice-first hiding and pane naming. */
    fun finishPreview(facts: PreviewFacts, durableDeletionConfirmationRecovery: Boolean, overlay: LinearLayout?) = with(panels) {
        with(settings) {
            show(privacyConsentStatusText, false)
            if (facts.settingsVisible) {
                nativeSettingsTitle.text = when {
                    facts.deletionConfirm -> "계정 삭제를 요청할까요?"
                    !facts.deletionIdle -> "계정 삭제"
                    else -> "설정"
                }
                show(privacySettingsControls, facts.deletionIdle && facts.homeAvailable)
                show(nativeSettingsLogoutButton, facts.deletionIdle && facts.homeAvailable && !facts.forceSettings)
                show(accountDeletionStatusText, !facts.deletionIdle)
                show(accountDeletionRequestButton, facts.deletionIdle || facts.deletionPhase == AccountDeletionPhase.COMPLETED)
                if (facts.deletionIdle) {
                    accountDeletionRequestButton.text = "계정 삭제"
                } else if (facts.deletionConfirm && !durableDeletionConfirmationRecovery) {
                    accountDeletionStatusText.text = "계정 삭제 요청 후 처리 상태를 확인할 수 있습니다."
                    accountDeletionStatusText.background = null
                    accountDeletionStatusText.setPadding(0, 0, 0, 0)
                    accountDeletionConfirmButton.text = "삭제 요청"
                    accountDeletionCancelButton.text = "취소"
                } else {
                    style.applyNativeNoticeStyle(accountDeletionStatusText,
                        if (facts.deletionPhase in setOf(AccountDeletionPhase.FAIL_CLOSED,
                            AccountDeletionPhase.PARTIAL_FAILURE, AccountDeletionPhase.RESTRICTED)) "warning" else "info")
                }
                accountDeletionRefreshButton.text = "처리 상태 새로고침"
                nativeSettingsLogoutButton.text = "계정 로그아웃"
                nativeSettingsHomeButton.text = "홈으로"
                show(nativeSettingsHomeButton, !facts.deletionConfirm)
                nativeSettingsHomeButton.isEnabled = facts.homeAvailable && !facts.forceSettings
            }
        }
        if (overlay == null) return@with
        val density = overlay.resources.displayMetrics.density
        val layout = overlayLayout(facts)
        val horizontal = (layout.horizontalDp * density).roundToInt()
        val vertical = (layout.verticalDp * density).roundToInt()
        overlay.setPadding(horizontal, vertical, horizontal, vertical)
        overlay.gravity = if (layout.centered) Gravity.CENTER_VERTICAL else Gravity.TOP
        overlay.setBackgroundColor(if (facts.deletionConfirm) 0xffe4e1dc.toInt() else WS_COLOR_GROUND)
        with(settings.privacyControls) {
            if (facts.deletionConfirm) {
                style.applyNativePanelStyle(this)
                (background as GradientDrawable).apply {
                    cornerRadius = 24f * density
                    setStroke(0, WS_COLOR_NOTICE_FILL)
                }
                setPadding((20f * density).roundToInt(), (24f * density).roundToInt(),
                    (20f * density).roundToInt(), (24f * density).roundToInt())
            } else {
                background = null
                setPadding(0, 0, 0, 0)
            }
        }
        listOf(
            overlay, onboarding.firstRunOnboardingControls, account.accountAccessControls,
            account.accountSignupControls, account.accountConsentStepControls, account.accountDetailsStepControls,
            onboarding.priorityUserOnboardingControls, onboarding.firstRunPhonePostureControls,
            onboarding.walkReadinessControls, destination.nativeDestinationControls,
            destination.nativeDestinationConfirmationControls, destination.nativeGuidanceControls,
            destination.nativeVoiceControls,
        ).forEach { style.spaceNativePreviewStack(it) }
        listOf(settings.privacyControls, settings.privacySettingsControls, settings.accountDeletionControls)
            .forEach { style.spaceNativePreviewStack(it, 12f) }
        (settings.nativeSettingsTitle.layoutParams as? LinearLayout.LayoutParams)?.let {
            val margin = (24f * density).roundToInt()
            if (it.bottomMargin != margin) {
                it.bottomMargin = margin
                settings.nativeSettingsTitle.layoutParams = it
            }
        }
        if (!previewStylesApplied) {
            previewStylesApplied = true
            listOf(
                account.accountLoginButton, account.accountConsentContinueButton, account.accountRequestOtpButton,
                account.accountCreateButton, onboarding.firstRunPurposeButton, onboarding.firstRunPhonePostureButton,
                onboarding.startupMetricPreflightButton, onboarding.postLoginDeviceCheckWakePhraseStartButton,
                onboarding.priorityUserEducationButton, onboarding.priorityUserEducationAgreeButton,
                destination.destinationSearchButton, destination.nativeDestinationStartButton,
                destination.nativeGuidanceRetryButton, destination.voiceReportButton, settings.nativeSettingsHomeButton,
            ).forEach { style.applyWsButtonStyle(it, 144f, primary = true) }
            style.applyNativeDangerButtonStyle(settings.accountDeletionRequestButton)
            style.applyNativeDangerButtonStyle(settings.accountDeletionConfirmButton)
            style.applyNativeNoticeStyle(onboarding.postLoginDeviceCheckWakePhraseInstructionText)
            style.applyNativeNoticeStyle(home.nativeHomeRestrictionText, "warning")
            settings.nativeSettingsTitle.typeface = style.wsTypeface(Typeface.NORMAL, medium = true)
        }
    }

    fun applyVoiceFirstPresentation(homeAvailable: Boolean, pageName: String, speechFailure: String?) = with(panels) {
        if (!homeAvailable) return@with
        val guidanceVisible = pageName == "GUIDANCE"
        listOf(
            home.nativeHomeRestrictionText, home.nativeHomeCompassText, home.nativeHomeRouteStatusText,
            destination.nativeDestinationConfirmationText, destination.nativeGuidanceDestinationText,
            destination.nativeGuidanceStatusText, destination.nativeGuidanceMeasurementText,
            destination.gatewayVoiceStatusText, settings.nativeSettingsTitle,
        ).forEach {
            val shown = guidanceVisible && (it === destination.nativeGuidanceDestinationText ||
                it === destination.nativeGuidanceStatusText)
            val visibility = if (shown) View.VISIBLE else View.GONE
            val importance = if (shown) View.IMPORTANT_FOR_ACCESSIBILITY_YES
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (it.visibility != visibility) it.visibility = visibility
            if (it.importantForAccessibility != importance) it.importantForAccessibility = importance
            if (it.accessibilityLiveRegion != View.ACCESSIBILITY_LIVE_REGION_NONE) {
                it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            }
        }
        with(destination) {
            val retryDescription = retryContentDescription(speechFailure,
                nativeGuidanceStatusText.text?.toString(), nativeGuidanceRetryButton.text?.toString() ?: "다시 시도")
            if (nativeGuidanceRetryButton.contentDescription?.toString() != retryDescription) {
                nativeGuidanceRetryButton.contentDescription = retryDescription
            }
            speechFailure?.takeIf { guidanceVisible }?.let { failure ->
                if (nativeGuidanceStatusText.text?.toString() != failure) nativeGuidanceStatusText.text = failure
                if (nativeGuidanceStatusText.contentDescription?.toString() != failure) {
                    nativeGuidanceStatusText.contentDescription = failure
                }
            }
        }
    }

    fun updateAccessibilityPaneTitle(facts: PaneFacts, overlay: LinearLayout?, camera: View?) {
        val root = overlay ?: return
        val cameraVisible = facts.cameraVisible && camera?.visibility == View.VISIBLE
        val target = if (cameraVisible) requireNotNull(camera) else root
        val title = paneTitle(facts.copy(cameraVisible = cameraVisible))
        val inactive = if (cameraVisible) root else camera
        if (inactive != null && ViewCompat.getAccessibilityPaneTitle(inactive) != null) {
            ViewCompat.setAccessibilityPaneTitle(inactive, null)
        }
        if (ViewCompat.getAccessibilityPaneTitle(target)?.toString() != title.toString()) {
            ViewCompat.setAccessibilityPaneTitle(target, title)
        }
    }

    fun updateHomeGeometry(scroll: ScrollView?, overlay: LinearLayout?, homeReady: Boolean) {
        if (scroll == null || overlay == null) return
        val viewport = scroll.height
        if (overlay.minimumHeight != viewport) overlay.minimumHeight = viewport
        if (!homeReady) return
        val home = panels.home
        if (home.homeCardGrid.visibility != View.VISIBLE) return
        val params = home.homeCardGrid.layoutParams as? LinearLayout.LayoutParams ?: return
        val limited = home.nativeHomeRestrictionText.visibility == View.VISIBLE
        val compassHeight = if (home.nativeHomeCompassText.visibility == View.VISIBLE)
            home.nativeHomeCompassText.measuredHeight else 0
        val routeStatusHeight = if (home.nativeHomeRouteStatusText.visibility == View.VISIBLE)
            home.nativeHomeRouteStatusText.measuredHeight else 0
        val geometry = homeGeometry(viewport, overlay.paddingTop, overlay.paddingBottom,
            compassHeight, routeStatusHeight, overlay.resources.displayMetrics.density, limited)
        if (params.height != geometry.height) {
            params.height = geometry.height
            params.topMargin = 0
            params.bottomMargin = 0
            home.homeCardGrid.layoutParams = params
        }
        for (index in 0 until home.homeCardGrid.childCount) {
            val child = home.homeCardGrid.getChildAt(index)
            val childParams = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (childParams.height != geometry.childHeight || childParams.weight != geometry.childWeight) {
                childParams.height = geometry.childHeight
                childParams.weight = geometry.childWeight
                child.layoutParams = childParams
            }
        }
    }

    private fun show(view: View, visible: Boolean) { view.visibility = if (visible) View.VISIBLE else View.GONE }

    companion object {
        fun paneTitle(facts: PaneFacts): CharSequence = when {
            facts.cameraVisible -> "카메라 화면"
            facts.settingsVisible -> "설정"
            !facts.homeAvailable -> facts.firstRunHeading?.takeIf { it.isNotBlank() }
                ?: facts.educationHeading?.takeIf { it.isNotBlank() } ?: "첫 실행 등록"
            else -> when (facts.pageName) {
                "DESTINATION_SEARCH" -> "목적지 찾기"
                "DESTINATION_CONFIRM" -> "목적지 확인"
                "GUIDANCE" -> "경로 안내"
                "VOICE_COMMAND" -> "음성 명령"
                "SETTINGS" -> "설정"
                else -> "홈"
            }
        }

        fun overlayLayout(facts: PreviewFacts): OverlayLayout {
            val unrestrictedHome = facts.homeVisible && !facts.limitedHome && !facts.deviceScreen
            return OverlayLayout(if (unrestrictedHome) 16f else 20f, if (unrestrictedHome) 16f else 24f,
                facts.accountVisible || facts.deviceScreen && !facts.deviceDetails || facts.deletionConfirm)
        }

        fun homeGeometry(viewport: Int, paddingTop: Int, paddingBottom: Int, compassHeight: Int,
                         routeStatusHeight: Int, density: Float, limited: Boolean): HomeGeometry {
            if (limited) return HomeGeometry(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f)
            val gap = (16f * density).roundToInt()
            val minimum = (144f * density).roundToInt() * 3 + gap * 2
            val available = viewport - paddingTop - paddingBottom - compassHeight - routeStatusHeight
            return HomeGeometry(maxOf(minimum, available), 0, 1f)
        }

        fun retryContentDescription(speechFailure: String?, status: String?, actionLabel: String = "다시 시도"): String =
            listOfNotNull(actionLabel, speechFailure, status?.takeIf { it.isNotBlank() }).joinToString(". ")
    }
}
