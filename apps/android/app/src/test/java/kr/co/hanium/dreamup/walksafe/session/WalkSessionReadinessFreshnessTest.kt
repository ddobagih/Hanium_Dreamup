package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** S03-9/S14-12: an old measurement wrapped in the current epoch is not current readiness. */
class WalkSessionReadinessFreshnessTest {
    private val epoch = WalkRuntimeEpoch("walk-1", 4)

    @Test
    fun oldMeasurementWithTheCurrentEpochIsNotReady() {
        val readiness = snapshot(assessedAt = 50_000L) { requirement ->
            if (requirement == WalkSessionReadinessRequirement.CAMERA) {
                measured(requirement, observedAt = 10_000L, maxAge = 30_000L)
            } else {
                null
            }
        }

        assertFalse(readiness.isReady)
        assertFalse(readiness.hasUnavailableRequirement)
        assertEquals(setOf(WalkSessionReadinessRequirement.CAMERA), readiness.staleRequirements)
        assertEquals(listOf("stale_observation:camera"), readiness.blockingReasons)
    }

    @Test
    fun measurementAtTheValidityBoundaryIsStillReady() {
        val readiness = snapshot(assessedAt = 40_000L) { requirement ->
            if (requirement == WalkSessionReadinessRequirement.LOCATION) {
                measured(requirement, observedAt = 10_000L, maxAge = 30_000L)
            } else {
                null
            }
        }
        assertTrue(readiness.isReady)
        assertTrue(readiness.staleRequirements.isEmpty())
    }

    @Test
    fun measurementFromTheFutureIsRejected() {
        val readiness = snapshot(assessedAt = 1_000L) { requirement ->
            if (requirement == WalkSessionReadinessRequirement.DEVICE_RESOURCES) {
                measured(requirement, observedAt = 1_001L, maxAge = 30_000L)
            } else {
                null
            }
        }
        assertFalse(readiness.isReady)
        assertEquals(listOf("stale_observation:device_resources"), readiness.blockingReasons)
    }

    @Test
    fun collectionTimeAndStaticObservationsNeedNoTimestamp() {
        assertTrue(snapshot(assessedAt = 999_999L) { null }.isReady)
    }

    @Test
    fun measuredObservationRequiresBothTimeAndValidity() {
        listOf(
            { WalkSessionReadinessObservation(epoch, WalkSessionReadinessRequirement.CAMERA, WalkSessionReadinessStatus.READY, observedAtElapsedRealtimeMs = 1L) },
            { WalkSessionReadinessObservation(epoch, WalkSessionReadinessRequirement.CAMERA, WalkSessionReadinessStatus.READY, maxAgeMs = 1L) },
            { WalkSessionReadinessObservation(epoch, WalkSessionReadinessRequirement.CAMERA, WalkSessionReadinessStatus.READY, observedAtElapsedRealtimeMs = -1L, maxAgeMs = 1L) },
            { WalkSessionReadinessObservation(epoch, WalkSessionReadinessRequirement.CAMERA, WalkSessionReadinessStatus.READY, observedAtElapsedRealtimeMs = 1L, maxAgeMs = 0L) },
        ).forEach { build ->
            try {
                build()
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun activeWalkWithStaleRuntimeEvidenceSafetyStops() {
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-9" }, clock = { 100L })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val start = snapshot(lifecycle.snapshot().epoch, assessedAt = 100L) { null }
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(start))
        lifecycle.handle(WalkSessionEvent.StartRequested(lifecycle.snapshot().confirmationToken!!))
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)

        val stale = snapshot(lifecycle.snapshot().epoch, assessedAt = 100L) { requirement ->
            if (requirement == WalkSessionReadinessRequirement.CAMERA) {
                WalkSessionReadinessObservation(
                    lifecycle.snapshot().epoch,
                    requirement,
                    WalkSessionReadinessStatus.READY,
                    observedAtElapsedRealtimeMs = 10L,
                    maxAgeMs = 50L,
                )
            } else {
                null
            }
        }
        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(stale))

        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
    }

