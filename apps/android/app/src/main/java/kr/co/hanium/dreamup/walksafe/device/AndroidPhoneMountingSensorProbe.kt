package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.io.Closeable
import java.util.ArrayDeque
import kotlin.math.sqrt

data class PhoneMountingSensorMeasurement(
    /** Optional diagnostic only; it is not required to measure the mounting angle. */
    val angularShakeDegreesPerSecond: Double?,
    /** Signed back-camera optical-axis angle above or below horizontal. */
    val cameraPitchFromHorizontalDegrees: Double,
)

/** Gravity supplies the mounting angle; gyro is an optional diagnostic. */
class AndroidPhoneMountingSensorProbe internal constructor(
    private val registration: PhoneMountingSensorRegistration,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : Closeable {
    constructor(context: Context) : this(AndroidPhoneMountingSensorRegistration(context))

    private data class TimedGyro(
        val observedAtElapsedRealtimeMs: Long,
        val magnitudeRadiansPerSecond: Double,
    )

    private data class TimedGravity(
        val observedAtElapsedRealtimeMs: Long,
        val x: Double,
        val y: Double,
        val z: Double,
    )

    private val lock = Any()
    private val recentGyro = ArrayDeque<TimedGyro>()
    private var latestGravity: TimedGravity? = null
    private var listener: SensorEventListener? = null
    private var generation = 0

    fun start(): Boolean {
        synchronized(lock) {
            if (listener != null) return true
            if (!registration.hasSensor(Sensor.TYPE_GRAVITY)) return false
            val currentGeneration = ++generation
            lateinit var currentListener: SensorEventListener
            currentListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    synchronized(lock) {
                        if (listener !== currentListener || generation != currentGeneration) return
                        onSensorSample(currentListener, event.sensor.type, event.timestamp, event.values)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    if (accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE) return
                    synchronized(lock) {
                        if (listener !== currentListener || generation != currentGeneration) return
                        when (sensor?.type) {
                            Sensor.TYPE_GYROSCOPE -> recentGyro.clear()
                            Sensor.TYPE_GRAVITY -> latestGravity = null
                        }
                    }
                }
            }
            listener = currentListener
            val gravityRegistered = registration.register(currentListener, Sensor.TYPE_GRAVITY)
            if (gravityRegistered) {
                // A missing or failed optional diagnostic must not disable a valid gravity stream.
                if (registration.hasSensor(Sensor.TYPE_GYROSCOPE)) {
                    runCatching { registration.register(currentListener, Sensor.TYPE_GYROSCOPE) }
                }
                return true
            }
            registration.unregister(currentListener)
            listener = null
            recentGyro.clear()
            latestGravity = null
            return false
        }
    }

    fun latestNow(
        maximumSensorAgeMs: Long = MAX_SENSOR_AGE_MS,
        shakeWindowMs: Long = SHAKE_WINDOW_MS,
    ): PhoneMountingSensorMeasurement? = synchronized(lock) {
        val nowElapsedRealtimeMs = elapsedRealtimeMs()
        if (nowElapsedRealtimeMs < 0L || maximumSensorAgeMs < 0L) {
            return@synchronized null
        }
        val gravitySample = latestGravity ?: return@synchronized null
        if (!isFresh(gravitySample.observedAtElapsedRealtimeMs, nowElapsedRealtimeMs, maximumSensorAgeMs)) {
            return@synchronized null
        }
        trimGyroscope(nowElapsedRealtimeMs - shakeWindowMs.coerceAtLeast(0L))
        val freshGyro = recentGyro.filter {
            shakeWindowMs >= 0L &&
                isFresh(it.observedAtElapsedRealtimeMs, nowElapsedRealtimeMs, maximumSensorAgeMs)
        }

        val cameraPitch = PhoneMountingSensorMath.cameraPitchFromHorizontalDegrees(
            gravityX = gravitySample.x,
            gravityY = gravitySample.y,
            gravityZ = gravitySample.z,
        ) ?: return@synchronized null
        val peakShake = freshGyro.maxOfOrNull { it.magnitudeRadiansPerSecond }
        PhoneMountingSensorMeasurement(
            angularShakeDegreesPerSecond = peakShake?.let(Math::toDegrees),
            cameraPitchFromHorizontalDegrees = cameraPitch,
        )
    }

    fun stop() {
        val current = synchronized(lock) {
            generation += 1
            listener.also {
                listener = null
                recentGyro.clear()
                latestGravity = null
            }
        }
        current?.let(registration::unregister)
    }

    override fun close() = stop()

    /** Shared with the platform callback so injected samples follow the same listener boundary. */
    internal fun onSensorSample(
        sourceListener: SensorEventListener,
        sensorType: Int,
        timestampNanos: Long,
        values: FloatArray,
    ): Unit = synchronized(lock) {
        if (listener !== sourceListener) return@synchronized
        when (sensorType) {
            Sensor.TYPE_GYROSCOPE -> recordGyroscope(timestampNanos, values)
            Sensor.TYPE_GRAVITY -> recordGravity(timestampNanos, values)
        }
    }

    private fun recordGyroscope(timestampNanos: Long, values: FloatArray) {
        if (values.size < 3) return
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)
        val observedAtMs = timestampNanos / NANOS_PER_MILLISECOND
        if (!magnitude.isFinite() || observedAtMs < 0L) return
        recentGyro.addLast(TimedGyro(observedAtMs, magnitude))
        trimGyroscope(observedAtMs - MAX_GYRO_RETENTION_MS)
    }

    private fun recordGravity(timestampNanos: Long, values: FloatArray) {
        if (values.size < 3) return
        val gravityValues = values.take(3).map { it.toDouble() }
        val observedAtMs = timestampNanos / NANOS_PER_MILLISECOND
        if (gravityValues.any { !it.isFinite() } || observedAtMs < 0L) return
        latestGravity = TimedGravity(
            observedAtElapsedRealtimeMs = observedAtMs,
            x = gravityValues[0],
            y = gravityValues[1],
            z = gravityValues[2],
        )
    }

    private fun trimGyroscope(minimumTimestampMs: Long) {
        while (
            recentGyro.isNotEmpty() &&
            recentGyro.first().observedAtElapsedRealtimeMs < minimumTimestampMs
        ) {
            recentGyro.removeFirst()
        }
    }

    private fun isFresh(observedAtMs: Long, nowMs: Long, maximumAgeMs: Long): Boolean =
        nowMs - observedAtMs in 0L..maximumAgeMs

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_SENSOR_AGE_MS = 1_000L
        const val SHAKE_WINDOW_MS = 500L
        const val MAX_GYRO_RETENTION_MS = 2_000L
    }
}

internal interface PhoneMountingSensorRegistration {
    fun hasSensor(type: Int): Boolean
    fun register(listener: SensorEventListener, type: Int): Boolean
    fun unregister(listener: SensorEventListener)
}

private class AndroidPhoneMountingSensorRegistration(context: Context) : PhoneMountingSensorRegistration {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override fun hasSensor(type: Int): Boolean = sensorManager.getDefaultSensor(type) != null

    override fun register(listener: SensorEventListener, type: Int): Boolean =
        sensorManager.getDefaultSensor(type)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        } == true

    override fun unregister(listener: SensorEventListener) = sensorManager.unregisterListener(listener)
}
