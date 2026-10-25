package kr.co.hanium.dreamup.walksafe.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.io.Closeable
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus

enum class WalkSessionResourceQuery {
    BATTERY,
    PRIVATE_STORAGE,
    THERMAL,
}

/** Event subscriptions that keep the polled values current between explicit rechecks. */
enum class WalkSessionResourceSubscription {
    RESOURCE_BROADCASTS,
    THERMAL_LISTENER,
}

enum class WalkSessionResourceMonitoringState {
    NOT_STARTED,
    SUBSCRIBED,
    SUBSCRIPTION_FAILED,
    CLOSED,
}

/**
 * S14-43: health of the resource monitor itself, kept apart from the resource values. A failed or
 * closed monitor is never treated as "normal"; not-started keeps the existing poll-only reads.
 */
data class WalkSessionResourceMonitoringHealth(
    val state: WalkSessionResourceMonitoringState = WalkSessionResourceMonitoringState.NOT_STARTED,
    val failedSubscriptions: Set<WalkSessionResourceSubscription> = emptySet(),
    /** Exception type only; messages may carry device details and are never kept. */
    val failureReason: String? = null,
    val subscribedAtElapsedRealtimeMs: Long? = null,
    val lastEventAtElapsedRealtimeMs: Long? = null,
) {
    val blocksReadiness: Boolean
        get() = state == WalkSessionResourceMonitoringState.SUBSCRIPTION_FAILED ||
            state == WalkSessionResourceMonitoringState.CLOSED

    val reason: String
        get() = when (state) {
            WalkSessionResourceMonitoringState.SUBSCRIPTION_FAILED -> "resource_monitoring_subscription_failed"
            WalkSessionResourceMonitoringState.CLOSED -> "resource_monitoring_closed"
            else -> ""
        }
}

/** Records subscription results and event receipt; the probe owns one instance per lifetime. */
internal class WalkSessionResourceMonitoringTracker(
    private val elapsedRealtimeMs: () -> Long,
) {
    @Volatile
    var health: WalkSessionResourceMonitoringHealth = WalkSessionResourceMonitoringHealth()
        private set

    @Synchronized
    fun subscribed() {
        if (health.state == WalkSessionResourceMonitoringState.CLOSED) return
        health = WalkSessionResourceMonitoringHealth(
            state = WalkSessionResourceMonitoringState.SUBSCRIBED,
            subscribedAtElapsedRealtimeMs = elapsedRealtimeMs(),
        )
    }

    @Synchronized
    fun subscriptionFailed(failed: Set<WalkSessionResourceSubscription>, error: Throwable?) {
        if (health.state == WalkSessionResourceMonitoringState.CLOSED) return
        health = WalkSessionResourceMonitoringHealth(
            state = WalkSessionResourceMonitoringState.SUBSCRIPTION_FAILED,
            failedSubscriptions = failed,
            failureReason = error?.javaClass?.simpleName?.ifEmpty { null } ?: "subscription_unavailable",
        )
    }

    @Synchronized
    fun eventReceived() {
        if (health.state != WalkSessionResourceMonitoringState.SUBSCRIBED) return
        health = health.copy(lastEventAtElapsedRealtimeMs = elapsedRealtimeMs())
    }

    @Synchronized
    fun closed() {
        health = health.copy(state = WalkSessionResourceMonitoringState.CLOSED)
    }
}

enum class WalkSessionBatteryObservationSource {
    UNKNOWN,
    ANDROID_SYSTEM,
}

enum class WalkSessionBatteryChargeStatus {
    UNKNOWN,
    CHARGING,
    DISCHARGING,
    NOT_CHARGING,
    FULL,
}

enum class WalkSessionBatteryUnknownReason {
    NO_OBSERVATION,
    LEVEL_UNAVAILABLE,
    CURRENT_NOW_UNAVAILABLE,
    CURRENT_AVERAGE_UNAVAILABLE,
    CHARGE_STATUS_UNAVAILABLE,
    POWER_CONNECTION_UNAVAILABLE,
    RECEIPT_TIME_UNAVAILABLE,
    QUERY_FAILED,
}

