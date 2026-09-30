package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * FP-035-DETAIL-04 interim (owner decision 3(b)): RAW originals and other general learning data
 * are sent only after the walk while the phone is charging. Charging means connected to power
 * ([BatteryManager.EXTRA_PLUGGED] non-zero) and charging or full; a missing or unreadable value is
 * never treated as charging (fail closed).
 */
object ChargingStatePolicy {
    fun isCharging(plugged: Int?, status: Int?): Boolean =
        plugged != null &&
            plugged > 0 &&
            (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
}

/** Reads the sticky battery broadcast on every call, so a stale value is never reused. */
class AndroidChargingStateProbe(context: Context) {
    private val appContext = context.applicationContext

    fun isCharging(): Boolean = try {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        battery != null && ChargingStatePolicy.isCharging(
            plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1).takeIf { it >= 0 },
            status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1).takeIf { it >= 0 },
        )
    } catch (_: RuntimeException) {
        false
    }
}
