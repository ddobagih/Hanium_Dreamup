package kr.co.hanium.dreamup.walksafe.session

import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyCoordinator
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyDisposition
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyObservation
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyStop
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyStopCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkRuntimeSafetyStopExhaustionCausesRegressionTest {
    private val epoch = WalkRuntimeEpoch("exhaustion-causes", 0L)
    private val combinedCauses = setOf(
        WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST,
        WalkRuntimeSafetyStopCause.THERMAL_CRITICAL,
    )

    @Test
    fun thirdFailedAcknowledgementReportsCausesAddedDuringThatCleanupAttempt() {
        val recorder = Recorder()
        val coordinator = coordinator(recorder)
        coordinator.beginEpoch(epoch)
        coordinator.observe(cameraFailure())
        recorder.acknowledgements[0](false)
        recorder.scheduled.removeAt(0).run()
        recorder.acknowledgements[1](false)
        recorder.scheduled.removeAt(0).run()
        assertEquals(3, recorder.stops.size)
        assertTrue(recorder.exhausted.isEmpty())

        coordinator.observe(thermalFailure(1_100L))
        assertEquals(combinedCauses, coordinator.latchedStop()?.causes)
        recorder.acknowledgements[2](false)

        assertTrue(coordinator.stopCleanupExhausted)
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertEquals(3, recorder.stops.size)
        assertEquals(2, recorder.scheduleAttempts)
        assertTrue(recorder.scheduled.isEmpty())
        assertEquals(epoch, recorder.exhausted.single().epoch)
        assertEquals(combinedCauses, recorder.exhausted.single().causes)
    }

    @Test
    fun deniedRetrySchedulingReportsCausesAddedBeforeTheFirstFailedAcknowledgement() {
        val recorder = Recorder().apply { schedulingAllowed = false }
        val coordinator = coordinator(recorder)
        coordinator.beginEpoch(epoch)
        coordinator.observe(cameraFailure())

        coordinator.observe(thermalFailure(1_100L))
        assertEquals(combinedCauses, coordinator.latchedStop()?.causes)
        recorder.acknowledgements.single()(false)

        assertTrue(coordinator.stopCleanupExhausted)
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertEquals(1, recorder.stops.size)
        assertEquals(1, recorder.scheduleAttempts)
        assertTrue(recorder.scheduled.isEmpty())
        assertEquals(epoch, recorder.exhausted.single().epoch)
        assertEquals(combinedCauses, recorder.exhausted.single().causes)
    }

    @Test
    fun causesAddedAfterExhaustionDoNotRetryCleanupOrPermitANewEpoch() {
        val recorder = Recorder()
        val coordinator = coordinator(recorder)
        coordinator.beginEpoch(epoch)
        coordinator.observe(cameraFailure())
        recorder.acknowledgements[0](false)
        recorder.scheduled.removeAt(0).run()
        recorder.acknowledgements[1](false)
        recorder.scheduled.removeAt(0).run()
        recorder.acknowledgements[2](false)
        assertTrue(coordinator.stopCleanupExhausted)
        assertEquals(3, recorder.stops.size)
        assertEquals(1, recorder.exhausted.size)

        val changed = coordinator.observe(thermalFailure(1_100L))

        assertEquals(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, changed.disposition)
        assertFalse(changed.safetyOutputsAllowed)
        assertEquals(combinedCauses, changed.causes)
        assertEquals(WalkRuntimeSafetyStop(epoch, combinedCauses), recorder.changes.single())
        coordinator.observe(thermalFailure(1_200L))
        assertEquals(1, recorder.changes.size)
        assertEquals(3, recorder.stops.size)
        assertEquals(2, recorder.scheduleAttempts)
        assertEquals(1, recorder.exhausted.size)
        assertTrue(recorder.scheduled.isEmpty())

        val nextEpoch = coordinator.beginEpoch(WalkRuntimeEpoch("exhaustion-next-walk", 0L))
        assertEquals(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, nextEpoch.disposition)
        assertFalse(nextEpoch.safetyOutputsAllowed)
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertTrue(coordinator.stopCleanupExhausted)
        assertEquals(epoch, coordinator.latchedStop()?.epoch)
        assertEquals(3, recorder.stops.size)
        assertEquals(2, recorder.scheduleAttempts)
    }

    private class Recorder {
        val stops = mutableListOf<WalkRuntimeSafetyStop>()
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val scheduled = mutableListOf<Runnable>()
        val exhausted = mutableListOf<WalkRuntimeSafetyStop>()
        val changes = mutableListOf<WalkRuntimeSafetyStop>()
        var schedulingAllowed = true
        var scheduleAttempts = 0
    }

    private fun coordinator(recorder: Recorder) = WalkRuntimeSafetyCoordinator(
        thresholdProfile = null,
        onSafeStopWithCompletion = { stop, acknowledge ->
            recorder.stops += stop
            recorder.acknowledgements += acknowledge
        },
        scheduleStopRetry = { retry ->
            recorder.scheduleAttempts += 1
            if (recorder.schedulingAllowed) {
                recorder.scheduled += retry
                true
            } else {
                false
            }
        },
        onStopDeliveryExhausted = { recorder.exhausted += it },
        onStopCausesChanged = { recorder.changes += it },
    )

    private fun cameraFailure() = WalkRuntimeSafetyObservation(
        epoch = epoch,
        observedAtElapsedRealtimeMs = 1_000L,
        cameraTrusted = false,
    )

    private fun thermalFailure(atMs: Long) = WalkRuntimeSafetyObservation(
        epoch = epoch,
        observedAtElapsedRealtimeMs = atMs,
        thermalCritical = true,
    )
}
