package kr.co.hanium.dreamup.walksafe.device

data class RuntimeMetricFrameContinuityDecision(
    val newFrame: Boolean,
    val outputAllowed: Boolean,
    val measurementLost: Boolean,
)

object RuntimeMetricFrameContinuityPolicy {
    fun evaluate(
        frameTimestampNanos: Long,
        previousFrameTimestampNanos: Long,
        samplesValid: Boolean,
        nowElapsedRealtimeMs: Long,
        lastValidFrameAtElapsedRealtimeMs: Long,
        staleTimeoutMs: Long,
    ): RuntimeMetricFrameContinuityDecision {
        val newFrame = frameTimestampNanos > 0L &&
            frameTimestampNanos > previousFrameTimestampNanos
        val clockValid = nowElapsedRealtimeMs >= 0L &&
            lastValidFrameAtElapsedRealtimeMs >= 0L &&
            nowElapsedRealtimeMs >= lastValidFrameAtElapsedRealtimeMs &&
            staleTimeoutMs > 0L
        val outputAllowed = clockValid && newFrame && samplesValid
        return RuntimeMetricFrameContinuityDecision(
            newFrame = newFrame,
            outputAllowed = outputAllowed,
            measurementLost = !clockValid ||
                (!outputAllowed &&
                    nowElapsedRealtimeMs - lastValidFrameAtElapsedRealtimeMs >= staleTimeoutMs),
        )
    }
}
