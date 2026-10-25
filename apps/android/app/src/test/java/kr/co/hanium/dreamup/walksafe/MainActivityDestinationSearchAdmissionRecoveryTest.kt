package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchController
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.startup.interaction.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Main search and response-epoch predicates. Only current SDK permission/service facts
 * are controlled; this neither invokes provider search nor proves phone permissions/position. */
class MainActivityDestinationSearchAdmissionRecoveryTest {
    @After fun clear() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun readyHomeCanSearchWithTheCurrentVerifiedCompletedBinding() {
        val f = F()
        assertTrue(f.call("shouldShowNativeHome"))
        assertTrue(f.call("postLoginDeviceCheckPassesFeatureGate"))
        assertTrue(f.call("currentDestinationSearchAllowsWork"))
    }

    @Test fun activePositionRestrictionBlocksDestinationSearchAndItsResponseEpoch() {
        val f = F(); f.activate()
        assertFalse("The current walk has no admitted environment output", f.call("currentNavigationCollectionAllowsWork"))
        assertFalse(f.call("nativeHomeFeatureContextAvailable"))
        assertFalse("A currently restricted walk must not admit destination search", f.call("currentDestinationSearchAllowsWork"))
        assertFalse(f.searchFacts().isEpochAllowed(f.base.epoch))
        assertFalse(f.searchFacts().isEpochAllowed(f.base.epoch.copy(walkSessionId = "retired")))
    }

    @Test fun pausedWalkKeepsSearchClosedWithoutResumingRuntimeOrGrantingNavigation() {
        val f = F(); f.activate()
        f.base.lifecycle.handle(WalkSessionEvent.RecheckRequested)
        assertEquals(WalkSessionState.PAUSED, f.base.lifecycle.snapshot().state)
        assertFalse(f.call("isWalkSessionRuntimeActive"))
        assertFalse(f.call("currentDestinationSearchAllowsWork"))
        assertFalse(f.searchFacts().isEpochAllowed(f.base.lifecycle.snapshot().epoch))
        assertFalse(f.searchFacts().isEpochAllowed(f.base.epoch))
        assertEquals(WalkSessionState.PAUSED, f.base.lifecycle.snapshot().state)
        assertFalse(f.call("currentNavigationCollectionAllowsWork"))
    }

    @Test fun currentLocationFeatureRestrictionBlocksNormalHomeSearch() {
        val f = F()
        val current = f.base.field("postLoginDeviceCheckSnapshot") as PostLoginDeviceCheckSnapshot
        f.base.set("postLoginDeviceCheckSnapshot", current.copy(state = PostLoginDeviceCheckState.LIMITED,
            disabledFeatures = setOf(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)))
        assertTrue(f.call("shouldShowNativeHome"))
        assertFalse(f.call("currentDestinationSearchAllowsWork"))
    }

