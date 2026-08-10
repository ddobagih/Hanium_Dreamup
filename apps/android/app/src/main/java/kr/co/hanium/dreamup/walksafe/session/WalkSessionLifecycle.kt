package kr.co.hanium.dreamup.walksafe.session

import java.util.UUID

enum class WalkSessionState {
    READY,
    ACTIVE,
    PAUSED,
    SAFE_STOP,
    ENDED,
}

enum class WalkSessionRecoveryStage {
    RECHECK_REQUIRED,
    AWAITING_RESUME_CONFIRMATION,
}

enum class WalkSessionMode {
    FULL,
    DISTANCE_LIMITED,
    UNAVAILABLE,
    ;

    val isAvailable: Boolean
        get() = this != UNAVAILABLE
}

enum class WalkSessionResumeConfirmation {
    START,
    CANCEL,
    NO_RESPONSE,
    UNRECOGNIZED,
    ;

    companion object {
        fun fromRecognizedText(text: String?): WalkSessionResumeConfirmation {
            val normalized = text?.trim().orEmpty()
            return when {
                normalized.isEmpty() -> NO_RESPONSE
                normalized == "시작" -> START
                normalized == "취소" -> CANCEL
                else -> UNRECOGNIZED
            }
        }
    }
}

sealed interface WalkSessionEvent {
    data object EnteredForeground : WalkSessionEvent

    data object EnteredBackground : WalkSessionEvent

    data object RecheckRequested : WalkSessionEvent

    data class InitialCheckCompleted(
        val readiness: WalkSessionReadinessSnapshot,
    ) : WalkSessionEvent

    data class RecoveryRecheckCompleted(
        val readiness: WalkSessionReadinessSnapshot,
    ) : WalkSessionEvent

    data class StartRequested(
        val token: WalkSessionConfirmationToken,
    ) : WalkSessionEvent

    data class ResumeConfirmationReceived(
        val confirmation: WalkSessionResumeConfirmation,
        val token: WalkSessionConfirmationToken,
    ) : WalkSessionEvent

    data class RuntimeReadinessChanged(
        val readiness: WalkSessionReadinessSnapshot,
    ) : WalkSessionEvent

    data object SafetyStopRequested : WalkSessionEvent

    data object EndRequested : WalkSessionEvent

    data object NewWalkRequested : WalkSessionEvent

    data object ProcessRestarted : WalkSessionEvent
}

data class WalkSessionSnapshot(
    val epoch: WalkRuntimeEpoch,
    val state: WalkSessionState,
    val mode: WalkSessionMode,
    val recoveryStage: WalkSessionRecoveryStage?,
    val isForeground: Boolean,
    val readiness: WalkSessionReadinessSnapshot?,
) {
    val confirmationToken: WalkSessionConfirmationToken?
        get() = readiness
            ?.takeIf { it.isReady }
            ?.let(::WalkSessionConfirmationToken)

    init {
        require((state == WalkSessionState.PAUSED) == (recoveryStage != null)) {
            "Only a paused session can have a recovery stage"
        }
        require(readiness == null || readiness.epoch == epoch) {
            "Readiness must belong to the current walk epoch"
        }
        require(
            state != WalkSessionState.ACTIVE ||
                (isForeground && mode.isAvailable && readiness?.isReady == true),
        ) {
            "An active session must be foregrounded with current ready evidence"
        }
        require(
            recoveryStage != WalkSessionRecoveryStage.RECHECK_REQUIRED || readiness == null,
        ) {
            "A session waiting for a recheck cannot retain earlier readiness"
        }
        require(
            recoveryStage != WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION ||
                readiness?.isReady == true,
        ) {
            "Resume confirmation requires current ready evidence"
        }
        require(state != WalkSessionState.ENDED || mode == WalkSessionMode.UNAVAILABLE) {
            "An ended session cannot expose a functional mode"
        }
    }
}

enum class WalkSessionTransitionReason {
    ENTERED_FOREGROUND,
    ENTERED_BACKGROUND,
    RECHECK_REQUESTED,
    INITIAL_READINESS_COMPLETED,
    START_CONFIRMED,
    RECOVERY_READINESS_COMPLETED,
    RESUME_CONFIRMATION_RECEIVED,
    RUNTIME_READINESS_CHANGED,
    SAFETY_STOP_REQUESTED,
    END_REQUESTED,
    NEW_WALK_REQUESTED,
    PROCESS_RESTARTED,
}

data class WalkSessionTransition(
    val previous: WalkSessionSnapshot,
    val current: WalkSessionSnapshot,
    val reason: WalkSessionTransitionReason,
    val occurredAtEpochMs: Long,
) {
    val changed: Boolean
        get() = previous != current
}

/**
 * Pure session lifecycle and recovery state machine for FP-017/FP-018.
 *
 * Functional mode, lifecycle state and immutable readiness are separate. Every background or
 * recovery boundary advances [WalkRuntimeEpoch.recoveryGeneration]. A restarted process receives a
 * new walk identity and cannot reuse readiness or runtime work from the previous process.
 */
