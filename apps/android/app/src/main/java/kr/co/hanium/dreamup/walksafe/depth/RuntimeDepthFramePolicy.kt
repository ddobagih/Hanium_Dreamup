package kr.co.hanium.dreamup.walksafe.depth

data class RuntimeDepthFrameAssessment(
    val isNewFrame: Boolean,
    val outputAllowed: Boolean,
    val shouldHandleLoss: Boolean,
    /** Set when a fatal acquisition failure, not the stale timeout, forced the loss. */
    val fatalAcquisitionFailure: DepthAcquisitionFailureReason? = null,
) {
    val refreshesLastValidTime: Boolean get() = isNewFrame && outputAllowed
}

/** Session.update may return the same frame after its normal camera wait timeout. */
fun assessRuntimeDepthFrame(
    frameTimestampNanos: Long,
    previousFrameTimestampNanos: Long,
    observedAtElapsedRealtimeMs: Long,
    lastValidFrameAtElapsedRealtimeMs: Long,
    samplesAreValid: Boolean,
    staleTimeoutMs: Long,
    /** S14-25: most severe acquisition failure since the previous frame, or null. */
    acquisitionFailure: DepthAcquisitionFailureReason? = null,
): RuntimeDepthFrameAssessment {
    val isNewFrame = frameTimestampNanos > previousFrameTimestampNanos
    val regressed = frameTimestampNanos < previousFrameTimestampNanos
    val lastValidAgeMs = observedAtElapsedRealtimeMs - lastValidFrameAtElapsedRealtimeMs
    val previousEvidenceFresh = lastValidAgeMs in 0L until staleTimeoutMs
    // Transient and tracking failures keep the stale-timeout budget; fatal ones never wait for it.
    val fatal = acquisitionFailure?.takeIf { it.stopsImmediately }
    val outputAllowed = fatal == null && frameTimestampNanos > 0L && !regressed && lastValidAgeMs >= 0L &&
        samplesAreValid && (isNewFrame || previousEvidenceFresh)
    return RuntimeDepthFrameAssessment(
        isNewFrame = isNewFrame,
        outputAllowed = outputAllowed,
        shouldHandleLoss = fatal != null || regressed || (!outputAllowed && !previousEvidenceFresh),
        fatalAcquisitionFailure = fatal,
    )
}

/** Visual processing only: missing depth cannot refresh metric evidence or authorize feedback. */
fun allowsRuntimeDepthGapProcessing(
    frameTimestampNanos: Long,
    latestFrameTimestampNanos: Long,
    observedAtElapsedRealtimeMs: Long,
    lastValidFrameAtElapsedRealtimeMs: Long,
    tracking: Boolean,
): Boolean = tracking && frameTimestampNanos > 0L &&
    frameTimestampNanos == latestFrameTimestampNanos &&
    lastValidFrameAtElapsedRealtimeMs > 0L &&
    observedAtElapsedRealtimeMs - lastValidFrameAtElapsedRealtimeMs in 0L..600L
