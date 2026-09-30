package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-32: every purpose reports a block reason exactly when that purpose is not allowed. */
class DeviceGatePurposeTest {
    @Test
    fun purposeReasonAgreesWithPurposePermissionForEveryInputCombination() {
        var combinations = 0
        for (mask in 0 until (1 shl 9)) {
            fun bit(index: Int) = mask and (1 shl index) != 0
            val state = DeviceGateState(
                cameraPermissionGranted = bit(0),
                arCoreSupported = bit(1),
                depthSupported = bit(2),
                tfliteConfigLoaded = bit(3),
                detectorAvailable = bit(4),
                arSessionRunning = bit(5),
                freshDepthObject = bit(6),
                freshQualitativeObject = bit(7),
                staleReason = "source_age".takeIf { bit(8) },
            )
            DeviceGatePurpose.entries.forEach { purpose ->
                assertEquals(
                    "$purpose $state",
                    state.allows(purpose),
                    state.blockedReason(purpose) == null,
                )
            }
            combinations += 1
        }
        assertEquals(512, combinations)
    }

    @Test
    fun missingDepthObjectBlocksRiskAlertsButNotActuatorReadiness() {
        val state = ready(freshDepthObject = false, freshQualitativeObject = false)
        assertTrue(state.actuatorsAllowed)
        assertNull(state.blockedReason(DeviceGatePurpose.ACTUATOR_READINESS))
        assertFalse(state.alertsAllowed)
        assertEquals("fresh_depth_object_missing", state.blockedReason(DeviceGatePurpose.RISK_ALERT))
    }

    @Test
    fun qualitativeCautionNeverAuthorizesAReportCandidate() {
        val state = ready(freshDepthObject = false, freshQualitativeObject = true)
        assertTrue(state.allows(DeviceGatePurpose.RISK_ALERT))
        assertFalse(state.allows(DeviceGatePurpose.REPORT_CANDIDATE))
        assertEquals(
            "fresh_metric_depth_object_missing",
            state.blockedReason(DeviceGatePurpose.REPORT_CANDIDATE),
        )
    }

    @Test
    fun startupAndStaleReasonsStayFirstForEveryPurpose() {
        val stale = ready(freshDepthObject = true, freshQualitativeObject = false)
            .copy(staleReason = "source_age>800ms")
        DeviceGatePurpose.entries.forEach { purpose ->
            assertEquals("stale_detection:source_age>800ms", stale.blockedReason(purpose))
        }
        val noCamera = stale.copy(cameraPermissionGranted = false)
        DeviceGatePurpose.entries.forEach { purpose ->
            assertEquals("camera_permission_missing", noCamera.blockedReason(purpose))
        }
    }

    private fun ready(freshDepthObject: Boolean, freshQualitativeObject: Boolean) = DeviceGateState(
        cameraPermissionGranted = true,
        arCoreSupported = true,
        depthSupported = true,
        tfliteConfigLoaded = true,
        detectorAvailable = true,
        arSessionRunning = true,
        freshDepthObject = freshDepthObject,
        freshQualitativeObject = freshQualitativeObject,
    )
}
