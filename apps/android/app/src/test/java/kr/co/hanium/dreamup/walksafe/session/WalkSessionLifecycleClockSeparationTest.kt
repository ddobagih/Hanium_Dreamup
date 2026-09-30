package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S03-10/S03-3: readiness age is measured on the monotonic clock that produced
 * [WalkSessionReadinessSnapshot.assessedAtElapsedRealtimeMs], never against the wall clock that
 * stamps [WalkSessionTransition.occurredAtEpochMs].
 */
class WalkSessionLifecycleClockSeparationTest {
    private var wallMs = 1_790_000_000_000L
    private var elapsedMs = 3_600_000L

    @Test
    fun freshElapsedRealtimeReadinessStartsDespiteAFarAwayWallClock() {
        val lifecycle = lifecycle()
        val token = readyForStart(lifecycle)

        elapsedMs += 1_000L
        val transition = lifecycle.handle(WalkSessionEvent.StartRequested(token))

        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        assertEquals(wallMs, transition.occurredAtEpochMs)
    }

    @Test
    fun readinessOlderThanTheTtlOnTheMonotonicClockReturnsToRecheck() {
        val lifecycle = lifecycle()
        val token = readyForStart(lifecycle)

        elapsedMs += WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS + 1L
        lifecycle.handle(WalkSessionEvent.StartRequested(token))

        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertNull(lifecycle.snapshot().readiness)
    }

    @Test
    fun wallClockJumpsDoNotExpireOrExtendReadiness() {
        val forward = lifecycle()
        val forwardToken = readyForStart(forward)
        wallMs += 24L * 60L * 60L * 1_000L
        forward.handle(WalkSessionEvent.StartRequested(forwardToken))
        assertEquals(WalkSessionState.ACTIVE, forward.snapshot().state)

        val backward = lifecycle()
        val backwardToken = readyForStart(backward)
        wallMs -= 48L * 60L * 60L * 1_000L
        elapsedMs += WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS + 1L
        backward.handle(WalkSessionEvent.StartRequested(backwardToken))
        assertEquals(WalkSessionState.READY, backward.snapshot().state)
    }

    @Test
    fun resumeConfirmationUsesTheMonotonicReadinessAge() {
        val lifecycle = lifecycle()
        lifecycle.handle(WalkSessionEvent.StartRequested(readyForStart(lifecycle)))
        lifecycle.handle(WalkSessionEvent.RecheckRequested)
        lifecycle.handle(
            WalkSessionEvent.RecoveryRecheckCompleted(
                ready(lifecycle.snapshot().epoch, WalkSessionAction.RESUME_WALK),
            ),
        )
        val resumeToken = lifecycle.snapshot().confirmationToken
            ?: throw AssertionError("fresh recovery readiness must produce a resume token")

        elapsedMs += 1_000L
        lifecycle.handle(
            WalkSessionEvent.ResumeConfirmationReceived(
                WalkSessionResumeConfirmation.START,
                resumeToken,
            ),
        )

        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
    }

    private fun lifecycle() = WalkSessionLifecycle(
        sessionIdFactory = { "walk-clock-separation" },
        clock = { wallMs },
        elapsedRealtimeClock = { elapsedMs },
    ).also { it.handle(WalkSessionEvent.EnteredForeground) }

    private fun readyForStart(lifecycle: WalkSessionLifecycle): WalkSessionConfirmationToken {
        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(
                ready(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK),
            ),
        )
        return lifecycle.snapshot().confirmationToken
            ?: throw AssertionError("fresh readiness must produce a start token")
    }

    private fun ready(epoch: WalkRuntimeEpoch, action: WalkSessionAction): WalkSessionReadinessSnapshot {
        val plan = WalkSessionReadinessPlan(action, WalkSessionMode.FULL)
        val collector = WalkSessionReadinessCollector(epoch, plan)
        plan.requiredRequirements.forEach { requirement ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = WalkSessionReadinessStatus.READY,
                ),
            )
        }
        return collector.build(assessedAtElapsedRealtimeMs = elapsedMs)
    }
}
