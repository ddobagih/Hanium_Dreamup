package kr.co.hanium.dreamup.walksafe.session

import kr.co.hanium.dreamup.walksafe.network.MobileNetworkPreference
import org.junit.Assert.*
import org.junit.Test

class SessionAuthorityRegressionTest {
    @Test fun accountChangeDropsFormerConsentButKeepsTheDeviceNetworkPreference() {
        for (rememberOnly in listOf(false, true)) {
            val policy = grantedPolicy()
            val current = if (rememberOnly) policy.rememberActor("other") else policy.authenticated("other")
            assertFalse(current.rawCollectionConsentGranted)
            assertFalse(current.automaticReportConsentGranted)
            assertFalse(current.trainingReuseConsentGranted)
            // Local network preference is device-wide; server consent is separately actor-bound.
            assertEquals(MobileNetworkPreference.ALLOW_CELLULAR, current.mobileNetworkPreference)
        }
    }

    @Test fun sameActorReauthenticationPreservesThatActorsChoices() {
        val policy = grantedPolicy()
        policy.authenticationExpired()
        assertTrue(policy.authenticated(" actor ").trainingReuseConsentGranted)
    }

    @Test fun logoutThenOtherLoginCannotRecoverOldGrants() {
        val policy = grantedPolicy()
        policy.explicitLogout()
        assertFalse(policy.authenticated("other").automaticReportConsentGranted)
    }

    @Test fun cancellationInvalidatesLateResumeAndOldReadiness() {
        val lifecycle = activeLifecycle()
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val readiness = readiness(lifecycle, WalkSessionAction.RESUME_WALK)
        lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(readiness))
        val old = requireNotNull(lifecycle.snapshot().confirmationToken)
        lifecycle.handle(WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.CANCEL, old))
        lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(readiness))
        lifecycle.handle(WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.START, old))
        assertEquals(WalkSessionState.PAUSED, lifecycle.snapshot().state)
        assertNull(lifecycle.snapshot().confirmationToken)
        assertNotEquals(old.epoch, lifecycle.snapshot().epoch)
    }

    @Test fun permissionLossWhileAwaitingStartRevokesConfirmation() {
        val lifecycle = foregroundLifecycle()
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness(lifecycle)))
        val token = requireNotNull(lifecycle.snapshot().confirmationToken)
        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(readiness(lifecycle, blocked = true)))
        lifecycle.handle(WalkSessionEvent.StartRequested(token))
        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
        assertNull(lifecycle.snapshot().confirmationToken)
    }

    @Test fun pendingRecheckCannotLeaveTheOldStartTokenUsable() {
        val lifecycle = foregroundLifecycle()
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness(lifecycle)))
        val token = requireNotNull(lifecycle.snapshot().confirmationToken)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness(lifecycle, pending = true)))
        lifecycle.handle(WalkSessionEvent.StartRequested(token))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertNull(lifecycle.snapshot().confirmationToken)
        assertNotEquals(token.epoch, lifecycle.snapshot().epoch)
    }

    @Test fun permissionLossWhileAwaitingResumeCannotBeOverriddenByLateConfirmation() {
        val lifecycle = activeLifecycle()
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(readiness(lifecycle, WalkSessionAction.RESUME_WALK)))
        val token = requireNotNull(lifecycle.snapshot().confirmationToken)
        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(readiness(lifecycle, blocked = true)))
        lifecycle.handle(WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.START, token))
        assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
    }

    @Test fun endedWalkRejectsLateReadyAndStartEvents() {
        val lifecycle = foregroundLifecycle()
        val evidence = readiness(lifecycle)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(evidence))
        val token = requireNotNull(lifecycle.snapshot().confirmationToken)
        lifecycle.handle(WalkSessionEvent.EndRequested)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(evidence))
        lifecycle.handle(WalkSessionEvent.StartRequested(token))
        assertEquals(WalkSessionState.ENDED, lifecycle.snapshot().state)
        assertNull(lifecycle.currentRuntimeEpochOrNull())
    }

    private fun grantedPolicy() = PermissionSessionPolicy(PermissionSessionSnapshot(
        actorId = "actor", authentication = AuthenticationState.ACTIVE,
        rawCollectionConsentGranted = true, automaticReportConsentGranted = true,
        mobileNetworkPreference = MobileNetworkPreference.ALLOW_CELLULAR, trainingReuseConsentGranted = true,
    ))
    private fun foregroundLifecycle() = WalkSessionLifecycle(clock = { 100L }).apply { handle(WalkSessionEvent.EnteredForeground) }
    private fun activeLifecycle() = foregroundLifecycle().apply {
        handle(WalkSessionEvent.InitialCheckCompleted(readiness(this)))
        handle(WalkSessionEvent.StartRequested(requireNotNull(snapshot().confirmationToken)))
        assertEquals(WalkSessionState.ACTIVE, snapshot().state)
    }
    private fun readiness(
        lifecycle: WalkSessionLifecycle,
        action: WalkSessionAction = WalkSessionAction.START_WALK,
        blocked: Boolean = false,
        pending: Boolean = false,
    ): WalkSessionReadinessSnapshot {
        val epoch = lifecycle.snapshot().epoch
        val plan = WalkSessionReadinessPlan(action, WalkSessionMode.FULL)
        return WalkSessionReadinessCollector(epoch, plan).apply {
            plan.requiredRequirements.forEach { requirement ->
                val missing = requirement == WalkSessionReadinessRequirement.CAMERA && (blocked || pending)
                record(WalkSessionReadinessObservation(epoch, requirement,
                    if (!missing) WalkSessionReadinessStatus.READY
                    else if (blocked) WalkSessionReadinessStatus.UNAVAILABLE else WalkSessionReadinessStatus.PENDING,
                    if (missing) "camera_permission_revoked" else ""))
            }
        }.build(100L)
    }
}
