package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Route-compass inputs registered by [AndroidEarthOrientationTracker]. */
enum class RouteCompassStream {
    ROTATION_VECTOR,
    MAGNETIC_FIELD,
    GRAVITY,
    ACCELEROMETER,
}

/**
 * Receipt watchdog. Registration success is not delivery: a registered stream must deliver a first
 * event and keep delivering. Recovery is limited re-registration, never one per 500 ms freshness miss.
 */
data class RouteCompassWatchdogConfig(
    val firstEventDeadlineMs: Long = 1_000L,
    val maximumEventGapMs: Long = 2_000L,
    val minimumReregistrationIntervalMs: Long = 30_000L,
    val maximumReregistrationsPerStart: Int = 2,
)

/**
 * Pure route-compass sensor lifecycle and evaluation state. Android sensor registration, the
 * callback thread and the strict AR/PDR orientation remain in [AndroidEarthOrientationTracker];
 * this class receives already-converted events so the lifecycle can be executed in unit tests.
 * Every event carries the registration generation that produced it; late events are ignored.
 */
internal class RouteCompassSensorState(
    private val watchdog: RouteCompassWatchdogConfig = RouteCompassWatchdogConfig(),
) {
    // Instance-only instrumentation seam: no setting, intent or persisted flag selects this.
    // The test sets/restores it while the owning Tracker is stopped. Raw and strict/PDR inputs
    // stay unchanged; query and event publication evaluate this same state and projection rate.
    @Volatile
    private var forwardAxisForInstrumentation = RouteCompassForwardAxis.REAR_CAMERA
    private var generation = 0
    private var started = false
    private var registeredStreams: Set<RouteCompassStream> = emptySet()
    private var receiptGraceStartedAtMs = 0L
    private val lastReceiptAtMs = mutableMapOf<RouteCompassStream, Long>()
    private val firstReceiptAtMs = mutableMapOf<RouteCompassStream, Long>()
    private val newestCallbackTimestampNs = mutableMapOf<RouteCompassStream, Long>()
    private val newestMeasurementTime = mutableMapOf<RouteCompassStream, SensorMeasurementTime>()
    private var consumingCallback: AdmittedSensorCallback? = null
    private var reregistrationCount = 0
    private var lastReregistrationAtMs: Long? = null

    private var latestRouteOrientation: DeviceEarthOrientation? = null
    private var latestRotationEventAtMs: Long? = null
    // Accuracy callbacks describe current sensor state, independently of the last vector's age.
    private var rotationAccuracyStatus: EarthOrientationAccuracy? = null
    private var rotationQualityRequiresFreshSample = false
    private var rotationQualityRejectedAtMs: Long? = null
    private var fallbackRecoveryEpisode: RouteCompassRecoveryEpisode? = null
    // Keep the sequence across stop/start; a consumer can have missed both the stop and rejection.
    private var recoveryEpisodeSequence = 0L
    private var unavailableReason = RouteCompassHeadingReason.ORIENTATION_MISSING
    private var previousRotation: DeviceEarthOrientation? = null
    private var rotationRateDegreesPerSecond: Double? = null
    private var rearFacingRateDegreesPerSecond: Double? = null
    private var magneticRateDegreesPerSecond: Double? = null
    private var magneticRateAtMs: Long? = null
    private var latestGravity: RouteCompassVectorSample? = null
    private val accelerometerWindow = ArrayDeque<RouteCompassVectorSample>()
    private val magneticHistory = ArrayDeque<RouteCompassVectorSample>()
    private val headingTrend = RouteHeadingTrend()

    val isStarted: Boolean get() = started
    val reregistrations: Int get() = reregistrationCount
    fun firstReceiptAt(stream: RouteCompassStream): Long? = firstReceiptAtMs[stream]
    fun lastReceiptAt(stream: RouteCompassStream): Long? = lastReceiptAtMs[stream]
    fun latestRotationEventAt(): Long? = latestRotationEventAtMs
    /** Rate between the last two rotation-vector samples (null across gaps or invalid rotation). */
    fun latestRotationRateDegreesPerSecond(): Double? = rotationRateDegreesPerSecond
    fun latestGravityAt(): Long? = latestGravity?.observedAtMs
    fun latestAccelerometerAt(): Long? = accelerometerWindow.lastOrNull()?.observedAtMs
    fun latestMagneticAt(): Long? = latestMagneticMeasurement()?.observedAtMs
    fun rotationQualityWait(): Boolean = rotationQualityRequiresFreshSample
    fun rotationQualityRejectedAt(): Long? = rotationQualityRejectedAtMs

    fun start(generation: Int, nowMs: Long, registered: Set<RouteCompassStream>) {
        clearSamples()
        this.generation = generation
        started = registered.isNotEmpty()
        registeredStreams = registered
        receiptGraceStartedAtMs = nowMs
        reregistrationCount = 0
        lastReregistrationAtMs = null
        unavailableReason = if (RouteCompassStream.ROTATION_VECTOR in registered) {
            RouteCompassHeadingReason.ORIENTATION_MISSING
        } else RouteCompassHeadingReason.SENSOR_UNAVAILABLE
    }

    fun stop() {
        generation = Int.MIN_VALUE
        started = false
        registeredStreams = emptySet()
        clearSamples()
        unavailableReason = RouteCompassHeadingReason.ORIENTATION_MISSING
    }

    /** A limited re-registration keeps the samples; only callbacks of [generation] are accepted. */
    fun onReregistered(generation: Int, nowMs: Long, registered: Set<RouteCompassStream>) {
        if (!started) return
        this.generation = generation
        headingTrend.reset()
        registeredStreams = registered
        receiptGraceStartedAtMs = nowMs
        reregistrationCount += 1
        lastReregistrationAtMs = nowMs
    }

    /**
     * Dispatch a current Android callback only after comparing its original ns to actual receipt.
     * Rejected measurements still prove stream delivery, but cannot reach either heading cache.
     */
    fun onSensorCallback(
        generation: Int,
        stream: RouteCompassStream,
        sensorTimestampNanos: Long,
        receivedAtNanos: Long,
        consume: (receivedAtMs: Long) -> Unit,
    ) {
        if (!accepts(generation) || receivedAtNanos < 0L) return
        val receivedAtMs = receivedAtNanos / 1_000_000L
        receipt(stream, receivedAtMs)
        if (sensorTimestampNanos < 0L || sensorTimestampNanos > receivedAtNanos) return
        val previousMeasurement = newestMeasurementTime[stream]
        if (newestCallbackTimestampNs[stream]?.let { sensorTimestampNanos <= it } == true ||
            previousMeasurement?.let {
                sensorTimestampNanos / 1_000_000L < it.observedAtMs ||
                    it.originalTimestampNs?.let { previous -> sensorTimestampNanos <= previous } == true
            } == true
        ) return
        newestCallbackTimestampNs[stream] = sensorTimestampNanos
        val previousCallback = consumingCallback
        consumingCallback = AdmittedSensorCallback(generation, stream, sensorTimestampNanos)
        try {
            // Tracker state, strict matrices/history, rates and recovery are all inside this callback.
            consume(receivedAtMs)
        } finally {
            consumingCallback = previousCallback?.takeIf { accepts(it.generation) }
        }
    }

    /** Returns the accepted RV accuracy (after the current accuracy status), or null if ignored. */
    fun onRotationVector(
        generation: Int,
        rotation: RotationMatrix3?,
        headingErrorDegrees: Float,
        observedAtMs: Long,
        eventAccuracy: EarthOrientationAccuracy,
        nowMs: Long,
    ): EarthOrientationAccuracy? {
        if (!accepts(generation) || !ownsConsumingCallback(generation, RouteCompassStream.ROTATION_VECTOR)) return null
        receipt(RouteCompassStream.ROTATION_VECTOR, nowMs)
        // Callback receipt is stream-liveness evidence, not a usable orientation measurement.
        // Impossible sensor times must not replace the accepted baseline or recovery state.
        if (observedAtMs < 0L || observedAtMs > nowMs) return null
        if (!admitMeasurementTime(generation, RouteCompassStream.ROTATION_VECTOR, observedAtMs)) return null
        latestRotationEventAtMs = observedAtMs
        val accuracy = rotationAccuracyStatus?.let { eventAccuracy.downgradedTo(it) } ?: eventAccuracy
        if (accuracy < EarthOrientationAccuracy.MEDIUM) beginFallbackRecovery(nowMs)
        if (rotation == null) {
            unavailableReason = RouteCompassHeadingReason.INVALID_ROTATION
            latestRouteOrientation = null
            previousRotation = null
            rotationRateDegreesPerSecond = null
            rearFacingRateDegreesPerSecond = null
            return null
        }
        if (accuracy >= EarthOrientationAccuracy.MEDIUM &&
            observedAtMs >= 0L && observedAtMs <= nowMs && nowMs - observedAtMs <= FRESHNESS_MS
        ) {
            rotationQualityRequiresFreshSample = false
            rotationQualityRejectedAtMs = null
        }
        val orientation = DeviceEarthOrientation(rotation, observedAtMs, headingErrorDegrees, accuracy)
        rotationRateDegreesPerSecond = previousRotation?.let { previous ->
            val intervalMs = observedAtMs - previous.observedAtElapsedRealtimeMs
            if (intervalMs in 1L..MAXIMUM_RATE_INTERVAL_MS) {
                rotationAngleDegrees(previous.deviceToMagneticEnu, rotation) * 1_000.0 / intervalMs
            } else null
        }
        // Route speech follows the rear camera's horizontal direction. Rolling or pitching
        // without changing that direction must not be extrapolated into an unseen yaw turn.
        // Keep the full 3D rate for fallback sensor pairing and strict chest/PDR consumers.
        rearFacingRateDegreesPerSecond = previousRotation?.let { previous ->
            val intervalMs = observedAtMs - previous.observedAtElapsedRealtimeMs
            if (intervalMs in 1L..MAXIMUM_RATE_INTERVAL_MS) {
                horizontalFacingChangeDegrees(previous.deviceToMagneticEnu, rotation, forwardAxisForInstrumentation)
                    ?.let { it * 1_000.0 / intervalMs }
            } else null
        } ?: rotationRateDegreesPerSecond
        previousRotation = orientation
        latestRouteOrientation = orientation
        if (headingErrorDegrees.isNaN() || headingErrorDegrees < 0f || !headingErrorDegrees.isFinite()) {
            unavailableReason = if (headingErrorDegrees.isNaN() || headingErrorDegrees < 0f) {
                RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE
            } else RouteCompassHeadingReason.HEADING_ACCURACY
        }
        return accuracy
    }

    fun onVector(generation: Int, stream: RouteCompassStream, sample: RouteCompassVectorSample, nowMs: Long) {
        if (!accepts(generation) || stream == RouteCompassStream.ROTATION_VECTOR || !ownsConsumingCallback(generation, stream)) return
        if (!sample.hasValidSensorTimestamp()) return
        receipt(stream, nowMs)
        // Receipt is a callback fact. Invalid values must not replace accepted guidance samples,
        // consume a history slot, or contaminate the measured rate before the later heading gate.
        if (!sample.x.isFinite() || !sample.y.isFinite() || !sample.z.isFinite() ||
            sample.observedAtMs < 0L || sample.observedAtMs > nowMs
        ) return
        if (!admitMeasurementTime(generation, stream, sample.observedAtMs, sample.sensorTimestampNanos)) return
        when (stream) {
            RouteCompassStream.GRAVITY -> latestGravity = sample
            RouteCompassStream.ACCELEROMETER -> accelerometerWindow.append(sample)
            RouteCompassStream.MAGNETIC_FIELD -> {
                magneticHistory.append(sample)
                updateMagneticRate(sample)
            }
            RouteCompassStream.ROTATION_VECTOR -> Unit
        }
    }

    fun onAccuracyChanged(generation: Int, stream: RouteCompassStream, accuracy: EarthOrientationAccuracy, nowMs: Long) {
        if (!accepts(generation)) return
        when (stream) {
            RouteCompassStream.ROTATION_VECTOR -> {
                rotationAccuracyStatus = accuracy
                if (accuracy < EarthOrientationAccuracy.MEDIUM) {
                    rotationQualityRequiresFreshSample = true
                    rotationQualityRejectedAtMs = nowMs
                    beginFallbackRecovery(nowMs)
                }
                latestRouteOrientation = latestRouteOrientation?.let {
                    it.copy(accuracy = it.accuracy.downgradedTo(accuracy))
                }
                if (accuracy == EarthOrientationAccuracy.UNRELIABLE) {
                    unavailableReason = RouteCompassHeadingReason.QUALITY_LOW
                }
            }
            RouteCompassStream.GRAVITY -> latestGravity = latestGravity?.downgradedTo(accuracy)
            RouteCompassStream.ACCELEROMETER -> accelerometerWindow.downgradeAll(accuracy)
            RouteCompassStream.MAGNETIC_FIELD -> magneticHistory.downgradeAll(accuracy)
        }
    }

    /** True when the registered inputs stopped (or never started) delivering events. */
    fun isStalled(nowMs: Long): Boolean {
        if (!started) return false
        fun alive(stream: RouteCompassStream): Boolean {
            if (stream !in registeredStreams) return false
            val last = lastReceiptAtMs[stream]
            val lastIsCurrent = last != null && nowMs >= last && nowMs - last <= watchdog.maximumEventGapMs
            return lastIsCurrent || (nowMs >= receiptGraceStartedAtMs &&
                nowMs - receiptGraceStartedAtMs <= watchdog.firstEventDeadlineMs)
        }
        val rotation = alive(RouteCompassStream.ROTATION_VECTOR)
        val fallback = alive(RouteCompassStream.MAGNETIC_FIELD) &&
            (alive(RouteCompassStream.GRAVITY) || alive(RouteCompassStream.ACCELEROMETER))
        return !rotation && !fallback
    }

    fun reregistrationDue(nowMs: Long): Boolean = isStalled(nowMs) &&
        reregistrationCount < watchdog.maximumReregistrationsPerStart &&
        lastReregistrationAtMs?.let { nowMs < it || nowMs - it >= watchdog.minimumReregistrationIntervalMs } != false

    fun evaluate(nowMs: Long, reference: ChestMountedGeomagneticReference?): RouteCompassHeadingResult {
        val result = evaluateCurrentCompass(nowMs, reference)
        val observation = result.observation
        if (observation == null) {
            headingTrend.reset()
            return result
        }
        val estimate = headingTrend.observe(observation, nowMs)
        return result.copy(observation = observation.copy(headingTrend = estimate))
    }

    private fun evaluateCurrentCompass(nowMs: Long, reference: ChestMountedGeomagneticReference?): RouteCompassHeadingResult {
        if (!started) return RouteCompassHeadingResult(null, unavailableReason)
        if (isStalled(nowMs)) return RouteCompassHeadingResult(null, RouteCompassHeadingReason.SENSOR_STALLED)
        if (rotationQualityRequiresFreshSample) {
            // A fresh bad callback/vector still vetoes fallback. A stopped RV stream must
            // not veto independently fresh gravity/magnetic samples forever. Never reuse
            // the rejected RV value, or promote fallback into the strict AR/PDR inputs.
            val rejectedAt = rotationQualityRejectedAtMs
                ?: return RouteCompassHeadingResult(null, RouteCompassHeadingReason.QUALITY_LOW)
            val evidenceAt = maxOf(rejectedAt, latestRotationEventAtMs ?: rejectedAt)
            if (nowMs < 0L || evidenceAt < 0L || evidenceAt > nowMs) {
                return RouteCompassHeadingResult(null, RouteCompassHeadingReason.INVALID_TIMESTAMP)
            }
            if (nowMs - evidenceAt <= FRESHNESS_MS) return independentlyQualifiedLowRvFallback(nowMs, reference)
            return fallback(nowMs, reference)
        }
        val orientation = latestRouteOrientation
        val primary = if (orientation != null) {
            RouteCompassHeading.crossCheckMagneticField(
                RouteCompassHeading.evaluate(
                    orientation, reference?.declinationDegrees, nowMs, rearFacingRateDegreesPerSecond,
                    forwardAxisForInstrumentation,
                ),
                latestMagneticMeasurement(), reference, nowMs,
            )
        } else {
            val at = latestRotationEventAtMs
            val reason = when {
                at != null && (nowMs < 0L || at < 0L || at > nowMs) -> RouteCompassHeadingReason.INVALID_TIMESTAMP
                at != null && nowMs - at > FRESHNESS_MS -> RouteCompassHeadingReason.STALE
                else -> unavailableReason
            }
            RouteCompassHeadingResult(null, reason)
        }
        // Only a fresh, fully usable primary can end a poor-RV episode. A quality callback,
        // timeout, invalid matrix/error, or suspect magnetic cross-check cannot do so.
        if (primary.observation?.magneticFieldSuspect == false) fallbackRecoveryEpisode = null
        if (primary.reason == RouteCompassHeadingReason.QUALITY_LOW)
            return independentlyQualifiedLowRvFallback(nowMs, reference)
        if (!RouteCompassHeading.permitsFallback(primary)) return primary
        return fallback(nowMs, reference)
    }

    private fun independentlyQualifiedLowRvFallback(nowMs: Long,
        reference: ChestMountedGeomagneticReference?,
    ): RouteCompassHeadingResult {
        // A different qualified source can support a nominal route clock while RV quality is
        // low. Explicit invalid matrices/numeric RV errors still cannot be bypassed when current.
        latestRouteOrientation?.takeIf {
            nowMs >= it.observedAtElapsedRealtimeMs && nowMs - it.observedAtElapsedRealtimeMs <= FRESHNESS_MS
        }?.let {
            if (!it.deviceToMagneticEnu.isOrthonormal())
                return RouteCompassHeadingResult(null, RouteCompassHeadingReason.INVALID_ROTATION)
            val error = it.headingErrorDeg.toDouble()
            if ((!error.isFinite() && !error.isNaN()) || error > 30.0)
                return RouteCompassHeadingResult(null, RouteCompassHeadingReason.HEADING_ACCURACY)
        }
        return fallback(nowMs, reference)
    }

    private fun fallback(nowMs: Long, reference: ChestMountedGeomagneticReference?): RouteCompassHeadingResult {
        // Magnetic-vector change is a lower bound for device rotation (it misses rotation about
        // the field axis); a fresh RV rate, when present, is also included.
        val rates = listOfNotNull(
            magneticRateDegreesPerSecond?.takeIf { magneticRateAtMs?.let { nowMs >= it && nowMs - it <= FRESHNESS_MS } == true },
            rotationRateDegreesPerSecond?.takeIf {
                latestRotationEventAtMs?.let { nowMs >= it && nowMs - it <= FRESHNESS_MS } == true
            },
        )
        val result = RouteCompassHeading.evaluateFallback(
            latestGravity, accelerometerWindow.toList(), magneticHistory.toList(), reference, nowMs, rates.maxOrNull(),
            forwardAxisForInstrumentation,
        )
        val episode = fallbackRecoveryEpisode ?: return result
        val observation = result.observation ?: return result
        return result.copy(observation = observation.copy(fallbackRecoveryEpisode = episode))
    }

    private fun beginFallbackRecovery(receivedAtMs: Long) {
        if (receivedAtMs < 0L) return
        val episode = fallbackRecoveryEpisode
        if (episode == null) {
            recoveryEpisodeSequence += 1L
            fallbackRecoveryEpisode = RouteCompassRecoveryEpisode(recoveryEpisodeSequence, receivedAtMs)
        } else {
            fallbackRecoveryEpisode = episode.copy(lastRejectedAtElapsedRealtimeMs = receivedAtMs)
        }
    }

    /** Latest admitted original measurement; same-ms SDK samples retain their original ns ordering. */
    internal fun latestMagneticMeasurement(): RouteCompassVectorSample? {
        val latestAtMs = magneticHistory.maxOfOrNull { it.observedAtMs } ?: return null
        return magneticHistory.lastOrNull { it.observedAtMs == latestAtMs }
    }

    private fun updateMagneticRate(sample: RouteCompassVectorSample) {
        val baseline = magneticHistory.lastOrNull {
            sample.observedAtMs - it.observedAtMs >= MINIMUM_MAGNETIC_RATE_BASELINE_MS
        }
        val intervalMs = baseline?.let { sample.observedAtMs - it.observedAtMs }
        magneticRateDegreesPerSecond = if (baseline != null && intervalMs != null && intervalMs <= MAXIMUM_MAGNETIC_RATE_BASELINE_MS) {
            vectorAngleDegrees(baseline, sample)?.let { it * 1_000.0 / intervalMs }
        } else null
        magneticRateAtMs = sample.observedAtMs
    }

    private fun accepts(eventGeneration: Int) = started && eventGeneration == generation

    private fun ownsConsumingCallback(eventGeneration: Int, stream: RouteCompassStream): Boolean =
        consumingCallback?.let { it.generation == eventGeneration && accepts(it.generation) && it.stream == stream } != false

    /** Original raw identity distinguishes SDK samples that share a displayed millisecond. */
    private fun admitMeasurementTime(eventGeneration: Int, stream: RouteCompassStream, observedAtMs: Long,
        originalTimestampNs: Long? = null,
    ): Boolean {
        val callback = consumingCallback
        if (callback != null && (callback.generation != eventGeneration || !accepts(callback.generation) ||
                callback.stream != stream || callback.timestampNs / 1_000_000L != observedAtMs ||
                newestCallbackTimestampNs[stream] != callback.timestampNs ||
                (originalTimestampNs != null && originalTimestampNs != callback.timestampNs))
        ) return false
        val exactNs = originalTimestampNs ?: callback?.timestampNs
        val rawHighwater = newestCallbackTimestampNs[stream]
        if (callback == null && rawHighwater != null &&
            (observedAtMs < rawHighwater / 1_000_000L ||
                (observedAtMs == rawHighwater / 1_000_000L && (exactNs == null || exactNs <= rawHighwater)))
        ) return false
        val previous = newestMeasurementTime[stream]
        if (previous != null && (observedAtMs < previous.observedAtMs ||
                (observedAtMs == previous.observedAtMs &&
                    (exactNs == null || (previous.originalTimestampNs == null && callback == null) ||
                        previous.originalTimestampNs?.let { exactNs <= it } == true)))
        ) return false
        newestMeasurementTime[stream] = SensorMeasurementTime(observedAtMs, exactNs)
        return true
    }

    private data class SensorMeasurementTime(val observedAtMs: Long, val originalTimestampNs: Long?)
    private data class AdmittedSensorCallback(val generation: Int, val stream: RouteCompassStream, val timestampNs: Long)

    private fun receipt(stream: RouteCompassStream, nowMs: Long) {
        firstReceiptAtMs.putIfAbsent(stream, nowMs)
        lastReceiptAtMs[stream] = nowMs
    }

    private fun ArrayDeque<RouteCompassVectorSample>.append(sample: RouteCompassVectorSample) {
        addLast(sample)
        while (size > MAXIMUM_BUFFERED_SAMPLES ||
            (isNotEmpty() && sample.observedAtMs - first().observedAtMs > BUFFER_RETENTION_MS)
        ) removeFirst()
    }

    private fun ArrayDeque<RouteCompassVectorSample>.downgradeAll(accuracy: EarthOrientationAccuracy) {
        val downgraded = map { it.downgradedTo(accuracy) }
        clear()
        addAll(downgraded)
    }

    private fun clearSamples() {
        headingTrend.reset()
        latestRouteOrientation = null
        latestRotationEventAtMs = null
        rotationAccuracyStatus = null
        rotationQualityRequiresFreshSample = false
        rotationQualityRejectedAtMs = null
        fallbackRecoveryEpisode = null
        previousRotation = null
        rotationRateDegreesPerSecond = null
        rearFacingRateDegreesPerSecond = null
        magneticRateDegreesPerSecond = null
        magneticRateAtMs = null
        latestGravity = null
        accelerometerWindow.clear()
        magneticHistory.clear()
        lastReceiptAtMs.clear()
        firstReceiptAtMs.clear()
        newestCallbackTimestampNs.clear()
        newestMeasurementTime.clear()
    }

    private fun RouteCompassVectorSample.downgradedTo(reported: EarthOrientationAccuracy) =
        copy(accuracy = accuracy.downgradedTo(reported))

    private fun EarthOrientationAccuracy.downgradedTo(reported: EarthOrientationAccuracy) =
        if (reported < this) reported else this

    companion object {
        private const val FRESHNESS_MS = 500L
        private const val MAXIMUM_RATE_INTERVAL_MS = 200L
        private const val MINIMUM_MAGNETIC_RATE_BASELINE_MS = 100L
        private const val MAXIMUM_MAGNETIC_RATE_BASELINE_MS = 300L
        private const val BUFFER_RETENTION_MS = 500L
        private const val MAXIMUM_BUFFERED_SAMPLES = 64

        private fun horizontalFacingChangeDegrees(a: RotationMatrix3, b: RotationMatrix3,
            forwardAxis: RouteCompassForwardAxis,
        ): Double? {
            if (!a.isOrthonormal() || !b.isOrthonormal()) return null
            val aEast = forwardAxis.east(a)
            val aNorth = forwardAxis.north(a)
            val bEast = forwardAxis.east(b)
            val bNorth = forwardAxis.north(b)
            // Match the rear-axis horizontal projection gate; retain the conservative 3D
            // allowance when either end cannot support an azimuth measurement.
            if (sqrt(aEast * aEast + aNorth * aNorth) < 0.3 ||
                sqrt(bEast * bEast + bNorth * bNorth) < 0.3
            ) return null
            return abs(Math.toDegrees(atan2(
                aNorth * bEast - aEast * bNorth,
                aEast * bEast + aNorth * bNorth,
            )))
        }

        /** Angle of the relative rotation between two device-to-world rotations. */
        fun rotationAngleDegrees(a: RotationMatrix3, b: RotationMatrix3): Double {
            // trace(Aᵀ B) = Σ a_ij b_ij for row-major matrices.
            val trace = a.m00.toDouble() * b.m00 + a.m01.toDouble() * b.m01 + a.m02.toDouble() * b.m02 +
                a.m10.toDouble() * b.m10 + a.m11.toDouble() * b.m11 + a.m12.toDouble() * b.m12 +
                a.m20.toDouble() * b.m20 + a.m21.toDouble() * b.m21 + a.m22.toDouble() * b.m22
            return Math.toDegrees(acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
        }

        fun vectorAngleDegrees(a: RouteCompassVectorSample, b: RouteCompassVectorSample): Double? {
            val na = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
            val nb = sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
            if (!na.isFinite() || !nb.isFinite() || na <= 0.0 || nb <= 0.0) return null
            val cosine = (a.x * b.x + a.y * b.y + a.z * b.z) / (na * nb)
            return Math.toDegrees(acos(cosine.coerceIn(-1.0, 1.0)))
        }
    }
}

/**
 * A geomagnetic reference with its provenance. It is recomputed from each trusted location, may
 * survive a short stop/restart in the same area, and expires after a long time without refresh.
 */
data class GeomagneticReferenceRecord(
    val reference: ChestMountedGeomagneticReference,
    val createdAtElapsedRealtimeMs: Long,
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val modelTimeMillis: Long,
) {
    fun usableAt(nowElapsedRealtimeMs: Long): ChestMountedGeomagneticReference? = reference.takeIf {
        nowElapsedRealtimeMs >= createdAtElapsedRealtimeMs &&
            nowElapsedRealtimeMs - createdAtElapsedRealtimeMs <= MAXIMUM_AGE_MS
    }

    companion object {
        // Declination changes slowly with distance; this bounds use after trusted fixes stopped
        // (for example after travelling by vehicle), not ordinary walking pauses.
        const val MAXIMUM_AGE_MS = 6L * 60L * 60L * 1_000L
    }
}