/**
 * Independent sticky extras and current-property reads, without a common physical sample time.
 * Raw facts only; these do not set readiness, a drain rate, or a remaining runtime.
 */
data class WalkSessionBatteryObservation(
    /** ACTION_BATTERY_CHANGED level/scale, kept together only when 0 <= level <= scale > 0. */
    val level: Int? = null,
    val scale: Int? = null,
    /** CURRENT_NOW/AVERAGE in microamperes: positive charging, negative discharging. */
    val currentNowMicroamps: Int? = null,
    /** The average period belongs to the device fuel gauge, not an application window. */
    val currentAverageMicroamps: Int? = null,
    val chargeStatus: WalkSessionBatteryChargeStatus = WalkSessionBatteryChargeStatus.UNKNOWN,
    val pluggedIn: Boolean? = null,
    val source: WalkSessionBatteryObservationSource = WalkSessionBatteryObservationSource.UNKNOWN,
    /** Receipt of this completed query, including a repeated sticky read; never measurement time. */
    val queryReceivedAtElapsedRealtimeMs: Long? = null,
    val unknownReasons: Set<WalkSessionBatteryUnknownReason> = setOf(WalkSessionBatteryUnknownReason.NO_OBSERVATION),
) {
    /** Neither the sticky extras nor the integer current properties provide a sample timestamp. */
    val measuredAtElapsedRealtimeMs: Long? get() = null
}

internal fun readWalkSessionBatteryObservation(
    level: Int?,
    scale: Int?,
    status: Int?,
    plugged: Int?,
    currentNowMicroamps: () -> Int?,
    currentAverageMicroamps: () -> Int?,
    targetSdkVersion: Int,
    elapsedRealtimeMs: () -> Long,
): WalkSessionBatteryObservation {
    val reasons = linkedSetOf<WalkSessionBatteryUnknownReason>()
    fun current(read: () -> Int?, unavailable: WalkSessionBatteryUnknownReason): Int? {
        val result = runCatching(read)
        if (result.isFailure) reasons.add(WalkSessionBatteryUnknownReason.QUERY_FAILED)
        // getIntProperty reports errors as zero only for legacy targets; current target 36 uses MIN.
        // A valid zero at target >= P remains a raw value and is never inferred to mean no drain.
        return result.getOrNull()?.takeUnless {
            it == Int.MIN_VALUE || (targetSdkVersion < Build.VERSION_CODES.P && it == 0)
        }.also { if (it == null) reasons.add(unavailable) }
    }
    val currentNow = current(currentNowMicroamps, WalkSessionBatteryUnknownReason.CURRENT_NOW_UNAVAILABLE)
    val currentAverage = current(currentAverageMicroamps, WalkSessionBatteryUnknownReason.CURRENT_AVERAGE_UNAVAILABLE)
    val receipt = runCatching(elapsedRealtimeMs).getOrNull()?.takeIf { it >= 0L }
    if (receipt == null) reasons.add(WalkSessionBatteryUnknownReason.RECEIPT_TIME_UNAVAILABLE)
    val validLevel = level != null && scale != null && scale > 0 && level in 0..scale
    if (!validLevel) reasons.add(WalkSessionBatteryUnknownReason.LEVEL_UNAVAILABLE)
    val chargeStatus = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> WalkSessionBatteryChargeStatus.CHARGING
        BatteryManager.BATTERY_STATUS_DISCHARGING -> WalkSessionBatteryChargeStatus.DISCHARGING
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> WalkSessionBatteryChargeStatus.NOT_CHARGING
        BatteryManager.BATTERY_STATUS_FULL -> WalkSessionBatteryChargeStatus.FULL
        else -> WalkSessionBatteryChargeStatus.UNKNOWN
    }
    if (chargeStatus == WalkSessionBatteryChargeStatus.UNKNOWN) {
        reasons.add(WalkSessionBatteryUnknownReason.CHARGE_STATUS_UNAVAILABLE)
    }
    val pluggedIn = plugged?.takeIf { it >= 0 }?.let { it != 0 }
    if (pluggedIn == null) reasons.add(WalkSessionBatteryUnknownReason.POWER_CONNECTION_UNAVAILABLE)
    val hasFact = validLevel || currentNow != null || currentAverage != null ||
        chargeStatus != WalkSessionBatteryChargeStatus.UNKNOWN || pluggedIn != null
    return WalkSessionBatteryObservation(
        level = level.takeIf { validLevel },
        scale = scale.takeIf { validLevel },
        currentNowMicroamps = currentNow,
        currentAverageMicroamps = currentAverage,
        chargeStatus = chargeStatus,
        pluggedIn = pluggedIn,
        source = if (hasFact) WalkSessionBatteryObservationSource.ANDROID_SYSTEM else WalkSessionBatteryObservationSource.UNKNOWN,
        queryReceivedAtElapsedRealtimeMs = receipt,
        unknownReasons = reasons.toSet(),
    )
}

