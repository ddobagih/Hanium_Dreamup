package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicyConfig
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth

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
)

data class FeedbackCandidate(
    val trackId: String,
    val message: String,
    val level: MessageLevel,
    val source: DepthSource,
    val confidence: Float,
    val timestampMs: Long,
    val trackAgeFrames: Int,
    val trackStableMs: Long,
    val stale: Boolean,
    /** Explicit monotonic-clock lease; camera timestamps must be translated by the producer. */
    val validUntilMs: Long = Long.MAX_VALUE,
    val timeToCollisionMs: Long? = null,
    val riskDistanceM: Float? = null,
) {
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
    /** Metric source below the output confidence (config.minConfidence). */
    CONFIDENCE_BELOW_OUTPUT,
    LEVEL_NOT_OUTPUT,
}

data class FeedbackAction(
    val trackId: String,
    val message: String,
    val level: MessageLevel,
    val vibrationPatternMs: LongArray?,
    val reason: String,
    val validUntilMs: Long = Long.MAX_VALUE,
) {
    val deliveryKey: String
        get() = "$trackId|${level.name}|$message"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeedbackAction) return false
        return trackId == other.trackId &&
            message == other.message &&
            level == other.level &&
            vibrationPatternMs.contentEquals(other.vibrationPatternMs) &&
            reason == other.reason &&
            validUntilMs == other.validUntilMs
    }

    override fun hashCode(): Int {
        var result = trackId.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + (vibrationPatternMs?.contentHashCode() ?: 0)
        result = 31 * result + reason.hashCode()
        result = 31 * result + validUntilMs.hashCode()
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
        val state: DeliveryState = DeliveryState.RESERVED,
        val message: String = "",
        /** Raw CAUTION distance at delivery; only a re-announce baseline, never level evidence. */
        val cautionDistanceM: Float? = null,
    )

    private data class RiskEvidence(
        val source: DepthSource,
        val timestampMs: Long,
        val validUntilMs: Long,
        val ttcMs: Long?,
        val distanceM: Float,
    )

    private data class QueuedFeedback(
        var candidate: FeedbackCandidate,
        val sequence: Long,
        var lastSeenAtMs: Long,
    )

    private val lastTrackEmitAt = mutableMapOf<String, Long>()
    private val lastTrackConfirmedAt = mutableMapOf<String, Long>()
    private val lastTrackDeliveredLevel = mutableMapOf<String, MessageLevel>()
    private val lastTrackDeliveredMessage = mutableMapOf<String, String>()
    private val riskThresholds = MessagePolicyConfig()
    private val lastTrackDeliveredRisk = mutableMapOf<String, RiskEvidence>()
    private val lastTrackDeliveredCautionDistance = mutableMapOf<String, Float>()
    private val lastTrackDeliveryOrder = mutableMapOf<String, Long>()
    private val pendingDeliverySnapshots = mutableMapOf<PendingDeliveryKey, CooldownSnapshot>()
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

    init {
        require(config.pendingQueueCapacity > 0) { "pendingQueueCapacity must be positive" }
        require(config.pendingQueueTtlMs > 0L) { "pendingQueueTtlMs must be positive" }
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
    ): FeedbackAction? {
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
                rollbackCooldowns(key, snapshot)
            }
        }
        val freshCandidates = candidates.filter { !it.stale && nowMs <= it.validUntilMs }
        pruneCompletedHistory(nowMs, freshCandidates)
        if (freshCandidates.isEmpty()) {
            reconcilePendingDelivery(emptyList())
            markMissing(nowMs)
            return null
        }
        lastCandidateSeenAt = nowMs
        recordOutputRejections(freshCandidates)
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
        if (pendingDeliverySnapshots.isNotEmpty()) return null
        val lastGlobalAt = lastGlobalEmitAt
        val currentTrackIds = stableEligible.mapTo(hashSetOf(), FeedbackCandidate::trackId)
        val queued = queuedFeedback.firstOrNull { queued ->
            val candidate = queued.candidate
            if (candidate.trackId !in currentTrackIds) return@firstOrNull false
            if (nowMs - queued.lastSeenAtMs > config.pendingQueueTtlMs) return@firstOrNull false
            val lastTrackAt = lastTrackEmitAt[candidate.trackId]
            val trackRateLimited = lastTrackAt != null && nowMs - lastTrackAt < config.perTrackIntervalMs
            val sameLevelWorsening = trackRateLimited && isSameLevelRiskWorsening(candidate)
            if (lastGlobalAt != null && nowMs - lastGlobalAt < config.globalIntervalMs &&
                (sameLevelWorsening || !isUrgentEscalation(candidate.level, lastGlobalDeliveredLevel))
            ) return@firstOrNull false
            !trackRateLimited ||
                isUrgentEscalation(candidate.level, lastTrackDeliveredLevel[candidate.trackId]) || sameLevelWorsening
        } ?: return null
        val candidate = queued.candidate
        val lastTrackAt = lastTrackEmitAt[candidate.trackId]
        queuedFeedback.remove(queued)
        pendingDeliverySnapshots[PendingDeliveryKey(candidate.trackId, nowMs)] = CooldownSnapshot(
            previousTrackEmitAt = lastTrackAt,
            previousGlobalEmitAt = lastGlobalEmitAt,
            previousRiskEmitAt = lastRiskEmitAt,
            changedRiskEmitAt = candidate.alertableLevel,
            level = candidate.level,
            validUntilMs = minOf(candidate.validUntilMs, nowMs + config.pendingQueueTtlMs),
            riskEvidence = riskEvidence(candidate),
            message = candidate.message,
            cautionDistanceM = cautionDistance(candidate),
        )
        lastTrackEmitAt[candidate.trackId] = nowMs
        lastGlobalEmitAt = nowMs
        if (candidate.alertableLevel) lastRiskEmitAt = nowMs
        return FeedbackAction(
            trackId = candidate.trackId,
            message = candidate.message,
            level = candidate.level,
            vibrationPatternMs = VibrationPatterns.forLevel(candidate.level),
            reason = when {
                candidate.source == DepthSource.POLYGON_TREND_PSEUDO_DEPTH -> "fresh_qualitative_depth_caution"
                candidate.level == MessageLevel.INFO -> "fresh_metric_path_guidance"
                else -> "fresh_metric_depth_alert"
            },
            validUntilMs = minOf(candidate.validUntilMs, nowMs + config.pendingQueueTtlMs),
        )
    }

    private fun isUrgentEscalation(level: MessageLevel, deliveredLevel: MessageLevel?): Boolean =
        // Only an urgent warning or stop can interrupt a lower completed alert's cooldown.
        (level == MessageLevel.WARNING || level == MessageLevel.STOP) &&
            deliveredLevel != null && level.ordinal > deliveredLevel.ordinal

    private fun isRepeatedCaution(candidate: FeedbackCandidate): Boolean {
        // A changed object/position phrase is a new situation; only the same CAUTION is withheld.
        if (candidate.level != MessageLevel.CAUTION ||
            lastTrackDeliveredLevel[candidate.trackId] != MessageLevel.CAUTION ||
            lastTrackDeliveredMessage[candidate.trackId] != candidate.message
        ) return false
        // Re-announce only when the same object moved markedly closer (a distance band crossing
        // with the existing hysteresis margin). Produced CAUTIONs are weak-metric (no trusted
        // evidence) or qualitative, so the raw CAUTION distance recorded at delivery is compared;
        // it only re-announces the same CAUTION and never raises a level (S13-18). A qualitative
        // CAUTION has no distance and stays withheld.
        val current = cautionDistance(candidate) ?: return true
        val previous = lastTrackDeliveredCautionDistance[candidate.trackId] ?: return false
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
        return RiskEvidence(candidate.source, candidate.timestampMs, candidate.validUntilMs,
            candidate.timeToCollisionMs?.takeIf { it >= 0L }, distance)
    }

    private fun isSameLevelRiskWorsening(candidate: FeedbackCandidate): Boolean {
        if ((candidate.level != MessageLevel.WARNING && candidate.level != MessageLevel.STOP) ||
            lastTrackDeliveredLevel[candidate.trackId] != candidate.level || !isStable(candidate)
        ) return false
        val previous = lastTrackDeliveredRisk[candidate.trackId] ?: return false
        val current = riskEvidence(candidate) ?: return false
        // The previous lease was checked when that alert started. Only the current observation
        // must still be live; camera timestamps are compared only within the same source.
        if (current.source != previous.source || current.timestampMs <= previous.timestampMs ||
            current.validUntilMs <= previous.validUntilMs
        ) return false
        val previousTtc = previous.ttcMs
        val currentTtc = current.ttcMs
        // A band crossing alone admits boundary jitter. Require at least a halving as well;
        // this is a replay guard, not a change to the existing warning/STOP thresholds.
        val ttcWorsened = previousTtc != null && currentTtc != null &&
            ttcRiskBand(currentTtc) > ttcRiskBand(previousTtc) && currentTtc <= previousTtc / 2L
        val distanceWorsened = distanceRiskBand(current.distanceM) > distanceRiskBand(previous.distanceM) &&
            previous.distanceM - current.distanceM >= riskThresholds.hysteresis.distanceMarginM
        return ttcWorsened || distanceWorsened
    }

    /**
     * A replay inside the per-track window continues the same alert episode, so its baseline keeps
     * the worst TTC and distance already announced. Otherwise a value that improved in the replay
     * frame could later count as a new worsening and let opposite jitter replay every 700 ms.
     */
    private fun deliveredRiskBaseline(
        trackId: String,
        snapshot: CooldownSnapshot,
        evaluatedAtMs: Long,
    ): RiskEvidence? {
        val current = snapshot.riskEvidence ?: return null
        val previous = lastTrackDeliveredRisk[trackId] ?: return current
        val previousEmitAt = snapshot.previousTrackEmitAt ?: return current
        val sameEpisode = previous.source == current.source &&
            lastTrackDeliveredLevel[trackId] == snapshot.level &&
            evaluatedAtMs - previousEmitAt < config.perTrackIntervalMs
        if (!sameEpisode) return current
        return current.copy(
            ttcMs = listOfNotNull(previous.ttcMs, current.ttcMs).minOrNull(),
            distanceM = minOf(previous.distanceM, current.distanceM),
        )
    }

    private fun ttcRiskBand(ttcMs: Long): Int = when {
        ttcMs <= EMERGENCY_STOP_TTC_MS -> 4
        ttcMs <= riskThresholds.ttcStopMs -> 3
        ttcMs <= riskThresholds.ttcWarningMs -> 2
        ttcMs <= riskThresholds.ttcAwareMs -> 1
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
        val completed = lastTrackConfirmedAt.entries.iterator()
        while (completed.hasNext()) {
            val (trackId, confirmedAtMs) = completed.next()
            if (trackId in protectedTrackIds || nowMs < confirmedAtMs ||
                nowMs - confirmedAtMs < config.perTrackIntervalMs
            ) continue
            // No current, queued, or in-flight use can observe this completed turn now.
            completed.remove()
            lastTrackEmitAt.remove(trackId)
            lastTrackDeliveredLevel.remove(trackId)
            lastTrackDeliveredMessage.remove(trackId)
            lastTrackDeliveredRisk.remove(trackId)
            lastTrackDeliveredCautionDistance.remove(trackId)
            lastTrackDeliveryOrder.remove(trackId)
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
            candidate.timeToCollisionMs?.let { it in 0L..EMERGENCY_STOP_TTC_MS } == true &&
            candidate.riskDistanceM?.let { it.isFinite() && it > 0f && it <= EMERGENCY_STOP_DISTANCE_M } == true

    private fun isEmergencyStop(candidate: FeedbackCandidate, nowMs: Long): Boolean =
        hasEmergencyStopEvidence(candidate) && candidate.validUntilMs - nowMs > 400L

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
            .thenBy { it.candidate.timeToCollisionMs?.takeIf { value -> value >= 0L } ?: Long.MAX_VALUE }
            .thenBy { it.candidate.riskDistanceM?.takeIf { value -> value.isFinite() && value > 0f } ?: Float.MAX_VALUE }
            .thenBy { lastTrackDeliveryOrder[it.candidate.trackId] ?: Long.MIN_VALUE }
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
        trackId: String,
        evaluatedAtMs: Long,
        deliveredAtMs: Long = evaluatedAtMs,
    ): Boolean {
        val key = PendingDeliveryKey(trackId, evaluatedAtMs)
        val snapshot = pendingDeliverySnapshots.remove(key) ?: return false
        val confirmedAtMs = deliveredAtMs.coerceAtLeast(evaluatedAtMs)
        if (lastTrackEmitAt[trackId] == evaluatedAtMs) {
            lastTrackEmitAt[trackId] = confirmedAtMs
            lastTrackConfirmedAt[trackId] = confirmedAtMs
            deliveredRiskBaseline(trackId, snapshot, evaluatedAtMs)?.let { lastTrackDeliveredRisk[trackId] = it }
                ?: lastTrackDeliveredRisk.remove(trackId)
            lastTrackDeliveredLevel[trackId] = snapshot.level
            lastTrackDeliveredMessage[trackId] = snapshot.message
            snapshot.cautionDistanceM?.let { lastTrackDeliveredCautionDistance[trackId] = it }
                ?: lastTrackDeliveredCautionDistance.remove(trackId)
            lastTrackDeliveryOrder[trackId] = nextDeliveryOrder++
        }
        if (lastGlobalEmitAt == evaluatedAtMs) {
            lastGlobalEmitAt = confirmedAtMs
            lastGlobalDeliveredLevel = snapshot.level
        }
        if (snapshot.changedRiskEmitAt && lastRiskEmitAt == evaluatedAtMs) lastRiskEmitAt = confirmedAtMs
        return true
    }

    @Synchronized
    fun claimFeedbackDelivery(trackId: String, evaluatedAtMs: Long, nowMs: Long): Boolean {
        val key = PendingDeliveryKey(trackId, evaluatedAtMs)
        val snapshot = pendingDeliverySnapshots[key] ?: return false
        if (snapshot.state != DeliveryState.RESERVED) return false
        if (nowMs > snapshot.validUntilMs) {
            pendingDeliverySnapshots.remove(key)
            rollbackCooldowns(key, snapshot)
            return false
        }
        pendingDeliverySnapshots[key] = snapshot.copy(state = DeliveryState.CLAIMED)
        return true
    }

    /** Read-only ownership check; it neither renews nor transfers the claimed delivery. */
    @Synchronized
    fun hasClaimedFeedbackDelivery(trackId: String, level: MessageLevel): Boolean =
        pendingDeliverySnapshots.any { (key, snapshot) ->
            key.trackId == trackId && snapshot.state == DeliveryState.CLAIMED && snapshot.level == level
        }

    @Synchronized
    fun rejectUndeliveredFeedback(trackId: String, evaluatedAtMs: Long) {
        val key = PendingDeliveryKey(trackId, evaluatedAtMs)
        val snapshot = pendingDeliverySnapshots.remove(key) ?: return
        rollbackCooldowns(key, snapshot)
    }

    @Synchronized
    fun cancelPendingFeedbackDeliveries() {
        pendingDeliverySnapshots.toMap().forEach { (key, snapshot) ->
            pendingDeliverySnapshots.remove(key)
            rollbackCooldowns(key, snapshot)
        }
        queuedFeedback.clear()
    }

    @Synchronized
    fun cancelFeedbackForTracks(trackIds: Set<String>) {
        pendingDeliverySnapshots.toMap().forEach { (key, snapshot) ->
            if (key.trackId in trackIds) {
                pendingDeliverySnapshots.remove(key)
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
        val completedAt = lastTrackConfirmedAt[sourceTrackId] ?: return
        if (completedAt <= (lastTrackConfirmedAt[targetTrackId] ?: Long.MIN_VALUE) ||
            pendingDeliverySnapshots.keys.any { it.trackId == targetTrackId }
        ) return
        // Cross-source continuity carries delivery timing, not comparable metric observations.
        lastTrackDeliveredRisk.remove(targetTrackId)
        lastTrackConfirmedAt[targetTrackId] = completedAt
        lastTrackEmitAt[targetTrackId] = completedAt
        lastTrackDeliveredLevel[sourceTrackId]?.let { lastTrackDeliveredLevel[targetTrackId] = it }
        lastTrackDeliveredMessage[sourceTrackId]?.let { lastTrackDeliveredMessage[targetTrackId] = it }
        // The CAUTION re-announce baseline follows the carried message (it never raises a level).
        lastTrackDeliveredCautionDistance[sourceTrackId]?.let { lastTrackDeliveredCautionDistance[targetTrackId] = it }
            ?: lastTrackDeliveredCautionDistance.remove(targetTrackId)
        lastTrackDeliveryOrder[sourceTrackId]?.let { lastTrackDeliveryOrder[targetTrackId] = it }
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
        pendingDeliverySnapshots.toMap().forEach { (key, snapshot) ->
            if (key.trackId.startsWith(trackIdPrefix) && snapshot.state == DeliveryState.RESERVED) {
                pendingDeliverySnapshots.remove(key)
                rollbackCooldowns(key, snapshot)
            }
        }
        queuedFeedback.removeAll { it.candidate.trackId.startsWith(trackIdPrefix) }
    }

    @Synchronized
    fun resetForNewWalk() {
        pendingDeliverySnapshots.clear()
        queuedFeedback.clear()
        lastTrackEmitAt.clear()
        lastTrackConfirmedAt.clear()
        lastTrackDeliveredLevel.clear()
        lastTrackDeliveredMessage.clear()
        lastTrackDeliveredRisk.clear()
        lastTrackDeliveredCautionDistance.clear()
        lastTrackDeliveryOrder.clear()
        nextQueueSequence = 0L
        nextDeliveryOrder = 0L
        lastGlobalEmitAt = null
        lastGlobalDeliveredLevel = null
        lastRiskEmitAt = null
        lastCandidateSeenAt = null
        consecutiveSafeFrames = 0
        outputRejectionCounts.clear()
        lastOutputRejections = emptyMap()
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
        rollbackCooldowns(pending.key, pending.value)
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
        eligibleCandidates(candidates.filter { candidate ->
            !candidate.stale && nowMs <= candidate.validUntilMs
        })

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
            candidate.riskDistanceM == null && candidate.timeToCollisionMs == null &&
            candidate.validUntilMs != Long.MAX_VALUE
        ) null else FeedbackOutputRejection.NON_METRIC_SOURCE
        !candidate.source.metric -> FeedbackOutputRejection.NON_METRIC_SOURCE
        !(candidate.confidence >= config.minConfidence) -> FeedbackOutputRejection.CONFIDENCE_BELOW_OUTPUT
        !(candidate.alertableLevel || candidate.level == MessageLevel.INFO) -> FeedbackOutputRejection.LEVEL_NOT_OUTPUT
        else -> null
    }

    private fun recordOutputRejections(freshCandidates: List<FeedbackCandidate>) {
        val rejections = freshCandidates.mapNotNull { candidate ->
            outputRejection(candidate)?.let { candidate.trackId to it }
        }.toMap()
        rejections.values.forEach { reason ->
            outputRejectionCounts[reason] = (outputRejectionCounts[reason] ?: 0L) + 1L
        }
        lastOutputRejections = rejections
    }

    /** Cumulative per-walk counts of generated candidates refused by the output gate. */
    @Synchronized
    fun outputRejectionCounts(): Map<FeedbackOutputRejection, Long> = outputRejectionCounts.toMap()

    /** Refusal reasons of the latest evaluated frame, by track; a refused message was not delivered. */
    @Synchronized
    fun lastOutputRejections(): Map<String, FeedbackOutputRejection> = lastOutputRejections

    private fun markMissing(nowMs: Long) {
        val lastSeen = lastCandidateSeenAt
        if (lastSeen == null || nowMs - lastSeen >= config.clearAfterMissingMs) {
            clearActiveState()
        }
    }

    private fun clearActiveState() {
        val claimedTracks = linkedSetOf<String>()
        pendingDeliverySnapshots.toMap().forEach { (key, snapshot) ->
            if (snapshot.state == DeliveryState.CLAIMED) {
                pendingDeliverySnapshots[key] = snapshot.copy(previousTrackEmitAt = null)
                claimedTracks += key.trackId
            } else {
                pendingDeliverySnapshots.remove(key)
                rollbackCooldowns(key, snapshot)
            }
        }
        lastTrackEmitAt.keys.retainAll(claimedTracks)
        lastTrackConfirmedAt.clear()
        lastTrackDeliveredLevel.clear()
        lastTrackDeliveredMessage.clear()
        lastTrackDeliveredRisk.clear()
        lastTrackDeliveredCautionDistance.clear()
        lastTrackDeliveryOrder.clear()
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
        timeToCollisionMs = timeToCollisionMs?.takeIf { source.metric && trend == kr.co.hanium.dreamup.walksafe.depth.Trend.APPROACHING },
        riskDistanceM = riskDistanceM?.takeIf { source.metric },
    )
}
