package kr.co.hanium.dreamup.walksafe.device

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
import android.os.SystemClock
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measurement sources for the FP-021 pre-detection gate. [CameraFrameQualityPolicy] owns the
 * thresholds and the pass/fail decision; this file only produces the four numbers it reads, and
 * every producer returns null rather than a guess so an unreadable sensor fails the gate closed.
 */

private const val DARK_LUMA_MAX = 24
private const val LUMA_SAMPLES_PER_AXIS = 32
private const val RADIANS_TO_DEGREES = 57.29577951308232
private const val MIN_GRAVITY_MAGNITUDE = 4.0
private const val MAX_GRAVITY_MAGNITUDE = 15.0
private const val GRAVITY_SMOOTHING = 0.2f
private const val DEFAULT_STALE_AFTER_MS = 500L

data class CameraLumaStats(
    val normalizedBrightness: Double,
    val occludedFraction: Double,
)

/**
 * Mean luma and near-black share of one frame.
 *
 * ponytail: occlusion is approximated by the share of near-black samples, which catches a hand,
 * a pocket or cloth over the lens but not a bright smear. Swap in a variance or edge-density
 * measure if device trials show blurred-but-bright occlusion passing the gate.
 */
fun cameraLumaStatsOf(lumaSamples: IntArray): CameraLumaStats? {
    if (lumaSamples.isEmpty()) return null
    var total = 0L
    var dark = 0
    for (sample in lumaSamples) {
        val clamped = sample.coerceIn(0, 255)
        total += clamped.toLong()
        if (clamped <= DARK_LUMA_MAX) dark += 1
    }
    return CameraLumaStats(
        normalizedBrightness = total.toDouble() / (lumaSamples.size.toDouble() * 255.0),
        occludedFraction = dark.toDouble() / lumaSamples.size.toDouble(),
    )
}

/** Reads the Y plane on a coarse grid so the gate costs a bounded number of samples per frame. */
fun sampleCameraLumaStats(image: Image, samplesPerAxis: Int = LUMA_SAMPLES_PER_AXIS): CameraLumaStats? {
    if (samplesPerAxis <= 0) return null
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return null
    val plane = image.planes?.firstOrNull() ?: return null
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    if (rowStride <= 0 || pixelStride <= 0) return null
    val buffer = plane.buffer?.duplicate() ?: return null

    val stepX = max(1, width / samplesPerAxis)
    val stepY = max(1, height / samplesPerAxis)
    val samples = ArrayList<Int>(samplesPerAxis * samplesPerAxis)
    var y = 0
    while (y < height) {
        val rowOffset = y * rowStride
        var x = 0
        while (x < width) {
            val index = rowOffset + x * pixelStride
            if (index < 0 || index >= buffer.limit()) break
            samples.add(buffer.get(index).toInt() and 0xff)
            x += stepX
        }
        y += stepY
    }
    return cameraLumaStatsOf(samples.toIntArray())
}

/** Gyroscope magnitude in degrees per second, or null when the reading is not usable. */
fun angularShakeDegreesPerSecond(x: Float, y: Float, z: Float): Double? {
    val magnitude = sqrt(
        (x.toDouble() * x.toDouble()) + (y.toDouble() * y.toDouble()) + (z.toDouble() * z.toDouble()),
    )
    if (!magnitude.isFinite()) return null
    return magnitude * RADIANS_TO_DEGREES
}

/**
 * Elevation of the rear camera above the horizon, in degrees, derived from the gravity vector.
 * 0 means the camera looks at the horizon, negative means it looks down at the pavement.
 *
 * Returns null while the device is not close to rest under gravity — during a fall, a hard jolt or
 * a bad reading the angle is meaningless and the gate must not be handed a number.
 */
fun cameraMountPitchDegrees(ax: Float, ay: Float, az: Float): Double? {
    val magnitude = sqrt(
        (ax.toDouble() * ax.toDouble()) + (ay.toDouble() * ay.toDouble()) + (az.toDouble() * az.toDouble()),
    )
    if (!magnitude.isFinite() || magnitude < MIN_GRAVITY_MAGNITUDE || magnitude > MAX_GRAVITY_MAGNITUDE) {
        return null
    }
    return asin((-az.toDouble() / magnitude).coerceIn(-1.0, 1.0)) * RADIANS_TO_DEGREES
}

/**
 * Latest gyroscope shake and mount pitch for the FP-021 pre-detection gate.
 *
 * Readings expire on their own so a stopped or unregistered sensor reports null instead of pinning
 * the last good value, and a device with no gyroscope or accelerometer never passes the gate.
 */
class DeviceMotionMonitor(
    private val sensorManager: SensorManager?,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : SensorEventListener {

    private data class Reading(val value: Double, val atMs: Long)

    @Volatile
    private var shake: Reading? = null

    @Volatile
    private var pitch: Reading? = null

    /** Touched only on the sensor delivery thread. */
    private var smoothedGravity: FloatArray? = null

    val angularShakeDegreesPerSecond: Double? get() = shake.freshValue()

    val mountPitchDegrees: Double? get() = pitch.freshValue()

    fun start() {
        val manager = sensorManager ?: return
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensor ->
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        shake = null
        pitch = null
        smoothedGravity = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (values.size < 3) return
        when (event.sensor?.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val degreesPerSecond = angularShakeDegreesPerSecond(values[0], values[1], values[2])
                shake = degreesPerSecond?.let { Reading(it, elapsedRealtimeMs()) }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val gravity = smoothGravity(values)
                val degrees = cameraMountPitchDegrees(gravity[0], gravity[1], gravity[2])
                pitch = degrees?.let { Reading(it, elapsedRealtimeMs()) }
            }

            else -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Walking shakes the accelerometer, so the gravity estimate is low-passed before the angle. */
    private fun smoothGravity(values: FloatArray): FloatArray {
        val previous = smoothedGravity
        val next = if (previous == null) {
            floatArrayOf(values[0], values[1], values[2])
        } else {
            FloatArray(3) { index ->
                previous[index] + GRAVITY_SMOOTHING * (values[index] - previous[index])
            }
        }
        smoothedGravity = next
        return next
    }

    private fun Reading?.freshValue(): Double? {
        val reading = this ?: return null
        val ageMs = elapsedRealtimeMs() - reading.atMs
        return if (ageMs in 0L..staleAfterMs) reading.value else null
    }
}
