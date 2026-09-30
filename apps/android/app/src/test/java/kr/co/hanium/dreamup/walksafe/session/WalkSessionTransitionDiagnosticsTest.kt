package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S03-28: rejected admissions carry a token-free reason for diagnostics. */
class WalkSessionTransitionDiagnosticsTest {
    private var now = 10_000L

    @Test
    fun acceptedStartHasNoRejection() {
        val lifecycle = foreground()
        val token = readyToken(lifecycle)
        val event = WalkSessionEvent.StartRequested(token)

        assertNull(rejection(lifecycle, event))
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
    }

    @Test
    fun startWithAReplacedTokenIsStale() {
        val lifecycle = foreground()
        val old = readyToken(lifecycle)
        now += 1_000L
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(ready(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK)))

        assertEquals(
            WalkSessionTransitionRejection.STALE_TOKEN,
            rejection(lifecycle, WalkSessionEvent.StartRequested(old)),
        )
    }

    @Test
    fun startFromTheBackgroundOrAnotherGenerationIsExplained() {
        val background = foreground()
        val token = readyToken(background)
        background.handle(WalkSessionEvent.EnteredBackground)
        assertEquals(
            WalkSessionTransitionRejection.NOT_FOREGROUND,
            rejection(background, WalkSessionEvent.StartRequested(token)),
        )

        val recheck = foreground()
        val earlier = readyToken(recheck)
        recheck.handle(WalkSessionEvent.RecheckRequested)
        recheck.handle(WalkSessionEvent.InitialCheckCompleted(ready(recheck.snapshot().epoch, WalkSessionAction.START_WALK)))
        assertEquals(
            WalkSessionTransitionRejection.STALE_EPOCH,
            rejection(recheck, WalkSessionEvent.StartRequested(earlier)),
        )
    }

    @Test
    fun expiredStartReadinessIsDistinguishedFromAStaleToken() {
        val lifecycle = foreground()
        val token = readyToken(lifecycle)
        now += WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS + 1L

        assertEquals(
            WalkSessionTransitionRejection.READINESS_EXPIRED,
            rejection(lifecycle, WalkSessionEvent.StartRequested(token)),
        )
    }

    @Test
    fun repeatedStartAfterActivationIsTheWrongState() {
        val lifecycle = foreground()
        val token = readyToken(lifecycle)
        lifecycle.handle(WalkSessionEvent.StartRequested(token))

        assertEquals(
            WalkSessionTransitionRejection.WRONG_STATE,
            rejection(lifecycle, WalkSessionEvent.StartRequested(token)),
        )
    }

    @Test
    fun resumeOutcomesAreSeparatedFromRejections() {
        val lifecycle = awaitingResume()
        val token = lifecycle.snapshot().confirmationToken!!

        assertEquals(
            WalkSessionTransitionRejection.UNCONFIRMED,
            rejection(
                lifecycle,
                WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.UNRECOGNIZED, token),
            ),
        )
        assertNull(
            rejection(
                lifecycle,
                WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.CANCEL, token),
            ),
        )
        assertEquals(
            WalkSessionTransitionRejection.WRONG_RECOVERY_STAGE,
            rejection(
                lifecycle,
                WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.START, token),
            ),
        )
    }

    @Test
    fun expiredResumeReadinessIsReported() {
        val lifecycle = awaitingResume()
        val token = lifecycle.snapshot().confirmationToken!!
        now += WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS + 1L

        assertEquals(
            WalkSessionTransitionRejection.READINESS_EXPIRED,
            rejection(
                lifecycle,
                WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.START, token),
            ),
        )
    }

    @Test
    fun readinessResultsForAnotherGenerationOrStillPendingAreExplained() {
        val lifecycle = foreground()
        val staleEpoch = lifecycle.snapshot().epoch
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)

        assertEquals(
            WalkSessionTransitionRejection.STALE_EPOCH,
            rejection(lifecycle, WalkSessionEvent.InitialCheckCompleted(ready(staleEpoch, WalkSessionAction.START_WALK))),
        )
        assertEquals(
            WalkSessionTransitionRejection.READINESS_NOT_READY,
            rejection(lifecycle, WalkSessionEvent.InitialCheckCompleted(pending(lifecycle.snapshot().epoch))),
        )
        assertEquals(
            WalkSessionTransitionRejection.WRONG_STATE,
            rejection(
                lifecycle,
                WalkSessionEvent.RecoveryRecheckCompleted(ready(lifecycle.snapshot().epoch, WalkSessionAction.RESUME_WALK)),
            ),
        )
        assertNull(rejection(lifecycle, WalkSessionEvent.RecheckRequested))
    }

    private fun rejection(lifecycle: WalkSessionLifecycle, event: WalkSessionEvent) =
        WalkSessionTransitionDiagnostics.rejectionOrNull(event, lifecycle.handle(event))

    private fun foreground() = WalkSessionLifecycle(
        sessionIdFactory = { "walk-diagnostics" },
        clock = { now },
    ).also { it.handle(WalkSessionEvent.EnteredForeground) }

    private fun readyToken(lifecycle: WalkSessionLifecycle): WalkSessionConfirmationToken {
        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(ready(lifecycle.snapshot().epoch, WalkSessionAction.START_WALK)),
        )
        return lifecycle.snapshot().confirmationToken!!
    }

    private fun awaitingResume(): WalkSessionLifecycle {
        val lifecycle = foreground()
        lifecycle.handle(WalkSessionEvent.StartRequested(readyToken(lifecycle)))
        lifecycle.handle(WalkSessionEvent.RecheckRequested)
        lifecycle.handle(
            WalkSessionEvent.RecoveryRecheckCompleted(ready(lifecycle.snapshot().epoch, WalkSessionAction.RESUME_WALK)),
        )
        return lifecycle
    }

    private fun ready(epoch: WalkRuntimeEpoch, action: WalkSessionAction) = snapshot(epoch, action) {
        WalkSessionReadinessStatus.READY
    }

    private fun pending(epoch: WalkRuntimeEpoch) = snapshot(epoch, WalkSessionAction.START_WALK) {
        if (it == WalkSessionReadinessRequirement.CAMERA) WalkSessionReadinessStatus.PENDING else WalkSessionReadinessStatus.READY
    }

    private fun snapshot(
        epoch: WalkRuntimeEpoch,
        action: WalkSessionAction,
        status: (WalkSessionReadinessRequirement) -> WalkSessionReadinessStatus,
    ): WalkSessionReadinessSnapshot {
        val collector = WalkSessionReadinessCollector(epoch, WalkSessionReadinessPlan(action, WalkSessionMode.FULL))
        collector.plan.requiredRequirements.forEach { requirement ->
            val current = status(requirement)
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = current,
                    reason = if (current == WalkSessionReadinessStatus.READY) "" else "camera_pending",
                ),
            )
        }
        // Positional: the assessment-time parameter is renamed by the S03-10 clock patch.
        return collector.build(now)
    }
}