class WalkSessionLifecycle(
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var current = initialSnapshot()

    @Synchronized
    fun snapshot(): WalkSessionSnapshot = current

    @Synchronized
    fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch): Boolean =
        current.state == WalkSessionState.ACTIVE &&
            current.epoch == epoch &&
            current.readiness?.isReady == true

    @Synchronized
    fun currentRuntimeEpochOrNull(): WalkRuntimeEpoch? =
        current.epoch.takeIf { isRuntimeEpochCurrent(it) }

    @Synchronized
    fun handle(event: WalkSessionEvent): WalkSessionTransition {
        val previous = current
        current = reduce(previous, event)
        return WalkSessionTransition(
            previous = previous,
            current = current,
            reason = event.transitionReason(),
            occurredAtEpochMs = clock(),
        )
    }

    private fun reduce(
        snapshot: WalkSessionSnapshot,
        event: WalkSessionEvent,
    ): WalkSessionSnapshot = when (event) {
        WalkSessionEvent.EnteredForeground -> snapshot.copy(isForeground = true)

        WalkSessionEvent.EnteredBackground -> when (snapshot.state) {
            WalkSessionState.ACTIVE,
            WalkSessionState.PAUSED,
            -> if (
                snapshot.state == WalkSessionState.PAUSED &&
                snapshot.recoveryStage == WalkSessionRecoveryStage.RECHECK_REQUIRED &&
                !snapshot.isForeground
            ) {
                snapshot
            } else {
                snapshot.copy(
                    epoch = snapshot.epoch.nextGeneration(),
                    state = WalkSessionState.PAUSED,
                    recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
                    isForeground = false,
                    readiness = null,
                )
            }

            WalkSessionState.READY -> if (!snapshot.isForeground) {
                snapshot
            } else {
                snapshot.copy(
                    epoch = snapshot.epoch.nextGeneration(),
                    mode = WalkSessionMode.UNAVAILABLE,
                    isForeground = false,
                    readiness = null,
                )
            }

            WalkSessionState.SAFE_STOP,
            WalkSessionState.ENDED,
            -> snapshot.copy(isForeground = false)
        }

        WalkSessionEvent.RecheckRequested -> when (snapshot.state) {
            WalkSessionState.ACTIVE -> snapshot.copy(
                epoch = snapshot.epoch.nextGeneration(),
                state = WalkSessionState.PAUSED,
                recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
                readiness = null,
            )
            WalkSessionState.PAUSED -> if (
                snapshot.recoveryStage == WalkSessionRecoveryStage.RECHECK_REQUIRED
            ) {
                snapshot
            } else {
                snapshot.copy(
                    epoch = snapshot.epoch.nextGeneration(),
                    recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
                    readiness = null,
                )
            }
            WalkSessionState.READY -> if (snapshot.readiness == null) {
                snapshot
            } else {
                snapshot.copy(
                    epoch = snapshot.epoch.nextGeneration(),
                    mode = WalkSessionMode.UNAVAILABLE,
                    readiness = null,
                )
            }
            WalkSessionState.SAFE_STOP,
            WalkSessionState.ENDED,
            -> snapshot
        }

        is WalkSessionEvent.InitialCheckCompleted -> {
            if (
                snapshot.state != WalkSessionState.READY ||
                !snapshot.isForeground ||
                event.readiness.epoch != snapshot.epoch ||
                event.readiness.plan.action != WalkSessionAction.START_WALK
            ) {
                snapshot
            } else if (event.readiness.isReady && event.readiness.plan.mode.isAvailable) {
                snapshot.copy(
                    mode = event.readiness.plan.mode,
                    readiness = event.readiness,
                )
            } else if (!event.readiness.hasUnavailableRequirement) {
                snapshot
            } else {
                safeStop(snapshot, event.readiness)
            }
        }

        is WalkSessionEvent.StartRequested -> {
            if (
                snapshot.state == WalkSessionState.READY &&
                snapshot.isForeground &&
                event.token.readiness.plan.action == WalkSessionAction.START_WALK &&
                snapshot.confirmationToken == event.token
            ) {
                snapshot.copy(state = WalkSessionState.ACTIVE)
            } else {
                snapshot
            }
        }

        is WalkSessionEvent.RecoveryRecheckCompleted -> {
            if (
                snapshot.state != WalkSessionState.PAUSED ||
                snapshot.recoveryStage != WalkSessionRecoveryStage.RECHECK_REQUIRED ||
                !snapshot.isForeground ||
                event.readiness.epoch != snapshot.epoch ||
                event.readiness.plan.action != WalkSessionAction.RESUME_WALK
            ) {
                snapshot
            } else if (
                event.readiness.isReady &&
                event.readiness.plan.mode.isAvailable
            ) {
                snapshot.copy(
                    mode = event.readiness.plan.mode,
                    recoveryStage = WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION,
                    readiness = event.readiness,
                )
            } else if (!event.readiness.hasUnavailableRequirement) {
                snapshot
            } else {
                safeStop(snapshot, event.readiness)
            }
        }

        is WalkSessionEvent.ResumeConfirmationReceived -> {
            if (
                snapshot.state != WalkSessionState.PAUSED ||
                snapshot.recoveryStage != WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION ||
                !snapshot.isForeground ||
                event.token.readiness.plan.action != WalkSessionAction.RESUME_WALK ||
                snapshot.confirmationToken != event.token
            ) {
                snapshot
            } else if (event.confirmation == WalkSessionResumeConfirmation.START) {
                snapshot.copy(
                    state = WalkSessionState.ACTIVE,
                    recoveryStage = null,
                )
            } else {
                snapshot
            }
        }

        is WalkSessionEvent.RuntimeReadinessChanged -> {
            if (
                snapshot.state != WalkSessionState.ACTIVE ||
                snapshot.epoch != event.readiness.epoch ||
                event.readiness.plan.action != WalkSessionAction.START_WALK
            ) {
                snapshot
            } else if (
                event.readiness.isReady &&
                event.readiness.plan.mode.isAvailable
            ) {
                snapshot.copy(
                    mode = event.readiness.plan.mode,
                    readiness = event.readiness,
                )
            } else {
                safeStop(snapshot, event.readiness)
            }
        }

        WalkSessionEvent.SafetyStopRequested -> {
            if (snapshot.state == WalkSessionState.ENDED) {
                snapshot
            } else {
                snapshot.copy(
                    epoch = snapshot.epoch.nextGeneration(),
                    state = WalkSessionState.SAFE_STOP,
                    mode = WalkSessionMode.UNAVAILABLE,
                    recoveryStage = null,
                    readiness = null,
                )
            }
        }

        WalkSessionEvent.EndRequested -> if (snapshot.state == WalkSessionState.ENDED) {
            snapshot
        } else {
            snapshot.copy(
                epoch = snapshot.epoch.nextGeneration(),
                state = WalkSessionState.ENDED,
                mode = WalkSessionMode.UNAVAILABLE,
                recoveryStage = null,
                readiness = null,
            )
        }

        WalkSessionEvent.NewWalkRequested ->
            initialSnapshot()

        WalkSessionEvent.ProcessRestarted ->
            initialSnapshot()
    }

    private fun safeStop(
        snapshot: WalkSessionSnapshot,
        readiness: WalkSessionReadinessSnapshot?,
    ): WalkSessionSnapshot = snapshot.copy(
        state = WalkSessionState.SAFE_STOP,
        mode = WalkSessionMode.UNAVAILABLE,
        recoveryStage = null,
        readiness = readiness,
    )

    private fun initialSnapshot() = WalkSessionSnapshot(
        epoch = WalkRuntimeEpoch(
            walkSessionId = sessionIdFactory(),
            recoveryGeneration = 0,
        ),
        state = WalkSessionState.READY,
        mode = WalkSessionMode.UNAVAILABLE,
        recoveryStage = null,
        isForeground = false,
        readiness = null,
    )

    private fun WalkRuntimeEpoch.nextGeneration(): WalkRuntimeEpoch =
        copy(recoveryGeneration = recoveryGeneration + 1)

    private fun WalkSessionEvent.transitionReason(): WalkSessionTransitionReason = when (this) {
        WalkSessionEvent.EnteredForeground -> WalkSessionTransitionReason.ENTERED_FOREGROUND
        WalkSessionEvent.EnteredBackground -> WalkSessionTransitionReason.ENTERED_BACKGROUND
        WalkSessionEvent.RecheckRequested -> WalkSessionTransitionReason.RECHECK_REQUESTED
        is WalkSessionEvent.InitialCheckCompleted ->
            WalkSessionTransitionReason.INITIAL_READINESS_COMPLETED
        is WalkSessionEvent.StartRequested -> WalkSessionTransitionReason.START_CONFIRMED
        is WalkSessionEvent.RecoveryRecheckCompleted ->
            WalkSessionTransitionReason.RECOVERY_READINESS_COMPLETED
        is WalkSessionEvent.ResumeConfirmationReceived ->
            WalkSessionTransitionReason.RESUME_CONFIRMATION_RECEIVED
        is WalkSessionEvent.RuntimeReadinessChanged ->
            WalkSessionTransitionReason.RUNTIME_READINESS_CHANGED
        WalkSessionEvent.SafetyStopRequested ->
            WalkSessionTransitionReason.SAFETY_STOP_REQUESTED
        WalkSessionEvent.EndRequested -> WalkSessionTransitionReason.END_REQUESTED
        WalkSessionEvent.NewWalkRequested -> WalkSessionTransitionReason.NEW_WALK_REQUESTED
        WalkSessionEvent.ProcessRestarted -> WalkSessionTransitionReason.PROCESS_RESTARTED
    }
}
