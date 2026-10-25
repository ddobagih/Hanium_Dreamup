package kr.co.hanium.dreamup.walksafe.device

import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLumaOcclusionEvidenceTest {
    @Test
    fun elevenPercentDarkSceneRegionDoesNotEstablishLensOcclusion() {
        val measurement = scene(nearBlackPixels = 11)

        assertTrue(measurement.normalizedBrightness > PROFILE.minimumNormalizedBrightness)
        assertEquals(0.11, measurement.nearBlackFraction, 0.000_001)
        assertPass(assess(observation(measurement)))
    }

    @Test
    fun halfShadowedSceneWithSufficientMeanBrightnessIsNotBlockedAsLensOcclusion() {
        val measurement = scene(nearBlackPixels = 50)

        assertTrue(measurement.normalizedBrightness > PROFILE.minimumNormalizedBrightness)
        assertEquals(0.50, measurement.nearBlackFraction, 0.000_001)
        assertPass(assess(observation(measurement)))
    }

    @Test
    fun uniformBrightSceneNeedsNoInventedOcclusionMeasurement() {
        val measurement = scene(nearBlackPixels = 0)

        assertEquals(0.0, measurement.nearBlackFraction, 0.000_001)
        assertPass(assess(observation(measurement)))
    }

    @Test
    fun blackAndUnderexposedScenesStillFailTheRequiredBrightnessCheck() {
        listOf(0, 20, 50).forEach { luma ->
            val assessment = assess(observation(uniformScene(luma)))

            assertEquals("luma=$luma", EnvironmentEvidenceStatus.FAIL, assessment.status)
            assertEquals(CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE, assessment.reason)
        }
    }

    @Test
    fun explicitOcclusionEvidenceStillUsesTheExistingInclusiveThreshold() {
        val bright = observation(scene(nearBlackPixels = 0))

        assertPass(assess(bright.copy(occludedFraction = 0.10)))
        val blocked = assess(bright.copy(occludedFraction = 0.11))
        assertEquals(EnvironmentEvidenceStatus.FAIL, blocked.status)
        assertEquals(CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE, blocked.reason)
    }

    @Test
    fun invalidExplicitOcclusionEvidenceCannotPass() {
        val bright = observation(scene(nearBlackPixels = 0))
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01).forEach { fraction ->
            val assessment = assess(bright.copy(occludedFraction = fraction))

            assertEquals(EnvironmentEvidenceStatus.UNKNOWN, assessment.status)
            assertEquals(CameraFrameQualityReason.INVALID_MEASUREMENT, assessment.reason)
        }
    }

    @Test
    fun diagnosticNearBlackValuesDoNotChangePassingRequiredMeasurements() {
        val bright = observation(scene(nearBlackPixels = 0))
        listOf(null, 0.0).forEach { physicalOcclusion ->
            DIAGNOSTIC_VALUES.forEach { fraction ->
                assertPass(assess(bright.copy(
                    occludedFraction = physicalOcclusion,
                    nearBlackFraction = fraction,
                )))
            }
        }
    }

    @Test
    fun diagnosticNearBlackValuesCannotOverrideExplicitOcclusionOrDarkness() {
        val physicallyObstructed = observation(scene(nearBlackPixels = 0)).copy(occludedFraction = 0.11)
        val dark = observation(uniformScene(0)).copy(occludedFraction = 0.0)
        DIAGNOSTIC_VALUES.forEach { fraction ->
            val occlusionFailure = assess(physicallyObstructed.copy(nearBlackFraction = fraction))
            val brightnessFailure = assess(dark.copy(nearBlackFraction = fraction))

            assertEquals(EnvironmentEvidenceStatus.FAIL, occlusionFailure.status)
            assertEquals(CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE, occlusionFailure.reason)
            assertEquals(EnvironmentEvidenceStatus.FAIL, brightnessFailure.status)
            assertEquals(CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE, brightnessFailure.reason)
        }
    }

    @Test
    fun absentOcclusionEvidenceCannotReplaceRequiredFrameBrightnessMountOrFreshness() {
        val bright = observation(scene(nearBlackPixels = 0))
        listOf(
            bright.copy(frameAvailable = false) to CameraFrameQualityReason.FRAME_UNAVAILABLE,
            bright.copy(frameAvailable = null) to CameraFrameQualityReason.FRAME_STATE_UNKNOWN,
            bright.copy(normalizedBrightness = null) to CameraFrameQualityReason.INVALID_MEASUREMENT,
            bright.copy(normalizedBrightness = Double.NaN) to CameraFrameQualityReason.INVALID_MEASUREMENT,
            bright.copy(mountPitchDegrees = null) to CameraFrameQualityReason.INVALID_MEASUREMENT,
            bright.copy(mountPitchDegrees = 46.0) to CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY,
            bright.copy(observedAtElapsedRealtimeMs = NOW_MS - 5_001L) to
                CameraFrameQualityReason.STALE_OR_INVALID_TIMESTAMP,
            bright.copy(observedAtElapsedRealtimeMs = NOW_MS + 1L) to
                CameraFrameQualityReason.STALE_OR_INVALID_TIMESTAMP,
            bright.copy(epoch = WalkRuntimeEpoch("another", 1L)) to CameraFrameQualityReason.EPOCH_MISMATCH,
        ).forEach { (observation, reason) ->
            val assessment = assess(observation)

            assertTrue(assessment.status != EnvironmentEvidenceStatus.PASS)
            assertEquals(reason, assessment.reason)
        }
    }

    private fun scene(nearBlackPixels: Int): CameraLumaMeasurement {
        val pixels = ByteArray(100) { index -> if (index < nearBlackPixels) 10 else 160.toByte() }
        return measure(pixels)
    }

    private fun uniformScene(luma: Int) = measure(ByteArray(100) { luma.toByte() })

    private fun measure(pixels: ByteArray) = requireNotNull(CameraLumaMeasurementPolicy.measure(
        buffer = ByteBuffer.wrap(pixels),
        width = 10,
        height = 10,
        rowStride = 10,
        pixelStride = 1,
    ))

    private fun observation(measurement: CameraLumaMeasurement) = CameraFrameQualityObservation(
        epoch = EPOCH,
        observedAtElapsedRealtimeMs = NOW_MS,
        frameAvailable = true,
        normalizedBrightness = measurement.normalizedBrightness,
        // Near-black scene pixels are not an observation of physical lens coverage.
        occludedFraction = null,
        angularShakeDegreesPerSecond = null,
        mountPitchDegrees = 0.0,
        nearBlackFraction = measurement.nearBlackFraction,
    )

    private fun assess(observation: CameraFrameQualityObservation) = CameraFrameQualityPolicy.assess(
        currentEpoch = EPOCH,
        nowElapsedRealtimeMs = NOW_MS,
        observation = observation,
        approvedProfile = PROFILE,
    )

    private fun assertPass(assessment: CameraFrameQualityAssessment) {
        assertEquals(EnvironmentEvidenceStatus.PASS, assessment.status)
        assertEquals(CameraFrameQualityReason.PASSED, assessment.reason)
    }

    private companion object {
        const val NOW_MS = 10_000L
        val EPOCH = WalkRuntimeEpoch("shadow-evidence", 1L)
        val DIAGNOSTIC_VALUES = listOf(
            null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1.0, 0.0, 0.11, 0.50, 1.0, 2.0,
        )
        val PROFILE = ApprovedCameraFrameQualityProfile(
            profileId = "camera-shadow-test-only",
            minimumNormalizedBrightness = 0.20,
            maximumOccludedFraction = 0.10,
            maximumAngularShakeDegreesPerSecond = 45.0,
            minimumMountPitchDegrees = -45.0,
            maximumMountPitchDegrees = 45.0,
            maximumEvidenceAgeMs = 5_000L,
        )
    }
}
