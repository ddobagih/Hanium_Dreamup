package kr.co.hanium.dreamup.walksafe.inference.tracking

/**
 * Selects a work-start budget from the cadence of admitted CPU images, not rendering calls.
 * This cannot preempt a native LK call. Source lifetime and image-quality gates remain separate.
 */
class AdaptiveVisualTrackingBudget {
    private val intervals = LongArray(WINDOW_SIZE)
    private var intervalCount = 0
    private var nextInterval = 0
    private var context: VisualFrameKey? = null
    private var previous: VisualFrameKey? = null
    private var selectedBudgetNs = DEFAULT_BUDGET_NS

    /** Clears cadence samples while retaining the accepted context floor against late callbacks. */
    @Synchronized
    fun reset() {
        intervals.fill(0L)
        intervalCount = 0
        nextInterval = 0
        previous = null
        selectedBudgetNs = DEFAULT_BUDGET_NS
    }

    /** Call once for every admitted frame, including frames without a detector result. */
    @Synchronized
    fun budgetForFrame(key: VisualFrameKey): Long {
        if (key.cameraTimestampNs <= 0L || key.frameId < 0L) return DEFAULT_BUDGET_NS
        val accepted = context
        if (accepted != null && (key.epoch < accepted.epoch ||
                (key.epoch == accepted.epoch && key.geometryVersion < accepted.geometryVersion))) {
            return DEFAULT_BUDGET_NS
        }
        if (accepted == null || key.epoch != accepted.epoch || key.geometryVersion != accepted.geometryVersion) {
            reset()
            context = key
        }
        val before = previous
        if (before == null) {
            previous = key
            return selectedBudgetNs
        }
        if (key.frameId <= before.frameId || key.cameraTimestampNs <= before.cameraTimestampNs) {
            return selectedBudgetNs
        }
        val interval = key.cameraTimestampNs - before.cameraTimestampNs
        previous = key
        intervals[nextInterval] = interval
        nextInterval = (nextInterval + 1) % WINDOW_SIZE
        intervalCount = minOf(intervalCount + 1, WINDOW_SIZE)
        // Long gaps still evict old samples, but 67/100ms frame drops cannot inflate cadence.
        val valid = intervals.take(intervalCount).filter { it in 1L..MAX_NOMINAL_INTERVAL_NS }.sorted()
        selectedBudgetNs = if (valid.size < MIN_INTERVALS) DEFAULT_BUDGET_NS else {
            val lowerQuartile = valid[(valid.size - 1) / 4]
            (lowerQuartile * 3L / 10L).coerceIn(DEFAULT_BUDGET_NS, MAX_BUDGET_NS)
        }
        return selectedBudgetNs
    }

    private companion object {
        const val WINDOW_SIZE = 12
        const val MIN_INTERVALS = 6
        const val MAX_NOMINAL_INTERVAL_NS = 50_000_000L
        const val DEFAULT_BUDGET_NS = 5_000_000L
        const val MAX_BUDGET_NS = 10_000_000L
    }
}
