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
    ) {
        val key: String
            get() = candidate.deliveryKey
    }

    private val lastTrackEmitAt = mutableMapOf<String, Long>()
    private val pendingDeliverySnapshots = mutableMapOf<PendingDeliveryKey, CooldownSnapshot>()
    private val queuedFeedback = mutableListOf<QueuedFeedback>()
    private var nextQueueSequence = 0L
    private var lastGlobalEmitAt: Long? = null
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
        if (lastGlobalAt != null && nowMs - lastGlobalAt < config.globalIntervalMs) return null
        val currentTrackIds = stableEligible.mapTo(hashSetOf(), FeedbackCandidate::trackId)
        val queued = queuedFeedback.firstOrNull { queued ->
            val candidate = queued.candidate
            if (candidate.trackId !in currentTrackIds) return@firstOrNull false
            if (nowMs - queued.lastSeenAtMs > config.pendingQueueTtlMs) return@firstOrNull false
            val lastTrackAt = lastTrackEmitAt[candidate.trackId]
            lastTrackAt == null || nowMs - lastTrackAt >= config.perTrackIntervalMs
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

    private fun refreshQueuedFeedback(candidates: List<FeedbackCandidate>, nowMs: Long) {
        queuedFeedback.removeAll { queued -> nowMs - queued.lastSeenAtMs > config.pendingQueueTtlMs }
        val pendingTrackId = pendingDeliverySnapshots.keys.firstOrNull()?.trackId
        queuedFeedback.removeAll { it.candidate.trackId == pendingTrackId }
        candidates.forEach { candidate ->
            if (candidate.trackId == pendingTrackId) return@forEach
            val key = candidate.deliveryKey
            val existing = queuedFeedback.firstOrNull { queued ->
                queued.candidate.trackId == candidate.trackId || queued.key == key
            }
            if (existing != null) {
                existing.candidate = candidate
                existing.lastSeenAtMs = nowMs
                return@forEach
            }
            val occupied = queuedFeedback.size + pendingDeliverySnapshots.size
            if (occupied >= config.pendingQueueCapacity) {
                val lowest = queuedFeedback.minWithOrNull(
                    compareBy<QueuedFeedback> { it.candidate.level.ordinal }
                        .thenByDescending(QueuedFeedback::sequence),
                ) ?: return@forEach
                if (candidate.level.ordinal <= lowest.candidate.level.ordinal) return@forEach
                queuedFeedback.remove(lowest)
            }
            queuedFeedback += QueuedFeedback(
                candidate = candidate,
                sequence = nextQueueSequence++,
                lastSeenAtMs = nowMs,
            )
        }
        queuedFeedback.sortWith(
            compareByDescending<QueuedFeedback> { it.candidate.level.ordinal }
                .thenBy(QueuedFeedback::sequence),
        )
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
        if (lastTrackEmitAt[trackId] == evaluatedAtMs) lastTrackEmitAt[trackId] = confirmedAtMs
        if (lastGlobalEmitAt == evaluatedAtMs) lastGlobalEmitAt = confirmedAtMs
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
    fun resetForNewWalk() {
        pendingDeliverySnapshots.clear()
        queuedFeedback.clear()
        lastTrackEmitAt.clear()
        nextQueueSequence = 0L
        lastGlobalEmitAt = null
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
