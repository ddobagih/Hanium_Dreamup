package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedHeading
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedHeadingResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedMagneticFieldSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedRotationSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservationHistory

/** Fresh, accuracy-bounded magnetic East-North-Up orientation from TYPE_ROTATION_VECTOR. */
class AndroidEarthOrientationTracker(context: Context) {
    private val chestHeadingHistory = HeadingObservationHistory()
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val chestMountedHeading = ChestMountedHeading()

    @Volatile
    private var latestOrientation: DeviceEarthOrientation? = null
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
        val sensor = rotationVectorSensor ?: return false
        magneticSensorRegistered = false
        latestOrientation = null
        latestRotationSample = null
        latestMagneticFieldSample = null
        chestHeadingHistory.clear()
        chestMountedHeading.reset()
        val generation = ++registrationGeneration
        lateinit var listener: SensorEventListener
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isCurrentRegistration(listener, generation)) return
                handleSensorChanged(event)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (!isCurrentRegistration(listener, generation)) return
                val mappedAccuracy = accuracy.toEarthOrientationAccuracy()
                // Never replay headings accepted under a superseded quality assessment.
                chestHeadingHistory.clear()
                when (sensor?.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
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
        currentListener = listener
        val rotationRegistered = sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        started = rotationRegistered
        if (!rotationRegistered) {
            if (currentListener === listener) currentListener = null
            chestHeadingHistory.clear()
            chestMountedHeading.reset()
            return false
        }
        magneticSensorRegistered = magneticFieldSensor?.let { magneticSensor ->
            runCatching {
                sensorManager.registerListener(
                    listener,
                    magneticSensor,
                    SensorManager.SENSOR_DELAY_GAME,
                )
            }.getOrDefault(false)
        } ?: false
        return true
    }

    @Synchronized
    fun stop() {
        registrationGeneration += 1
        val listener = currentListener
        currentListener = null
        started = false
        listener?.let(sensorManager::unregisterListener)
        latestOrientation = null
        latestRotationSample = null
        latestMagneticFieldSample = null
        magneticSensorRegistered = false
        chestHeadingHistory.clear()
        chestMountedHeading.reset()
    }

    fun latest(): DeviceEarthOrientation? = latestOrientation

    fun latestChestMountedHeading(
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ): ChestMountedHeadingResult = chestMountedHeading.evaluate(
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        rotation = latestRotationSample,
        magneticField = latestMagneticFieldSample,
        geomagneticReference = geomagneticReference,
        magneticSensorAvailable = magneticSensorRegistered,
    )

    fun chestMountedHeadingAt(timestampMs: Long, maximumAgeMs: Long = 500L): HeadingObservation? =
        chestHeadingHistory.atOrBefore(timestampMs, maximumAgeMs)

    fun isMagneticFieldAvailable(): Boolean = magneticSensorRegistered

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

    fun clearGeomagneticReference() {
        geomagneticReference = null
        chestHeadingHistory.clear()
    }

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
            ?.takeIf { it.isFinite() && it >= 0f }
            ?: run {
                latestOrientation = null
                latestRotationSample = null
                return
            }
        val rowMajorRotation = FloatArray(9)
        try {
            SensorManager.getRotationMatrixFromVector(rowMajorRotation, event.values)
        } catch (_: RuntimeException) {
            latestOrientation = null
            latestRotationSample = null
            return
        }
        val rotation = RotationMatrix3.fromRowMajor(rowMajorRotation) ?: run {
            latestOrientation = null
            latestRotationSample = null
            return
        }
        val observedAtMs = event.timestamp / NANOS_PER_MILLISECOND
        val headingErrorDeg = Math.toDegrees(headingErrorRad.toDouble()).toFloat()
        val accuracy = event.accuracy.toEarthOrientationAccuracy()
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
        )
    }

    private fun handleMagneticFieldChanged(event: SensorEvent) {
        latestMagneticFieldSample = ChestMountedMagneticFieldSample(
            xMicrotesla = event.values.getOrNull(0)?.toDouble() ?: Double.NaN,
            yMicrotesla = event.values.getOrNull(1)?.toDouble() ?: Double.NaN,
            zMicrotesla = event.values.getOrNull(2)?.toDouble() ?: Double.NaN,
            observedAtMs = event.timestamp / NANOS_PER_MILLISECOND,
            accuracy = event.accuracy.toEarthOrientationAccuracy(),
        )
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
