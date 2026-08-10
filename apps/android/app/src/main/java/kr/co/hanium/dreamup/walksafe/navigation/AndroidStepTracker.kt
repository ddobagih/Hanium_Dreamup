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
    private val onTrackingStarted: () -> Unit = {},
    private val onMotionSensorSample: (Int) -> Unit = {},
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var activeWindowHardwareBaseline: Float? = null
    private var activeWindowStepOffset = 0
    private var totalActiveSteps = 0
    private var lastFallbackStepAtNs = 0L
    private var started = false
    private var registrationGeneration = 0
    private var currentListener: SensorEventListener? = null

    val usesStepCounter: Boolean
        get() = stepCounter != null

    fun start() {
        if (started) return
        val sensor = stepCounter ?: accelerometer ?: return
        activeWindowHardwareBaseline = null
        activeWindowStepOffset = totalActiveSteps
        lastFallbackStepAtNs = 0L
        val generation = ++registrationGeneration
        lateinit var listener: SensorEventListener
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!started) return
                if (
                    generation != registrationGeneration ||
                    currentListener !== listener
                ) return
                handleSensorChanged(event)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        currentListener = listener
        started = sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
        )
        if (!started && currentListener === listener) {
            currentListener = null
        } else if (started) {
            onTrackingStarted()
        }
    }

    fun stop() {
        registrationGeneration += 1
        val listener = currentListener
        currentListener = null
        started = false
        listener?.let(sensorManager::unregisterListener)
        activeWindowHardwareBaseline = null
        activeWindowStepOffset = totalActiveSteps
        lastFallbackStepAtNs = 0L
    }

    fun resetForNewWalk() {
        stop()
        activeWindowHardwareBaseline = null
        activeWindowStepOffset = 0
        totalActiveSteps = 0
        lastFallbackStepAtNs = 0L
        onStepCountChanged(0)
    }

    private fun handleSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull() ?: return
                val baseline = activeWindowHardwareBaseline
                    ?: total.also { activeWindowHardwareBaseline = it }
                totalActiveSteps =
                    activeWindowStepOffset + (total - baseline).toInt().coerceAtLeast(0)
                onStepCountChanged(totalActiveSteps)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values.getOrNull(0) ?: return
                val y = event.values.getOrNull(1) ?: return
                val z = event.values.getOrNull(2) ?: return
                val magnitude = sqrt(x * x + y * y + z * z)
                if (magnitude >= FALLBACK_STEP_MAGNITUDE && event.timestamp - lastFallbackStepAtNs >= FALLBACK_STEP_DEBOUNCE_NS) {
                    totalActiveSteps += 1
                    lastFallbackStepAtNs = event.timestamp
                    onStepCountChanged(totalActiveSteps)
                }
            }
        }
        onMotionSensorSample(totalActiveSteps)
    }

    private companion object {
        const val FALLBACK_STEP_MAGNITUDE = 12.2f
        const val FALLBACK_STEP_DEBOUNCE_NS = 300_000_000L
    }
}
