package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianMotionProfileTest {
    @Test
    fun keepsDefaultsAndRefusesPersistenceUntilMinimumSamples() {
        val profile = PedestrianMotionProfile()

        val first = profile.calibrate(sample(distanceM = 7.0, steps = 10, durationMs = 7_000L))
        val second = profile.calibrate(sample(distanceM = 7.2, steps = 10, durationMs = 7_000L))

        assertEquals(MotionCalibrationStatus.ACCEPTED_WARMUP, first.status)
        assertEquals(MotionCalibrationStatus.ACCEPTED_WARMUP, second.status)
        assertEquals(0.65, second.snapshot.stepLengthM, 0.0)
        assertEquals(1.20, second.snapshot.meanWalkingSpeedMps, 0.0)
        assertEquals(0.16, second.snapshot.speedVarianceMps2, 0.0)
        assertEquals(2L, second.snapshot.acceptedSampleCount)
        assertFalse(second.snapshot.isCalibrated)
        assertNull(profile.snapshotForPersistence())
    }

    @Test
    fun usesRecentMedianAndBoundsEachEwmaUpdate() {
        val profile = PedestrianMotionProfile(
            PedestrianMotionProfileConfig(
                ewmaAlpha = 1.0,
                maximumStepLengthChangePerUpdateM = 0.02,
                maximumWalkingSpeedChangePerUpdateMps = 0.10,
            ),
        )
        repeat(3) {
            profile.calibrate(sample(distanceM = 8.0, steps = 10, durationMs = 5_000L))
        }

        val calibrated = profile.currentSnapshot()
        assertEquals(0.67, calibrated.stepLengthM, 1e-12)
        assertEquals(1.30, calibrated.meanWalkingSpeedMps, 1e-12)
        assertTrue(calibrated.isCalibrated)
        assertNotNull(profile.snapshotForPersistence())

        val withIsolatedOutlier = profile.calibrate(
            sample(distanceM = 12.0, steps = 10, durationMs = 4_000L),
        ).snapshot

        assertEquals(0.69, withIsolatedOutlier.stepLengthM, 1e-12)
        assertEquals(1.40, withIsolatedOutlier.meanWalkingSpeedMps, 1e-12)
        assertEquals(4L, withIsolatedOutlier.acceptedSampleCount)
    }

    @Test
    fun rejectsUntrustedCorruptAndImplausibleSamplesWithoutChangingCalibration() {
        val profile = PedestrianMotionProfile()
        val samples = listOf(
            sample(7.0, 10, 7_000L, trusted = false),
            sample(Double.NaN, 10, 7_000L),
            sample(7.0, 0, 7_000L),
            sample(20.0, 10, 7_000L),
            sample(7.0, 10, 1_000L),
        )

        val results = samples.map(profile::calibrate)

        assertTrue(results.all { it.status == MotionCalibrationStatus.REJECTED })
        assertEquals(MotionCalibrationRejectionReason.UNTRUSTED_POSITION, results[0].rejectionReason)
        assertEquals(MotionCalibrationRejectionReason.INVALID_DISTANCE, results[1].rejectionReason)
        assertEquals(MotionCalibrationRejectionReason.INVALID_STEP_COUNT, results[2].rejectionReason)
        assertEquals(MotionCalibrationRejectionReason.IMPLAUSIBLE_STEP_LENGTH, results[3].rejectionReason)
        assertEquals(MotionCalibrationRejectionReason.IMPLAUSIBLE_WALKING_SPEED, results[4].rejectionReason)
        val snapshot = profile.currentSnapshot()
        assertEquals(0L, snapshot.acceptedSampleCount)
        assertEquals(5L, snapshot.rejectedSampleCount)
        assertFalse(snapshot.isCalibrated)
        assertNull(profile.snapshotForPersistence())
    }

    @Test
    fun restoresOnlyValidatedPersistedProfilesAndKeepsRestoreAtomic() {
        val profile = PedestrianMotionProfile()
        val invalid = PersistedPedestrianMotionProfile(
            stepLengthM = Double.NaN,
            meanWalkingSpeedMps = 1.1,
            speedVarianceMps2 = 0.09,
            acceptedSampleCount = 10L,
        )
        val insufficient = PersistedPedestrianMotionProfile(
            stepLengthM = 0.61,
            meanWalkingSpeedMps = 1.1,
            speedVarianceMps2 = 0.09,
            acceptedSampleCount = 1L,
        )

        assertFalse(profile.restore(invalid))
        assertFalse(profile.restore(insufficient))
        assertEquals(0L, profile.currentSnapshot().acceptedSampleCount)

        val persisted = PersistedPedestrianMotionProfile(
            stepLengthM = 0.61,
            meanWalkingSpeedMps = 1.1,
            speedVarianceMps2 = 0.09,
            acceptedSampleCount = 12L,
        )
        assertTrue(profile.restore(persisted))
        val restored = profile.currentSnapshot()
        assertEquals(0.61, restored.stepLengthM, 0.0)
        assertEquals(1.1, restored.meanWalkingSpeedMps, 0.0)
        assertEquals(0.09, restored.speedVarianceMps2, 0.0)
        assertEquals(12L, restored.acceptedSampleCount)
        assertTrue(restored.isCalibrated)
        assertEquals(persisted, profile.snapshotForPersistence())
    }

    @Test
    fun derivesFiniteBoundedWalkingProcessAccelerationSigmaFromSpeedVariance() {
        val profile = PedestrianMotionProfile()
        val defaults = profile.currentSnapshot()

        assertEquals(1.5, defaults.walkingProcessAccelerationSigmaMps2, 1e-12)
        repeat(3) {
            profile.calibrate(sample(distanceM = 7.0, steps = 10, durationMs = 7_000L))
        }
        val calibrated = profile.currentSnapshot()

        assertTrue(calibrated.walkingProcessAccelerationSigmaMps2.isFinite())
        assertTrue(calibrated.walkingProcessAccelerationSigmaMps2 in 0.5..3.0)
        assertTrue(calibrated.walkingProcessAccelerationSigmaMps2 < defaults.walkingProcessAccelerationSigmaMps2)
    }

    private fun sample(
        distanceM: Double,
        steps: Int,
        durationMs: Long,
        trusted: Boolean = true,
    ) = WalkingCalibrationSample(
        distanceM = distanceM,
        stepCount = steps,
        durationMs = durationMs,
        trustedPositionSegment = trusted,
    )
}
