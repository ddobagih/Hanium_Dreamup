package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unknown usage conditions cannot establish that a calibrated stride still fits this walk. */
class PositioningUnknownCalibrationConditionRegressionTest {
    private val confirmed = PedestrianCalibrationCondition("STEP_DETECTOR", "CHEST_FORWARD")

    @Test
    fun nullSavedAndCurrentConditionsUseUnverifiedSigma() =
        assertRestoredSigma(saved = null, current = null, expectedSigma = 0.15)

    @Test
    fun matchingDetectorWithUnknownMountingUsesUnverifiedSigma() {
        val partial = PedestrianCalibrationCondition("STEP_DETECTOR", null)
        assertRestoredSigma(partial, partial, 0.15)
    }

    @Test
    fun matchingMountingWithUnknownStepInputUsesUnverifiedSigma() {
        val partial = PedestrianCalibrationCondition(null, "CHEST_FORWARD")
        assertRestoredSigma(partial, partial, 0.15)
    }

    @Test
    fun matchingObjectsWithBothFieldsUnknownUseUnverifiedSigma() {
        val unknown = PedestrianCalibrationCondition(null, null)
        assertRestoredSigma(unknown, unknown, 0.15)
    }

    @Test
    fun unknownCalibrationTimeStillUsesUnverifiedSigmaWithCompleteMatchingConditions() =
        assertRestoredSigma(confirmed, confirmed, 0.15, calibrationTimeKnown = false)

    @Test
    fun matchingCompleteConditionsKeepCalibratedSigma() =
        assertRestoredSigma(confirmed, confirmed, 0.08)

    @Test
    fun anotherKnownStepInputKeepsUnverifiedSigma() =
        assertRestoredSigma(confirmed, confirmed.copy(stepInput = "STEP_COUNTER"), 0.15)

    @Test
    fun anotherKnownMountingKeepsUnverifiedSigma() =
        assertRestoredSigma(confirmed, confirmed.copy(mounting = "NECKLACE_FORWARD"), 0.15)

    @Test
    fun unknownSavedConditionAndKnownCurrentConditionKeepUnverifiedSigma() =
        assertRestoredSigma(null, confirmed, 0.15)

    @Test
    fun unknownCurrentMountingAndKnownSavedConditionKeepUnverifiedSigma() =
        assertRestoredSigma(confirmed, confirmed.copy(mounting = null), 0.15)

    @Test
    fun uncalibratedProfileKeepsDefaultsAndUnverifiedSigma() {
        val coordinator = PositioningCoordinator()
        coordinator.setCurrentCalibrationCondition(confirmed)
        assertFalse(coordinator.currentProfile().isCalibrated)
        assertNull(coordinator.snapshotProfileForPersistence())
        assertSigma(coordinator, 0.15) {
            PositioningCoordinator().also { it.setCurrentCalibrationCondition(confirmed) }
        }
    }

    @Test
    fun newlyCalibratedUnknownMountingKeepsOriginalProfileButUsesUnverifiedSigma() {
        val partial = PedestrianCalibrationCondition("STEP_DETECTOR", null)
        fun prepared() = PositioningCoordinator().also { coordinator ->
            coordinator.setCurrentCalibrationCondition(partial)
            repeat(3) { index ->
                val result = coordinator.calibrateProfile(sample())
                assertEquals(if (index < 2) MotionCalibrationStatus.ACCEPTED_WARMUP else MotionCalibrationStatus.UPDATED, result.status)
            }
            assertTrue(coordinator.currentProfile().isCalibrated)
            assertTrue(coordinator.currentProfile().calibrationConditionKnown)
            assertEquals(partial, coordinator.currentProfile().calibrationCondition)
            assertEquals(partial, requireNotNull(coordinator.snapshotProfileForPersistence()).calibrationCondition)
        }
        assertSigma(prepared(), 0.15, ::prepared)
    }

    @Test
    fun newlyCalibratedCompleteConditionsKeepCalibratedSigmaAndPersistence() {
        fun prepared() = PositioningCoordinator().also { coordinator ->
            coordinator.setCurrentCalibrationCondition(confirmed)
            repeat(3) { coordinator.calibrateProfile(sample()) }
            val persisted = requireNotNull(coordinator.snapshotProfileForPersistence())
            assertEquals(confirmed, persisted.calibrationCondition)
            assertTrue(persisted.calibrationConditionKnown)
            assertEquals(3L, persisted.acceptedSampleCount)
        }
        assertSigma(prepared(), 0.08, ::prepared)
    }

    @Test
    fun restoredUnknownCalibrationTimeRemainsUnknownDuringWarmupThenUsesFreshCompleteConditions() {
        fun prepared(samples: Int) = restored(confirmed, confirmed, calibrationTimeKnown = false).also { coordinator ->
            repeat(samples) { coordinator.calibrateProfile(sample()) }
        }
        val warming = prepared(2)
        assertFalse(warming.currentProfile().calibrationConditionKnown)
        assertEquals(confirmed, warming.currentProfile().calibrationCondition)
        assertSigma(warming, 0.15) { prepared(2) }
        val fresh = prepared(3)
        assertTrue(fresh.currentProfile().calibrationConditionKnown)
        assertEquals(confirmed, fresh.currentProfile().calibrationCondition)
        assertSigma(fresh, 0.08) { prepared(3) }
    }

