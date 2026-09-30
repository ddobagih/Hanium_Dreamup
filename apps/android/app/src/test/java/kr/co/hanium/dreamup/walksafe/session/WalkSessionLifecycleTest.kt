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
    fun startConfirmationExpiresImmediatelyAfterTtlBoundaryAndRequiresFreshCheck() {
        var now = 100L
        val atBoundary = foregroundLifecycle(clock = { now })
        atBoundary.handle(
            WalkSessionEvent.InitialCheckCompleted(
                ready(atBoundary, WalkSessionAction.START_WALK, WalkSessionMode.FULL),
            ),
        )
        val validToken = atBoundary.snapshot().confirmationToken!!
        now += WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS

        assertTrue(atBoundary.handle(WalkSessionEvent.StartRequested(validToken)).changed)
        assertEquals(WalkSessionState.ACTIVE, atBoundary.snapshot().state)

        now = 100L
        val expired = foregroundLifecycle(clock = { now })
        expired.handle(
            WalkSessionEvent.InitialCheckCompleted(
                ready(expired, WalkSessionAction.START_WALK, WalkSessionMode.FULL),
            ),
        )
        val expiredToken = expired.snapshot().confirmationToken!!
        val oldEpoch = expired.snapshot().epoch
        now += WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS + 1L

        assertTrue(expired.handle(WalkSessionEvent.StartRequested(expiredToken)).changed)
        assertSnapshot(expired, WalkSessionState.READY, WalkSessionMode.UNAVAILABLE, null, true)
        assertEquals(
            oldEpoch.copy(recoveryGeneration = oldEpoch.recoveryGeneration + 1L),
            expired.snapshot().epoch,
        )
        assertNull(expired.snapshot().readiness)
        assertNull(expired.snapshot().confirmationToken)
        assertFalse(expired.handle(WalkSessionEvent.StartRequested(expiredToken)).changed)

        expired.handle(
            WalkSessionEvent.InitialCheckCompleted(
                ready(
                    expired,
                    WalkSessionAction.START_WALK,
                    WalkSessionMode.FULL,
                    assessedAtElapsedRealtimeMs = now,
                ),
            ),
        )
        assertTrue(
            expired.handle(
                WalkSessionEvent.StartRequested(expired.snapshot().confirmationToken!!),
            ).changed,
        )
        assertEquals(WalkSessionState.ACTIVE, expired.snapshot().state)
    }

    @Test
    fun resumeConfirmationExpiresImmediatelyAfterTtlBoundaryAndRequiresFreshRecheck() {
        var now = 100L
        val atBoundary = awaitingConfirmationLifecycle(clock = { now })
        val validToken = atBoundary.snapshot().confirmationToken!!
        now += WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS

        assertTrue(
            atBoundary.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    WalkSessionResumeConfirmation.START,
                    validToken,
                ),
            ).changed,
        )
        assertEquals(WalkSessionState.ACTIVE, atBoundary.snapshot().state)

        now = 100L
        val expired = awaitingConfirmationLifecycle(clock = { now })
        val expiredToken = expired.snapshot().confirmationToken!!
        val oldEpoch = expired.snapshot().epoch
        now += WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS + 1L

        assertTrue(
            expired.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    WalkSessionResumeConfirmation.START,
                    expiredToken,
                ),
            ).changed,
        )
        assertSnapshot(
            expired,
            WalkSessionState.PAUSED,
            WalkSessionMode.FULL,
            WalkSessionRecoveryStage.RECHECK_REQUIRED,
            true,
        )
        assertEquals(
            oldEpoch.copy(recoveryGeneration = oldEpoch.recoveryGeneration + 1L),
            expired.snapshot().epoch,
        )
        assertNull(expired.snapshot().readiness)
        assertNull(expired.snapshot().confirmationToken)
        assertFalse(
            expired.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    WalkSessionResumeConfirmation.START,
                    expiredToken,
                ),
            ).changed,
        )

        expired.handle(
            WalkSessionEvent.RecoveryRecheckCompleted(
                ready(
                    expired,
                    WalkSessionAction.RESUME_WALK,
                    WalkSessionMode.FULL,
                    assessedAtElapsedRealtimeMs = now,
                ),
            ),
        )
        assertTrue(
            expired.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    WalkSessionResumeConfirmation.START,
                    expired.snapshot().confirmationToken!!,
                ),
            ).changed,
        )
        assertEquals(WalkSessionState.ACTIVE, expired.snapshot().state)
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
    fun repeatedSafetyStopRequestsRetainStoppedEpochAndReportEachReason() {
        val lifecycle = activeLifecycle(WalkSessionMode.FULL)
        val activeEpoch = lifecycle.snapshot().epoch

        val firstStop = lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        val stopped = lifecycle.snapshot()
        assertTrue(firstStop.changed)
        assertEquals(activeEpoch.recoveryGeneration + 1, stopped.epoch.recoveryGeneration)
        assertEquals(WalkSessionState.SAFE_STOP, stopped.state)
        assertEquals(WalkSessionMode.UNAVAILABLE, stopped.mode)
        assertNull(stopped.readiness)
        assertFalse(lifecycle.isRuntimeEpochCurrent(activeEpoch))

        repeat(2) {
            val repeatedStop = lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
            assertFalse(repeatedStop.changed)
            assertEquals(stopped, repeatedStop.previous)
            assertEquals(stopped, repeatedStop.current)
            assertEquals(
                WalkSessionTransitionReason.SAFETY_STOP_REQUESTED,
                repeatedStop.reason,
            )
        }

        assertFalse(lifecycle.handle(WalkSessionEvent.RecheckRequested).changed)
        assertEquals(stopped, lifecycle.snapshot())

        val newWalk = lifecycle.handle(WalkSessionEvent.NewWalkRequested)
        assertTrue(newWalk.changed)
        assertNotEquals(stopped.epoch.walkSessionId, newWalk.current.epoch.walkSessionId)
        assertEquals(WalkSessionState.READY, newWalk.current.state)
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
                if (response == WalkSessionResumeConfirmation.CANCEL) {
                    WalkSessionRecoveryStage.RECHECK_REQUIRED
                } else WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION,
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

    @Test
    fun eachPendingInitialRequirementRevokesStartUntilFreshExplicitConfirmation() {
        val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
        for (requirement in plan.requiredRequirements) {
            val lifecycle = foregroundLifecycle()
            val originalReady = ready(lifecycle, WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            assertTrue(lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(originalReady)).changed)
            val waiting = lifecycle.snapshot()
            val oldToken = checkNotNull(waiting.confirmationToken)
            assertSnapshot(lifecycle, WalkSessionState.READY, WalkSessionMode.FULL, null, true)
            assertEquals(originalReady, waiting.readiness)

            val pending = pendingRequirementFrom(originalReady, requirement)
            assertEquals(waiting.epoch, pending.epoch)
            assertEquals(WalkSessionAction.START_WALK, pending.plan.action)
            assertEquals(WalkSessionMode.FULL, pending.plan.mode)
            assertEquals(
                setOf(requirement),
                pending.observations.filterValues {
                    it.status == WalkSessionReadinessStatus.PENDING
                }.keys,
            )
            assertFalse(pending.isReady)
            assertFalse(pending.hasUnavailableRequirement)

            assertTrue(lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(pending)).changed)

            val invalidated = lifecycle.snapshot()
            assertSnapshot(lifecycle, WalkSessionState.READY, WalkSessionMode.UNAVAILABLE, null, true)
            assertEquals(
                waiting.epoch.copy(recoveryGeneration = waiting.epoch.recoveryGeneration + 1L),
                invalidated.epoch,
            )
            assertNull(invalidated.readiness)
            assertNull(invalidated.confirmationToken)
            assertFalse(lifecycle.handle(WalkSessionEvent.StartRequested(oldToken)).changed)
            assertFalse(lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(originalReady)).changed)
            assertEquals(invalidated, lifecycle.snapshot())

            val fresh = ready(lifecycle, WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            assertEquals(invalidated.epoch, fresh.epoch)
            assertTrue(lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(fresh)).changed)
            val freshWaiting = lifecycle.snapshot()
            val newToken = checkNotNull(freshWaiting.confirmationToken)
            assertSnapshot(lifecycle, WalkSessionState.READY, WalkSessionMode.FULL, null, true)
            assertEquals(fresh, freshWaiting.readiness)
            assertEquals(invalidated.epoch, newToken.epoch)
            assertNotEquals(oldToken, newToken)
            assertFalse(lifecycle.handle(WalkSessionEvent.StartRequested(oldToken)).changed)
            assertEquals(freshWaiting, lifecycle.snapshot())

            assertTrue(lifecycle.handle(WalkSessionEvent.StartRequested(newToken)).changed)
            assertSnapshot(lifecycle, WalkSessionState.ACTIVE, WalkSessionMode.FULL, null, true)
        }
    }

    @Test
    fun eachPendingRuntimeRequirementRevokesReadyStartButDuplicateReadyPreservesIt() {
        val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
        for (requirement in plan.requiredRequirements) {
            val lifecycle = foregroundLifecycle()
            val originalReady = ready(lifecycle, WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            assertTrue(lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(originalReady)).changed)
            val waiting = lifecycle.snapshot()
            val oldToken = checkNotNull(waiting.confirmationToken)
            assertSnapshot(lifecycle, WalkSessionState.READY, WalkSessionMode.FULL, null, true)
            assertEquals(waiting.epoch, originalReady.epoch)
            assertEquals(WalkSessionAction.START_WALK, originalReady.plan.action)

            assertFalse(lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(originalReady)).changed)
            assertEquals(waiting, lifecycle.snapshot())
            assertEquals(oldToken, lifecycle.snapshot().confirmationToken)
            assertEquals(waiting.epoch, lifecycle.snapshot().epoch)

            val pending = pendingRequirementFrom(originalReady, requirement)
            assertEquals(waiting.epoch, pending.epoch)
            assertEquals(WalkSessionAction.START_WALK, pending.plan.action)
            assertEquals(WalkSessionMode.FULL, pending.plan.mode)
            assertEquals(
                setOf(requirement),
                pending.observations.filterValues {
                    it.status == WalkSessionReadinessStatus.PENDING
                }.keys,
            )
            assertFalse(pending.isReady)
            assertFalse(pending.hasUnavailableRequirement)

            assertTrue(lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(pending)).changed)

            val invalidated = lifecycle.snapshot()
            assertSnapshot(lifecycle, WalkSessionState.READY, WalkSessionMode.UNAVAILABLE, null, true)
            assertEquals(
                waiting.epoch.copy(recoveryGeneration = waiting.epoch.recoveryGeneration + 1L),
                invalidated.epoch,
            )
            assertNull(invalidated.readiness)
            assertNull(invalidated.confirmationToken)
            assertFalse(lifecycle.handle(WalkSessionEvent.StartRequested(oldToken)).changed)
            assertFalse(lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(originalReady)).changed)
            assertFalse(lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(originalReady)).changed)
            assertEquals(invalidated, lifecycle.snapshot())

            val fresh = ready(lifecycle, WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            assertEquals(invalidated.epoch, fresh.epoch)
            assertTrue(lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(fresh)).changed)
            val freshWaiting = lifecycle.snapshot()
            val newToken = checkNotNull(freshWaiting.confirmationToken)
            assertSnapshot(lifecycle, WalkSessionState.READY, WalkSessionMode.FULL, null, true)
            assertEquals(fresh, freshWaiting.readiness)
            assertNotEquals(oldToken, newToken)
            assertFalse(lifecycle.handle(WalkSessionEvent.StartRequested(oldToken)).changed)
            assertEquals(freshWaiting, lifecycle.snapshot())

            assertTrue(lifecycle.handle(WalkSessionEvent.StartRequested(newToken)).changed)
            assertSnapshot(lifecycle, WalkSessionState.ACTIVE, WalkSessionMode.FULL, null, true)
        }
    }

    @Test
    fun eachPendingResumeRequirementRequiresFreshRecheckAndExplicitNewResumeToken() {
        val plan = WalkSessionReadinessPlan(WalkSessionAction.RESUME_WALK, WalkSessionMode.FULL)
        for (requirement in plan.requiredRequirements) {
            val lifecycle = awaitingConfirmationLifecycle()
            val waiting = lifecycle.snapshot()
            val originalReady = checkNotNull(waiting.readiness)
            val oldToken = checkNotNull(waiting.confirmationToken)
            assertSnapshot(
                lifecycle,
                WalkSessionState.PAUSED,
                WalkSessionMode.FULL,
                WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION,
                true,
            )
            val pending = pendingRequirementFrom(originalReady, requirement)
            assertEquals(waiting.epoch, pending.epoch)
            assertEquals(WalkSessionAction.RESUME_WALK, pending.plan.action)
            assertEquals(WalkSessionMode.FULL, pending.plan.mode)
            assertEquals(
                setOf(requirement),
                pending.observations.filterValues {
                    it.status == WalkSessionReadinessStatus.PENDING
                }.keys,
            )
            assertFalse(pending.isReady)
            assertFalse(pending.hasUnavailableRequirement)

            assertTrue(lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(pending)).changed)

            val invalidated = lifecycle.snapshot()
            assertSnapshot(
                lifecycle,
                WalkSessionState.PAUSED,
                WalkSessionMode.UNAVAILABLE,
                WalkSessionRecoveryStage.RECHECK_REQUIRED,
                true,
            )
            assertEquals(
                waiting.epoch.copy(recoveryGeneration = waiting.epoch.recoveryGeneration + 1L),
                invalidated.epoch,
            )
            assertNull(invalidated.readiness)
            assertNull(invalidated.confirmationToken)
            assertFalse(
                lifecycle.handle(
                    WalkSessionEvent.ResumeConfirmationReceived(
                        WalkSessionResumeConfirmation.START,
                        oldToken,
                    ),
                ).changed,
            )
            assertFalse(lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(originalReady)).changed)
            assertFalse(lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(originalReady)).changed)
            assertEquals(invalidated, lifecycle.snapshot())

            val fresh = ready(lifecycle, WalkSessionAction.RESUME_WALK, WalkSessionMode.FULL)
            assertEquals(invalidated.epoch, fresh.epoch)
            assertFalse(lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(fresh)).changed)
            assertEquals(invalidated, lifecycle.snapshot())
            assertTrue(lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(fresh)).changed)
            val freshWaiting = lifecycle.snapshot()
            val newToken = checkNotNull(freshWaiting.confirmationToken)
            assertSnapshot(
                lifecycle,
                WalkSessionState.PAUSED,
                WalkSessionMode.FULL,
                WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION,
                true,
            )
            assertEquals(fresh, freshWaiting.readiness)
            assertEquals(invalidated.epoch, newToken.epoch)
            assertNotEquals(oldToken, newToken)
            assertFalse(
                lifecycle.handle(
                    WalkSessionEvent.ResumeConfirmationReceived(
                        WalkSessionResumeConfirmation.START,
                        oldToken,
                    ),
                ).changed,
            )
            assertFalse(lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(originalReady)).changed)
            assertFalse(lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(originalReady)).changed)
            assertEquals(freshWaiting, lifecycle.snapshot())

            assertTrue(
                lifecycle.handle(
                    WalkSessionEvent.ResumeConfirmationReceived(
                        WalkSessionResumeConfirmation.START,
                        newToken,
                    ),
                ).changed,
            )
            assertSnapshot(lifecycle, WalkSessionState.ACTIVE, WalkSessionMode.FULL, null, true)
        }
    }

    private fun pendingRequirementFrom(
        readiness: WalkSessionReadinessSnapshot,
        pendingRequirement: WalkSessionReadinessRequirement,
    ): WalkSessionReadinessSnapshot {
        require(readiness.plan.requires(pendingRequirement))
        val collector = WalkSessionReadinessCollector(readiness.epoch, readiness.plan)
        readiness.plan.requiredRequirements.forEach { requirement ->
            val observation = readiness.observations.getValue(requirement)
            collector.record(
                if (requirement == pendingRequirement) {
                    observation.copy(
                        status = WalkSessionReadinessStatus.PENDING,
                        reason = "required_evidence_pending",
                    )
                } else observation,
            )
        }
        return collector.build(assessedAtElapsedRealtimeMs = readiness.assessedAtElapsedRealtimeMs)
    }

    private fun foregroundLifecycle(clock: () -> Long = { 100L }) =
        WalkSessionLifecycle(clock = clock).also {
        it.handle(WalkSessionEvent.EnteredForeground)
    }

    private fun activeLifecycle(
        mode: WalkSessionMode,
        clock: () -> Long = { 100L },
    ) = foregroundLifecycle(clock).also {
        val readiness = ready(it, WalkSessionAction.START_WALK, mode)
        it.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
        it.handle(WalkSessionEvent.StartRequested(it.snapshot().confirmationToken!!))
    }

    private fun pausedForegroundLifecycle(
        mode: WalkSessionMode,
        clock: () -> Long = { 100L },
    ) = activeLifecycle(mode, clock).also {
        it.handle(WalkSessionEvent.EnteredBackground)
        it.handle(WalkSessionEvent.EnteredForeground)
    }

    private fun awaitingConfirmationLifecycle(clock: () -> Long = { 100L }) =
        pausedForegroundLifecycle(WalkSessionMode.FULL, clock).also {
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
        assessedAtElapsedRealtimeMs: Long = 100L,
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
        return collector.build(assessedAtElapsedRealtimeMs = assessedAtElapsedRealtimeMs)
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
        return collector.build(assessedAtElapsedRealtimeMs = 100)
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
