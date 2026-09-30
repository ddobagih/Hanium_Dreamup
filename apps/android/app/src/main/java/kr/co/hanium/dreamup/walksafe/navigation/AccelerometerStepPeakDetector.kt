package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateConfig

/**
 * S09-17 (originals/09.txt:52): minimal accelerometer fallback step detection used only when no
 * hardware step sensor registered. Its output stays LOW confidence (never promoted before a
 * detection-rate evaluation).
 *
 * - Gravity: the orientation-free magnitude is compared with standard gravity, so the signal is
 *   the dynamic acceleration |a| - g.
 * - Peak and trough: a step is the maximum of one excursion at or above [peakMagnitudeMps2] (the
 *   former 12.2 m/s² threshold) that has risen and fallen back, preceded by a trough since the
 *   previous peak. The trough level is proportional to that previous peak's excess over gravity,
 *   g - [TROUGH_DEPTH_FRACTION] x (peak - g) (before any peak: relative to the peak threshold), so a
 *   short sharp heel-strike peak with a long shallow trough (asymmetric gait) is still a step
 *   (w25 review pos-F9). A sustained high value never ends its excursion; a second hump without a
 *   trough in between belongs to the same step.
 * - Interval continuity: two peaks at least [minimumStepIntervalNs] (the former 300 ms debounce)
 *   and at most [maximumStartIntervalNs] (the existing walking step-gap bound) apart start a
 *   sequence and both become steps; afterwards each interval must stay within 0.5x..2x of the
 *   previous one. A full oscillation faster than the minimum interval, or a broken rhythm, restarts
 *   the sequence, so one shock or fast shaking produces no step.
 */
internal class AccelerometerStepPeakDetector(
    private val peakMagnitudeMps2: Double = PEAK_MAGNITUDE_MPS2,
    private val gravityMps2: Double = STANDARD_GRAVITY_MPS2,
    private val minimumStepIntervalNs: Long = MINIMUM_STEP_INTERVAL_NS,
    private val maximumStartIntervalNs: Long = UserMotionStateConfig().maximumWalkingStepGapMs * NANOS_PER_MILLISECOND,
) {
    private var lastSampleNs: Long? = null

    /** Maximum of the most recently closed excursion; the trough level after it scales with its excess. */
    private var lastExcursionPeakMps2: Double? = null
    private var excursionPeakNs: Long? = null
    private var excursionPeakMps2 = 0.0
    private var troughSincePeak = false
    private var lastPeakNs: Long? = null
    private var lastPeakEmitted = false
    private var lastIntervalNs: Long? = null

    fun reset() {
        lastSampleNs = null
        lastExcursionPeakMps2 = null
        excursionPeakNs = null
        excursionPeakMps2 = 0.0
        troughSincePeak = false
        lastPeakNs = null
        lastPeakEmitted = false
        lastIntervalNs = null
    }

    /** Returns the sensor timestamps (ns) of steps accepted with this sample, oldest first. */
    fun observe(timestampNs: Long, magnitudeMps2: Double): List<Long> {
        if (timestampNs < 0L || !magnitudeMps2.isFinite() || magnitudeMps2 < 0.0 ||
            lastSampleNs?.let { timestampNs <= it } == true
        ) return emptyList()
        lastSampleNs = timestampNs
        if (magnitudeMps2 >= peakMagnitudeMps2) {
            if (excursionPeakNs == null || magnitudeMps2 > excursionPeakMps2) {
                excursionPeakNs = timestampNs
                excursionPeakMps2 = magnitudeMps2
            }
            return emptyList()
        }
        val closedPeakNs = excursionPeakNs
        excursionPeakNs = null
        if (closedPeakNs != null) lastExcursionPeakMps2 = excursionPeakMps2
        val steps = closedPeakNs?.let(::acceptPeak).orEmpty()
        if (magnitudeMps2 <= troughMagnitudeMps2()) troughSincePeak = true
        return steps
    }

    private fun troughMagnitudeMps2(): Double =
        gravityMps2 - TROUGH_DEPTH_FRACTION * ((lastExcursionPeakMps2 ?: peakMagnitudeMps2) - gravityMps2)

    private fun acceptPeak(peakNs: Long): List<Long> {
        // A second hump without a trough in between is part of the same step.
        if (!troughSincePeak) return emptyList()
        troughSincePeak = false
        val previous = lastPeakNs ?: return startSequence(peakNs)
        val intervalNs = peakNs - previous
        if (intervalNs < minimumStepIntervalNs) return startSequence(peakNs)
        val previousInterval = lastIntervalNs
        val continuous = if (previousInterval == null) {
            intervalNs <= maximumStartIntervalNs
        } else {
            intervalNs * 2 >= previousInterval && intervalNs <= previousInterval * 2
        }
        if (!continuous) return startSequence(peakNs)
        val steps = if (lastPeakEmitted) listOf(peakNs) else listOf(previous, peakNs)
        lastPeakNs = peakNs
        lastPeakEmitted = true
        lastIntervalNs = intervalNs
        return steps
    }

    private fun startSequence(peakNs: Long): List<Long> {
        lastPeakNs = peakNs
        lastPeakEmitted = false
        lastIntervalNs = null
        return emptyList()
    }

    companion object {
        /** The former fixed fallback threshold, now the peak level. */
        const val PEAK_MAGNITUDE_MPS2 = 12.2

        /** Trough depth below gravity as a fraction of the previous peak's excess (pos-F9). */
        const val TROUGH_DEPTH_FRACTION = 0.25
        const val STANDARD_GRAVITY_MPS2 = 9.80665
        /** The former fallback debounce, now the minimum step interval. */
        const val MINIMUM_STEP_INTERVAL_NS = 300_000_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
