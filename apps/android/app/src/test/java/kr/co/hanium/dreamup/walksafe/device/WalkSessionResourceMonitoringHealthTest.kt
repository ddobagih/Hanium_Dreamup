package kr.co.hanium.dreamup.walksafe.device

import android.os.PowerManager
import java.io.File
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-43: resource values, their check time and the monitor's own health stay separate. */
class WalkSessionResourceMonitoringHealthTest {
    private var nowMs = 1_000L
    private val tracker = WalkSessionResourceMonitoringTracker { nowMs }

    private fun healthySnapshot(monitoring: WalkSessionResourceMonitoringHealth) =
        readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { true },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
            monitoring = monitoring,
        )

    @Test
    fun failedEventSubscriptionKeepsHealthyValuesOutOfReady() {
        tracker.subscriptionFailed(
            setOf(WalkSessionResourceSubscription.THERMAL_LISTENER),
            SecurityException("listener rejected"),
        )
        val snapshot = healthySnapshot(tracker.health)

        assertEquals(WalkSessionResourceMonitoringState.SUBSCRIPTION_FAILED, snapshot.monitoring.state)
        assertEquals(setOf(WalkSessionResourceSubscription.THERMAL_LISTENER), snapshot.monitoring.failedSubscriptions)
        assertEquals("SecurityException", snapshot.monitoring.failureReason)
        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.readinessStatus)
        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.measurementReadinessStatus)
        assertEquals("resource_monitoring_subscription_failed", snapshot.reason)
    }

    @Test
    fun lowValueStillWinsOverMonitorFailure() {
        tracker.subscriptionFailed(setOf(WalkSessionResourceSubscription.RESOURCE_BROADCASTS), null)
        val snapshot = readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { false },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
            monitoring = tracker.health,
        )
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, snapshot.readinessStatus)
        assertEquals("battery_low,resource_monitoring_subscription_failed", snapshot.reason)
        assertEquals("subscription_unavailable", snapshot.monitoring.failureReason)
    }

    @Test
    fun closedMonitorIsNotNormalAndCannotBeRevived() {
        tracker.subscribed()
        tracker.closed()
        tracker.subscribed()
        tracker.eventReceived()
        val snapshot = healthySnapshot(tracker.health)
        assertEquals(WalkSessionResourceMonitoringState.CLOSED, snapshot.monitoring.state)
        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.readinessStatus)
        assertEquals("resource_monitoring_closed", snapshot.reason)
    }

    @Test
    fun subscribedMonitorRecordsSubscribeAndLastEventTimes() {
        tracker.subscribed()
        nowMs = 1_250L
        tracker.eventReceived()
        val health = tracker.health
        assertEquals(WalkSessionResourceMonitoringState.SUBSCRIBED, health.state)
        assertEquals(1_000L, health.subscribedAtElapsedRealtimeMs)
        assertEquals(1_250L, health.lastEventAtElapsedRealtimeMs)
        assertFalse(health.blocksReadiness)
        assertEquals(WalkSessionReadinessStatus.READY, healthySnapshot(health).readinessStatus)
        assertEquals("", healthySnapshot(health).reason)
    }

    @Test
    fun eventAfterFailureDoesNotRestoreHealthButRetrySubscriptionDoes() {
        tracker.subscriptionFailed(setOf(WalkSessionResourceSubscription.RESOURCE_BROADCASTS), IllegalStateException())
        tracker.eventReceived()
        assertEquals(WalkSessionResourceMonitoringState.SUBSCRIPTION_FAILED, tracker.health.state)
        assertNull(tracker.health.lastEventAtElapsedRealtimeMs)
        nowMs = 2_000L
        tracker.subscribed()
        assertEquals(WalkSessionResourceMonitoringState.SUBSCRIBED, tracker.health.state)
        assertEquals(emptySet<WalkSessionResourceSubscription>(), tracker.health.failedSubscriptions)
        assertEquals(2_000L, tracker.health.subscribedAtElapsedRealtimeMs)
    }

    @Test
    fun notStartedMonitorKeepsTheExistingPollOnlyReadiness() {
        val snapshot = healthySnapshot(WalkSessionResourceMonitoringHealth())
        assertEquals(WalkSessionReadinessStatus.READY, snapshot.readinessStatus)
    }

    @Test
    fun everyQueryRecordsItsCheckTimeAndFailureTypeWithoutTheMessage() {
        var clock = 500L
        val snapshot = readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { throw SecurityException("serial=ABC123") },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { throw IllegalStateException("thermal hal") },
            elapsedRealtimeMs = { clock++ },
        )
        assertEquals(
            mapOf(
                WalkSessionResourceQuery.BATTERY to 500L,
                WalkSessionResourceQuery.PRIVATE_STORAGE to 501L,
                WalkSessionResourceQuery.THERMAL to 502L,
            ),
            snapshot.checkedAtElapsedRealtimeMs,
        )
        assertEquals(
            mapOf(
                WalkSessionResourceQuery.BATTERY to "SecurityException",
                WalkSessionResourceQuery.THERMAL to "IllegalStateException",
            ),
            snapshot.queryFailureReasons,
        )
        assertFalse(snapshot.queryFailureReasons.values.any { "ABC123" in it || "hal" in it })
        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.readinessStatus)
    }

    @Test
    fun repeatedSnapshotsQueryTheSystemAgain() {
        var batteryReads = 0
        repeat(2) {
            readWalkSessionDeviceResourceSnapshot(
                batteryNotLow = { batteryReads += 1; true },
                privateStorageAboveSystemLow = { true },
                thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
            )
        }
        assertEquals(2, batteryReads)
    }

    @Test
    fun probeWiresSubscriptionResultsAndResumeRechecksTheSnapshot() {
        val probe = source("device/AndroidWalkSessionResourceProbe.kt")
        val start = probe.substringAfter("fun start(onResourceChanged: () -> Unit): Boolean")
            .substringBefore("fun snapshot()")
        assertTrue(start.contains("WalkSessionResourceSubscription.RESOURCE_BROADCASTS"))
        assertTrue(start.contains("WalkSessionResourceSubscription.THERMAL_LISTENER"))
        assertTrue(start.indexOf("monitoring.subscribed()") > start.lastIndexOf("return false"))
        assertEquals(2, Regex("monitoring\\.eventReceived\\(\\)").findAll(start).count())
        val snapshot = probe.substringAfter("fun snapshot()").substringBefore("override fun close()")
        assertTrue(snapshot.contains("monitoring = monitoring.health"))
        assertTrue(snapshot.contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
        assertTrue(probe.substringAfter("override fun close()").contains("monitoring.closed()"))

        val activity = source("MainActivity.kt")
        val activate = activity.substringAfter("private fun activateWalkSessionRuntime(): Boolean")
            .substringBefore("scheduleGatewayWalkRenewal()")
        val ensure = activate.indexOf("ensureWalkSessionResourceMonitoring()")
        val observe = activate.indexOf("observeWalkRuntimeResourceSafety(runtimeEpoch)")
        assertTrue(ensure >= 0 && observe > ensure)
        val guard = activate.indexOf("walkSessionResourceProbe.monitoringHealth.blocksReadiness")
        assertTrue(guard in (ensure + 1) until observe)
        val guardBlock = activate.substring(guard, observe)
        assertTrue(guardBlock.contains("enterWalkSessionForegroundRecheckAndCancelOutputs(\"resource_monitoring_unavailable\")"))
        assertTrue(guardBlock.contains("walkSessionResumeRetryRequiresUserAction = true"))
        assertTrue(guardBlock.contains("return false"))
        val observeBody = activity.substringAfter("private fun observeWalkRuntimeResourceSafety(")
            .substringBefore("private fun firstRunPermissionRequestAllowed(")
        assertTrue(observeBody.contains("walkSessionResourceProbe.snapshot()"))
    }

    private fun source(relative: String): String = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
    ).first { it.isFile }.readText()
}
