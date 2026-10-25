package kr.co.hanium.dreamup.walksafe.session

import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyCoordinator
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyDisposition
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyObservation
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyStop
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyStopCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkRuntimeSafetyStopStatusTest {
    private val epochA = WalkRuntimeEpoch("stop-status", 0L)
    private val epochB = WalkRuntimeEpoch("stop-status", 1L)

    @Test
    fun firstLatchCauseSetAndAddedCausesStaySeparateThroughActualCleanupCompletion() {
        val recorder = Recorder()
        val coordinator = coordinator(recorder)
        assertNull(coordinator.latchedStopStatus())
        coordinator.beginEpoch(epochA)
        assertNull(coordinator.latchedStopStatus())

        coordinator.observe(observation(1_000L, cameraTrusted = false, gpsTrusted = false))
        val initial = requireNotNull(coordinator.latchedStopStatus())
        val initialCauses = setOf(
            WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST,
            WalkRuntimeSafetyStopCause.GPS_TRUST_LOST,
        )
        assertEquals(epochA, initial.stop.epoch)
        assertEquals(initialCauses, initial.initialCauses)
        assertEquals(initialCauses, initial.stop.causes)
        assertFalse(initial.cleanupComplete)
        assertFalse(initial.cleanupExhausted)

        coordinator.observe(observation(1_100L, thermalCritical = true))
        val added = requireNotNull(coordinator.latchedStopStatus())
        assertEquals(initialCauses, added.initialCauses)
        assertEquals(initialCauses + WalkRuntimeSafetyStopCause.THERMAL_CRITICAL, added.stop.causes)
        assertEquals(setOf(WalkRuntimeSafetyStopCause.THERMAL_CRITICAL), added.stop.causes - added.initialCauses)
        assertEquals(initialCauses, initial.stop.causes)
        assertEquals(1, recorder.stops.size)

        recorder.acknowledgements.single()(true)
        val completed = requireNotNull(coordinator.latchedStopStatus())
        assertTrue(completed.cleanupComplete)
        assertFalse(completed.cleanupExhausted)
        assertEquals(added.stop, completed.stop)
        assertEquals(initialCauses, completed.initialCauses)
        assertFalse(coordinator.stopCleanupBlocksNewWalk)
        coordinator.beginEpoch(epochA)
        assertEquals(completed, coordinator.latchedStopStatus())
        assertEquals(1, recorder.stops.size)
    }

    @Test
    fun snapshotCauseSetsAreImmutableAndAnOlderSnapshotDoesNotFollowLaterChanges() {
        val recorder = Recorder()
        val coordinator = coordinator(recorder)
        coordinator.beginEpoch(epochA)
        coordinator.observe(observation(1_000L, cameraTrusted = false))
        val snapshot = requireNotNull(coordinator.latchedStopStatus())

        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.initialCauses as MutableSet<WalkRuntimeSafetyStopCause>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.stop.causes as MutableSet<WalkRuntimeSafetyStopCause>).clear()
        }
        coordinator.observe(observation(1_100L, thermalCritical = true))

        assertEquals(setOf(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST), snapshot.initialCauses)
        assertEquals(setOf(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST), snapshot.stop.causes)
        assertEquals(
            setOf(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST, WalkRuntimeSafetyStopCause.THERMAL_CRITICAL),
            coordinator.latchedStopStatus()?.stop?.causes,
        )
        assertFalse(snapshot.cleanupComplete)
        assertFalse(snapshot.cleanupExhausted)
        assertEquals(1, recorder.stops.size)
    }

    @Test
    fun completedStopClearsAtANewEpochAndOldObservationsOrAcknowledgementsCannotRestoreIt() {
        val recorder = Recorder()
        val coordinator = coordinator(recorder)
        coordinator.beginEpoch(epochA)
        coordinator.observe(observation(1_000L, cameraTrusted = false))
        recorder.acknowledgements.single()(true)
        val previous = requireNotNull(coordinator.latchedStopStatus())

        coordinator.beginEpoch(epochB)

        assertNull(coordinator.latchedStopStatus())
        assertNull(coordinator.latchedStop())
        assertFalse(coordinator.stopCleanupBlocksNewWalk)
        assertFalse(coordinator.stopCleanupExhausted)
        val late = coordinator.observe(observation(1_100L, thermalCritical = true))
        assertEquals(WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION, late.disposition)
        assertNull(coordinator.latchedStopStatus())
        assertEquals(1, recorder.stops.size)

        coordinator.observe(observation(1_200L, epoch = epochB, thermalCritical = true))
        val current = requireNotNull(coordinator.latchedStopStatus())
        assertEquals(epochB, current.stop.epoch)
        assertEquals(setOf(WalkRuntimeSafetyStopCause.THERMAL_CRITICAL), current.initialCauses)
        assertEquals(current.initialCauses, current.stop.causes)
        assertFalse(current.cleanupComplete)
        recorder.acknowledgements[0](true)
        assertEquals(current, coordinator.latchedStopStatus())
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertEquals(epochA, previous.stop.epoch)
        assertEquals(setOf(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST), previous.initialCauses)
        assertTrue(previous.cleanupComplete)
    }

    @Test
    fun exhaustedStatusSurvivesADeniedNewEpochAndCloseHidesItWithoutReopeningTheWalk() {
        val recorder = Recorder()
        val coordinator = coordinator(recorder, schedulingAllowed = false)
        coordinator.beginEpoch(epochA)
        coordinator.observe(observation(1_000L, cameraTrusted = false))
        recorder.acknowledgements.single()(false)
        coordinator.observe(observation(1_100L, thermalCritical = true))
        val exhausted = requireNotNull(coordinator.latchedStopStatus())
        assertFalse(exhausted.cleanupComplete)
        assertTrue(exhausted.cleanupExhausted)
        assertEquals(setOf(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST), exhausted.initialCauses)
        assertEquals(
            setOf(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST, WalkRuntimeSafetyStopCause.THERMAL_CRITICAL),
            exhausted.stop.causes,
        )

        val denied = coordinator.beginEpoch(epochB)
        assertEquals(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, denied.disposition)
        assertEquals(exhausted, coordinator.latchedStopStatus())
        assertEquals(1, recorder.stops.size)
        coordinator.close()

        assertNull(coordinator.latchedStopStatus())
        assertEquals(exhausted.stop, coordinator.latchedStop())
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertEquals(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, coordinator.beginEpoch(epochB).disposition)
        recorder.acknowledgements.single()(true)
        assertNull(coordinator.latchedStopStatus())
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertTrue(exhausted.cleanupExhausted)
        assertEquals(1, recorder.stops.size)
    }

    @Test
    fun repeatedStatusReadsDoNotAcknowledgeCleanupOrRunAScheduledRetry() {
        val recorder = Recorder()
        val coordinator = coordinator(recorder)
        coordinator.beginEpoch(epochA)
        coordinator.observe(observation(1_000L, cameraTrusted = false))
        recorder.acknowledgements.single()(false)
        val pending = requireNotNull(coordinator.latchedStopStatus())

        repeat(3) {
            assertEquals(pending, coordinator.latchedStopStatus())
        }

        assertFalse(pending.cleanupComplete)
        assertFalse(pending.cleanupExhausted)
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertEquals(1, recorder.stops.size)
        assertEquals(1, recorder.scheduled.size)
    }

    private class Recorder {
        val stops = mutableListOf<WalkRuntimeSafetyStop>()
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val scheduled = mutableListOf<Runnable>()
    }

    private fun coordinator(recorder: Recorder, schedulingAllowed: Boolean = true) =
        WalkRuntimeSafetyCoordinator(
            thresholdProfile = null,
            onSafeStopWithCompletion = { stop, acknowledge ->
                recorder.stops += stop
                recorder.acknowledgements += acknowledge
            },
            scheduleStopRetry = { retry ->
                if (schedulingAllowed) {
                    recorder.scheduled += retry
                    true
                } else {
                    false
                }
            },
            onStopDeliveryExhausted = {},
        )

    private fun observation(
        atMs: Long,
        epoch: WalkRuntimeEpoch = epochA,
        cameraTrusted: Boolean? = null,
        gpsTrusted: Boolean? = null,
        thermalCritical: Boolean? = null,
    ) = WalkRuntimeSafetyObservation(
        epoch = epoch,
        observedAtElapsedRealtimeMs = atMs,
        cameraTrusted = cameraTrusted,
        gpsTrusted = gpsTrusted,
        thermalCritical = thermalCritical,
    )
}
