package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.navigation.*

/** Owns resume confirmation and admission tokens, without duplicating walk or speech state. */
internal class WalkSessionResumeController(
    private val walk: WalkSessionRuntimeAccess,
    private val readiness: WalkResumeReadiness,
    private val environment: WalkResumeEnvironment,
    private val effects: WalkResumeEffects,
) {
    private class ResumePromptOwner(val token: WalkSessionConfirmationToken, val feedbackGeneration: Int)
    private var promptOwner: ResumePromptOwner? = null
    private var deliveredPrompt: ResumePromptOwner? = null
    private var walkSessionResumePromptPending = false
    private var walkSessionResumeRetryRequiresUserAction = false
    @Volatile private var walkSessionResumeActivationToken: WalkSessionConfirmationToken? = null
    private var walkSessionResumeConfirmationToken: WalkSessionConfirmationToken? = null
    val promptPending get() = walkSessionResumePromptPending
    val awaitingResponseToDeliveredPrompt: Boolean get() = deliveredPrompt?.let(::ownsPrompt) == true
    private fun ownsPrompt(owner: ResumePromptOwner): Boolean {
        val snapshot = walk.snapshot()
        return promptOwner === owner && walkSessionResumePromptPending &&
            environment.isForeground() && snapshot.isForeground &&
            environment.isFeedbackCurrent(owner.feedbackGeneration) &&
            snapshot.state == WalkSessionState.PAUSED &&
            snapshot.recoveryStage == WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION &&
            snapshot.epoch == owner.token.epoch && snapshot.confirmationToken == owner.token &&
            walkSessionResumeConfirmationToken == owner.token
    }
    val retryRequiresUserAction get() = walkSessionResumeRetryRequiresUserAction
    val confirmationToken get() = walkSessionResumeConfirmationToken
    fun activationPending(epoch: WalkRuntimeEpoch) = walkSessionResumeActivationToken?.epoch == epoch
    fun clearActivation() { walkSessionResumeActivationToken = null }
    fun clearConfirmation() { walkSessionResumeConfirmationToken = null; deliveredPrompt = null; promptOwner = null }
    fun requireUserRetry() { walkSessionResumeRetryRequiresUserAction = true }
    fun clearPromptAfterRuntimeAdmission() { walkSessionResumePromptPending = false; deliveredPrompt = null; promptOwner = null }
    fun prepareConfirmationRetry() {
        deliveredPrompt = null; promptOwner = null
        walkSessionResumePromptPending = false
        walkSessionResumeRetryRequiresUserAction = false
    }
    fun clearResumeState() {
        deliveredPrompt = null; promptOwner = null
        walkSessionResumePromptPending = false
        walkSessionResumeRetryRequiresUserAction = false
        walkSessionResumeConfirmationToken = null
    }

    fun completePendingWalkSessionResumeActivation(expectedEpoch: WalkRuntimeEpoch) {
        val token = walkSessionResumeActivationToken ?: return
        if (token.epoch != expectedEpoch) return
        if (
            !environment.isForeground() || !environment.runtimeActive() ||
            !walk.isRuntimeEpochCurrent(expectedEpoch)
        ) {
            if (walkSessionResumeActivationToken == token) walkSessionResumeActivationToken = null
            return
        }
        if (environment.cameraHandoffPending(expectedEpoch)) return
        if (!environment.navigationOutputsAllowed()) return
        val decision = readiness.decision() ?: return
        if (readiness.blockReason(decision) != null) return
        val readiness = readiness.capture(decision, WalkSessionAction.RESUME_WALK)
        if (
            !readiness.isReady || readiness.epoch != expectedEpoch ||
            readiness.plan.mode != token.readiness.plan.mode ||
            walkSessionResumeActivationToken != token
        ) return
        completeAdmittedWalkSessionResume(token, effects::publishResumeCompletion, effects::requestNavigation)
    }

    /** Called only after the existing runtime/readiness checks admit the explicit resume token. */
    fun completeAdmittedWalkSessionResume(
        token: WalkSessionConfirmationToken,
        publishCompletion: () -> Unit,
        requestNavigation: (RoutePoint, String, Boolean, DestinationCoordinateEvidence?) -> Unit,
    ): Boolean {
        if (walkSessionResumeActivationToken != token ||
            !walk.isRuntimeEpochCurrent(token.epoch)) return false
        walkSessionResumeActivationToken = null
        walkSessionResumeRetryRequiresUserAction = false
        effects.completeAdmittedResume(token.epoch, publishCompletion, requestNavigation)
        return true
    }

    fun requestWalkSessionResumeConfirmation() {
        if (!walk.available() || walkSessionResumePromptPending) return
        val snapshot = walk.snapshot()
        if (
            snapshot.state != WalkSessionState.PAUSED ||
            snapshot.recoveryStage != WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION ||
            !snapshot.isForeground ||
            !readiness.isConfirmed()
        ) return

        if (!environment.voiceConfirmationAvailable()) {
            walkSessionResumeRetryRequiresUserAction = true
            effects.updateStatus(
                "보행 재개 확인 필요",
                "음성 확인 기능이 제한되어 있습니다. 화면의 보행 안내 재개 확인 버튼을 눌러 주세요.",
            )
            effects.enableResumeConfirmationButton()
            return
        }

        val confirmationToken = snapshot.confirmationToken ?: return
        walkSessionResumeConfirmationToken = confirmationToken
        deliveredPrompt = null; promptOwner = null
        walkSessionResumePromptPending = true
        val prompt = "재점검 뒤 진행하던 보행을 이어갈까요? 시작 또는 취소라고 말해 주세요"
        val generation = environment.feedbackGeneration()
        val owner = ResumePromptOwner(confirmationToken, generation)
        promptOwner = owner
        val delivered = {
            effects.runOnUiThread {
                if (ownsPrompt(owner) && deliveredPrompt == null) {
                    deliveredPrompt = owner
                    if (
                        !effects.startResumeRecognition()
                    ) {
                        handleWalkSessionResumeRecognizerNotStarted()
                    } else if (awaitingResponseToDeliveredPrompt) {
                        effects.enableResumeConfirmationButton()
                    }
                }
            }
        }
        val failed = {
            effects.runOnUiThread {
                if (ownsPrompt(owner) && deliveredPrompt == null) {
                    walkSessionResumePromptPending = false
                    deliveredPrompt = null; promptOwner = null
                    walkSessionResumeConfirmationToken = null
                    walkSessionResumeRetryRequiresUserAction = true
                    effects.updateStatus("보행 재개 확인 필요", "안내 음성을 재생하지 못했습니다. 보행 재개 확인 버튼으로 다시 시도해 주세요.")
                    effects.enableResumeConfirmationButton()
                }
            }
        }
        val accepted = effects.speakResume(prompt, delivered, failed)
        if (!accepted) failed()
    }

    fun handleWalkSessionResumeRecognizerNotStarted() {
        val snapshot = walk.snapshot()
        if (
            snapshot.state != WalkSessionState.PAUSED ||
            snapshot.recoveryStage != WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION
        ) return
        walkSessionResumePromptPending = false
        deliveredPrompt = null; promptOwner = null
        walkSessionResumeRetryRequiresUserAction = true
        walkSessionResumeConfirmationToken = null
        effects.updateStatus(
            "보행 안내 일시중지",
            "재개 응답을 들을 준비가 되지 않아 일시중지를 유지합니다. 재개 확인을 다시 눌러 주세요.",
        )
        effects.enableResumeConfirmationButton()
    }

    fun handleWalkSessionResumeRecognition(
        phrases: List<String>,
        confidenceScores: FloatArray? = null,
    ) {
        val confirmationToken = walkSessionResumeConfirmationToken ?: return
        val response = WalkSessionResumeConfirmation.fromRecognizedText(
            phrases, confidenceScores, effects::acceptsControlConfidence,
        )
        val currentReadiness = if (response == WalkSessionResumeConfirmation.START) {
            recheckWalkSessionResumeBeforeConfirmation(confirmationToken) ?: return
        } else confirmationToken.readiness
        walkSessionResumePromptPending = false
        deliveredPrompt = null; promptOwner = null
        walkSessionResumeConfirmationToken = null
        val transition = walk.transition(
            WalkSessionEvent.ResumeConfirmationReceived(
                confirmation = response,
                token = confirmationToken,
                currentReadiness = currentReadiness,
            ),
        )
        walkSessionResumeRetryRequiresUserAction =
            response != WalkSessionResumeConfirmation.START || !transition.changed || !environment.runtimeActive()
        if (response == WalkSessionResumeConfirmation.START && transition.changed && environment.runtimeActive()) {
            walkSessionResumeActivationToken = confirmationToken
            walkSessionResumeRetryRequiresUserAction = true
            effects.updateStatus("보행 재개 준비 중", "런타임 연결과 필수 기능을 확인하고 있습니다.")
            if (!effects.activateRuntime()) {
                if (walkSessionResumeActivationToken == confirmationToken) walkSessionResumeActivationToken = null
                walkSessionResumeRetryRequiresUserAction = true
            }
        } else {
            effects.persistInterruptionMarker()
            val detail = when (response) {
                WalkSessionResumeConfirmation.CANCEL -> "사용자가 취소해 일시중지를 유지합니다."
                WalkSessionResumeConfirmation.NO_RESPONSE -> "응답이 없어 일시중지를 유지합니다."
                WalkSessionResumeConfirmation.UNRECOGNIZED -> "재개 응답을 확인하지 못해 일시중지를 유지합니다."
                WalkSessionResumeConfirmation.START -> "재검사 상태가 바뀌어 일시중지를 유지합니다."
            }
            effects.updateStatus("보행 안내 일시중지", detail)
            effects.enableResumeConfirmationButton()
        }
    }

    fun confirmWalkSessionResumeFromButton() {
        val snapshot = walk.snapshot()
        val token = snapshot.confirmationToken ?: return
        if (
            snapshot.state != WalkSessionState.PAUSED ||
            snapshot.recoveryStage != WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION
        ) return
        val currentReadiness = recheckWalkSessionResumeBeforeConfirmation(token) ?: return
        deliveredPrompt = null; promptOwner = null
        walkSessionResumePromptPending = false
        walkSessionResumeRetryRequiresUserAction = true
        walkSessionResumeConfirmationToken = null
        val transition = walk.transition(
            WalkSessionEvent.ResumeConfirmationReceived(
                confirmation = WalkSessionResumeConfirmation.START,
                token = token,
                currentReadiness = currentReadiness,
            ),
        )
        if (transition.changed && environment.runtimeActive()) {
            walkSessionResumeActivationToken = token
            effects.updateStatus("보행 재개 준비 중", "런타임 연결과 필수 기능을 확인하고 있습니다.")
            if (!effects.activateRuntime()) {
                if (walkSessionResumeActivationToken == token) walkSessionResumeActivationToken = null
                walkSessionResumeRetryRequiresUserAction = true
            }
        }
    }

    private fun recheckWalkSessionResumeBeforeConfirmation(token: WalkSessionConfirmationToken): WalkSessionReadinessSnapshot? {
        val snapshot = walk.snapshot()
        if (
            !environment.isForeground() ||
            snapshot.state != WalkSessionState.PAUSED ||
            snapshot.recoveryStage != WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION ||
            snapshot.epoch != token.epoch || snapshot.confirmationToken != token
        ) return null
        // Runtime gates the start path checks before StartRequested (completeGatewayWalkActivation),
        // plus a pending lease revalidation, are checked before the resume transition.
        if (environment.activeLeaseOrNull(snapshot.epoch) == null) {
            effects.authorityLost("walk_lease_missing_on_resume_confirmation")
            return null
        }
        val runtimeGatesOpen =
            environment.permissionAllowsAutomaticStart() &&
                !environment.gatewayRevalidationPending() && readiness.isConfirmed()
        val decision = readiness.decision()
        val readiness = if (
            runtimeGatesOpen && decision != null && readiness.blockReason(decision) == null
        ) {
            readiness.capture(decision, WalkSessionAction.RESUME_WALK, recordResourceDiagnostics = true)
        } else null
        if (readiness != null && readiness.isReady && this.readiness.isConfirmed() &&
            readiness.epoch == token.epoch &&
            readiness.plan.action == WalkSessionAction.RESUME_WALK &&
            readiness.plan == token.readiness.plan
        ) return readiness
        effects.foregroundRecheck("resume_readiness_changed")
        walkSessionResumeRetryRequiresUserAction = true
        effects.updateStatus("보행 재개 확인 필요", "필수 기능 상태가 바뀌어 재개하지 않았습니다. 다시 확인해 주세요.")
        return null
    }
}
