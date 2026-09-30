package kr.co.hanium.dreamup.walksafe.device

import android.os.BatteryManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FP-035-DETAIL-04 interim (owner decision 3(b)): only a readable "plugged and charging/full" is charging. */
class ChargingStatePolicyTest {
    @Test
    fun pluggedAndChargingOrFullIsCharging() {
        assertTrue(ChargingStatePolicy.isCharging(BatteryManager.BATTERY_PLUGGED_AC, BatteryManager.BATTERY_STATUS_CHARGING))
        assertTrue(ChargingStatePolicy.isCharging(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_FULL))
        assertTrue(ChargingStatePolicy.isCharging(BatteryManager.BATTERY_PLUGGED_WIRELESS, BatteryManager.BATTERY_STATUS_CHARGING))
    }

    @Test
    fun unpluggedDischargingOrUnreadableIsNotCharging() {
        assertFalse(ChargingStatePolicy.isCharging(0, BatteryManager.BATTERY_STATUS_CHARGING))
        assertFalse(ChargingStatePolicy.isCharging(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        assertFalse(ChargingStatePolicy.isCharging(BatteryManager.BATTERY_PLUGGED_AC, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertFalse(ChargingStatePolicy.isCharging(null, BatteryManager.BATTERY_STATUS_CHARGING))
        assertFalse(ChargingStatePolicy.isCharging(BatteryManager.BATTERY_PLUGGED_AC, null))
        assertFalse(ChargingStatePolicy.isCharging(null, null))
    }
}
