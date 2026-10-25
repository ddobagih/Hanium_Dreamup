package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessCollector
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessObservation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessPlan
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessRequirement
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-41: execution readiness and a known performance restriction are separate facts. */
class WalkSessionResourcePerformanceReadinessTest {
    @Test
    fun nonCriticalThermalThrottlingKeepsExecutionReadyAndExplainsTheRestriction() {
        val snapshot = snapshot(thermalBelowCritical = true, thermalThrottled = true)

        assertEquals(WalkSessionReadinessStatus.READY, snapshot.readinessStatus)
        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.measurementReadinessStatus)
        assertEquals("thermal_throttled", snapshot.measurementReason)
        assertEquals("thermal_throttled", snapshot.reason)
    }

    @Test
    fun knownNormalResourcesRemainReadyWithoutAPerformanceRestriction() {
        val snapshot = snapshot(thermalBelowCritical = true, thermalThrottled = false)

        assertEquals(WalkSessionReadinessStatus.READY, snapshot.readinessStatus)
        assertEquals(WalkSessionReadinessStatus.READY, snapshot.measurementReadinessStatus)
        assertEquals("", snapshot.reason)
        assertEquals("", snapshot.measurementReason)
    }

    @Test
    fun nonCriticalResourcesWithUnknownPerformanceRemainReadyButDoNotClaimNormalPerformance() {
        val snapshot = snapshot(thermalBelowCritical = true, thermalThrottled = null)

        assertEquals(WalkSessionReadinessStatus.READY, snapshot.readinessStatus)
        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.measurementReadinessStatus)
        assertEquals("thermal_throttle_state_pending", snapshot.measurementReason)
        assertEquals("thermal_throttle_state_pending", snapshot.reason)
    }

    @Test
    fun unknownThermalStateRemainsPendingInsteadOfClaimingNormalPerformance() {
        val snapshot = snapshot(thermalBelowCritical = null, thermalThrottled = null)

        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.readinessStatus)
        assertEquals(WalkSessionReadinessStatus.PENDING, snapshot.measurementReadinessStatus)
        assertTrue(snapshot.reason.contains("thermal_state_pending"))
        assertTrue(snapshot.measurementReason.contains("thermal_throttle_state_pending"))
        assertFalse(snapshot.reason.contains("thermal_throttled"))
    }

    @Test
    fun criticalThermalStateRemainsUnavailableRatherThanOnlyPerformanceLimited() {
        val snapshot = snapshot(thermalBelowCritical = false, thermalThrottled = true)

        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, snapshot.readinessStatus)
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, snapshot.measurementReadinessStatus)
        assertEquals("thermal_critical", snapshot.reason)
    }

    @Test
    fun performanceReasonDoesNotBlockReadyEvidenceOrRenewItsOriginalExpiry() {
        listOf(true, null).forEach { thermalThrottled ->
            val resources = snapshot(thermalBelowCritical = true, thermalThrottled = thermalThrottled)
            val epoch = WalkRuntimeEpoch("resource-performance", 0L)
            val collector = WalkSessionReadinessCollector(
                epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL),
            )
            collector.plan.requiredRequirements.forEach { requirement ->
                val resource = requirement == WalkSessionReadinessRequirement.DEVICE_RESOURCES
                collector.record(
                    WalkSessionReadinessObservation(
                        epoch = epoch,
                        requirement = requirement,
                        status = if (resource) resources.readinessStatus else WalkSessionReadinessStatus.READY,
                        reason = if (resource) resources.reason else "",
                        observedAtElapsedRealtimeMs = 1_000L.takeIf { resource },
                        maxAgeMs = WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS.takeIf { resource },
                    ),
                )
            }
            val readiness = collector.build(assessedAtElapsedRealtimeMs = 1_000L)
            val deadline = 1_000L + WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS
            assertTrue(readiness.isReadyAt(deadline))
            assertTrue(readiness.blockingReasons.isEmpty())
            assertFalse(readiness.isReadyAt(deadline + 1L))
            assertEquals(
                listOf("stale_observation:device_resources"),
                readiness.copy(assessedAtElapsedRealtimeMs = deadline + 1L).blockingReasons,
            )
        }
    }

    private fun snapshot(
        thermalBelowCritical: Boolean?,
        thermalThrottled: Boolean?,
    ) = WalkSessionDeviceResourceSnapshot(
        batteryNotLow = true,
        privateStorageAboveSystemLow = true,
        thermalBelowCritical = thermalBelowCritical,
        thermalThrottled = thermalThrottled,
        monitoring = WalkSessionResourceMonitoringHealth(
            state = WalkSessionResourceMonitoringState.SUBSCRIBED,
        ),
    )
}
