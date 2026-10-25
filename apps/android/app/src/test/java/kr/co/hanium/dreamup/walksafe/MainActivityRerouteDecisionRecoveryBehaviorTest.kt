package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real Navigator TTL/Main dispatcher. SDK environment admission is a supplied result; fix admission/UI/TTS are not run. */
class MainActivityRerouteDecisionRecoveryBehaviorTest {
    @After fun releaseGateway() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun expiredRerouteFailureChoiceReopensOnAnAcceptedFixWithoutDirectionDispatch() {
        val fixture = Fixture()
        val expired = fixture.failRerouteAndExpireChoice()
        val routeId = fixture.navigator.currentRouteId()
        val routeMatch = fixture.navigator.currentRouteMatch()
        fixture.set("latestTmapOnRoute", true)
        assertFalse(fixture.deliverAcceptedFix())
        assertEquals(0, fixture.directions)
        assertEquals("A fresh accepted fix must reopen the failed reroute choice",
            RouteNavigatorUserDecision.REROUTE, fixture.navigator.pendingUserDecision())
        val renewed = requireNotNull(fixture.navigator.pendingDecisionToken())
        assertNotEquals(expired, renewed)
        assertEquals("tmap_unavailable", fixture.field("directionGuidancePauseReason"))
        assertEquals(1, fixture.questions.size)
        assertFalse(fixture.questions.single().shouldReroute)
        assertEquals("tmap_unavailable", fixture.questions.single().reason)
        assertFalse(fixture.pending.get())
        assertEquals(false, fixture.field("latestTmapOnRoute"))
        assertEquals(routeId, fixture.navigator.currentRouteId())
        assertSame(routeMatch, fixture.navigator.currentRouteMatch())
    }

    @Test fun expiredFailureChoiceDoesNotDescribeTheWalkerAsFollowingTheRoute() {
        val fixture = Fixture()
        fixture.failRerouteAndExpireChoice()
        assertEquals("새 경로를 찾지 못해 방향 안내가 멈춰 있습니다.",
            fixture.main.routeCommandUnavailableMessage())
    }

    @Test fun normalAcceptedFixStillDispatchesWhilePositionPauseStillBlocksIt() {
        val fixture = Fixture()
        assertTrue(fixture.deliverAcceptedFix())
        assertEquals(1, fixture.directions)
        assertFalse(fixture.deliverAcceptedFix(positionPaused = true))
        assertEquals(1, fixture.directions)
        assertTrue(fixture.questions.isEmpty())
    }

    @Test fun reopenedQuestionKeepsItsTokenAndOriginalThirtySecondDeadline() {
        val fixture = Fixture()
        fixture.failRerouteAndExpireChoice()
        fixture.deliverAcceptedFix()
        val token = fixture.navigator.pendingDecisionToken()
        fixture.now += 29_999L
        fixture.deliverAcceptedFix()
        assertEquals(token, fixture.navigator.pendingDecisionToken())
        assertEquals(1, fixture.questions.size)
        fixture.now += 2L
        assertNull(fixture.navigator.pendingDecisionToken())
        fixture.deliverAcceptedFix()
        assertNotEquals(token, fixture.navigator.pendingDecisionToken())
        assertEquals(2, fixture.questions.size)
        assertEquals(0, fixture.directions)
        assertFalse(fixture.pending.get())
    }

