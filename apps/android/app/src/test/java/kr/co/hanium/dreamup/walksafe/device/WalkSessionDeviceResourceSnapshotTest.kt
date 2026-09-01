package kr.co.hanium.dreamup.walksafe.device

import java.io.File
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSessionDeviceResourceSnapshotTest {
    private val probeSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidWalkSessionResourceProbe.kt",
    ).readText()

    @Test
    fun allPlatformSignalsReadyAllowTheDeviceResourceRequirement() {
        val snapshot = WalkSessionDeviceResourceSnapshot(
            batteryNotLow = true,
            privateStorageAboveSystemLow = true,
            thermalBelowCritical = true,
            thermalThrottled = true,
        )

        assertEquals(WalkSessionReadinessStatus.READY, snapshot.readinessStatus)
        assertEquals("", snapshot.reason)
        assertEquals(true, snapshot.thermalThrottled)
    }

    @Test
    fun anUnknownPlatformSignalKeepsReadinessPending() {
        val snapshot = WalkSessionDeviceResourceSnapshot(
            batteryNotLow = null,
            privateStorageAboveSystemLow = true,
            thermalBelowCritical = true,
        )

        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.readinessStatus)
        assertEquals("battery_state_pending", snapshot.reason)
    }

    @Test
    fun aPlatformCriticalSignalBlocksDeviceReadiness() {
        val snapshot = WalkSessionDeviceResourceSnapshot(
            batteryNotLow = true,
            privateStorageAboveSystemLow = true,
            thermalBelowCritical = false,
        )

        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, snapshot.readinessStatus)
        assertEquals("thermal_critical", snapshot.reason)
    }

    @Test
    fun probeUsesOnlyAndroidDefinedResourceSignalsAndObservesTheirChanges() {
        assertTrue(probeSource.contains("BatteryManager.EXTRA_BATTERY_LOW"))
        assertTrue(probeSource.contains("battery?.hasExtra("))
        assertTrue(probeSource.contains("Intent.ACTION_DEVICE_STORAGE_LOW"))
        assertTrue(probeSource.contains("Intent.ACTION_DEVICE_STORAGE_OK"))
        assertTrue(probeSource.contains("currentThermalStatus"))
        assertTrue(probeSource.contains("PowerManager.THERMAL_STATUS_CRITICAL"))
        assertTrue(probeSource.contains("PowerManager.THERMAL_STATUS_SEVERE"))
        assertTrue(probeSource.contains("thermalThrottled = thermalThrottled"))
        assertTrue(probeSource.contains("addThermalStatusListener("))
        assertTrue(probeSource.contains("removeThermalStatusListener("))
        assertTrue(probeSource.contains("appContext.unregisterReceiver(registered)"))
        assertFalse(probeSource.contains("getStorageLowBytes"))
    }

    @Test
    fun listenerRegistrationFailureIsReportedAndLeavesTheProbeRetryable() {
        val start = probeSource.substringAfter("fun start")
            .substringBefore("fun snapshot")

        assertTrue(start.contains("): Boolean"))
        assertTrue(start.contains("val receiverRegistered ="))
        assertTrue(start.contains("if (!receiverRegistered) return false"))
        assertTrue(start.contains("val thermalListenerRegistered ="))
        assertTrue(start.contains("if (!thermalListenerRegistered)"))
        assertTrue(start.contains("appContext.unregisterReceiver(resourceReceiver)"))
        assertTrue(
            start.indexOf("receiver = null") < start.lastIndexOf("return false"),
        )
        assertTrue(start.lastIndexOf("return true") > start.lastIndexOf("return false"))
    }
}
