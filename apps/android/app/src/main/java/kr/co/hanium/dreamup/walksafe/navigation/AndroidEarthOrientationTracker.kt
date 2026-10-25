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

/** A new original sensor sample or a quality-only invalidation; receipt never replaces sample time. */
internal data class RouteCompassObservationEvent(
    val registrationGeneration: Int,
    val stream: RouteCompassStream,
    val sensorTimestampNanos: Long?,
    val receivedAtMs: Long,
    val heading: RouteCompassHeadingResult,
    val invalidated: Boolean,
)

/** The tracker calls this only inside its admitted registration lock. No SDK or navigation state. */
internal class RouteCompassObservationPublisher {
    private var generation = -1
    private val newest = mutableMapOf<RouteCompassStream, Long>()
    fun reset(generation: Int) { this.generation = generation; newest.clear() }
    fun sample(generation: Int, stream: RouteCompassStream, timestampNs: Long, receivedAtNs: Long,
        heading: RouteCompassHeadingResult): RouteCompassObservationEvent? {
        if (generation != this.generation || timestampNs < 0L || timestampNs > receivedAtNs ||
            newest[stream]?.let { timestampNs <= it } == true) return null
        newest[stream] = timestampNs
        return RouteCompassObservationEvent(generation, stream, timestampNs, receivedAtNs / 1_000_000L,
            heading, heading.observation == null)
    }
    fun accuracy(generation: Int, stream: RouteCompassStream, receivedAtMs: Long,
        heading: RouteCompassHeadingResult): RouteCompassObservationEvent? {
        if (generation != this.generation || receivedAtMs < 0L || heading.observation != null) return null
        return RouteCompassObservationEvent(generation, stream, null, receivedAtMs, heading, true)
    }
}

