package kr.co.hanium.dreamup.walksafe.navigation.positioning

data class PositionConfidencePolicyConfig(
    // The confirmation count/gap applies to fresh HIGH/MEDIUM/LOW GNSS recovery.
    // Default continuity covers the existing raw-fix validity window; it does not renew a fix.
    val requiredHighRecoverySamples: Int = 2,
    val maximumHighRecoverySampleGapMs: Long = 10_000L,
    val repeatedAnnouncementIntervalMs: Long = 30_000L,
    val maximumSampleAgeMs: Long = 10_000L,
    val degradationConfirmationDurationMs: Long = 3_000L,
) {
    init {
        require(requiredHighRecoverySamples >= 2)
        require(maximumHighRecoverySampleGapMs > 0L)
        require(repeatedAnnouncementIntervalMs > 0L)
        require(maximumSampleAgeMs >= 0L)
        require(degradationConfirmationDurationMs >= 0L)
    }
}

enum class PositionConfidenceTransition {
    INITIALIZED,
    STABLE,
    QUALITY_CHANGED,
    DEGRADATION_PENDING,
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
    /** Raw quality remains unchanged while the interruption is being confirmed. */
    val degradationPendingSinceElapsedRealtimeMs: Long? = null,
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
    private var lastInformativeSampleAtMs: Long? = null
    private var lastMotionSampleAtMs: Long? = null
    private var lastEvaluationAtMs: Long? = null
    private var degradedEpisodeId: Long? = null
    private var nextEpisodeId = 0L
    private var nextAnnouncementToken = 0L
    private var consecutiveHighSamples = 0
    private var lastHighSampleAtMs: Long? = null
    private var consecutiveRecoverySamples = 0
    private var lastRecoverySampleAtMs: Long? = null
    private var pendingAnnouncement: PositionConfidenceAnnouncementRequest? = null
    private var lastDegradationAnnouncementDeliveredAtMs: Long? = null
    private var unavailableAnnouncementDelivered = false
    private var degradationPendingSinceMs: Long? = null
    private var hasUsablePosition = false
    private var episodeInterruptedUsablePosition = false

    fun reset() {
        currentQuality = null
        lastSampleAtMs = null
        lastInformativeSampleAtMs = null
        lastMotionSampleAtMs = null
        lastEvaluationAtMs = null
        degradedEpisodeId = null
        clearRecoveryEvidence()
        pendingAnnouncement = null
        lastDegradationAnnouncementDeliveredAtMs = null
        unavailableAnnouncementDelivered = false
        degradationPendingSinceMs = null
        hasUsablePosition = false
        episodeInterruptedUsablePosition = false
    }

    fun observe(
        quality: PositionQuality,
        sampleAtElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long = sampleAtElapsedRealtimeMs,
        informativeGnss: Boolean = true,
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
        if (informativeGnss) lastInformativeSampleAtMs = sampleAtElapsedRealtimeMs
        lastEvaluationAtMs = nowElapsedRealtimeMs
        if (informativeEvidenceExpired(nowElapsedRealtimeMs)) {
            return applyQuality(PositionQuality.UNAVAILABLE, nowElapsedRealtimeMs, PositionConfidenceFailureReason.STALE)
        }
        return applyQuality(
            quality, nowElapsedRealtimeMs, failureReason = null,
            recoverySampleAtMs = sampleAtElapsedRealtimeMs.takeIf { informativeGnss },
            gnssObservation = true,
        )
    }

    /** Motion may expose degradation; only GNSS measurements confirm recovery. */
    fun observeMotion(
        quality: PositionQuality,
        sampleAtElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long = sampleAtElapsedRealtimeMs,
    ): PositionConfidenceDecision {
        val failure = validateTime(sampleAtElapsedRealtimeMs, nowElapsedRealtimeMs, lastMotionSampleAtMs)
        if (failure != null) {
            val safeNowMs = maxOf(lastEvaluationAtMs ?: 0L, nowElapsedRealtimeMs.coerceAtLeast(0L))
            lastEvaluationAtMs = safeNowMs
            return applyQuality(PositionQuality.UNAVAILABLE, safeNowMs, failure)
        }
        lastMotionSampleAtMs = sampleAtElapsedRealtimeMs
        lastEvaluationAtMs = nowElapsedRealtimeMs
        if (informativeEvidenceExpired(nowElapsedRealtimeMs)) {
            return applyQuality(PositionQuality.UNAVAILABLE, nowElapsedRealtimeMs, PositionConfidenceFailureReason.STALE)
        }
        return applyQuality(quality, nowElapsedRealtimeMs, failureReason = null)
    }

