package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual admission, gateway ownership and lifecycle; measurement/network effects use fixtures. */
class WalkStartLivenessRegressionTest {
    @Test fun currentReadyMeasurementsReplaceExpiredPreviousReadinessBeforeStarting() {
        val f = AdmissionFixture()
        val old = f.gateway.prepare()
        f.gateway.now += 30_001L
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        val current = requireNotNull(f.gateway.lifecycle.snapshot().confirmationToken)
        assertNotEquals("A new real readiness capture must replace an expired token", old, current)
        assertEquals(f.gateway.now, current.readiness.assessedAtElapsedRealtimeMs)
        assertEquals(0, f.gateway.activations)
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertEquals(1, f.gateway.background.size)
        f.gateway.runBackground()
        assertEquals(WalkSessionState.ACTIVE, f.gateway.lifecycle.snapshot().state)
        assertEquals(1, f.gateway.activations)
        assertTrue(f.gateway.retries.isEmpty())
        assertEquals(old.readiness.assessedAtElapsedRealtimeMs + 30_001L, current.readiness.assessedAtElapsedRealtimeMs)
    }

    @Test fun exactlyThirtySecondCurrentEvidenceDoesNotRenewTheOriginalToken() {
        val f = AdmissionFixture(); val original = f.gateway.prepare()
        f.gateway.now += 30_000L
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertEquals(original, f.gateway.controller.startConfirmationToken)
        f.gateway.runBackground()
        assertEquals(WalkSessionState.ACTIVE, f.gateway.lifecycle.snapshot().state)
        assertEquals(1, f.gateway.activations)
    }

    @Test fun expiredReadinessCannotTurnUnavailableCurrentMeasurementsIntoAnActiveWalk() {
        val f = AdmissionFixture(); f.gateway.prepare(); f.gateway.now += 30_001L
        f.readinessOpen = false; f.gateway.readinessOpen = false
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertTrue(f.gateway.background.isEmpty())
        assertEquals(0, f.gateway.activations)
        assertNull(f.gateway.controller.startConfirmationToken)
    }

    @Test fun aRetiredServerResponseReleasesItsPendingStartAndRecoveryCanStart() {
        val f = AdmissionFixture(); f.gateway.prepare()
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        f.gateway.actorCurrent = false
        f.gateway.runBackground()
        assertNull("Retired response must not leave admission permanently waiting", f.gateway.controller.startConfirmationToken)
        assertEquals(0, f.gateway.activations)
        f.gateway.runBackground() // Release only the abandoned acquired lease.
        assertEquals(1, f.gateway.ends.size)
        f.gateway.actorCurrent = true
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertEquals(1, f.gateway.background.size)
        f.gateway.runBackground()
        assertEquals(WalkSessionState.ACTIVE, f.gateway.lifecycle.snapshot().state)
        assertEquals(1, f.gateway.activations)
    }

    @Test fun aLateOldResponseCannotClearANewerStartEvenIfTheReadinessTokenIsEqual() {
        val f = GatewayFixture(); val token = f.prepare()
        f.controller.requestGatewayWalkStart(token)
        val oldWork = f.background.removeAt(0)
        f.controller.clearDetachedRequestState()
        f.authority.invalidate(token.epoch)
        f.controller.requestGatewayWalkStart(token)
        oldWork()
        assertEquals(token, f.controller.startConfirmationToken)
        assertEquals(0, f.activations)
        f.runBackground()
        assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        assertEquals(1, f.activations)
        f.runBackground()
        assertTrue("An older response cannot end the newly tracked lease", f.ends.isEmpty())
    }
}
