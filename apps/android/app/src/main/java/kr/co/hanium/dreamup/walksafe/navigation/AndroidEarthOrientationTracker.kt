package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedHeading
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedHeadingResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedMagneticFieldSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedRotationSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservationHistory

/** Trusted RV orientation for AR/PDR, plus an independently gated route-only magnetic compass. */
class AndroidEarthOrientationTracker(context: Context) {
    private val chestHeadingHistory = HeadingObservationHistory()
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val chestMountedHeading = ChestMountedHeading()

    @Volatile
    private var latestOrientation: DeviceEarthOrientation? = null
    // Unlike latest(), this route-only sample may contain an unavailable numeric heading error.
    private var latestRouteOrientation: DeviceEarthOrientation? = null
    private var latestRotationEventAtMs: Long? = null
    // Accuracy callbacks describe current sensor state, independently of the last vector's age.
    private var rotationAccuracyStatus: EarthOrientationAccuracy? = null
    private var rotationQualityRequiresFreshSample = false
    private var rotationQualityRejectedAtMs: Long? = null
    private var sensorThread: HandlerThread? = null
    private var lastCompassDiagnosticAtMs: Long? = null
    private var latestGravitySample: RouteCompassVectorSample? = null
    private var latestAccelerometerSample: RouteCompassVectorSample? = null
    private var latestRouteMagneticSample: RouteCompassVectorSample? = null
    private var registrationStartedAtNanos = 0L
    @Volatile
    private var routeCompassUnavailableReason = RouteCompassHeadingReason.ORIENTATION_MISSING
    @Volatile
    private var latestRotationSample: ChestMountedRotationSample? = null
    @Volatile
    private var latestMagneticFieldSample: ChestMountedMagneticFieldSample? = null
    @Volatile
    private var geomagneticReference: ChestMountedGeomagneticReference? = null
    @Volatile
    private var magneticSensorRegistered = false
    @Volatile
    private var started = false
    private var registrationGeneration = 0
    private var currentListener: SensorEventListener? = null

