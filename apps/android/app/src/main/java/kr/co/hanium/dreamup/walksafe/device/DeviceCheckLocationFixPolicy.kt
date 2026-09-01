package kr.co.hanium.dreamup.walksafe.device

data class DeviceCheckLocationFixObservation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val observedAtElapsedRealtimeMs: Long,
    val nowElapsedRealtimeMs: Long,
    val mock: Boolean,
)

enum class DeviceCheckLocationFixReason {
    PASS,
    MOCK_LOCATION,
    INVALID_COORDINATES,
    ACCURACY_UNAVAILABLE,
    ACCURACY_INVALID,
    ACCURACY_OUTSIDE_FUNCTIONAL_RANGE,
    FUTURE_TIMESTAMP,
    STALE,
}

data class DeviceCheckLocationFixDecision(
    val passed: Boolean,
    val reason: DeviceCheckLocationFixReason,
    val accuracyMeters: Float?,
    val ageMs: Long,
)

/** Functional fused-location proof; the stricter walking-quality gate remains separate. */
object DeviceCheckLocationFixPolicy {
    const val MAX_AGE_MS = 30_000L
    const val MAX_ACCURACY_METERS = 100f

    fun evaluate(observation: DeviceCheckLocationFixObservation): DeviceCheckLocationFixDecision {
        val ageMs = observation.nowElapsedRealtimeMs - observation.observedAtElapsedRealtimeMs
        val reason = when {
            observation.mock -> DeviceCheckLocationFixReason.MOCK_LOCATION
            !observation.latitude.isFinite() || observation.latitude !in -90.0..90.0 ||
                !observation.longitude.isFinite() || observation.longitude !in -180.0..180.0 ->
                DeviceCheckLocationFixReason.INVALID_COORDINATES
            observation.accuracyMeters == null ->
                DeviceCheckLocationFixReason.ACCURACY_UNAVAILABLE
            !observation.accuracyMeters.isFinite() || observation.accuracyMeters < 0f ->
                DeviceCheckLocationFixReason.ACCURACY_INVALID
            observation.accuracyMeters > MAX_ACCURACY_METERS ->
                DeviceCheckLocationFixReason.ACCURACY_OUTSIDE_FUNCTIONAL_RANGE
            ageMs < 0L -> DeviceCheckLocationFixReason.FUTURE_TIMESTAMP
            ageMs > MAX_AGE_MS -> DeviceCheckLocationFixReason.STALE
            else -> DeviceCheckLocationFixReason.PASS
        }
        return DeviceCheckLocationFixDecision(
            passed = reason == DeviceCheckLocationFixReason.PASS,
            reason = reason,
            accuracyMeters = observation.accuracyMeters,
            ageMs = ageMs,
        )
    }

    fun passes(observation: DeviceCheckLocationFixObservation): Boolean = evaluate(observation).passed

    /** Rejects any mock sample in a delivered batch before selecting the freshest pass. */
    fun selectBatch(
        decisions: List<DeviceCheckLocationFixDecision>,
    ): DeviceCheckLocationFixDecision? {
        decisions.firstOrNull {
            it.reason == DeviceCheckLocationFixReason.MOCK_LOCATION
        }?.let { return it }
        decisions.filter(DeviceCheckLocationFixDecision::passed)
            .minByOrNull(::ageRank)
            ?.let { return it }
        return decisions.minByOrNull(::ageRank)
    }

    private fun ageRank(decision: DeviceCheckLocationFixDecision): Long =
        decision.ageMs.coerceAtLeast(0L)
}
