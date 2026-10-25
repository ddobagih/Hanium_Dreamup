package kr.co.hanium.dreamup.walksafe.navigation.positioning

/** Original individual-step measurement times around a fix; no fractional step is inferred. */
data class WalkingCalibrationStepBracket(
    val beforeStepElapsedRealtimeMs: Long,
    val afterStepElapsedRealtimeMs: Long,
) {
    internal fun containsFix(elapsedRealtimeMs: Long): Boolean =
        beforeStepElapsedRealtimeMs >= 0L &&
            beforeStepElapsedRealtimeMs <= elapsedRealtimeMs &&
            afterStepElapsedRealtimeMs > elapsedRealtimeMs
}

/**
 * Endpoint inputs retained from an accepted segment, not a measured step-length error or sigma.
 * [gateAccuracyProxyM] is the original credibility-gate value. A live caller can weight the reported
 * accuracy before supplying it, so this value must not be labelled a reported Android 68% radius.
 * A null [stepBracket] means individual-step boundary evidence was not supplied.
 */
data class WalkingCalibrationEndpointEvidence(
    val elapsedRealtimeMs: Long,
    val gateAccuracyProxyM: Double,
    val stepBracket: WalkingCalibrationStepBracket? = null,
)
