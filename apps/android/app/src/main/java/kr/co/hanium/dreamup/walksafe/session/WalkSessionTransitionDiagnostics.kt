package kr.co.hanium.dreamup.walksafe.session

/** Why an admission event did not produce the outcome it asked for. Contains no token values. */
enum class WalkSessionTransitionRejection {
    WRONG_STATE,
    WRONG_RECOVERY_STAGE,
    NOT_FOREGROUND,
    STALE_EPOCH,
    WRONG_ACTION,
    STALE_TOKEN,
    READINESS_EXPIRED,
    READINESS_NOT_READY,
    UNCONFIRMED,
}

/**
 * Explains rejected start/resume/readiness admissions from the event and the resulting
 * [WalkSessionTransition] only, mirroring the guard order of [WalkSessionLifecycle]. Returns null
 * when the event was accepted or is not an admission event.
 */
object WalkSessionTransitionDiagnostics {
    fun rejectionOrNull(
        event: WalkSessionEvent,
        transition: WalkSessionTransition,
    ): WalkSessionTransitionRejection? {
        val before = transition.previous
        val after = transition.current
        return when (event) {
            is WalkSessionEvent.StartRequested -> when {
                before.state == WalkSessionState.READY && after.state == WalkSessionState.ACTIVE -> null
                before.state != WalkSessionState.READY -> WalkSessionTransitionRejection.WRONG_STATE
                !before.isForeground -> WalkSessionTransitionRejection.NOT_FOREGROUND
                event.token.readiness.plan.action != WalkSessionAction.START_WALK ->
                    WalkSessionTransitionRejection.WRONG_ACTION
                event.token.epoch != before.epoch -> WalkSessionTransitionRejection.STALE_EPOCH
                before.confirmationToken != event.token -> WalkSessionTransitionRejection.STALE_TOKEN
                transition.changed -> WalkSessionTransitionRejection.READINESS_EXPIRED
                else -> WalkSessionTransitionRejection.STALE_TOKEN
            }

            is WalkSessionEvent.ResumeConfirmationReceived -> when {
                before.state == WalkSessionState.PAUSED && after.state == WalkSessionState.ACTIVE -> null
                before.state != WalkSessionState.PAUSED -> WalkSessionTransitionRejection.WRONG_STATE
                before.recoveryStage != WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION ->
                    WalkSessionTransitionRejection.WRONG_RECOVERY_STAGE
                !before.isForeground -> WalkSessionTransitionRejection.NOT_FOREGROUND
                event.token.readiness.plan.action != WalkSessionAction.RESUME_WALK ->
                    WalkSessionTransitionRejection.WRONG_ACTION
                event.token.epoch != before.epoch -> WalkSessionTransitionRejection.STALE_EPOCH
                before.confirmationToken != event.token -> WalkSessionTransitionRejection.STALE_TOKEN
                event.confirmation == WalkSessionResumeConfirmation.CANCEL -> null
                event.confirmation != WalkSessionResumeConfirmation.START ->
                    WalkSessionTransitionRejection.UNCONFIRMED
                else -> WalkSessionTransitionRejection.READINESS_EXPIRED
            }

            is WalkSessionEvent.InitialCheckCompleted -> readinessRejection(
                before = before,
                after = after,
                readiness = event.readiness,
                expectedState = WalkSessionState.READY,
                expectedStage = null,
                expectedAction = WalkSessionAction.START_WALK,
            )

            is WalkSessionEvent.RecoveryRecheckCompleted -> readinessRejection(
                before = before,
                after = after,
                readiness = event.readiness,
                expectedState = WalkSessionState.PAUSED,
                expectedStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
                expectedAction = WalkSessionAction.RESUME_WALK,
            )

            else -> null
        }
    }

    private fun readinessRejection(
        before: WalkSessionSnapshot,
        after: WalkSessionSnapshot,
        readiness: WalkSessionReadinessSnapshot,
        expectedState: WalkSessionState,
        expectedStage: WalkSessionRecoveryStage?,
        expectedAction: WalkSessionAction,
    ): WalkSessionTransitionRejection? = when {
        before.state != expectedState -> WalkSessionTransitionRejection.WRONG_STATE
        before.recoveryStage != expectedStage -> WalkSessionTransitionRejection.WRONG_RECOVERY_STAGE
        !before.isForeground -> WalkSessionTransitionRejection.NOT_FOREGROUND
        readiness.epoch != before.epoch -> WalkSessionTransitionRejection.STALE_EPOCH
        readiness.plan.action != expectedAction -> WalkSessionTransitionRejection.WRONG_ACTION
        after.readiness == readiness -> null
        else -> WalkSessionTransitionRejection.READINESS_NOT_READY
    }
}
