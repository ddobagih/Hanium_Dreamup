package kr.co.hanium.dreamup.walksafe.device

data class RuntimeMetricPreflightProfile(
    val profileId: String,
    val maximumDurationMs: Long,
    val minimumDistinctFrames: Int,
    val minimumObservationSpanMs: Long,
    val minimumValidSamplesPerPassingFrame: Int,
    val minimumPassingPercent: Int,
    val minimumValidDistanceMeters: Double,
    val maximumValidDistanceMeters: Double,
) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(maximumDurationMs > 0L) { "maximumDurationMs must be positive" }
        require(minimumDistinctFrames > 0) { "minimumDistinctFrames must be positive" }
        require(
            minimumObservationSpanMs in 0L..maximumDurationMs &&
                (minimumObservationSpanMs > 0L || minimumDistinctFrames == 1),
        ) {
            "minimumObservationSpanMs must be within maximumDurationMs; zero requires a single-frame profile"
        }
        require(minimumValidSamplesPerPassingFrame > 0) {
            "minimumValidSamplesPerPassingFrame must be positive"
        }
        require(minimumPassingPercent in 1..100) {
            "minimumPassingPercent must be between 1 and 100"
        }
        require(
            minimumValidDistanceMeters.isFinite() &&
                maximumValidDistanceMeters.isFinite() &&
                minimumValidDistanceMeters > 0.0 &&
                maximumValidDistanceMeters >= minimumValidDistanceMeters,
        ) {
            "metric distance range must be finite, positive, and ordered"
        }
    }
}

object RuntimeMetricPreflightPolicy {
    const val MAX_DURATION_MS = 10_000L
    const val MIN_DISTINCT_FRAMES = 1
    const val MIN_OBSERVATION_SPAN_MS = 0L
    const val MIN_VALID_SAMPLES_PER_PASSING_FRAME = 1
    const val MIN_PASSING_PERCENT = 100
    const val MIN_VALID_DISTANCE_METERS = 0.2
    const val MAX_VALID_DISTANCE_METERS = 8.0

    val testCandidateProfile = RuntimeMetricPreflightProfile(
        profileId = "runtime-metric-test-candidate-r002",
        maximumDurationMs = MAX_DURATION_MS,
        minimumDistinctFrames = MIN_DISTINCT_FRAMES,
        minimumObservationSpanMs = MIN_OBSERVATION_SPAN_MS,
        minimumValidSamplesPerPassingFrame = MIN_VALID_SAMPLES_PER_PASSING_FRAME,
        minimumPassingPercent = MIN_PASSING_PERCENT,
        minimumValidDistanceMeters = MIN_VALID_DISTANCE_METERS,
        maximumValidDistanceMeters = MAX_VALID_DISTANCE_METERS,
    )

    fun isValidMetricDistanceMeters(distanceMeters: Double): Boolean =
        isValidMetricDistanceMeters(distanceMeters, testCandidateProfile)

    fun isValidMetricDistanceMeters(
        distanceMeters: Double,
        profile: RuntimeMetricPreflightProfile,
    ): Boolean =
        distanceMeters.isFinite() &&
            distanceMeters in
            profile.minimumValidDistanceMeters..profile.maximumValidDistanceMeters
}

enum class RuntimeMetricDepthSupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class RuntimeMetricPreflightStatus {
    IN_PROGRESS,
    AVAILABLE,
    UNSUPPORTED,
    UNKNOWN,
}

enum class RuntimeMetricStartupDisposition {
    FULL_ELIGIBLE,
    LIMITED,
    BLOCKED,
}

enum class RuntimeMetricPreflightReason {
    AWAITING_STABLE_EVIDENCE,
    STABLE_METRIC_DEPTH,
    EXPLICIT_DEPTH_UNSUPPORTED,
    TIMEOUT,
    TRANSIENT_FAILURE,
    LIFECYCLE_CANCELLED,
    GENERATION_MISMATCH,
    INVALID_FRAME_ORDER,
    INVALID_FRAME_EVIDENCE,
    /** No profile is bound to this build; test-candidate thresholds are never substituted. */
    PROFILE_UNAVAILABLE,
}

data class RuntimeMetricFrameEvidence(
    val generation: Long,
    val frameTimestampNanos: Long,
    val observedAtElapsedRealtimeMs: Long,
    val tracking: Boolean,
    val metricDepthAvailable: Boolean,
    val validMetricSamplesInRange: Int,
)

