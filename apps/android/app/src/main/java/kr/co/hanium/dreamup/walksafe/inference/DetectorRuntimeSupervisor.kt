package kr.co.hanium.dreamup.walksafe.inference

enum class DetectorRuntimeFailureAction {
    KEEP_CURRENT,
    SAFE_STOP,
    LOAD_LEGACY_FOR_NEXT_WALK,
    DISABLE,
}

/** Prevents model replacement during a walk while retaining an offline next-walk fallback choice. */
class DetectorRuntimeSupervisor(
    private val maximumConsecutiveFailures: Int = 3,
) {
    var consecutiveFailures: Int = 0
        private set

    fun onSuccess() {
        consecutiveFailures = 0
    }

    fun onFailure(
        activeModelKey: String?,
        legacyFallbackAvailable: Boolean,
        activeWalk: Boolean,
    ): DetectorRuntimeFailureAction {
        consecutiveFailures += 1
        if (consecutiveFailures < maximumConsecutiveFailures.coerceAtLeast(1)) {
            return DetectorRuntimeFailureAction.KEEP_CURRENT
        }
        if (activeWalk) return DetectorRuntimeFailureAction.SAFE_STOP
        return if (
            activeModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY &&
            legacyFallbackAvailable
        ) {
            DetectorRuntimeFailureAction.LOAD_LEGACY_FOR_NEXT_WALK
        } else {
            DetectorRuntimeFailureAction.DISABLE
        }
    }
}
