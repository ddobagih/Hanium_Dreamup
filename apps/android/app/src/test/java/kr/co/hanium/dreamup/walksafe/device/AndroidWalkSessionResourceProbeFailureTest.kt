package kr.co.hanium.dreamup.walksafe.device

import android.os.PowerManager
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AndroidWalkSessionResourceProbeFailureTest {
    @Test
    fun batteryQueryFailurePreservesStorageAndThermalSignals() {
        val snapshot = readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { throw SecurityException("battery unavailable") },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
        )

        assertEquals(null, snapshot.batteryNotLow)
        assertEquals(true, snapshot.privateStorageAboveSystemLow)
        assertEquals(true, snapshot.thermalBelowCritical)
        assertEquals(false, snapshot.thermalThrottled)
        assertEquals(setOf(WalkSessionResourceQuery.BATTERY), snapshot.failedQueries)
        assertEquals("battery_state_query_failed", snapshot.reason)
        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.readinessStatus)
        assertNotEquals(WalkSessionReadinessStatus.READY, snapshot.readinessStatus)
    }

    @Test
    fun thermalQueryFailurePreservesBatteryAndStorageSignals() {
        val snapshot = readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { true },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { throw IllegalStateException("thermal unavailable") },
        )

        assertEquals(true, snapshot.batteryNotLow)
        assertEquals(true, snapshot.privateStorageAboveSystemLow)
        assertEquals(null, snapshot.thermalBelowCritical)
        assertEquals(null, snapshot.thermalThrottled)
        assertEquals(setOf(WalkSessionResourceQuery.THERMAL), snapshot.failedQueries)
        assertEquals("thermal_state_query_failed", snapshot.reason)
        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.readinessStatus)
        assertNotEquals(WalkSessionReadinessStatus.READY, snapshot.readinessStatus)
    }

    @Test
    fun storageQueryFailurePreservesBatteryAndThermalSignals() {
        val snapshot = readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { true },
            privateStorageAboveSystemLow = { throw SecurityException("storage unavailable") },
            thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
        )

        assertEquals(true, snapshot.batteryNotLow)
        assertEquals(null, snapshot.privateStorageAboveSystemLow)
        assertEquals(true, snapshot.thermalBelowCritical)
        assertEquals(setOf(WalkSessionResourceQuery.PRIVATE_STORAGE), snapshot.failedQueries)
        assertEquals("private_storage_state_query_failed", snapshot.reason)
        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.readinessStatus)
    }

    @Test
    fun normalQueriesAllowReadyWithoutFailureReason() {
        val snapshot = readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { true },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
        )

        assertEquals(true, snapshot.batteryNotLow)
        assertEquals(true, snapshot.privateStorageAboveSystemLow)
        assertEquals(true, snapshot.thermalBelowCritical)
        assertEquals(false, snapshot.thermalThrottled)
        assertEquals(emptySet<WalkSessionResourceQuery>(), snapshot.failedQueries)
        assertEquals("", snapshot.reason)
        assertEquals(WalkSessionReadinessStatus.READY, snapshot.readinessStatus)
    }
}
