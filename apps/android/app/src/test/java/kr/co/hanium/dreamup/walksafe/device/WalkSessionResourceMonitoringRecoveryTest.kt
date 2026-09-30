package kr.co.hanium.dreamup.walksafe.device

import android.os.PowerManager
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S14-43-F1: a failed subscription recovers when the subscription is retried in the same session.
 * S14-43-F2 (fake clock): after SUBSCRIBED the event stream may stop without notice; the periodic
 * walk tick re-reads the current values, so a later critical state still stops the walk.
 */
class WalkSessionResourceMonitoringRecoveryTest {
    private var nowMs = 1_000L
    private val tracker = WalkSessionResourceMonitoringTracker { nowMs }

    @Test
    fun aRetriedSubscriptionClearsTheFailureAndRestoresReadiness() {
        tracker.subscriptionFailed(setOf(WalkSessionResourceSubscription.RESOURCE_BROADCASTS), SecurityException())
        assertEquals(WalkSessionReadinessStatus.PENDING, poll(PowerManager.THERMAL_STATUS_NONE).readinessStatus)

        nowMs = 5_000L
        tracker.subscribed() // the retry at the next readiness capture succeeded
        val recovered = poll(PowerManager.THERMAL_STATUS_NONE)
        assertEquals(WalkSessionResourceMonitoringState.SUBSCRIBED, recovered.monitoring.state)
        assertEquals(emptySet<WalkSessionResourceSubscription>(), recovered.monitoring.failedSubscriptions)
        assertNull(recovered.monitoring.failureReason)
        assertEquals(5_000L, recovered.monitoring.subscribedAtElapsedRealtimeMs)
        assertEquals(WalkSessionReadinessStatus.READY, recovered.readinessStatus)
        assertEquals("", recovered.reason)
    }

    @Test
    fun theWalkTickPollStopsTheWalkAfterEventsSilentlyStopped() {
        val stops = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = WalkRuntimeSafetyCoordinator(onSafeStop = stops::add)
        val epoch = WalkRuntimeEpoch("walk-1", 0L)
        coordinator.beginEpoch(epoch)
        tracker.subscribed()
        observe(coordinator, epoch, poll(PowerManager.THERMAL_STATUS_NONE))
        assertTrue(stops.isEmpty())

        // The listener stops delivering; the device overheats at 11 s without any event.
        nowMs = 11_000L
        val thermal = PowerManager.THERMAL_STATUS_CRITICAL
        assertNull(tracker.health.lastEventAtElapsedRealtimeMs)

        // Next renewal tick (GATEWAY_WALK_RENEW_INTERVAL_MS = 30 s after activation).
        nowMs = 31_000L
        val ticked = poll(thermal)
        assertEquals(31_000L, ticked.checkedAtElapsedRealtimeMs[WalkSessionResourceQuery.THERMAL])
        assertEquals(false, ticked.thermalBelowCritical)
        observe(coordinator, epoch, ticked)
        assertEquals(1, stops.size)
        assertTrue(WalkRuntimeSafetyStopCause.THERMAL_CRITICAL in stops.single().causes)
    }

    private fun poll(thermalStatus: Int) = readWalkSessionDeviceResourceSnapshot(
        batteryNotLow = { true },
        privateStorageAboveSystemLow = { true },
        thermalStatus = { thermalStatus },
        monitoring = tracker.health,
        elapsedRealtimeMs = { nowMs },
    )

    /** Same observation shape as MainActivity.observeWalkRuntimeResourceSafety. */
    private fun observe(
        coordinator: WalkRuntimeSafetyCoordinator,
        epoch: WalkRuntimeEpoch,
        resources: WalkSessionDeviceResourceSnapshot,
    ) = coordinator.observe(
        WalkRuntimeSafetyObservation(
            epoch = epoch,
            observedAtElapsedRealtimeMs = nowMs,
            receivedAtElapsedRealtimeMs = nowMs,
            batteryCritical = resources.batteryNotLow == false,
            storageCritical = resources.privateStorageAboveSystemLow == false,
            thermalCritical = resources.thermalBelowCritical == false,
        ),
    )
}
