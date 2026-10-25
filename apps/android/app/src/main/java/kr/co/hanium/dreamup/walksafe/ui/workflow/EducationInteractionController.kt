package kr.co.hanium.dreamup.walksafe.ui.workflow

import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckFeature
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.session.PriorityUserBlockReason
import kr.co.hanium.dreamup.walksafe.session.PriorityUserEducationPlayback
import kr.co.hanium.dreamup.walksafe.session.PriorityUserEducationPresentation
import kr.co.hanium.dreamup.walksafe.session.PriorityUserOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.PriorityUserOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPractice
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPracticeAttemptToken
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPracticeDeliverySignal
import kr.co.hanium.dreamup.walksafe.session.PriorityUserSupportEnvironment

/** Owns education replay, terminal recording and practice attempts, with no constructor effects. */
internal class EducationInteractionController(
    private val profiles: Profiles,
    private val admission: Admission,
    private val runtime: Runtime,
    private val effects: Effects,
) {
    data class Account(val actorId: String, val policy: PriorityUserOnboardingPolicy)
    data class LegacyAccount(val actorId: String?,
        val policy: PriorityUserOnboardingPolicy, val block: PriorityUserBlockReason?)
    data class PresentationState(val educationInFlight: Boolean, val playbackIsPracticeNecessity: Boolean,
        val practiceInFlight: PriorityUserPractice?)

    interface Profiles {
        fun nativeAccountOrNull(): Account?
        fun legacyAccount(): LegacyAccount
        fun policy(): PriorityUserOnboardingPolicy
        fun initializedPolicyOrNull(): PriorityUserOnboardingPolicy?
        fun beginMutation(): Boolean
        fun persistOrFailClosed(): Boolean
        fun actorBound(actorId: String?): Boolean
        fun sameStateOwner(actorId: String, policy: PriorityUserOnboardingPolicy): Boolean
        fun deliveryCurrent(generation: Long, currentGeneration: Long, actorId: String,
            policy: PriorityUserOnboardingPolicy): Boolean
    }
    interface Admission {
        fun showPhonePosture(): Boolean
        fun showSafetyEducation(): Boolean
        fun requireDeviceCheck(): Boolean
        fun screenReaderActive(): Boolean
        fun nativeEducationFlow(): Boolean
        fun practiceActorId(): String?
        fun supportEnvironment(): PriorityUserSupportEnvironment
        fun featureEnabled(feature: PostLoginDeviceCheckFeature): Boolean
    }
    interface FeedbackChannel {
        fun isSpeechInitializing(): Boolean
        fun speakPriorityUserTraining(message: String, onCompleted: () -> Unit, onFailed: () -> Unit,
            onCancelled: (() -> Unit)? = null): NavigationSpeechDispatchResult
        fun playPriorityUserTrainingVibration(onWindowElapsed: () -> Unit, onFailed: () -> Unit): Boolean
    }
    interface PlaybackScheduler {
        fun post(action: () -> Unit)
        fun postDelayed(action: () -> Unit, delayMs: Long)
    }
    interface Runtime {
        fun runOnUiThread(action: () -> Unit)
        fun activityClosing(): Boolean
        fun foreground(): Boolean
        fun elapsedRealtimeMs(): Long
        fun newPlaybackScheduler(): PlaybackScheduler
        fun ensureFeedbackChannel(): FeedbackChannel
        fun cancelExistingFeedback()
    }
    enum class Presentation { NATIVE, PRIORITY, FIRST_RUN }
    interface Effects {
        fun showStatus(status: String, detail: String)
        fun refresh(presentation: Presentation)
        fun refreshPriorityIfBound()
        fun refreshStartupIfInitialized()
        fun speakInteraction(message: String)
        fun stopAccountConsentSpeech()
        fun cancelRecognitionIfActive()
        fun publishUsageConditionsAcknowledged()
        fun publishEducationAccepted(usageEducation: Boolean, actorId: String)
        fun completeFp004IfReady(actorId: String)
    }

    private var priorityUserEducationInFlight = false
    private var priorityUserEducationPlaybackIsPracticeNecessity = false
    private var priorityUserPracticeInFlight: PriorityUserPractice? = null
    private var priorityUserPracticeAttemptToken: PriorityUserPracticeAttemptToken? = null
    private var priorityUserTrainingGeneration = 0L

    fun presentationState() = PresentationState(priorityUserEducationInFlight,
        priorityUserEducationPlaybackIsPracticeNecessity, priorityUserPracticeInFlight)

    fun hazardResponseReady(policy: PriorityUserOnboardingPolicy): Boolean =
        priorityUserPracticeAttemptToken?.let(policy::isHazardResponseReady) == true

    private fun isPriorityUserTrainingDeliveryCurrent(generation: Long, actorId: String,
        policy: PriorityUserOnboardingPolicy): Boolean =
        profiles.deliveryCurrent(generation, priorityUserTrainingGeneration, actorId, policy)

    fun playNativeSafetyEducation(practiceNecessity: Boolean) {
        if (priorityUserEducationInFlight) {
            if (practiceNecessity == priorityUserEducationPlaybackIsPracticeNecessity) {
                cancelPendingPriorityUserTrainingFeedback()
                effects.showStatus(
                    "교육 듣기 중지",
                    "이번 재생은 완료로 기록하지 않습니다. 이미 완료한 청취 기록은 유지됩니다.",
                )
            }
            return
        }
        val usageEducation = admission.showPhonePosture()
        if ((!admission.showSafetyEducation() && !usageEducation) ||
            (usageEducation && practiceNecessity) || !admission.requireDeviceCheck()) return
        val account = profiles.nativeAccountOrNull() ?: return
        val actorId = account.actorId
        val policy = account.policy
        val state = policy.snapshot()
        if (!profiles.actorBound(actorId) || policy.accountBlockReason() != null ||
            priorityUserEducationInFlight || priorityUserPracticeInFlight != null ||
            (practiceNecessity && !state.educationReviewed)) return
        if (admission.screenReaderActive()) {
            // S02-11 / FP-010-DETAIL-03 interim: TalkBack reads this screen; the app voice stays off and
            // the button records the user's confirmation as a screen review (never as speech success).
            confirmNativeEducationWithScreenReader(policy, usageEducation, practiceNecessity)
            return
        }
        val usageToken = if (usageEducation) policy.beginAppUsageEducationPlayback() ?: return else null
        effects.stopAccountConsentSpeech()
        effects.cancelRecognitionIfActive()
        val generation = ++priorityUserTrainingGeneration
        priorityUserEducationPlaybackIsPracticeNecessity = practiceNecessity
        priorityUserEducationInFlight = true
        effects.refresh(Presentation.NATIVE)
        val failed = {
            usageToken?.let { policy.finishAppUsageEducationPlayback(it, completed = false) }
            failPriorityUserTrainingDelivery(
                generation = generation,
                actorId = actorId,
                policy = policy,
                token = null,
                detail = "안내를 끝까지 재생하지 못했습니다. 다시 듣기를 눌러 주세요. 이미 완료한 청취 기록은 유지됩니다.",
            )
        }
        val playback = when {
            usageEducation -> PriorityUserEducationPlayback.APP_USAGE
            practiceNecessity -> PriorityUserEducationPlayback.PRACTICE_NECESSITY
            else -> PriorityUserEducationPlayback.SAFETY_LIMITS
        }
        val message = PriorityUserEducationPresentation.speechText(playback)
        val segments = PriorityUserEducationPresentation.speechSegments(message)
        var segmentIndex = 0
        val actuator = runtime.ensureFeedbackChannel()
        val initializationDeadlineMs = runtime.elapsedRealtimeMs() +
            AndroidFeedbackActuator.INITIALIZATION_READINESS_TIMEOUT_MS
        val playbackHandler = runtime.newPlaybackScheduler()
        fun dispatchWhenReady() {
            if (runtime.activityClosing()) return
            if (!runtime.foreground() ||
                !isPriorityUserTrainingDeliveryCurrent(generation, actorId, policy) ||
                !priorityUserEducationInFlight) {
                clearPriorityUserTrainingDeliveryIfOwned(generation, actorId, policy, null)
                return
            }
            if (actuator.isSpeechInitializing()) {
                if (runtime.elapsedRealtimeMs() >= initializationDeadlineMs) {
                    failed()
                } else {
                    playbackHandler.postDelayed({ dispatchWhenReady() }, 100L)
                }
                return
            }
            val dispatchedSegment = segmentIndex
            val dispatch = actuator.speakPriorityUserTraining(
                message = segments[dispatchedSegment],
                onCompleted = {
                    runtime.runOnUiThread {
                        if (!runtime.foreground() ||
                            !isPriorityUserTrainingDeliveryCurrent(generation, actorId, policy) ||
                            !priorityUserEducationInFlight) {
                            clearPriorityUserTrainingDeliveryIfOwned(generation, actorId, policy, null)
                            return@runOnUiThread
                        }
                        if (segmentIndex != dispatchedSegment) return@runOnUiThread
                        segmentIndex += 1
                        if (segmentIndex < segments.size) {
                            // A segment DONE is not completion of the whole education. Recheck
                            // actor, foreground and device ownership before the next utterance.
                            playbackHandler.post { dispatchWhenReady() }
                            return@runOnUiThread
                        }
                        if (!profiles.beginMutation()) {
                            usageToken?.let { policy.finishAppUsageEducationPlayback(it, completed = false) }
                            priorityUserTrainingGeneration += 1L
                            priorityUserEducationInFlight = false
                            effects.showStatus("청취 기록 저장 준비 실패", "완료 기록을 저장할 수 없습니다. 다시 시도하세요.")
                            effects.refresh(Presentation.NATIVE)
                            return@runOnUiThread
                        }
                        if (usageToken != null) policy.finishAppUsageEducationPlayback(usageToken, completed = true)
                        else if (practiceNecessity) policy.reviewPracticeNecessity(voicePlaybackCompleted = true)
                        else policy.reviewSafetyEducation(voicePlaybackCompleted = true)
                        priorityUserTrainingGeneration += 1L
                        priorityUserEducationInFlight = false
                        if (!profiles.persistOrFailClosed()) {
                            effects.showStatus("청취 기록 저장 실패", "완료 기록을 저장하지 못해 동의 단계를 열지 않습니다.")
                        }
                        effects.refresh(Presentation.NATIVE)
                    }
                },
                onFailed = failed,
                onCancelled = {
                    runtime.runOnUiThread { clearPriorityUserTrainingDeliveryIfOwned(generation, actorId, policy, null) }
                },
            )
            if (dispatch != NavigationSpeechDispatchResult.ACCEPTED) failed()
        }
        dispatchWhenReady()
    }

    private fun confirmNativeEducationWithScreenReader(
        policy: PriorityUserOnboardingPolicy,
        usageEducation: Boolean,
        practiceNecessity: Boolean,
    ) {
        if (!profiles.beginMutation()) return
        policy.confirmEducationReviewedWithScreenReader(
            step = when {
                usageEducation -> PriorityUserEducationPlayback.APP_USAGE
                practiceNecessity -> PriorityUserEducationPlayback.PRACTICE_NECESSITY
                else -> PriorityUserEducationPlayback.SAFETY_LIMITS
            },
            screenReaderActive = admission.screenReaderActive(),
        )
        if (!profiles.persistOrFailClosed()) {
            effects.showStatus("확인 기록 저장 실패", "화면 확인 기록을 저장하지 못해 다음 단계를 열지 않습니다. 다시 시도하세요.")
        }
        effects.refresh(Presentation.NATIVE)
    }

    fun acknowledgeNativePhonePosture() {
        if (!admission.showPhonePosture() || !admission.requireDeviceCheck()) return
        if (!profiles.beginMutation()) return
        profiles.policy().acknowledgeUsageConditions()
        if (!profiles.persistOrFailClosed()) {
            effects.showStatus("사전 연습 동의 저장 실패", "동의 내용을 저장하지 못했습니다. 다시 시도하세요.")
            effects.refresh(Presentation.FIRST_RUN)
            return
        }
        effects.publishUsageConditionsAcknowledged()
    }

    fun acceptNativeSafetyEducation() {
        val usageEducation = admission.showPhonePosture()
        if ((!admission.showSafetyEducation() && !usageEducation) ||
            !admission.requireDeviceCheck()) return
        val account = profiles.nativeAccountOrNull() ?: return
        val actorId = account.actorId
        val state = account.policy.snapshot()
        if (!profiles.actorBound(actorId) ||
            !state.educationReviewed || !state.practiceNecessityReviewed ||
            (usageEducation && (!state.usageConditionsAcknowledged || !state.appUsageReviewed ||
                !state.interactivePracticeComplete))) return
        if (!profiles.beginMutation()) return
        if (usageEducation) account.policy.acceptAppUsageEducation()
        else account.policy.acceptEducationConsent()
        if (!profiles.persistOrFailClosed()) {
            effects.showStatus("안전교육 동의 저장 실패", "동의를 저장하지 못했습니다. 다시 시도하세요.")
            effects.refresh(Presentation.FIRST_RUN)
            return
        }
        // A replay is optional once both terminal completions are already persisted.
        cancelPendingPriorityUserTrainingFeedback()
        effects.publishEducationAccepted(usageEducation, actorId)
    }

    fun reviewPriorityUserSafetyEducation() {
        if (priorityUserPracticeInFlight != null) {
            cancelPendingPriorityUserTrainingFeedback()
            effects.showStatus("이번 연습 중지", "이번 연습은 완료하지 않았습니다. 같은 연습 버튼으로 다시 시도하세요. 이전 완료 기록은 유지됩니다.")
            return
        }
        if (priorityUserEducationInFlight) {
            if (priorityUserEducationPlaybackIsPracticeNecessity) return
            cancelPendingPriorityUserTrainingFeedback()
            effects.showStatus(
                "안전교육 듣기 중지",
                "이번 재생은 완료로 기록하지 않습니다. 이미 완료한 청취 기록은 유지됩니다.",
            )
            return
        }
        if (admission.nativeEducationFlow()) {
            playNativeSafetyEducation(practiceNecessity = false)
            return
        }
        if (!admission.requireDeviceCheck()) return
        val account = profiles.legacyAccount()
        val accountBlock = account.block
        val actorId = account.actorId
        if (
            actorId == null ||
            !profiles.actorBound(actorId) ||
            accountBlock != null
        ) {
            val notice = accountBlock?.noticeKo
                ?: "먼저 교육 대상 로그인 ID를 입력하고 확인하세요."
            effects.speakInteraction(notice)
            return
        }
        if (priorityUserEducationInFlight || priorityUserPracticeInFlight != null) {
            effects.showStatus(
                "안전 제한 안내 재생 중",
                "현재 안내가 끝날 때까지 기다리세요.",
            )
            return
        }
        val policy = account.policy
        effects.stopAccountConsentSpeech()
        effects.cancelRecognitionIfActive()
        val generation = ++priorityUserTrainingGeneration
        priorityUserEducationPlaybackIsPracticeNecessity = false
        priorityUserEducationInFlight = true
        effects.refresh(Presentation.PRIORITY)
        effects.showStatus(
            "안전 제한 안내 재생 중",
            "오프라인 한국어 음성이 끝난 뒤에만 안내 완료를 기록합니다. 같은 버튼으로 중지할 수 있습니다.",
        )
        val completed = {
            runtime.runOnUiThread {
                if (
                    !isPriorityUserTrainingDeliveryCurrent(
                        generation = generation,
                        actorId = actorId,
                        policy = policy,
                    ) ||
                    !priorityUserEducationInFlight
                ) {
                    clearPriorityUserTrainingDeliveryIfOwned(
                        generation = generation,
                        actorId = actorId,
                        policy = policy,
                        token = null,
                    )
                    return@runOnUiThread
                }
                if (!profiles.beginMutation()) {
                    priorityUserTrainingGeneration += 1L
                    priorityUserEducationInFlight = false
                    effects.showStatus(
                        "안전 제한 안내 저장 실패",
                        "변경 전 안전 표식을 저장하지 못해 현재 실행에서는 계정과 보행 기능을 열지 않습니다.",
                    )
                    effects.refresh(Presentation.PRIORITY)
                    effects.refreshStartupIfInitialized()
                    return@runOnUiThread
                }
                policy.reviewSafetyEducation(
                    voicePlaybackCompleted = true,
                )
                priorityUserTrainingGeneration += 1L
                priorityUserEducationInFlight = false
                if (!profiles.persistOrFailClosed()) {
                    effects.showStatus(
                        "안전 제한 안내 저장 실패",
                        "교육 완료를 저장하지 못해 보행 기능을 열지 않습니다.",
                    )
                }
                effects.refresh(Presentation.PRIORITY)
                effects.refreshStartupIfInitialized()
            }
        }
        val failed = {
            failPriorityUserTrainingDelivery(
                generation = generation,
                actorId = actorId,
                policy = policy,
                token = null,
                detail = "음성 재생이 끝나지 않아 안내 완료를 기록하지 않았습니다. 같은 버튼을 다시 누르세요.",
            )
        }
        val dispatch = runtime.ensureFeedbackChannel().speakPriorityUserTraining(
            message = "WalkSafe는 흰지팡이와 안내견을 대신하지 않습니다. " +
                "위험 안내를 들으면 멈추세요. 실제 도로가 아닌 안전한 장소에서 먼저 연습하세요.",
            onCompleted = completed,
            onFailed = failed,
            onCancelled = {
                runtime.runOnUiThread { clearPriorityUserTrainingDeliveryIfOwned(generation, actorId, policy, null) }
            },
        )
        if (dispatch != NavigationSpeechDispatchResult.ACCEPTED) {
            failed()
        }
    }

    fun confirmPriorityUserSafePracticePlace() {
        if (!admission.requireDeviceCheck()) return
        val before = profiles.policy().snapshot()
        if (
            (before.safePracticePlaceConfirmed && !before.requiresPracticeRestart) ||
            !before.educationReviewed ||
            (admission.showPhonePosture() &&
                (!before.usageConditionsAcknowledged || !before.appUsageReviewed)) ||
            priorityUserEducationInFlight || priorityUserPracticeInFlight != null ||
            profiles.policy().accountBlockReason() != null
        ) {
            val notice = profiles.policy().evaluate(
                admission.supportEnvironment(),
            ).noticeKo
            effects.speakInteraction(notice)
            return
        }
        if (!profiles.beginMutation()) {
            effects.showStatus(
                "안전한 연습 장소 저장 실패",
                "변경 전 안전 표식을 저장하지 못해 현재 실행에서는 계정과 보행 기능을 열지 않습니다.",
            )
            effects.refresh(Presentation.PRIORITY)
            return
        }
        profiles.policy().confirmSafePracticePlace()
        val persisted = profiles.persistOrFailClosed()
        if (!persisted) {
            effects.showStatus(
                "안전한 연습 장소 저장 실패",
                "확인 상태를 저장하지 못해 연습과 보행 기능을 열지 않습니다.",
            )
        }
        effects.refresh(Presentation.PRIORITY)
        if (persisted) {
            effects.speakInteraction("안전한 장소를 확인했습니다. 위험 안내 연습 버튼을 누르세요.")
        }
    }

    fun performPriorityUserPractice(practice: PriorityUserPractice) {
        if (!admission.requireDeviceCheck()) return
        if (practice == PriorityUserPractice.HAZARD_ALERT &&
            priorityUserPracticeInFlight == PriorityUserPractice.HAZARD_ALERT) {
            confirmPriorityUserHazardResponse()
            return
        }
        val actorId = admission.practiceActorId()
        val environment = admission.supportEnvironment()
        val voiceGuidanceEnabled =
            admission.featureEnabled(PostLoginDeviceCheckFeature.VOICE_GUIDANCE)
        val hapticFeedbackEnabled =
            admission.featureEnabled(PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK)
        val before = profiles.policy().snapshot()
        val currentDecision = profiles.policy().evaluate(environment)
        if (
            actorId == null ||
            !profiles.actorBound(actorId) ||
            currentDecision.accountBlockReason != null ||
            !before.educationReviewed ||
            !before.safePracticePlaceConfirmed ||
            before.requiresPracticeRestart ||
            (admission.showPhonePosture() &&
                (!before.usageConditionsAcknowledged || !before.appUsageReviewed)) ||
            priorityUserEducationInFlight ||
            priorityUserPracticeInFlight != null
        ) {
            effects.speakInteraction(
                if (actorId == null || !profiles.actorBound(actorId)) {
                    "먼저 교육 대상 로그인 ID를 입력하고 확인하세요."
                } else {
                    currentDecision.noticeKo
                },
            )
            return
        }
        val policy = profiles.policy()
        val requiredDeliverySignals = buildSet {
            if (voiceGuidanceEnabled) add(PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED)
            if (hapticFeedbackEnabled) add(PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED)
        }
        if (requiredDeliverySignals.isEmpty()) {
            effects.showStatus("연습 안내 수단 확인 필요", "음성 또는 진동을 사용할 수 있도록 기기 점검을 다시 완료하세요.")
            return
        }
        val token = policy.beginPractice(practice, requiredDeliverySignals)
        if (token == null) {
            val next = before.nextRequiredPractice
            effects.speakInteraction(
                next?.let { "먼저 ${it.actionLabelKo} 버튼을 누르세요." }
                    ?: "필수 조작 연습을 이미 완료했습니다.",
            )
            return
        }
        val generation = ++priorityUserTrainingGeneration
        priorityUserPracticeInFlight = practice
        priorityUserPracticeAttemptToken = token
        effects.stopAccountConsentSpeech()
        effects.cancelRecognitionIfActive()
        effects.refresh(Presentation.PRIORITY)
        effects.showStatus(
            "${practice.actionLabelKo} 진행 중",
            "사용 가능한 안내 방식으로 ${practice.instructionKo}",
        )
        val failed = {
            failPriorityUserTrainingDelivery(
                generation = generation,
                actorId = actorId,
                policy = policy,
                token = token,
                detail = "음성 재생 또는 진동 요청 패턴을 끝까지 확인하지 못해 연습을 기록하지 않았습니다.",
            )
        }
        val actuator = runtime.ensureFeedbackChannel()
        if (hapticFeedbackEnabled) {
            val vibrationAccepted = actuator.playPriorityUserTrainingVibration(
                onWindowElapsed = {
                    recordPriorityUserPracticeDelivery(
                        generation = generation,
                        actorId = actorId,
                        policy = policy,
                        token = token,
                        signal =
                            PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED,
                    )
                },
                onFailed = failed,
            )
            if (!vibrationAccepted) {
                failed()
                return
            }
        }
        if (voiceGuidanceEnabled) {
            val speech = actuator.speakPriorityUserTraining(
                message = practice.instructionKo,
                onCompleted = {
                    recordPriorityUserPracticeDelivery(
                        generation = generation,
                        actorId = actorId,
                        policy = policy,
                        token = token,
                        signal = PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED,
                    )
                },
                onFailed = failed,
                onCancelled = {
                    runtime.runOnUiThread { clearPriorityUserTrainingDeliveryIfOwned(generation, actorId, policy, token) }
                },
            )
            if (speech != NavigationSpeechDispatchResult.ACCEPTED) {
                failed()
            }
        }
    }

    fun confirmPriorityUserHazardResponse() {
        val account = profiles.nativeAccountOrNull() ?: return
        val actorId = account.actorId
        val policy = account.policy
        val token = priorityUserPracticeAttemptToken ?: return
        if (priorityUserPracticeInFlight != PriorityUserPractice.HAZARD_ALERT ||
            !isPriorityUserTrainingDeliveryCurrent(priorityUserTrainingGeneration, actorId, policy) ||
            !policy.isHazardResponseReady(token)) return
        if (!profiles.beginMutation()) {
            cancelPendingPriorityUserTrainingFeedback()
            effects.showStatus("멈춤 확인 저장 실패", "연습 결과를 저장할 수 없어 이번 연습을 완료하지 않았습니다.")
            return
        }
        val after = policy.confirmHazardResponse(token)
        if (!profiles.persistOrFailClosed()) {
            cancelPendingPriorityUserTrainingFeedback()
            effects.showStatus("멈춤 확인 저장 실패", "멈춤 확인을 저장하지 못했습니다. 저장 상태를 확인한 뒤 다시 연습하세요.")
            return
        }
        if (after.hazardResponseConfirmed) completePriorityUserPracticeStep(after, actorId)
    }

    private fun recordPriorityUserPracticeDelivery(
        generation: Long,
        actorId: String,
        policy: PriorityUserOnboardingPolicy,
        token: PriorityUserPracticeAttemptToken,
        signal: PriorityUserPracticeDeliverySignal,
    ) {
        runtime.runOnUiThread {
            if (
                !isPriorityUserTrainingDeliveryCurrent(
                    generation = generation,
                    actorId = actorId,
                    policy = policy,
                )
            ) {
                clearPriorityUserTrainingDeliveryIfOwned(
                    generation = generation,
                    actorId = actorId,
                    policy = policy,
                    token = token,
                )
                return@runOnUiThread
            }
            val before = policy.snapshot()
            if (!profiles.beginMutation()) {
                priorityUserTrainingGeneration += 1L
                priorityUserPracticeInFlight = null
                priorityUserPracticeAttemptToken = null
                policy.cancelPractice(token)
                runtime.cancelExistingFeedback()
                effects.showStatus(
                    "연습 결과 저장 실패",
                    "변경 전 안전 표식을 저장하지 못해 현재 실행에서는 계정과 보행 기능을 열지 않습니다.",
                )
                effects.refresh(Presentation.PRIORITY)
                effects.refreshStartupIfInitialized()
                return@runOnUiThread
            }
            val after = policy.recordPracticeDelivery(token, signal)
            val persisted = profiles.persistOrFailClosed()
            if (!persisted) {
                priorityUserTrainingGeneration += 1L
                priorityUserPracticeInFlight = null
                priorityUserPracticeAttemptToken = null
                policy.cancelPractice(token)
                runtime.cancelExistingFeedback()
                effects.showStatus(
                    "연습 결과 저장 실패",
                    "연습 상태를 안전하게 저장하지 못해 계정과 보행 기능을 열지 않습니다.",
                )
                effects.refresh(Presentation.PRIORITY)
                effects.refreshStartupIfInitialized()
                return@runOnUiThread
            }
            if (after.completedPractices == before.completedPractices) {
                if (policy.isHazardResponseReady(token)) effects.refresh(Presentation.PRIORITY)
                return@runOnUiThread
            }
            completePriorityUserPracticeStep(after, actorId)
        }
    }

    private fun completePriorityUserPracticeStep(after: PriorityUserOnboardingSnapshot, actorId: String) {
        val completedPractice = priorityUserPracticeInFlight
        priorityUserTrainingGeneration += 1L
        priorityUserPracticeInFlight = null
        priorityUserPracticeAttemptToken = null
        effects.showStatus(
            "연습 완료",
            "${completedPractice?.actionLabelKo ?: "필수 조작 연습"}을 완료했습니다.",
        )
        effects.refresh(Presentation.PRIORITY)
        if (!admission.nativeEducationFlow()) {
            if (after.interactivePracticeComplete) effects.completeFp004IfReady(actorId)
        }
        effects.refreshStartupIfInitialized()
    }

    private fun failPriorityUserTrainingDelivery(
        generation: Long,
        actorId: String,
        policy: PriorityUserOnboardingPolicy,
        token: PriorityUserPracticeAttemptToken?,
        detail: String,
    ) {
        runtime.runOnUiThread {
            if (
                !isPriorityUserTrainingDeliveryCurrent(
                    generation = generation,
                    actorId = actorId,
                    policy = policy,
                )
            ) {
                clearPriorityUserTrainingDeliveryIfOwned(
                    generation = generation,
                    actorId = actorId,
                    policy = policy,
                    token = token,
                )
                return@runOnUiThread
            }
            priorityUserTrainingGeneration += 1L
            priorityUserEducationInFlight = false
            priorityUserPracticeInFlight = null
            priorityUserPracticeAttemptToken = null
            policy.cancelPractice(token)
            runtime.cancelExistingFeedback()
            effects.showStatus("교육·연습 다시 필요", detail)
            effects.refresh(Presentation.PRIORITY)
        }
    }

    internal fun clearPriorityUserTrainingStateIfOwned(
        generation: Long,
        actorId: String,
        policy: PriorityUserOnboardingPolicy,
        token: PriorityUserPracticeAttemptToken?,
    ): Boolean {
        if (
            generation != priorityUserTrainingGeneration ||
            !profiles.sameStateOwner(actorId, policy)
        ) return false
        priorityUserTrainingGeneration += 1L
        priorityUserEducationInFlight = false
        priorityUserPracticeInFlight = null
        priorityUserPracticeAttemptToken = null
        policy.cancelPractice(token)
        policy.cancelAppUsageEducationPlayback()
        return true
    }

    private fun clearPriorityUserTrainingDeliveryIfOwned(
        generation: Long,
        actorId: String,
        policy: PriorityUserOnboardingPolicy,
        token: PriorityUserPracticeAttemptToken?,
    ) {
        if (!clearPriorityUserTrainingStateIfOwned(generation, actorId, policy, token)) return
        runtime.cancelExistingFeedback()
        effects.showStatus(
            "교육·연습 중지",
            "로그인 또는 기기 점검 상태가 변경되어 진행 중인 안내를 취소했습니다.",
        )
        effects.refresh(Presentation.PRIORITY)
    }

    fun cancelPendingPriorityUserTrainingFeedback() {
        effects.stopAccountConsentSpeech()
        priorityUserTrainingGeneration += 1L
        priorityUserEducationInFlight = false
        priorityUserPracticeInFlight = null
        priorityUserPracticeAttemptToken = null
        profiles.initializedPolicyOrNull()?.let { policy ->
            policy.cancelPractice()
            policy.cancelAppUsageEducationPlayback()
        }
        runtime.cancelExistingFeedback()
        effects.refreshPriorityIfBound()
    }

}
