package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualityEstimator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualitySnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssSatelliteSignal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

internal data class PositionCoordinate(
    val latitude: Double,
    val longitude: Double,
)

internal data class LocalEnuPosition(
    val eastM: Double,
    val northM: Double,
)

internal data class PositioningRawFix(
    val coordinate: PositionCoordinate,
    val localEnu: LocalEnuPosition?,
    val reportedHorizontalAccuracyM: Double?,
    val effectiveHorizontalAccuracyM: Double?,
    val elapsedRealtimeMs: Long,
)

internal data class PositioningFilteredFix(
    val coordinate: PositionCoordinate,
    val localEnu: LocalEnuPosition,
    val horizontalUncertaintyM: Double,
    val velocityEastMps: Double,
    val velocityNorthMps: Double,
    val elapsedRealtimeMs: Long,
)

internal data class PositioningSnapshot(
    val raw: PositioningRawFix?,
    val filtered: PositioningFilteredFix?,
    val quality: PositionQuality,
    val confidence: PositionConfidenceDecision,
    val gnssQuality: GnssQualitySnapshot,
    val gnssUpdate: PedestrianPositionEstimatorUpdate? = null,
    val motionUpdate: PedestrianMotionEstimatorUpdate? = null,
    val selectedHeading: SelectedPedestrianHeading? = null,
    val selectedHeadingAccuracyDegrees: Double? = null,
    val motionTrigger: PositioningMotionTrigger? = null,
    val filteredAtGnssMeasurement: PositioningFilteredFix? = null,
)

internal enum class PositioningMotionTrigger {
    STEP,
    ZUPT,
}

