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

    // Below: no fix arrived at all. Every reason above describes one that did.
    LOCATION_SERVICE_OFF,
    PROVIDER_DISABLED,
    NO_FIX_RECEIVED,
}

/**
 * Why no fix arrived, and what the user can do about it.
 *
 * Measuring the 15 m threshold took three runs returning zero samples on a phone whose location
 * was on, whose Wi-Fi was connected and whose permissions were all granted: Play Services'
 * network location provider was disabled, so indoors no accuracy threshold was reachable. The
 * app reported only that location was unavailable, which names the symptom and not the switch.
 */
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
        "설정 > 위치 > 위치 서비스에서 Google 위치 정확도를 켠 뒤 다시 점검하세요. " +
            "실내에서는 이 기능이 꺼져 있으면 위치를 찾을 수 없습니다.",
    ),
    NO_FIX_RECEIVED(
        DeviceCheckLocationFixReason.NO_FIX_RECEIVED,
        "실내에서는 위치를 찾지 못할 수 있습니다. 창가나 실외에서 다시 점검하세요.",
    ),
}

data class DeviceCheckLocationFixDecision(
    val passed: Boolean,
    val reason: DeviceCheckLocationFixReason,
    val accuracyMeters: Float?,
    val ageMs: Long,
)

/**
 * Pedestrian-grade fix proof. The bar is the one the walk gate already applies
 * (`WalkSafeEnvironmentProfiles` `maximumGpsHorizontalAccuracyMeters`); accepting anything
 * coarser here let a fix that cannot separate one crossing from the next clear the last gate
 * before walking. 11.5 m measured indoors on SM-A716S (2026-09-08) keeps this reachable.
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

    /** A decision for the case where no sample arrived, so there is nothing to evaluate. */
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
