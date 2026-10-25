package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.readiness.effectiveWalkSessionMode
import org.junit.Assert.*
import org.junit.Test

class WalkSessionAdmissionControllerBehaviorTest {
    @Test fun constructionDoesNotReadStartupPermissionOrMachine() {
        val f = AdmissionFixture(); assertTrue(f.events.isEmpty())
    }
    @Test fun pendingGatewayStartStopsBeforePermissionOrCapabilityQueries() {
        val f = AdmissionFixture(); val token = f.gateway.prepare(); f.gateway.controller.requestGatewayWalkStart(token)
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck(); assertTrue(f.events.isEmpty())
    }
    @Test fun unresolvedPermissionRecoveryRechecksThenStopsBeforeCapabilityCapture() {
        val f = AdmissionFixture(); f.permissionBlocked = true
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertEquals(listOf("permission", "bypass", "permissionRecheck", "permission"), f.events)
    }
    @Test fun firstReadinessCreatesTokenAndReturnsWithoutStartingGateway() {
        val f = AdmissionFixture(); f.gateway.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertNotNull(f.gateway.lifecycle.snapshot().confirmationToken)
        assertTrue(f.gateway.background.isEmpty()); assertEquals("refresh", f.events.last())
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertEquals(1, f.gateway.background.size)
    }
    @Test fun pendingStartupConfirmationPreventsReadinessDecisionQuery() {
        val f = AdmissionFixture(); f.confirmationPending = true
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertFalse(f.events.contains("decision")); assertTrue(f.gateway.background.isEmpty())
    }
    @Test fun missingInstallableVoiceBeforeWalkingDoesNotCreateFalseSafetyInterruption() {
        val f = AdmissionFixture(); f.gateway.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        f.blockedReason = "install voice"; f.decisionValue = WalkSafeStartupCapabilityDecision(
            WalkSafeStartupCapabilityTier.BLOCKED, listOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS), emptyList(), "voice")
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertEquals(WalkSessionState.READY, f.gateway.lifecycle.snapshot().state)
        assertTrue(f.events.contains("permissions")); assertFalse(f.events.contains("persist"))
    }
    @Test fun unavailableAndroidFailsClosedAndPersistsInterruption() {
        val f = AdmissionFixture(); f.gateway.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        f.blockedReason = "unsupported"; f.readinessOpen = false; f.decisionValue = WalkSafeStartupCapabilityDecision(
            WalkSafeStartupCapabilityTier.BLOCKED, listOf(WalkSafeStartupRequirement.ANDROID_VERSION), emptyList(), "android")
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertEquals(WalkSessionState.SAFE_STOP, f.gateway.lifecycle.snapshot().state)
        assertTrue(f.events.contains("persist")); assertFalse(f.events.contains("permissions"))
    }
    @Test fun pausedWalkWithoutHeldLeaseStopsBeforeReadinessAssessment() {
        val f = AdmissionFixture(); f.gateway.active(); f.gateway.lifecycle.handle(WalkSessionEvent.RecheckRequested)
        f.gateway.authority.reset(); f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertEquals(listOf("walk_lease_missing_on_resume"), f.gateway.stops)
        assertFalse(f.events.contains("blockReason"))
    }

    @Test fun capabilityModeChangesReplaceFreshReadinessBeforeRequestingGatewayStart() {
        for (initialLimited in listOf(true, false)) {
            val f = AdmissionFixture()
            fun decision(limited: Boolean) = WalkSafeStartupCapabilityDecision(
                if (limited) WalkSafeStartupCapabilityTier.LIMITED else WalkSafeStartupCapabilityTier.FULL,
                if (limited) listOf(WalkSafeStartupRequirement.METRIC_DISTANCE) else emptyList(),
                emptyList(), "current capability")
            f.gateway.lifecycle.handle(WalkSessionEvent.EnteredForeground)
            f.decisionValue = decision(initialLimited)
            f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
            val original = requireNotNull(f.snapshot().confirmationToken)
            assertTrue(original.readiness.isReadyAt(f.gateway.now))
            f.decisionValue = decision(!initialLimited)
            f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
            val replacement = requireNotNull(f.snapshot().confirmationToken)
            assertNotEquals("A fresh token for another capability mode must not be reused", original, replacement)
            assertEquals(original.epoch, replacement.epoch)
            assertEquals(f.decisionValue.effectiveWalkSessionMode(), replacement.readiness.plan.mode)
            assertTrue("Readiness refresh must precede the server start request", f.gateway.background.isEmpty())
            f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
            assertEquals(1, f.gateway.background.size)
            assertEquals(replacement, f.gateway.controller.startConfirmationToken)
        }
    }

    @Test fun unchangedLivePlanKeepsItsFreshConfirmationToken() {
        val f = AdmissionFixture()
        f.gateway.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        val token = f.snapshot().confirmationToken
        f.events.clear()
        f.controller.maybeAdvanceWalkSessionAfterCapabilityCheck()
        assertEquals(token, f.snapshot().confirmationToken)
        assertEquals(1, f.gateway.background.size)
    }
}

