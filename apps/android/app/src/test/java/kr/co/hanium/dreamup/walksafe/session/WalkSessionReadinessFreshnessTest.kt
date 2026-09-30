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
