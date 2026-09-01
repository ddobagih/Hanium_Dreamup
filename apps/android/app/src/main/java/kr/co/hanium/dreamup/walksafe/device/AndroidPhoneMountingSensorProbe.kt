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
    val angularShakeDegreesPerSecond: Double,
    /** Signed back-camera optical-axis angle above or below horizontal. */
    val cameraPitchFromHorizontalDegrees: Double,
)

/** Bounded pre-walk/runtime gyro and gravity observation; no device-model allowlist. */
class AndroidPhoneMountingSensorProbe(context: Context) : Closeable {
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

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val lock = Any()
    private val recentGyro = ArrayDeque<TimedGyro>()
    private var latestGravity: TimedGravity? = null
    private var listener: SensorEventListener? = null
    private var generation = 0

    fun start(): Boolean {
        synchronized(lock) {
            if (listener != null) return true
            val gyroSensor = gyroscope ?: return false
            val gravitySensor = gravity ?: return false
            val currentGeneration = ++generation
            lateinit var currentListener: SensorEventListener
            currentListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    synchronized(lock) {
                        if (listener !== currentListener || generation != currentGeneration) return
                        when (event.sensor.type) {
                            Sensor.TYPE_GYROSCOPE -> recordGyroscope(event)
                            Sensor.TYPE_GRAVITY -> recordGravity(event)
                        }
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
            val gyroRegistered = sensorManager.registerListener(
                currentListener,
                gyroSensor,
                SensorManager.SENSOR_DELAY_GAME,
            )
            val gravityRegistered = sensorManager.registerListener(
                currentListener,
                gravitySensor,
                SensorManager.SENSOR_DELAY_GAME,
            )
            if (gyroRegistered && gravityRegistered) return true
            sensorManager.unregisterListener(currentListener)
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
        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        if (nowElapsedRealtimeMs < 0L || maximumSensorAgeMs < 0L || shakeWindowMs < 0L) {
            return@synchronized null
        }
        val gravitySample = latestGravity ?: return@synchronized null
        if (!isFresh(gravitySample.observedAtElapsedRealtimeMs, nowElapsedRealtimeMs, maximumSensorAgeMs)) {
            return@synchronized null
        }
        trimGyroscope(nowElapsedRealtimeMs - shakeWindowMs)
        val freshGyro = recentGyro.filter {
            isFresh(it.observedAtElapsedRealtimeMs, nowElapsedRealtimeMs, maximumSensorAgeMs)
        }
        if (freshGyro.isEmpty()) return@synchronized null

        val cameraPitch = PhoneMountingSensorMath.cameraPitchFromHorizontalDegrees(
            gravityX = gravitySample.x,
            gravityY = gravitySample.y,
            gravityZ = gravitySample.z,
        ) ?: return@synchronized null
        val peakShake = freshGyro.maxOf { it.magnitudeRadiansPerSecond }
        PhoneMountingSensorMeasurement(
            angularShakeDegreesPerSecond = Math.toDegrees(peakShake),
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
        current?.let(sensorManager::unregisterListener)
    }

    override fun close() = stop()

    private fun recordGyroscope(event: SensorEvent) {
        if (event.values.size < 3) return
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)
        val observedAtMs = event.timestamp / NANOS_PER_MILLISECOND
        if (!magnitude.isFinite() || observedAtMs < 0L) return
        recentGyro.addLast(TimedGyro(observedAtMs, magnitude))
        trimGyroscope(observedAtMs - MAX_GYRO_RETENTION_MS)
    }

    private fun recordGravity(event: SensorEvent) {
        if (event.values.size < 3) return
        val values = event.values.take(3).map { it.toDouble() }
        val observedAtMs = event.timestamp / NANOS_PER_MILLISECOND
        if (values.any { !it.isFinite() } || observedAtMs < 0L) return
        latestGravity = TimedGravity(
            observedAtElapsedRealtimeMs = observedAtMs,
            x = values[0],
            y = values[1],
            z = values[2],
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
