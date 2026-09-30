package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessCollector
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessObservation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessPlan
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkRuntimeSafetyStopLifecycleIntegrationTest {
    @Test
    fun retryUsesActualStoppedEpochAndWaitsForCameraCompletionWithoutNewObservation() {
        val lifecycle = activeLifecycle()
        val sourceEpoch = lifecycle.snapshot().epoch
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        val scheduled = mutableListOf<Runnable>()
        var transitionRuns = 0
        var markerRuns = 0
        var cameraStarts = 0
        var attempts = 0
        var finishCamera: ((Boolean) -> Unit)? = null
        val coordinator = WalkRuntimeSafetyCoordinator(
            thresholdProfile = null,
            onSafeStopWithCompletion = { stop, acknowledge ->
                attempts += 1
                val current = lifecycle.snapshot()
                if (current.epoch != stop.epoch && !checkpoints.ownsStoppedSession(stop.epoch, current)) {
                    acknowledge(false)
                } else {
                    val transitioned = checkpoints.runStage("session_transition") {
                        transitionRuns += 1
                        val transition = lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
                        assertTrue(checkpoints.recordSafetyStopTransition(transition))
                    }
                    val markerSaved = checkpoints.runStage("interruption_marker", idempotent = true) {
                        markerRuns += 1
                        if (markerRuns == 1) throw IllegalStateException("first marker commit failed")
                    }
                    if (checkpoints.beginAsync("camera_release")) {
                        cameraStarts += 1
                        finishCamera = { released ->
                            if (checkpoints.finishAsync("camera_release", released)) {
                                acknowledge(transitioned && markerSaved && released)
                            }
                        }
                    } else if (checkpoints.isComplete("camera_release")) {
                        acknowledge(transitioned && markerSaved)
                    }
                }
            },
            scheduleStopRetry = { scheduled += it; true },
            onStopDeliveryExhausted = { throw AssertionError("cleanup must recover") },
        )
        coordinator.beginEpoch(sourceEpoch)
        val stopped = coordinator.observe(unsafeObservation(sourceEpoch))

        assertFalse(stopped.safetyOutputsAllowed)
        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
        // The former Main guard rejects exactly this genuine lifecycle successor.
        assertNotEquals(sourceEpoch, lifecycle.snapshot().epoch)
        assertTrue(checkpoints.ownsStoppedSession(sourceEpoch, lifecycle.snapshot()))
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertEquals(1, attempts)
        assertTrue(scheduled.isEmpty())

        requireNotNull(finishCamera).invoke(true)
        assertEquals(1, scheduled.size)
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        scheduled.removeAt(0).run()

        assertEquals(2, attempts)
        assertEquals(1, transitionRuns)
        assertEquals(2, markerRuns)
        assertEquals(1, cameraStarts)
        assertFalse(coordinator.stopCleanupBlocksNewWalk)
        assertFalse(coordinator.stopCleanupExhausted)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun oldStopOwnsNeitherNewActiveWalkNorItsLaterSafeStop() {
        val lifecycle = activeLifecycle()
        val sourceEpoch = lifecycle.snapshot().epoch
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        val transition = lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        assertTrue(checkpoints.recordSafetyStopTransition(transition))
        assertTrue(checkpoints.ownsStoppedSession(sourceEpoch, lifecycle.snapshot()))

        lifecycle.handle(WalkSessionEvent.NewWalkRequested)
        activate(lifecycle)
        val newActive = lifecycle.snapshot()
        assertEquals(WalkSessionState.ACTIVE, newActive.state)
        assertFalse(checkpoints.ownsStoppedSession(sourceEpoch, newActive))
        assertFalse(checkpoints.ownsStoppedSession(newActive.epoch, transition.current))

        val laterStop = lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        assertEquals(WalkSessionState.SAFE_STOP, laterStop.current.state)
        assertFalse(checkpoints.recordSafetyStopTransition(laterStop))
        assertFalse(checkpoints.ownsStoppedSession(sourceEpoch, lifecycle.snapshot()))
        assertTrue(checkpoints.ownsStoppedSession(sourceEpoch, transition.current))
    }

    @Test
    fun unrelatedTransitionCannotCreateStopOwnership() {
        val lifecycle = activeLifecycle()
        val sourceEpoch = lifecycle.snapshot().epoch
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        assertFalse(checkpoints.ownsStoppedSession(sourceEpoch, lifecycle.snapshot()))
        assertFalse(checkpoints.recordSafetyStopTransition(lifecycle.handle(WalkSessionEvent.RecheckRequested)))
        val stopped = lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        assertFalse(checkpoints.ownsStoppedSession(sourceEpoch, stopped.current))
        assertTrue(checkpoints.recordSafetyStopTransition(stopped))
        assertTrue(checkpoints.recordSafetyStopTransition(stopped))
        assertTrue(checkpoints.ownsStoppedSession(stopped.previous.epoch, stopped.current))
        assertFalse(checkpoints.ownsStoppedSession(sourceEpoch, stopped.current))
    }

    @Test
    fun lateOldCompletionAndObservationCannotLatchNewEpoch() {
        val lifecycle = activeLifecycle()
        val oldEpoch = lifecycle.snapshot().epoch
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val scheduled = mutableListOf<Runnable>()
        val coordinator = WalkRuntimeSafetyCoordinator(
            thresholdProfile = null,
            onSafeStopWithCompletion = { _, acknowledge -> acknowledgements += acknowledge },
            scheduleStopRetry = { scheduled += it; true },
            onStopDeliveryExhausted = {},
        )
        coordinator.beginEpoch(oldEpoch)
        coordinator.observe(unsafeObservation(oldEpoch))
        lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        acknowledgements.single().invoke(true)
        lifecycle.handle(WalkSessionEvent.NewWalkRequested)
        activate(lifecycle)
        val newActive = lifecycle.snapshot()
        coordinator.beginEpoch(newActive.epoch)

        acknowledgements.single().invoke(false)
        val late = coordinator.observe(unsafeObservation(oldEpoch))

        assertEquals(WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION, late.disposition)
        assertFalse(coordinator.stopCleanupBlocksNewWalk)
        assertFalse(coordinator.stopCleanupExhausted)
        assertTrue(scheduled.isEmpty())
        assertEquals(1, acknowledgements.size)
        assertEquals(newActive, lifecycle.snapshot())
    }

    private fun unsafeObservation(epoch: WalkRuntimeEpoch) = WalkRuntimeSafetyObservation(
        epoch = epoch,
        observedAtElapsedRealtimeMs = 1_000L,
        cameraTrusted = false,
    )

    private fun activeLifecycle() = WalkSessionLifecycle(clock = { 100L }).also {
        activate(it)
    }

    private fun activate(lifecycle: WalkSessionLifecycle) {
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val epoch = lifecycle.snapshot().epoch
        val collector = WalkSessionReadinessCollector(
            epoch = epoch,
            plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL),
        )
        collector.plan.requiredRequirements.forEach { requirement ->
            collector.record(
                WalkSessionReadinessObservation(epoch, requirement, WalkSessionReadinessStatus.READY),
            )
        }
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(assessedAtElapsedRealtimeMs = 100L)))
        lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
    }
}
