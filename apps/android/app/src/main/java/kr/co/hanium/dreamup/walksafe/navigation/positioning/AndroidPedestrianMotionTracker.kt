package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Android sensor adapter for [PedestrianStationaryDetector].
 *
 * Sensor events use Android's monotonic elapsed-realtime timebase. This class
 * only builds rolling-window motion statistics; stationary policy remains in
 * [PedestrianStationaryDetector].
 */
class AndroidPedestrianMotionTracker(
    context: Context,
    private val detector: PedestrianStationaryDetector = PedestrianStationaryDetector(),
    private val onDecision: (PedestrianStationaryDecision) -> Unit,
) : AutoCloseable {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerationWindow = ArrayDeque<TimedMagnitude>()
    private val gyroscopeWindow = ArrayDeque<TimedMagnitude>()
    private val stepEvidenceLock = Any()

    @Volatile
    private var started = false
    @Volatile
    private var closed = false
    @Volatile
    private var registrationGeneration = 0
    private var currentListener: SensorEventListener? = null
    private var accelerometerRegistered = false
    private var gyroscopeRegistered = false
    private var lastAccelerometerTimestampNs = NO_TIMESTAMP
    private var lastGyroscopeTimestampNs = NO_TIMESTAMP
    @Volatile
    private var lastEmittedTimestampMs = NO_TIMESTAMP
    private var lastRecordedStepTimestampMs = NO_TIMESTAMP
    private var pendingStepTimestampMs: Long? = null
    private var pendingStepGeneration = NO_GENERATION

    fun start(): Boolean {
        if (closed) return false
        if (started) return true
        if (accelerometer == null && gyroscope == null) return false

        clearMotionState()
        detector.reset()
        val generation = ++registrationGeneration
        lateinit var listener: SensorEventListener
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isCurrentRegistration(listener, generation)) return
                handleSensorChanged(event)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        currentListener = listener
        accelerometerRegistered = accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        } == true
        gyroscopeRegistered = gyroscope?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        } == true
        started = accelerometerRegistered || gyroscopeRegistered
        if (!started && currentListener === listener) {
            currentListener = null
            sensorManager.unregisterListener(listener)
        }
        return started
    }

    fun recordStep(timestampMs: Long): Boolean {
        if (timestampMs < 0L) return false
        val generation = registrationGeneration
        return synchronized(stepEvidenceLock) {
            if (
                !started ||
                closed ||
                currentListener == null ||
                generation != registrationGeneration
            ) {
                return@synchronized false
            }
            if (timestampMs <= lastRecordedStepTimestampMs) return@synchronized false
            if (
                lastEmittedTimestampMs != NO_TIMESTAMP &&
                timestampMs < lastEmittedTimestampMs - RECENT_STEP_WINDOW_MS
            ) {
                return@synchronized false
            }
            lastRecordedStepTimestampMs = timestampMs
            pendingStepTimestampMs = timestampMs
            pendingStepGeneration = generation
            true
        }
    }

    fun stop() {
        registrationGeneration += 1
        val listener = currentListener
        currentListener = null
        started = false
        listener?.let(sensorManager::unregisterListener)
        accelerometerRegistered = false
        gyroscopeRegistered = false
        clearMotionState()
        detector.reset()
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
    }

    private fun isCurrentRegistration(
        listener: SensorEventListener,
        generation: Int,
    ): Boolean =
        !closed &&
            started &&
            registrationGeneration == generation &&
            currentListener === listener

    private fun handleSensorChanged(event: SensorEvent) {
        val timestampNs = event.timestamp
        val targetWindow = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelerationWindow
            Sensor.TYPE_GYROSCOPE -> gyroscopeWindow
            else -> return
        }
        val magnitude = vectorMagnitude(event.values) ?: return
        if (!acceptTimestamp(event.sensor.type, timestampNs)) return

        targetWindow.addLast(TimedMagnitude(timestampNs, magnitude))
        val oldestAllowedTimestampNs = (timestampNs - WINDOW_DURATION_NS).coerceAtLeast(0L)
        trimBefore(accelerationWindow, oldestAllowedTimestampNs)
        trimBefore(gyroscopeWindow, oldestAllowedTimestampNs)

        val timestampMs = timestampNs / NANOS_PER_MILLISECOND
        if (timestampMs <= lastEmittedTimestampMs) return
        lastEmittedTimestampMs = timestampMs

        val acceleration = magnitudeStatistics(accelerationWindow)
        val angularVelocity = magnitudeStatistics(gyroscopeWindow)
        onDecision(
            detector.observe(
                PedestrianMotionSample(
                    elapsedRealtimeMs = timestampMs,
                    stepDetected = consumeRecentStep(timestampMs),
                    accelerationMagnitudeMeanMps2 = acceleration?.mean,
                    accelerationMagnitudeVarianceMps4 = acceleration?.variance,
                    gyroscopeRmsRadPerSecond = angularVelocity?.rootMeanSquare,
                    gyroscopePeakRadPerSecond = angularVelocity?.peak,
                ),
            ),
        )
    }

    private fun consumeRecentStep(sampleTimestampMs: Long): Boolean =
        synchronized(stepEvidenceLock) {
            val stepTimestampMs = pendingStepTimestampMs ?: return@synchronized false
            if (pendingStepGeneration != registrationGeneration) {
                clearStepEvidenceLocked()
                return@synchronized false
            }
            if (sampleTimestampMs < stepTimestampMs) return@synchronized false
            clearPendingStepLocked()
            sampleTimestampMs - stepTimestampMs <= RECENT_STEP_WINDOW_MS
        }

    private fun acceptTimestamp(sensorType: Int, timestampNs: Long): Boolean {
        if (timestampNs < 0L) return false
        return when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (timestampNs <= lastAccelerometerTimestampNs) return false
                lastAccelerometerTimestampNs = timestampNs
                true
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (timestampNs <= lastGyroscopeTimestampNs) return false
                lastGyroscopeTimestampNs = timestampNs
                true
            }
            else -> false
        }
    }

    private fun clearMotionState() {
        accelerationWindow.clear()
        gyroscopeWindow.clear()
        lastAccelerometerTimestampNs = NO_TIMESTAMP
        lastGyroscopeTimestampNs = NO_TIMESTAMP
        lastEmittedTimestampMs = NO_TIMESTAMP
        synchronized(stepEvidenceLock) {
            clearStepEvidenceLocked()
            lastRecordedStepTimestampMs = NO_TIMESTAMP
        }
    }

    private fun clearPendingStepLocked() {
        pendingStepTimestampMs = null
        pendingStepGeneration = NO_GENERATION
    }

    private fun clearStepEvidenceLocked() {
        clearPendingStepLocked()
        lastRecordedStepTimestampMs = NO_TIMESTAMP
    }

    private fun trimBefore(
        window: ArrayDeque<TimedMagnitude>,
        oldestAllowedTimestampNs: Long,
    ) {
        while (window.peekFirst()?.timestampNs?.let { it < oldestAllowedTimestampNs } == true) {
            window.removeFirst()
        }
    }

    private fun magnitudeStatistics(window: ArrayDeque<TimedMagnitude>): MagnitudeStatistics? {
        if (window.isEmpty()) return null
        var sum = 0.0
        var sumOfSquares = 0.0
        var peak = 0.0
        for (reading in window) {
            sum += reading.magnitude
            sumOfSquares += reading.magnitude * reading.magnitude
            peak = maxOf(peak, reading.magnitude)
        }
        val mean = sum / window.size
        var squaredDeviationSum = 0.0
        for (reading in window) {
            val deviation = reading.magnitude - mean
            squaredDeviationSum += deviation * deviation
        }
        return MagnitudeStatistics(
            mean = mean,
            variance = squaredDeviationSum / window.size,
            rootMeanSquare = sqrt(sumOfSquares / window.size),
            peak = peak,
        )
    }

    private fun vectorMagnitude(values: FloatArray): Double? {
        val x = values.getOrNull(0)?.toDouble() ?: return null
        val y = values.getOrNull(1)?.toDouble() ?: return null
        val z = values.getOrNull(2)?.toDouble() ?: return null
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        return sqrt(x * x + y * y + z * z).takeIf { it.isFinite() }
    }

    private data class TimedMagnitude(
        val timestampNs: Long,
        val magnitude: Double,
    )

    private data class MagnitudeStatistics(
        val mean: Double,
        val variance: Double,
        val rootMeanSquare: Double,
        val peak: Double,
    )

    private companion object {
        const val WINDOW_DURATION_NS = 1_000_000_000L
        const val RECENT_STEP_WINDOW_MS = 2_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NO_TIMESTAMP = -1L
        const val NO_GENERATION = -1
    }
}
