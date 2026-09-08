package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android's thermal ladder runs NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN.
 * Blocking only at CRITICAL admitted SEVERE, where the phone has already cut its own clocks —
 * camera and inference readings taken there measure the throttling, not the device.
 *
 * SEVERE is not a verdict on the phone, though. It is the phone's state right now, so the check
 * postpones rather than fails: `thermalThrottled` was already computed and never consulted.
 */
class WalkSessionThermalMeasurementTest {
    @Test
    fun throttlingPostponesTheMeasurementInsteadOfFailingTheDevice() {
        val snapshot = snapshot(thermalThrottled = true)

        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.readinessStatus)
        assertEquals("thermal_throttled", snapshot.reason)
    }

    @Test
    fun criticalHeatStillBlocks() {
        assertEquals(
            WalkSessionReadinessStatus.UNAVAILABLE,
            snapshot(thermalBelowCritical = false, thermalThrottled = true).readinessStatus,
        )
    }

    @Test
    fun aCoolDeviceIsUnaffected() {
        assertEquals(WalkSessionReadinessStatus.READY, snapshot().readinessStatus)
    }

    @Test
    fun anUnknownThrottleStateDoesNotSilentlyPass() {
        assertEquals(
            WalkSessionReadinessStatus.PENDING,
            snapshot(thermalThrottled = null).readinessStatus,
        )
    }

    private fun snapshot(
        batteryNotLow: Boolean? = true,
        privateStorageAboveSystemLow: Boolean? = true,
        thermalBelowCritical: Boolean? = true,
        thermalThrottled: Boolean? = false,
    ) = WalkSessionDeviceResourceSnapshot(
        batteryNotLow = batteryNotLow,
        privateStorageAboveSystemLow = privateStorageAboveSystemLow,
        thermalBelowCritical = thermalBelowCritical,
        thermalThrottled = thermalThrottled,
    )
}
