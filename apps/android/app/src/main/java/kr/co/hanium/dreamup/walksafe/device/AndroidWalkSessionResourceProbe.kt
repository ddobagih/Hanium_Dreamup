package kr.co.hanium.dreamup.walksafe.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.Closeable
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus

data class WalkSessionDeviceResourceSnapshot(
    val batteryNotLow: Boolean?,
    val privateStorageAboveSystemLow: Boolean?,
    val thermalBelowCritical: Boolean?,
    val thermalThrottled: Boolean? = null,
) {
    val readinessStatus: WalkSessionReadinessStatus
        get() = when {
            batteryNotLow == false ||
                privateStorageAboveSystemLow == false ||
                thermalBelowCritical == false -> WalkSessionReadinessStatus.UNAVAILABLE
            batteryNotLow == null ||
                privateStorageAboveSystemLow == null ||
                thermalBelowCritical == null -> WalkSessionReadinessStatus.PENDING
            else -> WalkSessionReadinessStatus.READY
        }

    val reason: String
        get() = buildList {
            if (batteryNotLow == false) add("battery_low")
            if (privateStorageAboveSystemLow == false) add("private_storage_low")
            if (thermalBelowCritical == false) add("thermal_critical")
            if (batteryNotLow == null) add("battery_state_pending")
            if (privateStorageAboveSystemLow == null) add("private_storage_state_pending")
            if (thermalBelowCritical == null) add("thermal_state_pending")
        }.joinToString(",")

    /** Stable measurement conditions for device checks, separate from runtime resource safety. */
    val measurementReadinessStatus: WalkSessionReadinessStatus
        get() = when {
            readinessStatus != WalkSessionReadinessStatus.READY -> readinessStatus
            thermalThrottled != false -> WalkSessionReadinessStatus.PENDING
            else -> WalkSessionReadinessStatus.READY
        }

    val measurementReason: String
        get() = buildList {
            if (reason.isNotEmpty()) add(reason)
            if (thermalThrottled == true) add("thermal_throttled")
            if (thermalThrottled == null) add("thermal_throttle_state_pending")
        }.joinToString(",")
}

/**
 * Reads only Android-defined low-battery and thermal severity signals. Device-specific numeric
 * thresholds remain unset until the designated-device measurement work approves them.
 */
class AndroidWalkSessionResourceProbe(
    context: Context,
) : Closeable {
    private val appContext = context.applicationContext
    private var receiver: BroadcastReceiver? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var closed = false

    @Suppress("DEPRECATION")
    fun start(onResourceChanged: () -> Unit): Boolean {
        check(receiver == null) { "AndroidWalkSessionResourceProbe may only be started once" }
        check(!closed) { "AndroidWalkSessionResourceProbe is closed" }
        val resourceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!closed) onResourceChanged()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
            addAction(Intent.ACTION_DEVICE_STORAGE_OK)
        }
        val receiverRegistered = runCatching {
            appContext.registerReceiver(resourceReceiver, filter)
            receiver = resourceReceiver
        }.isSuccess
        if (!receiverRegistered) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener {
                if (!closed) onResourceChanged()
            }
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            val thermalListenerRegistered = powerManager != null && runCatching {
                powerManager.addThermalStatusListener(appContext.mainExecutor, listener)
                thermalListener = listener
            }.isSuccess
            if (!thermalListenerRegistered) {
                runCatching { appContext.unregisterReceiver(resourceReceiver) }
                receiver = null
                return false
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    fun snapshot(): WalkSessionDeviceResourceSnapshot {
        val battery = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val batteryNotLow = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            battery?.hasExtra(BatteryManager.EXTRA_BATTERY_LOW) == true
        ) {
            !battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, true)
        } else {
            null
        }
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.getSystemService(PowerManager::class.java)
                ?.currentThermalStatus
        } else {
            null
        }
        val thermalBelowCritical = thermalStatus?.let {
            it < PowerManager.THERMAL_STATUS_CRITICAL
        }
        val thermalThrottled = thermalStatus?.let {
            it >= PowerManager.THERMAL_STATUS_SEVERE
        }
        val files = appContext.filesDir
        val privateStorageAboveSystemLow = runCatching {
            val systemLowStorage = appContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW),
            ) != null
            !systemLowStorage && files.isDirectory && files.canWrite()
        }.getOrNull()
        return WalkSessionDeviceResourceSnapshot(
            batteryNotLow = batteryNotLow,
            privateStorageAboveSystemLow = privateStorageAboveSystemLow,
            thermalBelowCritical = thermalBelowCritical,
            thermalThrottled = thermalThrottled,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        receiver?.let { registered ->
            runCatching { appContext.unregisterReceiver(registered) }
        }
        receiver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            thermalListener?.let { listener ->
                runCatching { powerManager?.removeThermalStatusListener(listener) }
            }
        }
        thermalListener = null
    }
}
