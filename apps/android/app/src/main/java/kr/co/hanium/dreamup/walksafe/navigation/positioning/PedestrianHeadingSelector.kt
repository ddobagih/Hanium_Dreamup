package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

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
    /** Gated chest heading sampled at or immediately before this GPS course measurement. */
    val magneticTrueHeadingAtGpsCourse: HeadingObservation? = null,
)

data class SelectedPedestrianHeading(
    val headingDegreesTrueNorth: Double,
    val source: PedestrianHeadingSource,
    val selectedAtElapsedRealtimeMs: Long,
    val sourceObservationAtElapsedRealtimeMs: Long,
    val accuracyDegrees: Double? = null,
    /** Newer chest rotation evidence; the absolute GPS observation timestamp stays unchanged. */
    val rotationObservationAtElapsedRealtimeMs: Long? = null,
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
    val turnHeadingSmoothingAlpha: Double = 0.85,
    val minimumAdaptiveTurnDegrees: Double = 30.0,
    val maximumGpsTurnBaselineSkewMs: Long = 100L,
    val maximumGpsTurnTransportMs: Long = 2_000L,
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
        require(turnHeadingSmoothingAlpha > 0.0 && turnHeadingSmoothingAlpha <= 1.0)
        require(minimumAdaptiveTurnDegrees > 0.0 && minimumAdaptiveTurnDegrees <= 180.0)
        require(maximumGpsTurnBaselineSkewMs >= 0L)
        require(maximumGpsTurnTransportMs >= 0L)
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
    private var lastOutput: SelectedPedestrianHeading? = null
    private var lastSelectionAtElapsedRealtimeMs: Long? = null

    fun reset() {
        currentSource = null
        sourceSelectedAtElapsedRealtimeMs = null
        clearPending()
        lastOutput = null
        lastSelectionAtElapsedRealtimeMs = null
    }

    fun select(input: PedestrianHeadingInput): SelectedPedestrianHeading? {
        val now = input.nowElapsedRealtimeMs
        if (now < 0L || lastSelectionAtElapsedRealtimeMs?.let { now < it } == true) return null
        lastSelectionAtElapsedRealtimeMs = now
        val speed = input.speed?.takeIf {
            validAge(now, it.elapsedRealtimeMs, config.maximumSpeedAgeMs) &&
                it.speedMps.isFinite() && it.speedMps >= 0.0 &&
                validAccuracy(it.accuracyMps, config.maximumSpeedAccuracyMps)
        } ?: return unavailable(clearPending = true)

        val gpsObservation = input.gpsCourse?.takeIf {
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
        val gps = gpsObservation?.let { gpsCandidate(it, magnetic, input) }
        val magneticCandidate = magnetic?.let {
            HeadingCandidate(it, it.headingDegreesTrueNorth, requireNotNull(it.accuracyDegrees))
        }
        val preferredSource = when {
            speed.speedMps <= config.magneticPreferredMaximumSpeedMps ->
                PedestrianHeadingSource.MAGNETIC_TRUE
            speed.speedMps >= config.gpsPreferredMinimumSpeedMps ->
                PedestrianHeadingSource.GPS_COURSE
            else -> currentSource ?: return unavailable(clearPending = true)
        }
        val preferredObservation = observationFor(preferredSource, gps, magneticCandidate)
        val activeSource = currentSource

        if (activeSource == null) {
            if (preferredObservation == null) return unavailable(clearPending = true)
            commitSource(preferredSource, now)
            return output(preferredSource, preferredObservation, now)
        }

        if (preferredSource == activeSource) {
            clearPending()
            val observation = observationFor(activeSource, gps, magneticCandidate)
                ?: return unavailable(clearPending = false)
            return output(activeSource, observation, now)
        }

        if (preferredObservation != null) {
            val preferredAtMs = preferredObservation.observation.elapsedRealtimeMs
            val pendingIsCurrent = pendingSource != preferredSource ||
                pendingLastObservationAtElapsedRealtimeMs?.let { preferredAtMs >= it } != false
            if (pendingIsCurrent) recordPending(preferredSource, preferredAtMs)
            val heldLongEnough = now - requireNotNull(sourceSelectedAtElapsedRealtimeMs) >=
                config.minimumSourceHoldMs
            if (
                pendingIsCurrent && heldLongEnough &&
                pendingObservationCount >= config.consecutivePreferredObservationsRequired
            ) {
                commitSource(preferredSource, now)
                return output(preferredSource, preferredObservation, now)
            }
        } else {
            clearPending()
        }

        val activeObservation = observationFor(activeSource, gps, magneticCandidate)
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
        candidate: HeadingCandidate,
        now: Long,
    ): SelectedPedestrianHeading? {
        val observation = candidate.observation
        val previous = lastOutput?.takeIf { it.source == source }
        if (previous != null) {
            if (observation.elapsedRealtimeMs < previous.sourceObservationAtElapsedRealtimeMs) return null
            val previousRotationAt = previous.rotationObservationAtElapsedRealtimeMs
            val rotationAt = candidate.rotationAtMs
            if (previousRotationAt != null && rotationAt != null && rotationAt < previousRotationAt) return null
            if (
                observation.elapsedRealtimeMs == previous.sourceObservationAtElapsedRealtimeMs &&
                rotationAt == previousRotationAt
            ) return previous.copy(selectedAtElapsedRealtimeMs = now)
        }
        val normalized = normalizeDegrees(candidate.headingDegrees)
        val currentEvidenceAt = candidate.rotationAtMs ?: observation.elapsedRealtimeMs
        val previousEvidenceAt = previous?.rotationObservationAtElapsedRealtimeMs
            ?: previous?.sourceObservationAtElapsedRealtimeMs
        val maximumGapMs = if (source == PedestrianHeadingSource.GPS_COURSE) {
            config.maximumGpsCourseAgeMs
        } else {
            config.maximumMagneticHeadingAgeMs
        }
        val sameCorrectionMode = (candidate.rotationAtMs != null) ==
            (previous?.rotationObservationAtElapsedRealtimeMs != null)
        val heading = if (
            previous != null && previousEvidenceAt != null && sameCorrectionMode &&
            currentEvidenceAt - previousEvidenceAt in 0L..maximumGapMs
        ) {
            val delta = abs(shortestDeltaDegrees(previous.headingDegreesTrueNorth, normalized))
            val significantTurn = delta >= max(
                config.minimumAdaptiveTurnDegrees,
                2.0 * candidate.accuracyDegrees,
            )
            val alpha = if (significantTurn) {
                max(config.headingSmoothingAlpha, config.turnHeadingSmoothingAlpha)
            } else {
                config.headingSmoothingAlpha
            }
            circularBlendDegrees(previous.headingDegreesTrueNorth, normalized, alpha)
        } else {
            normalized
        }
        return SelectedPedestrianHeading(
            headingDegreesTrueNorth = heading,
            source = source,
            selectedAtElapsedRealtimeMs = now,
            sourceObservationAtElapsedRealtimeMs = observation.elapsedRealtimeMs,
            accuracyDegrees = candidate.accuracyDegrees,
            rotationObservationAtElapsedRealtimeMs = candidate.rotationAtMs,
        ).also { lastOutput = it }
    }

    /** Transport a still-fresh absolute GPS course using a bounded pair of gated chest headings. */
    private fun gpsCandidate(
        gps: HeadingObservation,
        magnetic: HeadingObservation?,
        input: PedestrianHeadingInput,
    ): HeadingCandidate {
        val raw = HeadingCandidate(gps, gps.headingDegreesTrueNorth, requireNotNull(gps.accuracyDegrees))
        if (!input.phoneForwardMounted || magnetic == null) return raw
        val baseline = input.magneticTrueHeadingAtGpsCourse?.takeIf {
            validHeading(
                it,
                gps.elapsedRealtimeMs,
                config.maximumGpsTurnBaselineSkewMs,
                config.maximumMagneticHeadingAccuracyDegrees,
            )
        } ?: return raw
        if (
            magnetic.elapsedRealtimeMs < gps.elapsedRealtimeMs ||
            magnetic.elapsedRealtimeMs - baseline.elapsedRealtimeMs !in 0L..config.maximumGpsTurnTransportMs
        ) return raw
        val accuracy = sqrt(
            raw.accuracyDegrees * raw.accuracyDegrees +
                requireNotNull(baseline.accuracyDegrees).let { it * it } +
                requireNotNull(magnetic.accuracyDegrees).let { it * it },
        )
        if (accuracy > config.maximumGpsCourseAccuracyDegrees) return raw
        val delta = shortestDeltaDegrees(baseline.headingDegreesTrueNorth, magnetic.headingDegreesTrueNorth)
        return HeadingCandidate(gps, gps.headingDegreesTrueNorth + delta, accuracy, magnetic.elapsedRealtimeMs)
    }

    private fun unavailable(clearPending: Boolean): SelectedPedestrianHeading? {
        if (clearPending) clearPending()
        // Missing evidence produces no heading, but cannot make a reused sample smooth twice.
        return null
    }

    private data class HeadingCandidate(
        val observation: HeadingObservation,
        val headingDegrees: Double,
        val accuracyDegrees: Double,
        val rotationAtMs: Long? = null,
    )

    private fun observationFor(
        source: PedestrianHeadingSource,
        gps: HeadingCandidate?,
        magnetic: HeadingCandidate?,
    ): HeadingCandidate? = when (source) {
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
        return normalizeDegrees(from + shortestDeltaDegrees(from, to) * alpha).let {
            if (abs(it - FULL_CIRCLE_DEGREES) < DEGREES_EPSILON) 0.0 else it
        }
    }

    private fun shortestDeltaDegrees(from: Double, to: Double): Double =
        ((normalizeDegrees(to) - normalizeDegrees(from) + HALF_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES +
            FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES - HALF_CIRCLE_DEGREES

    private companion object {
        const val FULL_CIRCLE_DEGREES = 360.0
        const val HALF_CIRCLE_DEGREES = 180.0
        const val DEGREES_EPSILON = 1e-12
    }
}
