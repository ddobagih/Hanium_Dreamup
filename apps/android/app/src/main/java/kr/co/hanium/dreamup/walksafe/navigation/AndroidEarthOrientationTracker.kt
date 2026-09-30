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
    // Route-only compass state (it may hold an RV sample without a numeric heading error). The
    // pure state machine owns freshness, accuracy recovery, fallback, rates and the watchdog.
    private val routeCompassState = RouteCompassSensorState()
    // Accuracy callbacks describe current sensor state, independently of the last vector's age.
    private var rotationAccuracyStatus: EarthOrientationAccuracy? = null
    private var sensorThread: HandlerThread? = null
    private var lastCompassDiagnosticAtMs: Long? = null
    private var registrationStartedAtNanos = 0L
    private var registeredRouteStreams: Set<RouteCompassStream> = emptySet()
    @Volatile
    private var notStartedRouteReason = RouteCompassHeadingReason.ORIENTATION_MISSING
    @Volatile
    private var latestRotationSample: ChestMountedRotationSample? = null
    @Volatile
    private var latestMagneticFieldSample: ChestMountedMagneticFieldSample? = null
    @Volatile
    private var geomagneticReferenceRecord: GeomagneticReferenceRecord? = null
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
        rotationAccuracyStatus = null
        lastCompassDiagnosticAtMs = null
        routeCompassState.stop()
        latestRotationSample = null
        latestMagneticFieldSample = null
        chestHeadingHistory.clear()
        chestMountedHeading.reset()
        val generation = ++registrationGeneration
        val listener = createListener(generation)
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
        // Without RV the strict AR/PDR orientation stays absent; only the route fallback may work.
        notStartedRouteReason = if (!rotationRegistered) RouteCompassHeadingReason.SENSOR_UNAVAILABLE
            else RouteCompassHeadingReason.ORIENTATION_MISSING
        if (!started) {
            currentListener = null
            sensorManager.unregisterListener(listener)
            magneticSensorRegistered = false
            sensorThread = null
            thread.quitSafely()
            return false
        }
        registeredRouteStreams = routeStreams(rotationRegistered, magneticSensorRegistered, gravityRegistered, accelerometerRegistered)
        // Registration success is not delivery; the route state watches first/last receipt.
        routeCompassState.start(generation, SystemClock.elapsedRealtime(), registeredRouteStreams)
        return true
    }

    private fun routeStreams(rotation: Boolean, magnetic: Boolean, gravity: Boolean, accelerometer: Boolean) =
        buildSet {
            if (rotation) add(RouteCompassStream.ROTATION_VECTOR)
            if (magnetic) add(RouteCompassStream.MAGNETIC_FIELD)
            if (gravity) add(RouteCompassStream.GRAVITY)
            if (accelerometer) add(RouteCompassStream.ACCELEROMETER)
        }

    /** Limited watchdog recovery on the existing sensor thread; old callbacks are fenced off. */
    private fun reregisterStalledSensors(nowMs: Long) {
        val thread = sensorThread ?: return
        val oldListener = currentListener ?: return
        sensorManager.unregisterListener(oldListener)
        val generation = ++registrationGeneration
        val listener = createListener(generation)
        val handler = Handler(thread.looper)
        currentListener = listener
        registrationStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val rotationRegistered = register(listener, rotationVectorSensor, handler)
        magneticSensorRegistered = register(listener, magneticFieldSensor, handler)
        val gravityRegistered = register(listener, gravitySensor, handler)
        val accelerometerRegistered = register(listener, accelerometerSensor, handler)
        registeredRouteStreams = routeStreams(rotationRegistered, magneticSensorRegistered, gravityRegistered, accelerometerRegistered)
        routeCompassState.onReregistered(generation, nowMs, registeredRouteStreams)
    }

    private fun createListener(generation: Int): SensorEventListener {
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
                    sensor?.type?.toRouteStream()?.let { stream ->
                        routeCompassState.onAccuracyChanged(generation, stream, mappedAccuracy, SystemClock.elapsedRealtime())
                    }
                    when (sensor?.type) {
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            rotationAccuracyStatus = mappedAccuracy
                            latestRotationSample = latestRotationSample?.let { sample ->
                                sample.copy(accuracy = sample.accuracy.downgradedTo(mappedAccuracy))
                            }
                            if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                                latestOrientation = null
                            } else {
                                latestOrientation = latestOrientation?.let { orientation ->
                                    orientation.copy(
                                        accuracy = orientation.accuracy.downgradedTo(mappedAccuracy),
                                    )
                                }
                            }
                        }
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            latestMagneticFieldSample = latestMagneticFieldSample?.let { sample ->
                                sample.copy(accuracy = sample.accuracy.downgradedTo(mappedAccuracy))
                            }
                        }
                    }
                }
            }
        }
        return listener
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
        rotationAccuracyStatus = null
        lastCompassDiagnosticAtMs = null
        routeCompassState.stop()
        registeredRouteStreams = emptySet()
        notStartedRouteReason = RouteCompassHeadingReason.ORIENTATION_MISSING
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
        if (started && routeCompassState.reregistrationDue(nowMs)) reregisterStalledSensors(nowMs)
        val result = if (started) {
            routeCompassState.evaluate(nowMs, geomagneticReferenceRecord?.usableAt(nowMs))
        } else {
            RouteCompassHeadingResult(null, notStartedRouteReason)
        }
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
                " rv_age_ms=${age(routeCompassState.latestRotationEventAt())}" +
                " gravity_age_ms=${age(routeCompassState.latestGravityAt())}" +
                " accel_age_ms=${age(routeCompassState.latestAccelerometerAt())}" +
                " magnetic_age_ms=${age(routeCompassState.latestMagneticAt())}" +
                " rv_quality_wait=${routeCompassState.rotationQualityWait()}" +
                " rv_quality_age_ms=${age(routeCompassState.rotationQualityRejectedAt())}" +
                " rate_deg_s=${observation?.angularRateDegreesPerSecond}" +
                " magnetic_suspect=${observation?.magneticFieldSuspect}" +
                " quality=${observation?.sensorQuality}" +
                " reregistrations=${routeCompassState.reregistrations}")
        }
        return result
    }

    @Synchronized
    fun latestChestMountedHeading(
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ): ChestMountedHeadingResult = chestMountedHeading.evaluate(
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        rotation = latestRotationSample,
        magneticField = latestMagneticFieldSample,
        geomagneticReference = geomagneticReferenceRecord?.usableAt(nowElapsedRealtimeMs),
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
        // Keep provenance so a reference without refresh for a long time is not used indefinitely.
        geomagneticReferenceRecord = GeomagneticReferenceRecord(
            reference, SystemClock.elapsedRealtime(), latitudeDegrees, longitudeDegrees, timeMillis,
        )
        return true
    }

    @Synchronized
    fun clearGeomagneticReference() {
        geomagneticReferenceRecord = null
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
                routeCompassState.onVector(
                    registrationGeneration, RouteCompassStream.GRAVITY, event.toRouteVectorSample(), SystemClock.elapsedRealtime(),
                )
                return
            }
            Sensor.TYPE_ACCELEROMETER -> {
                routeCompassState.onVector(
                    registrationGeneration, RouteCompassStream.ACCELEROMETER, event.toRouteVectorSample(), SystemClock.elapsedRealtime(),
                )
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
        val headingErrorRad = event.values.getOrNull(4)
        val hasReportedError = headingErrorRad != null && !headingErrorRad.isNaN() && headingErrorRad >= 0f
        val observedAtMs = event.timestamp / NANOS_PER_MILLISECOND
        val nowMs = SystemClock.elapsedRealtime()
        val rowMajorRotation = FloatArray(9)
        val rotation = try {
            SensorManager.getRotationMatrixFromVector(rowMajorRotation, event.values)
            RotationMatrix3.fromRowMajor(rowMajorRotation)
        } catch (_: RuntimeException) {
            null
        }
        val headingErrorDeg = if (hasReportedError) Math.toDegrees(requireNotNull(headingErrorRad).toDouble()).toFloat()
            else Float.NaN
        val eventAccuracy = event.accuracy.toEarthOrientationAccuracy()
        routeCompassState.onRotationVector(
            registrationGeneration, rotation, headingErrorDeg, observedAtMs, eventAccuracy, nowMs,
        )
        if (rotation == null) {
            latestOrientation = null
            latestRotationSample = null
            return
        }
        val accuracy = rotationAccuracyStatus?.let { eventAccuracy.downgradedTo(it) } ?: eventAccuracy
        if (!hasReportedError || !headingErrorDeg.isFinite()) {
            latestOrientation = null
            latestRotationSample = null
            return
        }
        latestOrientation = DeviceEarthOrientation(
            deviceToMagneticEnu = rotation,
            observedAtElapsedRealtimeMs = observedAtMs,
            headingErrorDeg = headingErrorDeg,
            accuracy = accuracy,
        )
        latestRotationSample = ChestMountedRotationSample(
            deviceToMagneticEnu = rotation,
            observedAtMs = observedAtMs,
            headingAccuracyDegrees = headingErrorDeg.toDouble(),
            accuracy = accuracy,
            // S09-32: the same measured rate the route compass uses for its time allowance (S08-6).
            angularRateDegreesPerSecond = routeCompassState.latestRotationRateDegreesPerSecond(),
        )
    }

    private fun handleMagneticFieldChanged(event: SensorEvent) {
        routeCompassState.onVector(
            registrationGeneration, RouteCompassStream.MAGNETIC_FIELD, event.toRouteVectorSample(), SystemClock.elapsedRealtime(),
        )
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

    private fun Int.toRouteStream(): RouteCompassStream? = when (this) {
        Sensor.TYPE_ROTATION_VECTOR -> RouteCompassStream.ROTATION_VECTOR
        Sensor.TYPE_MAGNETIC_FIELD -> RouteCompassStream.MAGNETIC_FIELD
        Sensor.TYPE_GRAVITY -> RouteCompassStream.GRAVITY
        Sensor.TYPE_ACCELEROMETER -> RouteCompassStream.ACCELEROMETER
        else -> null
    }

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
