package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.CollisionAssessment
import kr.co.hanium.dreamup.walksafe.depth.DepthAgeBudget
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicyConfig
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

private const val EMERGENCY_STOP_TTC_MS = 300L
private const val EMERGENCY_STOP_DISTANCE_M = 0.9f

data class FeedbackPolicyConfig(
    val perTrackIntervalMs: Long = 2_500L,
    val globalIntervalMs: Long = 700L,
    val riskNavigationCooldownMs: Long = 2_500L,
    val clearAfterSafeFrames: Int = 2,
    val clearAfterMissingMs: Long = 1_500L,
    val minStableFrames: Int = 3,
    val minStableMs: Long = 700L,
    val minConfidence: Float = 0.55f,
    val pendingQueueCapacity: Int = 3,
    val pendingQueueTtlMs: Long = 1_500L,
    val maxObservationAgeMs: Long = DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS,
)

data class FeedbackCandidate(
    val trackId: String,
    val message: String,
    val level: MessageLevel,
    val source: DepthSource,
    val confidence: Float,
    /** Source camera clock; comparable only with another observation from that same source. */
    val timestampMs: Long,
    val trackAgeFrames: Int,
    val trackStableMs: Long,
    val stale: Boolean,
    /** Explicit monotonic-clock lease; camera timestamps must be translated by the producer. */
    val validUntilMs: Long = Long.MIN_VALUE,
    /** Compatibility backing slot for approach urgency, not a physical collision countdown. */
    val timeToCollisionMs: Long? = null,
    val riskDistanceM: Float? = null,
    /** Original capture-derived observation in the same elapsed-realtime clock as policy nowMs. */
    val observedAtElapsedRealtimeMs: Long? = null,
    /** The producer's actual age budget; omission cannot authorize output. */
    val observationMaxAgeMs: Long? = null,
    /** UNKNOWN retains near/approach warnings; it never authorizes a safety release. */
    val collisionAssessment: CollisionAssessment = CollisionAssessment.UNKNOWN,
) {
    val approachTimeMs: Long? get() = timeToCollisionMs

    val alertableLevel: Boolean
        get() = level == MessageLevel.CAUTION || level == MessageLevel.WARNING || level == MessageLevel.STOP

    val deliveryKey: String
        get() = "$trackId|${level.name}|$message"
}

/**
 * Why a fresh candidate produced by the message layer was not an output candidate (S12-21).
 * A generated message is not a delivered one; delivery is confirmed only by the speech callbacks.
 */
enum class FeedbackOutputRejection {
    BLANK_MESSAGE,
    /** Non-metric depth; only the bounded qualitative CAUTION contract may be output. */
    NON_METRIC_SOURCE,
    /** Generation and output intentionally have separate confidence gates (output defaults to .55). */
    CONFIDENCE_BELOW_OUTPUT,
    LEVEL_NOT_OUTPUT,
    MISSING_CAPTURE_LEASE,
    /** Stale flag, expired source, or invalid/missing source-clock metadata. */
    OBSERVATION_NOT_FRESH,
    DEVICE_GATE_BLOCKED,
}

/** One evaluation's facts, retained by the dispatch even after another frame or a walk reset. */
data class FeedbackOutputEvaluationSnapshot(
    val evaluatedAtMs: Long,
    val minimumOutputConfidence: Float,
    val deviceGateAllowsAlerts: Boolean,
    val frameRejection: FeedbackOutputRejection?,
    val rejectionsByTrack: Map<String, Set<FeedbackOutputRejection>>,
)

/** Identity of one policy-issued delivery; equal timestamps do not make two owners equal. */
class FeedbackDeliveryOwner internal constructor(
    val trackId: String,
    val evaluatedAtMs: Long,
    private val message: String,
    private val level: MessageLevel,
    private val validUntilMs: Long,
    val replacesDeliveryOwner: FeedbackDeliveryOwner?,
    private val reason: String,
) {
    private enum class State { RESERVED, CLAIMED, ADMITTED, RETIRED }
    private val state = AtomicReference(State.RESERVED)

    internal fun matches(action: FeedbackAction): Boolean =
        action.deliveryOwner === this && action.trackId == trackId && action.message == message &&
            action.level == level && action.validUntilMs == validUntilMs && action.reason == reason

    internal fun markClaimed(): Boolean = state.compareAndSet(State.RESERVED, State.CLAIMED)

    internal fun isClaimed(): Boolean = state.get().let { it == State.CLAIMED || it == State.ADMITTED }

    internal fun retire() { state.set(State.RETIRED) }

    /** Only the native admission transaction may consume the issued replacement relationship. */
    internal fun markReplacementAdmitted(): Boolean =
        replacesDeliveryOwner != null && state.compareAndSet(State.CLAIMED, State.ADMITTED)

    internal fun replacementWasAdmitted(): Boolean = state.get() == State.ADMITTED
}

data class FeedbackAction(
    val trackId: String,
    val message: String,
    val level: MessageLevel,
    val vibrationPatternMs: LongArray?,
    val reason: String,
    val validUntilMs: Long = Long.MIN_VALUE,
    val deliveryOwner: FeedbackDeliveryOwner? = null,
) {
    val deliveryKey: String
        get() = "$trackId|${level.name}|$message"

    val replacesDeliveryOwner: FeedbackDeliveryOwner?
        get() = deliveryOwner?.replacesDeliveryOwner

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeedbackAction) return false
        return trackId == other.trackId &&
            message == other.message &&
            level == other.level &&
            vibrationPatternMs.contentEquals(other.vibrationPatternMs) &&
            reason == other.reason &&
            validUntilMs == other.validUntilMs &&
            deliveryOwner === other.deliveryOwner
    }

    override fun hashCode(): Int {
        var result = trackId.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + (vibrationPatternMs?.contentHashCode() ?: 0)
        result = 31 * result + reason.hashCode()
        result = 31 * result + validUntilMs.hashCode()
        result = 31 * result + (deliveryOwner?.hashCode() ?: 0)
        return result
    }
}

/**
 * Stateful, single-threaded alert gate. Speech/haptics require fresh stable metric depth, and a
 * recent risk alert temporarily takes priority over navigation speech.
 */
