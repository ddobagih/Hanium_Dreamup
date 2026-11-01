package kr.co.hanium.dreamup.walksafe.ui

import android.Manifest
import android.view.View
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckLocationFixPolicy
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckLocationFixReason
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckLocationFixUnavailability
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckFailure
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckFeature
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckObservation
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSignal
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.device.PostLoginMetricDepthState
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityTier
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

/** Read-only device-check presentation. Probe, readiness and session decisions stay with the caller. */
internal class DeviceCheckPresenter(
    private val panel: OnboardingReadinessPanel,
    private val style: WalkSafeViewStyle,
) {
    data class DisabledFacts(
        val disabledFeatures: Set<PostLoginDeviceCheckFeature>,
        val passesFeatureGate: Boolean,
        val voiceCommandsUnavailable: Boolean,
        val metricFeatureEnabled: Boolean,
        val locationFeatureEnabled: Boolean,
        val activityRecognitionPermission: Boolean,
    )

    data class ItemsFacts(
        val state: PostLoginDeviceCheckState,
        val observation: PostLoginDeviceCheckObservation? = null,
        val microphoneAvailable: Boolean? = null,
        val gpsAvailable: Boolean? = null,
        val cameraAvailable: Boolean? = null,
        val microphonePermission: Boolean = false,
        val locationPermission: Boolean = false,
        val cameraPermission: Boolean = false,
        val cameraPipelineActive: Boolean = false,
        val cameraChecksDeferred: Boolean = false,
        val terminalCameraText: String? = null,
        val terminalMetricText: String? = null,
        val resourceWaitNotice: String? = null,
    )

    data class LocationFailureFacts(
        val reason: DeviceCheckLocationFixReason?,
        val accuracyMeters: Float?,
        val requestFailed: Boolean,
    )

    data class FailureFacts(
        val cameraPipelineFailureName: String?,
        val location: LocationFailureFacts,
        val koreanSpeechDetail: String?,
        val resourceWaitNotice: String?,
    )

    data class CapabilityFacts(
        val noticeKo: String,
        val tier: WalkSafeStartupCapabilityTier,
        val mayConfirmAndStart: Boolean,
        val hasPendingRequirements: Boolean,
        val confirmed: Boolean,
        val installableUnavailableLabels: List<String>,
        val priorityNoticeKo: String,
        val priorityMayStartWalk: Boolean,
        val officialEnvironmentReady: Boolean,
        val phoneMountingReady: Boolean,
    )

    data class CheckFacts(
        val state: PostLoginDeviceCheckState,
        val failure: PostLoginDeviceCheckFailure?,
        val passesFeatureGate: Boolean,
        val hasCurrentRestrictions: Boolean,
        val disabledFeaturesText: String,
        val itemsMessage: String,
        val failureMessage: String,
        val hasBoundSession: Boolean,
        val stage: FirstRunOnboardingStage,
        val deferredChecksRetriable: Boolean,
        val storedBindingValidationPending: Boolean,
    )

    data class SettingsFacts(
        val requiredPermissionsMissing: Boolean,
        val locationServiceEnabled: Boolean,
        val locationRequestFailed: Boolean,
        val locationFixReason: DeviceCheckLocationFixReason?,
        val koreanSpeechSettingsButtonText: String?,
    )

    data class WakeFacts(
        val signal: PostLoginDeviceCheckSignal,
        val modelReady: Boolean,
        val probeActive: Boolean,
        val startScheduled: Boolean,
    )

    data class ConfirmationFacts(
        val walkState: WalkSessionState,
        val recoveryStage: WalkSessionRecoveryStage?,
        val confirmationTokenPresent: Boolean,
        val awaitingExplicitResume: Boolean,
        val onboardingComplete: Boolean,
        val capabilityConfirmationPending: Boolean,
        val resumePromptPending: Boolean,
        val developmentQuickStartEnabled: Boolean,
        val retryRequiresUserAction: Boolean,
    )

    data class StartupFacts(
        val capability: CapabilityFacts,
        val check: CheckFacts,
        val settings: SettingsFacts,
        val wake: WakeFacts,
        val confirmation: ConfirmationFacts,
    )

    data class ButtonPresentation(val text: String, val enabled: Boolean, val visible: Boolean)

    data class StartupPresentation(
        val capabilityMessage: String,
        val deviceAction: ButtonPresentation,
        val settingsAction: ButtonPresentation,
        val voiceDataInstallVisible: Boolean,
        val wakeInstructionVisible: Boolean,
        val wakeAction: ButtonPresentation,
        val confirmationAction: ButtonPresentation?,
        val useExplicitResumeControl: Boolean,
    )

    /** Returns true only when the existing explicit-resume renderer must follow this update. */
    fun renderStartupCapability(facts: StartupFacts): Boolean {
        val presentation = startupPresentation(facts)
        if (panel.startupCapabilityText.text.toString() != presentation.capabilityMessage) {
            style.applyWsStatusText(panel.startupCapabilityText, presentation.capabilityMessage)
            panel.startupCapabilityText.contentDescription = presentation.capabilityMessage
        }
        panel.startupMetricPreflightButton.apply {
            visibility = if (presentation.deviceAction.visible) View.VISIBLE else View.GONE
            isEnabled = presentation.deviceAction.enabled
            text = presentation.deviceAction.text
            contentDescription = text
        }
        panel.postLoginDeviceCheckSettingsButton.apply {
            visibility = if (presentation.settingsAction.visible) View.VISIBLE else View.GONE
            text = presentation.settingsAction.text
            contentDescription = text
        }
        panel.voiceDataInstallButton.visibility = if (presentation.voiceDataInstallVisible) View.VISIBLE else View.GONE
        panel.postLoginDeviceCheckWakePhraseInstructionText.apply {
            visibility = if (presentation.wakeInstructionVisible) View.VISIBLE else View.GONE
            text = "호출어 시험을 누른 뒤 “길라잡이”라고 말해 주세요."
            contentDescription = text
        }
        panel.postLoginDeviceCheckWakePhraseStartButton.apply {
            visibility = if (presentation.wakeAction.visible) View.VISIBLE else View.GONE
            isEnabled = presentation.wakeAction.enabled
            text = presentation.wakeAction.text
            contentDescription = text
        }
        panel.postLoginDeviceCheckHapticQuestionText.visibility = View.GONE
        panel.postLoginDeviceCheckHapticConfirmButton.visibility = View.GONE
        panel.postLoginDeviceCheckHapticConfirmButton.isEnabled = false
        panel.postLoginDeviceCheckHapticRejectButton.visibility = View.GONE
        presentation.confirmationAction?.let { action ->
            panel.startupCapabilityConfirmButton.isEnabled = action.enabled
            panel.startupCapabilityConfirmButton.text = action.text
        }
        return presentation.useExplicitResumeControl
    }

    companion object {
        private fun Boolean?.toDeviceCheckSignal(): PostLoginDeviceCheckSignal = when (this) {
            true -> PostLoginDeviceCheckSignal.READY
            false -> PostLoginDeviceCheckSignal.UNAVAILABLE
            null -> PostLoginDeviceCheckSignal.PENDING
        }

        fun postLoginDeviceCheckDisabledFeatureText(facts: DisabledFacts): String = buildList {
            facts.disabledFeatures
                .sortedBy(PostLoginDeviceCheckFeature::ordinal)
                .forEach { feature ->
                    add(
                        when (feature) {
                            PostLoginDeviceCheckFeature.OBSTACLE_DETECTION -> "장애물 인식"
                            PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE -> "미터 거리 안내"
                            PostLoginDeviceCheckFeature.LOCATION_GUIDANCE -> "위치·경로 안내"
                            PostLoginDeviceCheckFeature.HANDS_FREE_VOICE -> if (facts.voiceCommandsUnavailable) {
                                "호출어·음성 명령"
                            } else {
                                "호출어 대기"
                            }
                            PostLoginDeviceCheckFeature.VOICE_GUIDANCE -> "음성 안내"
                            PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK -> "진동 알림"
                        },
                    )
                }
            if (
                facts.passesFeatureGate &&
                !facts.metricFeatureEnabled
            ) {
                add("카메라 기반 신고")
            }
            if (
                facts.passesFeatureGate &&
                !facts.locationFeatureEnabled
            ) {
                add("위치가 필요한 신고")
            }
            if (
                facts.passesFeatureGate &&
                !facts.activityRecognitionPermission
            ) {
                add("걸음 수 추적·정지 확인 후 대기 신고 자동 전송")
            }
        }.joinToString(", ")

        fun postLoginDeviceCheckItemsMessage(facts: ItemsFacts): String {
            val state = facts.state
            if (state == PostLoginDeviceCheckState.NOT_RUN) {
                return "항목별 상태: 점검 시작 전"
            }
            if (state == PostLoginDeviceCheckState.REQUESTING_PERMISSIONS) {
                return "항목별 상태: Android 권한 확인 중"
            }
            val observation = requireNotNull(facts.observation)
            fun combined(vararg signals: PostLoginDeviceCheckSignal): PostLoginDeviceCheckSignal = when {
                signals.any { it == PostLoginDeviceCheckSignal.UNAVAILABLE } ->
                    PostLoginDeviceCheckSignal.UNAVAILABLE
                signals.any { it == PostLoginDeviceCheckSignal.PENDING } ->
                    PostLoginDeviceCheckSignal.PENDING
                else -> PostLoginDeviceCheckSignal.READY
            }
            fun label(
                signal: PostLoginDeviceCheckSignal,
                pending: String = "검사 중",
                unavailable: String = "제한",
            ): String = when (signal) {
                PostLoginDeviceCheckSignal.PENDING -> pending
                PostLoginDeviceCheckSignal.READY -> "통과"
                PostLoginDeviceCheckSignal.UNAVAILABLE -> unavailable
            }
            val microphoneText = if (!facts.microphonePermission) {
                "제한(마이크 권한 필요)"
            } else {
                label(
                    combined(
                        facts.microphoneAvailable.toDeviceCheckSignal(),
                        observation.wakePhraseRecognition,
                    ),
                    "확인 필요",
                )
            }
            val locationText = if (!facts.locationPermission) {
                "제한(정확한 위치 권한 필요)"
            } else {
                label(
                    combined(
                        facts.gpsAvailable.toDeviceCheckSignal(),
                        observation.locationService,
                    ),
                )
            }
            val cameraPending = if (facts.cameraPipelineActive) {
                "두 모델 샘플 처리 중"
            } else {
                "검사 대기"
            }
            val cameraText = facts.terminalCameraText ?: if (
                facts.cameraChecksDeferred
            ) {
                if (facts.cameraPermission) "재점검 필요" else "권한 허용 후 재점검 필요"
            } else {
                label(
                    combined(
                        facts.cameraAvailable.toDeviceCheckSignal(),
                        observation.detector,
                        observation.cameraPipeline,
                    ),
                    cameraPending,
                )
            }
            val distanceText = facts.terminalMetricText ?: if (
                facts.cameraChecksDeferred
            ) {
                if (facts.cameraPermission) "재점검 필요" else "권한 허용 후 재점검 필요"
            } else when (observation.metricDepth) {
                PostLoginMetricDepthState.PENDING -> "지원 확인 중"
                PostLoginMetricDepthState.SUPPORTED -> "API 지원 확인"
                PostLoginMetricDepthState.AVAILABLE -> "실시간 거리 관측"
                PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED -> "제한(거리 제한 모드)"
                PostLoginMetricDepthState.UNKNOWN -> "확인 불가(AR 서비스·권한 확인)"
                PostLoginMetricDepthState.TIMED_OUT -> "제한(지원 확인 시간 초과)"
            }
            return listOf(
                "거리 API 지원\t$distanceText",
                "객체 인식(내장 이미지)\t$cameraText",
                "GPS·위치 서비스\t$locationText",
                "현재 위치 수신\t${label(observation.locationFix, "수신 대기", "수신 미확인")}",
                "한국어 음성 합성\t${label(observation.koreanTextToSpeech, "확인 중")}",
                "기기 내 호출어 인식\t$microphoneText",
                "기기 상태\t${facts.resourceWaitNotice ?: label(observation.deviceResources, "상태 확인 중", "배터리·저장공간·발열 확인 필요")}",
            ).joinToString(separator = "\n")
        }

        fun postLoginDeviceCheckPermissionLabel(permission: String): String = when (permission) {
            Manifest.permission.CAMERA -> "카메라"
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            -> "위치"
            Manifest.permission.RECORD_AUDIO -> "마이크"
            Manifest.permission.ACTIVITY_RECOGNITION -> "신체 활동"
            Manifest.permission.POST_NOTIFICATIONS -> "알림"
            else -> "필수 권한"
        }

        fun postLoginDeviceCheckFailureMessage(
            failure: PostLoginDeviceCheckFailure?,
            facts: FailureFacts,
        ): String = when (failure) {
            PostLoginDeviceCheckFailure.BACKGROUNDED ->
                "점검 중 앱이 전경을 벗어났습니다. 앱으로 돌아와 다시 점검하세요."
            PostLoginDeviceCheckFailure.MINIMUM_ANDROID_VERSION ->
                "Android 12 이상이 필요합니다."
            PostLoginDeviceCheckFailure.REQUIRED_PERMISSION ->
                "카메라·정확한 위치·마이크·신체 활동·알림 권한을 허용한 뒤 다시 점검하세요."
            PostLoginDeviceCheckFailure.VOICE_DISCLOSURE_NOT_PERSISTED ->
                "호출어 음성 처리 안내 확인을 저장하지 못했습니다. 앱 저장공간을 확인한 뒤 다시 점검하세요."
            PostLoginDeviceCheckFailure.CORE_HARDWARE_OR_SERVICE ->
                "필수 기능 중 일부를 사용할 수 없습니다. 위의 사용할 수 없는 기능 목록을 확인하세요."
            PostLoginDeviceCheckFailure.LOCATION_SERVICE_DISABLED ->
                "Android 위치 서비스를 켠 뒤 다시 점검하세요."
            PostLoginDeviceCheckFailure.LOCATION_FIX_UNAVAILABLE ->
                postLoginLocationFixFailureMessage(facts.location)
            PostLoginDeviceCheckFailure.DEVICE_RESOURCE ->
                "배터리·저장공간·발열 상태를 정상화한 뒤 다시 점검하세요."
            PostLoginDeviceCheckFailure.DETECTOR_UNAVAILABLE ->
                "기기 내 장애물 탐지 모델을 실행할 수 없습니다."
            PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE -> when (
                facts.cameraPipelineFailureName
            ) {
                "PERMISSION_OR_MODEL_UNAVAILABLE" ->
                    "카메라 권한 또는 기기 내 탐지 모델을 사용할 수 없습니다. 권한과 모델 상태를 확인하세요."
                "CAMERA_RESOURCE_BUSY" ->
                    "다른 보행 또는 카메라 점검이 실행 중입니다. 실행 중인 점검을 끝낸 뒤 다시 시도하세요."
                "BIND_FAILED" ->
                    "카메라를 열지 못했습니다. 다른 카메라 앱을 닫은 뒤 다시 점검하세요."
                "NO_VALID_FRAME" ->
                    "점검 시간 안에 유효한 카메라 영상을 받지 못했습니다. 다른 카메라 앱을 닫고 다시 시도하세요."
                "DETECTOR_FAILED" ->
                    "카메라 영상은 받았지만 기기 내 탐지 모델 실행을 확인하지 못했습니다. 앱을 다시 시작한 뒤 점검하세요."
                "COMPOSITE_RUNTIME_UNAVAILABLE" ->
                    "내장 테스트 이미지로 기본 인식과 추가 인식을 함께 실행할 설정을 확인하지 못했습니다. 발열과 기기 상태를 확인한 뒤 다시 점검하세요."
                "RELEASE_TIMED_OUT" ->
                    "카메라 점검을 끝냈지만 카메라 자원이 닫히지 않았습니다. 앱을 다시 시작한 뒤 점검하세요."
                "TIMED_OUT" ->
                    "내장 이미지의 두 모델 실행 점검 시간이 초과되었습니다. 잠시 후 다시 시도하세요."
                else ->
                    "내장 이미지로 두 인식 모델의 실행을 확인하지 못했습니다. 다시 점검하세요."
            }
            PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE ->
                requireNotNull(facts.koreanSpeechDetail)
            PostLoginDeviceCheckFailure.WAKE_PHRASE_UNAVAILABLE ->
                "기기 내 음성 모델이 ‘길라잡이’를 인식하지 못했습니다. 조용한 곳에서 다시 점검하세요."
            PostLoginDeviceCheckFailure.HAPTIC_UNAVAILABLE ->
                "진동 기능을 사용할 수 없습니다. 진동 설정과 기기 상태를 확인하세요."
            PostLoginDeviceCheckFailure.DEPTH_UNKNOWN ->
                "거리 기능 상태를 확정하지 못했습니다. 잠시 후 다시 점검하세요."
            PostLoginDeviceCheckFailure.DEPTH_TIMEOUT ->
                "거리 API 지원 확인 시간이 초과되었습니다. 앱 화면으로 돌아와 다시 점검하세요."
            PostLoginDeviceCheckFailure.CHECK_TIMEOUT ->
                facts.resourceWaitNotice?.let { "기기 점검 시간이 초과되었습니다. $it" }
                    ?: "기기 점검을 완료하지 못했습니다. 화면의 대기 중 항목을 확인하고 다시 점검하세요."
            PostLoginDeviceCheckFailure.RESULT_SAVE_FAILED ->
                "기기 점검 결과를 저장하지 못했습니다. 저장공간을 확인하고 다시 점검하세요."
            PostLoginDeviceCheckFailure.RESULT_SAVE_UNCONFIRMED ->
                "저장 결과를 확인 중입니다. 확인이 끝나기 전에는 점검 결과를 적용하지 않습니다."
            PostLoginDeviceCheckFailure.ONBOARDING_TRANSITION_REJECTED ->
                "기기 점검의 다음 단계로 진행하지 못했습니다. 현재 화면과 로그인 상태를 확인한 뒤 다시 점검하세요."
            PostLoginDeviceCheckFailure.SESSION_CHANGED,
            null,
            -> "로그인 세션이 변경되었습니다. 다시 로그인하고 점검하세요."
        }

        fun postLoginLocationFixFailureMessage(facts: LocationFailureFacts): String {
            DeviceCheckLocationFixUnavailability.entries
                .firstOrNull { it.reason == facts.reason }
                ?.let { return it.userActionKo }
            if (
                facts.requestFailed
            ) {
                return "Android 위치 요청을 시작하지 못했습니다. 앱의 정확한 위치 권한과 위치 서비스를 확인한 뒤 다시 점검하세요."
            }
            return when (facts.reason) {
                DeviceCheckLocationFixReason.MOCK_LOCATION ->
                    "모의 위치가 감지되어 통과시키지 않았습니다. 개발자 옵션의 모의 위치 앱을 끄고 다시 점검하세요."
                DeviceCheckLocationFixReason.ACCURACY_OUTSIDE_FUNCTIONAL_RANGE -> {
                    val accuracy = facts.accuracyMeters
                        ?.takeIf { it.isFinite() }
                        ?.let { String.format(Locale.US, "%.0f", it) }
                        ?: "확인 불가"
                    "새 위치는 받았지만 추정 오차 범위가 약 ${accuracy}미터로 기능 점검 기준 " +
                        "${DeviceCheckLocationFixPolicy.MAX_ACCURACY_METERS.toInt()}미터를 넘었습니다. " +
                        "창가나 실외에서 다시 점검하세요."
                }
                DeviceCheckLocationFixReason.STALE ->
                    "새 위치 대신 오래된 위치만 수신했습니다. 위치 서비스를 껐다 켠 뒤 창가나 실외에서 다시 점검하세요."
                DeviceCheckLocationFixReason.FUTURE_TIMESTAMP,
                DeviceCheckLocationFixReason.INVALID_COORDINATES,
                DeviceCheckLocationFixReason.ACCURACY_UNAVAILABLE,
                DeviceCheckLocationFixReason.ACCURACY_INVALID,
                -> "수신한 위치값이 유효하지 않았습니다. 위치 서비스를 껐다 켠 뒤 다시 점검하세요."
                DeviceCheckLocationFixReason.PASS ->
                    "위치 수신 뒤 점검 상태가 변경되었습니다. 기기 점검을 다시 실행하세요."
                DeviceCheckLocationFixReason.LOCATION_SERVICE_OFF,
                DeviceCheckLocationFixReason.PROVIDER_DISABLED,
                DeviceCheckLocationFixReason.NO_FIX_RECEIVED,
                -> DeviceCheckLocationFixUnavailability.entries
                    .first { it.reason == facts.reason }
                    .userActionKo
                null ->
                    "점검 시간 안에 새 위치를 받지 못했습니다. 정확한 위치를 켜고 창가나 실외에서 다시 점검하세요."
            }
        }

        fun startupPresentation(facts: StartupFacts): StartupPresentation {
            val capability = facts.capability
            val check = facts.check
            val confirmation = facts.confirmation
            val capabilityMessage = buildString {
                append(capability.noticeKo)
                append("\n기기 점검: ")
                append(when (check.state) {
                    PostLoginDeviceCheckState.NOT_RUN -> "시작 전"
                    PostLoginDeviceCheckState.REQUESTING_PERMISSIONS -> "권한 확인 중"
                    PostLoginDeviceCheckState.RUNNING -> "검사 중(최대 5분 · 이후 저장 확인)"
                    PostLoginDeviceCheckState.SAVING -> "점검 결과 저장 확인 중"
                    PostLoginDeviceCheckState.FULL -> if (check.hasCurrentRestrictions) {
                        "필수 점검 완료·현재 제한(${check.disabledFeaturesText})"
                    } else {
                        "필수 점검 완료"
                    }
                    PostLoginDeviceCheckState.LIMITED -> "제한 모드 점검 완료(${check.disabledFeaturesText})"
                    PostLoginDeviceCheckState.FAIL -> check.failureMessage
                })
                append("\n")
                append(check.itemsMessage)
                if (capability.installableUnavailableLabels.isNotEmpty()) {
                    append("\n다음 행동: ")
                    append(capability.installableUnavailableLabels.joinToString(", "))
                    append("은(는) 아래 버튼에서 휴대폰 음성 설정을 열어 설치할 수 있습니다.")
                }
                if (capability.confirmed) append("\n확인 완료: WalkSafe 기능을 시작할 수 있습니다.")
                append("\n${capability.priorityNoticeKo}")
            }
            val deviceCheckStage = check.stage in setOf(
                FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
                FirstRunOnboardingStage.DEVICE_CHECK,
                FirstRunOnboardingStage.FP004_TRAINING,
                FirstRunOnboardingStage.COMPLETE,
            )
            val showDeviceCheckAction = check.hasBoundSession && deviceCheckStage &&
                (!check.passesFeatureGate || check.deferredChecksRetriable)
            val deviceAction = ButtonPresentation(
                text = when {
                    check.storedBindingValidationPending -> "저장된 기기 점검 결과 확인 중"
                    check.deferredChecksRetriable -> "카메라·거리 기능 다시 점검"
                    else -> when (check.state) {
                        PostLoginDeviceCheckState.NOT_RUN -> "권한과 기기 기능 점검 시작"
                        PostLoginDeviceCheckState.REQUESTING_PERMISSIONS -> "권한 확인 중"
                        PostLoginDeviceCheckState.RUNNING -> "기기 기능 점검 중"
                        PostLoginDeviceCheckState.SAVING -> "점검 결과 저장 확인 중"
                        PostLoginDeviceCheckState.FULL -> "기기 점검 완료"
                        PostLoginDeviceCheckState.LIMITED -> "제한 기능을 적용하고 점검 완료"
                        PostLoginDeviceCheckState.FAIL -> "기기 점검 다시 시도"
                    }
                },
                enabled = showDeviceCheckAction && !check.storedBindingValidationPending &&
                    confirmation.walkState != WalkSessionState.ACTIVE && check.state !in setOf(
                        PostLoginDeviceCheckState.REQUESTING_PERMISSIONS,
                        PostLoginDeviceCheckState.RUNNING,
                        PostLoginDeviceCheckState.SAVING,
                    ),
                visible = showDeviceCheckAction,
            )
            val settings = facts.settings
            val transientRestriction = check.passesFeatureGate &&
                (settings.requiredPermissionsMissing || !settings.locationServiceEnabled)
            val showSettings = transientRestriction ||
                (check.state == PostLoginDeviceCheckState.FAIL && check.failure in setOf(
                    PostLoginDeviceCheckFailure.REQUIRED_PERMISSION,
                    PostLoginDeviceCheckFailure.LOCATION_SERVICE_DISABLED,
                    PostLoginDeviceCheckFailure.LOCATION_FIX_UNAVAILABLE,
                ))
            val settingsAction = ButtonPresentation(
                text = when {
                    transientRestriction && !settings.requiredPermissionsMissing && !settings.locationServiceEnabled ->
                        "Android 위치 설정 열기"
                    check.failure == PostLoginDeviceCheckFailure.LOCATION_SERVICE_DISABLED -> "Android 위치 설정 열기"
                    check.failure == PostLoginDeviceCheckFailure.LOCATION_FIX_UNAVAILABLE -> when {
                        settings.locationRequestFailed -> "Android 앱 권한 설정 열기"
                        settings.locationFixReason == DeviceCheckLocationFixReason.MOCK_LOCATION -> "Android 개발자 옵션 열기"
                        else -> "Android 위치 설정 열기"
                    }
                    check.failure == PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE ->
                        requireNotNull(settings.koreanSpeechSettingsButtonText)
                    else -> "Android 앱 권한 설정 열기"
                },
                // The existing renderer did not change this control's enabled state.
                enabled = true,
                visible = showSettings,
            )
            val interactiveCheckRunning = check.state == PostLoginDeviceCheckState.RUNNING
            val needsWakePhrase = interactiveCheckRunning && facts.wake.signal == PostLoginDeviceCheckSignal.PENDING
            val wakeAction = ButtonPresentation(
                text = when {
                    facts.wake.probeActive -> "길라잡이 호출어 듣는 중"
                    !facts.wake.modelReady -> "호출어 음성 모델 준비 중"
                    else -> "호출어 테스트 시작"
                },
                enabled = needsWakePhrase && facts.wake.modelReady && !facts.wake.probeActive && !facts.wake.startScheduled,
                visible = needsWakePhrase,
            )
            val paused = confirmation.walkState == WalkSessionState.PAUSED
            val preparesNewWalk = confirmation.walkState in setOf(WalkSessionState.SAFE_STOP, WalkSessionState.ENDED)
            val resumeRecheckRequired = paused && confirmation.recoveryStage == WalkSessionRecoveryStage.RECHECK_REQUIRED
            val mayConfirmSession = confirmation.walkState in setOf(
                WalkSessionState.READY, WalkSessionState.PAUSED, WalkSessionState.SAFE_STOP, WalkSessionState.ENDED,
            )
            val confirmationTokenReady = when {
                resumeRecheckRequired -> true
                confirmation.walkState in setOf(WalkSessionState.READY, WalkSessionState.PAUSED) ->
                    confirmation.confirmationTokenPresent
                else -> true
            }
            val useExplicitResumeControl = confirmation.awaitingExplicitResume && !preparesNewWalk
            val confirmationAction = if (useExplicitResumeControl) null else ButtonPresentation(
                enabled = confirmation.onboardingComplete && mayConfirmSession &&
                    !confirmation.capabilityConfirmationPending && !confirmation.resumePromptPending && (
                        preparesNewWalk || (
                            capability.mayConfirmAndStart &&
                                (confirmation.developmentQuickStartEnabled || capability.priorityMayStartWalk) &&
                                capability.officialEnvironmentReady && capability.phoneMountingReady &&
                                confirmationTokenReady && (!capability.confirmed || paused)
                            )
                        ),
                text = when {
                    !confirmation.onboardingComplete -> "첫 실행 등록 완료 필요"
                    preparesNewWalk -> "새 보행 준비"
                    !confirmation.developmentQuickStartEnabled && !capability.priorityMayStartWalk -> "교육과 연습 완료 필요"
                    !capability.officialEnvironmentReady -> "공식 사용환경 확인 필요"
                    !capability.phoneMountingReady -> "휴대전화 장착 확인 필요"
                    resumeRecheckRequired -> "보행 재개 상태 다시 확인"
                    !confirmationTokenReady -> "보행 준비 확인 중"
                    paused && confirmation.resumePromptPending -> "보행 재개 음성 확인 중"
                    paused -> "보행 안내 재개 확인"
                    confirmation.retryRequiresUserAction -> "보행 시작 다시 시도"
                    capability.confirmed && capability.tier == WalkSafeStartupCapabilityTier.FULL -> "목적과 안전 제한 확인 완료"
                    capability.confirmed -> "기능 제한 확인 완료"
                    confirmation.capabilityConfirmationPending -> "안전 제한 음성 안내 중"
                    capability.tier == WalkSafeStartupCapabilityTier.FULL -> "목적과 안전 제한 확인 후 계속"
                    capability.tier == WalkSafeStartupCapabilityTier.LIMITED -> "기능 제한 확인 후 계속"
                    capability.hasPendingRequirements -> "기기 기능 확인 중"
                    else -> "이 휴대폰에서 시작할 수 없음"
                },
                visible = true,
            )
            return StartupPresentation(
                capabilityMessage = capabilityMessage,
                deviceAction = deviceAction,
                settingsAction = settingsAction,
                voiceDataInstallVisible = capability.installableUnavailableLabels.isNotEmpty(),
                wakeInstructionVisible = interactiveCheckRunning,
                wakeAction = wakeAction,
                confirmationAction = confirmationAction,
                useExplicitResumeControl = useExplicitResumeControl,
            )
        }
    }
}
