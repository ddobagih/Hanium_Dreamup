package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class AndroidStepTracker(
    context: Context,
    private val onStepCountChanged: (Int) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var baselineSteps: Float? = null
    private var fallbackSteps = 0
    private var lastFallbackStepAtNs = 0L
    private var started = false

    val usesStepCounter: Boolean
        get() = stepCounter != null

    fun start() {
        if (started) return
        val sensor = stepCounter ?: accelerometer ?: return
        started = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        started = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull() ?: return
                val baseline = baselineSteps ?: total.also { baselineSteps = it }
                onStepCountChanged((total - baseline).toInt().coerceAtLeast(0))
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values.getOrNull(0) ?: return
                val y = event.values.getOrNull(1) ?: return
                val z = event.values.getOrNull(2) ?: return
                val magnitude = sqrt(x * x + y * y + z * z)
                if (magnitude >= FALLBACK_STEP_MAGNITUDE && event.timestamp - lastFallbackStepAtNs >= FALLBACK_STEP_DEBOUNCE_NS) {
                    fallbackSteps += 1
                    lastFallbackStepAtNs = event.timestamp
                    onStepCountChanged(fallbackSteps)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val FALLBACK_STEP_MAGNITUDE = 12.2f
        const val FALLBACK_STEP_DEBOUNCE_NS = 300_000_000L
    }
}
