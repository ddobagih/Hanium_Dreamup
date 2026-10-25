package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class OfficialEnvironmentControllerTest {
    @Test fun constructorDoesNotAuthorizeOrStartAnyCheck() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
        assertTrue(f.preflight.requests.isEmpty())
        assertEquals(0, f.bypassReads)
    }

    @Test fun backgroundConfirmationReturnsBeforeOnboardingOrLifecycleAccess() {
        val f = Fixture()
        f.foreground = false
        f.controller.confirmConditions()
        assertTrue(f.events.isEmpty())
        assertTrue(f.preflight.requests.isEmpty())
    }

    @Test fun explicitCurrentNativeCheckUsesTheGeneralGpsStreamWithoutANewSubscription() {
        val f = Fixture()
        f.native = true
        f.controller.requestGpsForCheck(f.epoch)
        assertEquals(listOf("general_gps"), f.events)
        assertTrue(f.preflight.requests.isEmpty())
    }

    @Test fun staleNativeCheckCannotFallBackToASeparateGpsSubscription() {
        val f = Fixture()
        f.native = true
        f.nativeCurrent = false
        f.controller.requestGpsForCheck(f.epoch)
        assertTrue(f.events.isEmpty())
        assertTrue(f.preflight.requests.isEmpty())
    }

    @Test fun missingLocationPermissionFailsExplicitlyAndPreservesRequiredRecovery() {
        val f = Fixture()
        f.permission = false
        f.controller.confirmConditions()
        assertEquals(OfficialEnvironmentPreflightPhase.FAILED, f.preflight.controller.phase)
        assertEquals("LOCATION_PERMISSION_MISSING", f.preflight.evidence.gpsDiagnosticDetail)
        assertTrue("request_permission" in f.events)
        assertTrue(f.preflight.requests.isEmpty())
    }

    @Test fun sameControllerUsesCurrentBypassAndRestoresPermissionCheckAfterItTurnsOff() {
        val f = Fixture()
        f.permission = false
        f.controller.confirmConditions()
        assertEquals(OfficialEnvironmentPreflightPhase.FAILED, f.preflight.controller.phase)
        f.events.clear()
        f.bypass = true
        f.controller.confirmConditions()
        assertTrue(f.events.isEmpty())
        assertEquals(OfficialEnvironmentPreflightPhase.FAILED, f.preflight.controller.phase)
        assertEquals("LOCATION_PERMISSION_MISSING", f.preflight.evidence.gpsDiagnosticDetail)
        f.bypass = false
        f.controller.confirmConditions()
        assertTrue("request_permission" in f.events)
        assertEquals(OfficialEnvironmentPreflightPhase.FAILED, f.preflight.controller.phase)
        assertTrue(f.preflight.requests.isEmpty())
    }

    private class Fixture : OfficialEnvironmentCheckFactsReader, OfficialEnvironmentCheckEffects {
        val preflight = OfficialEnvironmentPreflightControllerTest.Fixture()
        val runtime = OfficialEnvironmentRuntimeControllerTest.Fixture()
        val epoch = preflight.epoch
        var foreground = true
        var native = false
        var nativeCurrent = true
        var permission = true
        var bypass = false
        var bypassReads = 0
        val events = mutableListOf<String>()
        val controller = OfficialEnvironmentController(preflight.evidence, preflight.controller, runtime.controller,
            this, this, { 1_000L }, { bypassReads++; bypass }, false, "사용범위 안내")
        override fun foreground() = foreground
        override fun lifecycleInitialized() = true
        override fun nativeEducationCurrent() = true
        override fun environmentProfilesPresent() = true
        override fun lifecycle() = OfficialEnvironmentLifecycleFacts(epoch, WalkSessionState.READY)
        override fun nativeCheckRequested() = native
        override fun nativeGpsCheckCurrent(epoch: WalkRuntimeEpoch) = nativeCurrent
        override fun locationEnabled() = true
        override fun cameraEnabled() = false
        override fun locationPermission() = permission
        override fun locationServiceEnabled() = true
        override fun continuousGpsEvidence(epoch: WalkRuntimeEpoch, nowMs: Long): MeasuredEnvironmentEvidence? = null
        override fun currentAssessment(epoch: WalkRuntimeEpoch, nowMs: Long): OfficialEnvironmentAssessment { fail(); error("not used") }
        override fun profile(): ApprovedOfficialEnvironmentProfile? = null
        override fun requireFirstRunOnboardingComplete(reason: String): Boolean { events += "onboarding"; return true }
        override fun resetPrewalkCameraEvidenceForNewAttempt() { events += "reset_camera" }
        override fun resetPrewalkGuidanceStage() { events += "reset_stage" }
        override fun assumeInitialManualMountConfirmation(epoch: WalkRuntimeEpoch) { fail() }
        override fun requestMissingPermissionsIfDecisionPresent() { events += "request_permission" }
        override fun requestContinuousGpsForNativeCheck(epoch: WalkRuntimeEpoch) { events += "general_gps" }
        override fun showStatus(title: String, detail: String) { events += "status" }
        override fun refreshEnvironmentAndReadiness() { events += "refresh" }
        override fun speakInteraction(message: String) { events += "speech" }
        override fun cancelRuntimeCameraHandoff() { events += "cancel_handoff" }
        override fun stopMountingSensorIfInitialized() { events += "stop_sensor" }
        override fun clearContinuousGpsSourceAndRecovery() { events += "clear_gps" }
        override fun recordInvalidationIfInitialized(reason: String) { events += "record" }
        override fun refreshEnvironmentIfInitialized() { events += "refresh_environment" }
    }
}