    @Test
    fun startRechecksCameraEvidenceAgeEvenWhenTheConfirmationTokenIsFresh() {
        var now = 4_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-start" }, clock = { now })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(
                cameraSnapshot(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK, now),
            ),
        )
        val token = lifecycle.snapshot().confirmationToken!!

        // The token is only two seconds old, but its original camera evidence expired at 5s.
        now = 6_000L
        lifecycle.handle(WalkSessionEvent.StartRequested(token))

        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertEquals(null, lifecycle.snapshot().confirmationToken)
    }

    @Test
    fun resumeRechecksCameraEvidenceAgeEvenWhenTheConfirmationTokenIsFresh() {
        var now = 1_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-resume" }, clock = { now })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(
                cameraSnapshot(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK, now),
            ),
        )
        lifecycle.handle(WalkSessionEvent.StartRequested(lifecycle.snapshot().confirmationToken!!))
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        now = 4_000L
        lifecycle.handle(
            WalkSessionEvent.RecoveryRecheckCompleted(
                cameraSnapshot(lifecycle.snapshot().epoch, WalkSessionAction.RESUME_WALK, now),
            ),
        )
        val token = lifecycle.snapshot().confirmationToken!!

        now = 6_000L
        lifecycle.handle(
            WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.START, token),
        )

        assertEquals(WalkSessionState.PAUSED, lifecycle.snapshot().state)
        assertEquals(WalkSessionRecoveryStage.RECHECK_REQUIRED, lifecycle.snapshot().recoveryStage)
        assertEquals(null, lifecycle.snapshot().confirmationToken)
    }

    @Test
    fun freshCameraEvidenceStillStartsAtItsValidityBoundary() {
        var now = 4_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-fresh" }, clock = { now })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(
                cameraSnapshot(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK, now),
            ),
        )
        val token = lifecycle.snapshot().confirmationToken!!

        now = 5_000L
        lifecycle.handle(WalkSessionEvent.StartRequested(token))

        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
    }

    @Test
    fun delayedInitialCheckCannotPublishAnExpiredCameraObservation() {
        var now = 4_999L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-initial" }, clock = { now })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val readiness = cameraSnapshot(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK, now)
        assertTrue(readiness.isReady)

        now = 5_001L
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))

        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.UNAVAILABLE, lifecycle.snapshot().mode)
        assertEquals(null, lifecycle.snapshot().confirmationToken)
    }

    @Test
    fun delayedRecoveryCheckCannotPublishAnExpiredCameraObservation() {
        var now = 1_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-recovery" }, clock = { now })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(
                cameraSnapshot(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK, now),
            ),
        )
        lifecycle.handle(WalkSessionEvent.StartRequested(lifecycle.snapshot().confirmationToken!!))
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val readiness = cameraSnapshot(lifecycle.snapshot().epoch, WalkSessionAction.RESUME_WALK, 4_999L)
        assertTrue(readiness.isReady)

        now = 5_001L
        lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(readiness))

        assertEquals(WalkSessionState.PAUSED, lifecycle.snapshot().state)
        assertEquals(WalkSessionRecoveryStage.RECHECK_REQUIRED, lifecycle.snapshot().recoveryStage)
        assertEquals(null, lifecycle.snapshot().confirmationToken)
    }

    @Test
    fun delayedRuntimeCheckCannotKeepAnExpiredCameraObservationActive() {
        var now = 1_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-runtime" }, clock = { now })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(
                cameraSnapshot(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK, now),
            ),
        )
        lifecycle.handle(WalkSessionEvent.StartRequested(lifecycle.snapshot().confirmationToken!!))
        val readiness = cameraSnapshot(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK, 4_999L)
        assertTrue(readiness.isReady)

        now = 5_001L
        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(readiness))

        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
        assertEquals(null, lifecycle.snapshot().confirmationToken)
        assertEquals(now, lifecycle.snapshot().readiness!!.assessedAtElapsedRealtimeMs)
        assertEquals(
            setOf(WalkSessionReadinessRequirement.CAMERA),
            lifecycle.snapshot().readiness!!.staleRequirements,
        )
    }

    @Test
    fun delayedRuntimeCheckInvalidatesAnAwaitingStartWithTheSameObservations() {
        var now = 4_999L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-awaiting" }, clock = { now })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val readiness = cameraSnapshot(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK, now)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
        assertTrue(lifecycle.snapshot().confirmationToken != null)

        now = 5_001L
        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(readiness))

        assertEquals(WalkSessionMode.UNAVAILABLE, lifecycle.snapshot().mode)
        assertEquals(null, lifecycle.snapshot().confirmationToken)
    }

    private fun cameraSnapshot(
        epoch: WalkRuntimeEpoch,
        action: WalkSessionAction,
        assessedAt: Long,
    ): WalkSessionReadinessSnapshot {
        val collector = WalkSessionReadinessCollector(
            epoch,
            WalkSessionReadinessPlan(action, WalkSessionMode.FULL),
        )
        collector.plan.requiredRequirements.forEach { requirement ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = WalkSessionReadinessStatus.READY,
                    observedAtElapsedRealtimeMs = 0L.takeIf {
                        requirement == WalkSessionReadinessRequirement.CAMERA
                    },
                    maxAgeMs = 5_000L.takeIf {
                        requirement == WalkSessionReadinessRequirement.CAMERA
                    },
                ),
            )
        }
        return collector.build(assessedAtElapsedRealtimeMs = assessedAt)
    }

    private fun measured(
        requirement: WalkSessionReadinessRequirement,
        observedAt: Long,
        maxAge: Long,
    ) = WalkSessionReadinessObservation(
        epoch = epoch,
        requirement = requirement,
        status = WalkSessionReadinessStatus.READY,
        observedAtElapsedRealtimeMs = observedAt,
        maxAgeMs = maxAge,
    )

    private fun snapshot(
        assessedAt: Long,
        override: (WalkSessionReadinessRequirement) -> WalkSessionReadinessObservation?,
    ) = snapshot(epoch, assessedAt, override)

    private fun snapshot(
        epoch: WalkRuntimeEpoch,
        assessedAt: Long,
        override: (WalkSessionReadinessRequirement) -> WalkSessionReadinessObservation?,
    ): WalkSessionReadinessSnapshot {
        val collector = WalkSessionReadinessCollector(
            epoch,
            WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL),
        )
        collector.plan.requiredRequirements.forEach { requirement ->
            collector.record(
                override(requirement) ?: WalkSessionReadinessObservation(
                    epoch,
                    requirement,
                    WalkSessionReadinessStatus.READY,
                ),
            )
        }
        return collector.build(assessedAtElapsedRealtimeMs = assessedAt)
    }
}
