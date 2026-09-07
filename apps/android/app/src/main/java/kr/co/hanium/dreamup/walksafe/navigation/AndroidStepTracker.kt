package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

enum class StepEventSource {
    STEP_DETECTOR,
    STEP_COUNTER,
    ACCELEROMETER_FALLBACK,
}

enum class StepEventConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

data class StepEvent(
    val deltaSteps: Int,
    val timestampMs: Long,
    val source: StepEventSource,
    val confidence: StepEventConfidence,
)

class AndroidStepTracker(
    context: Context,
    private val onStepCountChanged: (Int) -> Unit,
    private val onTrackingStarted: () -> Unit = {},
    private val onMotionSensorSample: (Int) -> Unit = {},
    private val onStepEvent: (StepEvent) -> Unit = {},
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var activeWindowHardwareBaseline: Float? = null
    private var activeWindowStepOffset = 0
    private var totalActiveSteps = 0
    private var lastFallbackStepAtNs = 0L
    private var stepCounterRegistered = false
    private var stepDetectorRegistered = false
    private var accelerometerRegistered = false
    private var started = false
    private var registrationGeneration = 0
    private var currentListener: SensorEventListener? = null

    val usesStepCounter: Boolean
        get() = stepCounter != null

    fun start() {
        if (started) return
        if (stepCounter == null && stepDetector == null && accelerometer == null) return
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
        stepCounterRegistered = stepCounter?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        } == true
        stepDetectorRegistered = stepDetector?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        } == true
        accelerometerRegistered =
            if (!stepCounterRegistered && !stepDetectorRegistered) {
                accelerometer?.let {
                    sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
                } == true
            } else {
                false
            }
        started = stepCounterRegistered || stepDetectorRegistered || accelerometerRegistered
        if (!started && currentListener === listener) {
            currentListener = null
            sensorManager.unregisterListener(listener)
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
        stepCounterRegistered = false
        stepDetectorRegistered = false
        accelerometerRegistered = false
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
                val previousTotal = totalActiveSteps
                val updatedTotal =
                    activeWindowStepOffset + (total - baseline).toInt().coerceAtLeast(0)
                totalActiveSteps = maxOf(previousTotal, updatedTotal)
                onStepCountChanged(totalActiveSteps)
                val deltaSteps = totalActiveSteps - previousTotal
                if (!stepDetectorRegistered && deltaSteps > 0) {
                    emitStepEvent(
                        deltaSteps = deltaSteps,
                        timestampNs = event.timestamp,
                        source = StepEventSource.STEP_COUNTER,
                        confidence = StepEventConfidence.MEDIUM,
                    )
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                val detected = event.values.firstOrNull()?.takeIf { it > 0f } ?: return
                if (!stepCounterRegistered) {
                    totalActiveSteps += 1
                    onStepCountChanged(totalActiveSteps)
                }
                emitStepEvent(
                    deltaSteps = 1,
                    timestampNs = event.timestamp,
                    source = StepEventSource.STEP_DETECTOR,
                    confidence = StepEventConfidence.HIGH,
                )
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
                    emitStepEvent(
                        deltaSteps = 1,
                        timestampNs = event.timestamp,
                        source = StepEventSource.ACCELEROMETER_FALLBACK,
                        confidence = StepEventConfidence.LOW,
                    )
                }
            }
        }
        onMotionSensorSample(totalActiveSteps)
    }

    private fun emitStepEvent(
        deltaSteps: Int,
        timestampNs: Long,
        source: StepEventSource,
        confidence: StepEventConfidence,
    ) {
        if (deltaSteps <= 0 || timestampNs < 0L) return
        onStepEvent(
            StepEvent(
                deltaSteps = deltaSteps,
                timestampMs = timestampNs / NANOS_PER_MILLISECOND,
                source = source,
                confidence = confidence,
            ),
        )
    }

    private companion object {
        const val FALLBACK_STEP_MAGNITUDE = 12.2f
        const val FALLBACK_STEP_DEBOUNCE_NS = 300_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
