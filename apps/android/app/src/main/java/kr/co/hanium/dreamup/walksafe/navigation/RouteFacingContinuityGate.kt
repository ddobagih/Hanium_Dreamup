package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.abs

internal const val NOMINAL_CLOCK_BOUNDARY_CONTINUITY_DEGREES = 5.0

/**
 * Owns source confirmation and clock-sector continuity for one navigator.
 * Calls are serialized by RouteNavigator; observing the same timestamp never confirms twice.
 * Route progress, speech grace and direction-recheck lifetimes remain with the navigator.
 */
internal class RouteFacingContinuityGate {
    private var confirmedClockHour: Int? = null
    private var pendingClockHour: Int? = null
    private var pendingClockObservationCount = 0
    private var lastClockObservationAtMs: Long? = null
    // Facing-source continuity: the previous source is comparison evidence, never the facing.
    private var facingSourceAnchor: RouteFacingSource? = null
    var facingSourceSwitchPending = false
        private set
    private var facingRecoveryEpisodeAnchor: RouteCompassRecoveryEpisode? = null
    private var facingConfirmationDirection: RouteFacingDirection? = null
    private var facingConfirmationNominalClock: Int? = null
    private var facingConfirmationNominalDifferenceDegrees: Double? = null
    private var facingConfirmationCount = 0
    private var facingConfirmationObservedAtMs: Long? = null


    fun stabilizeClockSector(
        facing: RouteFacingGuidanceResult,
        observation: RouteFacingObservation?,
    ): RouteFacingGuidanceResult {
        val hour = facing.clockHour
        if (hour == null || observation == null) {
            // S08-5 (nav-F1): only an absent, UNKNOWN or imprecise observation clears the spoken
            // sector. A usable, precise sample withheld only because it is near a sector edge keeps
            // the confirmed sector, so the next sector still needs its own consecutive fresh samples;
            // it only interrupts a pending change.
            if (observation == null || facing.direction == RouteFacingDirection.UNKNOWN || !facing.clockPrecisionAvailable) {
                resetClockSector()
            } else if (lastClockObservationAtMs?.let { observation.observedAtElapsedRealtimeMs > it } != false) {
                lastClockObservationAtMs = observation.observedAtElapsedRealtimeMs
                pendingClockHour = null
                pendingClockObservationCount = 0
            }
            return facing
        }
        val observedAtMs = observation.observedAtElapsedRealtimeMs
        val lastObservedAtMs = lastClockObservationAtMs
        if (lastObservedAtMs != null && observedAtMs < lastObservedAtMs) {
            return facing.copy(clockDirectionAllowed = false)
        }
        if (lastObservedAtMs == null || observedAtMs > lastObservedAtMs) {
            lastClockObservationAtMs = observedAtMs
            if (pendingClockHour == hour) {
                pendingClockObservationCount += 1
            } else {
                pendingClockHour = hour
                pendingClockObservationCount = 1
            }
            // Only a change of the spoken sector is stabilized: with no confirmed sector (first fix,
            // or after an invalid/absent observation reset it) the first fresh sample is spoken.
            // A repeated call with the same sensor sample cannot confirm a sector change.
            // Nominal source confirmation already counted two actual nearby representatives.
            // Do not turn their tiny boundary crossing back into loss at this second gate.
            val confirmedNominal = facing.clockReferenceKind == RouteClockReferenceKind.NOMINAL_TREND_CLOCK &&
                hasConfirmedFacingDirection(facing)
            if (confirmedClockHour == null || pendingClockObservationCount >= 2 || confirmedNominal) confirmedClockHour = hour
        }
        return facing.copy(clockDirectionAllowed = hour == confirmedClockHour)
    }

    fun resetClockSector() {
        confirmedClockHour = null
        pendingClockHour = null
        pendingClockObservationCount = 0
        lastClockObservationAtMs = null
    }

