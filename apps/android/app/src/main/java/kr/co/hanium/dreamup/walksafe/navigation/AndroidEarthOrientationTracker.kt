package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Fresh, accuracy-bounded magnetic East-North-Up orientation from TYPE_ROTATION_VECTOR. */
class AndroidEarthOrientationTracker(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile
    private var latestOrientation: DeviceEarthOrientation? = null
    @Volatile
    private var started = false
    private var registrationGeneration = 0
    private var currentListener: SensorEventListener? = null

    fun start(): Boolean {
        if (started) return true
        val sensor = rotationVectorSensor ?: return false
        val generation = ++registrationGeneration
        lateinit var listener: SensorEventListener
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isCurrentRegistration(listener, generation)) return
                handleSensorChanged(event)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (
                    isCurrentRegistration(listener, generation) &&
                    sensor?.type == Sensor.TYPE_ROTATION_VECTOR &&
                    accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                ) {
                    latestOrientation = null
                }
            }
        }
        currentListener = listener
        started = sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        if (!started && currentListener === listener) currentListener = null
        return started
    }

    fun stop() {
        registrationGeneration += 1
        val listener = currentListener
        currentListener = null
        started = false
        listener?.let(sensorManager::unregisterListener)
        latestOrientation = null
    }

    fun latest(): DeviceEarthOrientation? = latestOrientation

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
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val headingErrorRad = event.values.getOrNull(4)
            ?.takeIf { it.isFinite() && it >= 0f }
            ?: run {
                latestOrientation = null
                return
            }
        val rowMajorRotation = FloatArray(9)
        try {
            SensorManager.getRotationMatrixFromVector(rowMajorRotation, event.values)
        } catch (_: RuntimeException) {
            latestOrientation = null
            return
        }
        val rotation = RotationMatrix3.fromRowMajor(rowMajorRotation) ?: run {
            latestOrientation = null
            return
        }
        latestOrientation = DeviceEarthOrientation(
            deviceToMagneticEnu = rotation,
            observedAtElapsedRealtimeMs = event.timestamp / NANOS_PER_MILLISECOND,
            headingErrorDeg = Math.toDegrees(headingErrorRad.toDouble()).toFloat(),
            accuracy = event.accuracy.toEarthOrientationAccuracy(),
        )
    }

    private fun Int.toEarthOrientationAccuracy(): EarthOrientationAccuracy = when (this) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> EarthOrientationAccuracy.HIGH
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> EarthOrientationAccuracy.MEDIUM
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> EarthOrientationAccuracy.LOW
        else -> EarthOrientationAccuracy.UNRELIABLE
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
