package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepLengthEstimatorTest {
    @Test
    fun usesDefaultStepLengthForTrustedMetricDistance() {
        val estimator = StepLengthEstimator()

        assertEquals(4, estimator.stepsForTrustedMetricDistance(2.6f))
        assertEquals(5, estimator.stepsForTrustedMetricDistance(2.7f))
    }

    @Test
    fun acceptsOnlyValidCalibrationRange() {
        val estimator = StepLengthEstimator()

        assertFalse(estimator.calibrate(StepCalibrationSample(distanceM = 2f, steps = 3, durationMs = 2_000L)))
        assertFalse(estimator.calibrate(StepCalibrationSample(distanceM = 20f, steps = 10, durationMs = 2_000L)))
        assertTrue(estimator.calibrate(StepCalibrationSample(distanceM = 6.5f, steps = 10, durationMs = 8_000L)))
        assertEquals(0.65f, estimator.stepLengthM, 0.001f)
    }
}