class WalkSafeFeedbackPolicy(
    private val config: FeedbackPolicyConfig = FeedbackPolicyConfig(),
) {
    private enum class DeliveryState {
        RESERVED,
        CLAIMED,
    }

    private data class PendingDeliveryKey(
        val trackId: String,
        val evaluatedAtMs: Long,
    )

    private data class CooldownSnapshot(
        val previousTrackEmitAt: Long?,
        val previousGlobalEmitAt: Long?,
        val previousRiskEmitAt: Long?,
        val changedRiskEmitAt: Boolean,
        val level: MessageLevel,
        val validUntilMs: Long,
        val riskEvidence: RiskEvidence?,
        val owner: FeedbackDeliveryOwner,
        val state: DeliveryState = DeliveryState.RESERVED,
        val message: String = "",
        /** Raw CAUTION distance at delivery; only a re-announce baseline, never level evidence. */
        val cautionDistanceM: Float? = null,
    )

    private data class PendingRiskReplacement(val key: PendingDeliveryKey, val snapshot: CooldownSnapshot)

    private data class RiskEvidence(
        val source: DepthSource,
        val timestampMs: Long,
        val validUntilMs: Long,
        val approachTimeMs: Long?,
        val distanceM: Float,
    )

    private data class QueuedFeedback(
        var candidate: FeedbackCandidate,
        val sequence: Long,
        var lastSeenAtMs: Long,
    )

    private val lastTrackEmitAt = mutableMapOf<String, Long>()
    private data class CompletedTrackFeedbackHistory(
        val confirmedAtMs: Long,
        val level: MessageLevel,
        val message: String,
        val riskEvidence: RiskEvidence?,
        /** Re-announce baseline only; never raises a level. */
        val cautionDistanceM: Float?,
        val deliveryOrder: Long,
    )

    private val completedTrackHistory = mutableMapOf<String, CompletedTrackFeedbackHistory>()
    private val riskThresholds = MessagePolicyConfig()
    private val pendingDeliverySnapshots = mutableMapOf<PendingDeliveryKey, CooldownSnapshot>()
    private var pendingRiskReplacement: PendingRiskReplacement? = null
    private val queuedFeedback = mutableListOf<QueuedFeedback>()
    private var nextQueueSequence = 0L
    private var nextDeliveryOrder = 0L
    private var lastGlobalEmitAt: Long? = null
    private var lastGlobalDeliveredLevel: MessageLevel? = null
    private var lastRiskEmitAt: Long? = null
    private var lastCandidateSeenAt: Long? = null
    private var consecutiveSafeFrames = 0
    private val outputRejectionCounts = mutableMapOf<FeedbackOutputRejection, Long>()
    private var lastOutputRejections: Map<String, FeedbackOutputRejection> = emptyMap()
    private var lastOutputEvaluation: FeedbackOutputEvaluationSnapshot? = null

    init {
        require(config.pendingQueueCapacity > 0) { "pendingQueueCapacity must be positive" }
        require(config.pendingQueueTtlMs > 0L) { "pendingQueueTtlMs must be positive" }
        require(config.maxObservationAgeMs in 1L until Long.MAX_VALUE) { "maxObservationAgeMs must be finite and positive" }
    }

    fun evaluate(
        candidate: FeedbackCandidate?,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
    ): FeedbackAction? = evaluateCandidates(listOfNotNull(candidate), deviceGateAllowsAlerts, nowMs)

    @Synchronized
    fun evaluateCandidates(
        candidates: List<FeedbackCandidate>,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
        /** Pre-admission observations are diagnostic only; they never join the output queue. */
        observedCandidates: List<FeedbackCandidate> = candidates,
    ): FeedbackAction? {
        reconcileAdmittedRiskReplacement()
        pendingRiskReplacement?.let { proposal ->
            if (nowMs > proposal.snapshot.validUntilMs) discardRiskReplacement()
        }
        recordOutputRejections(candidates, observedCandidates, deviceGateAllowsAlerts, nowMs)
        if (!deviceGateAllowsAlerts) {
            cancelPendingFeedbackDeliveries()
            clearActiveState()
            return null
        }
        pendingDeliverySnapshots.toMap().forEach { (key, snapshot) ->
            // The deadline fences START. A claimed dispatch is retired by its explicit
            // cancellation/completion callback, not by another camera frame arriving later.
            if (snapshot.state != DeliveryState.CLAIMED && nowMs > snapshot.validUntilMs) {
                pendingDeliverySnapshots.remove(key)
                snapshot.owner.retire()
                rollbackCooldowns(key, snapshot)
            }
        }
        val freshCandidates = candidates.filter { isFreshAt(it, nowMs) }
        pruneCompletedHistory(nowMs, freshCandidates)
        if (freshCandidates.isEmpty()) {
            reconcilePendingDelivery(emptyList())
            markMissing(nowMs)
            return null
        }
        lastCandidateSeenAt = nowMs
        val eligible = eligibleCandidates(freshCandidates)
        val stableEligible = eligible.filter { candidate ->
            isStable(candidate) || isEmergencyStop(candidate, nowMs)
        }.sortedByDescending { it.level.ordinal }
            .distinctBy(FeedbackCandidate::trackId)
        reconcilePendingDelivery(stableEligible)
        // FP-020: the same continuously visible CAUTION is not repeated. It still keeps the scene
        // from counting as safe, but it neither queues nor renews the navigation cooldown.
        refreshQueuedFeedback(stableEligible.filterNot(::isRepeatedCaution), nowMs)
        if (eligible.isEmpty()) {
            markSafe()
            return null
        }
        consecutiveSafeFrames = 0
        if (pendingRiskReplacement != null) return null
        val pending = pendingDeliverySnapshots.entries.firstOrNull()
        val replacementCandidate = pending?.takeIf { it.value.state == DeliveryState.CLAIMED &&
            it.key.evaluatedAtMs != nowMs }?.let { (_, snapshot) ->
            stableEligible.firstOrNull { candidate ->
                candidate.trackId == snapshot.owner.trackId && candidate.level == snapshot.level &&
                    isStable(candidate) && isRiskWorsening(candidate, snapshot.riskEvidence)
            }
        }
        if (pending != null && replacementCandidate == null) return null
        val lastGlobalAt = lastGlobalEmitAt
        val currentTrackIds = stableEligible.mapTo(hashSetOf(), FeedbackCandidate::trackId)
        val available = replacementCandidate?.let { listOf(QueuedFeedback(it, nextQueueSequence, nowMs)) }
            ?: queuedFeedback
        val queued = available.firstOrNull { queued ->
            val candidate = queued.candidate
            if (candidate.trackId !in currentTrackIds) return@firstOrNull false
            if (nowMs - queued.lastSeenAtMs > config.pendingQueueTtlMs) return@firstOrNull false
            val lastTrackAt = lastTrackEmitAt[candidate.trackId]
            val trackRateLimited = lastTrackAt != null && nowMs - lastTrackAt < config.perTrackIntervalMs
            val sameLevelWorsening = candidate === replacementCandidate ||
                (trackRateLimited && isSameLevelRiskWorsening(candidate))
            if (lastGlobalAt != null && nowMs - lastGlobalAt < config.globalIntervalMs &&
                (sameLevelWorsening || !isUrgentEscalation(candidate.level, lastGlobalDeliveredLevel))
            ) return@firstOrNull false
            !trackRateLimited ||
                isUrgentEscalation(candidate.level, completedTrackHistory[candidate.trackId]?.level) || sameLevelWorsening
        } ?: return null
        val candidate = queued.candidate
        val lastTrackAt = lastTrackEmitAt[candidate.trackId]
        val validUntilMs = minOf(observationDeadlineMs(candidate), finiteDeadline(nowMs, config.pendingQueueTtlMs))
        val reason = when {
            candidate.source == DepthSource.POLYGON_TREND_PSEUDO_DEPTH -> "fresh_qualitative_depth_caution"
            candidate.level == MessageLevel.INFO -> "fresh_metric_path_guidance"
            else -> "fresh_metric_depth_alert"
        }
        val replacedOwner = if (candidate === replacementCandidate) pending?.value?.owner else null
        val owner = FeedbackDeliveryOwner(candidate.trackId, nowMs, candidate.message,
            candidate.level, validUntilMs, replacedOwner, reason)
        queuedFeedback.remove(queued)
        val key = PendingDeliveryKey(candidate.trackId, nowMs)
        val snapshot = CooldownSnapshot(
            previousTrackEmitAt = lastTrackAt,
            previousGlobalEmitAt = lastGlobalEmitAt,
            previousRiskEmitAt = lastRiskEmitAt,
            changedRiskEmitAt = candidate.alertableLevel,
            level = candidate.level,
            validUntilMs = validUntilMs,
            riskEvidence = if (replacedOwner != null) {
                combinedRiskEvidence(pending?.value?.riskEvidence, riskEvidence(candidate))
            } else riskEvidence(candidate),
            owner = owner,
            message = candidate.message,
            cautionDistanceM = cautionDistance(candidate),
        )
        if (replacedOwner != null) {
            // Evaluation and claim do not revoke an ongoing delivery. A rejected or expired
            // proposal leaves its old completion/cooldowns intact until native admission.
            pendingRiskReplacement = PendingRiskReplacement(key, snapshot)
        } else {
            pendingDeliverySnapshots[key] = snapshot
            applyReservedCooldowns(key, snapshot)
        }
        return FeedbackAction(
            trackId = candidate.trackId,
            message = candidate.message,
            level = candidate.level,
            vibrationPatternMs = VibrationPatterns.forLevel(candidate.level),
            reason = reason,
            validUntilMs = validUntilMs,
            deliveryOwner = owner,
        )
    }

    private fun isUrgentEscalation(level: MessageLevel, deliveredLevel: MessageLevel?): Boolean =
        // Only an urgent warning or stop can interrupt a lower completed alert's cooldown.
        (level == MessageLevel.WARNING || level == MessageLevel.STOP) &&
            deliveredLevel != null && level.ordinal > deliveredLevel.ordinal

    private fun isRepeatedCaution(candidate: FeedbackCandidate): Boolean {
        // A changed object/position phrase is a new situation; only the same CAUTION is withheld.
        if (candidate.level != MessageLevel.CAUTION ||
            completedTrackHistory[candidate.trackId]?.level != MessageLevel.CAUTION ||
            completedTrackHistory[candidate.trackId]?.message != candidate.message
        ) return false
        // Re-announce only when the same object moved markedly closer (a distance band crossing
        // with the existing hysteresis margin). Produced CAUTIONs are weak-metric (no trusted
        // evidence) or qualitative, so the raw CAUTION distance recorded at delivery is compared;
        // it only re-announces the same CAUTION and never raises a level (S13-18). A qualitative
        // CAUTION has no distance and stays withheld.
        val current = cautionDistance(candidate) ?: return true
        val previous = completedTrackHistory[candidate.trackId]?.cautionDistanceM ?: return false
        val movedMarkedlyCloser = distanceRiskBand(current) > distanceRiskBand(previous) &&
            previous - current >= riskThresholds.hysteresis.distanceMarginM
        return !movedMarkedlyCloser
    }

    private fun cautionDistance(candidate: FeedbackCandidate): Float? =
        candidate.riskDistanceM?.takeIf { candidate.level == MessageLevel.CAUTION && it.isFinite() && it > 0f }

    private fun riskEvidence(candidate: FeedbackCandidate): RiskEvidence? {
        if (!candidate.source.trustedForStepGuidance ||
            !candidate.confidence.isFinite() ||
            candidate.confidence < (riskThresholds.metricSourceMinConfidence[candidate.source] ?: 1f) ||
            candidate.validUntilMs == Long.MAX_VALUE
        ) return null
        val distance = candidate.riskDistanceM?.takeIf { it.isFinite() && it > 0f } ?: return null
        return RiskEvidence(candidate.source, candidate.timestampMs, observationDeadlineMs(candidate),
            candidate.approachTimeMs?.takeIf { it >= 0L }, distance)
    }

    private fun isSameLevelRiskWorsening(candidate: FeedbackCandidate): Boolean {
        if ((candidate.level != MessageLevel.WARNING && candidate.level != MessageLevel.STOP) ||
            completedTrackHistory[candidate.trackId]?.level != candidate.level || !isStable(candidate)
        ) return false
        return isRiskWorsening(candidate, completedTrackHistory[candidate.trackId]?.riskEvidence)
    }

    private fun isRiskWorsening(candidate: FeedbackCandidate, previous: RiskEvidence?): Boolean {
        if ((candidate.level != MessageLevel.WARNING && candidate.level != MessageLevel.STOP) ||
            previous == null
        ) return false
        val current = riskEvidence(candidate) ?: return false
        // The previous lease was checked when that alert started. Only the current observation
        // must still be live; camera timestamps are compared only within the same source.
        if (current.source != previous.source || current.timestampMs <= previous.timestampMs ||
            current.validUntilMs <= previous.validUntilMs
        ) return false
        val previousApproachTime = previous.approachTimeMs
        val currentApproachTime = current.approachTimeMs
        // A band crossing alone admits boundary jitter. Require at least a halving as well;
        // this is a replay guard, not a change to the existing warning/STOP thresholds.
        val approachWorsened = previousApproachTime != null && currentApproachTime != null &&
            approachRiskBand(currentApproachTime) > approachRiskBand(previousApproachTime) &&
            currentApproachTime <= previousApproachTime / 2L
        val distanceWorsened = distanceRiskBand(current.distanceM) > distanceRiskBand(previous.distanceM) &&
            previous.distanceM - current.distanceM >= riskThresholds.hysteresis.distanceMarginM
        return approachWorsened || distanceWorsened
    }

    /**
     * A replay inside the per-track window continues the same alert episode, so its baseline keeps
     * the worst approach time and distance already announced. Otherwise a value improved in the replay
     * frame could later count as a new worsening and let opposite jitter replay every 700 ms.
     */
    private fun deliveredRiskBaseline(
        trackId: String,
        snapshot: CooldownSnapshot,
        evaluatedAtMs: Long,
    ): RiskEvidence? {
        val current = snapshot.riskEvidence ?: return null
        val previous = completedTrackHistory[trackId]?.riskEvidence ?: return current
        val previousEmitAt = snapshot.previousTrackEmitAt ?: return current
        val sameEpisode = previous.source == current.source &&
            completedTrackHistory[trackId]?.level == snapshot.level &&
            evaluatedAtMs - previousEmitAt < config.perTrackIntervalMs
        if (!sameEpisode) return current
        return combinedRiskEvidence(previous, current)
    }

    private fun combinedRiskEvidence(previous: RiskEvidence?, current: RiskEvidence?): RiskEvidence? {
        if (current == null || previous == null || current.source != previous.source) return current
        return current.copy(
            approachTimeMs = listOfNotNull(previous.approachTimeMs, current.approachTimeMs).minOrNull(),
            distanceM = minOf(previous.distanceM, current.distanceM),
        )
    }

    private fun approachRiskBand(approachTimeMs: Long): Int = when {
        approachTimeMs <= EMERGENCY_STOP_TTC_MS -> 4
        approachTimeMs <= riskThresholds.ttcStopMs -> 3
        approachTimeMs <= riskThresholds.ttcWarningMs -> 2
        approachTimeMs <= riskThresholds.ttcAwareMs -> 1
        else -> 0
    }

    private fun distanceRiskBand(distanceM: Float): Int = when {
        distanceM <= EMERGENCY_STOP_DISTANCE_M -> 4
        distanceM <= riskThresholds.stopDistanceM -> 3
        distanceM <= riskThresholds.warningDistanceM -> 2
        distanceM <= riskThresholds.approachingWarningDistanceM -> 1
        else -> 0
    }

    private fun pruneCompletedHistory(nowMs: Long, freshCandidates: List<FeedbackCandidate>) {
        val protectedTrackIds = freshCandidates.mapTo(hashSetOf(), FeedbackCandidate::trackId)
        pendingDeliverySnapshots.keys.mapTo(protectedTrackIds, PendingDeliveryKey::trackId)
        queuedFeedback.mapTo(protectedTrackIds) { it.candidate.trackId }
        val completed = completedTrackHistory.entries.iterator()
        while (completed.hasNext()) {
            val (trackId, history) = completed.next()
            val confirmedAtMs = history.confirmedAtMs
            if (trackId in protectedTrackIds || nowMs < confirmedAtMs ||
                nowMs - confirmedAtMs < config.perTrackIntervalMs
            ) continue
            // No current, queued, or in-flight use can observe this completed turn now.
            completed.remove()
            lastTrackEmitAt.remove(trackId)
        }
    }

    private fun isStable(candidate: FeedbackCandidate): Boolean =
        candidate.trackAgeFrames >= config.minStableFrames && candidate.trackStableMs >= config.minStableMs

    private fun hasEmergencyStopEvidence(candidate: FeedbackCandidate): Boolean =
        candidate.level == MessageLevel.STOP &&
            candidate.source == DepthSource.ARCORE_RAW_DEPTH &&
            candidate.confidence.isFinite() && candidate.confidence >= 0.90f &&
            candidate.trackAgeFrames >= 2 && candidate.trackStableMs >= 100L &&
            candidate.validUntilMs != Long.MAX_VALUE &&
            candidate.approachTimeMs?.let { it in 0L..EMERGENCY_STOP_TTC_MS } == true &&
            candidate.riskDistanceM?.let { it.isFinite() && it > 0f && it <= EMERGENCY_STOP_DISTANCE_M } == true

    private fun isEmergencyStop(candidate: FeedbackCandidate, nowMs: Long): Boolean =
        hasEmergencyStopEvidence(candidate) && observationDeadlineMs(candidate) - nowMs > 400L

    private fun refreshQueuedFeedback(candidates: List<FeedbackCandidate>, nowMs: Long) {
        val currentByTrack = candidates.associateBy(FeedbackCandidate::trackId)
        val pendingTrackId = pendingDeliverySnapshots.keys.firstOrNull()?.trackId
        queuedFeedback.removeAll { queued ->
            nowMs - queued.lastSeenAtMs > config.pendingQueueTtlMs ||
                queued.candidate.trackId == pendingTrackId
        }
        // Refresh existing entries before comparing a new arrival with their current severity.
        queuedFeedback.forEach { queued ->
            currentByTrack[queued.candidate.trackId]?.let { current ->
                queued.candidate = current
                queued.lastSeenAtMs = nowMs
            }
        }
        // A continuously visible, already delivered track must not reclaim every vacancy before
        // an unheard peer. Only successful delivery advances this order; reservations do not.
        // Retain a briefly missing entry's FIFO position until its TTL, but evict it before any
        // current candidate when capacity is needed. It is never eligible for delivery while absent.
        val priority = compareBy<QueuedFeedback> { it.candidate.trackId !in currentByTrack }
            .thenByDescending { it.candidate.level.ordinal }
            .thenBy { it.candidate.approachTimeMs?.takeIf { value -> value >= 0L } ?: Long.MAX_VALUE }
            .thenBy { it.candidate.riskDistanceM?.takeIf { value -> value.isFinite() && value > 0f } ?: Float.MAX_VALUE }
            .thenBy { completedTrackHistory[it.candidate.trackId]?.deliveryOrder ?: Long.MIN_VALUE }
            .thenBy(QueuedFeedback::sequence)
        candidates.forEach { candidate ->
            if (candidate.trackId == pendingTrackId) return@forEach
            if (queuedFeedback.any { it.candidate.trackId == candidate.trackId }) return@forEach
            val incoming = QueuedFeedback(candidate, nextQueueSequence, nowMs)
            val occupied = queuedFeedback.size + pendingDeliverySnapshots.size
            if (occupied >= config.pendingQueueCapacity) {
                val lowest = queuedFeedback.maxWithOrNull(priority) ?: return@forEach
                if (priority.compare(incoming, lowest) >= 0) return@forEach
                queuedFeedback.remove(lowest)
            }
            queuedFeedback += incoming
            nextQueueSequence++
        }
        queuedFeedback.sortWith(priority)
    }

    @Synchronized
    fun confirmFeedbackDelivery(
        action: FeedbackAction,
        evaluatedAtMs: Long,
        deliveredAtMs: Long = evaluatedAtMs,
    ): Boolean {
        reconcileAdmittedRiskReplacement()
        if (!matchesIssuedDelivery(action, evaluatedAtMs) ||
            (action.deliveryOwner != null && !hasClaimedFeedbackDelivery(action))
        ) return false
        return confirmFeedbackDelivery(action.trackId, evaluatedAtMs, deliveredAtMs)
    }

    @Synchronized
    fun confirmFeedbackDelivery(
        trackId: String,
        evaluatedAtMs: Long,
        deliveredAtMs: Long = evaluatedAtMs,
    ): Boolean {
        reconcileAdmittedRiskReplacement()
        val key = PendingDeliveryKey(trackId, evaluatedAtMs)
        val snapshot = pendingDeliverySnapshots.remove(key) ?: return false
        snapshot.owner.retire()
        if (pendingRiskReplacement?.snapshot?.owner?.replacesDeliveryOwner === snapshot.owner) {
            discardRiskReplacement()
        }
        val confirmedAtMs = deliveredAtMs.coerceAtLeast(evaluatedAtMs)
        if (lastTrackEmitAt[trackId] == evaluatedAtMs) {
            lastTrackEmitAt[trackId] = confirmedAtMs
            completedTrackHistory[trackId] = CompletedTrackFeedbackHistory(
                confirmedAtMs = confirmedAtMs,
                level = snapshot.level,
                message = snapshot.message,
                riskEvidence = deliveredRiskBaseline(trackId, snapshot, evaluatedAtMs),
                cautionDistanceM = snapshot.cautionDistanceM,
                deliveryOrder = nextDeliveryOrder++,
            )
        }
        if (lastGlobalEmitAt == evaluatedAtMs) {
            lastGlobalEmitAt = confirmedAtMs
            lastGlobalDeliveredLevel = snapshot.level
        }
        if (snapshot.changedRiskEmitAt && lastRiskEmitAt == evaluatedAtMs) lastRiskEmitAt = confirmedAtMs
        return true
    }

    @Synchronized
    fun claimFeedbackDelivery(action: FeedbackAction, evaluatedAtMs: Long, nowMs: Long): Boolean {
        reconcileAdmittedRiskReplacement()
        if (!matchesIssuedDelivery(action, evaluatedAtMs)) return false
        return claimFeedbackDelivery(action.trackId, evaluatedAtMs, nowMs)
    }

    @Synchronized
    fun claimFeedbackDelivery(trackId: String, evaluatedAtMs: Long, nowMs: Long): Boolean {
        reconcileAdmittedRiskReplacement()
        val key = PendingDeliveryKey(trackId, evaluatedAtMs)
        pendingRiskReplacement?.takeIf { it.key == key }?.let { proposal ->
            val snapshot = proposal.snapshot
            if (snapshot.state != DeliveryState.RESERVED) return false
            if (nowMs > snapshot.validUntilMs) {
                discardRiskReplacement()
                return false
            }
            if (!snapshot.owner.markClaimed()) return false
            pendingRiskReplacement = proposal.copy(snapshot = snapshot.copy(state = DeliveryState.CLAIMED))
            return true
        }
        val snapshot = pendingDeliverySnapshots[key] ?: return false
        if (snapshot.state != DeliveryState.RESERVED) return false
        if (nowMs > snapshot.validUntilMs) {
            pendingDeliverySnapshots.remove(key)
            snapshot.owner.retire()
            rollbackCooldowns(key, snapshot)
            return false
        }
        if (!snapshot.owner.markClaimed()) return false
        pendingDeliverySnapshots[key] = snapshot.copy(state = DeliveryState.CLAIMED)
        return true
    }

    /** Observe native admission receipts without renewing the source lease. */
    @Synchronized
    fun hasClaimedFeedbackDelivery(trackId: String, level: MessageLevel): Boolean {
        reconcileAdmittedRiskReplacement()
        return pendingDeliverySnapshots.any { (key, snapshot) ->
            key.trackId == trackId && snapshot.state == DeliveryState.CLAIMED && snapshot.level == level
        }
    }

    /** Exact issued owner check for callbacks and the Main output/start validator. */
    @Synchronized
    fun hasClaimedFeedbackDelivery(action: FeedbackAction): Boolean {
        reconcileAdmittedRiskReplacement()
        val owner = action.deliveryOwner ?: return hasClaimedFeedbackDelivery(action.trackId, action.level)
        if (!owner.matches(action) || !owner.isClaimed()) return false
        val snapshot = pendingRiskReplacement?.takeIf { it.snapshot.owner === owner }?.snapshot
            ?: pendingDeliverySnapshots[PendingDeliveryKey(owner.trackId, owner.evaluatedAtMs)]
        return snapshot != null && snapshot.owner === owner && snapshot.state == DeliveryState.CLAIMED
    }

    @Synchronized
    fun rejectUndeliveredFeedback(action: FeedbackAction, evaluatedAtMs: Long) {
        reconcileAdmittedRiskReplacement()
        if (matchesIssuedDelivery(action, evaluatedAtMs)) {
            rejectUndeliveredFeedback(action.trackId, evaluatedAtMs)
        }
    }

    @Synchronized
    fun rejectUndeliveredFeedback(trackId: String, evaluatedAtMs: Long) {
        reconcileAdmittedRiskReplacement()
        val key = PendingDeliveryKey(trackId, evaluatedAtMs)
        if (pendingRiskReplacement?.key == key) {
            discardRiskReplacement()
            return
        }
        val snapshot = pendingDeliverySnapshots.remove(key) ?: return
        snapshot.owner.retire()
        if (pendingRiskReplacement?.snapshot?.owner?.replacesDeliveryOwner === snapshot.owner) {
            discardRiskReplacement()
        }
        rollbackCooldowns(key, snapshot)
    }

    @Synchronized
    fun cancelPendingFeedbackDeliveries() {
        reconcileAdmittedRiskReplacement()
        discardRiskReplacement()
        pendingDeliverySnapshots.toMap().forEach { (key, snapshot) ->
            pendingDeliverySnapshots.remove(key)
            snapshot.owner.retire()
            rollbackCooldowns(key, snapshot)
        }
        queuedFeedback.clear()
    }

    @Synchronized
    fun cancelFeedbackForTracks(trackIds: Set<String>) {
        reconcileAdmittedRiskReplacement()
        if (pendingRiskReplacement?.key?.trackId in trackIds) discardRiskReplacement()
        pendingDeliverySnapshots.toMap().forEach { (key, snapshot) ->
            if (key.trackId in trackIds) {
                pendingDeliverySnapshots.remove(key)
                snapshot.owner.retire()
                rollbackCooldowns(key, snapshot)
            }
        }
        queuedFeedback.removeAll { it.candidate.trackId in trackIds }
    }

    /** Share only completed history after the caller proves a continuous cross-source region.
     * A reservation/claim stays with its original source and cannot become a completed delivery.
     */
    @Synchronized
    fun carryCompletedFeedbackHistory(sourceTrackId: String, targetTrackId: String) {
        val source = completedTrackHistory[sourceTrackId] ?: return
        if (source.confirmedAtMs <= (completedTrackHistory[targetTrackId]?.confirmedAtMs ?: Long.MIN_VALUE) ||
            pendingDeliverySnapshots.keys.any { it.trackId == targetTrackId }
        ) return
        // Carry message/cooldown history, but never cross-source metric risk observations.
        completedTrackHistory[targetTrackId] = source.copy(riskEvidence = null)
        lastTrackEmitAt[targetTrackId] = source.confirmedAtMs
    }

    /** Invalidate one producer context without cancelling another source's accepted output. */
    @Synchronized
    fun cancelFeedbackForTrackPrefix(trackIdPrefix: String) {
        require(trackIdPrefix.isNotEmpty())
        val trackIds = pendingDeliverySnapshots.keys.map { it.trackId } +
            queuedFeedback.map { it.candidate.trackId }
        cancelFeedbackForTracks(trackIds.filter { it.startsWith(trackIdPrefix) }.toSet())
    }

    /**
     * S11-15: a visual tracking generation ended (display geometry, epoch or tracker replaced).
     * Its queued feedback and reservations that have not started are discarded; an utterance that
     * already started (claimed) is not cut and still completes (S13-7).
     */
    @Synchronized
    fun discardUnstartedFeedbackForTrackPrefix(trackIdPrefix: String) {
        require(trackIdPrefix.isNotEmpty())
        reconcileAdmittedRiskReplacement()
        if (pendingRiskReplacement?.key?.trackId?.startsWith(trackIdPrefix) == true) discardRiskReplacement()
        pendingDeliverySnapshots.toMap().forEach { (key, snapshot) ->
            if (key.trackId.startsWith(trackIdPrefix) && snapshot.state == DeliveryState.RESERVED) {
                pendingDeliverySnapshots.remove(key)
                snapshot.owner.retire()
                rollbackCooldowns(key, snapshot)
            }
        }
        queuedFeedback.removeAll { it.candidate.trackId.startsWith(trackIdPrefix) }
    }

    @Synchronized
    fun resetForNewWalk() {
        discardRiskReplacement()
        pendingDeliverySnapshots.values.forEach { it.owner.retire() }
        pendingDeliverySnapshots.clear()
        queuedFeedback.clear()
        lastTrackEmitAt.clear()
        completedTrackHistory.clear()
        nextQueueSequence = 0L
        nextDeliveryOrder = 0L
        lastGlobalEmitAt = null
        lastGlobalDeliveredLevel = null
        lastRiskEmitAt = null
        lastCandidateSeenAt = null
        consecutiveSafeFrames = 0
        outputRejectionCounts.clear()
        lastOutputRejections = emptyMap()
        lastOutputEvaluation = null
    }

    private fun rollbackCooldowns(key: PendingDeliveryKey, snapshot: CooldownSnapshot) {
        val trackId = key.trackId
        val evaluatedAtMs = key.evaluatedAtMs
        if (lastTrackEmitAt[trackId] == evaluatedAtMs) {
            snapshot.previousTrackEmitAt?.let { lastTrackEmitAt[trackId] = it } ?: lastTrackEmitAt.remove(trackId)
        }
        if (lastGlobalEmitAt == evaluatedAtMs) lastGlobalEmitAt = snapshot.previousGlobalEmitAt
        if (snapshot.changedRiskEmitAt && lastRiskEmitAt == evaluatedAtMs) {
            lastRiskEmitAt = snapshot.previousRiskEmitAt
        }
    }

    private fun reconcilePendingDelivery(candidates: List<FeedbackCandidate>) {
        val pending = pendingDeliverySnapshots.entries.firstOrNull() ?: return
        val sameTrackStillDeliverable = candidates.any { it.trackId == pending.key.trackId }
        val higherSeverityAvailable = candidates.any { it.level.ordinal > pending.value.level.ordinal }
        if (!higherSeverityAvailable && (pending.value.state == DeliveryState.CLAIMED || sameTrackStillDeliverable)) return
        pendingDeliverySnapshots.remove(pending.key)
        pending.value.owner.retire()
        discardRiskReplacement()
        rollbackCooldowns(pending.key, pending.value)
    }

    private fun discardRiskReplacement() {
        pendingRiskReplacement?.snapshot?.owner?.retire()
        pendingRiskReplacement = null
    }

    private fun matchesIssuedDelivery(action: FeedbackAction, evaluatedAtMs: Long): Boolean {
        val owner = action.deliveryOwner ?: return true
        if (owner.evaluatedAtMs != evaluatedAtMs || !owner.matches(action)) return false
        val key = PendingDeliveryKey(owner.trackId, evaluatedAtMs)
        val snapshot = pendingRiskReplacement?.takeIf { it.key == key }?.snapshot
            ?: pendingDeliverySnapshots[key]
        return snapshot?.owner === owner
    }

    private fun applyReservedCooldowns(key: PendingDeliveryKey, snapshot: CooldownSnapshot) {
        lastTrackEmitAt[key.trackId] = key.evaluatedAtMs
        lastGlobalEmitAt = key.evaluatedAtMs
        if (snapshot.changedRiskEmitAt) lastRiskEmitAt = key.evaluatedAtMs
    }

    private fun reconcileAdmittedRiskReplacement() {
        val proposal = pendingRiskReplacement ?: return
        if (!proposal.snapshot.owner.replacementWasAdmitted()) return
        val replacedOwner = proposal.snapshot.owner.replacesDeliveryOwner
        val old = pendingDeliverySnapshots.entries.firstOrNull { it.value.owner === replacedOwner }
        if (old == null || old.value.state != DeliveryState.CLAIMED ||
            proposal.snapshot.state != DeliveryState.CLAIMED
        ) {
            discardRiskReplacement()
            return
        }
        pendingRiskReplacement = null
        pendingDeliverySnapshots.remove(old.key)
        old.value.owner.retire()
        rollbackCooldowns(old.key, old.value)
        val admitted = proposal.snapshot.copy(
            previousTrackEmitAt = lastTrackEmitAt[proposal.key.trackId],
            previousGlobalEmitAt = lastGlobalEmitAt,
            previousRiskEmitAt = lastRiskEmitAt,
        )
        pendingDeliverySnapshots[proposal.key] = admitted
        applyReservedCooldowns(proposal.key, admitted)
    }

    @Synchronized
    fun activeRiskTrackIds(
        candidates: List<FeedbackCandidate>,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
    ): Set<String> {
        if (!deviceGateAllowsAlerts) return emptySet()
        return activeQueryCandidates(candidates, nowMs)
            .filter { candidate ->
                candidate.alertableLevel &&
                    (isStable(candidate) || hasEmergencyStopEvidence(candidate))
            }
            .mapTo(linkedSetOf(), FeedbackCandidate::trackId)
    }

    @Synchronized
    fun activeFeedbackDeliveryKeys(
        candidates: List<FeedbackCandidate>,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
    ): Set<String> {
        if (!deviceGateAllowsAlerts) return emptySet()
        return activeQueryCandidates(candidates, nowMs)
            .filter { candidate ->
                isStable(candidate) || hasEmergencyStopEvidence(candidate)
            }
            .mapTo(linkedSetOf(), FeedbackCandidate::deliveryKey)
    }

    private fun activeQueryCandidates(candidates: List<FeedbackCandidate>, nowMs: Long): List<FeedbackCandidate> =
        eligibleCandidates(candidates.filter { isFreshAt(it, nowMs) })

    private fun isFreshAt(candidate: FeedbackCandidate, nowMs: Long): Boolean {
        if (candidate.stale || nowMs !in 0L until Long.MAX_VALUE ||
            candidate.validUntilMs !in nowMs until Long.MAX_VALUE
        ) return false
        val observedAt = candidate.observedAtElapsedRealtimeMs ?: return false
        val maxAgeMs = candidate.observationMaxAgeMs ?: return false
        if (observedAt !in 0L..nowMs || maxAgeMs !in 1L until Long.MAX_VALUE) return false
        return nowMs - observedAt <= minOf(maxAgeMs, config.maxObservationAgeMs)
    }

    /** Called only for admitted candidates; neither dispatch time nor a camera frame renews this. */
    private fun observationDeadlineMs(candidate: FeedbackCandidate): Long = minOf(
        candidate.validUntilMs,
        finiteDeadline(requireNotNull(candidate.observedAtElapsedRealtimeMs),
            minOf(requireNotNull(candidate.observationMaxAgeMs), config.maxObservationAgeMs)),
    )

    private fun finiteDeadline(observedAtMs: Long, durationMs: Long): Long =
        if (durationMs > Long.MAX_VALUE - 1L - observedAtMs) Long.MAX_VALUE - 1L
        else observedAtMs + durationMs

    @Synchronized
    fun canSpeakNavigation(nowMs: Long): Boolean {
        val lastGlobalAt = lastGlobalEmitAt ?: return true
        val lastRiskAt = lastRiskEmitAt
        if (lastRiskAt != null && nowMs - lastRiskAt < config.riskNavigationCooldownMs) return false
        return nowMs - lastGlobalAt >= config.globalIntervalMs
    }

    private fun markSafe() {
        consecutiveSafeFrames += 1
        if (consecutiveSafeFrames >= config.clearAfterSafeFrames) {
            clearActiveState()
        }
    }

    private fun eligibleCandidates(candidates: List<FeedbackCandidate>): List<FeedbackCandidate> =
        candidates.filter { candidate -> outputRejection(candidate) == null }

    /** The output gate itself; thresholds are unchanged, only the reason is now named. */
    internal fun outputRejection(candidate: FeedbackCandidate): FeedbackOutputRejection? = when {
        candidate.message.isBlank() -> FeedbackOutputRejection.BLANK_MESSAGE
        candidate.source == DepthSource.POLYGON_TREND_PSEUDO_DEPTH -> if (
            candidate.level == MessageLevel.CAUTION &&
            candidate.confidence.isFinite() && candidate.confidence > 0f &&
            candidate.riskDistanceM == null && candidate.approachTimeMs == null &&
            candidate.validUntilMs != Long.MAX_VALUE
        ) null else FeedbackOutputRejection.NON_METRIC_SOURCE
        !candidate.source.metric -> FeedbackOutputRejection.NON_METRIC_SOURCE
        !(candidate.confidence >= config.minConfidence) -> FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT
        !(candidate.alertableLevel || candidate.level == MessageLevel.INFO) -> FeedbackOutputRejection.LEVEL_NOT_OUTPUT
        else -> null
    }

    private fun recordOutputRejections(candidates: List<FeedbackCandidate>, observedCandidates: List<FeedbackCandidate>,
        deviceGateAllowsAlerts: Boolean, nowMs: Long) {
        // Preserve the cumulative counter's existing fresh, device-admitted output-gate meaning.
        val countedRejections = candidates.filter { deviceGateAllowsAlerts && isFreshAt(it, nowMs) }.mapNotNull { candidate ->
            outputRejection(candidate)?.let { candidate.trackId to it }
        }.toMap()
        countedRejections.values.forEach { reason ->
            outputRejectionCounts[reason] = (outputRejectionCounts[reason] ?: 0L) + 1L
        }
        val current = linkedMapOf<String, MutableSet<FeedbackOutputRejection>>()
        observedCandidates.forEach { candidate ->
            val reasons = linkedSetOf<FeedbackOutputRejection>()
            if (!deviceGateAllowsAlerts) reasons += FeedbackOutputRejection.DEVICE_GATE_BLOCKED
            if (candidate.validUntilMs !in 0L until Long.MAX_VALUE) {
                reasons += FeedbackOutputRejection.MISSING_CAPTURE_LEASE
            } else if (!isFreshAt(candidate, nowMs)) {
                reasons += FeedbackOutputRejection.OBSERVATION_NOT_FRESH
            }
            outputRejection(candidate)?.let { reasons += it }
            if (reasons.isNotEmpty()) current.getOrPut(candidate.trackId) { linkedSetOf() }.addAll(reasons)
        }
        val immutableReasons: Map<String, Set<FeedbackOutputRejection>> = current.mapValues { (_, reasons) ->
            Collections.unmodifiableSet(LinkedHashSet(reasons))
        }
        lastOutputRejections = Collections.unmodifiableMap(immutableReasons.mapValues { (_, reasons) -> reasons.first() })
        lastOutputEvaluation = FeedbackOutputEvaluationSnapshot(
            evaluatedAtMs = nowMs,
            minimumOutputConfidence = config.minConfidence,
            deviceGateAllowsAlerts = deviceGateAllowsAlerts,
            frameRejection = if (deviceGateAllowsAlerts) null else FeedbackOutputRejection.DEVICE_GATE_BLOCKED,
            rejectionsByTrack = Collections.unmodifiableMap(immutableReasons),
        )
    }

    /** Cumulative per-walk counts of generated candidates refused by the output gate. */
    @Synchronized
    fun outputRejectionCounts(): Map<FeedbackOutputRejection, Long> = outputRejectionCounts.toMap()

    /** Refusal reasons of the latest evaluated frame, by track; a refused message was not delivered. */
    @Synchronized
    fun lastOutputRejections(): Map<String, FeedbackOutputRejection> = lastOutputRejections

    @Synchronized
    fun lastOutputEvaluation(): FeedbackOutputEvaluationSnapshot? = lastOutputEvaluation

    private fun markMissing(nowMs: Long) {
        val lastSeen = lastCandidateSeenAt
        if (lastSeen == null || nowMs - lastSeen >= config.clearAfterMissingMs) {
            clearActiveState()
        }
    }

    private fun clearActiveState() {
        discardRiskReplacement()
        val claimedTracks = linkedSetOf<String>()
        pendingDeliverySnapshots.toMap().forEach { (key, snapshot) ->
            if (snapshot.state == DeliveryState.CLAIMED) {
                pendingDeliverySnapshots[key] = snapshot.copy(previousTrackEmitAt = null)
                claimedTracks += key.trackId
            } else {
                pendingDeliverySnapshots.remove(key)
                snapshot.owner.retire()
                rollbackCooldowns(key, snapshot)
            }
        }
        lastTrackEmitAt.keys.retainAll(claimedTracks)
        completedTrackHistory.clear()
        queuedFeedback.clear()
        consecutiveSafeFrames = 0
        lastCandidateSeenAt = null
    }
}

