package kr.co.hanium.dreamup.walksafe.navigation.positioning

data class PositionConfidencePolicyConfig(
    val requiredHighRecoverySamples: Int = 2,
    val maximumHighRecoverySampleGapMs: Long = 5_000L,
    val repeatedAnnouncementIntervalMs: Long = 30_000L,
    val maximumSampleAgeMs: Long = 10_000L,
) {
    init {
        require(requiredHighRecoverySamples >= 2)
        require(maximumHighRecoverySampleGapMs > 0L)
        require(repeatedAnnouncementIntervalMs > 0L)
        require(maximumSampleAgeMs >= 0L)
    }
}

enum class PositionConfidenceTransition {
    INITIALIZED,
    STABLE,
    QUALITY_CHANGED,
    DEGRADED,
    ESCALATED,
    RECOVERY_PENDING,
    RECOVERED,
    FAIL_CLOSED,
}

enum class PositionConfidenceFailureReason {
    INVALID_TIME,
    STALE,
    OUT_OF_ORDER,
}

enum class PositionConfidenceAnnouncementKind {
    LOW,
    UNAVAILABLE,
    RECOVERED,
}

data class PositionConfidenceAnnouncementRequest(
    val token: Long,
    val episodeId: Long,
    val kind: PositionConfidenceAnnouncementKind,
    val issuedAtElapsedRealtimeMs: Long,
    val repeated: Boolean,
)

data class PositionConfidenceDecision(
    val quality: PositionQuality,
    val previousQuality: PositionQuality?,
    val transition: PositionConfidenceTransition,
    val guidancePaused: Boolean,
    val degradedEpisodeId: Long?,
    val consecutiveHighRecoverySamples: Int,
    val failureReason: PositionConfidenceFailureReason? = null,
    val announcementRequest: PositionConfidenceAnnouncementRequest? = null,
)

/**
 * Turns estimator quality into a fail-closed guidance and announcement state.
 * Announcement requests remain pending until the caller confirms actual delivery.
 */