internal class AdmissionFixture : WalkSessionRuntimeAccess, WalkResumeReadiness, WalkAdmissionState, WalkAdmissionEffects {
    val gateway = GatewayFixture(); val resume = ResumeFixture(); val events = mutableListOf<String>()
    var permissionBlocked = false; var confirmationPending = false; var readinessOpen = true; var blockedReason: String? = null
    var decisionValue = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "ready")
    val controller = WalkSessionAdmissionController(this, gateway.authority, gateway.controller, resume.controller, this, this, this,
        setOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS), { gateway.now })
    override fun available() = true
    override fun snapshot() = gateway.lifecycle.snapshot()
    override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = gateway.lifecycle.isRuntimeEpochCurrent(epoch)
    override fun transition(event: WalkSessionEvent) = gateway.lifecycle.handle(event)
    override fun decision(): WalkSafeStartupCapabilityDecision { events += "decision"; return decisionValue }
    override fun blockReason(decision: WalkSafeStartupCapabilityDecision): String? { events += "blockReason"; return blockedReason }
    override fun capture(decision: WalkSafeStartupCapabilityDecision, action: WalkSessionAction, recordResourceDiagnostics: Boolean): WalkSessionReadinessSnapshot {
        events += "capture:$recordResourceDiagnostics"
        val plan = WalkSessionReadinessPlan(action, decision.effectiveWalkSessionMode(), checkEntryPermissions = true)
        val collector = WalkSessionReadinessCollector(gateway.lifecycle.snapshot().epoch, plan)
        plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(gateway.lifecycle.snapshot().epoch, it,
            if (readinessOpen) WalkSessionReadinessStatus.READY else WalkSessionReadinessStatus.UNAVAILABLE,
            reason = if (readinessOpen) "" else "unsupported")) }
        return collector.build(gateway.now)
    }
    override fun isConfirmed() = true
    override fun permissionRecoveryBlocksStart(): Boolean { events += "permission"; return permissionBlocked }
    override fun developmentBypassEnabled(): Boolean { events += "bypass"; return false }
    override fun isForeground() = true
    override fun startupConfirmationPending() = confirmationPending
    override fun startupRetryRequiresUserAction() = false
    override fun consumeDevelopmentResumeRequest(epoch: WalkRuntimeEpoch) = false
    override fun completePermissionRecoveryRecheck() { events += "permissionRecheck" }
    override fun persistInterruptionMarker(): Boolean { events += "persist"; return true }
    override fun maybeRequestMissingPermissions(decision: WalkSafeStartupCapabilityDecision) { events += "permissions" }
    override fun updateStatus(title: String, detail: String) { events += "status:$title" }
    override fun refreshReadinessUi() { events += "refresh" }
}