object VibrationPatterns {
    fun forLevel(level: MessageLevel): LongArray? {
        return when (level) {
            MessageLevel.STOP -> longArrayOf(0L, 180L, 80L, 240L, 80L, 320L)
            MessageLevel.WARNING -> longArrayOf(0L, 220L)
            MessageLevel.CAUTION,
            MessageLevel.AWARE,
            MessageLevel.INFO,
            MessageLevel.NONE,
            -> null
        }
    }
}

fun TrackedObjectDepth.toFeedbackCandidate(stale: Boolean = false): FeedbackCandidate? {
    val message = userFacing.message ?: return null
    return FeedbackCandidate(
        trackId = trackId,
        message = message,
        level = userFacing.messageLevel,
        source = source,
        confidence = confidence.finalScore,
        timestampMs = timestampMs,
        trackAgeFrames = trackAgeFrames,
        trackStableMs = trackStableMs,
        stale = stale,
        validUntilMs = sourceValidUntilElapsedRealtimeMs ?: Long.MIN_VALUE,
        timeToCollisionMs = approachTimeMs?.takeIf { source.metric && trend == kr.co.hanium.dreamup.walksafe.depth.Trend.APPROACHING },
        riskDistanceM = riskDistanceM?.takeIf { source.metric },
        observedAtElapsedRealtimeMs = sourceObservedAtElapsedRealtimeMs,
        observationMaxAgeMs = sourceMaxAgeMs,
        collisionAssessment = collisionAssessment,
    )
}
