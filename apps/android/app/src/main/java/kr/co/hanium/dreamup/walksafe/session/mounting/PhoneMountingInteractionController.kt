package kr.co.hanium.dreamup.walksafe.session.mounting

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*

/** Confirmation and presentation use the existing mounting session as the sole evidence owner. */
internal class PhoneMountingInteractionController<Receipt>(
    sessionSource: () -> PhoneMountingSessionController<*, Receipt>,
    private val admission: PhoneMountingInteractionAdmission,
    private val preflight: PhoneMountingInteractionPreflight,
    private val effects: PhoneMountingInteractionEffects,
    private val view: PhoneMountingInteractionView,
) {
    private val session by lazy(sessionSource)
    fun confirmPhoneMounting(method: PhoneMountingMethod) {
        if (admission.developmentBypassEnabled()) return
        if (!admission.isForeground() || !admission.lifecycleAvailable()) return
        if (!admission.requireOnboarding("phone_mounting_confirmation")) return
        if (!admission.cameraEnabled()) {
            effects.updateStatus(
                "휴대전화 장착 점검 생략",
                "카메라 분석 기능이 제한되어 관련 장착 점검을 생략합니다.",
            )
            return
        }
        val snapshot = admission.snapshot()
        val runtimeState = session.runtimeState
        val activeCorrection =
            snapshot.state == WalkSessionState.ACTIVE &&
                runtimeState?.epoch == snapshot.epoch &&
                runtimeState.correctionRequiredSinceElapsedRealtimeMs != null &&
                admission.isRuntimeEpochCurrent(snapshot.epoch)
        if (
            snapshot.state !in setOf(
                WalkSessionState.READY,
                WalkSessionState.PAUSED,
            ) &&
            !activeCorrection
        ) {
            effects.updateStatus(
                "휴대전화 장착 확인 불가",
                "새 보행 준비, 일시중지 재검사 또는 장착 교정 상태에서 다시 확인하세요.",
            )
            return
        }
        if (
            !activeCorrection &&
                admission.officialReadiness(snapshot.epoch).first !=
                WalkSessionReadinessStatus.READY
        ) {
            effects.updateStatus(
                "환경 확인 먼저 필요",
                "지원 환경 확인과 위치·카메라 측정을 먼저 통과한 뒤 휴대전화 정면 고정을 확인하세요.",
            )
            view.postFocusEnvironmentConfirmation()
            return
        }
        if (
            activeCorrection &&
            session.runtimeRetryArmedAt() != null
        ) {
            effects.updateStatus(
                "휴대전화 장착 재검사 중",
                "교정 확인 뒤의 새 카메라 결과를 기다리고 있습니다.",
            )
            return
        }
        if (
            !activeCorrection &&
            (
                preflight.cameraActive() ||
                    preflight.cameraCleanupPending()
            )
        ) {
            preflight.stopCamera(
                onReleased = { confirmPhoneMounting(method) },
            )
            return
        }
        if (!activeCorrection) {
            session.resetPrewalkCameraEvidenceForNewAttempt()
            preflight.resetReadyGrace()
            preflight.resetGuidanceProgress()
        }
        val (confirmedAtMs, mountObservation) = session.confirmExplicit(snapshot.epoch, method, activeCorrection)
        session.consumeConfirmation(mountObservation)
        if (activeCorrection) {
            session.schedulePhoneMountingRuntimeRetryTimeout(
                epoch = snapshot.epoch,
                armedAtElapsedRealtimeMs = confirmedAtMs,
            )
            updatePhoneMountingUi()
            effects.updateStatus(
                "휴대전화 장착 재검사 중",
                "교정 확인 뒤의 새 카메라 결과를 기다리고 있습니다.",
            )
            effects.speakInteraction(
                "장착 교정을 확인했습니다. 새 카메라 검사를 진행합니다.",
            )
            return
        } else {
            if (preflight.startCamera(snapshot.epoch)) {
                if (
                    admission.locationFeatureEnabled()
                ) {
                    preflight.requestGps(
                        epoch = snapshot.epoch,
                        clearExistingEvidence = false,
                    )
                }
            } else {
                preflight.markFailed()
            }
            updatePhoneMountingUi()
            effects.refreshReadinessUi()
        }
        effects.speakInteraction(
            "휴대전화가 앞을 향하도록 고정된 상태를 기록했습니다. " +
                "카메라 품질과 현재 장착 측정 기준도 통과해야 하며, " +
                "흰지팡이와 안내견을 대신하지 않습니다.",
        )
    }

    fun updatePhoneMountingUi() {
        if (
            !view.statusPublished() ||
            !view.chestPublished() ||
            !view.necklacePublished() ||
            !admission.lifecycleAvailable()
        ) return
        val snapshot = admission.snapshot()
        if (admission.developmentBypassEnabled()) {
            view.hideStatus()
            view.setChestVisible(false)
            view.hideNecklace()
            return
        }
        if (!admission.cameraEnabled()) {
            val message =
                "카메라 분석 기능이 제한되어 휴대전화 장착 점검을 생략합니다."
            view.applyStatus(message)
            view.setStatusDescription(message)
            view.setChestVisible(false)
            view.hideNecklace()
            return
        }
        val mountingChoiceVisibility = !(admission.quickStartEnabled() && snapshot.state == WalkSessionState.READY)
        view.setChestVisible(mountingChoiceVisibility)
        view.hideNecklace()
        val runtimeState = session.runtimeState
        val activeCorrection =
            snapshot.state == WalkSessionState.ACTIVE &&
                runtimeState?.epoch == snapshot.epoch &&
                runtimeState.correctionRequiredSinceElapsedRealtimeMs != null &&
                !runtimeState.safetyStopRequired
        val assessment = if (runtimeState?.epoch == snapshot.epoch) {
            session.currentPhoneMountingAssessment(
                epoch = snapshot.epoch,
                phase = PhoneMountingAssessmentPhase.ACTIVE,
                previousState = runtimeState,
            )
        } else {
            session.currentPhoneMountingAssessment(snapshot.epoch)
        }
        val message = phoneMountingStatusMessage(assessment)
        if (view.statusText() != message) {
            view.applyStatus(message)
            view.setStatusDescription(message)
        }
        val buttonsEnabled =
            admission.profileAvailable() &&
                admission.onboardingComplete() &&
                admission.isForeground() &&
                (
                    activeCorrection ||
                        admission.officialReadiness(snapshot.epoch).first ==
                        WalkSessionReadinessStatus.READY
                ) &&
                (
                    snapshot.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED) ||
                        activeCorrection
                )
        updatePhoneMountingButton(
            button = view.chestButton(),
            baseLabel = "휴대전화 정면 고정",
            enabled = buttonsEnabled,
            activeCorrection = activeCorrection,
            epoch = snapshot.epoch,
        )
    }

    private fun updatePhoneMountingButton(
        button: PhoneMountingButtonView,
        baseLabel: String,
        enabled: Boolean,
        activeCorrection: Boolean,
        epoch: WalkRuntimeEpoch,
    ) {
        val runtimeRetryPending =
            activeCorrection &&
                session.runtimeRetryArmedAt() != null
        button.enabled = enabled && !runtimeRetryPending
        button.text = when {
            runtimeRetryPending ->
                "$baseLabel 카메라 재검사 중"
            activeCorrection -> "$baseLabel 교정 완료 후 재검사"
            session.confirmation?.let {
                it.epoch == epoch &&
                    (it.method == PhoneMountingMethod.CHEST_FORWARD ||
                        it.method == PhoneMountingMethod.NECKLACE_FORWARD)
            } == true -> "$baseLabel 다시 확인"
            else -> "$baseLabel 확인"
        }
        button.contentDescription = button.text
    }

    fun phoneMountingStatusMessage(
        assessment: PhoneMountingAssessment,
    ): String {
        if (admission.developmentBypassEnabled()) return "안내 시작 전 장착·교정 점검을 생략합니다."
        val status = when (assessment.status) {
            PhoneMountingStatus.SUITABLE -> "적합"
            PhoneMountingStatus.CORRECTION_REQUIRED -> "교정 필요"
            PhoneMountingStatus.UNUSABLE -> "사용 불가"
        }
        val candidateNotice = if (
            admission.profileApprovedForProduction() == false
        ) {
            "출시 전 현장시험 후보 기준이며 정식 승인 결과가 아닙니다.\n"
        } else {
            ""
        }
        return candidateNotice +
            "휴대전화를 가슴 앞에 밀착해 세로로 고정하고 후면 카메라를 바깥으로 향하게 하세요. " +
            "손에 들거나 주머니에 넣지 마세요. " +
            "WalkSafe는 흰지팡이·안내견 등 기존 보조수단을 대신하지 않습니다.\n" +
            "$status\n원인: ${assessment.accessibleReasonKo}\n" +
            "다음 행동: ${assessment.accessibleActionKo}"
    }

    fun phoneMountingBlockReason(
        epoch: WalkRuntimeEpoch,
    ): String? {
        val (status, _) = session.phoneMountingReadiness(epoch)
        if (status == WalkSessionReadinessStatus.READY) return null
        return phoneMountingStatusMessage(session.currentPhoneMountingAssessment(epoch))
    }
}