data class WalkSessionDeviceResourceSnapshot(
    val batteryNotLow: Boolean?,
    val privateStorageAboveSystemLow: Boolean?,
    val thermalBelowCritical: Boolean?,
    val thermalThrottled: Boolean? = null,
    val failedQueries: Set<WalkSessionResourceQuery> = emptySet(),
    val monitoring: WalkSessionResourceMonitoringHealth = WalkSessionResourceMonitoringHealth(),
    /** elapsedRealtime of each query attempt in this poll, including failed attempts. */
    val checkedAtElapsedRealtimeMs: Map<WalkSessionResourceQuery, Long> = emptyMap(),
    /** Exception type per failed query; the message is never kept. */
    val queryFailureReasons: Map<WalkSessionResourceQuery, String> = emptyMap(),
    val batteryObservation: WalkSessionBatteryObservation = WalkSessionBatteryObservation(),
) {
    /** Resource evidence only; the caller supplies the epoch and monotonic source/receipt times. */
    fun toRuntimeSafetyObservation(
        epoch: WalkRuntimeEpoch,
        observedAtElapsedRealtimeMs: Long,
        receivedAtElapsedRealtimeMs: Long,
    ): WalkRuntimeSafetyObservation = WalkRuntimeSafetyObservation(
        epoch = epoch,
        observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
        receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs,
        batteryCritical = batteryNotLow?.not(),
        storageCritical = privateStorageAboveSystemLow?.not(),
        thermalCritical = thermalBelowCritical?.not(),
    )

    val readinessStatus: WalkSessionReadinessStatus
        get() = when {
            batteryNotLow == false ||
                privateStorageAboveSystemLow == false ||
                thermalBelowCritical == false -> WalkSessionReadinessStatus.UNAVAILABLE
            batteryNotLow == null ||
                privateStorageAboveSystemLow == null ||
                thermalBelowCritical == null ||
                monitoring.blocksReadiness -> WalkSessionReadinessStatus.PENDING
            else -> WalkSessionReadinessStatus.READY
        }

    val reason: String
        get() = buildList {
            if (batteryNotLow == false) add("battery_low")
            if (privateStorageAboveSystemLow == false) add("private_storage_low")
            if (thermalBelowCritical == false) add("thermal_critical")
            if (batteryNotLow == null) add(
                if (WalkSessionResourceQuery.BATTERY in failedQueries) {
                    "battery_state_query_failed"
                } else {
                    "battery_state_pending"
                },
            )
            if (privateStorageAboveSystemLow == null) add(
                if (WalkSessionResourceQuery.PRIVATE_STORAGE in failedQueries) {
                    "private_storage_state_query_failed"
                } else {
                    "private_storage_state_pending"
                },
            )
            if (thermalBelowCritical == null) add(
                if (WalkSessionResourceQuery.THERMAL in failedQueries) {
                    "thermal_state_query_failed"
                } else {
                    "thermal_state_pending"
                },
            )
            if (monitoring.reason.isNotEmpty()) add(monitoring.reason)
            if (readinessStatus == WalkSessionReadinessStatus.READY) {
                if (thermalThrottled == true) add("thermal_throttled")
                if (thermalThrottled == null) add("thermal_throttle_state_pending")
            }
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
        }.distinct().joinToString(",")
}

