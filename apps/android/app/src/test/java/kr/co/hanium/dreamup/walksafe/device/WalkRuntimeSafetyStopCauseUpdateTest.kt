package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-10: causes added after the first stop reach the UI; cleanup still runs once. */
class WalkRuntimeSafetyStopCauseUpdateTest {
    private val epochA = WalkRuntimeEpoch("cause-update", 0L)
    private val epochB = WalkRuntimeEpoch("cause-update", 1L)

    private class Recorder {
        val stops = mutableListOf<WalkRuntimeSafetyStop>()
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val changes = mutableListOf<WalkRuntimeSafetyStop>()
    }

    private fun coordinator(recorder: Recorder, acknowledgeImmediately: Boolean = true) =
        WalkRuntimeSafetyCoordinator(
            thresholdProfile = null,
            onSafeStopWithCompletion = { stop, acknowledge ->
                recorder.stops += stop
                if (acknowledgeImmediately) acknowledge(true) else recorder.acknowledgements += acknowledge
            },
            scheduleStopRetry = { false },
            onStopDeliveryExhausted = {},
            onStopCausesChanged = { recorder.changes += it },
        )

    private fun observation(
        epoch: WalkRuntimeEpoch,
        atMs: Long,
        cameraTrusted: Boolean? = null,
        thermalCritical: Boolean? = null,
        gpsTrusted: Boolean? = null,
    ) = WalkRuntimeSafetyObservation(
        epoch = epoch,
        observedAtElapsedRealtimeMs = atMs,
        cameraTrusted = cameraTrusted,
        thermalCritical = thermalCritical,
        gpsTrusted = gpsTrusted,
    )

    @Test
    fun addedCauseIsDeliveredAsStateChangeWithoutRepeatingCleanup() {
        val recorder = Recorder()
        val coordinator = coordinator(recorder)
        coordinator.beginEpoch(epochA)

        coordinator.observe(observation(epochA, 1_000L, cameraTrusted = false))
        assertEquals(1, recorder.stops.size)
        assertEquals(setOf(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST), recorder.stops[0].causes)
        assertTrue(recorder.changes.isEmpty())

        val later = coordinator.observe(observation(epochA, 1_100L, thermalCritical = true))
        val expected = setOf(
            WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST,
            WalkRuntimeSafetyStopCause.THERMAL_CRITICAL,
        )
        assertEquals(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, later.disposition)
        assertEquals(1, recorder.stops.size)
        assertEquals(listOf(WalkRuntimeSafetyStop(epochA, expected)), recorder.changes)
        assertEquals(WalkRuntimeSafetyStop(epochA, expected), coordinator.latchedStop())

        // The same cause again is not a state change.
        coordinator.observe(observation(epochA, 1_200L, thermalCritical = true))
        coordinator.observe(observation(epochA, 1_300L, cameraTrusted = false))
        assertEquals(1, recorder.changes.size)
        assertEquals(1, recorder.stops.size)
    }

    @Test
    fun causeAddedWhileFirstCleanupIsInFlightIsStillReported() {
        val recorder = Recorder()
        val coordinator = coordinator(recorder, acknowledgeImmediately = false)
        coordinator.beginEpoch(epochA)
        coordinator.observe(observation(epochA, 1_000L, cameraTrusted = false))
        assertEquals(1, recorder.stops.size)

        coordinator.observe(observation(epochA, 1_050L, gpsTrusted = false))
        assertEquals(
            setOf(
                WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST,
                WalkRuntimeSafetyStopCause.GPS_TRUST_LOST,
            ),
            recorder.changes.single().causes,
        )
        recorder.acknowledgements.single()(true)
        assertEquals(1, recorder.stops.size)
    }

    @Test
    fun oldEpochObservationCannotReportCausesForTheNextWalk() {
        val recorder = Recorder()
        val coordinator = coordinator(recorder)
        coordinator.beginEpoch(epochA)
        coordinator.observe(observation(epochA, 1_000L, cameraTrusted = false))
        coordinator.beginEpoch(epochB)
        assertNull(coordinator.latchedStop())

        val late = coordinator.observe(observation(epochA, 1_100L, thermalCritical = true))
        assertEquals(WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION, late.disposition)
        assertTrue(recorder.changes.isEmpty())
        assertNull(coordinator.latchedStop())
    }

    @Test
    fun throwingListenerDoesNotReopenOutputsOrHideTheLatchedCauses() {
        val stops = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = WalkRuntimeSafetyCoordinator(
            thresholdProfile = null,
            onSafeStopWithCompletion = { stop, acknowledge ->
                stops += stop
                acknowledge(true)
            },
            scheduleStopRetry = { false },
            onStopDeliveryExhausted = {},
            onStopCausesChanged = { throw IllegalStateException("ui gone") },
        )
        coordinator.beginEpoch(epochA)
        coordinator.observe(observation(epochA, 1_000L, cameraTrusted = false))
        val decision = coordinator.observe(observation(epochA, 1_100L, thermalCritical = true))
        assertEquals(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, decision.disposition)
        assertEquals(2, coordinator.latchedStop()?.causes?.size)
        assertEquals(1, stops.size)
    }
}
