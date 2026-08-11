package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.ceil

data class StepCalibrationSample(
    val distanceM: Float,
    val steps: Int,
    val durationMs: Long,
)

class StepLengthEstimator(
    private val defaultStepLengthM: Float = DEFAULT_STEP_LENGTH_M,
) {
    var stepLengthM: Float = defaultStepLengthM
        private set

    fun calibrate(sample: StepCalibrationSample): Boolean {
        if (sample.steps <= 0 || sample.durationMs <= 0L) return false
        if (sample.distanceM < 4f && sample.steps < 8) return false
        val minutes = sample.durationMs / 60_000f
        val seconds = sample.durationMs / 1000f
        if (minutes <= 0f || seconds <= 0f) return false
        val spm = sample.steps / minutes
        val speedMps = sample.distanceM / seconds
        if (spm !in 50f..140f) return false
        if (speedMps !in 0.2f..2.2f) return false
        val calibrated = sample.distanceM / sample.steps
        if (calibrated !in 0.30f..1.20f) return false
        stepLengthM = calibrated
        return true
    }

    fun stepsForTrustedMetricDistance(distanceM: Float?): Int? {
        if (distanceM == null || !distanceM.isFinite() || distanceM <= 0f) return null
        return ceil((distanceM / stepLengthM).toDouble()).toInt().coerceAtLeast(1)
    }

    fun setStepLengthM(value: Float) {
        if (value in 0.30f..1.20f) {
            stepLengthM = value
        }
    }

    companion object {
        const val DEFAULT_STEP_LENGTH_M = 0.65f
    }
}
