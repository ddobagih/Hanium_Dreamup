package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S09-40 (originals/09.txt:113) and S09-43 (09.txt:116): the step-length error distinguishes
 * uncalibrated, calibrated, and "calibrated under a different usage condition". The condition is
 * stored as enum names only (step input kind, confirmed mounting) with the calibration time and
 * model version; a changed condition widens the sigma until a calibration under the new one.
 */
class PositioningCalibrationConditionTest {
    private val chestDetector = PedestrianCalibrationCondition("STEP_DETECTOR", "CHEST_FORWARD")

    @Test
    fun sameConditionKeepsTheCalibratedSigmaAndAnotherConditionOrUncalibratedWidensIt() {
        val calibrated = profile(chestDetector)
        assertEquals(sigmaDerived(0.08), stepUncertainty(calibrated, current = chestDetector), 1e-12)
        assertEquals(sigmaDerived(0.15), stepUncertainty(calibrated, current = chestDetector.copy(stepInput = "STEP_COUNTER")), 1e-12)
        assertEquals(sigmaDerived(0.15), stepUncertainty(calibrated, current = chestDetector.copy(mounting = "NECKLACE_FORWARD")), 1e-12)
        assertEquals(sigmaDerived(0.15), stepUncertainty(null, current = chestDetector), 1e-12)
        // A stored calibration without a recorded condition is "condition unknown" once one is known.
        assertEquals(sigmaDerived(0.15), stepUncertainty(profile(null), current = chestDetector), 1e-12)
    }

    @Test
    fun calibrationUnderTheCurrentConditionRecordsItAndRestoresTheCalibratedSigma() {
        val counter = chestDetector.copy(stepInput = "STEP_COUNTER")
        fun recalibrated() = PositioningCoordinator().also { coordinator ->
            assertTrue(coordinator.restoreProfile(profile(chestDetector)))
            coordinator.setCurrentCalibrationCondition(counter)
            repeat(3) {
                coordinator.calibrateProfile(WalkingCalibrationSample(6.5, 10, 5_417L, trustedPositionSegment = true))
            }
        }
        val coordinator = recalibrated()
        assertEquals(counter, coordinator.currentProfile().calibrationCondition)
        assertEquals(counter, requireNotNull(coordinator.snapshotProfileForPersistence()).calibrationCondition)
        assertEquals(
            stepUncertainty(recalibrated(), current = counter, sigma = 0.08),
            stepUncertainty(coordinator, current = counter),
            1e-12,
        )
    }

    @Test
    fun storeKeepsOnlyEnumNamesAndMigratesOlderRecordsWithAnUnknownCondition() {
        val storage = ConditionFakeProfileStorage()
        val store = AndroidPedestrianProfileStore(storage, Unit) { 1_700_000_000_000L }
        assertTrue(store.save("actor", profile(chestDetector)))
        val stored = JSONObject(storage.values.values.single())
        assertEquals("STEP_DETECTOR", stored.getString("step_input"))
        assertEquals("CHEST_FORWARD", stored.getString("mounting"))
        assertEquals(
            setOf(
                "schema_version", "model_version", "step_length_m", "mean_walking_speed_mps", "speed_variance_mps2",
                "accepted_sample_count", "last_calibrated_at_epoch_ms", "step_input", "mounting",
            ),
            stored.keys().asSequence().toSet(),
        )
        assertEquals(chestDetector, requireNotNull(store.loadResult("actor").profile).calibrationCondition)

        // A v2 record (time and model known, no condition) migrates to v3 with the condition unknown.
        val key = storage.values.keys.single()
        storage.values[key] = JSONObject(stored.toString())
            .put("schema_version", "walksafe.pedestrian-motion-profile.v2").apply { remove("step_input"); remove("mounting") }
            .toString()
        val migrated = store.loadResult("actor")
        assertEquals(PedestrianProfileLoadStatus.MIGRATED, migrated.status)
        assertEquals(1_700_000_000_000L, migrated.lastCalibratedAtEpochMs)
        assertNull(requireNotNull(migrated.profile).calibrationCondition)
        assertTrue(JSONObject(storage.values.getValue(key)).isNull("step_input"))

        // Anything but an enum-like name is not stored data of this schema: the record is removed alone.
        storage.values[key] = JSONObject(stored.toString()).put("mounting", "Kim's chest strap").toString()
        assertEquals(PedestrianProfileLoadStatus.RESET_INVALID_RECORD, store.loadResult("actor").status)
        assertTrue(storage.values.isEmpty())
    }

    private fun profile(condition: PedestrianCalibrationCondition?) = PersistedPedestrianMotionProfile(
        stepLengthM = 0.65,
        meanWalkingSpeedMps = 1.20,
        speedVarianceMps2 = 0.16,
        acceptedSampleCount = 12L,
        calibrationCondition = condition,
    )

    private fun stepUncertainty(profile: PersistedPedestrianMotionProfile?, current: PedestrianCalibrationCondition?): Double {
        val coordinator = PositioningCoordinator()
        if (profile != null) assertTrue(coordinator.restoreProfile(profile))
        return stepUncertainty(coordinator, current)
    }

    private fun stepUncertainty(coordinator: PositioningCoordinator, current: PedestrianCalibrationCondition?, sigma: Double? = null): Double {
        coordinator.setCurrentCalibrationCondition(current)
        coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 1.5, 1_000L, 1_000L))
        val input = PedestrianHeadingInput(
            nowElapsedRealtimeMs = 2_000L,
            speed = WalkingSpeedObservation(1.0, 0.1, 2_000L),
            gpsCourse = HeadingObservation(90.0, 0.5, 2_000L),
            magneticTrueHeading = null,
            phoneForwardMounted = false,
        )
        val snapshot = coordinator.observeStep(
            stepCount = 8, headingInput = input, quality = PdrStepQuality.HIGH,
            stepLengthSigmaM = sigma, headingCoversWholeBatch = true,
        )
        return requireNotNull(snapshot.filtered).horizontalUncertaintyM
    }

    /** Same step with an explicit sigma on a coordinator restored to the same (calibrated) values. */
    private fun sigmaDerived(sigma: Double): Double =
        stepUncertainty(PositioningCoordinator().also { assertTrue(it.restoreProfile(profile(null))) }, current = null, sigma = sigma)

    private class ConditionFakeProfileStorage : PedestrianProfileStorage {
        val values = linkedMapOf<String, String>()
        override fun isBlocked(): Boolean = false
        override fun contains(key: String): Boolean = key in values
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String): Boolean {
            values[key] = value
            return true
        }
        override fun remove(key: String): Boolean {
            values.remove(key)
            return true
        }
        override fun failClosed() = Unit
        override fun resetAll(): Boolean {
            values.clear()
            return true
        }
    }
}
