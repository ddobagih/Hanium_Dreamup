package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth

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
) {
    val alertableLevel: Boolean
        get() = level == MessageLevel.CAUTION || level == MessageLevel.WARNING || level == MessageLevel.STOP

    val deliveryKey: String
        get() = "$trackId|${level.name}|$message"
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
        val state: DeliveryState = DeliveryState.RESERVED,
    )

    private data class QueuedFeedback(
        var candidate: FeedbackCandidate,
        val sequence: Long,
        var lastSeenAtMs: Long,
    )

    private val lastTrackEmitAt = mutableMapOf<String, Long>()
    private val lastTrackConfirmedAt = mutableMapOf<String, Long>()
    private val lastTrackDeliveredLevel = mutableMapOf<String, MessageLevel>()
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
            clearActiveState()
            return null
        }
        val freshCandidates = candidates.filterNot(FeedbackCandidate::stale)
        if (freshCandidates.isEmpty()) {
            reconcilePendingDelivery(emptyList())
            markMissing(nowMs)
            return null
        }
        lastCandidateSeenAt = nowMs
        val eligible = eligibleCandidates(freshCandidates)
        val stableEligible = eligible.filter { candidate ->
            candidate.trackAgeFrames >= config.minStableFrames && candidate.trackStableMs >= config.minStableMs
        }.sortedByDescending { it.level.ordinal }
            .distinctBy(FeedbackCandidate::trackId)
        reconcilePendingDelivery(stableEligible)
        refreshQueuedFeedback(stableEligible, nowMs)
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
            if (lastGlobalAt != null && nowMs - lastGlobalAt < config.globalIntervalMs &&
                !isUrgentEscalation(candidate.level, lastGlobalDeliveredLevel)
            ) return@firstOrNull false
            val lastTrackAt = lastTrackEmitAt[candidate.trackId]
            lastTrackAt == null || nowMs - lastTrackAt >= config.perTrackIntervalMs ||
                isUrgentEscalation(candidate.level, lastTrackDeliveredLevel[candidate.trackId])
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
        )
        lastTrackEmitAt[candidate.trackId] = nowMs
        lastGlobalEmitAt = nowMs
        if (candidate.alertableLevel) lastRiskEmitAt = nowMs
        return FeedbackAction(
            trackId = candidate.trackId,
            message = candidate.message,
            level = candidate.level,
            vibrationPatternMs = VibrationPatterns.forLevel(candidate.level),
            reason = if (candidate.level == MessageLevel.INFO) {
                "fresh_metric_path_guidance"
            } else {
                "fresh_metric_depth_alert"
            },
            validUntilMs = nowMs + config.pendingQueueTtlMs,
        )
    }

    private fun isUrgentEscalation(level: MessageLevel, deliveredLevel: MessageLevel?): Boolean =
        // Only an urgent warning or stop can interrupt a lower completed alert's cooldown.
        (level == MessageLevel.WARNING || level == MessageLevel.STOP) &&
            deliveredLevel != null && level.ordinal > deliveredLevel.ordinal

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
            lastTrackDeliveredLevel[trackId] = snapshot.level
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
    fun claimFeedbackDelivery(trackId: String, evaluatedAtMs: Long): Boolean {
        val key = PendingDeliveryKey(trackId, evaluatedAtMs)
        val snapshot = pendingDeliverySnapshots[key] ?: return false
        if (snapshot.state != DeliveryState.RESERVED) return false
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
        lastTrackConfirmedAt[targetTrackId] = completedAt
        lastTrackEmitAt[targetTrackId] = completedAt
        lastTrackDeliveredLevel[sourceTrackId]?.let { lastTrackDeliveredLevel[targetTrackId] = it }
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

    @Synchronized
    fun resetForNewWalk() {
        pendingDeliverySnapshots.clear()
        queuedFeedback.clear()
        lastTrackEmitAt.clear()
        lastTrackConfirmedAt.clear()
        lastTrackDeliveredLevel.clear()
        lastTrackDeliveryOrder.clear()
        nextQueueSequence = 0L
        nextDeliveryOrder = 0L
        lastGlobalEmitAt = null
        lastGlobalDeliveredLevel = null
        lastRiskEmitAt = null
        lastCandidateSeenAt = null
        consecutiveSafeFrames = 0
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
    ): Set<String> {
        if (!deviceGateAllowsAlerts) return emptySet()
        return eligibleCandidates(candidates.filterNot(FeedbackCandidate::stale))
            .filter { candidate ->
                candidate.alertableLevel &&
                    candidate.trackAgeFrames >= config.minStableFrames &&
                    candidate.trackStableMs >= config.minStableMs
            }
            .mapTo(linkedSetOf(), FeedbackCandidate::trackId)
    }

    @Synchronized
    fun activeFeedbackDeliveryKeys(
        candidates: List<FeedbackCandidate>,
        deviceGateAllowsAlerts: Boolean,
    ): Set<String> {
        if (!deviceGateAllowsAlerts) return emptySet()
        return eligibleCandidates(candidates.filterNot(FeedbackCandidate::stale))
            .filter { candidate ->
                candidate.trackAgeFrames >= config.minStableFrames &&
                    candidate.trackStableMs >= config.minStableMs
            }
            .mapTo(linkedSetOf(), FeedbackCandidate::deliveryKey)
    }

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
        candidates.filter { candidate ->
            candidate.source.metric &&
                candidate.confidence >= config.minConfidence &&
                candidate.message.isNotBlank() &&
                (candidate.alertableLevel || candidate.level == MessageLevel.INFO)
        }

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
    )
}
