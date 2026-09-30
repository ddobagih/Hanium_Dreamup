package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkRuntimeSafetyStopDeliveryRetryTest {
    private val epoch = WalkRuntimeEpoch("stop-delivery", 0L)

    @Test
    fun failedUiCleanupRetriesOnLocalScheduleWithoutAnotherCoordinatorCall() {
        var attempts = 0
        val pending = mutableListOf<Runnable>()
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val coordinator = completionCoordinator(
            onStop = { _, acknowledge ->
                attempts += 1
                acknowledgements += acknowledge
            },
            scheduled = pending,
        )
        coordinator.beginEpoch(epoch)

        val first = coordinator.observe(unsafeObservation())
        assertEquals(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, first.disposition)
        assertFalse(first.safetyOutputsAllowed)
        assertEquals(1, attempts)
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertTrue(pending.isEmpty())

        acknowledgements[0](false)
        assertEquals(1, pending.size)
        pending.removeAt(0).run()
        assertEquals(2, attempts)
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        acknowledgements[1](true)
        assertFalse(coordinator.stopCleanupBlocksNewWalk)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun completedCleanupStageIsNotRepeatedAfterLaterIdempotentStageFails() {
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        val pending = mutableListOf<Runnable>()
        var completedStageRuns = 0
        var retryableStageRuns = 0
        val coordinator = completionCoordinator(
            onStop = { _, acknowledge ->
                val first = checkpoints.runStage("completed") { completedStageRuns += 1 }
                val second = checkpoints.runStage("retryable", idempotent = true) {
                    retryableStageRuns += 1
                    if (retryableStageRuns == 1) throw IllegalStateException("transient marker write")
                }
                acknowledge(first && second)
            },
            scheduled = pending,
        )
        coordinator.beginEpoch(epoch)
        coordinator.observe(unsafeObservation())

        assertEquals(1, completedStageRuns)
        assertEquals(1, retryableStageRuns)
        pending.removeAt(0).run()
        assertEquals(1, completedStageRuns)
        assertEquals(2, retryableStageRuns)
        assertFalse(coordinator.stopCleanupBlocksNewWalk)
    }

    @Test
    fun threeFailedAttemptsExhaustAndBlockNewEpoch() {
        var attempts = 0
        var exhausted = 0
        val pending = mutableListOf<Runnable>()
        val coordinator = completionCoordinator(
            onStop = { _, acknowledge ->
                attempts += 1
                acknowledge(false)
            },
            scheduled = pending,
            onExhausted = { exhausted += 1 },
        )
        coordinator.beginEpoch(epoch)

        val first = coordinator.observe(unsafeObservation())
        assertEquals(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, first.disposition)
        assertFalse(first.safetyOutputsAllowed)
        assertEquals(1, attempts)
        pending.removeAt(0).run()
        pending.removeAt(0).run()
        assertEquals(3, attempts)
        assertEquals(1, exhausted)
        assertTrue(pending.isEmpty())
        assertTrue(coordinator.stopCleanupExhausted)
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        val newEpoch = WalkRuntimeEpoch("new-walk", 0L)
        assertEquals(
            WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED,
            coordinator.beginEpoch(newEpoch).disposition,
        )
        assertEquals(3, attempts)
    }

    @Test
    fun lateCompletionCannotReopenExhaustedStop() {
        val pending = mutableListOf<Runnable>()
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val coordinator = completionCoordinator(
            onStop = { _, acknowledge -> acknowledgements += acknowledge },
            scheduled = pending,
        )
        coordinator.beginEpoch(epoch)
        coordinator.observe(unsafeObservation())
        acknowledgements[0](false)
        pending.removeAt(0).run()
        acknowledgements[1](false)
        pending.removeAt(0).run()
        acknowledgements[2](false)

        acknowledgements[0](true)
        acknowledgements[2](true)
        assertTrue(coordinator.stopCleanupExhausted)
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertEquals(
            WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED,
            coordinator.evaluate(1_001L).disposition,
        )
    }

    private fun completionCoordinator(
        onStop: (WalkRuntimeSafetyStop, (Boolean) -> Unit) -> Unit,
        scheduled: MutableList<Runnable>,
        onExhausted: (WalkRuntimeSafetyStop) -> Unit = {},
    ) = WalkRuntimeSafetyCoordinator(
        thresholdProfile = null,
        onSafeStopWithCompletion = onStop,
        scheduleStopRetry = { retry -> scheduled += retry; true },
        onStopDeliveryExhausted = onExhausted,
    )

    private fun unsafeObservation() = WalkRuntimeSafetyObservation(
        epoch = epoch,
        observedAtElapsedRealtimeMs = 1_000L,
        cameraTrusted = false,
    )
}
