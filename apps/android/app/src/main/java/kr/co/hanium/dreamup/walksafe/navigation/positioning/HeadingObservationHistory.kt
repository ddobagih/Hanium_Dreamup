package kr.co.hanium.dreamup.walksafe.navigation.positioning

/**
 * Bounded history of already-gated heading samples, queried using measurement time.
 * The caller must clear this history when mounting or sensor trust becomes invalid.
 */
class HeadingObservationHistory(
    private val maximumRetainedAgeMs: Long = 2_500L,
    private val maximumEntries: Int = 256,
) {
    private val observations = mutableListOf<HeadingObservation>()

    init {
        require(maximumRetainedAgeMs >= 0L)
        require(maximumEntries > 0)
    }

    @Synchronized
    fun add(observation: HeadingObservation): Boolean {
        if (
            observation.elapsedRealtimeMs < 0L ||
            !observation.headingDegreesTrueNorth.isFinite() ||
            observation.accuracyDegrees?.let { it.isFinite() && it >= 0.0 } != true ||
            observations.lastOrNull()?.let { observation.elapsedRealtimeMs <= it.elapsedRealtimeMs } == true
        ) return false
        observations.add(observation)
        val oldestAtMs = observation.elapsedRealtimeMs - maximumRetainedAgeMs
        while (
            observations.size > maximumEntries ||
            observations.firstOrNull()?.let { it.elapsedRealtimeMs < oldestAtMs } == true
        ) observations.removeAt(0)
        return true
    }

    @Synchronized
    fun atOrBefore(elapsedRealtimeMs: Long, maximumAgeMs: Long = 500L): HeadingObservation? {
        if (elapsedRealtimeMs < 0L || maximumAgeMs < 0L) return null
        return observations.lastOrNull { it.elapsedRealtimeMs <= elapsedRealtimeMs }
            ?.takeIf { elapsedRealtimeMs - it.elapsedRealtimeMs <= maximumAgeMs }
    }

    @Synchronized
    fun clear() {
        observations.clear()
    }
}
