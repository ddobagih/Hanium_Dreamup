package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.abs

enum class PedestrianHeadingSource {
    GPS_COURSE,
    MAGNETIC_TRUE,
}

data class HeadingObservation(
    val headingDegreesTrueNorth: Double,
    val accuracyDegrees: Double?,
    val elapsedRealtimeMs: Long,
)

data class WalkingSpeedObservation(
    val speedMps: Double,
    val accuracyMps: Double?,
    val elapsedRealtimeMs: Long,
)

data class PedestrianHeadingInput(
    val nowElapsedRealtimeMs: Long,
    val speed: WalkingSpeedObservation?,
    val gpsCourse: HeadingObservation?,
    /** Heading already corrected to true north by the Android integration layer. */
    val magneticTrueHeading: HeadingObservation?,
    val phoneForwardMounted: Boolean,
)

data class SelectedPedestrianHeading(
    val headingDegreesTrueNorth: Double,
    val source: PedestrianHeadingSource,
    val selectedAtElapsedRealtimeMs: Long,
    val sourceObservationAtElapsedRealtimeMs: Long,
)

data class PedestrianHeadingSelectorConfig(
    val magneticPreferredMaximumSpeedMps: Double = 0.5,
    val gpsPreferredMinimumSpeedMps: Double = 0.8,
    val maximumSpeedAgeMs: Long = 2_000L,
    val maximumGpsCourseAgeMs: Long = 2_000L,
    val maximumMagneticHeadingAgeMs: Long = 500L,
    val maximumSpeedAccuracyMps: Double = 0.8,
    val maximumGpsCourseAccuracyDegrees: Double = 35.0,
    val maximumMagneticHeadingAccuracyDegrees: Double = 30.0,
    val minimumSourceHoldMs: Long = 1_500L,
    val consecutivePreferredObservationsRequired: Int = 2,
    val headingSmoothingAlpha: Double = 0.35,
) {
    init {
        require(magneticPreferredMaximumSpeedMps >= 0.0)
        require(gpsPreferredMinimumSpeedMps > magneticPreferredMaximumSpeedMps)
        require(maximumSpeedAgeMs >= 0L)
        require(maximumGpsCourseAgeMs >= 0L)
        require(maximumMagneticHeadingAgeMs >= 0L)
        require(maximumSpeedAccuracyMps >= 0.0)
        require(maximumGpsCourseAccuracyDegrees >= 0.0)
        require(maximumMagneticHeadingAccuracyDegrees >= 0.0)
        require(minimumSourceHoldMs >= 0L)
        require(consecutivePreferredObservationsRequired > 0)
        require(headingSmoothingAlpha > 0.0 && headingSmoothingAlpha <= 1.0)
    }
}

/**
 * Chooses a pedestrian heading without Android dependencies.
 *
 * Magnetic input must already include declination correction. In the hysteresis
 * band the active source is retained. A source change requires both a minimum
 * hold time and consecutive, strictly newer observations from the new source.
 */