internal fun readWalkSessionDeviceResourceSnapshot(
    batteryNotLow: () -> Boolean?,
    privateStorageAboveSystemLow: () -> Boolean?,
    thermalStatus: () -> Int?,
    monitoring: WalkSessionResourceMonitoringHealth = WalkSessionResourceMonitoringHealth(),
    elapsedRealtimeMs: (() -> Long)? = null,
    batteryObservation: (() -> WalkSessionBatteryObservation)? = null,
): WalkSessionDeviceResourceSnapshot {
    val checkedAt = linkedMapOf<WalkSessionResourceQuery, Long>()
    fun <T> query(kind: WalkSessionResourceQuery, read: () -> T): Result<T> {
        elapsedRealtimeMs?.let { clock -> runCatching(clock).getOrNull()?.let { checkedAt[kind] = it } }
        return runCatching(read)
    }
    val battery = query(WalkSessionResourceQuery.BATTERY, batteryNotLow)
    val storage = query(WalkSessionResourceQuery.PRIVATE_STORAGE, privateStorageAboveSystemLow)
    val thermal = query(WalkSessionResourceQuery.THERMAL, thermalStatus)
    val observedThermalStatus = thermal.getOrNull()
    val thermalBelowCritical = observedThermalStatus?.let {
        it < PowerManager.THERMAL_STATUS_CRITICAL
    }
    val thermalThrottled = observedThermalStatus?.let {
        it >= PowerManager.THERMAL_STATUS_SEVERE
    }
    return WalkSessionDeviceResourceSnapshot(
        batteryNotLow = battery.getOrNull(),
        privateStorageAboveSystemLow = storage.getOrNull(),
        thermalBelowCritical = thermalBelowCritical,
        thermalThrottled = thermalThrottled,
        failedQueries = buildSet {
            if (battery.isFailure) add(WalkSessionResourceQuery.BATTERY)
            if (storage.isFailure) add(WalkSessionResourceQuery.PRIVATE_STORAGE)
            if (thermal.isFailure) add(WalkSessionResourceQuery.THERMAL)
        },
        monitoring = monitoring,
        checkedAtElapsedRealtimeMs = checkedAt.toMap(),
        queryFailureReasons = buildMap {
            fun failure(kind: WalkSessionResourceQuery, result: Result<*>) {
                result.exceptionOrNull()?.let { put(kind, it.javaClass.simpleName.ifEmpty { "Exception" }) }
            }
            failure(WalkSessionResourceQuery.BATTERY, battery)
            failure(WalkSessionResourceQuery.PRIVATE_STORAGE, storage)
            failure(WalkSessionResourceQuery.THERMAL, thermal)
        },
        // Quantitative failure cannot discard an independently confirmed low-battery signal.
        batteryObservation = batteryObservation?.let { read ->
            runCatching(read).getOrElse {
                WalkSessionBatteryObservation(unknownReasons = setOf(WalkSessionBatteryUnknownReason.QUERY_FAILED))
            }
        } ?: WalkSessionBatteryObservation(),
    )
}

/**
 * Reads Android-defined resource signals and raw battery facts. Device-specific numeric thresholds
 * remain unset until the designated-device measurement work approves them.
 */
