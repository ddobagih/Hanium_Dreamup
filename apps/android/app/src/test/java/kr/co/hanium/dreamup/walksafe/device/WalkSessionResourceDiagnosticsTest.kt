package kr.co.hanium.dreamup.walksafe.device

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WalkSessionResourceDiagnosticsTest {
    @Test
    fun normalQueriesKeepEachOriginalTimeAndSubscriptionEvidence() {
        var queryClock = 500L
        val snapshot = readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { true },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
            monitoring = WalkSessionResourceMonitoringHealth(
                state = WalkSessionResourceMonitoringState.SUBSCRIBED,
                subscribedAtElapsedRealtimeMs = 50L,
                lastEventAtElapsedRealtimeMs = 70L,
            ),
            elapsedRealtimeMs = { queryClock++ },
        )
        val originalTimes = snapshot.checkedAtElapsedRealtimeMs.toMap()

        val diagnostics = snapshot.resourceDiagnosticsLine()

        assertEquals(
            "checked_at_ms=BATTERY:500+PRIVATE_STORAGE:501+THERMAL:502 " +
                "failed_queries=NONE " +
                "query_failures=BATTERY:NONE+PRIVATE_STORAGE:NONE+THERMAL:NONE " +
                "monitoring=SUBSCRIBED failed_subscriptions=NONE monitoring_failure=NONE " +
                "subscribed_at_ms=50 last_event_at_ms=70" + unknownBatterySuffix,
            diagnostics,
        )
        assertEquals(diagnostics, snapshot.resourceDiagnosticsLine())
        assertEquals(originalTimes, snapshot.checkedAtElapsedRealtimeMs)
        assertEquals(503L, queryClock)
    }

    @Test
    fun failedQueriesAndSubscriptionsKeepEnumsAndTypesWithoutExceptionMessages() {
        var queryClock = 900L
        val monitoring = WalkSessionResourceMonitoringTracker { 1L }
        monitoring.subscriptionFailed(
            linkedSetOf(
                WalkSessionResourceSubscription.THERMAL_LISTENER,
                WalkSessionResourceSubscription.RESOURCE_BROADCASTS,
            ),
            SecurityException("serial=ABC123 token=secret"),
        )
        val snapshot = readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { throw SecurityException("serial=ABC123 token=secret") },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { throw IllegalStateException("thermal hal secret") },
            monitoring = monitoring.health,
            elapsedRealtimeMs = { queryClock++ },
        )

        val diagnostics = snapshot.resourceDiagnosticsLine()

        assertEquals(
            "checked_at_ms=BATTERY:900+PRIVATE_STORAGE:901+THERMAL:902 " +
                "failed_queries=BATTERY+THERMAL " +
                "query_failures=BATTERY:SecurityException+PRIVATE_STORAGE:NONE+THERMAL:IllegalStateException " +
                "monitoring=SUBSCRIPTION_FAILED " +
                "failed_subscriptions=RESOURCE_BROADCASTS+THERMAL_LISTENER " +
                "monitoring_failure=SecurityException subscribed_at_ms=UNKNOWN last_event_at_ms=UNKNOWN" + unknownBatterySuffix,
            diagnostics,
        )
        assertFalse(diagnostics.contains("serial"))
        assertFalse(diagnostics.contains("ABC123"))
        assertFalse(diagnostics.contains("secret"))
        assertFalse(diagnostics.contains("thermal hal"))
    }

    @Test
    fun missingEvidenceAndMalformedFailureTypesRemainUnknown() {
        val snapshot = WalkSessionDeviceResourceSnapshot(
            batteryNotLow = null,
            privateStorageAboveSystemLow = null,
            thermalBelowCritical = null,
        )

        assertEquals(
            "checked_at_ms=BATTERY:UNKNOWN+PRIVATE_STORAGE:UNKNOWN+THERMAL:UNKNOWN " +
                "failed_queries=NONE " +
                "query_failures=BATTERY:UNKNOWN+PRIVATE_STORAGE:UNKNOWN+THERMAL:UNKNOWN " +
                "monitoring=NOT_STARTED failed_subscriptions=NONE monitoring_failure=UNKNOWN " +
                "subscribed_at_ms=UNKNOWN last_event_at_ms=UNKNOWN" + unknownBatterySuffix,
            snapshot.resourceDiagnosticsLine(),
        )
        val malformed = snapshot.copy(
            failedQueries = setOf(WalkSessionResourceQuery.BATTERY),
            queryFailureReasons = mapOf(WalkSessionResourceQuery.BATTERY to "serial=ABC123 secret"),
            monitoring = WalkSessionResourceMonitoringHealth(
                state = WalkSessionResourceMonitoringState.SUBSCRIPTION_FAILED,
                failureReason = "token=secret",
            ),
        ).resourceDiagnosticsLine()
        assertEquals(
            "checked_at_ms=BATTERY:UNKNOWN+PRIVATE_STORAGE:UNKNOWN+THERMAL:UNKNOWN " +
                "failed_queries=BATTERY " +
                "query_failures=BATTERY:UNKNOWN+PRIVATE_STORAGE:UNKNOWN+THERMAL:UNKNOWN " +
                "monitoring=SUBSCRIPTION_FAILED failed_subscriptions=NONE monitoring_failure=UNKNOWN " +
                "subscribed_at_ms=UNKNOWN last_event_at_ms=UNKNOWN" + unknownBatterySuffix,
            malformed,
        )
        assertFalse(malformed.contains("ABC123"))
        assertFalse(malformed.contains("secret"))
    }

    private val unknownBatterySuffix = " battery_level=UNKNOWN battery_scale=UNKNOWN " +
        "battery_current_now_ua=UNKNOWN battery_current_average_ua=UNKNOWN " +
        "battery_charge_status=UNKNOWN battery_plugged_in=UNKNOWN battery_source=UNKNOWN " +
        "battery_query_received_at_ms=UNKNOWN battery_measured_at_ms=UNKNOWN " +
        "battery_unknown_reasons=NO_OBSERVATION"
}