class PedestrianHeadingSelector(
    private val config: PedestrianHeadingSelectorConfig = PedestrianHeadingSelectorConfig(),
) {
    private var currentSource: PedestrianHeadingSource? = null
    private var sourceSelectedAtElapsedRealtimeMs: Long? = null
    private var pendingSource: PedestrianHeadingSource? = null
    private var pendingObservationCount: Int = 0
    private var pendingLastObservationAtElapsedRealtimeMs: Long? = null
    private var lastOutputHeadingDegrees: Double? = null

    fun reset() {
        currentSource = null
        sourceSelectedAtElapsedRealtimeMs = null
        clearPending()
        lastOutputHeadingDegrees = null
    }

    fun select(input: PedestrianHeadingInput): SelectedPedestrianHeading? {
        val now = input.nowElapsedRealtimeMs
        val speed = input.speed?.takeIf {
            validAge(now, it.elapsedRealtimeMs, config.maximumSpeedAgeMs) &&
                it.speedMps.isFinite() && it.speedMps >= 0.0 &&
                validAccuracy(it.accuracyMps, config.maximumSpeedAccuracyMps)
        } ?: return unavailable(clearPending = true)

        val gps = input.gpsCourse?.takeIf {
            validHeading(it, now, config.maximumGpsCourseAgeMs, config.maximumGpsCourseAccuracyDegrees)
        }
        val magnetic = input.magneticTrueHeading?.takeIf {
            input.phoneForwardMounted &&
                validHeading(
                    it,
                    now,
                    config.maximumMagneticHeadingAgeMs,
                    config.maximumMagneticHeadingAccuracyDegrees,
                )
        }
        val preferredSource = when {
            speed.speedMps <= config.magneticPreferredMaximumSpeedMps ->
                PedestrianHeadingSource.MAGNETIC_TRUE
            speed.speedMps >= config.gpsPreferredMinimumSpeedMps ->
                PedestrianHeadingSource.GPS_COURSE
            else -> currentSource ?: return unavailable(clearPending = true)
        }
        val preferredObservation = observationFor(preferredSource, gps, magnetic)
        val activeSource = currentSource

        if (activeSource == null) {
            if (preferredObservation == null) return unavailable(clearPending = true)
            commitSource(preferredSource, now)
            return output(preferredSource, preferredObservation, now)
        }

        if (preferredSource == activeSource) {
            clearPending()
            val observation = observationFor(activeSource, gps, magnetic)
                ?: return unavailable(clearPending = false)
            return output(activeSource, observation, now)
        }

        if (preferredObservation != null) {
            recordPending(preferredSource, preferredObservation.elapsedRealtimeMs)
            val heldLongEnough = now - requireNotNull(sourceSelectedAtElapsedRealtimeMs) >=
                config.minimumSourceHoldMs
            if (
                heldLongEnough &&
                pendingObservationCount >= config.consecutivePreferredObservationsRequired
            ) {
                commitSource(preferredSource, now)
                return output(preferredSource, preferredObservation, now)
            }
        } else {
            clearPending()
        }

        val activeObservation = observationFor(activeSource, gps, magnetic)
            ?: return unavailable(clearPending = false)
        return output(activeSource, activeObservation, now)
    }

    private fun recordPending(source: PedestrianHeadingSource, observationAtMs: Long) {
        val lastObservationAtMs = pendingLastObservationAtElapsedRealtimeMs
        if (pendingSource != source || lastObservationAtMs == null || observationAtMs < lastObservationAtMs) {
            pendingSource = source
            pendingObservationCount = 1
            pendingLastObservationAtElapsedRealtimeMs = observationAtMs
        } else if (observationAtMs > lastObservationAtMs) {
            pendingObservationCount += 1
            pendingLastObservationAtElapsedRealtimeMs = observationAtMs
        }
    }

    private fun commitSource(source: PedestrianHeadingSource, now: Long) {
        currentSource = source
        sourceSelectedAtElapsedRealtimeMs = now
        clearPending()
    }

    private fun clearPending() {
        pendingSource = null
        pendingObservationCount = 0
        pendingLastObservationAtElapsedRealtimeMs = null
    }

    private fun output(
        source: PedestrianHeadingSource,
        observation: HeadingObservation,
        now: Long,
    ): SelectedPedestrianHeading {
        val normalized = normalizeDegrees(observation.headingDegreesTrueNorth)
        val heading = lastOutputHeadingDegrees?.let {
            circularBlendDegrees(it, normalized, config.headingSmoothingAlpha)
        } ?: normalized
        lastOutputHeadingDegrees = heading
        return SelectedPedestrianHeading(
            headingDegreesTrueNorth = heading,
            source = source,
            selectedAtElapsedRealtimeMs = now,
            sourceObservationAtElapsedRealtimeMs = observation.elapsedRealtimeMs,
        )
    }

    private fun unavailable(clearPending: Boolean): SelectedPedestrianHeading? {
        if (clearPending) clearPending()
        lastOutputHeadingDegrees = null
        return null
    }

    private fun observationFor(
        source: PedestrianHeadingSource,
        gps: HeadingObservation?,
        magnetic: HeadingObservation?,
    ): HeadingObservation? = when (source) {
        PedestrianHeadingSource.GPS_COURSE -> gps
        PedestrianHeadingSource.MAGNETIC_TRUE -> magnetic
    }

    private fun validHeading(
        observation: HeadingObservation,
        now: Long,
        maximumAgeMs: Long,
        maximumAccuracyDegrees: Double,
    ): Boolean =
        observation.headingDegreesTrueNorth.isFinite() &&
            validAccuracy(observation.accuracyDegrees, maximumAccuracyDegrees) &&
            validAge(now, observation.elapsedRealtimeMs, maximumAgeMs)

    private fun validAccuracy(accuracy: Double?, maximum: Double): Boolean =
        accuracy != null && accuracy.isFinite() && accuracy >= 0.0 && accuracy <= maximum

    private fun validAge(now: Long, observedAt: Long, maximumAgeMs: Long): Boolean =
        now >= 0L && observedAt >= 0L && observedAt <= now && now - observedAt <= maximumAgeMs

    private fun normalizeDegrees(degrees: Double): Double {
        val normalized = degrees % FULL_CIRCLE_DEGREES
        return if (normalized < 0.0) normalized + FULL_CIRCLE_DEGREES else normalized
    }

    private fun circularBlendDegrees(from: Double, to: Double, alpha: Double): Double {
        val delta = ((to - from + HALF_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES +
            FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES - HALF_CIRCLE_DEGREES
        return normalizeDegrees(from + delta * alpha).let {
            if (abs(it - FULL_CIRCLE_DEGREES) < DEGREES_EPSILON) 0.0 else it
        }
    }

    private companion object {
        const val FULL_CIRCLE_DEGREES = 360.0
        const val HALF_CIRCLE_DEGREES = 180.0
        const val DEGREES_EPSILON = 1e-12
    }
}