    /** Pure ownership check: does not evaluate quality, advance time, or count recovery samples. */
    fun isAnnouncementCurrent(token: Long): Boolean = pendingAnnouncement?.token == token

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

    /** Watchdog reads expire evidence without counting a new GNSS/recovery sample. */
    fun evaluate(
        nowElapsedRealtimeMs: Long,
        observedQuality: PositionQuality? = null,
    ): PositionConfidenceDecision {
        val previousEvaluation = lastEvaluationAtMs
        if (nowElapsedRealtimeMs < 0L || previousEvaluation?.let { nowElapsedRealtimeMs < it } == true) {
            val safeNowMs = maxOf(previousEvaluation ?: 0L, nowElapsedRealtimeMs.coerceAtLeast(0L))
            lastEvaluationAtMs = safeNowMs
            return applyQuality(PositionQuality.UNAVAILABLE, safeNowMs, PositionConfidenceFailureReason.INVALID_TIME)
        }
        lastEvaluationAtMs = nowElapsedRealtimeMs
        val sampleAt = lastInformativeSampleAtMs ?: lastSampleAtMs
        // Before any quality input, a watchdog has no observed degradation to latch.
        // Keep outputs paused while waiting, without changing how the first real fix is admitted.
        if (sampleAt == null && currentQuality == null) {
            return PositionConfidenceDecision(
                quality = PositionQuality.UNAVAILABLE,
                previousQuality = null,
                transition = PositionConfidenceTransition.INITIALIZED,
                guidancePaused = true,
                degradedEpisodeId = null,
                consecutiveHighRecoverySamples = 0,
            )
        }
        val expired = sampleAt == null || nowElapsedRealtimeMs - sampleAt > config.maximumSampleAgeMs
        return applyQuality(
            if (expired) PositionQuality.UNAVAILABLE
            else observedQuality ?: currentQuality ?: PositionQuality.UNAVAILABLE,
            nowElapsedRealtimeMs,
            if (expired) PositionConfidenceFailureReason.STALE else null,
        )
    }

    private fun informativeEvidenceExpired(nowMs: Long): Boolean =
        lastInformativeSampleAtMs?.let { nowMs - it > config.maximumSampleAgeMs } == true