    @Test
    fun explicitCallerSigmaRemainsAnOverrideForUnknownConditions() {
        val narrow = directed(restored(null, null), sigma = 0.08)
        val wide = directed(restored(null, null), sigma = 0.15)
        val narrowCovariance = covariance(narrow)
        val wideCovariance = covariance(wide)
        assertEquals(0.15 * 0.15 - 0.08 * 0.08,
            wideCovariance.eastVarianceM2 - narrowCovariance.eastVarianceM2, 1e-12)
        assertEquals(narrowCovariance.northVarianceM2, wideCovariance.northVarianceM2, 1e-12)
        assertEquals(narrowCovariance.eastNorthCovarianceM2, wideCovariance.eastNorthCovarianceM2, 1e-12)
        println("explicit_sigma_axis_variance narrow=${narrowCovariance.eastVarianceM2} wide=${wideCovariance.eastVarianceM2}")
    }

    @Test
    fun unknownConditionBatchWithoutWholeDirectionCoverageStillOnlyAddsCovariance() {
        val coordinator = restored(null, null)
        val before = initialize(coordinator)
        val result = coordinator.observeStep(3, headingInput(), PdrStepQuality.MEDIUM)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertNotNull(result.selectedHeading)
        assertEquals(requireNotNull(before.filtered).localEnu, requireNotNull(result.filtered).localEnu)
        assertTrue(covariance(result).eastVarianceM2 > covariance(before).eastVarianceM2)
        assertTrue(covariance(result).northVarianceM2 > covariance(before).northVarianceM2)
        val control = restored(null, null)
        initialize(control)
        val explicit = control.observeStep(3, headingInput(), PdrStepQuality.MEDIUM, stepLengthSigmaM = 0.15)
        assertCovarianceEquals(covariance(explicit), covariance(result))
    }

    private fun assertRestoredSigma(
        saved: PedestrianCalibrationCondition?, current: PedestrianCalibrationCondition?,
        expectedSigma: Double, calibrationTimeKnown: Boolean = true,
    ) {
        val coordinator = restored(saved, current, calibrationTimeKnown)
        val originalProfile = coordinator.currentProfile()
        val originalPersisted = coordinator.snapshotProfileForPersistence()
        assertSigma(coordinator, expectedSigma) { restored(saved, current, calibrationTimeKnown) }
        assertEquals(originalProfile, coordinator.currentProfile())
        assertEquals(originalPersisted, coordinator.snapshotProfileForPersistence())
    }

    private fun restored(
        saved: PedestrianCalibrationCondition?, current: PedestrianCalibrationCondition?,
        calibrationTimeKnown: Boolean = true,
    ) = PositioningCoordinator().also { coordinator ->
        val persisted = PersistedPedestrianMotionProfile(0.65, 1.20, 0.16, 12L, calibrationTimeKnown, saved)
        assertTrue(coordinator.restoreProfile(persisted))
        assertEquals(persisted, coordinator.snapshotProfileForPersistence())
        coordinator.setCurrentCalibrationCondition(current)
    }

    private fun assertSigma(coordinator: PositioningCoordinator, expectedSigma: Double, reference: () -> PositioningCoordinator) {
        val actual = directed(coordinator)
        val explicit = directed(reference(), expectedSigma)
        assertEquals(requireNotNull(explicit.filtered).localEnu, requireNotNull(actual.filtered).localEnu)
        val actualCovariance = covariance(actual)
        val expectedCovariance = covariance(explicit)
        println("condition_sigma expected=$expectedSigma actual_east=${actualCovariance.eastVarianceM2} expected_east=${expectedCovariance.eastVarianceM2}")
        assertCovarianceEquals(expectedCovariance, actualCovariance)
    }

    private fun directed(coordinator: PositioningCoordinator, sigma: Double? = null): PositioningSnapshot {
        initialize(coordinator)
        return coordinator.observeStep(1, headingInput(), PdrStepQuality.HIGH, stepLengthSigmaM = sigma).also { result ->
            assertEquals(PedestrianMotionUpdateDisposition.APPLIED, result.motionUpdate?.disposition)
            assertEquals(PedestrianHeadingSource.GPS_COURSE, result.selectedHeading?.source)
            assertEquals(2_000L, result.selectedHeading?.sourceObservationAtElapsedRealtimeMs)
            assertEquals(0.65, requireNotNull(result.filtered).localEnu.eastM, 1e-8)
            assertEquals(0.0, requireNotNull(result.filtered).localEnu.northM, 1e-8)
        }
    }

    private fun initialize(coordinator: PositioningCoordinator) = coordinator.observeGnss(
        GnssPositionObservation(37.5, 127.0, 1.5, 1_000L, 1_000L),
    ).also { assertEquals(GnssObservationDisposition.INITIALIZED, it.gnssUpdate?.disposition) }

    private fun headingInput() = PedestrianHeadingInput(
        2_000L, WalkingSpeedObservation(1.0, 0.1, 2_000L), HeadingObservation(90.0, 0.5, 2_000L), null, false,
    )

    private fun sample() = WalkingCalibrationSample(6.5, 10, 5_417L, trustedPositionSegment = true)

    private fun covariance(snapshot: PositioningSnapshot) = requireNotNull(requireNotNull(snapshot.filtered).covarianceEnu)

    private fun assertCovarianceEquals(expected: EnuPositionCovariance, actual: EnuPositionCovariance) {
        assertEquals("east variance", expected.eastVarianceM2, actual.eastVarianceM2, 1e-12)
        assertEquals("north variance", expected.northVarianceM2, actual.northVarianceM2, 1e-12)
        assertEquals("east/north covariance", expected.eastNorthCovarianceM2, actual.eastNorthCovarianceM2, 1e-12)
    }
}