data class RuntimeMetricPreflightResult(
    val generation: Long,
    val status: RuntimeMetricPreflightStatus,
    val reason: RuntimeMetricPreflightReason,
    // Counts and span describe the recent qualification window, not startup history.
    val distinctFrameCount: Int,
    val passingFrameCount: Int,
    val observationSpanMs: Long,
    val completedAtElapsedRealtimeMs: Long?,
) {
    val metricDistanceAvailable: Boolean?
        get() = when (status) {
            RuntimeMetricPreflightStatus.AVAILABLE -> true
            RuntimeMetricPreflightStatus.UNSUPPORTED -> false
            RuntimeMetricPreflightStatus.IN_PROGRESS,
            RuntimeMetricPreflightStatus.UNKNOWN,
            -> null
        }

    val startupDisposition: RuntimeMetricStartupDisposition
        get() = when (status) {
            RuntimeMetricPreflightStatus.AVAILABLE -> RuntimeMetricStartupDisposition.FULL_ELIGIBLE
            RuntimeMetricPreflightStatus.UNSUPPORTED -> RuntimeMetricStartupDisposition.LIMITED
            RuntimeMetricPreflightStatus.IN_PROGRESS,
            RuntimeMetricPreflightStatus.UNKNOWN,
            -> RuntimeMetricStartupDisposition.BLOCKED
        }
}

/**
 * [profile] has no default: callers pass the profile bound to their build (for example the active
 * environment bundle). A missing profile ends immediately as UNKNOWN and never becomes AVAILABLE.
 */
