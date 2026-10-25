package kr.co.hanium.dreamup.walksafe.session.readiness

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class WalkReadinessCoordinatorTest {
    @Test fun constructorsDoNotReadAnyLiveSourceOrClock() {
        val f = Fixture(record = false)
        assertTrue(f.events.isEmpty())
        assertNull(f.capability.currentDecision)
    }

    @Test fun cachedEvidenceRetainsOriginalTimeAndVersionAcrossReassessment() {
        val f = Fixture()
        f.now = 2_000L
        val result = f.capture()
        assertEquals(1_000L, f.capability.originalObservedAtMs)
        assertEquals(1_000L, result.observations.getValue(WalkSessionReadinessRequirement.CAMERA).supportingEvidence.single().observedAtElapsedRealtimeMs)
        assertFalse(f.events.contains("resolve"))
        assertEquals("profile-v1", f.capability.originalVersion)
    }

    @Test fun capabilityCacheUsesInclusiveTtlAndOriginalVersionAndReevaluatesAChangedVersion() {
        val f = Fixture()
        f.now = 31_000L
        f.capability.reusableOrRefresh(f.decision, f.now)
        assertFalse(f.events.contains("resolve"))
        assertEquals(1_000L, f.capability.originalObservedAtMs)
        f.now = 31_001L
        f.capability.reusableOrRefresh(f.decision, f.now)
        assertTrue(f.events.contains("resolve"))
        assertEquals(31_001L, f.capability.originalObservedAtMs)
        f.events.clear()
        f.version = "profile-v2"
        f.capability.reusableOrRefresh(f.decision, f.now)
        assertEquals("profile-v2", f.capability.originalVersion)
        assertTrue(f.events.contains("resolve"))
    }

    @Test fun conditionalEnvironmentAndMountMeasurementsFollowResourceCaptureAndAreNotReadForSearch() {
        val f = Fixture()
        f.capture()
        assertTrue(f.events.indexOf("resource_retry") < f.events.indexOf("resources"))
        assertTrue(f.events.indexOf("resources") < f.events.indexOf("official_assessment"))
        assertTrue(f.events.indexOf("official_assessment") < f.events.indexOf("mount_assessment"))
        f.events.clear()
        f.capture(WalkSessionAction.DESTINATION_SEARCH)
        assertFalse(f.events.contains("official_assessment"))
        assertFalse(f.events.contains("mount_assessment"))
        assertFalse(f.events.contains("detector_load"))
        assertFalse(f.events.contains("permission_observe"))
    }

    @Test fun detectorLoadIsFollowedByRestrictionReevaluationBeforeRequirementAdmission() {
        val f = Fixture()
        f.failOnLoad = true
        val result = f.capture()
        assertTrue(f.events.indexOf("disabled_features") < f.events.indexOf("detector_load"))
        assertEquals(2, f.events.count { it == "disabled_features" })
        assertTrue(f.events.indexOf("detector_load") < f.events.indexOf("restrict_decision"))
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, result.observations.getValue(WalkSessionReadinessRequirement.CAMERA).status)
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, result.observations.getValue(WalkSessionReadinessRequirement.MODEL).status)
        assertEquals("model-key", result.observations.getValue(WalkSessionReadinessRequirement.MODEL).evidenceVersion)
        assertSame(f.decision, f.capability.currentDecision)
    }

    @Test fun initialAsyncModelLoadKeepsStartPendingUntilTheExistingCompletionRecapturesReadiness() {
        val f = Fixture()
        f.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        f.detectorAvailable = false
        f.asyncLoad = true
        val pending = f.capture()
        assertEquals(WalkSessionReadinessStatus.PENDING,
            pending.observations.getValue(WalkSessionReadinessRequirement.MODEL).status)
        assertFalse(pending.isReady)
        assertFalse(pending.hasUnavailableRequirement)
        assertEquals(1, f.events.count { it == "detector_state" })
        f.lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(pending))
        assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
        assertNull(f.lifecycle.snapshot().confirmationToken)
        val epoch = f.lifecycle.snapshot().epoch
        f.asyncLoad = false
        f.loadInFlight = false
        f.detectorAvailable = true
        val completed = f.capture() // Existing loaded/modelVerified callbacks request this recapture.
        f.lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(completed))
        assertEquals(epoch, f.lifecycle.snapshot().epoch)
        assertTrue(completed.isReady)
        assertNotNull(f.lifecycle.snapshot().confirmationToken)
        assertEquals("Readiness completion never starts a walk itself", WalkSessionState.READY,
            f.lifecycle.snapshot().state)
    }

    @Test fun modelPreparationOnlyWaitsForStartOrResumeBeforeRuntimeAdmission() {
        val f = Fixture()
        f.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val ready = f.capture()
        f.lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(ready))
        f.lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(f.lifecycle.snapshot().confirmationToken), ready))
        f.detectorAvailable = false
        f.asyncLoad = true
        val runtime = f.capture()
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE,
            runtime.observations.getValue(WalkSessionReadinessRequirement.MODEL).status)
        f.lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(runtime))
        assertEquals(WalkSessionState.SAFE_STOP, f.lifecycle.snapshot().state)

        val resume = Fixture()
        resume.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val initial = resume.capture()
        resume.lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(initial))
        resume.lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(resume.lifecycle.snapshot().confirmationToken), initial))
        resume.lifecycle.handle(WalkSessionEvent.EnteredBackground)
        resume.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        resume.lifecycle.handle(WalkSessionEvent.RecheckRequested)
        resume.detectorAvailable = false
        resume.asyncLoad = true
        val pending = resume.capture(WalkSessionAction.RESUME_WALK)
        assertEquals(WalkSessionReadinessStatus.PENDING,
            pending.observations.getValue(WalkSessionReadinessRequirement.MODEL).status)
        resume.lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(pending))
        assertEquals(WalkSessionState.PAUSED, resume.lifecycle.snapshot().state)
        assertNull(resume.lifecycle.snapshot().confirmationToken)
        resume.asyncLoad = false
        resume.loadInFlight = false
        resume.detectorAvailable = true
        val completed = resume.capture(WalkSessionAction.RESUME_WALK)
        resume.lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(completed))
        assertNotNull(resume.lifecycle.snapshot().confirmationToken)
        assertEquals(WalkSessionState.PAUSED, resume.lifecycle.snapshot().state)
    }

    @Test fun modelPendingReadsOneConsistentObservationAndNeverOverridesMissingCameraPermission() {
        val f = Fixture()
        var observations = 0
        val detector = object : ReadinessDetector {
            override fun ensureLoadedAfterCameraGate() = Unit
            override fun isAvailable(): Boolean = error("Separate availability read")
            override fun loadFailureReason(): String? = error("Separate failure read")
            override fun modelKey() = "model-key"
            override fun modelState() = ReadinessDetector.ModelState(false, true, null).also { observations++ }
        }
        val feature = FeatureReadinessProducer(f, detector)
        assertEquals(WalkSessionReadinessStatus.PENDING, feature.model(allowPreparationPending = true).first)
        assertEquals(1, observations)
        f.cameraPermission = false
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, feature.model(allowPreparationPending = true).first)
        assertEquals(2, observations)
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, feature.model().first)
    }

    @Test fun incompleteOnboardingDoesNotAttemptDetectorOrReadItsCameraPermissionGate() {
        val f = Fixture()
        f.firstRunComplete = false
        val result = f.capture()
        assertFalse(f.events.contains("detector_load"))
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, result.observations.getValue(WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING).status)
        assertTrue(result.observations.getValue(WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING).reason.startsWith("first_run_onboarding_incomplete:"))
    }

    @Test fun activeRuntimeKeepsCapabilityAndResourceEvidenceButDoesNotRenewPreflightMeasurements() {
        val f = Fixture()
        val initial = f.capture()
        f.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        f.lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(initial))
        val token = requireNotNull(f.lifecycle.snapshot().confirmationToken)
        f.lifecycle.handle(WalkSessionEvent.StartRequested(token, initial))
        assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        f.now = 2_000L
        val runtime = f.capture(checkEntryPermissions = false)
        assertEquals(WalkSessionReadinessStatus.NOT_REQUIRED, runtime.observations.getValue(WalkSessionReadinessRequirement.PERMISSIONS).status)
        assertTrue(runtime.observations.getValue(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT).supportingEvidence.isEmpty())
        assertTrue(runtime.observations.getValue(WalkSessionReadinessRequirement.PHONE_MOUNTING).supportingEvidence.isEmpty())
        assertEquals(1_000L, runtime.observations.getValue(WalkSessionReadinessRequirement.CAMERA).supportingEvidence.single().observedAtElapsedRealtimeMs)
        assertEquals(700L, runtime.observations.getValue(WalkSessionReadinessRequirement.DEVICE_RESOURCES).supportingEvidence.last().observedAtElapsedRealtimeMs)
        assertEquals(800L, f.official.assessedAtElapsedRealtimeMs)
    }

    @Test fun bypassIsDynamicAndStillRequiresGatewayAndEndpoint() {
        val f = Fixture()
        f.cameraEnabled = false
        f.gatewayReady = false
        f.bypass = true
        val bypassed = f.capture()
        assertEquals(WalkSessionReadinessStatus.READY, bypassed.observations.getValue(WalkSessionReadinessRequirement.CAMERA).status)
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, bypassed.observations.getValue(WalkSessionReadinessRequirement.GATEWAY).status)
        assertTrue(bypassed.observations.getValue(WalkSessionReadinessRequirement.CAMERA).supportingEvidence.isEmpty())
        f.bypass = false
        val ordinary = f.capture()
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, ordinary.observations.getValue(WalkSessionReadinessRequirement.CAMERA).status)
        assertFalse(ordinary.observations.getValue(WalkSessionReadinessRequirement.CAMERA).supportingEvidence.isEmpty())
    }

    @Test fun readinessUsesActualPermissionPolicyAndPreservesResourceOriginalQueryTimes() {
        val f = Fixture()
        f.observedPermission = ObservedPermissionSnapshot(false, true, true, false)
        val result = f.capture()
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, result.observations.getValue(WalkSessionReadinessRequirement.PERMISSIONS).status)
        assertEquals("required_walk_permissions_missing:camera", result.observations.getValue(WalkSessionReadinessRequirement.PERMISSIONS).reason)
        val resourceEvidence = result.observations.getValue(WalkSessionReadinessRequirement.DEVICE_RESOURCES).supportingEvidence
        assertEquals(listOf(1_000L, 700L), resourceEvidence.map { it.observedAtElapsedRealtimeMs })
    }

    private class Fixture(record: Boolean = true) : ReadinessFeatureAdmission, ReadinessDetector, ReadinessResourceSource,
        OfficialReadinessSource {
        val events = mutableListOf<String>()
        var now = 1_000L
        var version = "profile-v1"
        var firstRunComplete = true
        var cameraEnabled = true
        var cameraPermission = true
        var gatewayReady = true
        var bypass = false
        var failOnLoad = false
        var asyncLoad = false
        var loadInFlight = false
        var detectorAvailable = true
        var observedPermission = ObservedPermissionSnapshot(true, true, true, false)
        val decision = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "notice")
        val lifecycle = WalkSessionLifecycle({ "walk" }, { 100L }, { now })
        val epoch = lifecycle.snapshot().epoch
        val official = OfficialEnvironmentAssessment(epoch, 800L, "official", OfficialEnvironmentFactor.entries.associateWith { EnvironmentEvidenceStatus.PASS },
            usageLimitsAcknowledged = true, readinessEvidence = mapOf(OfficialEnvironmentFactor.GPS_QUALITY to WalkSessionReadinessEvidence("gps", 800L, 30_000L, "gps-profile")))
        val mount = PhoneMountingAssessment(PhoneMountingAssessmentPhase.PREFLIGHT, PhoneMountingStatus.SUITABLE, PhoneMountingReason.PASSED,
            "mount", PhoneMountingPolicy.initialState(epoch, null), false, 900L,
            listOf(WalkSessionReadinessEvidence("camera", 900L, 30_000L, "camera-profile")))
        val mountingSource = object : MountingReadinessSource {
            override fun assessment(epoch: WalkRuntimeEpoch): PhoneMountingAssessment { events += "mount_assessment"; return mount }
            override fun readiness(epoch: WalkRuntimeEpoch, assessment: PhoneMountingAssessment?) = WalkSessionReadinessStatus.READY to ""
        }
        val capability = StartupCapabilityReadinessEvidence({ events += "resolve"; decision }, { events += "version"; version }, { events += "clock"; now })
        val feature = FeatureReadinessProducer(this, this)
        val producers = WalkReadinessProducers(
            FirstRunReadinessProducer({ events += "first_run"; firstRunComplete }, { FirstRunOnboardingStage.VERIFIED_LOGIN }),
            PriorityWalkReadinessProducer({ PriorityUserOnboardingPolicy() }, { PriorityUserSupportEnvironment(false, false, false, true, true) },
                { lifecycle.isRuntimeEpochCurrent(epoch) }, { true }, { "actor" }),
            PermissionReadinessProducer({ events += "permission_observe"; observedPermission }, { true }), feature,
            EndpointReadinessProducer(true, "https://gateway.example.test"), GatewayReadinessProducer({ gatewayReady }), this, mountingSource,
        )
        val coordinator = WalkReadinessCoordinator({ lifecycle }, capability, this, this, mountingSource, producers, { bypass }, { events += "clock"; now }, 35)
        init { if (record) { capability.record(decision); events.clear() } }
        fun capture(action: WalkSessionAction = WalkSessionAction.START_WALK, checkEntryPermissions: Boolean = true) =
            coordinator.capture(decision, action, checkEntryPermissions = checkEntryPermissions)
        override fun cameraEnabled(): Boolean { events += "camera_enabled"; return cameraEnabled }
        override fun cameraPermission(): Boolean { events += "camera_permission"; return cameraPermission }
        override fun locationEnabled() = true
        override fun locationPermission() = true
        override fun disabledFeatures(): Set<PostLoginDeviceCheckFeature> { events += "disabled_features"; return if (detectorAvailable) emptySet() else setOf(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION) }
        override fun restrict(decision: WalkSafeStartupCapabilityDecision): WalkSafeStartupCapabilityDecision {
            events += "restrict_decision"; return decision.copy(unavailableRequirements = listOf(WalkSafeStartupRequirement.CAMERA))
        }
        override fun ensureLoadedAfterCameraGate() { events += "detector_load"; if (asyncLoad) loadInFlight = true; if (failOnLoad) { detectorAvailable = false; cameraEnabled = false } }
        override fun isAvailable() = detectorAvailable
        override fun loadFailureReason() = if (detectorAvailable || loadInFlight) null else "controlled_load_failure"
        override fun modelState(): ReadinessDetector.ModelState {
            events += "detector_state"
            return ReadinessDetector.ModelState(detectorAvailable, loadInFlight, loadFailureReason())
        }
        override fun modelKey() = "model-key"
        override fun retryFailedMonitoring() { events += "resource_retry" }
        override fun snapshot(): WalkSessionDeviceResourceSnapshot { events += "resources"; return WalkSessionDeviceResourceSnapshot(true, true, true, checkedAtElapsedRealtimeMs = mapOf(WalkSessionResourceQuery.BATTERY to 700L)) }
        override fun recordDiagnostic(source: String, resources: WalkSessionDeviceResourceSnapshot) { events += "resource_diagnostic" }
        override fun assessment(epoch: WalkRuntimeEpoch): OfficialEnvironmentAssessment { events += "official_assessment"; return official }
        override fun readiness(epoch: WalkRuntimeEpoch, assessment: OfficialEnvironmentAssessment?) = WalkSessionReadinessStatus.READY to ""
    }
}