/** Serial, Android-free boundary joining the pedestrian positioning components. */
internal class PositioningCoordinator(
    private val estimator: PedestrianPositionEstimator = PedestrianPositionEstimator(),
    private val confidencePolicy: PositionConfidencePolicy = PositionConfidencePolicy(),
    private val headingSelector: PedestrianHeadingSelector = PedestrianHeadingSelector(),
    private val motionProfile: PedestrianMotionProfile = PedestrianMotionProfile(),
    private val gnssQualityEstimator: GnssQualityEstimator = GnssQualityEstimator(),
) {
    private var localFrame: SessionLocalFrame? = null
    private var lastConfidenceDecision: PositionConfidenceDecision? = null
    private var lastConfidenceSampleAtMs: Long? = null
    private var lastMotionConfidenceSampleAtMs: Long? = null

    init {
        applyProfileDynamics(motionProfile.currentSnapshot())
    }

    fun addGnssSignalEpoch(
        elapsedRealtimeNanos: Long,
        signals: List<GnssSatelliteSignal>,
    ): GnssQualitySnapshot = gnssQualityEstimator.addEpoch(elapsedRealtimeNanos, signals)

    fun observeGnss(observation: GnssPositionObservation): PositioningSnapshot {
        val gnssQuality = gnssQualityEstimator.snapshot(toElapsedRealtimeNanos(observation.elapsedRealtimeMs))
        val effectiveAccuracyM = observation.horizontalAccuracyM?.let { accuracyM ->
            if (accuracyM.isFinite()) {
                accuracyM * sqrt(gnssQuality.measurementNoiseMultiplier)
            } else {
                accuracyM
            }
        }
        val adjustedObservation = observation.copy(horizontalAccuracyM = effectiveAccuracyM)
        val update = estimator.observeGnss(adjustedObservation)

        if (
            localFrame == null &&
            update.disposition != GnssObservationDisposition.HARD_REJECTED &&
            update.estimate != null
        ) {
            localFrame = SessionLocalFrame(observation.latitude, observation.longitude)
        }

        val rawCoordinate = PositionCoordinate(observation.latitude, observation.longitude)
        val raw = PositioningRawFix(
            coordinate = rawCoordinate,
            localEnu = localFrame?.takeIf { validCoordinate(rawCoordinate) }?.toLocal(rawCoordinate),
            reportedHorizontalAccuracyM = observation.horizontalAccuracyM,
            effectiveHorizontalAccuracyM = effectiveAccuracyM,
            elapsedRealtimeMs = observation.elapsedRealtimeMs,
        )
        val confidence = when {
            update.disposition != GnssObservationDisposition.HARD_REJECTED && update.estimate != null ->
                observeConfidence(
                    update.estimate.quality,
                    observation.elapsedRealtimeMs,
                    observation.receivedAtElapsedRealtimeMs,
                    informativeGnss = update.estimateAtGnssMeasurement
                        ?.lastInformativeGnssAtElapsedRealtimeMs == observation.elapsedRealtimeMs,
                )
            observation.elapsedRealtimeMs > (lastConfidenceSampleAtMs ?: -1L) ->
                observeConfidence(PositionQuality.UNAVAILABLE, observation.elapsedRealtimeMs, observation.receivedAtElapsedRealtimeMs)
            else -> currentOrUnavailableConfidence(observation.receivedAtElapsedRealtimeMs)
        }
        return snapshot(
            raw = raw,
            estimate = update.estimate,
            confidence = confidence,
            gnssQuality = gnssQuality,
            gnssUpdate = update,
        )
    }

    fun observeStep(
        stepCount: Int,
        headingInput: PedestrianHeadingInput,
        quality: PdrStepQuality,
        stepLengthSigmaM: Double = 0.08,
        receivedAtElapsedRealtimeMs: Long = headingInput.nowElapsedRealtimeMs,
    ): PositioningSnapshot {
        val selectedHeading = headingSelector.select(headingInput)
        val selectedAccuracy = selectedHeading?.accuracyDegrees ?: when (selectedHeading?.source) {
            PedestrianHeadingSource.GPS_COURSE -> headingInput.gpsCourse?.accuracyDegrees
            PedestrianHeadingSource.MAGNETIC_TRUE -> headingInput.magneticTrueHeading?.accuracyDegrees
            null -> null
        }
        val profile = motionProfile.currentSnapshot()
        val update = estimator.observePdrStep(
            PdrStepObservation(
                stepCount = stepCount,
                stepLengthM = profile.stepLengthM,
                stepLengthSigmaM = stepLengthSigmaM,
                headingDegreesTrueNorth = selectedHeading?.headingDegreesTrueNorth,
                headingAccuracyDegrees = selectedAccuracy,
                quality = quality,
                elapsedRealtimeMs = headingInput.nowElapsedRealtimeMs,
            ),
        )
        return motionSnapshot(
            update = update,
            eventAtMs = headingInput.nowElapsedRealtimeMs,
            motionTrigger = PositioningMotionTrigger.STEP,
            selectedHeading = selectedHeading,
            selectedHeadingAccuracyDegrees = selectedAccuracy,
            receivedAtMs = receivedAtElapsedRealtimeMs,
        )
    }

    fun observeZupt(observation: ZuptObservation): PositioningSnapshot {
        val update = estimator.observeZupt(observation)
        return motionSnapshot(
            update = update,
            eventAtMs = observation.elapsedRealtimeMs,
            motionTrigger = PositioningMotionTrigger.ZUPT,
        )
    }

    fun calibrateProfile(sample: WalkingCalibrationSample): PedestrianMotionCalibrationResult {
        val result = motionProfile.calibrate(sample)
        applyProfileDynamics(result.snapshot)
        return result
    }

    fun restoreProfile(profile: PersistedPedestrianMotionProfile): Boolean {
        if (!motionProfile.restore(profile)) return false
        applyProfileDynamics(motionProfile.currentSnapshot())
        return true
    }

    fun currentProfile(): PedestrianMotionProfileSnapshot = motionProfile.currentSnapshot()

    fun snapshotProfileForPersistence(): PersistedPedestrianMotionProfile? =
        motionProfile.snapshotForPersistence()

    fun commitAnnouncementDelivered(token: Long, deliveredAtElapsedRealtimeMs: Long): Boolean =
        confidencePolicy.commitAnnouncementDelivered(token, deliveredAtElapsedRealtimeMs)

    fun reset() {
        estimator.reset()
        confidencePolicy.reset()
        headingSelector.reset()
        gnssQualityEstimator.clear()
        localFrame = null
        lastConfidenceDecision = null
        lastConfidenceSampleAtMs = null
        lastMotionConfidenceSampleAtMs = null
        applyProfileDynamics(motionProfile.currentSnapshot())
    }

    private fun motionSnapshot(
        update: PedestrianMotionEstimatorUpdate,
        eventAtMs: Long,
        motionTrigger: PositioningMotionTrigger,
        selectedHeading: SelectedPedestrianHeading? = null,
        selectedHeadingAccuracyDegrees: Double? = null,
        receivedAtMs: Long = eventAtMs,
    ): PositioningSnapshot {
        val confidence = update.estimate?.let { estimate ->
            if (estimate.quality == PositionQuality.HIGH) {
                currentOrUnavailableConfidence(receivedAtMs)
            } else if (estimate.estimatedAtElapsedRealtimeMs > (lastMotionConfidenceSampleAtMs ?: -1L)) {
                observeMotionConfidence(estimate.quality, estimate.estimatedAtElapsedRealtimeMs, receivedAtMs)
            } else {
                currentOrUnavailableConfidence(receivedAtMs)
            }
        } ?: currentOrUnavailableConfidence(receivedAtMs)
        return snapshot(
            raw = null,
            estimate = update.estimate,
            confidence = confidence,
            gnssQuality = gnssQualityEstimator.snapshot(toElapsedRealtimeNanos(eventAtMs)),
            motionUpdate = update,
            selectedHeading = selectedHeading,
            selectedHeadingAccuracyDegrees = selectedHeadingAccuracyDegrees,
            motionTrigger = motionTrigger,
        )
    }

    private fun snapshot(
        raw: PositioningRawFix?,
        estimate: EstimatedLocation?,
        confidence: PositionConfidenceDecision,
        gnssQuality: GnssQualitySnapshot,
        gnssUpdate: PedestrianPositionEstimatorUpdate? = null,
        motionUpdate: PedestrianMotionEstimatorUpdate? = null,
        selectedHeading: SelectedPedestrianHeading? = null,
        selectedHeadingAccuracyDegrees: Double? = null,
        motionTrigger: PositioningMotionTrigger? = null,
    ): PositioningSnapshot {
        fun filteredFix(estimated: EstimatedLocation?): PositioningFilteredFix? = estimated?.let {
            localFrame?.let { frame ->
                val enu = frame.toLocal(PositionCoordinate(estimated.latitude, estimated.longitude))
                val coordinate = frame.toCoordinate(enu)
                PositioningFilteredFix(
                    coordinate = coordinate,
                    localEnu = enu,
                    horizontalUncertaintyM = estimated.horizontalUncertaintyM,
                    velocityEastMps = estimated.velocityEastMps,
                    velocityNorthMps = estimated.velocityNorthMps,
                    elapsedRealtimeMs = estimated.estimatedAtElapsedRealtimeMs,
                )
            }
        }
        return PositioningSnapshot(
            raw = raw,
            filtered = filteredFix(estimate),
            quality = estimate?.quality ?: PositionQuality.UNAVAILABLE,
            confidence = confidence,
            gnssQuality = gnssQuality,
            gnssUpdate = gnssUpdate,
            motionUpdate = motionUpdate,
            selectedHeading = selectedHeading,
            selectedHeadingAccuracyDegrees = selectedHeadingAccuracyDegrees,
            motionTrigger = motionTrigger,
            filteredAtGnssMeasurement = filteredFix(gnssUpdate?.estimateAtGnssMeasurement),
        )
    }

    private fun observeConfidence(
        quality: PositionQuality,
        sampleAtMs: Long,
        nowMs: Long,
        informativeGnss: Boolean = true,
    ): PositionConfidenceDecision {
        val decision = confidencePolicy.observe(quality, sampleAtMs, nowMs, informativeGnss)
        lastConfidenceDecision = decision
        if (sampleAtMs >= 0L) lastConfidenceSampleAtMs = maxOf(lastConfidenceSampleAtMs ?: -1L, sampleAtMs)
        return decision
    }

    private fun currentOrUnavailableConfidence(nowMs: Long): PositionConfidenceDecision =
        lastConfidenceDecision ?: observeMotionConfidence(PositionQuality.UNAVAILABLE, nowMs, nowMs)

    private fun observeMotionConfidence(
        quality: PositionQuality,
        sampleAtMs: Long,
        nowMs: Long,
    ): PositionConfidenceDecision {
        val decision = confidencePolicy.observeMotion(quality, sampleAtMs, nowMs)
        lastConfidenceDecision = decision
        if (sampleAtMs >= 0L) lastMotionConfidenceSampleAtMs = maxOf(lastMotionConfidenceSampleAtMs ?: -1L, sampleAtMs)
        return decision
    }

    private fun applyProfileDynamics(profile: PedestrianMotionProfileSnapshot) {
        estimator.setPedestrianDynamics(
            PedestrianDynamics(
                processAccelerationSigmaMps2 = profile.walkingProcessAccelerationSigmaMps2,
                averageWalkingSpeedMps = profile.averageWalkingSpeedMps,
            ),
        )
    }

    private fun toElapsedRealtimeNanos(elapsedRealtimeMs: Long): Long = when {
        elapsedRealtimeMs <= 0L -> 0L
        elapsedRealtimeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND -> Long.MAX_VALUE
        else -> elapsedRealtimeMs * NANOS_PER_MILLISECOND
    }

    private fun validCoordinate(coordinate: PositionCoordinate): Boolean =
        coordinate.latitude.isFinite() && coordinate.latitude in -90.0..90.0 &&
            coordinate.longitude.isFinite() && coordinate.longitude in -180.0..180.0

    private class SessionLocalFrame(
        private val originLatitude: Double,
        private val originLongitude: Double,
    ) {
        private val originLatitudeRadians = originLatitude * PI / 180.0
        private val longitudeScale = EARTH_RADIUS_M * cos(originLatitudeRadians) * PI / 180.0
        private val latitudeScale = EARTH_RADIUS_M * PI / 180.0

        fun toLocal(coordinate: PositionCoordinate): LocalEnuPosition = LocalEnuPosition(
            eastM = (coordinate.longitude - originLongitude) * longitudeScale,
            northM = (coordinate.latitude - originLatitude) * latitudeScale,
        )

        fun toCoordinate(position: LocalEnuPosition): PositionCoordinate = PositionCoordinate(
            latitude = originLatitude + position.northM / latitudeScale,
            longitude = originLongitude + position.eastM / longitudeScale,
        )
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_378_137.0
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
