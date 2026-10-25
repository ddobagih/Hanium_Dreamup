package kr.co.hanium.dreamup.walksafe.navigation

/** SDK arrays are decoded here after the registration and original-nanosecond callback admission. */
internal object RouteCompassRawSensorConsumer {
    fun rotationVector(
        state: RouteCompassSensorState,
        generation: Int,
        values: FloatArray,
        sensorTimestampNanos: Long,
        accuracy: EarthOrientationAccuracy,
        receivedAtMs: Long,
    ): DeviceEarthOrientation? {
        val headingErrorRad = values.getOrNull(4)
        val headingErrorDegrees = if (headingErrorRad != null && !headingErrorRad.isNaN() && headingErrorRad >= 0f) {
            Math.toDegrees(headingErrorRad.toDouble()).toFloat()
        } else Float.NaN
        val observedAtMs = sensorTimestampNanos / NANOS_PER_MILLISECOND
        val rotation = RotationVectorMatrixConverter.fromSensorValues(values)
        state.onRotationVector(generation, rotation, headingErrorDegrees, observedAtMs, accuracy, receivedAtMs)
        return rotation?.let { DeviceEarthOrientation(it, observedAtMs, headingErrorDegrees, accuracy) }
    }

    fun vector(
        state: RouteCompassSensorState,
        generation: Int,
        stream: RouteCompassStream,
        values: FloatArray,
        sensorTimestampNanos: Long,
        accuracy: EarthOrientationAccuracy,
        receivedAtMs: Long,
    ): RouteCompassVectorSample {
        val sample = RouteCompassVectorSample(
            values.getOrNull(0)?.toDouble() ?: Double.NaN,
            values.getOrNull(1)?.toDouble() ?: Double.NaN,
            values.getOrNull(2)?.toDouble() ?: Double.NaN,
            sensorTimestampNanos / NANOS_PER_MILLISECOND,
            accuracy,
            sensorTimestampNanos = sensorTimestampNanos,
        )
        state.onVector(generation, stream, sample, receivedAtMs)
        return sample
    }

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
