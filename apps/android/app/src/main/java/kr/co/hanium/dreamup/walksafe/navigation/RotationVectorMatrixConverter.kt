package kr.co.hanium.dreamup.walksafe.navigation

/** Converts Android TYPE_ROTATION_VECTOR values to the row-major matrix consumed by guidance. */
internal object RotationVectorMatrixConverter {
    fun fromSensorValues(values: FloatArray): RotationMatrix3? {
        if (values.size < 3 || values.take(3).any { !it.isFinite() }) return null
        val q1 = values[0]
        val q2 = values[1]
        val q3 = values[2]
        val q0 = if (values.size >= 4) {
            values[3].takeIf(Float::isFinite) ?: return null
        } else {
            val squaredScalar = 1f - q1 * q1 - q2 * q2 - q3 * q3
            if (squaredScalar > 0f) kotlin.math.sqrt(squaredScalar) else 0f
        }

        val rowMajor = floatArrayOf(
            1f - 2f * q2 * q2 - 2f * q3 * q3,
            2f * q1 * q2 - 2f * q3 * q0,
            2f * q1 * q3 + 2f * q2 * q0,
            2f * q1 * q2 + 2f * q3 * q0,
            1f - 2f * q1 * q1 - 2f * q3 * q3,
            2f * q2 * q3 - 2f * q1 * q0,
            2f * q1 * q3 - 2f * q2 * q0,
            2f * q2 * q3 + 2f * q1 * q0,
            1f - 2f * q1 * q1 - 2f * q2 * q2,
        )
        return RotationMatrix3.fromRowMajor(rowMajor)
    }
}