class PositionConfidencePolicy(
    private val config: PositionConfidencePolicyConfig = PositionConfidencePolicyConfig(),
) {
    private var currentQuality: PositionQuality? = null
    private var lastSampleAtMs: Long? = null
    private var lastEvaluationAtMs: Long? = null
    private var degradedEpisodeId: Long? = null
    private var nextEpisodeId = 0L
    private var nextAnnouncementToken = 0L
    private var consecutiveHighSamples = 0
    private var lastHighSampleAtMs: Long? = null
    private var pendingAnnouncement: PositionConfidenceAnnouncementRequest? = null
    private var lastDegradationAnnouncementDeliveredAtMs: Long? = null
    private var unavailableAnnouncementDelivered = false

    fun reset() {
        currentQuality = null
        lastSampleAtMs = null
        lastEvaluationAtMs = null
        degradedEpisodeId = null
        consecutiveHighSamples = 0
        lastHighSampleAtMs = null
        pendingAnnouncement = null
        lastDegradationAnnouncementDeliveredAtMs = null
        unavailableAnnouncementDelivered = false
    }

    fun observe(
        quality: PositionQuality,
        sampleAtElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long = sampleAtElapsedRealtimeMs,
    ): PositionConfidenceDecision {
        val failure = validateTime(sampleAtElapsedRealtimeMs, nowElapsedRealtimeMs)
        if (failure != null) {
            val safeNowMs = maxOf(lastEvaluationAtMs ?: 0L, nowElapsedRealtimeMs.coerceAtLeast(0L))
            lastEvaluationAtMs = safeNowMs
            if (
                failure == PositionConfidenceFailureReason.STALE &&
                sampleAtElapsedRealtimeMs > (lastSampleAtMs ?: -1L)
            ) {
                lastSampleAtMs = sampleAtElapsedRealtimeMs
            }
            return applyQuality(
                quality = PositionQuality.UNAVAILABLE,
                nowMs = safeNowMs,
                failureReason = failure,
            )
        }

        lastSampleAtMs = sampleAtElapsedRealtimeMs
        lastEvaluationAtMs = nowElapsedRealtimeMs
        return applyQuality(quality, nowElapsedRealtimeMs, failureReason = null)
    }

    fun commitAnnouncementDelivered(
        token: Long,
        deliveredAtElapsedRealtimeMs: Long,
    ): Boolean {
        val request = pendingAnnouncement ?: return false
        if (request.token != token || deliveredAtElapsedRealtimeMs < request.issuedAtElapsedRealtimeMs) {
            return false
        }
        pendingAnnouncement = null
        when (request.kind) {
            PositionConfidenceAnnouncementKind.LOW -> {
                lastDegradationAnnouncementDeliveredAtMs = deliveredAtElapsedRealtimeMs
            }
            PositionConfidenceAnnouncementKind.UNAVAILABLE -> {
                lastDegradationAnnouncementDeliveredAtMs = deliveredAtElapsedRealtimeMs
                unavailableAnnouncementDelivered = true
            }
            PositionConfidenceAnnouncementKind.RECOVERED -> Unit
        }
        return true
    }

    private fun validateTime(
        sampleAtMs: Long,
        nowMs: Long,
    ): PositionConfidenceFailureReason? {
        if (sampleAtMs < 0L || nowMs < 0L || nowMs < sampleAtMs) {
            return PositionConfidenceFailureReason.INVALID_TIME
        }
        if (lastEvaluationAtMs?.let { nowMs < it } == true) {
            return PositionConfidenceFailureReason.INVALID_TIME
        }
        if (lastSampleAtMs?.let { sampleAtMs <= it } == true) {
            return PositionConfidenceFailureReason.OUT_OF_ORDER
        }
        if (nowMs - sampleAtMs > config.maximumSampleAgeMs) {
            return PositionConfidenceFailureReason.STALE
        }
        return null
    }

    private fun applyQuality(
        quality: PositionQuality,
        nowMs: Long,
        failureReason: PositionConfidenceFailureReason?,
    ): PositionConfidenceDecision {
        val previous = currentQuality
        currentQuality = quality
        var transition = when {
            previous == null -> PositionConfidenceTransition.INITIALIZED
            previous == quality -> PositionConfidenceTransition.STABLE
            else -> PositionConfidenceTransition.QUALITY_CHANGED
        }
        var decisionEpisodeId = degradedEpisodeId

        if (quality == PositionQuality.LOW || quality == PositionQuality.UNAVAILABLE) {
            clearRecoveryEvidence()
            if (degradedEpisodeId == null) {
                degradedEpisodeId = newEpisodeId()
                decisionEpisodeId = degradedEpisodeId
                pendingAnnouncement = null
                lastDegradationAnnouncementDeliveredAtMs = null
                unavailableAnnouncementDelivered = false
                transition = PositionConfidenceTransition.DEGRADED
                requestDegradationAnnouncement(quality, nowMs, repeated = false)
            } else if (
                quality == PositionQuality.UNAVAILABLE &&
                !unavailableAnnouncementDelivered &&
                pendingAnnouncement?.kind != PositionConfidenceAnnouncementKind.UNAVAILABLE
            ) {
                transition = PositionConfidenceTransition.ESCALATED
                requestDegradationAnnouncement(quality, nowMs, repeated = false)
            } else {
                maybeRequestRepeatedAnnouncement(quality, nowMs)
            }
        } else if (degradedEpisodeId != null) {
            decisionEpisodeId = degradedEpisodeId
            if (quality == PositionQuality.HIGH) {
                val previousHighAtMs = lastHighSampleAtMs
                consecutiveHighSamples = if (
                    previousHighAtMs != null &&
                    nowMs - previousHighAtMs in 1L..config.maximumHighRecoverySampleGapMs
                ) {
                    consecutiveHighSamples + 1
                } else {
                    1
                }
                lastHighSampleAtMs = nowMs
                if (consecutiveHighSamples >= config.requiredHighRecoverySamples) {
                    val recoveredEpisodeId = requireNotNull(degradedEpisodeId)
                    degradedEpisodeId = null
                    clearRecoveryEvidence()
                    transition = PositionConfidenceTransition.RECOVERED
                    pendingAnnouncement = newAnnouncement(
                        episodeId = recoveredEpisodeId,
                        kind = PositionConfidenceAnnouncementKind.RECOVERED,
                        nowMs = nowMs,
                        repeated = false,
                    )
                    lastDegradationAnnouncementDeliveredAtMs = null
                    unavailableAnnouncementDelivered = false
                } else {
                    transition = PositionConfidenceTransition.RECOVERY_PENDING
                }
            } else {
                clearRecoveryEvidence()
            }
        } else {
            clearRecoveryEvidence()
        }

        if (failureReason != null) transition = PositionConfidenceTransition.FAIL_CLOSED
        return PositionConfidenceDecision(
            quality = quality,
            previousQuality = previous,
            transition = transition,
            guidancePaused = degradedEpisodeId != null,
            degradedEpisodeId = decisionEpisodeId,
            consecutiveHighRecoverySamples = consecutiveHighSamples,
            failureReason = failureReason,
            announcementRequest = pendingAnnouncement,
        )
    }

    private fun maybeRequestRepeatedAnnouncement(quality: PositionQuality, nowMs: Long) {
        if (pendingAnnouncement != null) return
        val lastDeliveredAtMs = lastDegradationAnnouncementDeliveredAtMs ?: return
        if (nowMs - lastDeliveredAtMs < config.repeatedAnnouncementIntervalMs) return
        requestDegradationAnnouncement(quality, nowMs, repeated = true)
    }

    private fun requestDegradationAnnouncement(
        quality: PositionQuality,
        nowMs: Long,
        repeated: Boolean,
    ) {
        val episodeId = requireNotNull(degradedEpisodeId)
        val kind = if (quality == PositionQuality.UNAVAILABLE) {
            PositionConfidenceAnnouncementKind.UNAVAILABLE
        } else {
            PositionConfidenceAnnouncementKind.LOW
        }
        pendingAnnouncement = newAnnouncement(episodeId, kind, nowMs, repeated)
    }

    private fun newAnnouncement(
        episodeId: Long,
        kind: PositionConfidenceAnnouncementKind,
        nowMs: Long,
        repeated: Boolean,
    ): PositionConfidenceAnnouncementRequest = PositionConfidenceAnnouncementRequest(
        token = ++nextAnnouncementToken,
        episodeId = episodeId,
        kind = kind,
        issuedAtElapsedRealtimeMs = nowMs,
        repeated = repeated,
    )

    private fun newEpisodeId(): Long = ++nextEpisodeId

    private fun clearRecoveryEvidence() {
        consecutiveHighSamples = 0
        lastHighSampleAtMs = null
    }
}