class RuntimeMetricPreflightSession(
    val generation: Long,
    val startedAtElapsedRealtimeMs: Long,
    private val depthSupport: RuntimeMetricDepthSupport,
    private val profile: RuntimeMetricPreflightProfile?,
) {
    private var status = when {
        depthSupport == RuntimeMetricDepthSupport.UNSUPPORTED -> RuntimeMetricPreflightStatus.UNSUPPORTED
        profile == null -> RuntimeMetricPreflightStatus.UNKNOWN
        else -> RuntimeMetricPreflightStatus.IN_PROGRESS
    }
    private var reason = when {
        depthSupport == RuntimeMetricDepthSupport.UNSUPPORTED ->
            RuntimeMetricPreflightReason.EXPLICIT_DEPTH_UNSUPPORTED
        profile == null -> RuntimeMetricPreflightReason.PROFILE_UNAVAILABLE
        else -> RuntimeMetricPreflightReason.AWAITING_STABLE_EVIDENCE
    }
    private var distinctFrameCount = 0
    private var passingFrameCount = 0
    private val recentFrames = ArrayDeque<RuntimeMetricFrameEvidence>()
    private var firstFrameTimestampNanos: Long? = null
    private var lastFrameTimestampNanos: Long? = null
    private var lastObservedAtElapsedRealtimeMs: Long? = null
    private var completedAtElapsedRealtimeMs: Long? = if (status != RuntimeMetricPreflightStatus.IN_PROGRESS) {
        startedAtElapsedRealtimeMs
    } else {
        null
    }

    init {
        require(generation > 0L) { "generation must be positive" }
        require(startedAtElapsedRealtimeMs >= 0L) { "start time must not be negative" }
    }

    @Synchronized
    fun result(): RuntimeMetricPreflightResult = RuntimeMetricPreflightResult(
        generation = generation,
        status = status,
        reason = reason,
        distinctFrameCount = distinctFrameCount,
        passingFrameCount = passingFrameCount,
        observationSpanMs = observationSpanMs(),
        completedAtElapsedRealtimeMs = completedAtElapsedRealtimeMs,
    )

    @Synchronized
    fun observe(frame: RuntimeMetricFrameEvidence): RuntimeMetricPreflightResult {
        if (status != RuntimeMetricPreflightStatus.IN_PROGRESS) return result()
        val criteria = profile ?: return result()
        if (frame.generation != generation) {
            return finishUnknown(RuntimeMetricPreflightReason.GENERATION_MISMATCH, null)
        }
        if (hasReachedDeadline(frame.observedAtElapsedRealtimeMs, criteria)) {
            return finishUnknown(RuntimeMetricPreflightReason.TIMEOUT, frame.observedAtElapsedRealtimeMs)
        }
        if (!frame.isWellFormed()) {
            return finishUnknown(RuntimeMetricPreflightReason.INVALID_FRAME_EVIDENCE, frame.observedAtElapsedRealtimeMs)
        }
        if (frame.frameTimestampNanos == 0L) {
            lastObservedAtElapsedRealtimeMs = frame.observedAtElapsedRealtimeMs
            return result()
        }
        val priorTimestamp = lastFrameTimestampNanos
        if (priorTimestamp != null && frame.frameTimestampNanos < priorTimestamp) {
            return finishUnknown(RuntimeMetricPreflightReason.INVALID_FRAME_ORDER, frame.observedAtElapsedRealtimeMs)
        }
        if (frame.frameTimestampNanos == priorTimestamp) {
            lastObservedAtElapsedRealtimeMs = frame.observedAtElapsedRealtimeMs
            return result()
        }

        firstFrameTimestampNanos = firstFrameTimestampNanos ?: frame.frameTimestampNanos
        lastFrameTimestampNanos = frame.frameTimestampNanos
        lastObservedAtElapsedRealtimeMs = frame.observedAtElapsedRealtimeMs
        distinctFrameCount += 1
        if (frame.isPassing(criteria)) passingFrameCount += 1
        recentFrames.addLast(frame)
        while (
            recentFrames.size > criteria.minimumDistinctFrames &&
            (frame.frameTimestampNanos - recentFrames[1].frameTimestampNanos) / 1_000_000L >=
                criteria.minimumObservationSpanMs
        ) {
            val expiredFrame = recentFrames.removeFirst()
            distinctFrameCount -= 1
            if (expiredFrame.isPassing(criteria)) passingFrameCount -= 1
        }
        firstFrameTimestampNanos = recentFrames.first().frameTimestampNanos

        if (depthSupport == RuntimeMetricDepthSupport.SUPPORTED && hasStableEvidence(criteria)) {
            status = RuntimeMetricPreflightStatus.AVAILABLE
            reason = RuntimeMetricPreflightReason.STABLE_METRIC_DEPTH
            completedAtElapsedRealtimeMs = frame.observedAtElapsedRealtimeMs
        }
        return result()
    }

    @Synchronized
    fun expire(nowElapsedRealtimeMs: Long): RuntimeMetricPreflightResult {
        if (status != RuntimeMetricPreflightStatus.IN_PROGRESS) return result()
        val criteria = profile ?: return result()
        if (hasReachedDeadline(nowElapsedRealtimeMs, criteria)) {
            return finishUnknown(RuntimeMetricPreflightReason.TIMEOUT, nowElapsedRealtimeMs)
        }
        if (!isValidClock(nowElapsedRealtimeMs)) {
            return finishUnknown(RuntimeMetricPreflightReason.INVALID_FRAME_EVIDENCE, nowElapsedRealtimeMs)
        }
        return result()
    }

    @Synchronized
    fun failTransiently(nowElapsedRealtimeMs: Long): RuntimeMetricPreflightResult =
        finishUnknown(RuntimeMetricPreflightReason.TRANSIENT_FAILURE, nowElapsedRealtimeMs)

    @Synchronized
    fun cancelForLifecycle(nowElapsedRealtimeMs: Long): RuntimeMetricPreflightResult =
        finishUnknown(RuntimeMetricPreflightReason.LIFECYCLE_CANCELLED, nowElapsedRealtimeMs)

    private fun RuntimeMetricFrameEvidence.isWellFormed(): Boolean =
        frameTimestampNanos >= 0L &&
            observedAtElapsedRealtimeMs >= startedAtElapsedRealtimeMs &&
            (lastObservedAtElapsedRealtimeMs == null ||
                observedAtElapsedRealtimeMs >= checkNotNull(lastObservedAtElapsedRealtimeMs)) &&
            validMetricSamplesInRange >= 0 &&
            (metricDepthAvailable || validMetricSamplesInRange == 0)

    private fun RuntimeMetricFrameEvidence.isPassing(criteria: RuntimeMetricPreflightProfile): Boolean =
        tracking &&
            metricDepthAvailable &&
            validMetricSamplesInRange >= criteria.minimumValidSamplesPerPassingFrame

    private fun hasStableEvidence(criteria: RuntimeMetricPreflightProfile): Boolean =
        distinctFrameCount >= criteria.minimumDistinctFrames &&
            observationSpanMs() >= criteria.minimumObservationSpanMs &&
            passingFrameCount.toLong() * 100L >=
            distinctFrameCount.toLong() * criteria.minimumPassingPercent

    private fun observationSpanMs(): Long {
        val first = firstFrameTimestampNanos ?: return 0L
        val last = lastFrameTimestampNanos ?: return 0L
        return (last - first) / 1_000_000L
    }

    private fun isValidClock(nowElapsedRealtimeMs: Long): Boolean =
        nowElapsedRealtimeMs >= startedAtElapsedRealtimeMs &&
            (lastObservedAtElapsedRealtimeMs == null ||
                nowElapsedRealtimeMs >= checkNotNull(lastObservedAtElapsedRealtimeMs))

    private fun hasReachedDeadline(
        nowElapsedRealtimeMs: Long,
        criteria: RuntimeMetricPreflightProfile,
    ): Boolean =
        nowElapsedRealtimeMs >= startedAtElapsedRealtimeMs &&
            nowElapsedRealtimeMs - startedAtElapsedRealtimeMs >= criteria.maximumDurationMs

    private fun finishUnknown(
        terminalReason: RuntimeMetricPreflightReason,
        nowElapsedRealtimeMs: Long?,
    ): RuntimeMetricPreflightResult {
        if (status != RuntimeMetricPreflightStatus.IN_PROGRESS) return result()
        status = RuntimeMetricPreflightStatus.UNKNOWN
        reason = terminalReason
        completedAtElapsedRealtimeMs = nowElapsedRealtimeMs?.takeIf { it >= startedAtElapsedRealtimeMs }
        return result()
    }
}