    /**
     * After a primary/fallback compass switch, poor-RV recovery, or a skipped fallback motion check,
     * a direction is spoken only after two distinct fresh observations of that source agree.
     * The previous source detects the switch; its heading is never reused as the current facing.
     */
    fun gateFacingSource(
        facing: RouteFacingGuidanceResult,
        observation: RouteFacingObservation?,
    ): RouteFacingGuidanceResult {
        if (observation == null || facing.direction == RouteFacingDirection.UNKNOWN) {
            facingConfirmationDirection = null
            facingConfirmationNominalClock = null
            facingConfirmationNominalDifferenceDegrees = null
            facingConfirmationCount = 0
            return facing
        }
        if (facingSourceChanged(observation)) {
            // The consumer need not have received the intervening null. Reset for a new episode
            // or actual rejection, not every flagged sample; two fresh agreeing samples confirm.
            facingSourceSwitchPending = true
            facingConfirmationDirection = null
            facingConfirmationNominalClock = null
            facingConfirmationNominalDifferenceDegrees = null
            facingConfirmationCount = 0
            facingConfirmationObservedAtMs = null
        }
        facingRecoveryEpisodeAnchor = observation.fallbackRecoveryEpisode
        facingSourceAnchor = observation.source
        val observedAtMs = observation.observedAtElapsedRealtimeMs
        val lastObservedAtMs = facingConfirmationObservedAtMs
        val nominalClock = facing.clockReferenceKind == RouteClockReferenceKind.NOMINAL_TREND_CLOCK
        if (lastObservedAtMs == null || observedAtMs > lastObservedAtMs) {
            facingConfirmationObservedAtMs = observedAtMs
            if (facingConfirmationDirection == facing.direction &&
                (!nominalClock || hasContinuousNominalClock(facing))
            ) {
                facingConfirmationCount += 1
            } else {
                facingConfirmationDirection = facing.direction
                facingConfirmationCount = 1
            }
            facingConfirmationNominalClock = facing.clockHour.takeIf { nominalClock }
            facingConfirmationNominalDifferenceDegrees = facing.signedDifferenceDegrees.takeIf { nominalClock }
        }
        val confirmed = hasConfirmedFacingDirection(facing)
        if (facingSourceSwitchPending && confirmed) facingSourceSwitchPending = false
        val motionUnchecked = observation.sensorQuality == RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK
        // A cold nominal clock needs two real observations of the same clock or its small boundary.
        // This continuity does not promote its missing numeric angular accuracy.
        return if ((facingSourceSwitchPending || motionUnchecked || nominalClock) && !confirmed) {
            facing.copy(direction = RouteFacingDirection.UNKNOWN)
        } else facing
    }

    fun facingSourceChanged(observation: RouteFacingObservation): Boolean =
        (observation.fallbackRecoveryEpisode?.let {
            it.sequence != facingRecoveryEpisodeAnchor?.sequence ||
                it.startedAtElapsedRealtimeMs != facingRecoveryEpisodeAnchor?.startedAtElapsedRealtimeMs
        } == true) ||
            (facingSourceAnchor != null && observation.source != facingSourceAnchor)

    fun hasConfirmedFacingDirection(facing: RouteFacingGuidanceResult): Boolean =
        facingConfirmationDirection == facing.direction && facingConfirmationCount >= 2 &&
            (facing.clockReferenceKind != RouteClockReferenceKind.NOMINAL_TREND_CLOCK ||
                hasContinuousNominalClock(facing))

    private fun hasContinuousNominalClock(facing: RouteFacingGuidanceResult): Boolean {
        val previousClock = facingConfirmationNominalClock ?: return false
        val currentClock = facing.clockHour ?: return false
        if (previousClock == currentClock) return true
        val clockwiseSteps = (currentClock - previousClock + 12) % 12
        if (clockwiseSteps != 1 && clockwiseSteps != 11) return false
        val previousDifference = facingConfirmationNominalDifferenceDegrees ?: return false
        val currentDifference = facing.signedDifferenceDegrees ?: return false
        val change = abs((currentDifference - previousDifference + 540.0) % 360.0 - 180.0)
        return change <= NOMINAL_CLOCK_BOUNDARY_CONTINUITY_DEGREES
    }

    fun resetFacingSourceGate() {
        facingSourceAnchor = null
        facingSourceSwitchPending = false
        facingRecoveryEpisodeAnchor = null
        facingConfirmationDirection = null
        facingConfirmationNominalClock = null
        facingConfirmationNominalDifferenceDegrees = null
        facingConfirmationCount = 0
        facingConfirmationObservedAtMs = null
    }

}
