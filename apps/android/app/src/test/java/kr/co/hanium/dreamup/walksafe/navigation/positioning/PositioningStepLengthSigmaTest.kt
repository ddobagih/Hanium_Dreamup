package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S09-40: the default step-length sigma depends on the profile state, not one fixed value. */
class PositioningStepLengthSigmaTest {
    @Test
    fun uncalibratedDefaultStepUsesTheWiderSigma() {
        val derived = stepUncertainty(PositioningCoordinator(), sigma = null)
        val wide = stepUncertainty(PositioningCoordinator(), sigma = 0.15)
        val narrow = stepUncertainty(PositioningCoordinator(), sigma = 0.08)

        assertEquals(wide, derived, 1e-12)
        assertTrue(derived > narrow)
    }

    @Test
    fun calibratedProfileKeepsTheFormerSigma() {
        fun calibrated() = PositioningCoordinator().also { assertTrue(it.restoreProfile(knownProfile())) }

        val derived = stepUncertainty(calibrated(), sigma = null)
        val narrow = stepUncertainty(calibrated(), sigma = 0.08)

        assertEquals(narrow, derived, 1e-12)
    }

    @Test
    fun restoredRecordOfUnknownCalibrationConditionUsesTheWiderSigmaUntilRecalibrated() {
        fun unknown() = PositioningCoordinator().also {
            assertTrue(it.restoreProfile(knownProfile().copy(calibrationConditionKnown = false)))
        }

        val derived = stepUncertainty(unknown(), sigma = null)
        val wide = stepUncertainty(unknown(), sigma = 0.15)
        assertEquals(wide, derived, 1e-12)

        fun recalibrated() = unknown().also { coordinator ->
            repeat(3) {
                coordinator.calibrateProfile(WalkingCalibrationSample(6.5, 10, 5_417L, trustedPositionSegment = true))
            }
            assertTrue(coordinator.currentProfile().calibrationConditionKnown)
        }
        assertEquals(stepUncertainty(recalibrated(), sigma = 0.08), stepUncertainty(recalibrated(), sigma = null), 1e-12)
    }

    @Test
    fun explicitSigmaIsStillHonored() {
        val coordinator = PositioningCoordinator().also { assertTrue(it.restoreProfile(knownProfile())) }

        val explicit = stepUncertainty(coordinator, sigma = 0.3)
        val reference = stepUncertainty(PositioningCoordinator().also { it.restoreProfile(knownProfile()) }, sigma = 0.3)

        assertEquals(reference, explicit, 1e-12)
    }

    private fun stepUncertainty(coordinator: PositioningCoordinator, sigma: Double?): Double {
        coordinator.observeGnss(
            GnssPositionObservation(
                latitude = 37.5,
                longitude = 127.0,
                horizontalAccuracyM = 1.5,
                elapsedRealtimeMs = 1_000L,
                receivedAtElapsedRealtimeMs = 1_000L,
            ),
        )
        val input = PedestrianHeadingInput(
            nowElapsedRealtimeMs = 2_000L,
            speed = WalkingSpeedObservation(1.0, 0.1, 2_000L),
            gpsCourse = HeadingObservation(90.0, 0.5, 2_000L),
            magneticTrueHeading = null,
            phoneForwardMounted = false,
        )
        val snapshot = if (sigma == null) {
            coordinator.observeStep(stepCount = 8, headingInput = input, quality = PdrStepQuality.HIGH,
                headingCoversWholeBatch = true)
        } else {
            coordinator.observeStep(stepCount = 8, headingInput = input, quality = PdrStepQuality.HIGH,
                stepLengthSigmaM = sigma, headingCoversWholeBatch = true)
        }
        return requireNotNull(snapshot.filtered).horizontalUncertaintyM
    }

    // Same values as the uncalibrated defaults so only the sigma differs between states.
    private fun knownProfile() = PersistedPedestrianMotionProfile(
        stepLengthM = 0.65,
        meanWalkingSpeedMps = 1.20,
        speedVarianceMps2 = 0.16,
        acceptedSampleCount = 12L,
    )
}
