package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSessionLifecycleFp018Test {
    @Test
    fun initialReadinessCannotActivateRuntimeBeforeExplicitStart() {
        val lifecycle = lifecycle()
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val readiness = readySnapshot(
            epoch = lifecycle.snapshot().epoch,
            action = WalkSessionAction.START_WALK,
            mode = WalkSessionMode.FULL,
        )

        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))

        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.FULL, lifecycle.snapshot().mode)
        assertFalse(lifecycle.isRuntimeEpochCurrent(readiness.epoch))

        val transition = lifecycle.handle(
            WalkSessionEvent.StartRequested(
                lifecycle.snapshot().confirmationToken!!,
            ),
        )

        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        assertTrue(lifecycle.isRuntimeEpochCurrent(readiness.epoch))
        assertEquals(WalkSessionTransitionReason.START_CONFIRMED, transition.reason)
        assertEquals(1_002L, transition.occurredAtEpochMs)
    }

    @Test
    fun staleReadinessFromThePreviousGenerationCannotChangeCurrentState() {
        val lifecycle = activeLifecycle(mode = WalkSessionMode.FULL)
        val staleEpoch = lifecycle.snapshot().epoch

        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val transition = lifecycle.handle(
            WalkSessionEvent.RecoveryRecheckCompleted(
                readySnapshot(
                    epoch = staleEpoch,
                    action = WalkSessionAction.RESUME_WALK,
                    mode = WalkSessionMode.FULL,
                ),
            ),
        )

        assertFalse(transition.changed)
        assertEquals(
            WalkSessionRecoveryStage.RECHECK_REQUIRED,
            lifecycle.snapshot().recoveryStage,
        )
    }

    @Test
    fun lifecycleRejectsReadinessCapturedForAnotherAction() {
        val lifecycle = lifecycle()
        lifecycle.handle(WalkSessionEvent.EnteredForeground)

        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(
                readySnapshot(
                    epoch = lifecycle.snapshot().epoch,
                    action = WalkSessionAction.DESTINATION_SEARCH,
                    mode = WalkSessionMode.FULL,
                ),
            ),
        )

        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertNull(lifecycle.snapshot().readiness)

        val active = activeLifecycle(mode = WalkSessionMode.FULL)
        active.handle(WalkSessionEvent.EnteredBackground)
        active.handle(WalkSessionEvent.EnteredForeground)
        active.handle(
            WalkSessionEvent.RecoveryRecheckCompleted(
                readySnapshot(
                    epoch = active.snapshot().epoch,
                    action = WalkSessionAction.ROUTE_REQUEST,
                    mode = WalkSessionMode.FULL,
                ),
            ),
        )

        assertEquals(
            WalkSessionRecoveryStage.RECHECK_REQUIRED,
            active.snapshot().recoveryStage,
        )
        assertNull(active.snapshot().readiness)
    }

    @Test
    fun backgroundInvalidatesReadinessButPreservesDistanceLimitedMode() {
        val lifecycle = activeLifecycle(mode = WalkSessionMode.DISTANCE_LIMITED)
        val activeEpoch = lifecycle.snapshot().epoch

        lifecycle.handle(WalkSessionEvent.EnteredBackground)

        val paused = lifecycle.snapshot()
        assertEquals(WalkSessionState.PAUSED, paused.state)
        assertEquals(WalkSessionMode.DISTANCE_LIMITED, paused.mode)
        assertEquals(activeEpoch.recoveryGeneration + 1, paused.epoch.recoveryGeneration)
        assertNull(paused.readiness)
        assertFalse(lifecycle.isRuntimeEpochCurrent(activeEpoch))
    }

    @Test
    fun recoveryRequiresCurrentReadinessAndExactStartConfirmation() {
        val lifecycle = activeLifecycle(mode = WalkSessionMode.FULL)
        val staleEpoch = lifecycle.snapshot().epoch
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val recoveryEpoch = lifecycle.snapshot().epoch
        lifecycle.handle(
            WalkSessionEvent.RecoveryRecheckCompleted(
                readySnapshot(
                    epoch = recoveryEpoch,
                    action = WalkSessionAction.RESUME_WALK,
                    mode = WalkSessionMode.DISTANCE_LIMITED,
                ),
            ),
        )

        assertFalse(
            lifecycle.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    confirmation = WalkSessionResumeConfirmation.START,
                    token = WalkSessionConfirmationToken(
                        readySnapshot(
                            epoch = staleEpoch,
                            action = WalkSessionAction.RESUME_WALK,
                            mode = WalkSessionMode.FULL,
                        ),
                    ),
                ),
            ).changed,
        )
        assertFalse(
            lifecycle.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    confirmation = WalkSessionResumeConfirmation.CANCEL,
                    token = lifecycle.snapshot().confirmationToken!!,
                ),
            ).changed,
        )

        lifecycle.handle(
            WalkSessionEvent.ResumeConfirmationReceived(
                confirmation = WalkSessionResumeConfirmation.START,
                token = lifecycle.snapshot().confirmationToken!!,
            ),
        )

        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.DISTANCE_LIMITED, lifecycle.snapshot().mode)
    }

    @Test
    fun processRestartCreatesANewWalkWithoutRestoringRuntimeReadiness() {
        val lifecycle = activeLifecycle(mode = WalkSessionMode.FULL)
        val previous = lifecycle.snapshot()

        lifecycle.handle(WalkSessionEvent.ProcessRestarted)

        val restarted = lifecycle.snapshot()
        assertNotEquals(previous.epoch.walkSessionId, restarted.epoch.walkSessionId)
        assertEquals(0L, restarted.epoch.recoveryGeneration)
        assertEquals(WalkSessionState.READY, restarted.state)
        assertEquals(WalkSessionMode.UNAVAILABLE, restarted.mode)
        assertNull(restarted.readiness)
        assertFalse(lifecycle.isRuntimeEpochCurrent(previous.epoch))
    }

    @Test
    fun runtimeReadinessLossMovesAnActiveWalkToSafetyStop() {
        val lifecycle = activeLifecycle(mode = WalkSessionMode.FULL)
        val epoch = lifecycle.snapshot().epoch
        val blocked = blockedSnapshot(
            epoch = epoch,
            mode = WalkSessionMode.FULL,
            requirement = WalkSessionReadinessRequirement.CAMERA,
        )

        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(blocked))

        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.UNAVAILABLE, lifecycle.snapshot().mode)
        assertFalse(lifecycle.isRuntimeEpochCurrent(epoch))
    }

    @Test
    fun aNewWalkAfterSafetyStopGetsANewIdentity() {
        val lifecycle = activeLifecycle(mode = WalkSessionMode.FULL)
        lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        val stoppedWalkId = lifecycle.snapshot().epoch.walkSessionId

        lifecycle.handle(WalkSessionEvent.NewWalkRequested)

        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertNotEquals(stoppedWalkId, lifecycle.snapshot().epoch.walkSessionId)
        assertNull(lifecycle.snapshot().readiness)
    }

    private fun activeLifecycle(mode: WalkSessionMode): WalkSessionLifecycle {
        val lifecycle = lifecycle()
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val readiness = readySnapshot(
            epoch = lifecycle.snapshot().epoch,
            action = WalkSessionAction.START_WALK,
            mode = mode,
        )
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
        lifecycle.handle(
            WalkSessionEvent.StartRequested(
                lifecycle.snapshot().confirmationToken!!,
            ),
        )
        return lifecycle
    }

    private fun lifecycle(): WalkSessionLifecycle {
        var sessionId = 0
        var now = 1_000L
        return WalkSessionLifecycle(
            sessionIdFactory = { "walk-${++sessionId}" },
            clock = { now++ },
        )
    }

    private fun readySnapshot(
        epoch: WalkRuntimeEpoch,
        action: WalkSessionAction,
        mode: WalkSessionMode,
    ): WalkSessionReadinessSnapshot {
        val collector = WalkSessionReadinessCollector(
            epoch = epoch,
            plan = WalkSessionReadinessPlan(action = action, mode = mode),
        )
        collector.plan.requiredRequirements.forEach { requirement ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = WalkSessionReadinessStatus.READY,
                ),
            )
        }
        return collector.build(assessedAtEpochMs = 900)
    }

    private fun blockedSnapshot(
        epoch: WalkRuntimeEpoch,
        mode: WalkSessionMode,
        requirement: WalkSessionReadinessRequirement,
    ): WalkSessionReadinessSnapshot {
        val collector = WalkSessionReadinessCollector(
            epoch = epoch,
            plan = WalkSessionReadinessPlan(
                action = WalkSessionAction.START_WALK,
                mode = mode,
            ),
        )
        collector.plan.requiredRequirements.forEach { current ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = current,
                    status = if (current == requirement) {
                        WalkSessionReadinessStatus.UNAVAILABLE
                    } else {
                        WalkSessionReadinessStatus.READY
                    },
                    reason = if (current == requirement) "required_capability_missing" else "",
                ),
            )
        }
        return collector.build(assessedAtEpochMs = 901)
    }
}
