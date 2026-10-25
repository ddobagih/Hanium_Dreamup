package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A current distance-only restriction preserves navigation. Wider modes and unavailable
 * required inputs still stop the active walk; old confirmations never grant a wider mode.
 */
class WalkSessionActiveModeChangeTest {
    @Test
    fun activeFullWalkLosingOnlyDistanceKeepsItsEpochAndPublishesLimitedReadiness() {
        val lifecycle = active(WalkSessionMode.FULL)
        val epoch = lifecycle.snapshot().epoch
        val limited = ready(lifecycle, WalkSessionMode.DISTANCE_LIMITED)

        lifecycle.handle(
            WalkSessionEvent.RuntimeReadinessChanged(limited),
        )

        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.DISTANCE_LIMITED, lifecycle.snapshot().mode)
        assertEquals(epoch, lifecycle.snapshot().epoch)
        assertSame(limited, lifecycle.snapshot().readiness)
        assertEquals(WalkSessionReadinessStatus.NOT_REQUIRED,
            limited.observations.getValue(WalkSessionReadinessRequirement.METRIC_DISTANCE).status)
        assertTrue(lifecycle.isRuntimeEpochCurrent(epoch))
    }

    @Test
    fun activeDistanceLimitedWalkIsNotSilentlyWidenedToFull() {
        val lifecycle = active(WalkSessionMode.DISTANCE_LIMITED)

        lifecycle.handle(
            WalkSessionEvent.RuntimeReadinessChanged(ready(lifecycle, WalkSessionMode.FULL)),
        )

        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
    }

    @Test
    fun activeWalkLosingTheCameraSafetyStops() {
        val lifecycle = active(WalkSessionMode.FULL)

        lifecycle.handle(
            WalkSessionEvent.RuntimeReadinessChanged(
                ready(lifecycle, WalkSessionMode.FULL) { requirement ->
                    if (requirement == WalkSessionReadinessRequirement.CAMERA) {
                        WalkSessionReadinessStatus.UNAVAILABLE to "camera_permission_missing"
                    } else {
                        null
                    }
                },
            ),
        )

        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
        assertEquals(
            listOf("camera_permission_missing"),
            lifecycle.snapshot().readiness?.blockingReasons,
        )
    }

    @Test
    fun sameModeRuntimeRefreshKeepsTheActiveWalkWithTheNewEvidence() {
        var now = 100L
        val lifecycle = active(WalkSessionMode.DISTANCE_LIMITED, clock = { now })
        val epoch = lifecycle.snapshot().epoch
        now = 200L
        val refreshed = ready(lifecycle, WalkSessionMode.DISTANCE_LIMITED, assessedAt = now)

        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(refreshed))

        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.DISTANCE_LIMITED, lifecycle.snapshot().mode)
        assertEquals(epoch, lifecycle.snapshot().epoch)
        assertSame(refreshed, lifecycle.snapshot().readiness)
    }

    @Test
    fun distanceRestrictionCannotHidePendingOrUnavailableRequiredInputs() {
        val required = listOf(
            WalkSessionReadinessRequirement.CAMERA,
            WalkSessionReadinessRequirement.MODEL,
            WalkSessionReadinessRequirement.LOCATION,
            WalkSessionReadinessRequirement.VOICE_OUTPUT,
            WalkSessionReadinessRequirement.DEVICE_RESOURCES,
            WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT,
        )
        required.forEach { blocked ->
            listOf(WalkSessionReadinessStatus.PENDING, WalkSessionReadinessStatus.UNAVAILABLE).forEach { status ->
                val lifecycle = active(WalkSessionMode.FULL)
                val epoch = lifecycle.snapshot().epoch
                val limited = ready(lifecycle, WalkSessionMode.DISTANCE_LIMITED) { requirement ->
                    (status to "required_input_lost").takeIf { requirement == blocked }
                }

                lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(limited))

                assertEquals("$blocked/$status", WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
                assertEquals(WalkSessionMode.UNAVAILABLE, lifecycle.snapshot().mode)
                assertFalse(lifecycle.isRuntimeEpochCurrent(epoch))
            }
        }
    }

    @Test
    fun staleDistanceLimitedEvidenceStillSafetyStops() {
        var now = 100L
        val lifecycle = active(WalkSessionMode.FULL, clock = { now })
        val limited = ready(lifecycle, WalkSessionMode.DISTANCE_LIMITED).let { snapshot ->
            snapshot.copy(observations = snapshot.observations.mapValues { (requirement, observation) ->
                if (requirement == WalkSessionReadinessRequirement.CAMERA) {
                    observation.copy(observedAtElapsedRealtimeMs = 100L, maxAgeMs = 50L)
                } else observation
            })
        }
        now = 151L

        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(limited))

        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.UNAVAILABLE, lifecycle.snapshot().mode)
    }

    @Test
    fun evidenceInvalidAtAssessmentCannotBecomeValidJustBecauseItsCallbackArrivedLater() {
        var now = 100L
        val lifecycle = active(WalkSessionMode.FULL, clock = { now })
        val limited = withFutureCameraEvidence(ready(lifecycle, WalkSessionMode.DISTANCE_LIMITED))
        now = 200L
        assertFalse(limited.isReady)
        assertTrue(limited.isReadyAt(now))

        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(limited))

        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.UNAVAILABLE, lifecycle.snapshot().mode)
    }

    @Test
    fun lateInitialCheckCannotGrantConfirmationForAnInvalidAssessment() {
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "future-initial" }, clock = { 200L })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val initial = withFutureCameraEvidence(ready(lifecycle, WalkSessionMode.FULL))
        assertFalse(initial.isReady)
        assertTrue(initial.isReadyAt(200L))

        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(initial))

        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertEquals(null, lifecycle.snapshot().confirmationToken)
    }

    @Test
    fun lateRecoveryCheckCannotEnterConfirmationWithAnInvalidAssessment() {
        var now = 100L
        val lifecycle = active(WalkSessionMode.FULL, clock = { now })
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val resumeCollector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch,
            WalkSessionReadinessPlan(WalkSessionAction.RESUME_WALK, WalkSessionMode.FULL))
        resumeCollector.plan.requiredRequirements.forEach { requirement ->
            resumeCollector.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch,
                requirement, WalkSessionReadinessStatus.READY))
        }
        val recovery = withFutureCameraEvidence(resumeCollector.build(100L))
        now = 200L
        assertFalse(recovery.isReady)
        assertTrue(recovery.isReadyAt(now))

        lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(recovery))

        assertEquals(WalkSessionState.PAUSED, lifecycle.snapshot().state)
        assertEquals(WalkSessionRecoveryStage.RECHECK_REQUIRED, lifecycle.snapshot().recoveryStage)
        assertEquals(null, lifecycle.snapshot().confirmationToken)
    }

    @Test
    fun anotherEpochCannotRestrictTheCurrentActiveWalk() {
        val lifecycle = active(WalkSessionMode.FULL)
        val before = lifecycle.snapshot()
        val foreign = ready(lifecycle, WalkSessionMode.DISTANCE_LIMITED,
            epoch = before.epoch.copy(recoveryGeneration = before.epoch.recoveryGeneration + 1L))

        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(foreign))

        assertSame(before, lifecycle.snapshot())
    }

    @Test
    fun unavailableModeStillStopsEvenWhenItsObservationValuesAreReady() {
        val lifecycle = active(WalkSessionMode.FULL)

        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(ready(lifecycle, WalkSessionMode.UNAVAILABLE)))

        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.UNAVAILABLE, lifecycle.snapshot().mode)
    }

    @Test
    fun fullConfirmationCannotBeReusedAfterTheDistanceRestrictionOrRecoveryBoundary() {
        val lifecycle = active(WalkSessionMode.FULL)
        val oldToken = lifecycle.snapshot().confirmationToken!!
        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(ready(lifecycle, WalkSessionMode.DISTANCE_LIMITED)))
        val limited = lifecycle.snapshot()
        assertFalse(oldToken == limited.confirmationToken)

        lifecycle.handle(WalkSessionEvent.StartRequested(oldToken))
        lifecycle.handle(WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.START, oldToken))
        assertSame(limited, lifecycle.snapshot())

        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val paused = lifecycle.snapshot()
        lifecycle.handle(WalkSessionEvent.StartRequested(oldToken))
        lifecycle.handle(WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.START, oldToken))
        assertSame(paused, lifecycle.snapshot())
        assertEquals(WalkSessionState.PAUSED, paused.state)
        assertFalse(oldToken.epoch == paused.epoch)
    }

    @Test
    fun distanceRestrictionPublishesModeAndReadinessTogether() {
        val lifecycle = active(WalkSessionMode.FULL)
        val previous = lifecycle.snapshot()
        val limited = ready(lifecycle, WalkSessionMode.DISTANCE_LIMITED)
        var fenceCalled = false

        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(limited)) { before, next ->
            fenceCalled = true
            assertSame(previous, before)
            assertSame(previous, lifecycle.snapshot())
            assertEquals(WalkSessionMode.DISTANCE_LIMITED, next.mode)
            assertEquals(next.mode, next.readiness?.plan?.mode)
            assertSame(limited, next.readiness)
        }

        assertTrue(fenceCalled)
        assertEquals(WalkSessionMode.DISTANCE_LIMITED, lifecycle.snapshot().mode)
        assertSame(limited, lifecycle.snapshot().readiness)
    }

    private fun withFutureCameraEvidence(snapshot: WalkSessionReadinessSnapshot) = snapshot.copy(
        observations = snapshot.observations.mapValues { (requirement, observation) ->
            if (requirement == WalkSessionReadinessRequirement.CAMERA) {
                observation.copy(observedAtElapsedRealtimeMs = 150L, maxAgeMs = 5_000L)
            } else observation
        },
    )

    private fun active(
        mode: WalkSessionMode,
        clock: () -> Long = { 100L },
    ): WalkSessionLifecycle {
        var ids = 0
        val lifecycle = WalkSessionLifecycle(
            sessionIdFactory = { "walk-${++ids}" },
            clock = clock,
        )
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(ready(lifecycle, mode)))
        lifecycle.handle(
            WalkSessionEvent.StartRequested(lifecycle.snapshot().confirmationToken!!),
        )
        check(lifecycle.snapshot().state == WalkSessionState.ACTIVE)
        return lifecycle
    }

    private fun ready(
        lifecycle: WalkSessionLifecycle,
        mode: WalkSessionMode,
        assessedAt: Long = 100L,
        epoch: WalkRuntimeEpoch = lifecycle.snapshot().epoch,
        override: (WalkSessionReadinessRequirement) -> Pair<WalkSessionReadinessStatus, String>? =
            { null },
    ): WalkSessionReadinessSnapshot {
        val collector = WalkSessionReadinessCollector(
            epoch,
            WalkSessionReadinessPlan(WalkSessionAction.START_WALK, mode),
        )
        collector.plan.requiredRequirements.forEach { requirement ->
            val (status, reason) = override(requirement) ?: (WalkSessionReadinessStatus.READY to "")
            collector.record(WalkSessionReadinessObservation(epoch, requirement, status, reason))
        }
        return collector.build(assessedAtElapsedRealtimeMs = assessedAt)
    }
}
