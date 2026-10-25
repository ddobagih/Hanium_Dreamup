package kr.co.hanium.dreamup.walksafe.fieldlog.diagnostics

import kr.co.hanium.dreamup.walksafe.fieldlog.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.*

/** Getter boundary reads the original OS Location only after the raw/filtered admission. */
internal interface PositionDiagnosticLocation {
    val elapsedRealtimeNanos: Long
    val time: Long
    val accuracy: Float
    val speed: Float
    val bearing: Float
    fun hasAccuracy(): Boolean
    fun hasSpeed(): Boolean
    fun hasBearing(): Boolean
}

/** Existing Positioning/route/step owners are observed at the original trace assembly point. */
internal interface PositionTraceReadings {
    fun currentProfile(): PedestrianMotionProfileSnapshot
    fun currentAcceptedRouteMatchFor(atMs: Long): RouteCorridorMatchResult?
    val stepCount: Int
    val stepLengthM: Float
    val gpsCourse: HeadingObservation?
    val stationaryState: PositionStationaryState
}

/** Trace assembly only: the actual recorder owns authority, leases, UTC anchor and file ACK. */
internal class PositionFieldDiagnosticRecorder(
    private val recorder: () -> PositionFieldRecorderController,
    private val authority: () -> FieldRecordAuthority,
    private val readings: PositionTraceReadings,
) {
    private val latestStepCount get() = readings.stepCount
    private val latestGpsCourseObservation get() = readings.gpsCourse
    private val latestPositionStationaryState get() = readings.stationaryState
    fun appendPositionFieldGnssTrace(
        snapshot: PositioningSnapshot,
        location: PositionDiagnosticLocation,
        matchedEvidenceCurrent: Boolean,
    ) {
        val raw = snapshot.raw ?: return
        // A replayed live state may be later than this GNSS measurement.
        val filtered = snapshot.filteredAtGnssMeasurement
            ?: snapshot.filtered?.takeIf { it.elapsedRealtimeMs == raw.elapsedRealtimeMs }
            ?: return
        val measurementElapsedRealtimeNs = location.elapsedRealtimeNanos
            .takeIf { it >= 0L } ?: return
        val measurementUtcEpochMs = location.time.takeIf { it >= 0L } ?: return
        val routeMatch = if (matchedEvidenceCurrent && snapshot.filtered?.elapsedRealtimeMs == filtered.elapsedRealtimeMs) {
            readings.currentAcceptedRouteMatchFor(filtered.elapsedRealtimeMs)
        } else {
            null
        }
        val profile = readings.currentProfile()
        val frequencyState = snapshot.gnssQuality.frequencySummary.state.name
        val risk = when (snapshot.gnssQuality.signalEnvironmentRisk.name) {
            "LOW" -> PositionGnssRisk.LOW
            "MEDIUM" -> PositionGnssRisk.MEDIUM
            "HIGH" -> PositionGnssRisk.HIGH
            else -> PositionGnssRisk.UNAVAILABLE
        }
        recorder().appendGnss(
            authority,
            PositionFieldGnssSample(
                measurementElapsedRealtimeNs = measurementElapsedRealtimeNs,
                measurementUtcEpochMs = measurementUtcEpochMs,
                rawPosition = PositionTraceCoordinate(
                    raw.coordinate.latitude,
                    raw.coordinate.longitude,
                ),
                filteredPosition = PositionTraceCoordinate(
                    filtered.coordinate.latitude,
                    filtered.coordinate.longitude,
                ),
                matchedPosition = routeMatch?.matchedPoint?.let {
                    PositionTraceCoordinate(it.latitude, it.longitude)
                },
                accuracyMeters = location.accuracy
                    .takeIf { location.hasAccuracy() && it.isFinite() && it >= 0f }
                    ?.toDouble(),
                speedMetersPerSecond = location.speed
                    .takeIf { location.hasSpeed() && it.isFinite() && it >= 0f }
                    ?.toDouble(),
                bearingDegrees = location.bearing
                    .takeIf { location.hasBearing() && it.isFinite() && it in 0f..<360f }
                    ?.toDouble(),
                gnss = PositionGnssTrace(
                    l5Available = when (frequencyState) {
                        "DUAL_FREQUENCY_OBSERVED" -> true
                        "SINGLE_FREQUENCY_OBSERVED" -> false
                        else -> null
                    },
                    l5UsedSatelliteCount = null,
                    meanCn0DbHz = snapshot.gnssQuality.medianCn0DbHz,
                    risk = risk,
                    // Legacy weighted reported-radius proxy, not axis sigma or posterior uncertainty.
                    // This trace value excludes the estimator's floor/default and innovation gate.
                    measurementNoiseMeters = raw.effectiveHorizontalAccuracyM,
                ),
                stepProfile = PositionStepProfileTrace(
                    stepCount = latestStepCount.toLong().coerceAtLeast(0L),
                    currentStepLengthMeters = readings.stepLengthM.toDouble(),
                    profileStepLengthMeters = profile.stepLengthM,
                    profileSampleCount = profile.acceptedSampleCount
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                ),
                heading = PositionHeadingTrace(
                    selectedDegrees = latestGpsCourseObservation?.headingDegreesTrueNorth,
                    source = if (latestGpsCourseObservation == null) {
                        PositionHeadingSource.NONE
                    } else {
                        PositionHeadingSource.GNSS_COURSE
                    },
                ),
                stationary = currentPositionStationaryTrace(zuptApplied = false),
            ),
        )
        snapshot.filtered?.takeIf { it.elapsedRealtimeMs > filtered.elapsedRealtimeMs }?.let { current ->
            val currentMatch = if (matchedEvidenceCurrent) {
                readings.currentAcceptedRouteMatchFor(current.elapsedRealtimeMs)
            } else {
                null
            }
            appendPositionFieldMotionTrace(
                snapshot = snapshot,
                stepDetected = false,
                zuptApplied = false,
                measurementElapsedRealtimeMs = current.elapsedRealtimeMs,
                matchedCoordinate = currentMatch?.matchedPoint?.let {
                    PositionTraceCoordinate(it.latitude, it.longitude)
                },
                routeMatchEvaluated = true,
            )
        }
    }

    fun appendPositionFieldMotionTrace(
        snapshot: PositioningSnapshot?,
        stepDetected: Boolean,
        zuptApplied: Boolean,
        measurementElapsedRealtimeMs: Long,
        matchedCoordinate: PositionTraceCoordinate? = null,
        routeMatchEvaluated: Boolean = false,
    ) {
        val profile = readings.currentProfile()
        val selectedHeading = snapshot?.selectedHeading
        val headingSource = when (selectedHeading?.source?.name) {
            "GPS_COURSE" -> PositionHeadingSource.GNSS_COURSE
            "MAGNETIC_TRUE" -> PositionHeadingSource.MAGNETIC_ROTATION_VECTOR
            else -> PositionHeadingSource.NONE
        }
        recorder().appendMotion(
            authority,
            PositionFieldMotionSample(
                measurementElapsedRealtimeMs = measurementElapsedRealtimeMs,
                filteredPosition = snapshot?.filtered?.coordinate?.let {
                    PositionTraceCoordinate(it.latitude, it.longitude)
                },
                matchedPosition = matchedCoordinate,
                routeMatchEvaluated = routeMatchEvaluated,
                stepProfile = PositionStepProfileTrace(
                    stepCount = latestStepCount.toLong().coerceAtLeast(0L),
                    stepDetected = stepDetected,
                    currentStepLengthMeters = readings.stepLengthM.toDouble(),
                    profileStepLengthMeters = profile.stepLengthM,
                    profileSampleCount = profile.acceptedSampleCount
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                ),
                heading = PositionHeadingTrace(
                    selectedDegrees = selectedHeading?.headingDegreesTrueNorth,
                    source = headingSource,
                ),
                stationary = currentPositionStationaryTrace(zuptApplied),
            ),
        )
    }

    fun currentPositionStationaryTrace(zuptApplied: Boolean): PositionStationaryTrace =
        PositionStationaryTrace(
            stationary = latestPositionStationaryState == PositionStationaryState.STATIONARY,
            state = latestPositionStationaryState,
            zuptApplied = zuptApplied,
        )
}