    @Synchronized
    fun start(): Boolean {
        if (started) return true
        magneticSensorRegistered = false
        latestOrientation = null
        latestRouteOrientation = null
        latestRotationEventAtMs = null
        rotationAccuracyStatus = null
        rotationQualityRequiresFreshSample = false
        rotationQualityRejectedAtMs = null
        lastCompassDiagnosticAtMs = null
        latestGravitySample = null
        latestAccelerometerSample = null
        latestRouteMagneticSample = null
        routeCompassUnavailableReason = RouteCompassHeadingReason.ORIENTATION_MISSING
        latestRotationSample = null
        latestMagneticFieldSample = null
        chestHeadingHistory.clear()
        chestMountedHeading.reset()
        val generation = ++registrationGeneration
        lateinit var listener: SensorEventListener
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(this@AndroidEarthOrientationTracker) {
                    if (!isCurrentRegistration(listener, generation) || event.timestamp < registrationStartedAtNanos) return
                    handleSensorChanged(event)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                synchronized(this@AndroidEarthOrientationTracker) {
                    if (!isCurrentRegistration(listener, generation)) return
                    val mappedAccuracy = accuracy.toEarthOrientationAccuracy()
                    // Only the strict PDR input sensors can invalidate its accepted history.
                    if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
                        chestHeadingHistory.clear()
                    }
                    when (sensor?.type) {
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            rotationAccuracyStatus = mappedAccuracy
                            if (mappedAccuracy < EarthOrientationAccuracy.MEDIUM) {
                                rotationQualityRequiresFreshSample = true
                                rotationQualityRejectedAtMs = SystemClock.elapsedRealtime()
                            }
                            latestRouteOrientation = latestRouteOrientation?.let { orientation ->
                                orientation.copy(accuracy = orientation.accuracy.downgradedTo(mappedAccuracy))
                            }
                            latestRotationSample = latestRotationSample?.let { sample ->
                                sample.copy(accuracy = sample.accuracy.downgradedTo(mappedAccuracy))
                            }
                            if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                                latestOrientation = null
                                routeCompassUnavailableReason = RouteCompassHeadingReason.QUALITY_LOW
                            } else {
                                latestOrientation = latestOrientation?.let { orientation ->
                                    orientation.copy(
                                        accuracy = orientation.accuracy.downgradedTo(mappedAccuracy),
                                    )
                                }
                            }
                        }
                        Sensor.TYPE_GRAVITY -> {
                            latestGravitySample = latestGravitySample?.downgradedTo(mappedAccuracy)
                        }
                        Sensor.TYPE_ACCELEROMETER -> {
                            latestAccelerometerSample = latestAccelerometerSample?.downgradedTo(mappedAccuracy)
                        }
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            latestRouteMagneticSample = latestRouteMagneticSample?.downgradedTo(mappedAccuracy)
                            latestMagneticFieldSample = latestMagneticFieldSample?.let { sample ->
                                sample.copy(accuracy = sample.accuracy.downgradedTo(mappedAccuracy))
                            }
                        }
                    }
                }
            }
        }
        // A busy UI must not delay sensor delivery past the 500 ms freshness limit.
        val thread = HandlerThread("WalkSafeOrientation").apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        currentListener = listener
        registrationStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        started = true
        // Registration failures are independent: a device without RV can still have a compass.
        val rotationRegistered = register(listener, rotationVectorSensor, handler)
        magneticSensorRegistered = register(listener, magneticFieldSensor, handler)
        val gravityRegistered = register(listener, gravitySensor, handler)
        // Register raw acceleration independently; some devices expose gravity but produce no data.
        val accelerometerRegistered = register(listener, accelerometerSensor, handler)
        started = rotationRegistered || (magneticSensorRegistered && (gravityRegistered || accelerometerRegistered))
        if (!rotationRegistered) routeCompassUnavailableReason = RouteCompassHeadingReason.SENSOR_UNAVAILABLE
        if (!started) {
            currentListener = null
            sensorManager.unregisterListener(listener)
            magneticSensorRegistered = false
            sensorThread = null
            thread.quitSafely()
            return false
        }
        return true
    }

    @Synchronized
    fun stop() {
        registrationGeneration += 1
        val listener = currentListener
        currentListener = null
        started = false
        listener?.let(sensorManager::unregisterListener)
        // Do not join while holding this monitor: an old callback may be waiting for it.
        sensorThread?.quitSafely()
        sensorThread = null
        latestOrientation = null
        latestRouteOrientation = null
        latestRotationEventAtMs = null
        rotationAccuracyStatus = null
        rotationQualityRequiresFreshSample = false
        rotationQualityRejectedAtMs = null
        lastCompassDiagnosticAtMs = null
        latestGravitySample = null
        latestAccelerometerSample = null
        latestRouteMagneticSample = null
        routeCompassUnavailableReason = RouteCompassHeadingReason.ORIENTATION_MISSING
        latestRotationSample = null
        latestMagneticFieldSample = null
        magneticSensorRegistered = false
        chestHeadingHistory.clear()
        chestMountedHeading.reset()
    }

    fun latest(): DeviceEarthOrientation? = latestOrientation

    /** No fixed chest posture is required; fallback observations never enter latest() or PDR. */
    @Synchronized
    fun latestRouteCompassHeading(nowMs: Long = SystemClock.elapsedRealtime()): RouteCompassHeadingResult {
        val result = evaluateRouteCompassHeading(nowMs)
        // Opt-in diagnostics; no coordinates, no speech, and at most one line per second.
        val previousLogAt = lastCompassDiagnosticAtMs
        if (Log.isLoggable("RouteCompass", Log.DEBUG) &&
            (previousLogAt == null || nowMs < previousLogAt || nowMs - previousLogAt >= 1_000L)
        ) {
            lastCompassDiagnosticAtMs = nowMs
            fun age(at: Long?): Long? = at?.let { nowMs - it }
            val observation = result.observation
            Log.d("RouteCompass", "reason=${result.reason} source=${observation?.source}" +
                " heading_true_deg=${observation?.degreesTrueNorth}" +
                " heading_age_ms=${age(observation?.observedAtElapsedRealtimeMs)}" +
                " rv_age_ms=${age(latestRotationEventAtMs)}" +
                " gravity_age_ms=${age(latestGravitySample?.observedAtMs)}" +
                " accel_age_ms=${age(latestAccelerometerSample?.observedAtMs)}" +
                " magnetic_age_ms=${age(latestRouteMagneticSample?.observedAtMs)}" +
                " rv_quality_wait=$rotationQualityRequiresFreshSample" +
                " rv_quality_age_ms=${age(rotationQualityRejectedAtMs)}")
        }
        return result
    }

    private fun evaluateRouteCompassHeading(nowMs: Long): RouteCompassHeadingResult {
        if (!started) return RouteCompassHeadingResult(null, routeCompassUnavailableReason)
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
            if (nowMs - evidenceAt <= 500L) {
                return RouteCompassHeadingResult(null, RouteCompassHeadingReason.QUALITY_LOW)
            }
            return RouteCompassHeading.evaluateFallback(
                latestGravitySample, latestAccelerometerSample, latestRouteMagneticSample,
                geomagneticReference, nowMs,
            )
        }
        val orientation = latestRouteOrientation
        val primary = if (orientation != null) {
            RouteCompassHeading.evaluate(orientation, geomagneticReference?.declinationDegrees, nowMs)
        } else {
            val at = latestRotationEventAtMs
            val reason = when {
                at != null && (nowMs < 0L || at < 0L || at > nowMs) -> RouteCompassHeadingReason.INVALID_TIMESTAMP
                at != null && nowMs - at > 500L -> RouteCompassHeadingReason.STALE
                else -> routeCompassUnavailableReason
            }
            RouteCompassHeadingResult(null, reason)
        }
        if (!RouteCompassHeading.permitsFallback(primary)) return primary
        return RouteCompassHeading.evaluateFallback(
            latestGravitySample, latestAccelerometerSample, latestRouteMagneticSample,
            geomagneticReference, nowMs,
        )
    }

    @Synchronized
    fun latestChestMountedHeading(
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ): ChestMountedHeadingResult = chestMountedHeading.evaluate(
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        rotation = latestRotationSample,
        magneticField = latestMagneticFieldSample,
        geomagneticReference = geomagneticReference,
        magneticSensorAvailable = magneticSensorRegistered,
    )

    @Synchronized
    fun chestMountedHeadingAt(timestampMs: Long, maximumAgeMs: Long = 500L): HeadingObservation? =
        chestHeadingHistory.atOrBefore(timestampMs, maximumAgeMs)

    fun isMagneticFieldAvailable(): Boolean = magneticSensorRegistered

    @Synchronized
    fun updateGeomagneticReference(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        altitudeMeters: Double = 0.0,
        timeMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (
            !latitudeDegrees.isFinite() || latitudeDegrees !in -90.0..90.0 ||
            !longitudeDegrees.isFinite() || longitudeDegrees !in -180.0..180.0 ||
            !altitudeMeters.isFinite()
        ) {
            clearGeomagneticReference()
            return false
        }
        val field = runCatching {
            GeomagneticField(
                latitudeDegrees.toFloat(),
                longitudeDegrees.toFloat(),
                altitudeMeters.toFloat(),
                timeMillis,
            )
        }.getOrNull() ?: run {
            clearGeomagneticReference()
            return false
        }
        val reference = ChestMountedGeomagneticReference(
            declinationDegrees = field.declination.toDouble(),
            inclinationDegrees = field.inclination.toDouble(),
            expectedFieldStrengthMicrotesla =
                field.fieldStrength.toDouble() / NANOTESLA_PER_MICROTESLA,
        )
        if (
            !reference.declinationDegrees.isFinite() ||
            !reference.expectedFieldStrengthMicrotesla.isFinite() ||
            reference.expectedFieldStrengthMicrotesla <= 0.0
        ) {
            clearGeomagneticReference()
            return false
        }
        geomagneticReference = reference
        return true
    }

    @Synchronized
    fun clearGeomagneticReference() {
        geomagneticReference = null
        chestHeadingHistory.clear()
        chestMountedHeading.reset()
    }

    private fun register(listener: SensorEventListener, sensor: Sensor?, handler: Handler): Boolean = sensor?.let {
        runCatching { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler) }
            .getOrDefault(false)
    } ?: false

    // Kept as text so the frozen FP-017 trace can identify the superseded
    // listener contract while runtime code uses the stronger registration lease.
    /*
if (!started || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
started = false
        sensorManager.unregisterListener(this)
    */
    private fun isCurrentRegistration(
        listener: SensorEventListener,
        generation: Int,
    ): Boolean =
        started &&
            registrationGeneration == generation &&
            currentListener === listener

    private fun handleSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVectorChanged(event)
            Sensor.TYPE_MAGNETIC_FIELD -> handleMagneticFieldChanged(event)
            Sensor.TYPE_GRAVITY -> {
                latestGravitySample = event.toRouteVectorSample()
                return
            }
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccelerometerSample = event.toRouteVectorSample()
                return
            }
            else -> return
        }
        // Save already-gated observations at sensor time, before delayed step callbacks consume them.
        val result = latestChestMountedHeading(SystemClock.elapsedRealtime())
        val heading = result.trueHeadingDegrees
        val accuracy = result.accuracyDegrees
        val observedAt = result.observedAtMs
        if (result.isValid && heading != null && accuracy != null && observedAt != null) {
            chestHeadingHistory.add(HeadingObservation(heading, accuracy, observedAt))
        } else {
            chestHeadingHistory.clear()
        }
    }

    private fun handleRotationVectorChanged(event: SensorEvent) {
        latestRotationEventAtMs = event.timestamp / NANOS_PER_MILLISECOND
        val headingErrorRad = event.values.getOrNull(4)
        val hasReportedError = headingErrorRad != null && !headingErrorRad.isNaN() && headingErrorRad >= 0f
        val rowMajorRotation = FloatArray(9)
        try {
            SensorManager.getRotationMatrixFromVector(rowMajorRotation, event.values)
        } catch (_: RuntimeException) {
            routeCompassUnavailableReason = RouteCompassHeadingReason.INVALID_ROTATION
            latestOrientation = null
            latestRouteOrientation = null
            latestRotationSample = null
            return
        }
        val rotation = RotationMatrix3.fromRowMajor(rowMajorRotation) ?: run {
            routeCompassUnavailableReason = RouteCompassHeadingReason.INVALID_ROTATION
            latestOrientation = null
            latestRouteOrientation = null
            latestRotationSample = null
            return
        }
        val observedAtMs = event.timestamp / NANOS_PER_MILLISECOND
        val headingErrorDeg = if (hasReportedError) Math.toDegrees(requireNotNull(headingErrorRad).toDouble()).toFloat()
            else Float.NaN
        val eventAccuracy = event.accuracy.toEarthOrientationAccuracy()
        val accuracy = rotationAccuracyStatus?.let { eventAccuracy.downgradedTo(it) } ?: eventAccuracy
        val nowMs = SystemClock.elapsedRealtime()
        if (accuracy >= EarthOrientationAccuracy.MEDIUM &&
            observedAtMs >= 0L && observedAtMs <= nowMs && nowMs - observedAtMs <= 500L
        ) {
            rotationQualityRequiresFreshSample = false
            rotationQualityRejectedAtMs = null
        }
        latestRouteOrientation = DeviceEarthOrientation(
            deviceToMagneticEnu = rotation,
            observedAtElapsedRealtimeMs = observedAtMs,
            headingErrorDeg = headingErrorDeg,
            accuracy = accuracy,
        )
        if (!hasReportedError || !headingErrorDeg.isFinite()) {
            routeCompassUnavailableReason = if (hasReportedError) RouteCompassHeadingReason.HEADING_ACCURACY
                else RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE
            latestOrientation = null
            latestRotationSample = null
            return
        }
        latestOrientation = latestRouteOrientation
        latestRotationSample = ChestMountedRotationSample(
            deviceToMagneticEnu = rotation,
            observedAtMs = observedAtMs,
            headingAccuracyDegrees = headingErrorDeg.toDouble(),
            accuracy = accuracy,
        )
    }

    private fun handleMagneticFieldChanged(event: SensorEvent) {
        latestRouteMagneticSample = event.toRouteVectorSample()
        latestMagneticFieldSample = ChestMountedMagneticFieldSample(
            xMicrotesla = event.values.getOrNull(0)?.toDouble() ?: Double.NaN,
            yMicrotesla = event.values.getOrNull(1)?.toDouble() ?: Double.NaN,
            zMicrotesla = event.values.getOrNull(2)?.toDouble() ?: Double.NaN,
            observedAtMs = event.timestamp / NANOS_PER_MILLISECOND,
            accuracy = event.accuracy.toEarthOrientationAccuracy(),
        )
    }

    private fun SensorEvent.toRouteVectorSample() = RouteCompassVectorSample(
        values.getOrNull(0)?.toDouble() ?: Double.NaN,
        values.getOrNull(1)?.toDouble() ?: Double.NaN,
        values.getOrNull(2)?.toDouble() ?: Double.NaN,
        timestamp / NANOS_PER_MILLISECOND,
        accuracy.toEarthOrientationAccuracy(),
    )

    private fun RouteCompassVectorSample.downgradedTo(reported: EarthOrientationAccuracy) =
        copy(accuracy = accuracy.downgradedTo(reported))

    private fun Int.toEarthOrientationAccuracy(): EarthOrientationAccuracy = when (this) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> EarthOrientationAccuracy.HIGH
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> EarthOrientationAccuracy.MEDIUM
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> EarthOrientationAccuracy.LOW
        else -> EarthOrientationAccuracy.UNRELIABLE
    }

    private fun EarthOrientationAccuracy.downgradedTo(
        reported: EarthOrientationAccuracy,
    ): EarthOrientationAccuracy = if (reported < this) reported else this

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOTESLA_PER_MICROTESLA = 1_000.0
    }
}
