package kr.co.hanium.dreamup.walksafe.inference

enum class DetectorRuntimeFailureAction {
    KEEP_CURRENT,
    LOAD_LEGACY,
    DISABLE,
}

/** Converts repeated invoke failures into one bounded fallback or an explicit unavailable state. */
class DetectorRuntimeSupervisor(
    private val maximumConsecutiveFailures: Int = 3,
) {
    var consecutiveFailures: Int = 0
        private set

    fun onSuccess() {
        consecutiveFailures = 0
    }

    fun onFailure(activeModelKey: String?, legacyFallbackAvailable: Boolean): DetectorRuntimeFailureAction {
        consecutiveFailures += 1
        if (consecutiveFailures < maximumConsecutiveFailures.coerceAtLeast(1)) {
            return DetectorRuntimeFailureAction.KEEP_CURRENT
        }
        return if (
            activeModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY &&
            legacyFallbackAvailable
        ) {
            DetectorRuntimeFailureAction.LOAD_LEGACY
        } else {
            DetectorRuntimeFailureAction.DISABLE
        }
    }
}
