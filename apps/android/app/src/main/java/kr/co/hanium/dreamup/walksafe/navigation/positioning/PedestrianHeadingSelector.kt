package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
    /**
     * S09-28: observation spacing at which [headingSmoothingAlpha] and [turnHeadingSmoothingAlpha]
     * are the per-observation gains; other spacings use the same time constant. Magnetic evidence
     * (including a GPS course transported by chest rotation) uses the existing magnetic sample
     * spacing limit (500 ms), a raw GPS course the 1 s GNSS spacing. These are the current nominal
     * intervals, not new tuning.
     */
    val magneticSmoothingNominalIntervalMs: Long = 500L,
    val gpsCourseSmoothingNominalIntervalMs: Long = 1_000L,
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
        require(magneticSmoothingNominalIntervalMs > 0L)
        require(gpsCourseSmoothingNominalIntervalMs > 0L)
    }
}

/**
 * Chooses a pedestrian heading without Android dependencies.
 *
 * Magnetic input must already include declination correction. In the hysteresis
 * band the active source is retained; without an active source the first valid
 * source is chosen. A source change requires both a minimum hold time and
 * consecutive, strictly newer observations from the new source, unless the
 * active source's own input is lost and the other source is valid.
 * A trusted GNSS speed stays mandatory for every heading.
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
    private var lastGpsTurnAtElapsedRealtimeMs: Long? = null
    private var lastGpsTurnEvidence: Pair<HeadingObservation, HeadingObservation>? = null
    private var lastGpsTurnCourseAtElapsedRealtimeMs: Long? = null

    /** S09-28: end of the current turn-gain window (one nominal interval of time after a turn starts). */
    private var turnWindowEndsAtElapsedRealtimeMs: Long? = null

    /** S09-29: gated chest headings seen by earlier selections (bounded by the GPS turn transport window). */
    private val recentChestHeadings = ArrayDeque<HeadingObservation>()

    fun reset() {
        currentSource = null
        sourceSelectedAtElapsedRealtimeMs = null
        clearPending()
        lastOutput = null
        lastSelectionAtElapsedRealtimeMs = null
        lastGpsTurnAtElapsedRealtimeMs = null
        lastGpsTurnEvidence = null
        lastGpsTurnCourseAtElapsedRealtimeMs = null
        turnWindowEndsAtElapsedRealtimeMs = null
        recentChestHeadings.clear()
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
        rememberChestHeading(magnetic, input.phoneForwardMounted, now)
        val gps = gpsObservation?.let { gpsCandidate(it, magnetic, input) }
        val magneticCandidate = magnetic?.let {
            HeadingCandidate(it, it.headingDegreesTrueNorth, requireNotNull(it.accuracyDegrees))
        }
        // A source is "lost" only when its own input is missing or invalid. A GPS course that is
        // valid but refused by turn transport stays fail-closed and is never replaced here.
        val replacement = Replacement(
            speedMps = speed.speedMps,
            gpsInputLost = gpsObservation == null,
            gps = gps,
            magnetic = magneticCandidate,
        )
        val preferredSource = when {
            speed.speedMps <= config.magneticPreferredMaximumSpeedMps ->
                PedestrianHeadingSource.MAGNETIC_TRUE
            speed.speedMps >= config.gpsPreferredMinimumSpeedMps ->
                PedestrianHeadingSource.GPS_COURSE
            // The band only suppresses switching; an initial choice uses whichever source is
            // valid, preferring the GPS course (the measured direction of travel).
            else -> currentSource
                ?: gps?.let { PedestrianHeadingSource.GPS_COURSE }
                ?: replacement.substituteFor(PedestrianHeadingSource.GPS_COURSE)?.let {
                    PedestrianHeadingSource.MAGNETIC_TRUE
                }
                ?: return unavailable(clearPending = true)
        }
        val preferredObservation = observationFor(preferredSource, gps, magneticCandidate)
        val activeSource = currentSource

        if (activeSource == null) {
            if (preferredObservation == null) {
                return replaceLostSource(preferredSource, replacement, now) ?: unavailable(clearPending = true)
            }
            commitSource(preferredSource, now)
            return output(preferredSource, preferredObservation, now)
        }

        if (preferredSource == activeSource) {
            clearPending()
            val observation = observationFor(activeSource, gps, magneticCandidate)
                ?: return replaceLostSource(activeSource, replacement, now) ?: unavailable(clearPending = false)
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
            ?: return replaceLostSource(activeSource, replacement, now) ?: unavailable(clearPending = false)
        return output(activeSource, activeObservation, now)
    }

    /**
     * The other valid source for one selection, used when the chosen source has no usable input.
     * Replacing a lost source is not a normal switch, so hold time and consecutive evidence do not
     * apply; the previous heading value is never reused.
     */
    private inner class Replacement(
        val speedMps: Double,
        val gpsInputLost: Boolean,
        val gps: HeadingCandidate?,
        val magnetic: HeadingCandidate?,
    ) {
        fun substituteFor(lost: PedestrianHeadingSource): Pair<PedestrianHeadingSource, HeadingCandidate>? =
            when (lost) {
                PedestrianHeadingSource.GPS_COURSE ->
                    magnetic?.takeIf { gpsInputLost }?.let { PedestrianHeadingSource.MAGNETIC_TRUE to it }
                // A GPS course is not a fallback at magnetic-preferred (near-stationary) speeds.
                PedestrianHeadingSource.MAGNETIC_TRUE ->
                    gps?.takeIf { speedMps > config.magneticPreferredMaximumSpeedMps }
                        ?.let { PedestrianHeadingSource.GPS_COURSE to it }
            }
    }

    private fun replaceLostSource(
        lost: PedestrianHeadingSource,
        replacement: Replacement,
        now: Long,
    ): SelectedPedestrianHeading? {
        val (source, candidate) = replacement.substituteFor(lost) ?: return null
        commitSource(source, now)
        return output(source, candidate, now)
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
            val turnAlpha = max(config.headingSmoothingAlpha, config.turnHeadingSmoothingAlpha)
            // Evidence time is the chest rotation sample when a GPS course is transported by it.
            val nominalMs = if (source == PedestrianHeadingSource.MAGNETIC_TRUE || candidate.rotationAtMs != null) {
                config.magneticSmoothingNominalIntervalMs
            } else {
                config.gpsCourseSmoothingNominalIntervalMs
            }
            val alpha = timeScaledAlpha(
                nominalMs = nominalMs,
                significantTurn = significantTurn,
                previousEvidenceAt = previousEvidenceAt,
                currentEvidenceAt = currentEvidenceAt,
                turnAlpha = turnAlpha,
            )
            circularBlendDegrees(previous.headingDegreesTrueNorth, normalized, alpha)
        } else {
            turnWindowEndsAtElapsedRealtimeMs = null
            normalized
        }
        return SelectedPedestrianHeading(
            headingDegreesTrueNorth = heading,
            source = source,
            selectedAtElapsedRealtimeMs = now,
            sourceObservationAtElapsedRealtimeMs = observation.elapsedRealtimeMs,
            // Smoothing adds a known angular residual; raw sensor accuracy alone is not
            // the error budget of the delayed heading. This is not a calibrated probability.
            accuracyDegrees = (candidate.accuracyDegrees +
                abs(shortestDeltaDegrees(heading, normalized))).coerceAtMost(HALF_CIRCLE_DEGREES),
            rotationObservationAtElapsedRealtimeMs = candidate.rotationAtMs,
        ).also { lastOutput = it }
    }

    /**
     * S09-28 (originals/09.txt:83): the gain follows elapsed evidence time instead of the number of
     * observations. With time constant tau = -T / ln(1 - alpha) for the nominal interval T, the kept
     * fraction over dt is (1 - alpha)^(dt / T), so dt = T reproduces the per-observation gain and any
     * input rate gives the same response. A significant turn opens a turn-gain window of one nominal
     * interval starting at the previous evidence time (the same single observation of turn gain at
     * the nominal rate); the part of dt inside it uses the turn gain. A zero interval keeps the
     * per-observation gain because no elapsed time is available.
     */
    private fun timeScaledAlpha(
        nominalMs: Long,
        significantTurn: Boolean,
        previousEvidenceAt: Long,
        currentEvidenceAt: Long,
        turnAlpha: Double,
    ): Double {
        val windowEnd = turnWindowEndsAtElapsedRealtimeMs
        if (significantTurn && (windowEnd == null || windowEnd <= previousEvidenceAt)) {
            turnWindowEndsAtElapsedRealtimeMs = previousEvidenceAt + nominalMs
        }
        val elapsedMs = currentEvidenceAt - previousEvidenceAt
        if (elapsedMs <= 0L) return if (significantTurn) turnAlpha else config.headingSmoothingAlpha
        val turnEnd = turnWindowEndsAtElapsedRealtimeMs
        val turnMs = if (turnEnd == null) 0L else (min(currentEvidenceAt, turnEnd) - previousEvidenceAt).coerceIn(0L, elapsedMs)
        val steadyMs = elapsedMs - turnMs
        val kept = keptFraction(config.headingSmoothingAlpha, steadyMs, nominalMs) *
            keptFraction(turnAlpha, turnMs, nominalMs)
        return (1.0 - kept).coerceIn(0.0, 1.0)
    }

    private fun keptFraction(alpha: Double, durationMs: Long, nominalMs: Long): Double =
        if (durationMs <= 0L) 1.0 else (1.0 - alpha).pow(durationMs.toDouble() / nominalMs)

    /** Transport a still-fresh absolute GPS course using a bounded pair of gated chest headings. */
    private fun gpsCandidate(
        gps: HeadingObservation,
        magnetic: HeadingObservation?,
        input: PedestrianHeadingInput,
    ): HeadingCandidate? {
        val raw = HeadingCandidate(gps, gps.headingDegreesTrueNorth, requireNotNull(gps.accuracyDegrees))
        fun unrotatedCourseOrNull(): HeadingCandidate? =
            if (lastGpsTurnAtElapsedRealtimeMs?.let { gps.elapsedRealtimeMs <= it } == true) null else raw

        if (!input.phoneForwardMounted || magnetic == null) return unrotatedCourseOrNull()
        val baseline = input.magneticTrueHeadingAtGpsCourse?.takeIf {
            validHeading(
                it,
                gps.elapsedRealtimeMs,
                config.maximumGpsTurnBaselineSkewMs,
                config.maximumMagneticHeadingAccuracyDegrees,
            )
        } ?: return if (untransportableTurnAfter(gps, magnetic)) null else unrotatedCourseOrNull()
        if (magnetic.elapsedRealtimeMs < gps.elapsedRealtimeMs) return unrotatedCourseOrNull()
        if (lastGpsTurnAtElapsedRealtimeMs?.let { magnetic.elapsedRealtimeMs < it } == true) return null
        val turnEvidence = baseline to magnetic
        val previousTurnEvidence = lastGpsTurnEvidence
        val newerCourseForSameRotation = previousTurnEvidence != null &&
            lastGpsTurnCourseAtElapsedRealtimeMs?.let { gps.elapsedRealtimeMs > it } == true &&
            magnetic == previousTurnEvidence.second
        if (lastGpsTurnAtElapsedRealtimeMs == magnetic.elapsedRealtimeMs &&
            previousTurnEvidence != turnEvidence && !newerCourseForSameRotation
        ) return null
        val delta = shortestDeltaDegrees(baseline.headingDegreesTrueNorth, magnetic.headingDegreesTrueNorth)
        if (delta != 0.0 || newerCourseForSameRotation) {
            // Failed transport cannot promote a course preceding observed rotation back to raw validity.
            lastGpsTurnAtElapsedRealtimeMs = magnetic.elapsedRealtimeMs
            lastGpsTurnEvidence = turnEvidence
            lastGpsTurnCourseAtElapsedRealtimeMs = gps.elapsedRealtimeMs
        }
        if (magnetic.elapsedRealtimeMs - baseline.elapsedRealtimeMs !in 0L..config.maximumGpsTurnTransportMs) {
            return unrotatedCourseOrNull()
        }
        val accuracy = sqrt(
            raw.accuracyDegrees * raw.accuracyDegrees +
                requireNotNull(baseline.accuracyDegrees).let { it * it } +
                requireNotNull(magnetic.accuracyDegrees).let { it * it },
        )
        if (accuracy > config.maximumGpsCourseAccuracyDegrees) return unrotatedCourseOrNull()
        return HeadingCandidate(gps, gps.headingDegreesTrueNorth + delta, accuracy, magnetic.elapsedRealtimeMs)
    }

    private fun rememberChestHeading(magnetic: HeadingObservation?, mounted: Boolean, now: Long) {
        if (!mounted) {
            recentChestHeadings.clear()
            return
        }
        if (magnetic != null && recentChestHeadings.lastOrNull()?.let { magnetic.elapsedRealtimeMs <= it.elapsedRealtimeMs } != true) {
            recentChestHeadings.addLast(magnetic)
        }
        while (recentChestHeadings.firstOrNull()?.let { now - it.elapsedRealtimeMs > config.maximumGpsTurnTransportMs } == true) {
            recentChestHeadings.removeFirst()
        }
    }

    /**
     * S09-29 (originals/09.txt:84): without a usable chest baseline at the course time the turn cannot
     * be transported, but chest headings already seen after the course can still confirm a large turn
     * (the existing adaptive-turn angle). Then the pre-turn course is not returned at its original
     * confidence; the selector waits for a course newer than the turn. A chest heading from before the
     * course cannot confirm this, because the turn may already be in the course.
     */
    private fun untransportableTurnAfter(gps: HeadingObservation, magnetic: HeadingObservation): Boolean {
        if (magnetic.elapsedRealtimeMs <= gps.elapsedRealtimeMs) return false
        val reference = recentChestHeadings.firstOrNull {
            it.elapsedRealtimeMs >= gps.elapsedRealtimeMs && it.elapsedRealtimeMs < magnetic.elapsedRealtimeMs
        } ?: return false
        val turned = abs(shortestDeltaDegrees(reference.headingDegreesTrueNorth, magnetic.headingDegreesTrueNorth)) >=
            config.minimumAdaptiveTurnDegrees
        if (turned && lastGpsTurnAtElapsedRealtimeMs?.let { it >= magnetic.elapsedRealtimeMs } != true) {
            lastGpsTurnAtElapsedRealtimeMs = magnetic.elapsedRealtimeMs
        }
        return turned
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
