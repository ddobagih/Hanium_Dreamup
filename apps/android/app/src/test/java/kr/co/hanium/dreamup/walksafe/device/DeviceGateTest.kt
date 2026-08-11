package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGateTest {
    @Test
    fun startupReadyAllowsActuatorsButRiskAlertsStillNeedFreshDepthObject() {
        val state = readyState(freshDepthObject = false)

        assertTrue(state.startupReady)
        assertTrue(state.actuatorsAllowed)
        assertFalse(state.alertsAllowed)
    }

    @Test
    fun staleDetectionBlocksAllActuators() {
        val state = readyState(freshDepthObject = true, staleReason = "source_age>800ms")

        assertFalse(state.actuatorsAllowed)
        assertFalse(state.alertsAllowed)
    }

    @Test
    fun missingDetectorBlocksStartupAndAlerts() {
        val state = readyState(detectorAvailable = false, freshDepthObject = true)

        assertFalse(state.startupReady)
        assertFalse(state.actuatorsAllowed)
        assertFalse(state.alertsAllowed)
    }

    private fun readyState(
        detectorAvailable: Boolean = true,
        freshDepthObject: Boolean,
        staleReason: String? = null,
    ): DeviceGateState {
        return DeviceGateState(
            cameraPermissionGranted = true,
            arCoreSupported = true,
            depthSupported = true,
            tfliteConfigLoaded = true,
            detectorAvailable = detectorAvailable,
            arSessionRunning = true,
            freshDepthObject = freshDepthObject,
            staleReason = staleReason,
        )
    }
}
