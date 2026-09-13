package kr.co.hanium.dreamup.walksafe.navigation.positioning

/**
 * Aligns unmodified GNSS fixes with actual individual STEP_DETECTOR timestamps.
 *
 * Callbacks must be serialized. A fix waits for a later step timestamp so an
 * earlier step whose callback arrives after GNSS is included at the endpoint.
 * Strictly increasing detector events establish the step-stream watermark;
 * duplicate/out-of-order events invalidate the current history and segment.
 *
 * Only high-confidence individual detector events may set individualStepDetector
 * to true. Counter batches and fallback events never receive invented individual
 * timestamps. Counter-only devices consequently keep their normal PDR behavior
 * without automatic stride learning through this component.
 */
class TimestampAlignedWalkingCalibration {
    private data class TimedStep(val timestampMs: Long, val count: Int)

    private val segments = WalkingCalibrationSegmentPolicy()
    private val steps = ArrayDeque<TimedStep>()
    private val pendingFixes = ArrayDeque<RawWalkingCalibrationFix>()
    private var stepCount = 0
    private var lastFixTimestampMs: Long? = null
    private var lastDeliveryTimestampMs: Long? = null

    fun reset() {
        segments.reset()
        steps.clear()
        pendingFixes.clear()
        stepCount = 0
        lastFixTimestampMs = null
        lastDeliveryTimestampMs = null
    }

    fun onStep(
        timestampMs: Long,
        deltaSteps: Int,
        individualStepDetector: Boolean,
        receivedAtElapsedRealtimeMs: Long,
    ): List<WalkingCalibrationSample> {
        if (!individualStepDetector || deltaSteps != 1 || timestampMs < 0L ||
            receivedAtElapsedRealtimeMs < timestampMs ||
            receivedAtElapsedRealtimeMs - timestampMs > MAXIMUM_STEP_DELIVERY_DELAY_MS
        ) {
            reset()
            return emptyList()
        }
        if (!prepareDelivery(receivedAtElapsedRealtimeMs)) return emptyList()

        val previous = steps.lastOrNull()
        if (previous != null && timestampMs <= previous.timestampMs) {
            reset()
            return emptyList()
        }
        if ((previous != null && timestampMs - previous.timestampMs > MAXIMUM_STEP_GAP_MS) ||
            stepCount == Int.MAX_VALUE
        ) reset()
        lastDeliveryTimestampMs = receivedAtElapsedRealtimeMs
        steps.addLast(TimedStep(timestampMs, ++stepCount))
        while (steps.size > MAXIMUM_STEP_HISTORY_SIZE ||
            timestampMs - steps.first().timestampMs > MAXIMUM_STEP_HISTORY_AGE_MS
        ) steps.removeFirst()
        return drainConfirmedFixes(receivedAtElapsedRealtimeMs)
    }

    fun onFix(fix: RawWalkingCalibrationFix): List<WalkingCalibrationSample> {
        if (!segments.isCredible(fix)) {
            reset()
            return emptyList()
        }
        if (!prepareDelivery(fix.receivedAtElapsedRealtimeMs)) return emptyList()
        val previousTimestamp = lastFixTimestampMs
        if (previousTimestamp != null && fix.elapsedRealtimeMs <= previousTimestamp) {
            reset()
            return emptyList()
        }
        if (pendingFixes.size >= MAXIMUM_PENDING_FIXES) reset()
        lastDeliveryTimestampMs = fix.receivedAtElapsedRealtimeMs
        lastFixTimestampMs = fix.elapsedRealtimeMs
        // Keep the complete immutable raw fix, including its original receipt time.
        pendingFixes.addLast(fix)
        return drainConfirmedFixes(fix.receivedAtElapsedRealtimeMs)
    }

    private fun prepareDelivery(receivedAtMs: Long): Boolean {
        val previousDelivery = lastDeliveryTimestampMs
        if (previousDelivery != null && receivedAtMs < previousDelivery) {
            reset()
            return false
        }
        val oldestPending = pendingFixes.firstOrNull()
        if (oldestPending != null &&
            receivedAtMs - oldestPending.receivedAtElapsedRealtimeMs > MAXIMUM_FIX_CONFIRMATION_WAIT_MS
        ) reset()
        lastDeliveryTimestampMs = receivedAtMs
        return true
    }

    private fun drainConfirmedFixes(receivedAtMs: Long): List<WalkingCalibrationSample> {
        val samples = mutableListOf<WalkingCalibrationSample>()
        while (pendingFixes.isNotEmpty()) {
            val fix = pendingFixes.first()
            val newestStep = steps.lastOrNull() ?: break
            // A step at exactly the endpoint is included, but does not yet seal it.
            if (newestStep.timestampMs <= fix.elapsedRealtimeMs) break
            pendingFixes.removeFirst()
            val before = steps.lastOrNull { it.timestampMs <= fix.elapsedRealtimeMs }
            val after = steps.firstOrNull { it.timestampMs > fix.elapsedRealtimeMs }
            if (before == null || after == null ||
                after.timestampMs - before.timestampMs > MAXIMUM_STEP_GAP_MS ||
                receivedAtMs - fix.receivedAtElapsedRealtimeMs > MAXIMUM_FIX_CONFIRMATION_WAIT_MS
            ) {
                segments.reset()
                continue
            }
            segments.observe(fix, before.count)?.let(samples::add)
        }
        return samples
    }

    private companion object {
        const val MAXIMUM_STEP_DELIVERY_DELAY_MS = 2_000L
        const val MAXIMUM_STEP_GAP_MS = 2_000L
        const val MAXIMUM_FIX_CONFIRMATION_WAIT_MS = 2_000L
        const val MAXIMUM_STEP_HISTORY_AGE_MS = 10_000L
        const val MAXIMUM_STEP_HISTORY_SIZE = 64
        const val MAXIMUM_PENDING_FIXES = 16
    }
}
