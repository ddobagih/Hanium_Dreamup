package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A route-only representative direction; neither a new measurement nor an angular error. */
data class RouteHeadingTrendEstimate(
    val representativeDegreesTrueNorth: Double,
    val latestActualObservedAtMs: Long,
    val windowSpanMs: Long,
    val sampleCount: Int,
    val source: RouteFacingSource,
    /** Change of successive representatives; the raw sensor rate remains on the observation. */
    val representativeAngularRateDegreesPerSecond: Double? = null,
    /** Spread of the contributing raw headings, not a sensor angular error. */
    val observedHeadingSpanDegrees: Double = 0.0,
    val followsActualLargeTurn: Boolean = false,
    /** Original accepted time covered the window, even when cadence leaves its retained span shorter. */
    val hasCompleteTimeWindow: Boolean = false,
    /** A moderate raw jump needs another actual sample before replacing the representative. */
    val largeTurnPending: Boolean = false,
) {
    internal fun isBoundTo(observation: RouteFacingObservation): Boolean =
        representativeDegreesTrueNorth.isFinite() && representativeDegreesTrueNorth in 0.0..<360.0 &&
            latestActualObservedAtMs == observation.observedAtElapsedRealtimeMs && source == observation.source &&
            windowSpanMs in 0L..600L && sampleCount in 1..601 &&
            representativeAngularRateDegreesPerSecond?.let { it.isFinite() && it >= 0.0 } != false &&
            observedHeadingSpanDegrees.isFinite() && observedHeadingSpanDegrees in 0.0..360.0 &&
            (sampleCount > 1 || windowSpanMs == 0L)
}

/** Short circular history for walking sway. Large actual turns immediately replace the history. */
internal class RouteHeadingTrend {
    private val samples = ArrayDeque<RouteFacingObservation>()
    private var estimate: RouteHeadingTrendEstimate? = null
    private var followsActualLargeTurn = false
    private var awaitingTurnSettle = false
    private var completeTimeWindow = false
    private var pendingLargeTurn: RouteFacingObservation? = null
    private var pendingLargeTurnStartedAtMs: Long? = null

    fun reset() { samples.clear(); estimate = null; followsActualLargeTurn = false; awaitingTurnSettle = false; completeTimeWindow = false; pendingLargeTurn = null; pendingLargeTurnStartedAtMs = null }

    fun observe(observation: RouteFacingObservation, nowMs: Long): RouteHeadingTrendEstimate? {
        if (!RouteFacingGuidance.isUsableObservation(observation, nowMs) || observation.magneticFieldSuspect) {
            reset()
            return null
        }
        val previous = samples.lastOrNull()
        if (previous != null) {
            if (observation.source == previous.source && observation.observedAtElapsedRealtimeMs <= requireNotNull(estimate).latestActualObservedAtMs) {
                return estimate?.takeIf { it.isBoundTo(observation) }
            }
            val gap = observation.observedAtElapsedRealtimeMs - requireNotNull(estimate).latestActualObservedAtMs
            val change = abs(signedAngle(observation.degreesTrueNorth, estimate!!.representativeDegreesTrueNorth))
            val oldestChange = abs(signedAngle(observation.degreesTrueNorth, samples.first().degreesTrueNorth))
            val actualTurn = change >= 60.0 - 0.000001 || oldestChange >= 60.0 - 0.000001
            if (observation.source != previous.source || gap !in 1L..500L) reset()
            else if (actualTurn) {
                val candidate = pendingLargeTurn
                val continuesTurn = candidate?.let {
                    observation.observedAtElapsedRealtimeMs - it.observedAtElapsedRealtimeMs in 1L..500L &&
                        abs(signedAngle(observation.degreesTrueNorth, it.degreesTrueNorth)) <= 20.0
                } == true
                val startedAtMs = if (continuesTurn) requireNotNull(pendingLargeTurnStartedAtMs)
                    else observation.observedAtElapsedRealtimeMs
                // Distinct high-rate callbacks are not elapsed confirmation. Hold a moderate
                // jump through a 300 ms observation span; a real reversal remains immediate.
                val confirmed = maxOf(change, oldestChange) >= 135.0 || continuesTurn &&
                    observation.observedAtElapsedRealtimeMs - startedAtMs >= 300L
                if (!confirmed) {
                    pendingLargeTurn = observation
                    pendingLargeTurnStartedAtMs = startedAtMs
                    // This real raw jump is considered but excluded from the stable estimate.
                    // Its original time is preserved, while CLOCK is withheld until it is supported.
                    return estimate!!.copy(latestActualObservedAtMs = observation.observedAtElapsedRealtimeMs,
                        largeTurnPending = true).also { estimate = it }
                }
                reset(); followsActualLargeTurn = true; awaitingTurnSettle = true
            }
            else if (awaitingTurnSettle && abs(signedAngle(observation.degreesTrueNorth, previous.degreesTrueNorth)) <= 3.0) {
                // Once a real large turn settles, its new direction precedes the older turn arc.
                reset()
            }
        }
        pendingLargeTurn = null
        pendingLargeTurnStartedAtMs = null
        samples.addLast(observation)
        while (samples.size > 601) {
            samples.removeFirst()
            completeTimeWindow = false
        }
        while (observation.observedAtElapsedRealtimeMs - samples.first().observedAtElapsedRealtimeMs > 600L) {
            // This removed original sample proves that real accepted time crossed the boundary.
            // Keep the actual span of the remaining measurements instead of rounding it to600.
            samples.removeFirst(); completeTimeWindow = true
        }
        // Circular averaging keeps 359/1 near north, rather than averaging them into south.
        val east = samples.sumOf { sin(Math.toRadians(it.degreesTrueNorth)) }
        val north = samples.sumOf { cos(Math.toRadians(it.degreesTrueNorth)) }
        val representative = ((Math.toDegrees(atan2(east, north)) % 360.0) + 360.0) % 360.0
        val offsets = samples.map { signedAngle(it.degreesTrueNorth, representative) }
        val observedSpan = offsets.max() - offsets.min()
        val representativeRate = estimate?.takeIf {
            observation.observedAtElapsedRealtimeMs > it.latestActualObservedAtMs
        }?.let { abs(signedAngle(representative, it.representativeDegreesTrueNorth)) * 1000.0 /
            (observation.observedAtElapsedRealtimeMs - it.latestActualObservedAtMs) }
        return RouteHeadingTrendEstimate(representative, observation.observedAtElapsedRealtimeMs,
            observation.observedAtElapsedRealtimeMs - samples.first().observedAtElapsedRealtimeMs,
            samples.size, observation.source, representativeRate, observedSpan,
            followsActualLargeTurn, completeTimeWindow ||
                observation.observedAtElapsedRealtimeMs - samples.first().observedAtElapsedRealtimeMs >= 600L)
            .also { estimate = it }
    }

    private fun signedAngle(a: Double, b: Double): Double = (a - b + 540.0) % 360.0 - 180.0
}
