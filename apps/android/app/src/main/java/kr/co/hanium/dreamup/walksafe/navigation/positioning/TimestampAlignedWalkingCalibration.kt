package kr.co.hanium.dreamup.walksafe.navigation.positioning

import java.util.Collections

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

    private val decisionCounts = linkedMapOf<WalkingCalibrationDecision, Long>()
    private var latestDecision: WalkingCalibrationDecision? = null

    /** Reset measurement history without erasing why the current scope could not calibrate. */
    @Synchronized
    fun reset() = resetHistory()

    /** Actor/collection/session changes explicitly retire both history and diagnostic counts. */
    @Synchronized
    fun resetForScope() {
        resetHistory()
        decisionCounts.clear()
        latestDecision = null
    }

    /** Immutable side-effect-free view; reading does not create a sample or renew any timestamp. */
    @Synchronized
    fun diagnosticSnapshot(): WalkingCalibrationDiagnosticSnapshot = WalkingCalibrationDiagnosticSnapshot(
        latestDecision = latestDecision,
        decisionCounts = Collections.unmodifiableMap(LinkedHashMap(decisionCounts)),
    )

    private fun record(stage: WalkingCalibrationStage, outcome: WalkingCalibrationOutcome, reason: WalkingCalibrationReason) {
        val decision = WalkingCalibrationDecision(stage, outcome, reason)
        latestDecision = decision
        decisionCounts[decision] = (decisionCounts[decision] ?: 0L) + 1L
    }

    private fun resetHistory() {
        segments.reset()
        steps.clear()
        pendingFixes.clear()
        stepCount = 0
        lastFixTimestampMs = null
        lastDeliveryTimestampMs = null
    }

    @Synchronized
    fun onStep(
        timestampMs: Long,
        deltaSteps: Int,
        individualStepDetector: Boolean,
        receivedAtElapsedRealtimeMs: Long,
    ): List<WalkingCalibrationSample> {
        val invalidReason = when {
            !individualStepDetector -> WalkingCalibrationReason.STEP_NOT_INDIVIDUAL
            deltaSteps != 1 -> WalkingCalibrationReason.STEP_NOT_SINGLE
            timestampMs < 0L || receivedAtElapsedRealtimeMs < timestampMs -> WalkingCalibrationReason.INVALID_STEP_TIME
            receivedAtElapsedRealtimeMs - timestampMs > MAXIMUM_STEP_DELIVERY_DELAY_MS -> WalkingCalibrationReason.STALE_STEP
            else -> null
        }
        if (invalidReason != null) {
            resetHistory()
            record(WalkingCalibrationStage.STEP_ELIGIBILITY, WalkingCalibrationOutcome.REJECTED, invalidReason)
            return emptyList()
        }
        record(WalkingCalibrationStage.STEP_ELIGIBILITY, WalkingCalibrationOutcome.PASSED, WalkingCalibrationReason.ELIGIBLE_STEP)
        if (!prepareDelivery(receivedAtElapsedRealtimeMs)) return emptyList()

        val previous = steps.lastOrNull()
        if (previous != null && timestampMs <= previous.timestampMs) {
            resetHistory()
            record(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.STEP_ORDER)
            return emptyList()
        }
        val discontinuity = when {
            previous != null && timestampMs - previous.timestampMs > MAXIMUM_STEP_GAP_MS -> WalkingCalibrationReason.STEP_GAP
            stepCount == Int.MAX_VALUE -> WalkingCalibrationReason.STEP_COUNT_OVERFLOW
            else -> null
        }
        if (discontinuity != null) {
            resetHistory()
            record(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED, discontinuity)
        }
        lastDeliveryTimestampMs = receivedAtElapsedRealtimeMs
        steps.addLast(TimedStep(timestampMs, ++stepCount))
        while (steps.size > MAXIMUM_STEP_HISTORY_SIZE ||
            timestampMs - steps.first().timestampMs > MAXIMUM_STEP_HISTORY_AGE_MS
        ) steps.removeFirst()
        return drainConfirmedFixes(receivedAtElapsedRealtimeMs)
    }

    @Synchronized
    fun onFix(fix: RawWalkingCalibrationFix): List<WalkingCalibrationSample> {
        val invalidReason = segments.credibilityRejectionReason(fix)
        if (invalidReason != null) {
            resetHistory()
            record(WalkingCalibrationStage.RAW_FIX_CREDIBILITY, WalkingCalibrationOutcome.REJECTED, invalidReason)
            return emptyList()
        }
        record(WalkingCalibrationStage.RAW_FIX_CREDIBILITY, WalkingCalibrationOutcome.PASSED, WalkingCalibrationReason.CREDIBLE_RAW_FIX)
        if (!prepareDelivery(fix.receivedAtElapsedRealtimeMs)) return emptyList()
        val previousTimestamp = lastFixTimestampMs
        if (previousTimestamp != null && fix.elapsedRealtimeMs <= previousTimestamp) {
            resetHistory()
            record(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.FIX_ORDER)
            return emptyList()
        }
        if (pendingFixes.size >= MAXIMUM_PENDING_FIXES) {
            resetHistory()
            record(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.PENDING_FIX_CAPACITY)
        }
        lastDeliveryTimestampMs = fix.receivedAtElapsedRealtimeMs
        lastFixTimestampMs = fix.elapsedRealtimeMs
        // Keep the complete immutable raw fix, including its original receipt time.
        pendingFixes.addLast(fix)
        return drainConfirmedFixes(fix.receivedAtElapsedRealtimeMs)
    }

    private fun prepareDelivery(receivedAtMs: Long): Boolean {
        val previousDelivery = lastDeliveryTimestampMs
        if (previousDelivery != null && receivedAtMs < previousDelivery) {
            resetHistory()
            record(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.DELIVERY_ORDER)
            return false
        }
        val oldestPending = pendingFixes.firstOrNull()
        if (oldestPending != null &&
            receivedAtMs - oldestPending.receivedAtElapsedRealtimeMs > MAXIMUM_FIX_CONFIRMATION_WAIT_MS
        ) {
            resetHistory()
            record(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.FIX_CONFIRMATION_EXPIRED)
        }
        lastDeliveryTimestampMs = receivedAtMs
        return true
    }

    private fun drainConfirmedFixes(receivedAtMs: Long): List<WalkingCalibrationSample> {
        val samples = mutableListOf<WalkingCalibrationSample>()
        if (pendingFixes.isEmpty()) {
            record(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.WAITING, WalkingCalibrationReason.WAITING_FOR_RAW_FIX)
        }
        while (pendingFixes.isNotEmpty()) {
            val fix = pendingFixes.first()
            val newestStep = steps.lastOrNull()
            // A step at exactly the endpoint is included, but does not yet seal it.
            if (newestStep == null || newestStep.timestampMs <= fix.elapsedRealtimeMs) {
                record(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.WAITING, WalkingCalibrationReason.WAITING_FOR_STEP_WATERMARK)
                break
            }
            pendingFixes.removeFirst()
            val before = steps.lastOrNull { it.timestampMs <= fix.elapsedRealtimeMs }
            val after = steps.firstOrNull { it.timestampMs > fix.elapsedRealtimeMs }
            val alignmentFailure = when {
                before == null || after == null -> WalkingCalibrationReason.MISSING_STEP_BRACKET
                after.timestampMs - before.timestampMs > MAXIMUM_STEP_GAP_MS -> WalkingCalibrationReason.STEP_BRACKET_GAP
                receivedAtMs - fix.receivedAtElapsedRealtimeMs > MAXIMUM_FIX_CONFIRMATION_WAIT_MS -> WalkingCalibrationReason.FIX_CONFIRMATION_EXPIRED
                else -> null
            }
            if (alignmentFailure != null) {
                segments.reset()
                record(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED, alignmentFailure)
                continue
            }
            record(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.PASSED, WalkingCalibrationReason.ALIGNED_FIX)
            val beforeStep = requireNotNull(before)
            val afterStep = requireNotNull(after)
            segments.observe(
                fix,
                beforeStep.count,
                WalkingCalibrationStepBracket(beforeStep.timestampMs, afterStep.timestampMs),
            )?.let(samples::add)
            segments.lastDecision?.let { record(it.stage, it.outcome, it.reason) }
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
