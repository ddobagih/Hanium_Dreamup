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
    LOCATION_SERVICE_OFF,
    PROVIDER_DISABLED,
    NO_FIX_RECEIVED,
}

/** Observed no-fix conditions; provider state does not identify a vendor-specific setting. */
enum class DeviceCheckLocationFixUnavailability(
    val reason: DeviceCheckLocationFixReason,
    val userActionKo: String,
) {
    LOCATION_SERVICE_OFF(
        DeviceCheckLocationFixReason.LOCATION_SERVICE_OFF,
        "휴대전화 설정에서 위치를 켠 뒤 다시 점검하세요.",
    ),
    PROVIDER_DISABLED(
        DeviceCheckLocationFixReason.PROVIDER_DISABLED,
        "사용 가능한 위치 제공자를 확인하지 못했습니다. 휴대전화의 위치 서비스 설정을 확인하고 " +
            "창가나 실외에서 다시 점검하세요.",
    ),
    NO_FIX_RECEIVED(
        DeviceCheckLocationFixReason.NO_FIX_RECEIVED,
        "위치 신호를 받지 못했습니다. 창가나 실외에서 다시 점검하세요.",
    ),
}

data class DeviceCheckLocationFixDecision(
    val passed: Boolean,
    val reason: DeviceCheckLocationFixReason,
    val accuracyMeters: Float?,
    val ageMs: Long,
)

/**
 * Checks a current location fix against the supported environment's 15 m uncertainty limit.
 * Android's reported accuracy is an uncertainty estimate, not a measured positioning error;
 * the runtime walking-quality assessment remains separate.
 */
object DeviceCheckLocationFixPolicy {
    const val MAX_AGE_MS = 30_000L
    const val MAX_ACCURACY_METERS = 15f

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

    /** Used only when no fix was delivered, rather than replacing a rejected fix's reason. */
    fun unavailable(
        cause: DeviceCheckLocationFixUnavailability,
    ): DeviceCheckLocationFixDecision = DeviceCheckLocationFixDecision(
        passed = false,
        reason = cause.reason,
        accuracyMeters = null,
        ageMs = 0L,
    )

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
