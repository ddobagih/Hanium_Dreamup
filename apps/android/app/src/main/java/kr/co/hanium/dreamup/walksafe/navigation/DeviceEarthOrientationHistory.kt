package kr.co.hanium.dreamup.walksafe.navigation

/**
 * Bounded RV history preserving the full matrix and original measurement metadata.
 * Retention limits bound resources; each caller supplies its own permitted measurement age.
 * The tracker clears this history when its RV trust or registration lifetime ends.
 */
internal class DeviceEarthOrientationHistory(
    private val maximumRetainedAgeMs: Long = 2_500L,
    private val maximumEntries: Int = 256,
) {
    private val orientations = mutableListOf<DeviceEarthOrientation>()

    init {
        require(maximumRetainedAgeMs >= 0L)
        require(maximumEntries > 0)
    }

    @Synchronized
    fun add(orientation: DeviceEarthOrientation): Boolean {
        if (
            orientation.observedAtElapsedRealtimeMs < 0L ||
            !orientation.deviceToMagneticEnu.isFinite() ||
            !orientation.headingErrorDeg.isFinite() || orientation.headingErrorDeg < 0f ||
            orientations.lastOrNull()?.let {
                orientation.observedAtElapsedRealtimeMs <= it.observedAtElapsedRealtimeMs
            } == true
        ) return false
        orientations.add(orientation)
        val oldestAtMs = orientation.observedAtElapsedRealtimeMs - maximumRetainedAgeMs
        while (
            orientations.size > maximumEntries ||
            orientations.firstOrNull()?.let { it.observedAtElapsedRealtimeMs < oldestAtMs } == true
        ) orientations.removeAt(0)
        return true
    }

    @Synchronized
    fun atOrBefore(timestampMs: Long, maximumAgeMs: Long): DeviceEarthOrientation? {
        if (timestampMs < 0L || maximumAgeMs < 0L) return null
        return orientations.lastOrNull { it.observedAtElapsedRealtimeMs <= timestampMs }
            ?.takeIf { timestampMs - it.observedAtElapsedRealtimeMs <= maximumAgeMs }
    }

    @Synchronized
    fun clear() {
        orientations.clear()
    }
}
