package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSessionLifecycleTest {
    @Test
    fun successfulInitialCheckRequiresCurrentExplicitStartToken() {
        val lifecycle = foregroundLifecycle()
        val readiness = ready(lifecycle, WalkSessionAction.START_WALK, WalkSessionMode.FULL)

        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))

        assertSnapshot(
            lifecycle = lifecycle,
            state = WalkSessionState.READY,
            mode = WalkSessionMode.FULL,
            recoveryStage = null,
            isForeground = true,
        )
        lifecycle.handle(
            WalkSessionEvent.StartRequested(
                lifecycle.snapshot().confirmationToken!!,
            ),
        )
        assertSnapshot(
            lifecycle = lifecycle,
            state = WalkSessionState.ACTIVE,
            mode = WalkSessionMode.FULL,
            recoveryStage = null,
            isForeground = true,
        )
    }

    @Test
    fun unavailableInitialCheckFailsClosedIntoSafeStop() {
        val lifecycle = foregroundLifecycle()

        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(
                blocked(
                    lifecycle = lifecycle,
                    action = WalkSessionAction.START_WALK,
                    mode = WalkSessionMode.FULL,
                ),
            ),
        )

        assertSnapshot(
            lifecycle = lifecycle,
            state = WalkSessionState.SAFE_STOP,
            mode = WalkSessionMode.UNAVAILABLE,
            recoveryStage = null,
            isForeground = true,
        )
    }

    @Test
    fun foregroundReturnCannotResumeBeforeRecheckAndExactStartConfirmation() {
        val lifecycle = activeLifecycle(WalkSessionMode.FULL)

        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        assertSnapshot(
            lifecycle = lifecycle,
            state = WalkSessionState.PAUSED,
            mode = WalkSessionMode.FULL,
            recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
            isForeground = false,
        )

        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val staleToken = WalkSessionConfirmationToken(
            ready(lifecycle, WalkSessionAction.RESUME_WALK, WalkSessionMode.FULL),
        )
        assertFalse(
            lifecycle.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    confirmation = WalkSessionResumeConfirmation.START,
                    token = staleToken,
                ),
            ).changed,
        )

        lifecycle.handle(
            WalkSessionEvent.RecoveryRecheckCompleted(
                ready(lifecycle, WalkSessionAction.RESUME_WALK, WalkSessionMode.FULL),
            ),
        )
        assertSnapshot(
            lifecycle = lifecycle,
            state = WalkSessionState.PAUSED,
            mode = WalkSessionMode.FULL,
            recoveryStage = WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION,
            isForeground = true,
        )

        lifecycle.handle(
            WalkSessionEvent.ResumeConfirmationReceived(
                confirmation = WalkSessionResumeConfirmation.START,
                token = lifecycle.snapshot().confirmationToken!!,
            ),
        )
        assertSnapshot(
            lifecycle = lifecycle,
            state = WalkSessionState.ACTIVE,
            mode = WalkSessionMode.FULL,
            recoveryStage = null,
            isForeground = true,
        )
    }

    @Test
    fun leavingForegroundWhileAwaitingConfirmationRequiresAnotherRecheck() {
        val lifecycle = awaitingConfirmationLifecycle()
        val staleToken = lifecycle.snapshot().confirmationToken!!

        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)

        assertSnapshot(
            lifecycle = lifecycle,
            state = WalkSessionState.PAUSED,
            mode = WalkSessionMode.FULL,
            recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
            isForeground = true,
        )
        assertFalse(
            lifecycle.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    confirmation = WalkSessionResumeConfirmation.START,
                    token = staleToken,
                ),
            ).changed,
        )
    }

    @Test
    fun failedRecoveryRecheckEntersSafeStop() {
        val lifecycle = pausedForegroundLifecycle(WalkSessionMode.FULL)

        lifecycle.handle(
            WalkSessionEvent.RecoveryRecheckCompleted(
                blocked(
                    lifecycle = lifecycle,
                    action = WalkSessionAction.RESUME_WALK,
                    mode = WalkSessionMode.FULL,
                ),
            ),
        )

        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
    }

    @Test
    fun distanceLimitedModeRemainsSeparateFromPausedLifecycleState() {
        val lifecycle = activeLifecycle(WalkSessionMode.FULL)
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(
            WalkSessionEvent.RecoveryRecheckCompleted(
                ready(
                    lifecycle,
                    WalkSessionAction.RESUME_WALK,
                    WalkSessionMode.DISTANCE_LIMITED,
                ),
            ),
        )

        assertSnapshot(
            lifecycle = lifecycle,
            state = WalkSessionState.PAUSED,
            mode = WalkSessionMode.DISTANCE_LIMITED,
            recoveryStage = WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION,
            isForeground = true,
        )
    }

    @Test
    fun safetyStopCannotBeBypassedByRecheckOrResumeConfirmation() {
        val lifecycle = activeLifecycle(WalkSessionMode.FULL)
        lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        val stopped = lifecycle.snapshot()

        assertFalse(
            lifecycle.handle(
                WalkSessionEvent.RecoveryRecheckCompleted(
                    ready(
                        lifecycle,
                        WalkSessionAction.RESUME_WALK,
                        WalkSessionMode.FULL,
                    ),
                ),
            ).changed,
        )
        assertNull(stopped.confirmationToken)
        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
    }

    @Test
    fun cancellationNoResponseAndMisrecognitionRemainPaused() {
        listOf(
            WalkSessionResumeConfirmation.CANCEL,
            WalkSessionResumeConfirmation.NO_RESPONSE,
            WalkSessionResumeConfirmation.UNRECOGNIZED,
        ).forEach { response ->
            val lifecycle = awaitingConfirmationLifecycle()
            val token = lifecycle.snapshot().confirmationToken!!

            lifecycle.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    confirmation = response,
                    token = token,
                ),
            )

            assertEquals(WalkSessionState.PAUSED, lifecycle.snapshot().state)
            assertEquals(
                WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION,
                lifecycle.snapshot().recoveryStage,
            )
        }
    }

    @Test
    fun onlyExactKoreanStartAndCancelPhrasesAreRecognized() {
        assertEquals(
            WalkSessionResumeConfirmation.START,
            WalkSessionResumeConfirmation.fromRecognizedText(" 시작 "),
        )
        assertEquals(
            WalkSessionResumeConfirmation.CANCEL,
            WalkSessionResumeConfirmation.fromRecognizedText("취소"),
        )
        assertEquals(
            WalkSessionResumeConfirmation.NO_RESPONSE,
            WalkSessionResumeConfirmation.fromRecognizedText(null),
        )
        listOf("시작해", "다시 시작", "START", "취소해").forEach { transcript ->
            assertEquals(
                WalkSessionResumeConfirmation.UNRECOGNIZED,
                WalkSessionResumeConfirmation.fromRecognizedText(transcript),
            )
        }
    }

    @Test
    fun processRestartCreatesAFreshReadyWalkIdentity() {
        val lifecycle = activeLifecycle(WalkSessionMode.FULL)
        val previousWalkId = lifecycle.snapshot().epoch.walkSessionId

        lifecycle.handle(WalkSessionEvent.ProcessRestarted)

        assertNotEquals(previousWalkId, lifecycle.snapshot().epoch.walkSessionId)
        assertSnapshot(
            lifecycle = lifecycle,
            state = WalkSessionState.READY,
            mode = WalkSessionMode.UNAVAILABLE,
            recoveryStage = null,
            isForeground = false,
        )
        assertNull(lifecycle.snapshot().readiness)
    }

    @Test
    fun processRestartNeverRestoresPreviouslyActiveSession() {
        val lifecycle = activeLifecycle(WalkSessionMode.FULL)
        val previousEpoch = lifecycle.snapshot().epoch

        lifecycle.handle(WalkSessionEvent.ProcessRestarted)

        assertNotEquals(previousEpoch, lifecycle.snapshot().epoch)
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.UNAVAILABLE, lifecycle.snapshot().mode)
        assertNull(lifecycle.snapshot().readiness)
        assertNull(lifecycle.snapshot().confirmationToken)
    }

    @Test
    fun duplicateLifecycleConfirmationAndEndEventsAreIdempotent() {
        val lifecycle = activeLifecycle(WalkSessionMode.FULL)
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        assertFalse(lifecycle.handle(WalkSessionEvent.EnteredBackground).changed)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        assertFalse(lifecycle.handle(WalkSessionEvent.EnteredForeground).changed)

        val readiness = ready(
            lifecycle,
            WalkSessionAction.RESUME_WALK,
            WalkSessionMode.FULL,
        )
        lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(readiness))
        assertFalse(
            lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(readiness)).changed,
        )
        val token = lifecycle.snapshot().confirmationToken!!
        lifecycle.handle(
            WalkSessionEvent.ResumeConfirmationReceived(
                confirmation = WalkSessionResumeConfirmation.START,
                token = token,
            ),
        )
        assertFalse(
            lifecycle.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    confirmation = WalkSessionResumeConfirmation.START,
                    token = token,
                ),
            ).changed,
        )

        lifecycle.handle(WalkSessionEvent.EndRequested)
        assertFalse(lifecycle.handle(WalkSessionEvent.EndRequested).changed)
        assertEquals(WalkSessionState.ENDED, lifecycle.snapshot().state)
    }

    @Test
    fun initialCheckCannotAdvanceWhileBackgrounded() {
        val lifecycle = WalkSessionLifecycle()
        val readiness = ready(
            lifecycle,
            WalkSessionAction.START_WALK,
            WalkSessionMode.FULL,
        )

        val transition = lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(readiness),
        )

        assertFalse(transition.changed)
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.UNAVAILABLE, lifecycle.snapshot().mode)
    }

    private fun foregroundLifecycle() = WalkSessionLifecycle().also {
        it.handle(WalkSessionEvent.EnteredForeground)
    }

    private fun activeLifecycle(mode: WalkSessionMode) = foregroundLifecycle().also {
        val readiness = ready(it, WalkSessionAction.START_WALK, mode)
        it.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
        it.handle(WalkSessionEvent.StartRequested(it.snapshot().confirmationToken!!))
    }

    private fun pausedForegroundLifecycle(mode: WalkSessionMode) = activeLifecycle(mode).also {
        it.handle(WalkSessionEvent.EnteredBackground)
        it.handle(WalkSessionEvent.EnteredForeground)
    }

    private fun awaitingConfirmationLifecycle() =
        pausedForegroundLifecycle(WalkSessionMode.FULL).also {
            it.handle(
                WalkSessionEvent.RecoveryRecheckCompleted(
                    ready(
                        lifecycle = it,
                        action = WalkSessionAction.RESUME_WALK,
                        mode = WalkSessionMode.FULL,
                    ),
                ),
            )
        }

    private fun ready(
        lifecycle: WalkSessionLifecycle,
        action: WalkSessionAction,
        mode: WalkSessionMode,
    ): WalkSessionReadinessSnapshot {
        val epoch = lifecycle.snapshot().epoch
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
        return collector.build(assessedAtEpochMs = 100)
    }

    private fun blocked(
        lifecycle: WalkSessionLifecycle,
        action: WalkSessionAction,
        mode: WalkSessionMode,
    ): WalkSessionReadinessSnapshot {
        val epoch = lifecycle.snapshot().epoch
        val collector = WalkSessionReadinessCollector(
            epoch = epoch,
            plan = WalkSessionReadinessPlan(action = action, mode = mode),
        )
        collector.plan.requiredRequirements.forEachIndexed { index, requirement ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = if (index == 0) {
                        WalkSessionReadinessStatus.UNAVAILABLE
                    } else {
                        WalkSessionReadinessStatus.READY
                    },
                    reason = if (index == 0) "required_resource_unavailable" else "",
                ),
            )
        }
        return collector.build(assessedAtEpochMs = 100)
    }

    private fun assertSnapshot(
        lifecycle: WalkSessionLifecycle,
        state: WalkSessionState,
        mode: WalkSessionMode,
        recoveryStage: WalkSessionRecoveryStage?,
        isForeground: Boolean,
    ) {
        assertEquals(state, lifecycle.snapshot().state)
        assertEquals(mode, lifecycle.snapshot().mode)
        assertEquals(recoveryStage, lifecycle.snapshot().recoveryStage)
        assertEquals(isForeground, lifecycle.snapshot().isForeground)
    }
}
