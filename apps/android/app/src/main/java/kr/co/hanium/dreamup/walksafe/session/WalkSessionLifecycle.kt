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
        private val TRAILING_PUNCTUATION = Regex("""[\s.,!?。、！？…~]+$""")

        fun fromRecognizedText(text: String?): WalkSessionResumeConfirmation {
            val normalized = text?.trim()?.replace(TRAILING_PUNCTUATION, "").orEmpty()
            return when {
                normalized.isEmpty() -> NO_RESPONSE
                normalized == "시작" -> START
                normalized == "취소" -> CANCEL
                else -> UNRECOGNIZED
            }
        }

        /**
         * Resolves recognizer alternatives without widening the accepted words. Blank alternatives
         * are skipped, the first spoken alternative decides, and an unrecognized first alternative
         * is never overridden by a later "시작". Alternatives that contradict each other ask again.
         */
        fun fromRecognizedText(candidates: List<String?>): WalkSessionResumeConfirmation {
            val spoken = candidates.map(::fromRecognizedText).filter { it != NO_RESPONSE }
            val first = spoken.firstOrNull() ?: return NO_RESPONSE
            if (first == UNRECOGNIZED) return UNRECOGNIZED
            val contradicted = spoken.any { it != first && it != UNRECOGNIZED }
            return if (contradicted) UNRECOGNIZED else first
        }

        /** Scores belong to the original candidate indexes, including skipped blank alternatives. */
        fun fromRecognizedText(
            candidates: List<String?>,
            confidenceScores: FloatArray?,
            acceptsConfidence: (Float) -> Boolean,
        ): WalkSessionResumeConfirmation {
            val confirmation = fromRecognizedText(candidates)
            if (confirmation != START && confirmation != CANCEL) return confirmation
            val firstSpokenIndex = candidates.indexOfFirst { fromRecognizedText(it) != NO_RESPONSE }
            val scores = confidenceScores ?: return UNRECOGNIZED
            if (scores.size != candidates.size) return UNRECOGNIZED
            val confidence = scores.getOrNull(firstSpokenIndex) ?: return UNRECOGNIZED
            return if (acceptsConfidence(confidence)) confirmation else UNRECOGNIZED
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

    data class StartRequested @JvmOverloads constructor(
        val token: WalkSessionConfirmationToken,
        /** Live measurements collected after delivery of the notice and the server response. */
        val currentReadiness: WalkSessionReadinessSnapshot = token.readiness,
    ) : WalkSessionEvent

    data class ResumeConfirmationReceived @JvmOverloads constructor(
        val confirmation: WalkSessionResumeConfirmation,
        val token: WalkSessionConfirmationToken,
        /** Actual sensor recheck at the explicit START response; never renews the original token. */
        val currentReadiness: WalkSessionReadinessSnapshot = token.readiness,
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
 *
 * [clock] is the wall clock recorded in [WalkSessionTransition.occurredAtEpochMs] for diagnostics.
 * [elapsedRealtimeClock] must be the same monotonic clock used for
 * [WalkSessionReadinessSnapshot.assessedAtElapsedRealtimeMs]; Android passes
 * `SystemClock::elapsedRealtime`. Readiness age is never computed across the two clocks. Only
 * single-clock callers (pure tests, fixed practice clocks) may omit it; then the one [clock]
 * sample of each event is used for both purposes.
 */
class WalkSessionLifecycle(
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeClock: (() -> Long)? = null,
) {
    companion object {
        // Bound point-in-time readiness to a prompt spoken/UI confirmation. This limits stale
        // evidence exposure; it does not replace a fresh check of live device conditions.
        const val CONFIRMATION_TOKEN_TTL_MS = 30_000L
    }

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

    /**
     * Memory-only owner fencing runs after reduction and before publication. A callback failure
     * propagates without publishing the next snapshot; callers must not perform I/O or wait in this callback.
     */
    @JvmOverloads
    @Synchronized
    fun handle(
        event: WalkSessionEvent,
        beforePublish: (previous: WalkSessionSnapshot, next: WalkSessionSnapshot) -> Unit = { _, _ -> },
    ): WalkSessionTransition {
        val previous = current
        val occurredAtEpochMs = clock()
        val nowElapsedRealtimeMs = elapsedRealtimeClock?.invoke() ?: occurredAtEpochMs
        val next = reduce(previous, event, nowElapsedRealtimeMs)
        beforePublish(previous, next)
        current = next
        return WalkSessionTransition(
            previous = previous,
            current = current,
            reason = event.transitionReason(),
            occurredAtEpochMs = occurredAtEpochMs,
        )
    }

    private fun reduce(
        snapshot: WalkSessionSnapshot,
        event: WalkSessionEvent,
        nowElapsedRealtimeMs: Long,
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
            } else if (event.readiness.isReady && event.readiness.isReadyAt(nowElapsedRealtimeMs) &&
                event.readiness.plan.mode.isAvailable) {
                snapshot.copy(
                    mode = event.readiness.plan.mode,
                    readiness = event.readiness,
                )
            } else if (!event.readiness.hasUnavailableRequirement) {
                if (snapshot.readiness == null) snapshot else snapshot.copy(
                    epoch = snapshot.epoch.nextGeneration(),
                    mode = WalkSessionMode.UNAVAILABLE,
                    readiness = null,
                )
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
                if (
                    event.token.hasValidConfirmationAgeAt(nowElapsedRealtimeMs) &&
                    event.currentReadiness.epoch == snapshot.epoch &&
                    event.currentReadiness.plan == event.token.readiness.plan &&
                    event.currentReadiness.isReady &&
                    event.currentReadiness.isReadyAt(nowElapsedRealtimeMs)
                ) {
                    // The confirmation still belongs to the original attempt. Live sensor
                    // evidence has its own shorter lifetime and must come from a fresh check.
                    snapshot.copy(state = WalkSessionState.ACTIVE, readiness = event.currentReadiness)
                } else {
                    reduce(snapshot, WalkSessionEvent.RecheckRequested, nowElapsedRealtimeMs)
                }
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
                event.readiness.isReady && event.readiness.isReadyAt(nowElapsedRealtimeMs) &&
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
                if (event.token.hasValidConfirmationAgeAt(nowElapsedRealtimeMs) &&
                    event.currentReadiness.epoch == snapshot.epoch &&
                    event.currentReadiness.plan == event.token.readiness.plan &&
                    event.currentReadiness.isReady && event.currentReadiness.isReadyAt(nowElapsedRealtimeMs)) {
                    snapshot.copy(
                        state = WalkSessionState.ACTIVE,
                        recoveryStage = null,
                        readiness = event.currentReadiness,
                    )
                } else {
                    reduce(snapshot, WalkSessionEvent.RecheckRequested, nowElapsedRealtimeMs)
                }
            } else if (!event.token.hasValidReadinessAt(nowElapsedRealtimeMs)) {
                reduce(snapshot, WalkSessionEvent.RecheckRequested, nowElapsedRealtimeMs)
            } else if (event.confirmation == WalkSessionResumeConfirmation.CANCEL) {
                snapshot.copy(
                    epoch = snapshot.epoch.nextGeneration(),
                    recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
                    readiness = null,
                )
            } else {
                snapshot
            }
        }

        is WalkSessionEvent.RuntimeReadinessChanged -> {
            if (
                snapshot.state !in setOf(WalkSessionState.READY, WalkSessionState.ACTIVE, WalkSessionState.PAUSED) ||
                snapshot.epoch != event.readiness.epoch ||
                event.readiness.plan.action !in setOf(WalkSessionAction.START_WALK, WalkSessionAction.RESUME_WALK) ||
                snapshot.readiness == null
            ) {
                snapshot
            } else if (snapshot.state != WalkSessionState.ACTIVE) {
                if (event.readiness.isReady && event.readiness.isReadyAt(nowElapsedRealtimeMs) &&
                    event.readiness.plan.mode == snapshot.mode &&
                    event.readiness.observations == snapshot.readiness.observations
                ) {
                    snapshot
                } else if (event.readiness.hasUnavailableRequirement) {
                    safeStop(snapshot, event.readiness)
                } else {
                    snapshot.copy(
                        epoch = snapshot.epoch.nextGeneration(),
                        mode = WalkSessionMode.UNAVAILABLE,
                        recoveryStage = if (snapshot.state == WalkSessionState.PAUSED) {
                            WalkSessionRecoveryStage.RECHECK_REQUIRED
                        } else null,
                        readiness = null,
                    )
                }
            } else if (
                event.readiness.isReady && event.readiness.isReadyAt(nowElapsedRealtimeMs) &&
                (
                    event.readiness.plan.mode == snapshot.mode ||
                        (snapshot.mode == WalkSessionMode.FULL &&
                            event.readiness.plan.mode == WalkSessionMode.DISTANCE_LIMITED)
                )
            ) {
                // A distance-only restriction preserves the active walk when every remaining
                // required input is current and ready. Publish mode and its evidence together.
                snapshot.copy(mode = event.readiness.plan.mode, readiness = event.readiness)
            } else {
                // A wider mode, unavailable mode or lost required input still stops the walk.
                // Preserve the original observations while recording the time they were consumed.
                // Otherwise an expired observation would still expose a READY confirmation token.
                safeStop(
                    snapshot,
                    event.readiness.copy(assessedAtElapsedRealtimeMs = nowElapsedRealtimeMs),
                )
            }
        }

        WalkSessionEvent.SafetyStopRequested -> {
            if (snapshot.state == WalkSessionState.SAFE_STOP || snapshot.state == WalkSessionState.ENDED) {
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

    private fun WalkSessionConfirmationToken.hasValidReadinessAt(
        nowElapsedRealtimeMs: Long,
    ): Boolean = readiness.isReadyAt(nowElapsedRealtimeMs) &&
        hasValidConfirmationAgeAt(nowElapsedRealtimeMs)

    private fun WalkSessionConfirmationToken.hasValidConfirmationAgeAt(
        nowElapsedRealtimeMs: Long,
    ): Boolean {
        val assessedAtElapsedRealtimeMs = readiness.assessedAtElapsedRealtimeMs
        return nowElapsedRealtimeMs >= assessedAtElapsedRealtimeMs &&
            nowElapsedRealtimeMs - assessedAtElapsedRealtimeMs <= CONFIRMATION_TOKEN_TTL_MS
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