class AndroidWalkSessionResourceProbe(
    context: Context,
) : Closeable {
    private val appContext = context.applicationContext
    private var receiver: BroadcastReceiver? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var closed = false
    private val monitoring = WalkSessionResourceMonitoringTracker(SystemClock::elapsedRealtime)

    /** Subscription health for diagnostics; [snapshot] carries the same value. */
    val monitoringHealth: WalkSessionResourceMonitoringHealth
        get() = monitoring.health

    @Suppress("DEPRECATION")
    fun start(onResourceChanged: () -> Unit): Boolean {
        check(receiver == null) { "AndroidWalkSessionResourceProbe may only be started once" }
        check(!closed) { "AndroidWalkSessionResourceProbe is closed" }
        val resourceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!closed) {
                    monitoring.eventReceived()
                    onResourceChanged()
                }
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
        }.onFailure { error ->
            monitoring.subscriptionFailed(setOf(WalkSessionResourceSubscription.RESOURCE_BROADCASTS), error)
        }.isSuccess
        if (!receiverRegistered) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener {
                if (!closed) {
                    monitoring.eventReceived()
                    onResourceChanged()
                }
            }
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            var thermalFailure: Throwable? = null
            val thermalListenerRegistered = powerManager != null && runCatching {
                powerManager.addThermalStatusListener(appContext.mainExecutor, listener)
                thermalListener = listener
            }.onFailure { thermalFailure = it }.isSuccess
            if (!thermalListenerRegistered) {
                runCatching { appContext.unregisterReceiver(resourceReceiver) }
                receiver = null
                monitoring.subscriptionFailed(setOf(WalkSessionResourceSubscription.THERMAL_LISTENER), thermalFailure)
                return false
            }
        }
        monitoring.subscribed()
        return true
    }

    @Suppress("DEPRECATION")
    fun snapshot(): WalkSessionDeviceResourceSnapshot {
        // Every call re-queries the system; a resume recheck therefore never reuses an old value.
        var batteryObservation = WalkSessionBatteryObservation()
        return readWalkSessionDeviceResourceSnapshot(
            monitoring = monitoring.health,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            batteryNotLow = {
                val battery = appContext.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                )
                val notLow = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    battery?.hasExtra(BatteryManager.EXTRA_BATTERY_LOW) == true
                ) {
                    !battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, true)
                } else {
                    null
                }
                batteryObservation = runCatching {
                    fun extra(name: String): Int? = if (battery?.hasExtra(name) == true) {
                        battery.getIntExtra(name, Int.MIN_VALUE)
                    } else null
                    readWalkSessionBatteryObservation(
                        level = extra(BatteryManager.EXTRA_LEVEL),
                        scale = extra(BatteryManager.EXTRA_SCALE),
                        status = extra(BatteryManager.EXTRA_STATUS),
                        plugged = extra(BatteryManager.EXTRA_PLUGGED),
                        currentNowMicroamps = {
                            appContext.getSystemService(BatteryManager::class.java)
                                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                        },
                        currentAverageMicroamps = {
                            appContext.getSystemService(BatteryManager::class.java)
                                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                        },
                        targetSdkVersion = appContext.applicationInfo.targetSdkVersion,
                        elapsedRealtimeMs = SystemClock::elapsedRealtime,
                    )
                }.getOrElse {
                    WalkSessionBatteryObservation(unknownReasons = setOf(WalkSessionBatteryUnknownReason.QUERY_FAILED))
                }
                notLow
            },
            batteryObservation = { batteryObservation },
            privateStorageAboveSystemLow = {
                val files = appContext.filesDir
                val systemLowStorage = appContext.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW),
                ) != null
                !systemLowStorage && files.isDirectory && files.canWrite()
            },
            thermalStatus = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appContext.getSystemService(PowerManager::class.java)
                        ?.currentThermalStatus
                } else {
                    null
                }
            },
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        monitoring.closed()
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
