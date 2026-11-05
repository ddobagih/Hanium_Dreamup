package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import kr.co.hanium.dreamup.walksafe.session.FirstRunAgeBand
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.PriorityUserAgeBand
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPractice
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_BUTTON_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_EMPHASIS
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_LINE
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_FILL
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_CONTROL_GAP_DP
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_CORNER_RADIUS_DP
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_GROUP_GAP_DP
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_SECTION_GAP_DP
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_TITLE_GAP_DP
import kotlin.math.roundToInt

/** Creates registration, education and readiness controls without invoking app policy or probes. */
internal class OnboardingReadinessPanel(
    private val context: Context,
    private val style: WalkSafeViewStyle,
    props: Props,
    actions: Actions,
) {
    data class Props(
        val stageCount: Int,
        val purposeNotice: String,
        val integratedConsentDisclosure: String,
        val consentClauses: Map<IntegratedConsentItem, String>,
        val phonePostureNotice: String,
        val officialEnvironmentConfirmLabel: String,
    )

    data class Actions(
        val registration: RegistrationActions,
        val education: EducationActions,
        val readiness: ReadinessActions,
    )

    data class RegistrationActions(
        val onNoticeToggle: () -> Unit,
        val onPurposeAcknowledged: () -> Unit,
        val onAgeSelected: (FirstRunAgeBand) -> Unit,
        val onDisclosureToggle: () -> Unit,
        val onConsentToggle: (IntegratedConsentItem) -> Unit,
        val onAllConsentsToggle: () -> Unit,
        val onConsentSave: () -> Unit,
    )

    data class EducationActions(
        val onPriorityAgeSelected: (PriorityUserAgeBand) -> Unit,
        val onSafetyEducation: () -> Unit,
        val onSafePracticePlace: () -> Unit,
        val onPractice: (PriorityUserPractice) -> Unit,
        val onTrainingReset: () -> Unit,
        val onPracticeNecessity: () -> Unit,
        val onEducationAgree: () -> Unit,
    )

    data class ReadinessActions(
        val onPhonePostureAgree: () -> Unit,
        val onEnvironmentConfirm: () -> Unit,
        val onForwardMountConfirm: () -> Unit,
        val onNecklaceMountConfirm: () -> Unit,
        val onDeviceCheck: () -> Unit,
        val onDeviceSettings: () -> Unit,
        val onVoiceDataSettings: () -> Unit,
        val onWakePhraseProbe: () -> Unit,
        val onHapticConfirmed: (Boolean) -> Unit,
        val onStartupConfirmed: () -> Unit,
        val onPermissionDenialConfirmed: () -> Unit,
        val onAppSettings: () -> Unit,
        val onReadinessToggle: () -> Unit,
    )

    private val resources get() = context.resources

    val productPurposeText: TextView
    val firstRunNoticeToggleButton: Button
    val firstRunProgressBar: LinearLayout
    val firstRunOnboardingStatusText: TextView
    val firstRunPurposeButton: Button
    val firstRunIntegratedConsentDisclosureText: TextView
    val firstRunDisclosureToggleButton: Button
    val firstRunConsentCountText: TextView
    val firstRunConsentAllButton: Button
    val firstRunIntegratedConsentSaveButton: Button
    val firstRunWaitingText: TextView
    val firstRunWaitingCard: LinearLayout
    val firstRunOnboardingControls: LinearLayout
    val priorityUserOnboardingStatusText: TextView
    val priorityUserEducationProgressText: TextView
    val priorityUserEducationBodyText: TextView
    val priorityUserEducationButton: Button
    val priorityUserSafePlaceButton: Button
    val priorityUserResetButton: Button
    val priorityUserPracticeNecessityButton: Button
    val priorityUserEducationAgreeButton: Button
    val firstRunPhonePostureText: TextView
    val firstRunPhonePostureButton: Button
    val firstRunPhonePostureControls: LinearLayout
    val officialEnvironmentStatusText: TextView
    val officialEnvironmentConfirmButton: Button
    val phoneMountingStatusText: TextView
    val phoneMountingChestConfirmButton: Button
    val phoneMountingNecklaceConfirmButton: Button
    val startupCapabilityText: TextView
    val postLoginDeviceCheckLiveStatusText: TextView
    val startupMetricPreflightButton: Button
    val postLoginDeviceCheckSettingsButton: Button
    val voiceDataInstallButton: Button
    val postLoginDeviceCheckWakePhraseInstructionText: TextView
    val postLoginDeviceCheckWakePhraseStartButton: Button
    val postLoginDeviceCheckHapticQuestionText: TextView
    val postLoginDeviceCheckHapticConfirmButton: Button
    val postLoginDeviceCheckHapticRejectButton: Button
    val startupCapabilityConfirmButton: Button
    val priorityUserOnboardingControls: LinearLayout
    val permissionDenialSummaryText: TextView
    val permissionDenialConfirmButton: Button
    val permissionDenialSettingsButton: Button
    val permissionDenialPanel: LinearLayout
    val walkReadinessSummaryText: TextView
    val walkReadinessToggleButton: Button
    val nativeDeviceCheckPanel: LinearLayout
    val walkReadinessControls: LinearLayout
    private val progressSegments = mutableListOf<View>()
    val firstRunProgressSegments: List<View> get() = progressSegments
    private val ageButtons = mutableMapOf<FirstRunAgeBand, Button>()
    val firstRunAgeButtons: Map<FirstRunAgeBand, Button> get() = ageButtons
    private val integratedConsentButtons = mutableMapOf<IntegratedConsentItem, Button>()
    val firstRunIntegratedConsentButtons: Map<IntegratedConsentItem, Button> get() = integratedConsentButtons
    private val consentCards = mutableMapOf<IntegratedConsentItem, LinearLayout>()
    val firstRunConsentCards: Map<IntegratedConsentItem, LinearLayout> get() = consentCards
    private val consentClauseTexts = mutableMapOf<IntegratedConsentItem, TextView>()
    val firstRunConsentClauseTexts: Map<IntegratedConsentItem, TextView> get() = consentClauseTexts
    private val waitingDots = mutableListOf<View>()
    val firstRunWaitingDots: List<View> get() = waitingDots
    private val priorityAgeButtons = mutableMapOf<PriorityUserAgeBand, Button>()
    val priorityUserAgeButtons: Map<PriorityUserAgeBand, Button> get() = priorityAgeButtons
    private val practiceButtons = mutableMapOf<PriorityUserPractice, Button>()
    val priorityUserPracticeButtons: Map<PriorityUserPractice, Button> get() = practiceButtons
    private val deviceCheckValues = linkedMapOf<String, TextView>()
    val nativeDeviceCheckValues: Map<String, TextView> get() = deviceCheckValues

    init {
        productPurposeText = TextView(context).apply {
            text = props.purposeNotice
            textSize = 18f
            setTextColor(WS_COLOR_NOTICE_TEXT)
            setLineSpacing(0f, 1.45f)
            val density = resources.displayMetrics.density
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = WS_CORNER_RADIUS_DP * density
                setColor(WS_COLOR_NOTICE_FILL)
                setStroke((1f * density).roundToInt(), WS_COLOR_LINE)
            }
            setPadding(
                (16f * density).roundToInt(),
                (16f * density).roundToInt(),
                (16f * density).roundToInt(),
                (16f * density).roundToInt(),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (WS_SECTION_GAP_DP * density).roundToInt()
            }
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        firstRunNoticeToggleButton = style.accessiblePriorityUserButton(
            label = "안전 고지 다시 보기",
            onClick = actions.registration.onNoticeToggle,
        )
        firstRunProgressBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 같은 정보를 아래 heading 문장이 낭독하므로 접근성 트리에서 제외한다.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val density = resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (WS_TITLE_GAP_DP * density).roundToInt() }
            repeat(props.stageCount) { index ->
                val segment = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        (3f * density).roundToInt(),
                        1f,
                    ).apply {
                        if (index > 0) marginStart = (6f * density).roundToInt()
                    }
                    setBackgroundColor(WS_COLOR_LINE)
                }
                progressSegments += segment
                addView(segment)
            }
        }
        firstRunOnboardingStatusText = TextView(context).apply {
            id = View.generateViewId()
            text = "첫 실행 등록 상태를 확인하는 중입니다."
            textSize = 20f
            setTypeface(typeface, Typeface.NORMAL)
            setTextColor(WS_COLOR_EMPHASIS)
            letterSpacing = 0.0f
            setLineSpacing(0f, 1.2f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (WS_TITLE_GAP_DP * resources.displayMetrics.density).roundToInt()
                bottomMargin = (WS_GROUP_GAP_DP * resources.displayMetrics.density).roundToInt()
            }
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        ViewCompat.setAccessibilityHeading(firstRunOnboardingStatusText, true)
        firstRunPurposeButton = style.accessiblePriorityUserButton(
            label = "동의하기",
            emphasis = true,
            onClick = actions.registration.onPurposeAcknowledged,
        )
        listOf(
            FirstRunAgeBand.ADULT_18_PLUS,
            FirstRunAgeBand.AGE_14_TO_17,
            FirstRunAgeBand.UNDER_14,
        ).forEach { ageBand ->
            val label = when (ageBand) {
                FirstRunAgeBand.ADULT_18_PLUS -> "만 18세 이상"
                FirstRunAgeBand.AGE_14_TO_17 -> "만 14세 이상 18세 미만"
                FirstRunAgeBand.UNDER_14 -> "만 14세 미만"
                FirstRunAgeBand.VERIFIED_14_PLUS -> "가입 시 만 14세 이상 확인됨"
            }
            ageButtons[ageBand] = style.accessiblePriorityUserButton(
                label = label,
                spokenLabel = "가입 연령: $label",
                onClick = { actions.registration.onAgeSelected(ageBand) },
            )
        }
        firstRunIntegratedConsentDisclosureText = TextView(context).apply {
            id = View.generateViewId()
            text = props.integratedConsentDisclosure
            textSize = 18f
            setTextColor(WS_COLOR_BUTTON_TEXT)
            setLineSpacing(0f, 1.45f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (WS_CONTROL_GAP_DP * resources.displayMetrics.density).roundToInt()
            }
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        firstRunDisclosureToggleButton = style.accessiblePriorityUserButton(
            label = "통합 동의 전문 보기",
            onClick = actions.registration.onDisclosureToggle,
        )
        firstRunConsentCountText = TextView(context).apply {
            id = View.generateViewId()
            textSize = 18f
            setTextColor(WS_COLOR_NOTICE_TEXT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (WS_GROUP_GAP_DP * resources.displayMetrics.density).roundToInt()
            }
        }
        IntegratedConsentItem.entries.forEach { item ->
            val button = style.accessiblePriorityUserButton(
                label = item.name,
                onClick = { actions.registration.onConsentToggle(item) },
            )
            integratedConsentButtons[item] = button
            consentCards[item] = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val density = resources.displayMetrics.density
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = WS_CORNER_RADIUS_DP * density
                    setColor(WS_COLOR_NOTICE_FILL)
                    setStroke((1f * density).roundToInt(), WS_COLOR_LINE)
                }
                setPadding(
                    (14f * density).roundToInt(),
                    (12f * density).roundToInt(),
                    (14f * density).roundToInt(),
                    (12f * density).roundToInt(),
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = (WS_GROUP_GAP_DP * density).roundToInt()
                }
                val clause = props.consentClauses[item] ?: props.integratedConsentDisclosure
                consentClauseTexts[item] = TextView(context).apply {
                    id = View.generateViewId()
                    text = clause
                    contentDescription = clause
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    style.applyWsClauseBox(this)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin =
                            (WS_GROUP_GAP_DP * resources.displayMetrics.density).roundToInt()
                    }
                }
                addView(consentClauseTexts.getValue(item))
                addView(button)
            }
        }
        firstRunConsentAllButton = style.accessiblePriorityUserButton(
            label = INTEGRATED_CONSENT_ALL_LABEL,
            onClick = actions.registration.onAllConsentsToggle,
        )
        firstRunIntegratedConsentSaveButton = style.accessiblePriorityUserButton(
            label = "네 가지 선택을 서버에 저장하고 확인",
            emphasis = true,
            onClick = actions.registration.onConsentSave,
        )
        firstRunWaitingText = TextView(context).apply {
            id = View.generateViewId()
            textSize = 18f
            setTextColor(WS_COLOR_NOTICE_TEXT)
            setLineSpacing(0f, 1.65f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        firstRunWaitingCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = WS_CORNER_RADIUS_DP * density
                setColor(WS_COLOR_NOTICE_FILL)
                setStroke((1f * density).roundToInt(), WS_COLOR_LINE)
            }
            setPadding(
                (14f * density).roundToInt(),
                (12f * density).roundToInt(),
                (14f * density).roundToInt(),
                (12f * density).roundToInt(),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (WS_GROUP_GAP_DP * density).roundToInt()
            }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = (WS_CONTROL_GAP_DP * density).roundToInt()
                    }
                    repeat(FIRST_RUN_WAITING_DOT_COUNT) { index ->
                        val dot = View(context).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(WS_COLOR_EMPHASIS)
                            }
                            alpha = FIRST_RUN_WAITING_DOT_MIN_ALPHA
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            layoutParams = LinearLayout.LayoutParams(
                                (8f * density).roundToInt(),
                                (8f * density).roundToInt(),
                            ).apply {
                                if (index > 0) leftMargin = (8f * density).roundToInt()
                            }
                        }
                        waitingDots += dot
                        addView(dot)
                    }
                },
            )
            addView(firstRunWaitingText)
        }
        firstRunOnboardingControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(firstRunOnboardingStatusText)
            addView(productPurposeText)
            addView(firstRunPurposeButton)
        }
        priorityUserOnboardingStatusText = TextView(context).apply {
            id = View.generateViewId()
            text = "안전교육"
            textSize = 26f
            typeface = style.wsTypeface(Typeface.NORMAL)
            setTextColor(WS_COLOR_EMPHASIS)
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ViewCompat.setAccessibilityHeading(priorityUserOnboardingStatusText, true)
        priorityUserEducationProgressText = TextView(context).apply {
            id = View.generateViewId()
            textSize = 18f
            setLineSpacing(0f, 1.4f)
            setTextColor(WS_COLOR_EMPHASIS)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        priorityUserEducationBodyText = TextView(context).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            textSize = 18f
            setLineSpacing(0f, 1.4f)
            setTextColor(WS_COLOR_BUTTON_TEXT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        listOf(
            PriorityUserAgeBand.ADULT_18_PLUS,
            PriorityUserAgeBand.AGE_14_TO_17,
            PriorityUserAgeBand.UNDER_14,
        ).forEach { ageBand ->
            priorityAgeButtons[ageBand] = style.accessiblePriorityUserButton(
                label = "연령: ${ageBand.labelKo}",
                onClick = { actions.education.onPriorityAgeSelected(ageBand) },
            )
        }
        priorityUserEducationButton = style.accessiblePriorityUserButton(
            label = "1. 안전 제한 안내 듣기",
            onClick = actions.education.onSafetyEducation,
        )
        priorityUserSafePlaceButton = style.accessiblePriorityUserButton(
            label = "2. 안전한 연습 장소 확인",
            onClick = actions.education.onSafePracticePlace,
        )
        PriorityUserPractice.entries.forEachIndexed { index, practice ->
            practiceButtons[practice] = style.accessiblePriorityUserButton(
                label = "${index + 3}. ${practice.actionLabelKo}",
                onClick = { actions.education.onPractice(practice) },
            )
        }
        priorityUserResetButton = style.accessiblePriorityUserButton(
            label = "교육과 연습 다시 시작",
            onClick = actions.education.onTrainingReset,
        )
        priorityUserPracticeNecessityButton = style.accessiblePriorityUserButton(
            label = "2. 연습 필요성 듣기",
            onClick = actions.education.onPracticeNecessity,
        )
        priorityUserEducationAgreeButton = style.accessiblePriorityUserButton(
            label = "동의하기",
            emphasis = true,
            onClick = actions.education.onEducationAgree,
        )
        firstRunPhonePostureText = TextView(context).apply {
            id = View.generateViewId()
            text = props.phonePostureNotice
            textSize = 18f
            setTextColor(WS_COLOR_BUTTON_TEXT)
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        firstRunPhonePostureButton = style.accessiblePriorityUserButton(
            label = "동의하기",
            emphasis = true,
            onClick = actions.readiness.onPhonePostureAgree,
        )
        firstRunPhonePostureControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(firstRunPhonePostureText)
            addView(firstRunPhonePostureButton)
        }
        officialEnvironmentStatusText = TextView(context).apply {
            id = View.generateViewId()
            text = "공식 사용환경을 확인하는 중입니다."
            textSize = 18f
            setTextColor(WS_COLOR_EMPHASIS)
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        ViewCompat.setAccessibilityHeading(officialEnvironmentStatusText, true)
        officialEnvironmentConfirmButton = style.accessiblePriorityUserButton(
            label = props.officialEnvironmentConfirmLabel,
            onClick = actions.readiness.onEnvironmentConfirm,
        )
        phoneMountingStatusText = TextView(context).apply {
            id = View.generateViewId()
            text = "휴대전화 장착 상태를 확인하는 중입니다."
            textSize = 18f
            setTextColor(WS_COLOR_EMPHASIS)
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        ViewCompat.setAccessibilityHeading(phoneMountingStatusText, true)
        phoneMountingChestConfirmButton = style.accessiblePriorityUserButton(
            label = "휴대전화 정면 고정 확인",
            onClick = actions.readiness.onForwardMountConfirm,
        )
        phoneMountingNecklaceConfirmButton = style.accessiblePriorityUserButton(
            label = "휴대전화 정면 고정 확인",
            onClick = actions.readiness.onNecklaceMountConfirm,
        )
        startupCapabilityText = TextView(context).apply {
            text = "기기 기능을 확인하는 중입니다."
            textSize = 16f
            setTextColor(WS_COLOR_EMPHASIS)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        postLoginDeviceCheckLiveStatusText = TextView(context).apply {
            textSize = 16f
            setTextColor(WS_COLOR_EMPHASIS)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        startupMetricPreflightButton = Button(context).apply {
            text = "기기 점검 시작"
            isEnabled = false
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
            setOnClickListener { actions.readiness.onDeviceCheck() }
        }
        postLoginDeviceCheckSettingsButton = Button(context).apply {
            text = "Android 설정 열기"
            isEnabled = true
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
            setOnClickListener { actions.readiness.onDeviceSettings() }
        }
        voiceDataInstallButton = Button(context).apply {
            text = "한국어 음성 데이터 설치"
            contentDescription =
                "한국어 음성 데이터 설치 열기. 휴대폰 음성 설정으로 이동합니다."
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
            setOnClickListener { actions.readiness.onVoiceDataSettings() }
        }
        postLoginDeviceCheckWakePhraseInstructionText = TextView(context).apply {
            text = "호출어 시험 준비. 아래 버튼을 누르고 짧은 진동이 울리면 ‘길라잡이’를 한 번 말하세요."
            textSize = 18f
            setTextColor(WS_COLOR_BUTTON_TEXT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
        }
        ViewCompat.setAccessibilityHeading(postLoginDeviceCheckWakePhraseInstructionText, true)
        postLoginDeviceCheckWakePhraseStartButton = Button(context).apply {
            text = "호출어 시험"
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
            setOnClickListener { actions.readiness.onWakePhraseProbe() }
        }
        postLoginDeviceCheckHapticQuestionText = TextView(context).apply {
            text = "시험 진동이 느껴졌나요?"
            textSize = 18f
            setTextColor(WS_COLOR_BUTTON_TEXT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
        }
        ViewCompat.setAccessibilityHeading(postLoginDeviceCheckHapticQuestionText, true)
        postLoginDeviceCheckHapticConfirmButton = Button(context).apply {
            text = "예, 시험 진동이 느껴졌습니다"
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
            setOnClickListener { actions.readiness.onHapticConfirmed(true) }
        }
        postLoginDeviceCheckHapticRejectButton = Button(context).apply {
            text = "아니요, 시험 진동을 느끼지 못했습니다"
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
            setOnClickListener { actions.readiness.onHapticConfirmed(false) }
        }
        startupCapabilityConfirmButton = Button(context).apply {
            text = "기기 기능 확인 중"
            isEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { actions.readiness.onStartupConfirmed() }
        }
        priorityUserOnboardingControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(priorityUserOnboardingStatusText)
            addView(priorityUserEducationProgressText)
            addView(priorityUserEducationBodyText)
            addView(firstRunPhonePostureControls)
            addView(priorityUserEducationButton)
            addView(priorityUserPracticeNecessityButton)
            addView(priorityUserSafePlaceButton)
            PriorityUserPractice.entries.forEach { addView(practiceButtons.getValue(it)) }
            addView(priorityUserEducationAgreeButton)
        }
        permissionDenialSummaryText = TextView(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        permissionDenialConfirmButton = Button(context).apply {
            id = View.generateViewId()
            text = "확인"
            accessibilityTraversalAfter = permissionDenialSummaryText.id
            setOnClickListener { actions.readiness.onPermissionDenialConfirmed() }
        }
        permissionDenialSettingsButton = Button(context).apply {
            id = View.generateViewId()
            text = "앱 설정 열기"
            accessibilityTraversalAfter = permissionDenialConfirmButton.id
            setOnClickListener { actions.readiness.onAppSettings() }
        }
        permissionDenialPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(permissionDenialSummaryText)
            addView(permissionDenialConfirmButton)
            addView(permissionDenialSettingsButton)
        }
        walkReadinessSummaryText = TextView(context).apply {
            textSize = 18f
            setTextColor(WS_COLOR_NOTICE_TEXT)
            setLineSpacing(0f, 1.45f)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        walkReadinessToggleButton = style.accessiblePriorityUserButton(
            label = "기기 준비 상태 접기",
            spokenLabel = "기기 준비 상태, 펼침. 두 번 탭하여 접기",
            onClick = actions.readiness.onReadinessToggle,
        )
        nativeDeviceCheckPanel = buildNativeDeviceCheckPanel()
        walkReadinessControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(priorityUserOnboardingControls)
            addView(startupCapabilityText)
            addView(nativeDeviceCheckPanel)
            addView(postLoginDeviceCheckLiveStatusText)
            addView(startupMetricPreflightButton)
            addView(postLoginDeviceCheckSettingsButton)
            addView(voiceDataInstallButton)
            addView(postLoginDeviceCheckWakePhraseInstructionText)
            addView(postLoginDeviceCheckWakePhraseStartButton)
            addView(officialEnvironmentStatusText)
            addView(officialEnvironmentConfirmButton)
            addView(phoneMountingStatusText)
            addView(phoneMountingChestConfirmButton)
            addView(phoneMountingNecklaceConfirmButton)
            addView(startupCapabilityConfirmButton)
        }
    }

    fun setAccountContent(accountContent: View) {
        firstRunOnboardingControls.addView(accountContent, 2)
    }

    private fun buildNativeDeviceCheckPanel(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            style.applyNativePanelStyle(this)
            val labels = DeviceCheckItems.orderedLabels
            labels.forEachIndexed { index, label ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (index < labels.lastIndex) {
                            bottomMargin = (16f * resources.displayMetrics.density).roundToInt()
                        }
                    }
                }
                row.addView(TextView(context).apply {
                    text = label
                    textSize = 18f
                    typeface = style.wsTypeface(Typeface.NORMAL)
                    setTextColor(WS_COLOR_EMPHASIS)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val value = TextView(context).apply {
                    text = "확인 중"
                    textSize = 18f
                    typeface = style.wsTypeface(Typeface.NORMAL)
                    setTextColor(WS_COLOR_NOTICE_TEXT)
                    gravity = Gravity.END
                }
                deviceCheckValues[label] = value
                row.addView(value, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                addView(row)
            }
        }
    }


    private companion object {
        const val INTEGRATED_CONSENT_ALL_LABEL = "네 항목 모두 허용"
        const val FIRST_RUN_WAITING_DOT_COUNT = 3
        const val FIRST_RUN_WAITING_DOT_MIN_ALPHA = 0.2f
    }
}
