package kr.co.hanium.dreamup.walksafe.depth

data class RuntimeDepthFrameAssessment(
    val isNewFrame: Boolean,
    val outputAllowed: Boolean,
    val shouldHandleLoss: Boolean,
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
): RuntimeDepthFrameAssessment {
    val isNewFrame = frameTimestampNanos > previousFrameTimestampNanos
    val regressed = frameTimestampNanos < previousFrameTimestampNanos
    val lastValidAgeMs = observedAtElapsedRealtimeMs - lastValidFrameAtElapsedRealtimeMs
    val previousEvidenceFresh = lastValidAgeMs in 0L until staleTimeoutMs
    val outputAllowed = frameTimestampNanos > 0L && !regressed && lastValidAgeMs >= 0L && samplesAreValid &&
        (isNewFrame || previousEvidenceFresh)
    return RuntimeDepthFrameAssessment(
        isNewFrame = isNewFrame,
        outputAllowed = outputAllowed,
        shouldHandleLoss = regressed || (!outputAllowed && !previousEvidenceFresh),
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