/** Trusted RV orientation for AR/PDR, plus an independently gated route-only magnetic compass. */
class AndroidEarthOrientationTracker(
    context: Context,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val chestHeadingHistory = HeadingObservationHistory()
    private val orientationHistory = DeviceEarthOrientationHistory()
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
    private val routeObservationPublisher = RouteCompassObservationPublisher()
    @Volatile private var routeObservationListener: ((RouteCompassObservationEvent) -> Unit)? = null

    internal fun setRouteObservationListener(listener: ((RouteCompassObservationEvent) -> Unit)?) {
        routeObservationListener = listener
    }

    @Synchronized
    internal fun isRouteObservationCurrent(event: RouteCompassObservationEvent): Boolean =
        started && registrationGeneration == event.registrationGeneration

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
        orientationHistory.clear()
        rotationAccuracyStatus = null
        lastCompassDiagnosticAtMs = null
        routeCompassState.stop()
        latestRotationSample = null
        latestMagneticFieldSample = null
        chestHeadingHistory.clear()
        chestMountedHeading.reset()
        val generation = ++registrationGeneration
        routeObservationPublisher.reset(generation)
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
    private fun reregisterStalledSensors() {
        val nowMs = elapsedRealtimeMs()
        if (!routeCompassState.reregistrationDue(nowMs)) return
        val thread = sensorThread ?: return
        val oldListener = currentListener ?: return
        sensorManager.unregisterListener(oldListener)
        val generation = ++registrationGeneration
        routeObservationPublisher.reset(generation)
        orientationHistory.clear()
        val listener = createListener(generation)
        val handler = Handler(thread.looper)
        currentListener = listener
        registrationStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val rotationRegistered = register(listener, rotationVectorSensor, handler)
        magneticSensorRegistered = register(listener, magneticFieldSensor, handler)
        val gravityRegistered = register(listener, gravitySensor, handler)
        val accelerometerRegistered = register(listener, accelerometerSensor, handler)
        registeredRouteStreams = routeStreams(rotationRegistered, magneticSensorRegistered, gravityRegistered, accelerometerRegistered)
        routeCompassState.onReregistered(generation, SystemClock.elapsedRealtime(), registeredRouteStreams)
    }

    private fun createListener(generation: Int): SensorEventListener {
        lateinit var listener: SensorEventListener
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val receivedAtNanos = SystemClock.elapsedRealtimeNanos()
                val observation = synchronized(this@AndroidEarthOrientationTracker) {
                    if (!isCurrentRegistration(listener, generation) || event.timestamp < registrationStartedAtNanos) return
                    val stream = event.sensor.type.toRouteStream() ?: return
                    var accepted: RouteCompassObservationEvent? = null
                    routeCompassState.onSensorCallback(generation, stream, event.timestamp, receivedAtNanos) { receivedAtMs ->
                        handleSensorChanged(event, receivedAtMs)
                        // Quality is evaluated from the original sensor cache; this neither runs
                        // the Navigator nor invokes the SDK re-registration watchdog.
                        accepted = publishAdmittedRouteCompassSample(generation, stream, event.timestamp, receivedAtNanos)
                    }
                    accepted
                }
                // Never enter Main/UI code while holding the sensor registration monitor.
                observation?.let { routeObservationListener?.invoke(it) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                val observation = synchronized(this@AndroidEarthOrientationTracker) {
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
                            orientationHistory.clear()
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
                    val stream = sensor?.type?.toRouteStream() ?: return@synchronized null
                    val receivedAtMs = SystemClock.elapsedRealtime()
                    val heading = routeCompassState.evaluate(receivedAtMs, geomagneticReferenceRecord?.usableAt(receivedAtMs))
                    routeObservationPublisher.accuracy(generation, stream, receivedAtMs, heading)
                }
                observation?.let { routeObservationListener?.invoke(it) }
            }
        }
        return listener
    }

    /** Called under the registration monitor after original timestamp admission and conversion. */
    @Synchronized
    internal fun publishAdmittedRouteCompassSample(
        generation: Int,
        stream: RouteCompassStream,
        sensorTimestampNanos: Long,
        receivedAtNanos: Long,
    ): RouteCompassObservationEvent? {
        val receivedAtMs = receivedAtNanos / 1_000_000L
        val snapshotNowMs = maxOf(receivedAtMs, elapsedRealtimeMs())
        val heading = routeCompassState.evaluate(snapshotNowMs, geomagneticReferenceRecord?.usableAt(snapshotNowMs))
        return routeObservationPublisher.sample(generation, stream, sensorTimestampNanos, receivedAtNanos, heading)
    }

    @Synchronized
    fun stop() {
        registrationGeneration += 1
        routeObservationPublisher.reset(registrationGeneration)
        val listener = currentListener
        currentListener = null
        started = false
        orientationHistory.clear()
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

    /** Full RV matrix measured at or before capture; caller retains its existing trust/age gates. */
    @Synchronized
    fun orientationAt(timestampMs: Long, maximumAgeMs: Long): DeviceEarthOrientation? =
        if (started) orientationHistory.atOrBefore(timestampMs, maximumAgeMs) else null

    /** No fixed chest posture is required; fallback observations never enter latest() or PDR. */
    @Synchronized
    fun latestRouteCompassHeading(nowMs: Long = SystemClock.elapsedRealtime()): RouteCompassHeadingResult {
        if (started) reregisterStalledSensors()
        // A sensor callback or reference update may acquire this monitor after the caller
        // captures nowMs. Evaluate the current cache against a clock read under the same lock;
        // original measurement timestamps and their freshness limits stay unchanged.
        val snapshotNowMs = maxOf(nowMs, elapsedRealtimeMs())
        val result = if (started) {
            routeCompassState.evaluate(snapshotNowMs, geomagneticReferenceRecord?.usableAt(snapshotNowMs))
        } else {
            RouteCompassHeadingResult(null, notStartedRouteReason)
        }
        // Opt-in diagnostics; no coordinates, no speech, and at most one line per second.
        val previousLogAt = lastCompassDiagnosticAtMs
        if (Log.isLoggable("RouteCompass", Log.DEBUG) &&
            (previousLogAt == null || snapshotNowMs < previousLogAt || snapshotNowMs - previousLogAt >= 1_000L)
        ) {
            lastCompassDiagnosticAtMs = snapshotNowMs
            fun age(at: Long?): Long? = at?.let { snapshotNowMs - it }
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
        orientationHistory.clear()
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

    private fun handleSensorChanged(event: SensorEvent, receivedAtMs: Long) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVectorChanged(event, receivedAtMs)
            Sensor.TYPE_MAGNETIC_FIELD -> handleMagneticFieldChanged(event, receivedAtMs)
            Sensor.TYPE_GRAVITY -> {
                consumeRawVector(event, RouteCompassStream.GRAVITY, receivedAtMs)
                return
            }
            Sensor.TYPE_ACCELEROMETER -> {
                consumeRawVector(event, RouteCompassStream.ACCELEROMETER, receivedAtMs)
                return
            }
            else -> return
        }
        // Save already-gated observations at sensor time, before delayed step callbacks consume them.
        val result = latestChestMountedHeading(receivedAtMs)
        val heading = result.trueHeadingDegrees
        val accuracy = result.accuracyDegrees
        val observedAt = result.observedAtMs
        if (result.isValid && heading != null && accuracy != null && observedAt != null) {
            chestHeadingHistory.add(HeadingObservation(heading, accuracy, observedAt))
        } else {
            chestHeadingHistory.clear()
        }
    }

    private fun handleRotationVectorChanged(event: SensorEvent, receivedAtMs: Long) {
        val eventAccuracy = event.accuracy.toEarthOrientationAccuracy()
        val decoded = RouteCompassRawSensorConsumer.rotationVector(
            routeCompassState, registrationGeneration, event.values, event.timestamp, eventAccuracy, receivedAtMs,
        )
        if (decoded == null) {
            latestOrientation = null
            orientationHistory.clear()
            latestRotationSample = null
            return
        }
        val rotation = decoded.deviceToMagneticEnu
        val observedAtMs = decoded.observedAtElapsedRealtimeMs
        val headingErrorDeg = decoded.headingErrorDeg
        val accuracy = rotationAccuracyStatus?.let { eventAccuracy.downgradedTo(it) } ?: eventAccuracy
        if (!headingErrorDeg.isFinite()) {
            latestOrientation = null
            orientationHistory.clear()
            latestRotationSample = null
            return
        }
        latestOrientation = DeviceEarthOrientation(
            deviceToMagneticEnu = rotation,
            observedAtElapsedRealtimeMs = observedAtMs,
            headingErrorDeg = headingErrorDeg,
            accuracy = accuracy,
        )
        // A degraded RV cannot leave an earlier trusted matrix available to a delayed capture.
        if (accuracy < EarthOrientationAccuracy.MEDIUM) orientationHistory.clear()
        orientationHistory.add(requireNotNull(latestOrientation))
        latestRotationSample = ChestMountedRotationSample(
            deviceToMagneticEnu = rotation,
            observedAtMs = observedAtMs,
            headingAccuracyDegrees = headingErrorDeg.toDouble(),
            accuracy = accuracy,
            // S09-32: the same measured rate the route compass uses for its time allowance (S08-6).
            angularRateDegreesPerSecond = routeCompassState.latestRotationRateDegreesPerSecond(),
        )
    }

    private fun handleMagneticFieldChanged(event: SensorEvent, receivedAtMs: Long) {
        val sample = consumeRawVector(event, RouteCompassStream.MAGNETIC_FIELD, receivedAtMs)
        latestMagneticFieldSample = selectStrictMagneticMeasurement(
            routeCompassState.latestMagneticMeasurement(),
            ChestMountedMagneticFieldSample(
                xMicrotesla = sample.x,
                yMicrotesla = sample.y,
                zMicrotesla = sample.z,
                observedAtMs = sample.observedAtMs,
                accuracy = sample.accuracy,
            ),
        )
    }

    private fun consumeRawVector(event: SensorEvent, stream: RouteCompassStream, receivedAtMs: Long) =
        RouteCompassRawSensorConsumer.vector(routeCompassState, registrationGeneration, stream,
            event.values, event.timestamp, event.accuracy.toEarthOrientationAccuracy(), receivedAtMs)

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
        const val NANOTESLA_PER_MICROTESLA = 1_000.0
    }
}

/** Magnetic cache selection shared with the Android callback. */
internal fun selectStrictMagneticMeasurement(
    latestMeasurement: RouteCompassVectorSample?,
    incoming: ChestMountedMagneticFieldSample,
): ChestMountedMagneticFieldSample {
    // Keep malformed/low-quality arrivals fail-closed instead of hiding them behind older data.
    val strengthSquared = incoming.xMicrotesla * incoming.xMicrotesla +
        incoming.yMicrotesla * incoming.yMicrotesla + incoming.zMicrotesla * incoming.zMicrotesla
    if (!incoming.xMicrotesla.isFinite() || !incoming.yMicrotesla.isFinite() ||
        !incoming.zMicrotesla.isFinite() || !strengthSquared.isFinite() ||
        incoming.accuracy < EarthOrientationAccuracy.MEDIUM
    ) return incoming
    // The existing State history retains the newest original measurement across degraded arrivals.
    return latestMeasurement?.let {
        ChestMountedMagneticFieldSample(it.x, it.y, it.z, it.observedAtMs, it.accuracy)
    } ?: incoming
}