    private fun validateTime(
        sampleAtMs: Long,
        nowMs: Long,
        previousSampleAtMs: Long? = lastSampleAtMs,
    ): PositionConfidenceFailureReason? {
        if (sampleAtMs < 0L || nowMs < 0L || nowMs < sampleAtMs) {
            return PositionConfidenceFailureReason.INVALID_TIME
        }
        if (lastEvaluationAtMs?.let { nowMs < it } == true) {
            return PositionConfidenceFailureReason.INVALID_TIME
        }
        if (previousSampleAtMs?.let { sampleAtMs <= it } == true) {
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
        recoverySampleAtMs: Long? = null,
        gnssObservation: Boolean = false,
    ): PositionConfidenceDecision {
        val previous = currentQuality
        currentQuality = quality
        var transition = when {
            previous == null -> PositionConfidenceTransition.INITIALIZED
            previous == quality -> PositionConfidenceTransition.STABLE
            else -> PositionConfidenceTransition.QUALITY_CHANGED
        }
        var decisionEpisodeId = degradedEpisodeId

        if (quality == PositionQuality.UNAVAILABLE) {
            // A failed GNSS measurement interrupts confirmation. Ordinary prediction fluctuations
            // cannot erase actual GNSS evidence; expiry and invalid time still fail closed.
            if (gnssObservation || failureReason != null) clearRecoveryEvidence()
            // A new bad input retires an undelivered recovery notice, even during the grace period.
            if (pendingAnnouncement?.kind == PositionConfidenceAnnouncementKind.RECOVERED) {
                pendingAnnouncement = null
            }
            if (degradedEpisodeId == null && failureReason == null) {
                val pendingSince = degradationPendingSinceMs ?: nowMs.also {
                    degradationPendingSinceMs = it
                }
                if (nowMs - pendingSince < config.degradationConfirmationDurationMs) {
                    return PositionConfidenceDecision(
                        quality = quality,
                        previousQuality = previous,
                        transition = PositionConfidenceTransition.DEGRADATION_PENDING,
                        guidancePaused = !hasUsablePosition,
                        degradedEpisodeId = null,
                        consecutiveHighRecoverySamples = 0,
                        degradationPendingSinceElapsedRealtimeMs = pendingSince,
                    )
                }
            }
            degradationPendingSinceMs = null
            if (degradedEpisodeId == null) {
                degradedEpisodeId = newEpisodeId()
                episodeInterruptedUsablePosition = hasUsablePosition
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
            degradationPendingSinceMs = null
            decisionEpisodeId = degradedEpisodeId
            if (quality != PositionQuality.HIGH) {
                consecutiveHighSamples = 0
                lastHighSampleAtMs = null
            }
            if (quality == PositionQuality.HIGH && recoverySampleAtMs != null) {
                val previousHighAtMs = lastHighSampleAtMs
                consecutiveHighSamples = if (
                    previousHighAtMs != null &&
                    recoverySampleAtMs - previousHighAtMs in 1L..minOf(config.maximumHighRecoverySampleGapMs, config.maximumSampleAgeMs)
                ) {
                    consecutiveHighSamples + 1
                } else {
                    1
                }
                lastHighSampleAtMs = recoverySampleAtMs
            }
            if (recoverySampleAtMs != null) {
                // Usable quality keeps its precision label. Only distinct, fresh informative
                // GNSS may confirm recovery; motion/watchdogs cannot count another measurement.
                val previousRecoveryAtMs = lastRecoverySampleAtMs
                consecutiveRecoverySamples = if (
                    previousRecoveryAtMs != null &&
                    recoverySampleAtMs - previousRecoveryAtMs in 1L..minOf(config.maximumHighRecoverySampleGapMs, config.maximumSampleAgeMs)
                ) {
                    consecutiveRecoverySamples + 1
                } else {
                    1
                }
                lastRecoverySampleAtMs = recoverySampleAtMs
                if (consecutiveRecoverySamples >= config.requiredHighRecoverySamples) {
                    val recoveredEpisodeId = requireNotNull(degradedEpisodeId)
                    degradedEpisodeId = null
                    clearRecoveryEvidence()
                    transition = PositionConfidenceTransition.RECOVERED
                    pendingAnnouncement = if (
                        episodeInterruptedUsablePosition && lastDegradationAnnouncementDeliveredAtMs != null
                    ) newAnnouncement(
                        episodeId = recoveredEpisodeId,
                        kind = PositionConfidenceAnnouncementKind.RECOVERED,
                        nowMs = nowMs,
                        repeated = false,
                    ) else null
                    episodeInterruptedUsablePosition = false
                    lastDegradationAnnouncementDeliveredAtMs = null
                    unavailableAnnouncementDelivered = false
                } else {
                    transition = PositionConfidenceTransition.RECOVERY_PENDING
                }
            }
        } else {
            degradationPendingSinceMs = null
            clearRecoveryEvidence()
        }

        if (degradedEpisodeId == null &&
            quality != PositionQuality.UNAVAILABLE && recoverySampleAtMs != null
        ) hasUsablePosition = true

        if (failureReason != null) transition = PositionConfidenceTransition.FAIL_CLOSED
        return PositionConfidenceDecision(
            quality = quality,
            previousQuality = previous,
            transition = transition,
            guidancePaused = degradedEpisodeId != null || !hasUsablePosition,
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
        consecutiveRecoverySamples = 0
        lastRecoverySampleAtMs = null
    }
}