    @Test fun oldChoiceCannotSelectTheNewQuestionAndOnlyNewExplicitChoiceCanReroute() {
        val fixture = Fixture()
        val expired = fixture.failRerouteAndExpireChoice()
        fixture.deliverAcceptedFix()
        val current = requireNotNull(fixture.navigator.pendingDecisionToken())
        val stale = fixture.navigator.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE, expired)
        assertEquals("route_decision_stale", stale.reason)
        assertFalse(stale.shouldReroute)
        assertEquals(current, fixture.navigator.pendingDecisionToken())
        assertFalse(fixture.pending.get())
        assertTrue(fixture.navigator.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE, current).shouldReroute)
    }

    @Test fun actualDecisionPublisherKeepsTmapDirectionStop() {
        val fixture = Fixture()
        fixture.failRerouteAndExpireChoice()
        fixture.deliverAcceptedFix(publishDecision = { update ->
            fixture.questions += update
            // A current queued question avoids SDK speech/haptics; run the actual publisher's state mutation.
            fixture.set("routeDeviationHapticDecision", RouteNavigatorUserDecision.REROUTE)
            val token = requireNotNull(fixture.navigator.pendingDecisionToken())
            assertTrue(fixture.main.speakCurrentRouteDecision(requireNotNull(update.instruction), token,
                isScopeCurrent = { true }, submitSpeech = { _, current, _, _ -> current() },
                enqueueRetry = { error("The fixture question should be accepted") }, outputBusy = { false }))
            assertEquals(token, MainActivityControllerTestFixture.decisionSpeechState(fixture.main).pendingToken)
            fixture.method("applyRouteDeviationSafetyUpdate", Boolean::class.javaPrimitiveType!!,
                RouteNavigatorUpdate::class.java).invokeWithArguments(fixture.main, update) as Boolean
        })
        assertEquals("tmap_unavailable", fixture.field("directionGuidancePauseReason"))
        assertEquals(false, fixture.field("latestTmapOnRoute"))
        assertEquals(0, fixture.directions)
        assertEquals(1, fixture.questions.size)
    }

    @Test fun positionGateInEitherCallerOrMainSuppressesQuestionRecovery() {
        for (callerPaused in listOf(true, false)) {
            val fixture = Fixture()
            fixture.failRerouteAndExpireChoice()
            fixture.set("positionGuidancePaused", !callerPaused)
            assertFalse(fixture.deliverAcceptedFix(positionPaused = callerPaused))
            fixture.assertNoQuestionOrDirection()
        }
    }

    @Test fun inactiveMissingRouteAndInFlightRequestsDoNotOpenAQuestion() {
        for (state in listOf("inactive", "missing_route", "in_flight", "missing_destination")) {
            val fixture = Fixture()
            fixture.failRerouteAndExpireChoice()
            when (state) {
                "inactive" -> fixture.set("isRouteActive", false)
                "missing_route" -> fixture.navigator.clear()
                "in_flight" -> fixture.pending.set(true)
                else -> fixture.set("currentDestination", null)
            }
            fixture.deliverAcceptedFix()
            fixture.assertNoQuestionOrDirection()
        }
    }

    @Test fun staleEpochBackgroundSafetyStopAndMissingAuthorityCannotRecoverQuestion() {
        for (state in listOf("epoch", "background", "safe_stop", "authority")) {
            val fixture = Fixture()
            fixture.failRerouteAndExpireChoice()
            val epoch = fixture.lifecycle.snapshot().epoch
            when (state) {
                "background" -> fixture.set("isActivityForeground", false)
                "safe_stop" -> fixture.lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
                "authority" -> fixture.set("gatewayWalkAuthorityController", GatewayWalkAuthorityController(elapsedClock = { fixture.now }))
            }
            fixture.deliverAcceptedFix(epoch = if (state == "epoch") epoch.copy(recoveryGeneration = 99L) else epoch)
            fixture.assertNoQuestionOrDirection()
        }
    }

    @Test fun closedEnvironmentOrExpiredGatewaySessionCannotRecoverQuestion() {
        for (environment in listOf(true, false)) {
            val fixture = Fixture()
            fixture.failRerouteAndExpireChoice()
            if (environment) fixture.environmentAdmissionAllowed = false else fixture.session.invalidate()
            fixture.deliverAcceptedFix()
            fixture.assertNoQuestionOrDirection()
        }
    }

    @Test fun actualOfficialGpsRestrictionAlsoRejectsQuestionAtEnvironmentBoundary() {
        val fixture = Fixture()
        fixture.failRerouteAndExpireChoice()
        val guard = fixture.field("officialEnvironmentRuntimeGuard") as OfficialEnvironmentRuntimeGuard
        guard.onAssessment(OfficialEnvironmentAssessment(fixture.lifecycle.snapshot().epoch, fixture.now,
            "reroute-fixture", OfficialEnvironmentFactor.entries.associateWith {
                if (it == OfficialEnvironmentFactor.GPS_QUALITY) EnvironmentEvidenceStatus.FAIL else EnvironmentEvidenceStatus.PASS
            }, true), fixture.now)
        assertFalse(guard.decision().navigationOutputsAllowed)
        fixture.deliverAcceptedFix(navigationOutputsAllowed = { guard.decision().navigationOutputsAllowed })
        fixture.assertNoQuestionOrDirection()
    }

    @Test fun environmentSdkAdmissionIsReadLazilyOnlyForAnEligibleFailureQuestion() {
        val fixture = Fixture()
        fixture.deliverAcceptedFix()
        assertEquals(0, fixture.environmentReads)
        fixture.failRerouteAndExpireChoice()
        fixture.deliverAcceptedFix(positionPaused = true)
        assertEquals(0, fixture.environmentReads)
        fixture.deliverAcceptedFix()
        assertEquals(1, fixture.environmentReads)
        assertEquals(1, fixture.questions.size)
    }

    @Test fun staleOrFutureFilteredAndRawFixesCannotRecoverQuestion() {
        for (raw in listOf(false, true)) {
            for (offset in listOf(-10_001L, 1L)) {
                val fixture = Fixture()
                fixture.failRerouteAndExpireChoice()
                val fresh = fixture.freshFix()
                val stale = fresh.copy(elapsedRealtimeMs = fixture.now + offset)
                fixture.deliverAcceptedFix(location = if (raw) fresh else stale,
                    rawLocation = if (raw) stale else fresh)
                fixture.assertNoQuestionOrDirection()
            }
        }
    }

    @Test fun retiredFilteredAndRawObjectOwnersCannotRecoverQuestion() {
        for (raw in listOf(false, true)) {
            val fixture = Fixture()
            fixture.failRerouteAndExpireChoice()
            val retired = fixture.freshFix()
            fixture.set("latestTrustedLocation", if (raw) retired else retired.copy())
            fixture.set("latestRawRouteLocation", if (raw) retired.copy() else retired)
            fixture.deliverAcceptedFix(location = retired, rawLocation = retired, publishLatest = false)
            fixture.assertNoQuestionOrDirection()
        }
    }

    @Test fun tmapFailureWithoutConfirmedDeviationDoesNotInventARerouteQuestion() {
        val fixture = Fixture()
        fixture.set("directionGuidancePauseReason", "tmap_unavailable")
        assertFalse(fixture.deliverAcceptedFix())
        fixture.assertNoQuestionOrDirection()
    }

    @Test fun defaultRouteStatusWordingStillDescribesInactiveAndHealthyGuidance() {
        val fixture = Fixture()
        assertEquals("경로를 따라가는 중입니다.", fixture.main.routeCommandUnavailableMessage())
        fixture.set("directionGuidancePauseReason", "tmap_unavailable")
        fixture.set("isRouteActive", false)
        assertEquals("길안내 중이 아닙니다.", fixture.main.routeCommandUnavailableMessage())
    }

    @Test fun productionAcceptedFixAndSpeechWiringKeepQuestionSeparateFromDirectionMatching() {
        val source = sequenceOf(File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")).first { it.isFile }.readText()
        val caller = NavigationPolicySource.observation(source)
        assertTrue(MainActivityRefactorSource.member(source, "handleLocationUpdate")
            .contains("positioningSessionController.handleLocationUpdate(location, owner, locationGeneration, binding, receivedAtElapsedRealtimeMs)"))
        val positioningWire = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(positioningWire.contains("val scope = positions.scope()"))
        val assembly = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val positioningPorts = ReportStaticSourceInspector.blockAfter(
            assembly.substringAfter("positions = PositioningAssemblyBindings("), "scope =")
        assertTrue(positioningPorts.contains("override fun currentOwner() = currentLocationCollectionOwnerOrNull()"))
        assertTrue(positioningPorts.contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
        assertTrue(caller.indexOf("if (!scope.navigationAllowed)") in
            0 until caller.indexOf("val matchedEvidenceCurrent = guidance().dispatchAcceptedRouteFix("))
        assertTrue(caller.indexOf("latestTrustedLocation = filteredTrusted") in
            0 until caller.indexOf("val matchedEvidenceCurrent = guidance().dispatchAcceptedRouteFix("))
        assertTrue(caller.indexOf("latestRawRouteLocation = rawRouteLocation") in
            0 until caller.indexOf("val matchedEvidenceCurrent = guidance().dispatchAcceptedRouteFix("))
        assertTrue(caller.contains("filteredTrusted, rawRouteLocation, positionGuidancePaused,"))
        assertTrue(caller.contains("appendGnssTrace(positioningSnapshot, matchedEvidenceCurrent)"))
        val dispatcher = MainActivityRefactorSource.function(source, "internal fun dispatchAcceptedRouteFix(")
        assertTrue(dispatcher.contains("navigationOutputsAllowed: () -> Boolean = { scope.navigationAllowed }"))
        assertTrue(dispatcher.contains("scope.runtimeActive && navigationOutputsAllowed() &&"))
        assertTrue(dispatcher.contains("publishDecision: (RouteNavigatorUpdate) -> Boolean = ::applyRouteDeviationSafetyUpdate"))
        assertTrue(dispatcher.contains("publishDecision(decision.copy(reason = \"tmap_unavailable\"))"))
        assertTrue(dispatcher.contains("dispatchGuidance: (TrustedLocation, TrustedLocation) -> Unit = ::updateRouteGuidance"))
        assertFalse(dispatcher.contains("requestRoute("))
        assertFalse(dispatcher.contains("routeNavigator.update("))
        val explanation = MainActivityRefactorSource.function(source, "private fun explainRouteCommandUnavailable(")
        assertTrue(explanation.contains("output.speakInteraction(routeCommandUnavailableMessage(onRouteKo))"))
    }

    private class Fixture {
        val main = MainActivity()
        var now = 1_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "reroute-decision-recovery" },
            clock = { now }, elapsedRealtimeClock = { now })
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1),
            nowElapsedRealtimeMs = { now })
        val destination = RoutePoint(37.0009, 127.0, "목적지")
        val questions = mutableListOf<RouteNavigatorUpdate>()
        var directions = 0
        var environmentAdmissionAllowed = true
        var environmentReads = 0
        val pending get() = field("routeRequestInFlight") as AtomicBoolean
        val session: GatewayFieldSession
        init {
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            val actor = "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            val onboarding = FirstRunOnboardingPolicy.initialEmailAccount().copy(
                stage = FirstRunOnboardingStage.COMPLETE,
                ageBand = FirstRunAgeBand.VERIFIED_14_PLUS,
                completedReceiptHashes = listOf(FirstRunOnboardingStage.VERIFIED_LOGIN,
                    FirstRunOnboardingStage.PURPOSE_AND_SAFETY, FirstRunOnboardingStage.FP004_TRAINING)
                    .associateWith { FirstRunReceiptHash.fromSha256Hex("a".repeat(64)) },
                verifiedActorBinding = FirstRunOpaqueActorBinding.fromProvider(actor))
            session = GatewayFieldSession.verified("https://gateway.example.test", actor,
                "walksafe_field_session=v2.reroute", Long.MAX_VALUE)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(
                requireNotNull(GatewaySessionProcessCoordinator.beginOperation()), session, onboarding))
            set("reporterUserId", actor)
            set("firstRunOnboardingSnapshot", onboarding)
            set("postLoginDeviceCheckSnapshot", PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL,
                actor, GatewaySessionProcessCoordinator.snapshot().generation, 1L))
            set("isActivityForeground", true)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val readiness = WalkSessionReadinessCollector(lifecycle.snapshot().epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            readiness.plan.requiredRequirements.forEach {
                readiness.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch, it,
                    WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness.build(now)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            set("walkSessionLifecycle", lifecycle)
            val epoch = lifecycle.snapshot().epoch
            val authority = GatewayWalkAuthorityController(elapsedClock = { now })
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, authority.completeStart(
                requireNotNull(authority.beginStart(epoch)), GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "GRANTED", actor, "fixture-device", epoch.walkSessionId, "fixture-lease", 1L,
                    now, 1_000_000L, now, 1_000_000L))))
            set("gatewayWalkAuthorityController", authority)
            set("officialEnvironmentOutputsAllowed", true)
            val profile = ApprovedOfficialEnvironmentProfile("reroute-fixture", "camera-fixture", 15.0, 10_000L, 2)
            val guard = OfficialEnvironmentRuntimeGuard(lifecycle.snapshot().epoch, profile)
            guard.onAssessment(OfficialEnvironmentAssessment(lifecycle.snapshot().epoch, now, profile.profileId,
                OfficialEnvironmentFactor.entries.associateWith { EnvironmentEvidenceStatus.PASS }, true), now)
            assertTrue(guard.decision().navigationOutputsAllowed)
            set("officialEnvironmentRuntimeGuard", guard)
            navigator.setRoute(WalkingRoute("STAIR_AVOID", WalkingRouteSummary(100, 90),
                listOf(RoutePoint(37.0, 127.0), destination), emptyList()), destination = destination)
            set("routeNavigator", navigator)
            set("currentDestination", destination)
            set("isRouteActive", true)
        }
        fun failRerouteAndExpireChoice(): RouteNavigatorDecisionToken {
            navigator.update(TrustedLocation(37.0, 127.01, 5f, now), nowMs = now, requestInFlight = false)
            assertTrue(navigator.approveReroute().shouldReroute)
            assertNull(navigator.pendingUserDecision())
            assertNotNull(navigator.rerouteRequestFailed())
            // State produced by Main.retainRouteAfterRerouteFailure; its Android UI/TTS is outside this fixture.
            set("directionGuidancePauseReason", "tmap_unavailable")
            set("latestTmapOnRoute", false)
            val token = requireNotNull(navigator.pendingDecisionToken())
            now += 30_001L
            assertNull(navigator.pendingUserDecision())
            assertNull(navigator.pendingDecisionToken())
            return token
        }
        fun freshFix() = TrustedLocation(37.0, 127.01, 5f, now)
        fun assertNoQuestionOrDirection() {
            assertNull(navigator.pendingDecisionToken())
            assertTrue(questions.isEmpty())
            assertEquals(0, directions)
            assertEquals("tmap_unavailable", field("directionGuidancePauseReason"))
        }
        fun deliverAcceptedFix(
            positionPaused: Boolean = false,
            location: TrustedLocation = freshFix(),
            rawLocation: TrustedLocation = location,
            epoch: WalkRuntimeEpoch? = lifecycle.snapshot().epoch,
            publishLatest: Boolean = true,
            navigationOutputsAllowed: () -> Boolean = { environmentAdmissionAllowed },
            publishDecision: (RouteNavigatorUpdate) -> Boolean = { questions += it; true },
        ): Boolean {
            if (publishLatest) {
                set("latestTrustedLocation", location)
                set("latestRawRouteLocation", rawLocation)
            }
            return main.dispatchAcceptedRouteFix(location, rawLocation, positionPaused, epoch, now,
                navigationOutputsAllowed = { environmentReads += 1; navigationOutputsAllowed() },
                publishDecision = publishDecision,
                dispatchGuidance = { _, _ -> directions += 1 })
        }
        fun method(name: String, result: Class<*>, vararg arguments: Class<*>) =
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup()).findVirtual(
                MainActivity::class.java, name, MethodType.methodType(result, arguments.toList()))
        fun field(name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        fun set(name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
    }
}
