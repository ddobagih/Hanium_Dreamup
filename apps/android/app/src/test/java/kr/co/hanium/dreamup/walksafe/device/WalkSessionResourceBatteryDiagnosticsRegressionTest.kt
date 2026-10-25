package kr.co.hanium.dreamup.walksafe.device

import android.os.BatteryManager
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkSessionResourceBatteryDiagnosticsRegressionTest {
    @Test
    fun differentQuantitativeFactsReachDiagnosticsWithoutRefreshingOrPromotingUnknowns() {
        val first = snapshot(level = 90, now = { -320_000 }, average = { -300_000 }, receipt = 101L)
        val second = snapshot(level = 35, now = { -820_000 }, average = { -800_000 }, receipt = 202L)
        assertEquals(first.batteryNotLow, second.batteryNotLow)
        assertNotEquals(first.batteryObservation, second.batteryObservation)
        val firstLine = first.resourceDiagnosticsLine()
        val secondLine = second.resourceDiagnosticsLine()
        println("S14-40 diagnostics typed A=${first.batteryObservation}")
        println("S14-40 diagnostics typed B=${second.batteryObservation}")
        println("S14-40 diagnostics line A=$firstLine")
        println("S14-40 diagnostics line B=$secondLine")
        assertNotEquals("Different typed battery facts must not collapse in the actual formatter", firstLine, secondLine)
        assertEquals(prefix +
            " battery_level=90 battery_scale=100 battery_current_now_ua=-320000 " +
            "battery_current_average_ua=-300000 battery_charge_status=DISCHARGING " +
            "battery_plugged_in=false battery_source=ANDROID_SYSTEM battery_query_received_at_ms=101 " +
            "battery_measured_at_ms=UNKNOWN battery_unknown_reasons=NONE", firstLine)
        assertEquals(prefix +
            " battery_level=35 battery_scale=100 battery_current_now_ua=-820000 " +
            "battery_current_average_ua=-800000 battery_charge_status=DISCHARGING " +
            "battery_plugged_in=false battery_source=ANDROID_SYSTEM battery_query_received_at_ms=202 " +
            "battery_measured_at_ms=UNKNOWN battery_unknown_reasons=NONE", secondLine)
        assertEquals(firstLine, first.resourceDiagnosticsLine())
        assertEquals(101L, first.batteryObservation.queryReceivedAtElapsedRealtimeMs)
        assertEquals(202L, second.batteryObservation.queryReceivedAtElapsedRealtimeMs)
        assertNull(first.batteryObservation.measuredAtElapsedRealtimeMs)
        assertNull(second.batteryObservation.measuredAtElapsedRealtimeMs)

        val charging = snapshot(level = 90, now = { 150_000 }, average = { 120_000 }, receipt = 303L,
            status = BatteryManager.BATTERY_STATUS_CHARGING, plugged = BatteryManager.BATTERY_PLUGGED_USB)
        assertEquals(prefix +
            " battery_level=90 battery_scale=100 battery_current_now_ua=150000 " +
            "battery_current_average_ua=120000 battery_charge_status=CHARGING " +
            "battery_plugged_in=true battery_source=ANDROID_SYSTEM battery_query_received_at_ms=303 " +
            "battery_measured_at_ms=UNKNOWN battery_unknown_reasons=NONE", charging.resourceDiagnosticsLine())
        val zero = snapshot(level = 90, now = { 0 }, average = { 0 }, receipt = 404L)
        assertEquals(prefix +
            " battery_level=90 battery_scale=100 battery_current_now_ua=0 " +
            "battery_current_average_ua=0 battery_charge_status=DISCHARGING " +
            "battery_plugged_in=false battery_source=ANDROID_SYSTEM battery_query_received_at_ms=404 " +
            "battery_measured_at_ms=UNKNOWN battery_unknown_reasons=NONE", zero.resourceDiagnosticsLine())
        val failedCurrent = snapshot(level = 90,
            now = { throw SecurityException("serial=ABC123 token=secret") },
            average = { -300_000 }, receipt = 505L).resourceDiagnosticsLine()
        assertEquals(prefix +
            " battery_level=90 battery_scale=100 battery_current_now_ua=UNKNOWN " +
            "battery_current_average_ua=-300000 battery_charge_status=DISCHARGING " +
            "battery_plugged_in=false battery_source=ANDROID_SYSTEM battery_query_received_at_ms=505 " +
            "battery_measured_at_ms=UNKNOWN battery_unknown_reasons=CURRENT_NOW_UNAVAILABLE+QUERY_FAILED", failedCurrent)
        assertFalse(failedCurrent.contains("ABC123"))
        assertFalse(failedCurrent.contains("serial"))
        assertFalse(failedCurrent.contains("token"))
        assertFalse(failedCurrent.contains("secret"))
    }

    private val prefix = "checked_at_ms=BATTERY:UNKNOWN+PRIVATE_STORAGE:UNKNOWN+THERMAL:UNKNOWN " +
        "failed_queries=NONE query_failures=BATTERY:UNKNOWN+PRIVATE_STORAGE:UNKNOWN+THERMAL:UNKNOWN " +
        "monitoring=NOT_STARTED failed_subscriptions=NONE monitoring_failure=UNKNOWN " +
        "subscribed_at_ms=UNKNOWN last_event_at_ms=UNKNOWN"

    private fun snapshot(
        level: Int,
        now: () -> Int?,
        average: () -> Int?,
        receipt: Long,
        status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING,
        plugged: Int = 0,
    ): WalkSessionDeviceResourceSnapshot {
        var observation = WalkSessionBatteryObservation()
        return readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = {
                observation = readWalkSessionBatteryObservation(level, 100, status, plugged,
                    now, average, 36, { receipt })
                true
            },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
            batteryObservation = { observation },
        )
    }
}
