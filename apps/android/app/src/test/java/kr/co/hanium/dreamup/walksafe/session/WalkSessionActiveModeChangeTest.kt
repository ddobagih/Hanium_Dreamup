package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * S03-7: the functional mode confirmed at start is part of the confirmation. An active walk never
 * adopts a different mode silently (SP-05-POLICY-001); a same-mode refresh keeps the walk.
 */
class WalkSessionActiveModeChangeTest {
    @Test
    fun activeFullWalkLosingDistanceSafetyStopsInsteadOfSwitchingSilently() {
        val lifecycle = active(WalkSessionMode.FULL)
        val epoch = lifecycle.snapshot().epoch

        lifecycle.handle(
            WalkSessionEvent.RuntimeReadinessChanged(
                ready(lifecycle, WalkSessionMode.DISTANCE_LIMITED),
            ),
        )

        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.UNAVAILABLE, lifecycle.snapshot().mode)
        assertEquals(epoch.walkSessionId, lifecycle.snapshot().epoch.walkSessionId)
        assertFalse(lifecycle.isRuntimeEpochCurrent(epoch))
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
        val lifecycle = active(WalkSessionMode.DISTANCE_LIMITED)
        val epoch = lifecycle.snapshot().epoch
        val refreshed = ready(lifecycle, WalkSessionMode.DISTANCE_LIMITED, assessedAt = 200L)

        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(refreshed))

        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        assertEquals(WalkSessionMode.DISTANCE_LIMITED, lifecycle.snapshot().mode)
        assertEquals(epoch, lifecycle.snapshot().epoch)
        assertSame(refreshed, lifecycle.snapshot().readiness)
    }

    private fun active(mode: WalkSessionMode): WalkSessionLifecycle {
        var ids = 0
        val lifecycle = WalkSessionLifecycle(
            sessionIdFactory = { "walk-${++ids}" },
            clock = { 100L },
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
        override: (WalkSessionReadinessRequirement) -> Pair<WalkSessionReadinessStatus, String>? =
            { null },
    ): WalkSessionReadinessSnapshot {
        val epoch = lifecycle.snapshot().epoch
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
