package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Android sensor adapter for [PedestrianStationaryDetector].
 *
 * Sensor events use Android's monotonic elapsed-realtime timebase. This class
 * only builds rolling-window motion statistics; stationary policy remains in
 * [PedestrianStationaryDetector].
 */
class AndroidPedestrianMotionTracker internal constructor(
    private val registration: MotionSensorRegistration,
    private val detector: PedestrianStationaryDetector = PedestrianStationaryDetector(),
    private val onDecision: (PedestrianStationaryDecision) -> Unit,
) : AutoCloseable {
    constructor(
        context: Context,
        detector: PedestrianStationaryDetector = PedestrianStationaryDetector(),
        onDecision: (PedestrianStationaryDecision) -> Unit,
    ) : this(AndroidMotionSensorRegistration(context), detector, onDecision)

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
    // Callback receipts share one serial clock; per-source measurement order stays independent.
    private var lastKnownReceiptNs: Long? = null
    @Volatile
    private var lastEmittedTimestampMs = NO_TIMESTAMP
    private var lastRecordedStepTimestampMs = NO_TIMESTAMP
    private var pendingStepTimestampMs: Long? = null
    private var pendingStepGeneration = NO_GENERATION

    private val health = SensorTrackerHealthRecorder(listOf(
        TrackedSensorSource.ACCELEROMETER, TrackedSensorSource.GYROSCOPE,
    ))

    /** Registration, accepted input and cleanup facts; current permission is checked by the caller. */
    val operationalHealth: SensorTrackerHealthSnapshot
        get() = health.snapshot

    fun start(): Boolean {
        if (closed) return false
        if (started) return true
        // A failed unregister still owns its listener; release it before creating another one.
        if (currentListener != null) {
            try {
                stop(SensorTrackerStoppedReason.FAILED_START)
            } catch (failure: RuntimeException) {
                health.retire(registrationGeneration, SensorTrackerStoppedReason.FAILED_START, currentListener != null)
                throw failure
            }
        }
        health.beginAttempt(registrationGeneration)
        val anySensorAvailable = try {
            probeSensorAvailability(Sensor.TYPE_ACCELEROMETER, TrackedSensorSource.ACCELEROMETER) ||
                probeSensorAvailability(Sensor.TYPE_GYROSCOPE, TrackedSensorSource.GYROSCOPE)
        } catch (failure: RuntimeException) {
            health.retire(registrationGeneration, SensorTrackerStoppedReason.FAILED_START, currentListener != null)
            throw failure
        }
        if (!anySensorAvailable) {
            health.retire(registrationGeneration, SensorTrackerStoppedReason.FAILED_START, false)
            return false
        }

        clearMotionState()
        detector.reset()
        val generation = ++registrationGeneration
        health.generationStarted(generation)
        lateinit var listener: SensorEventListener
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isCurrentRegistration(listener, generation)) return
                handleSensorChanged(event)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        currentListener = listener
        try {
            val accelerometerAvailable = registration.hasSensor(Sensor.TYPE_ACCELEROMETER)
            health.availability(TrackedSensorSource.ACCELEROMETER, accelerometerAvailable)
            accelerometerRegistered = accelerometerAvailable &&
                health.register(TrackedSensorSource.ACCELEROMETER) {
                    registration.register(listener, Sensor.TYPE_ACCELEROMETER)
                }
            val gyroscopeAvailable = registration.hasSensor(Sensor.TYPE_GYROSCOPE)
            health.availability(TrackedSensorSource.GYROSCOPE, gyroscopeAvailable)
            gyroscopeRegistered = gyroscopeAvailable &&
                health.register(TrackedSensorSource.GYROSCOPE) {
                    registration.register(listener, Sensor.TYPE_GYROSCOPE)
                }
        } catch (failure: RuntimeException) {
            try {
                stop(SensorTrackerStoppedReason.FAILED_START)
            } catch (cleanupFailure: RuntimeException) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        started = accelerometerRegistered || gyroscopeRegistered
        if (!started && currentListener === listener) {
            stop(SensorTrackerStoppedReason.FAILED_START)
        } else if (started) {
            health.activate()
        }
        return started
    }

    private fun probeSensorAvailability(type: Int, source: TrackedSensorSource): Boolean =
        registration.hasSensor(type).also { health.availability(source, it) }

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

    fun stop() = stop(SensorTrackerStoppedReason.EXPLICIT_STOP)

    private fun stop(reason: SensorTrackerStoppedReason) {
        registrationGeneration += 1
        val listener = currentListener
        started = false
        val effectiveReason = if (closed) SensorTrackerStoppedReason.CLOSED else reason
        health.retire(registrationGeneration, effectiveReason, listener != null)
        try {
            listener?.let(registration::unregister)
            if (currentListener === listener) currentListener = null
        } finally {
            accelerometerRegistered = false
            gyroscopeRegistered = false
            clearMotionState()
            detector.reset()
            health.retire(registrationGeneration, effectiveReason, currentListener != null)
        }
    }

    override fun close() {
        if (closed && currentListener == null) return
        closed = true
        stop(SensorTrackerStoppedReason.CLOSED)
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
        val receivedAtNs = SystemClock.elapsedRealtimeNanos()
        onSensorSample(event.sensor.type, event.values, event.timestamp, receivedAtNs)
    }

    /** The real callback's input path; unknown legacy receipt stays null in the health snapshot. */
    internal fun onSensorSample(sensorType: Int, values: FloatArray, timestampNs: Long,
        receivedAtNs: Long? = null): Boolean {
        if (!started || closed || currentListener == null) return false
        val generation = registrationGeneration
        val source = when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> TrackedSensorSource.ACCELEROMETER
            Sensor.TYPE_GYROSCOPE -> TrackedSensorSource.GYROSCOPE
            else -> return false
        }
        if (health.snapshot.source(source)?.acceptingSamples != true) return false
        if (receivedAtNs != null && receivedAtNs < timestampNs) return false
        if (receivedAtNs != null && lastKnownReceiptNs?.let { receivedAtNs < it } == true) return false
        val targetWindow = when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> accelerationWindow
            else -> gyroscopeWindow
        }
        val magnitude = vectorMagnitude(values) ?: return false
        if (!acceptTimestamp(sensorType, timestampNs)) return false
        if (receivedAtNs != null) lastKnownReceiptNs = receivedAtNs
        health.acceptedSample(source, generation, timestampNs, receivedAtNs)

        targetWindow.addLast(TimedMagnitude(timestampNs, magnitude))
        val oldestAllowedTimestampNs = (timestampNs - WINDOW_DURATION_NS).coerceAtLeast(0L)
        trimBefore(accelerationWindow, oldestAllowedTimestampNs)
        trimBefore(gyroscopeWindow, oldestAllowedTimestampNs)

        val timestampMs = timestampNs / NANOS_PER_MILLISECOND
        if (timestampMs <= lastEmittedTimestampMs) return true
        lastEmittedTimestampMs = timestampMs

        val acceleration = magnitudeStatistics(accelerationWindow, timestampNs)
        val angularVelocity = magnitudeStatistics(gyroscopeWindow, timestampNs)
        onDecision(
            detector.observe(
                PedestrianMotionSample(
                    elapsedRealtimeMs = timestampMs,
                    stepDetected = consumeRecentStep(timestampMs),
                    accelerationMagnitudeMeanMps2 = acceleration?.mean,
                    accelerationMagnitudeVarianceMps4 = acceleration?.variance,
                    gyroscopeRmsRadPerSecond = angularVelocity?.rootMeanSquare,
                    gyroscopePeakRadPerSecond = angularVelocity?.peak,
                    accelerationWindow = acceleration?.coverage,
                    gyroscopeWindow = angularVelocity?.coverage,
                ),
            ),
        )
        return true
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
        lastKnownReceiptNs = null
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

    private fun magnitudeStatistics(
        window: ArrayDeque<TimedMagnitude>,
        nowNs: Long,
    ): MagnitudeStatistics? {
        if (window.size < MINIMUM_WINDOW_SAMPLES) return null
        val first = window.peekFirst() ?: return null
        val last = window.peekLast() ?: return null
        var maximumGapNs = 0L
        var previousAtNs = first.timestampNs
        for (reading in window) {
            maximumGapNs = maxOf(maximumGapNs, reading.timestampNs - previousAtNs)
            previousAtNs = reading.timestampNs
        }
        val coverage = PedestrianMotionWindowCoverage(
            sampleCount = window.size,
            startedAtElapsedRealtimeMs = first.timestampNs / NANOS_PER_MILLISECOND,
            endedAtElapsedRealtimeMs = last.timestampNs / NANOS_PER_MILLISECOND,
            maximumGapMs = (maximumGapNs + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND,
        )
        if (!coverage.isSufficient(
                nowNs / NANOS_PER_MILLISECOND,
                MINIMUM_WINDOW_SAMPLES,
                MINIMUM_WINDOW_DURATION_MS,
                MAXIMUM_WINDOW_GAP_MS,
            )
        ) return null
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
            coverage = coverage,
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
        val coverage: PedestrianMotionWindowCoverage,
    )

    private companion object {
        const val WINDOW_DURATION_NS = 1_000_000_000L
        const val MINIMUM_WINDOW_SAMPLES = 5
        const val MINIMUM_WINDOW_DURATION_MS = 200L
        const val MAXIMUM_WINDOW_GAP_MS = 250L
        const val RECENT_STEP_WINDOW_MS = 2_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NO_TIMESTAMP = -1L
        const val NO_GENERATION = -1
    }
}

internal interface MotionSensorRegistration {
    fun hasSensor(type: Int): Boolean
    fun register(listener: SensorEventListener, type: Int): Boolean
    fun unregister(listener: SensorEventListener?)
}

private class AndroidMotionSensorRegistration(context: Context) : MotionSensorRegistration {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    override fun hasSensor(type: Int): Boolean = sensorFor(type) != null

    override fun register(listener: SensorEventListener, type: Int): Boolean =
        sensorFor(type)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        } == true

    override fun unregister(listener: SensorEventListener?) {
        listener?.let(sensorManager::unregisterListener)
    }

    private fun sensorFor(type: Int): Sensor? = when (type) {
        Sensor.TYPE_ACCELEROMETER -> accelerometer
        Sensor.TYPE_GYROSCOPE -> gyroscope
        else -> null
    }
}
