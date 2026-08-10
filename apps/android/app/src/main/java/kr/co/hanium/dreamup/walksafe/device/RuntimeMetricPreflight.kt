package kr.co.hanium.dreamup.walksafe.device

object RuntimeMetricPreflightPolicy {
    const val MAX_DURATION_MS = 10_000L
    const val MIN_DISTINCT_FRAMES = 10
    const val MIN_OBSERVATION_SPAN_MS = 1_000L
    const val MIN_VALID_SAMPLES_PER_PASSING_FRAME = 30
    const val MIN_PASSING_PERCENT = 80
    const val MIN_VALID_DISTANCE_METERS = 0.2
    const val MAX_VALID_DISTANCE_METERS = 8.0

    fun isValidMetricDistanceMeters(distanceMeters: Double): Boolean =
        distanceMeters.isFinite() &&
            distanceMeters in MIN_VALID_DISTANCE_METERS..MAX_VALID_DISTANCE_METERS
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

class RuntimeMetricPreflightSession(
    val generation: Long,
    val startedAtElapsedRealtimeMs: Long,
    private val depthSupport: RuntimeMetricDepthSupport,
) {
    private var status = if (depthSupport == RuntimeMetricDepthSupport.UNSUPPORTED) {
        RuntimeMetricPreflightStatus.UNSUPPORTED
    } else {
        RuntimeMetricPreflightStatus.IN_PROGRESS
    }
    private var reason = if (depthSupport == RuntimeMetricDepthSupport.UNSUPPORTED) {
        RuntimeMetricPreflightReason.EXPLICIT_DEPTH_UNSUPPORTED
    } else {
        RuntimeMetricPreflightReason.AWAITING_STABLE_EVIDENCE
    }
    private var distinctFrameCount = 0
    private var passingFrameCount = 0
    private var firstFrameTimestampNanos: Long? = null
    private var lastFrameTimestampNanos: Long? = null
    private var lastObservedAtElapsedRealtimeMs: Long? = null
    private var completedAtElapsedRealtimeMs: Long? = if (status == RuntimeMetricPreflightStatus.UNSUPPORTED) {
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
        if (status == RuntimeMetricPreflightStatus.UNSUPPORTED || status == RuntimeMetricPreflightStatus.UNKNOWN) {
            return result()
        }
        if (frame.generation != generation) {
            return finishUnknown(RuntimeMetricPreflightReason.GENERATION_MISMATCH, frame.observedAtElapsedRealtimeMs)
        }
        if (status == RuntimeMetricPreflightStatus.AVAILABLE) {
            return result()
        }
        if (frame.observedAtElapsedRealtimeMs - startedAtElapsedRealtimeMs >
            RuntimeMetricPreflightPolicy.MAX_DURATION_MS
        ) {
            return finishUnknown(RuntimeMetricPreflightReason.TIMEOUT, frame.observedAtElapsedRealtimeMs)
        }
        if (!frame.isWellFormed()) {
            return finishUnknown(RuntimeMetricPreflightReason.INVALID_FRAME_EVIDENCE, frame.observedAtElapsedRealtimeMs)
        }
        val priorTimestamp = lastFrameTimestampNanos
        if (priorTimestamp != null && frame.frameTimestampNanos <= priorTimestamp) {
            return finishUnknown(RuntimeMetricPreflightReason.INVALID_FRAME_ORDER, frame.observedAtElapsedRealtimeMs)
        }

        firstFrameTimestampNanos = firstFrameTimestampNanos ?: frame.frameTimestampNanos
        lastFrameTimestampNanos = frame.frameTimestampNanos
        lastObservedAtElapsedRealtimeMs = frame.observedAtElapsedRealtimeMs
        distinctFrameCount += 1
        if (frame.isPassing()) passingFrameCount += 1

        if (depthSupport == RuntimeMetricDepthSupport.SUPPORTED && hasStableEvidence()) {
            status = RuntimeMetricPreflightStatus.AVAILABLE
            reason = RuntimeMetricPreflightReason.STABLE_METRIC_DEPTH
            completedAtElapsedRealtimeMs = frame.observedAtElapsedRealtimeMs
        } else if (
            frame.observedAtElapsedRealtimeMs - startedAtElapsedRealtimeMs >=
            RuntimeMetricPreflightPolicy.MAX_DURATION_MS
        ) {
            return finishUnknown(RuntimeMetricPreflightReason.TIMEOUT, frame.observedAtElapsedRealtimeMs)
        }
        return result()
    }

    @Synchronized
    fun expire(nowElapsedRealtimeMs: Long): RuntimeMetricPreflightResult {
        if (status != RuntimeMetricPreflightStatus.IN_PROGRESS) return result()
        if (!isValidClock(nowElapsedRealtimeMs)) {
            return finishUnknown(RuntimeMetricPreflightReason.INVALID_FRAME_EVIDENCE, nowElapsedRealtimeMs)
        }
        if (nowElapsedRealtimeMs - startedAtElapsedRealtimeMs >= RuntimeMetricPreflightPolicy.MAX_DURATION_MS) {
            return finishUnknown(RuntimeMetricPreflightReason.TIMEOUT, nowElapsedRealtimeMs)
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
        frameTimestampNanos > 0L &&
            observedAtElapsedRealtimeMs >= startedAtElapsedRealtimeMs &&
            (lastObservedAtElapsedRealtimeMs == null ||
                observedAtElapsedRealtimeMs >= checkNotNull(lastObservedAtElapsedRealtimeMs)) &&
            validMetricSamplesInRange >= 0 &&
            (metricDepthAvailable || validMetricSamplesInRange == 0)

    private fun RuntimeMetricFrameEvidence.isPassing(): Boolean =
        tracking &&
            metricDepthAvailable &&
            validMetricSamplesInRange >= RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME

    private fun hasStableEvidence(): Boolean =
        distinctFrameCount >= RuntimeMetricPreflightPolicy.MIN_DISTINCT_FRAMES &&
            observationSpanMs() >= RuntimeMetricPreflightPolicy.MIN_OBSERVATION_SPAN_MS &&
            passingFrameCount.toLong() * 100L >=
            distinctFrameCount.toLong() * RuntimeMetricPreflightPolicy.MIN_PASSING_PERCENT

    private fun observationSpanMs(): Long {
        val first = firstFrameTimestampNanos ?: return 0L
        val last = lastFrameTimestampNanos ?: return 0L
        return (last - first) / 1_000_000L
    }

    private fun isValidClock(nowElapsedRealtimeMs: Long): Boolean =
        nowElapsedRealtimeMs >= startedAtElapsedRealtimeMs &&
            (lastObservedAtElapsedRealtimeMs == null ||
                nowElapsedRealtimeMs >= checkNotNull(lastObservedAtElapsedRealtimeMs))

    private fun finishUnknown(
        terminalReason: RuntimeMetricPreflightReason,
        nowElapsedRealtimeMs: Long,
    ): RuntimeMetricPreflightResult {
        if (status == RuntimeMetricPreflightStatus.UNSUPPORTED) return result()
        status = RuntimeMetricPreflightStatus.UNKNOWN
        reason = terminalReason
        completedAtElapsedRealtimeMs = nowElapsedRealtimeMs.takeIf { it >= startedAtElapsedRealtimeMs }
        return result()
    }
}
