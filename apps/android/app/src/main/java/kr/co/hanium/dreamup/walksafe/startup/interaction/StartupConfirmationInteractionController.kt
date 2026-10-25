package kr.co.hanium.dreamup.walksafe.startup.interaction

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.ui.DeviceCheckPresenter

/** Sole owner of notice confirmation and persistence deduplication; decision evidence remains borrowed. */
internal class StartupConfirmationInteractionController(
    private val lifecycle: () -> WalkSessionLifecycle,
    private val facts: StartupConfirmationFacts,
    private val effects: StartupConfirmationEffects,
    private val storage: StartupDecisionStorage,
) {
    @Volatile private var confirmed: WalkSafeStartupCapabilityDecision? = null
    private var pending = false
    private var retryRequiresUserAction = false
    private var lastPersisted: WalkSafeStartupCapabilityDecision? = null
    fun confirmedDecision() = confirmed
    fun writeConfirmedDecision(value: WalkSafeStartupCapabilityDecision?) { confirmed = value }
    fun confirmationPending() = pending
    fun writeConfirmationPending(value: Boolean) { pending = value }
    fun retryRequired() = retryRequiresUserAction
    fun writeRetryRequired(value: Boolean) { retryRequiresUserAction = value }

    @JvmOverloads
    fun handleStartupCapabilityConfirmAction(preferTouchResume: Boolean = false) {
        val session = lifecycle().snapshot()
        val preparesNewWalk = session.state in setOf(
            WalkSessionState.SAFE_STOP,
            WalkSessionState.ENDED,
        )
        retryRequiresUserAction = false
        if (session.state == WalkSessionState.PAUSED) {
            effects.prepareResumeRetry()
        }
        if (preparesNewWalk) {
            effects.startFreshWalk("user_requested_after_${session.state.name.lowercase(Locale.US)}")
            effects.refresh()
            return
        }
        if (facts.resourceProbeInitialized) {
            effects.logResources("explicit_recheck", facts.resourceSnapshot())
        }
        if (effects.resumePermissionRecovery()) return
        when (session.state) {
            WalkSessionState.PAUSED -> {
                if (session.recoveryStage == WalkSessionRecoveryStage.RECHECK_REQUIRED) {
                    effects.refresh()
                } else if (isStartupCapabilityConfirmed()) {
                    if (!preferTouchResume && effects.voiceResumeAvailable()) {
                        effects.refresh()
                    } else {
                        effects.confirmResumeButton()
                    }
                } else {
                    confirmStartupCapabilityDecision()
                }
            }
            else -> confirmStartupCapabilityDecision()
        }
    }

    fun confirmStartupCapabilityDecision() {
        val decision = facts.decision ?: return
        if (!decision.mayConfirmAndStart || pending) return
        val blockReason = effects.readinessBlock(decision)
        if (blockReason != null) {
            effects.requestMissingPermissions(decision)
            effects.status("보행 시작 대기", blockReason)
            return
        }
        if (lifecycle().snapshot().confirmationToken == null) {
            effects.status(
                "보행 시작 대기",
                "같은 보행 점검 세대의 필수 기능 확인이 끝나지 않았습니다.",
            )
            return
        }
        pending = true
        effects.refresh()
        val accepted = deliverStartupCapabilityNotice(
            decision = decision,
            onDelivered = { completeStartupCapabilityConfirmation(decision) },
            onFailed = { failStartupCapabilityConfirmation(decision) },
        )
        if (!accepted) failStartupCapabilityConfirmation(decision)
    }

    fun deliverStartupCapabilityNotice(
        decision: WalkSafeStartupCapabilityDecision,
        onDelivered: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean {
        if (!facts.foreground || facts.decision != decision) return false
        val generation = facts.feedbackGeneration
        val epoch = lifecycle().snapshot().epoch
        val actorId = facts.actorId
        val confirmationToken = lifecycle().snapshot().confirmationToken
        fun ownsConfirmation(): Boolean = facts.feedbackGeneration == generation &&
            facts.actorId == actorId && facts.decision == decision &&
            lifecycle().snapshot().epoch == epoch &&
            lifecycle().snapshot().confirmationToken == confirmationToken
        val deliveredOnMain = {
            effects.onUi {
                if (facts.feedbackCurrent(generation) && ownsConfirmation()) onDelivered()
            }
        }
        val failedOnMain = {
            effects.onUi {
                if (ownsConfirmation()) onFailed()
            }
        }
        // Explain the start restriction through the verified voice channel, not the still-closed
        // walking-output gate. Only a real completion may confirm the existing readiness token.
        return effects.speakNotice(
            message = decision.noticeKo,
            onCompleted = deliveredOnMain,
            onFailed = failedOnMain,
            onCancelled = failedOnMain,
            onRetired = failedOnMain,
            additionalCurrent = { facts.feedbackCurrent(generation) &&
                facts.decision == decision && facts.actorId == actorId &&
                lifecycle().snapshot().epoch == epoch },
        )
    }

    fun completeStartupCapabilityConfirmation(expected: WalkSafeStartupCapabilityDecision) {
        if (!facts.foreground || facts.decision != expected) {
            pending = false
            effects.refresh()
            return
        }
        pending = false
        retryRequiresUserAction = false
        confirmed = expected
        effects.refresh()
    }

    fun failStartupCapabilityConfirmation(expected: WalkSafeStartupCapabilityDecision) {
        if (facts.decision != expected || !pending) return
        pending = false
        confirmed = null
        retryRequiresUserAction = true
        effects.refresh()
        effects.status(
            "안전 제한 안내 확인 필요",
            "필수 안내가 전달되지 않았습니다. 음성 기능을 확인한 뒤 다시 시도하세요.",
        )
    }

    fun isStartupCapabilityConfirmed(): Boolean {
        val decision = facts.decision ?: return false
        if (facts.bypass && facts.onboardingComplete() &&
            facts.currentReporter() != null) return true
        return decision.mayConfirmAndStart && confirmed == decision
    }

    fun requireStartupCapabilityConfirmation(): Boolean {
        effects.refresh()
        if (isStartupCapabilityConfirmed()) return true
        return false
    }

    fun persistStartupCapabilityDecision(decision: WalkSafeStartupCapabilityDecision) {
        if (facts.quickStart || facts.bypass) return
        if (lastPersisted == decision) return
        lastPersisted = decision
        val profileMatch = facts.profileMatch()
        storage.write(decision, profileMatch)
    }
}
