package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.hardware.Sensor
import android.hardware.SensorEventListener
import kr.co.hanium.dreamup.walksafe.navigation.AndroidStepTracker
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepSensorRegistration
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Real step-counter reducer with fake registration; no Android Activity or sensor hardware is exercised. */
class UserMotionStepCounterEpochTest {
    @Test fun newWalkCounterBaselineAndQueuedOldOwnerDoNotRestorePreviousMotion() {
        val coordinator = PositioningCoordinator()
        val old = owner("walk-1")
        var callbackOwner = old
        val queued = mutableListOf<Pair<StepEvent, UserMotionStateOwner>>()
        val counts = mutableListOf<Int>()
        val tracker = AndroidStepTracker(
            registration = CounterRegistration(), onStepCountChanged = { counts += it },
            onStepEvent = { event -> queued += event to callbackOwner })
        coordinator.beginUserMotionEpoch(old, 1_000L)
        assertTrue(tracker.start())
        assertTrue(tracker.onStepCounterTotal(5_000f, 1_000L * MS))
        assertTrue(tracker.onStepCounterTotal(5_002f, 2_000L * MS))
        assertEquals(2, counts.last())
        assertEquals(1, queued.size)

        val current = owner("walk-2")
        coordinator.beginUserMotionEpoch(current, 2_500L)
        tracker.resetForNewWalk()
        callbackOwner = current
        assertEquals(0, counts.last())
        assertTrue(tracker.start())
        // Its cumulative total contains all previous walking; the new baseline imports none of it.
        assertTrue(tracker.onStepCounterTotal(5_003f, 3_000L * MS))
        assertEquals(0, counts.last())
        assertEquals(1, queued.size)
        val stale = queued.removeAt(0)
        assertFalse(coordinator.isUserMotionInputCurrent(stale.first.timestampMs, stale.second))
        coordinator.observeStep(stale.first.deltaSteps, heading(stale.first.timestampMs), PdrStepQuality.MEDIUM,
            receivedAtElapsedRealtimeMs = 3_100L, expectedMotionOwner = stale.second)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, coordinator.currentUserMotion(3_100L).evidenceHealth)

        assertTrue(tracker.onStepCounterTotal(5_004f, 4_000L * MS))
        assertEquals(1, counts.last())
        val fresh = queued.single()
        assertEquals(1, fresh.first.deltaSteps)
        assertTrue(coordinator.isUserMotionInputCurrent(fresh.first.timestampMs, fresh.second))
        coordinator.observeStep(fresh.first.deltaSteps, heading(fresh.first.timestampMs), PdrStepQuality.MEDIUM,
            expectedMotionOwner = fresh.second)
        assertEquals(UserMovementState.MOVING, coordinator.currentUserMotion(4_000L).movement)
        tracker.stop()
    }

    private fun owner(walk: String) = UserMotionStateOwner(
        1L, WalkRuntimeEpoch(walk, 0L), "boot-count:10", "process-1")

    private fun heading(at: Long) = PedestrianHeadingInput(
        at, speed = null, gpsCourse = null, magneticTrueHeading = null, phoneForwardMounted = false)

    private class CounterRegistration : StepSensorRegistration {
        override fun hasSensor(type: Int): Boolean = type == Sensor.TYPE_STEP_COUNTER
        override fun register(listener: SensorEventListener, type: Int): Boolean = hasSensor(type)
        override fun unregister(listener: SensorEventListener?) = Unit
    }

    private companion object { const val MS = 1_000_000L }
}
