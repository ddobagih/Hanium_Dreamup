package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import kr.co.hanium.dreamup.walksafe.session.FirstRunAgeBand
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.PriorityUserAgeBand
import kr.co.hanium.dreamup.walksafe.session.PriorityUserEducationPresentation
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPractice
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_BUTTON_BORDER
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_BUTTON_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_EMPHASIS
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_LINE
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_FILL
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_CORNER_RADIUS_DP
import kotlin.math.roundToInt

/** First-run and education presentation. Policy decisions and feedback delivery stay with the caller. */
internal class FirstRunOnboardingPresenter(
    private val context: Context,
    private val style: WalkSafeViewStyle,
    private var onboarding: OnboardingReadinessPanel,
) {
    private var account: AccountAccessPanel? = null
    private var legacyLogin: LegacyLoginViews? = null
    private var runtimeViews = RuntimeViews()
    private var firstRunNoticeExpandedByUser = false
    private var firstRunDisclosureExpanded = false

    data class LegacyLoginViews(
        val loginUserIdInput: EditText,
        val loginSaveButton: Button,
        val accountLogoutButton: Button,
    )

    data class RuntimeViews(
        val fieldSessionLogButton: Button? = null,
        val walkStatusSection: LinearLayout? = null,
        val runtimeControls: LinearLayout? = null,
        val developmentQuickStartButton: Button? = null,
    )

    data class StageFacts(
        val stage: FirstRunOnboardingStage,
        val stageNumber: Int,
        val stageCount: Int,
        val emailAccountFlow: Boolean,
        val showPosture: Boolean,
        val firstRunComplete: Boolean,
    )

    data class ConsentStageFacts(
        val stage: FirstRunOnboardingStage,
        val allGranted: Boolean,
        val allConsentLabel: String,
        val disclosureTextReady: Boolean,
        val disclosureToggleReady: Boolean,
        val countReady: Boolean,
        val allButtonReady: Boolean,
        val saveButtonReady: Boolean,
    )

    data class WaitingFacts(
        val text: String?,
        val activityForeground: Boolean,
        val animationsEnabled: Boolean,
    )

    data class PreparationFacts(
        val developmentQuickStartEnabled: Boolean,
        val developmentGuidanceStartBypassEnabled: Boolean,
        val walkScreenVisible: Boolean,
        val mayTrain: Boolean,
        val showWalkPreparation: Boolean,
        val showPhoneMountingPreparation: Boolean,
        val mayCheckDevice: Boolean,
        val mayUseWalk: Boolean,
        val walkState: WalkSessionState,
        val priorityControlsReady: Boolean,
        val environmentStatusReady: Boolean,
        val startupTextReady: Boolean,
        val readinessControlsReady: Boolean,
    )

    data class RuntimeFacts(val mayUseWalk: Boolean, val runtimeVisible: Boolean)
    data class DevelopmentFacts(val enabled: Boolean, val paused: Boolean)
    data class ProgressFacts(val stageNumber: Int, val hidden: Boolean)
    data class ConsentSummaryFacts(val itemCount: Int, val draftGranted: Int, val serverSummary: String)

    /** Only the values rendered by the education screen, without policy revision or delivery owners. */
    data class EducationState(
        val ageBand: PriorityUserAgeBand,
        val usageConditionsAcknowledged: Boolean,
        val appUsageReviewed: Boolean,
        val educationReviewed: Boolean,
        val practiceNecessityReviewed: Boolean,
        val interactivePracticeComplete: Boolean,
        val safePracticePlaceConfirmed: Boolean,
        val requiresPracticeRestart: Boolean,
        val nextRequiredPractice: PriorityUserPractice?,
        val completedPractices: Set<PriorityUserPractice>,
    )

    data class NativeEducationFacts(
        val state: EducationState,
        val showPosture: Boolean,
        val showEducation: Boolean,
        val eligible: Boolean,
        val educationInFlight: Boolean,
        val playbackIsPracticeNecessity: Boolean,
        val practiceInFlight: PriorityUserPractice?,
        val screenReaderReview: Boolean,
        val hazardResponseReady: Boolean,
        val body: String,
        val progress: String,
        val activityForeground: Boolean,
    )

    data class AccessibilityFacts(
        val screenReaderActive: Boolean,
        val largeTextEnabled: Boolean,
        val highContrastEnabled: Boolean,
    )

    data class PriorityFacts(
        val state: EducationState,
        val accessibility: AccessibilityFacts,
        val accountBound: Boolean,
        val deviceGateOpen: Boolean,
        val mayActivateAccount: Boolean,
        val notice: String,
        val remainingPracticeLabels: List<String>,
        val educationInFlight: Boolean,
        val practiceInFlight: PriorityUserPractice?,
        val hazardResponseReady: Boolean,
    )

    data class FocusFacts(
        val stage: FirstRunOnboardingStage,
        val showPosture: Boolean,
        val appUsageReviewed: Boolean,
    )

    data class FocusActions(
        val isStageCurrent: (FirstRunOnboardingStage) -> Boolean,
        val hasControlsScroll: () -> Boolean,
    )

    private lateinit var publishedRegistration: OnboardingReadinessPanel
    private lateinit var publishedEducation: OnboardingReadinessPanel
    private lateinit var publishedReadiness: OnboardingReadinessPanel
    private lateinit var publishedPermission: OnboardingReadinessPanel
    fun publishRegistrationTargets() { publishedRegistration = onboarding }
    fun publishEducationTargets() { publishedEducation = onboarding }
    fun publishReadinessTargets() { publishedReadiness = onboarding }
    fun publishPermissionTargets() { publishedPermission = onboarding }
    fun registrationBinding(): OnboardingReadinessPanel = publishedRegistration
    fun educationBinding(): OnboardingReadinessPanel = publishedEducation
    fun readinessBinding(): OnboardingReadinessPanel = publishedReadiness
    fun permissionBinding(): OnboardingReadinessPanel = publishedPermission

    fun hidePermissionPanel() { permissionBinding().permissionDenialPanel.visibility = View.GONE }
    fun renderPermissionProgress(awaitingResume: Boolean) = with(permissionBinding()) {
        permissionDenialPanel.visibility = View.VISIBLE
        permissionDenialSummaryText.text = if (awaitingResume)
            "가입, 동의, 권한과 필수 기능 전체 재확인을 마쳤습니다. 확인하기 전에는 보행, 수집 또는 전송을 시작하지 않습니다."
        else "필수 기능 다시 확인을 눌러 현재 환경과 휴대전화 장착 상태를 점검하세요. 점검만 진행하며 보행 안내나 전송을 시작하지 않습니다."
        permissionDenialSummaryText.contentDescription = permissionDenialSummaryText.text
        permissionDenialConfirmButton.text = if (awaitingResume) "재검사 결과 확인" else "필수 기능 다시 확인"
        permissionDenialConfirmButton.isEnabled = true
        permissionDenialSettingsButton.visibility = if (awaitingResume) View.GONE else View.VISIBLE
    }
    fun renderPermissionDenial(message: String) {
        with(permissionBinding()) {
            permissionDenialPanel.visibility = View.VISIBLE
            permissionDenialSummaryText.text = message
            permissionDenialSummaryText.contentDescription = message
            permissionDenialConfirmButton.text = "확인"
            permissionDenialConfirmButton.isEnabled = true
            permissionDenialSettingsButton.visibility = View.VISIBLE
            permissionDenialSummaryText.post {
                with(permissionBinding()) {
                    if (permissionDenialPanel.visibility == View.VISIBLE) {
                        permissionDenialSummaryText.requestFocus()
                        permissionDenialSummaryText.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                    }
                }
            }
        }
    }

    fun bindOnboardingPanel(panel: OnboardingReadinessPanel) { onboarding = panel }
    fun bindAccountPanel(panel: AccountAccessPanel) { account = panel }
    fun bindLegacyLoginViews(views: LegacyLoginViews) { legacyLogin = views }
    fun bindRuntimeViews(views: RuntimeViews) { runtimeViews = views }

    fun toggleNotice(purposeStageVisible: Boolean) {
        firstRunNoticeExpandedByUser = !firstRunNoticeExpandedByUser
        refreshFirstRunNoticeUi(purposeStageVisible)
        if (firstRunNoticeExpandedByUser) {
            onboarding.productPurposeText.post {
                onboarding.productPurposeText.requestFocus()
                onboarding.productPurposeText.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null,
                )
            }
        }
    }

    fun toggleDisclosure(refresh: () -> Unit) {
        firstRunDisclosureExpanded = !firstRunDisclosureExpanded
        refresh()
        if (firstRunDisclosureExpanded) {
            onboarding.firstRunIntegratedConsentDisclosureText.post {
                onboarding.firstRunIntegratedConsentDisclosureText.requestFocus()
                onboarding.firstRunIntegratedConsentDisclosureText.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null,
                )
            }
        }
    }

    fun refreshFirstRunNoticeUi(purposeStageVisible: Boolean) {
        with(onboarding) {
            firstRunNoticeToggleButton.visibility = View.GONE
            productPurposeText.visibility = if (purposeStageVisible) View.VISIBLE else View.GONE
            if (purposeStageVisible) {
                productPurposeText.text =
                    "이 앱은 모든 장애물과 위험을 감지하지 못하며, 안전한 보행을 보장하지 않습니다.\n\n" +
                    "조명·날씨·주변 소음·휴대폰 장착 상태 등에 따라 인식과 안내가 늦거나 누락될 수 있습니다. " +
                    "위치와 거리에도 오차가 생길 수 있습니다.\n\n" +
                    "흰지팡이·안내견 등 기존 보조수단을 대신하지 않습니다. " +
                    "안내가 없더라도 안전하다고 판단하지 마세요. " +
                    "안내가 불확실하거나 위험하다고 느끼면 안전한 곳에서 멈추고 주변 도움을 요청하세요."
                productPurposeText.contentDescription = productPurposeText.text
                style.applyWsSafetyNoticeBody(productPurposeText)
            }
        }
    }

    fun linkFirstRunAccessibilityTraversal(signupDocumentKeys: Iterable<String>) {
        with(onboarding) {
            with(checkNotNull(account)) {
                val controls = buildList<View> {
                    add(firstRunNoticeToggleButton)
                    add(productPurposeText)
                    add(firstRunOnboardingStatusText)
                    add(accountAccessStatusText)
                    add(accountEmailInput)
                    add(accountPasswordInput)
                    add(accountLoginButton)
                    add(accountSignupToggleButton)
                    signupDocumentKeys.forEach { key ->
                        add(accountConsentChecks.getValue(key))
                        add(accountConsentClauseTexts.getValue(key))
                        add(accountConsentListenButtons.getValue(key))
                    }
                    add(accountConsentContinueButton)
                    add(accountPasswordConfirmationInput)
                    add(accountDateOfBirthInput)
                    add(accountRequestOtpButton)
                    add(accountOtpInput)
                    add(accountCreateButton)
                    add(accountSignupBackButton)
                    add(accountSessionLogoutButton)
                    add(firstRunPurposeButton)
                    listOf(
                        FirstRunAgeBand.ADULT_18_PLUS,
                        FirstRunAgeBand.AGE_14_TO_17,
                        FirstRunAgeBand.UNDER_14,
                    ).forEach { ageBand -> add(firstRunAgeButtons.getValue(ageBand)) }
                    add(firstRunDisclosureToggleButton)
                    add(firstRunIntegratedConsentDisclosureText)
                    add(firstRunConsentCountText)
                    add(firstRunConsentAllButton)
                    IntegratedConsentItem.entries.forEach { item ->
                        add(firstRunConsentClauseTexts.getValue(item))
                        add(firstRunIntegratedConsentButtons.getValue(item))
                    }
                    add(firstRunIntegratedConsentSaveButton)
                    add(firstRunWaitingText)
                }
                controls.forEach { view ->
                    if (view.id == View.NO_ID) view.id = View.generateViewId()
                }
                controls.zipWithNext().forEach { (previous, current) ->
                    current.accessibilityTraversalAfter = previous.id
                }
                controls.filter(View::isFocusable).zipWithNext().forEach { (previous, current) ->
                    previous.nextFocusForwardId = current.id
                    previous.nextFocusDownId = current.id
                    current.nextFocusUpId = previous.id
                }

            }
        }
    }

    fun focusCurrentFirstRunStage(facts: FocusFacts, actions: FocusActions) {
        with(onboarding) {
            val stage = facts.stage
            val target = when (stage) {
                FirstRunOnboardingStage.FP004_TRAINING ->
                    if (facts.showPosture) priorityUserEducationProgressText
                    else priorityUserOnboardingStatusText
                FirstRunOnboardingStage.COMPLETE -> return
                else -> return
            }
            val firstAction = when (stage) {
                FirstRunOnboardingStage.FP004_TRAINING ->
                    if (facts.showPosture) {
                        when {
                            firstRunPhonePostureButton.isEnabled -> firstRunPhonePostureButton
                            !facts.appUsageReviewed -> priorityUserEducationButton
                            priorityUserSafePlaceButton.isEnabled -> priorityUserSafePlaceButton
                            else -> priorityUserPracticeButtons.values.firstOrNull { it.isEnabled }
                                ?: priorityUserEducationAgreeButton
                        }
                    } else priorityUserEducationButton
                FirstRunOnboardingStage.COMPLETE -> null
                else -> null
            }
            target.post {
                if (
                    !actions.isStageCurrent(stage) ||
                    !target.isShown ||
                    !actions.hasControlsScroll()
                ) return@post
                firstAction?.takeIf { it.isShown && it.isEnabled }?.requestFocus()
                target.requestRectangleOnScreen(
                    Rect(0, 0, target.width, target.height),
                    true,
                )
                target.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null,
                )
            }
        }
    }

    private fun applyStageHeadingStyle(message: String) {
        with(onboarding) {
            val separator = message.indexOf('.')
            if (separator <= 0 || separator + 1 >= message.length) {
                firstRunOnboardingStatusText.text = message
                return
            }
            val eyebrowEnd = separator + 1
            // 표시용으로만 줄을 나눈다. contentDescription 은 원문 그대로이므로 낭독은 변하지 않는다.
            val display = message.substring(0, eyebrowEnd) + "\n" +
                message.substring(eyebrowEnd).trimStart()
            val styled = SpannableString(display)
            styled.setSpan(
                RelativeSizeSpan(0.62f),
                0,
                eyebrowEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            styled.setSpan(
                ForegroundColorSpan(WS_COLOR_NOTICE_TEXT),
                0,
                eyebrowEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            styled.setSpan(
                StyleSpan(Typeface.BOLD),
                eyebrowEnd + 1,
                display.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            firstRunOnboardingStatusText.text = styled
        }
    }

    fun renderStage(facts: StageFacts) {
        with(onboarding) {
            val stageNumber = facts.stageNumber
            val stageCount = facts.stageCount
            val message = when (facts.stage) {
                FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT ->
                    "첫 실행 $stageNumber/${stageCount}단계. 로그인하거나 새 계정을 만드세요."
                FirstRunOnboardingStage.ACCOUNT_CREATED ->
                    "첫 실행 $stageNumber/${stageCount}단계. 인증번호를 입력해 계정을 만드세요."
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY ->
                    "첫 실행 $stageNumber/${stageCount}단계. 서비스 목적과 안전 한계를 확인하세요."
                FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED ->
                    "첫 실행 $stageNumber/${stageCount}단계. 연령 구간을 확인하세요."
                FirstRunOnboardingStage.INTEGRATED_CONSENT ->
                    "첫 실행 $stageNumber/${stageCount}단계. 필수·선택 동의를 확인하세요."
                FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION ->
                    "첫 실행 $stageNumber/${stageCount}단계. 본인 확인 제출 상태를 확인하세요."
                FirstRunOnboardingStage.VERIFIED_SMS ->
                    "첫 실행 $stageNumber/${stageCount}단계. 인증 확인을 기다리는 중입니다."
                FirstRunOnboardingStage.GUARDIAN_APPROVAL ->
                    "첫 실행 $stageNumber/${stageCount}단계. 보호자 확인을 기다리는 중입니다."
                FirstRunOnboardingStage.ACCOUNT_ACTIVATION ->
                    "첫 실행 $stageNumber/${stageCount}단계. 계정 활성화를 기다리는 중입니다."
                FirstRunOnboardingStage.VERIFIED_LOGIN ->
                    if (facts.emailAccountFlow) {
                        "첫 실행 $stageNumber/${stageCount}단계. 만든 계정으로 로그인하세요."
                    } else {
                        "첫 실행 $stageNumber/${stageCount}단계. 로그인 확인을 기다리는 중입니다."
                    }
                FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION ->
                    "첫 실행 $stageNumber/${stageCount}단계. 필요한 권한을 확인하세요."
                FirstRunOnboardingStage.DEVICE_CHECK ->
                    "첫 실행 $stageNumber/${stageCount}단계. 기기 기능을 점검하세요."
                FirstRunOnboardingStage.FP004_TRAINING ->
                    if (facts.showPosture) {
                        "사전 연습의 착용 방법과 사용 환경을 확인하고 안내를 끝까지 들어 주세요."
                    } else {
                        "안전 제한 안내와 연습 필요성을 차례대로 들은 뒤 동의하세요."
                    }
                FirstRunOnboardingStage.COMPLETE ->
                    if (facts.firstRunComplete) {
                        "첫 실행 $stageNumber/${stageCount}단계 완료. 첫 설정을 완료했습니다."
                    } else {
                        "첫 실행 $stageNumber/${stageCount}단계 완료 기록은 있으나 " +
                            "현재 권한·안전 상태 재확인이 필요합니다. 기능은 잠겨 있습니다."
                    }
                FirstRunOnboardingStage.BLOCKED_UNDER_14 ->
                    "첫 실행 $stageNumber/${stageCount}단계. 만 14세 미만은 가입할 수 없습니다."
            }
            firstRunOnboardingStatusText.text = message
            firstRunOnboardingStatusText.contentDescription = message
            applyStageHeadingStyle(message)
        }
    }

    fun renderConsentStage(facts: ConsentStageFacts) {
        with(onboarding) {
            firstRunPurposeButton.visibility =
                if (facts.stage == FirstRunOnboardingStage.PURPOSE_AND_SAFETY) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            firstRunPurposeButton.isEnabled =
                facts.stage == FirstRunOnboardingStage.PURPOSE_AND_SAFETY
            firstRunAgeButtons.forEach { (_, button) ->
                button.visibility =
                    if (facts.stage == FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                button.isEnabled =
                    facts.stage == FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED
            }
            val integratedConsentVisible =
                facts.stage == FirstRunOnboardingStage.INTEGRATED_CONSENT
            if (facts.disclosureTextReady) {
                // 항목마다 승인 전문의 해당 조항을 이미 담고 있으므로, 문서 전체는 요청할 때만 편다.
                firstRunIntegratedConsentDisclosureText.visibility =
                    if (integratedConsentVisible && firstRunDisclosureExpanded) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }
            if (facts.disclosureToggleReady) {
                firstRunDisclosureToggleButton.visibility =
                    if (integratedConsentVisible) View.VISIBLE else View.GONE
                firstRunDisclosureToggleButton.text =
                    if (firstRunDisclosureExpanded) "통합 동의 전문 접기" else "통합 동의 전문 보기"
                firstRunDisclosureToggleButton.contentDescription =
                    firstRunDisclosureToggleButton.text
            }
            firstRunIntegratedConsentButtons.values.forEach { button ->
                button.visibility =
                    if (integratedConsentVisible) View.VISIBLE else View.GONE
            }
            firstRunConsentCards.values.forEach { card ->
                card.visibility = if (integratedConsentVisible) View.VISIBLE else View.GONE
            }
            if (facts.countReady) {
                firstRunConsentCountText.visibility =
                    if (integratedConsentVisible) View.VISIBLE else View.GONE
            }
            if (facts.allButtonReady) {
                firstRunConsentAllButton.visibility =
                    if (integratedConsentVisible) View.VISIBLE else View.GONE
                val allGranted =
                    facts.allGranted
                val label = if (allGranted) "네 항목 모두 거부" else facts.allConsentLabel
                firstRunConsentAllButton.text = label
                firstRunConsentAllButton.contentDescription = label
            }
            if (facts.saveButtonReady) {
                firstRunIntegratedConsentSaveButton.visibility =
                    if (integratedConsentVisible) View.VISIBLE else View.GONE
            }
        }
    }

    fun renderWaiting(facts: WaitingFacts) {
        with(onboarding) {
            val waitingText = facts.text
            firstRunWaitingCard.visibility = if (waitingText == null) View.GONE else View.VISIBLE
            if (waitingText != null && firstRunWaitingText.text != waitingText) {
                firstRunWaitingText.text = waitingText
                firstRunWaitingText.contentDescription = waitingText
            }
            updateFirstRunWaitingDots(
                active = waitingText != null && facts.activityForeground,
                animationsEnabled = facts.animationsEnabled,
            )
        }
    }

    fun updateFirstRunWaitingDots(active: Boolean, animationsEnabled: Boolean) {
        with(onboarding) {
            firstRunWaitingDots.forEachIndexed { index, dot ->
                if (!active || !animationsEnabled) {
                    dot.clearAnimation()
                    dot.alpha = FIRST_RUN_WAITING_DOT_MIN_ALPHA
                    return@forEachIndexed
                }
                if (dot.animation != null) return@forEachIndexed
                dot.startAnimation(
                    AlphaAnimation(FIRST_RUN_WAITING_DOT_MIN_ALPHA, 1f).apply {
                        duration = FIRST_RUN_WAITING_DOT_PERIOD_MS
                        startOffset = index * FIRST_RUN_WAITING_DOT_STAGGER_MS
                        repeatMode = Animation.REVERSE
                        repeatCount = Animation.INFINITE
                    },
                )
            }
        }
    }

    fun renderPreparation(facts: PreparationFacts) {
        with(onboarding) {
            firstRunOnboardingControls.visibility =
                if (facts.developmentQuickStartEnabled || facts.walkScreenVisible) View.GONE else View.VISIBLE
            if (facts.priorityControlsReady) {
                val (loginUserIdInput, loginSaveButton, accountLogoutButton) = checkNotNull(legacyLogin)
                priorityUserOnboardingControls.visibility =
                    if (facts.mayTrain) View.VISIBLE else View.GONE
                priorityUserAgeButtons.values.forEach { button ->
                    button.visibility = View.GONE
                    button.isEnabled = false
                }
                loginUserIdInput.visibility = View.GONE
                loginSaveButton.visibility = View.GONE
                accountLogoutButton.visibility = View.GONE
            }
            if (facts.environmentStatusReady) {
                officialEnvironmentStatusText.visibility =
                    if (facts.showWalkPreparation && !facts.developmentGuidanceStartBypassEnabled) View.VISIBLE else View.GONE
                officialEnvironmentConfirmButton.visibility =
                    if (facts.showWalkPreparation && !facts.developmentGuidanceStartBypassEnabled) View.VISIBLE else View.GONE
                phoneMountingStatusText.visibility =
                    if (facts.showWalkPreparation && !facts.developmentGuidanceStartBypassEnabled) View.VISIBLE else View.GONE
                phoneMountingChestConfirmButton.visibility =
                    if (facts.showPhoneMountingPreparation && !facts.developmentGuidanceStartBypassEnabled &&
                        (!facts.developmentQuickStartEnabled || facts.walkState != WalkSessionState.READY)
                    ) View.VISIBLE else View.GONE
                phoneMountingNecklaceConfirmButton.visibility = View.GONE
            }
            if (facts.startupTextReady) {
                startupCapabilityText.visibility =
                    if (facts.mayCheckDevice) View.VISIBLE else View.GONE
                startupCapabilityConfirmButton.visibility =
                    if (facts.showWalkPreparation && !facts.developmentGuidanceStartBypassEnabled) View.VISIBLE else View.GONE
            }
            runtimeViews.fieldSessionLogButton?.let { fieldSessionLogButton ->
                fieldSessionLogButton.visibility =
                    if (facts.mayUseWalk) View.VISIBLE else View.GONE
            }
            if (facts.readinessControlsReady) {
                val showReadiness = facts.mayTrain || facts.mayCheckDevice || facts.showWalkPreparation
                walkReadinessControls.visibility =
                    if (showReadiness && facts.walkState != WalkSessionState.ACTIVE) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }
        }
    }

    fun renderRuntime(facts: RuntimeFacts) {
        runtimeViews.walkStatusSection?.visibility = if (facts.mayUseWalk) View.VISIBLE else View.GONE
        runtimeViews.runtimeControls?.visibility = if (facts.runtimeVisible) View.VISIBLE else View.GONE
    }

    fun renderDevelopmentControl(facts: DevelopmentFacts) {
        runtimeViews.developmentQuickStartButton?.isEnabled = facts.enabled
        runtimeViews.developmentQuickStartButton?.text = if (facts.paused) {
            "보행 재개 상태 확인"
        } else {
            "개발 기능 테스트 시작 / 다시 시도"
        }
    }

    fun renderProgress(facts: ProgressFacts) {
        with(onboarding) {
            firstRunProgressSegments.forEachIndexed { index, segment ->
                segment.setBackgroundColor(if (index < facts.stageNumber) WS_COLOR_EMPHASIS else WS_COLOR_LINE)
            }
            firstRunProgressBar.visibility = if (facts.hidden) View.GONE else View.VISIBLE
        }
    }

    fun linkPriorityUserAccessibilityTraversal() {
        with(onboarding) {
            val (loginUserIdInput, loginSaveButton, accountLogoutButton) = checkNotNull(legacyLogin)
            val controls = buildList<View> {
                add(priorityUserOnboardingStatusText)
                add(priorityUserEducationProgressText)
                add(priorityUserEducationBodyText)
                add(loginUserIdInput)
                add(loginSaveButton)
                add(accountLogoutButton)
                listOf(
                    PriorityUserAgeBand.ADULT_18_PLUS,
                    PriorityUserAgeBand.AGE_14_TO_17,
                    PriorityUserAgeBand.UNDER_14,
                ).forEach { ageBand -> add(priorityUserAgeButtons.getValue(ageBand)) }
                add(priorityUserEducationButton)
                add(priorityUserSafePlaceButton)
                PriorityUserPractice.entries.forEach { practice ->
                    add(priorityUserPracticeButtons.getValue(practice))
                }
                add(priorityUserResetButton)
            }
            controls.forEach { view ->
                if (view.id == View.NO_ID) view.id = View.generateViewId()
            }
            controls.zipWithNext().forEach { (previous, current) ->
                current.accessibilityTraversalAfter = previous.id
            }
            val focusableControls = controls.filter(View::isFocusable)
            focusableControls.zipWithNext().forEach { (previous, current) ->
                previous.nextFocusForwardId = current.id
                previous.nextFocusDownId = current.id
                current.nextFocusUpId = previous.id
            }
            loginUserIdInput.contentDescription = "교육 대상 로그인 ID 입력"
            loginSaveButton.contentDescription = "교육 대상 로그인 ID 확인"
            accountLogoutButton.contentDescription = "사용자 로그아웃"
        }
    }

    fun beginLegacyPriorityPresentation() {
        with(onboarding) {
            firstRunPhonePostureControls.visibility = View.GONE
            priorityUserEducationBodyText.visibility = View.GONE
            priorityUserEducationProgressText.visibility = View.GONE
            priorityUserPracticeNecessityButton.visibility = View.GONE
            priorityUserEducationAgreeButton.visibility = View.GONE
        }
    }

    fun updatePriorityUserOnboardingUi(facts: PriorityFacts) {
        with(onboarding) {
            val (loginUserIdInput, loginSaveButton, accountLogoutButton) = checkNotNull(legacyLogin)
            val environment = facts.accessibility
            val snapshot = facts.state
            val accountBound = facts.accountBound
            val deviceGateOpen = facts.deviceGateOpen
            val priorityUserEducationInFlight = facts.educationInFlight
            val priorityUserPracticeInFlight = facts.practiceInFlight
            val trainingDeliveryInFlight = priorityUserEducationInFlight || priorityUserPracticeInFlight != null
            val accessibilityModes = buildList {
                if (environment.screenReaderActive) add("화면읽기 사용 중")
                if (environment.largeTextEnabled) add("큰 글자 사용 중")
                if (environment.highContrastEnabled) add("고대비 사용 중")
            }.ifEmpty { listOf("기본 표시 설정") }
            val remaining = facts.remainingPracticeLabels.joinToString(", ")
            val message = buildString {
                append("전맹·저시력 사용자를 같은 우선순위로 지원합니다.\n")
                append("연령: ${snapshot.ageBand.labelKo} · ${accessibilityModes.joinToString(" · ")}\n")
                if (accountBound) {
                    if (deviceGateOpen) {
                        append(facts.notice)
                    } else {
                        append("안전교육 전에 권한과 기기 기능 점검을 다시 완료하세요.")
                    }
                } else {
                    append("먼저 교육 대상 로그인 ID를 입력하고 확인하세요.")
                }
                if (trainingDeliveryInFlight) {
                    append("\n음성 재생과 진동 연습 확인이 끝날 때까지 기다리세요.")
                }
                if (remaining.isNotBlank()) append("\n남은 연습: $remaining")
            }
            priorityUserOnboardingStatusText.text = message
            priorityUserOnboardingStatusText.contentDescription = message
            priorityUserOnboardingStatusText.setTextColor(WS_COLOR_BUTTON_TEXT)
            val panelDensity = context.resources.displayMetrics.density
            priorityUserOnboardingControls.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = WS_CORNER_RADIUS_DP * panelDensity
                setColor(WS_COLOR_NOTICE_FILL)
                setStroke(
                    ((if (environment.highContrastEnabled) 2f else 1f) * panelDensity).roundToInt(),
                    if (environment.highContrastEnabled) WS_COLOR_BUTTON_BORDER else WS_COLOR_LINE,
                )
            }
            priorityUserOnboardingControls.setPadding(
                (16f * panelDensity).roundToInt(),
                (16f * panelDensity).roundToInt(),
                (16f * panelDensity).roundToInt(),
                (16f * panelDensity).roundToInt(),
            )
            priorityUserAgeButtons.forEach { (ageBand, button) ->
                button.isEnabled = accountBound && !trainingDeliveryInFlight
                button.text = "연령: ${ageBand.labelKo}" +
                    if (snapshot.ageBand == ageBand) " · 선택됨" else ""
                button.contentDescription = button.text
            }
            val accountEligible =
                accountBound && deviceGateOpen && facts.mayActivateAccount
            priorityUserEducationButton.text = if (priorityUserPracticeInFlight != null) {
                "이번 연습 중지"
            } else if (priorityUserEducationInFlight) {
                "안전교육 듣기 중지"
            } else if (snapshot.educationReviewed) {
                "1. 안전 제한 안내 다시 듣기"
            } else {
                "1. 안전 제한 안내 듣기"
            }
            priorityUserEducationButton.contentDescription = priorityUserEducationButton.text
            priorityUserEducationButton.isEnabled = priorityUserPracticeInFlight != null || priorityUserEducationInFlight ||
                (accountEligible && !trainingDeliveryInFlight)
            priorityUserSafePlaceButton.isEnabled =
                accountEligible &&
                    snapshot.educationReviewed &&
                    !trainingDeliveryInFlight
            priorityUserPracticeButtons.forEach { (practice, button) ->
                val awaitingHazardResponse = practice == PriorityUserPractice.HAZARD_ALERT &&
                    facts.hazardResponseReady
                button.isEnabled =
                    accountEligible &&
                        snapshot.safePracticePlaceConfirmed &&
                        !snapshot.requiresPracticeRestart &&
                        snapshot.nextRequiredPractice == practice &&
                        (!trainingDeliveryInFlight || awaitingHazardResponse)
                val completed = practice in snapshot.completedPractices
                val current = snapshot.nextRequiredPractice == practice
                button.text = if (awaitingHazardResponse) PriorityUserEducationPresentation.hazardResponseActionKo else practice.actionLabelKo +
                    when {
                        completed -> " · 완료"
                        current -> " · 다음"
                        else -> " · 순서 대기"
                    }
                button.contentDescription = button.text
            }
            priorityUserResetButton.isEnabled =
                accountBound &&
                    deviceGateOpen &&
                    !trainingDeliveryInFlight &&
                    (
                        snapshot.educationReviewed ||
                            snapshot.safePracticePlaceConfirmed ||
                            snapshot.completedPractices.isNotEmpty()
                    )
            loginUserIdInput.isEnabled = !trainingDeliveryInFlight
            loginSaveButton.isEnabled = !trainingDeliveryInFlight
        }
    }

    fun updateNativeSafetyEducationUi(facts: NativeEducationFacts): Boolean = with(onboarding) {
        val (loginUserIdInput, loginSaveButton, accountLogoutButton) = checkNotNull(legacyLogin)
        val priorityUserEducationInFlight = facts.educationInFlight
        val priorityUserEducationPlaybackIsPracticeNecessity = facts.playbackIsPracticeNecessity
        val priorityUserPracticeInFlight = facts.practiceInFlight
        val state = facts.state
        val showPosture = facts.showPosture
        val showEducation = facts.showEducation
        val showAnyEducation = showPosture || showEducation
        val eligible = facts.eligible
        val playing = priorityUserEducationInFlight || priorityUserPracticeInFlight != null
        // S02-11: with TalkBack the buttons confirm what TalkBack read instead of starting app speech.
        val screenReaderReview = facts.screenReaderReview
        val educationPlaying = priorityUserEducationInFlight && !priorityUserEducationPlaybackIsPracticeNecessity
        val necessityPlaying = priorityUserEducationInFlight && priorityUserEducationPlaybackIsPracticeNecessity
        val hazardResponseReady = facts.hazardResponseReady
        priorityUserEducationBodyText.visibility = if (showAnyEducation) View.VISIBLE else View.GONE
        val body = facts.body
        if (priorityUserEducationBodyText.text.toString() != body) priorityUserEducationBodyText.text = body
        priorityUserEducationBodyText.contentDescription = body
        priorityUserEducationProgressText.visibility = if (showAnyEducation) View.VISIBLE else View.GONE
        val progress = facts.progress
        if (priorityUserEducationProgressText.text.toString() != progress) {
            priorityUserEducationProgressText.text = progress
        }
        priorityUserEducationProgressText.contentDescription = progress
        firstRunPhonePostureControls.visibility = if (showPosture) View.VISIBLE else View.GONE
        // The shared body already displays the complete posture notice spoken by TTS.
        firstRunPhonePostureText.visibility = View.GONE
        firstRunPhonePostureButton.isEnabled = showPosture && eligible && !playing && !state.usageConditionsAcknowledged
        firstRunPhonePostureButton.text = if (state.usageConditionsAcknowledged)
            "착용 방법과 사용 환경 확인 완료" else "착용 방법과 사용 환경을 이해했습니다"
        firstRunPhonePostureButton.contentDescription = firstRunPhonePostureButton.text
        priorityUserOnboardingStatusText.visibility = if (showAnyEducation) View.VISIBLE else View.GONE
        val heading = if (showPosture) "사전 연습" else "안전 교육"
        if (priorityUserOnboardingStatusText.text.toString() != heading) priorityUserOnboardingStatusText.text = heading
        priorityUserOnboardingStatusText.contentDescription = priorityUserOnboardingStatusText.text
        priorityUserAgeButtons.values.forEach { it.visibility = View.GONE }
        val practiceEligible = showPosture && eligible && state.usageConditionsAcknowledged && state.appUsageReviewed
        priorityUserSafePlaceButton.visibility = if (showPosture) View.VISIBLE else View.GONE
        priorityUserSafePlaceButton.text = if (state.safePracticePlaceConfirmed && !state.requiresPracticeRestart)
            "안전한 연습 장소 확인 완료" else "안전한 연습 장소 확인: 실제 도로가 아닌 장소에 멈춰 있습니다"
        priorityUserSafePlaceButton.contentDescription = priorityUserSafePlaceButton.text
        priorityUserSafePlaceButton.isEnabled = practiceEligible && !playing &&
            (!state.safePracticePlaceConfirmed || state.requiresPracticeRestart)
        priorityUserPracticeButtons.forEach { (practice, button) ->
            button.visibility = if (showPosture) View.VISIBLE else View.GONE
            val awaitingHazardResponse = practice == PriorityUserPractice.HAZARD_ALERT && hazardResponseReady
            val completed = !state.requiresPracticeRestart && practice in state.completedPractices
            button.text = if (awaitingHazardResponse) PriorityUserEducationPresentation.hazardResponseActionKo else {
                practice.actionLabelKo + when {
                    completed -> " · 완료"
                    state.requiresPracticeRestart -> " · 장소 확인 후 다시 연습"
                    state.nextRequiredPractice == practice -> " · 다음"
                    else -> " · 순서 대기"
                }
            }
            button.contentDescription = button.text
            button.isEnabled = practiceEligible && (awaitingHazardResponse ||
                (!playing && state.safePracticePlaceConfirmed && !state.requiresPracticeRestart &&
                    state.nextRequiredPractice == practice))
        }
        priorityUserResetButton.visibility = View.GONE
        loginUserIdInput.visibility = View.GONE
        loginSaveButton.visibility = View.GONE
        accountLogoutButton.visibility = View.GONE
        priorityUserEducationButton.visibility = if (showAnyEducation) View.VISIBLE else View.GONE
        priorityUserPracticeNecessityButton.visibility = if (showEducation) View.VISIBLE else View.GONE
        priorityUserEducationAgreeButton.visibility = if (showAnyEducation) View.VISIBLE else View.GONE
        priorityUserEducationAgreeButton.text = if (showPosture) "완료하고 홈으로" else "안전 교육 동의하고 사전 연습으로"
        priorityUserEducationAgreeButton.contentDescription = priorityUserEducationAgreeButton.text
        priorityUserEducationButton.text = when {
            priorityUserPracticeInFlight != null -> "이번 연습 중지"
            educationPlaying -> if (showPosture) "사전 연습 듣기 중지" else "1. 안전 제한 안내 듣기 중지"
            screenReaderReview && showPosture ->
                if (state.appUsageReviewed) "사전 연습 설명 확인 완료" else "사전 연습 설명을 화면에서 모두 확인했습니다"
            screenReaderReview ->
                if (state.educationReviewed) "1. 안전 제한 안내 확인 완료" else "1. 안전 제한 안내를 화면에서 모두 확인했습니다"
            showPosture && state.appUsageReviewed -> "사전 연습 다시 듣기"
            showPosture -> "사전 연습 듣기"
            state.educationReviewed -> "1. 안전 제한 안내 다시 듣기"
            else -> "1. 안전 제한 안내 듣기"
        }
        priorityUserPracticeNecessityButton.text = when {
            necessityPlaying -> "2. 연습 필요성 듣기 중지"
            screenReaderReview ->
                if (state.practiceNecessityReviewed) "2. 연습 필요성 확인 완료" else "2. 연습 필요성을 화면에서 모두 확인했습니다"
            state.practiceNecessityReviewed -> "2. 연습 필요성 다시 듣기"
            else -> "2. 연습 필요성 듣기"
        }
        priorityUserEducationButton.contentDescription = priorityUserEducationButton.text
        priorityUserPracticeNecessityButton.contentDescription = priorityUserPracticeNecessityButton.text
        priorityUserEducationButton.isEnabled = showAnyEducation &&
            (priorityUserPracticeInFlight != null || educationPlaying || (eligible && !playing))
        priorityUserPracticeNecessityButton.isEnabled = showEducation &&
            (necessityPlaying || (eligible && state.educationReviewed && !playing))
        priorityUserEducationAgreeButton.isEnabled = eligible && if (showPosture) {
            state.usageConditionsAcknowledged && state.appUsageReviewed && state.interactivePracticeComplete
        } else {
            showEducation && state.educationReviewed && state.practiceNecessityReviewed
        }
        priorityUserEducationProgressText.accessibilityTraversalAfter = priorityUserOnboardingStatusText.id
        priorityUserEducationBodyText.accessibilityTraversalAfter = priorityUserEducationProgressText.id
        firstRunPhonePostureButton.accessibilityTraversalAfter = priorityUserEducationBodyText.id
        priorityUserPracticeNecessityButton.accessibilityTraversalAfter = priorityUserEducationButton.id
        priorityUserEducationButton.accessibilityTraversalAfter =
            if (showPosture) firstRunPhonePostureButton.id else priorityUserEducationBodyText.id
        priorityUserSafePlaceButton.accessibilityTraversalAfter = priorityUserEducationButton.id
        var previousPracticeControl: View = priorityUserSafePlaceButton
        PriorityUserPractice.entries.forEach { practice ->
            val button = priorityUserPracticeButtons.getValue(practice)
            button.accessibilityTraversalAfter = previousPracticeControl.id
            previousPracticeControl = button
        }
        priorityUserEducationAgreeButton.accessibilityTraversalAfter =
            if (showPosture) previousPracticeControl.id else priorityUserPracticeNecessityButton.id
        val enabledActions = buildList {
            if (showPosture) add(firstRunPhonePostureButton)
            add(priorityUserEducationButton)
            if (showEducation) add(priorityUserPracticeNecessityButton)
            if (showPosture) {
                add(priorityUserSafePlaceButton)
                PriorityUserPractice.entries.forEach { add(priorityUserPracticeButtons.getValue(it)) }
            }
            add(priorityUserEducationAgreeButton)
        }.filter { it.isEnabled }
        enabledActions.forEach {
            it.nextFocusForwardId = View.NO_ID
            it.nextFocusDownId = View.NO_ID
            it.nextFocusUpId = View.NO_ID
        }
        enabledActions.zipWithNext().forEach { (previous, current) ->
            previous.nextFocusForwardId = current.id
            previous.nextFocusDownId = current.id
            current.nextFocusUpId = previous.id
        }
        (showPosture || showEducation) && eligible && facts.activityForeground
    }

    fun updateFirstRunConsentSummaryUi(facts: ConsentSummaryFacts) {
        with(onboarding) {
            firstRunConsentCountText.text =
                "선택 초안: ${facts.itemCount}개 중 ${facts.draftGranted}개 허용 · ${facts.serverSummary}"
            firstRunConsentCountText.contentDescription = firstRunConsentCountText.text
        }
    }

    private companion object {
        const val FIRST_RUN_WAITING_DOT_MIN_ALPHA = 0.2f
        const val FIRST_RUN_WAITING_DOT_PERIOD_MS = 750L
        const val FIRST_RUN_WAITING_DOT_STAGGER_MS = 300L
    }
}