    @Test fun aRestrictedWalkKeepsPermittedStepCollectionWithoutGrantingNavigationOrSearch() {
        val f = F(); f.activate()
        val device = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL,
            emptyList(), emptyList(), "collection-policy-device-pass")
        f.base.set("startupCapabilityDecision", device)
        f.base.set("confirmedStartupCapabilityDecision", device)
        assertTrue("Recovery observations may continue for the current permitted owner", f.call("currentStepTrackingCollectionAllowsWork"))
        assertFalse(f.call("currentNavigationCollectionAllowsWork"))
        assertFalse(f.call("currentDestinationSearchAllowsWork"))
        f.permission = false
        assertFalse("Collecting observations still requires actual current permission", f.call("currentStepTrackingCollectionAllowsWork"))
    }

    @Test fun activeNormalEnvironmentKeepsDestinationSearchAvailable() {
        val f = F(); f.activate()
        // This search-only case begins with an already admitted walk, not a HOME-start proof.
        val device = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL,
            emptyList(), emptyList(), "search-policy-device-pass")
        f.base.set("startupCapabilityDecision", device)
        f.base.set("confirmedStartupCapabilityDecision", device)
        val guard = OfficialEnvironmentRuntimeGuard(f.base.epoch, f.base.profile)
        guard.onAssessment(OfficialEnvironmentAssessment(f.base.epoch, f.base.now, f.base.profile.profileId,
            OfficialEnvironmentFactor.entries.associateWith { EnvironmentEvidenceStatus.PASS },
            usageLimitsAcknowledged = true), f.base.now)
        f.base.set("officialEnvironmentRuntimeGuard", guard)
        f.base.set("officialEnvironmentOutputsAllowed", guard.decision().navigationOutputsAllowed)
        assertTrue(f.call("currentNavigationCollectionAllowsWork"))
        assertTrue(f.call("currentDestinationSearchAllowsWork"))
        assertTrue(f.searchFacts().isEpochAllowed(f.base.epoch))
    }

    @Test fun currentPermissionServiceForegroundAndActorRemainMandatory() {
        val f = F()
        f.permission = false; assertFalse(f.call("currentDestinationSearchAllowsWork"))
        f.permission = true; f.service = false; assertFalse(f.call("currentDestinationSearchAllowsWork"))
        f.service = true; f.base.set("isActivityForeground", false); assertFalse(f.call("currentDestinationSearchAllowsWork"))
        f.base.set("isActivityForeground", true); f.base.set("reporterUserId", "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        assertFalse(f.call("currentDestinationSearchAllowsWork"))
    }

    private class F {
        val base = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val main = base.main
        var permission = true
        var service = true
        init {
            base.set("locationPermissionCheck", { permission })
            base.set("locationServiceEnabledReader", { service })
            val actor = base.field("reporterUserId") as String
            val education = PriorityUserOnboardingSnapshot(ageBand = PriorityUserAgeBand.VERIFIED_14_PLUS,
                educationReviewed = true, safePracticePlaceConfirmed = true, completedPractices = PriorityUserPractice.entries.toSet(),
                phonePostureAcknowledged = true, practiceNecessityReviewed = true, educationAccepted = true,
                usageConditionsAcknowledged = true, appUsageReviewed = true, appUsageAccepted = true, hazardResponseConfirmed = true)
            base.set("priorityUserOnboardingPolicy", PriorityUserOnboardingPolicy(education))
            base.set("priorityUserOnboardingActorId", actor)
            val facts = startupPort<PostLoginDeviceFacts> { name, _ -> when(name) {
                "getSnapshot" -> base.field("postLoginDeviceCheckSnapshot")
                "getQuickStart", "getBypass", "getCameraChecksDeferred", "getProbeInitialized" -> false
                "getObstacleOverride", "getMetricOverride", "getOfflineTtsOverride", "getResourceWaitNotice" -> null
                "locationPermission" -> permission
                "locationServiceEnabled" -> service
                "passesFeatureGate" -> call("postLoginDeviceCheckPassesFeatureGate")
                "cameraPermission", "recordAudioPermission", "notificationPermission", "disclosureAccepted", "activityRecognitionPermission" -> true
                else -> error("unexpected SDK fact $name")
            } }
            val view = startupPort<PostLoginDeviceLiveStatusView> { name, _ -> error("unexpected view $name") }
            MainActivity::class.java.getDeclaredField("postLoginDeviceInteraction\$delegate").apply { isAccessible = true }
                .set(main, lazyOf(PostLoginDeviceInteractionController(facts, view)))
            base.set("nativeUiPage", kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage.DESTINATION_SEARCH)
        }
        fun activate() {
            base.activate()
            val actor = base.field("reporterUserId") as String
            val authority = kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityController(elapsedClock = { base.now })
            val start = requireNotNull(authority.beginStart(base.epoch))
            val result = authority.completeStart(start, kr.co.hanium.dreamup.walksafe.network.GatewayWalkStartResult.Granted(
                kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease("GRANTED", actor, "fixture-device", base.epoch.walkSessionId,
                    "fixture-lease", 1L, 100L, 100_000L, 100L, 100_000L)))
            assertEquals(kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityCompletion.LEASE_ACTIVE, result)
            base.set("gatewayWalkAuthorityController", authority)
        }
        fun call(name: String) = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(Boolean::class.javaPrimitiveType))
            .invokeWithArguments(main) as Boolean
        fun searchFacts(): DestinationSearchController.Facts {
            val c = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, "getDestinationSearchController", MethodType.methodType(DestinationSearchController::class.java))
                .invokeWithArguments(main) as DestinationSearchController
            return DestinationSearchController::class.java.getDeclaredField("facts").apply { isAccessible = true }.get(c) as DestinationSearchController.Facts
        }
    }
}
