package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocalTactileCapabilityTest {
    @Test
    fun metricArCoreTierRemainsPreferred() {
        val tier = AndroidLocalTactileCapability.resolve(
            AndroidLocalTactileCapabilityInput(
                cameraPermissionGranted = true,
                detectorAvailable = true,
                arCoreSupported = true,
                depthSupported = true,
                arSessionRunning = true,
                cameraFallbackRunning = true,
                imuFresh = true,
                tmapRouteActive = true,
            ),
        )

        assertEquals(AndroidLocalTactileTier.ARCORE_METRIC, tier)
        assertTrue(tier.metric)
        assertTrue(tier.mayCreateReportCandidates)
    }

    @Test
    fun cameraFallbackRequiresDetectorAndFreshImuButNotAnActiveRouteAndNeverReports() {
        val available = AndroidLocalTactileCapabilityInput(
            cameraPermissionGranted = true,
            detectorAvailable = true,
            arCoreSupported = false,
            depthSupported = false,
            arSessionRunning = false,
            cameraFallbackRunning = true,
            imuFresh = true,
            tmapRouteActive = false,
        )

        val tier = AndroidLocalTactileCapability.resolve(available)

        assertEquals(AndroidLocalTactileTier.CAMERA_IMU_NON_METRIC, tier)
        assertFalse(tier.metric)
        assertFalse(tier.mayCreateReportCandidates)
        assertEquals(
            AndroidLocalTactileTier.CAMERA_IMU_NON_METRIC,
            AndroidLocalTactileCapability.resolve(available.copy(tmapRouteActive = true)),
        )
        assertEquals(
            AndroidLocalTactileTier.TMAP_ONLY,
            AndroidLocalTactileCapability.resolve(available.copy(imuFresh = false)),
        )
        assertEquals(
            AndroidLocalTactileTier.TMAP_ONLY,
            AndroidLocalTactileCapability.resolve(available.copy(detectorAvailable = false)),
        )
        assertEquals(
            AndroidLocalTactileTier.TMAP_ONLY,
            AndroidLocalTactileCapability.resolve(available.copy(cameraPermissionGranted = false)),
        )
    }
}
