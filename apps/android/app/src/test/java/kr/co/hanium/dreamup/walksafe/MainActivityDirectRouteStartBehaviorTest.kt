package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import org.junit.Assert.*
import org.junit.Test

/** Actual Main response-location/installation selection and Navigator. SDK, HTTP, encrypted storage
 * and audio are not executed; their caller admission/order is asserted in source below. */
class MainActivityDirectRouteStartBehaviorTest {
    @Test fun nativeInstallationDoesNotCreateAnInitialJoinStage() {
        val main = MainActivity()
        val origin = fix(50.0)
        assertEquals(RouteResponseInstallLocationStatus.CONNECTED,
            main.routeResponseLocationStatus(route(), origin, origin, 1_000L))
        main.installRequestedRoute(route(), destination(), origin, null, "user_destination")
        val nav = navigator(main)
        assertFalse("The native start must install ordinary guidance without a join stage", nav.isWaitingForInitialRouteJoin())
        val update = nav.update(origin, 1_000L, false, facingObservation = facing())
        assertNotNull(nav.currentAcceptedRouteMatchFor(origin.elapsedRealtimeMs))
        assertTrue(requireNotNull(update.instruction).contains("경로를 따라 약 50미터"))
        assertFalse(requireNotNull(update.instruction).contains("시작점"))
    }

    @Test fun aProviderStartOffsetOffersClosestApproachWithoutAStartPointConnector() {
        val main = MainActivity()
        val origin = fix(0.0, northM = 22.0)
        val status = main.routeResponseLocationStatus(route(), origin, origin, 1_000L)
        assertEquals(RouteResponseInstallLocationStatus.APPROACH_REQUIRED, status)
        main.installRequestedRoute(route(), destination(), origin, null, "user_destination")
        val nav = navigator(main)
        assertTrue(nav.hasRoute())
        assertTrue(nav.isWaitingForInitialRouteJoin())
        val update = nav.update(origin, 1_000L, false, facingObservation = facing())
        assertFalse(update.arrived)
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
    }

    @Test fun disconnectedGeometryDoesNotCreateAnotherWaitForTheOldRouteStart() {
        val main = MainActivity()
        selectDestination(main)
        assertFalse("Disconnected geometry needs a visible failure, not a join wait",
            main.canRetainInitialRouteResponseForLocation(destination(), "user_destination", false,
                RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED))
        assertTrue("Missing GPS still retains the same explicit human start",
            main.canRetainInitialRouteResponseForLocation(destination(), "user_destination", false,
                RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE))
    }

    @Test fun currentPositionAlongTheReturnedRouteUsesNormalDistanceWithoutWalkingBackToItsFirstPoint() {
        val main = MainActivity()
        val requestedAtStart = fix(0.0)
        val current = fix(50.0, at = 2_000L)
        assertEquals(RouteResponseInstallLocationStatus.CONNECTED,
            main.routeResponseLocationStatus(route(), requestedAtStart, current, 2_000L))
        main.installRequestedRoute(route(), destination(), requestedAtStart, null, "user_destination")
        val update = navigator(main).update(current, 2_000L, false, facingObservation = facing(2_000L))
        val text = requireNotNull(update.instruction)
        assertTrue(text, text.contains("약 12시 방향"))
        assertTrue(text, text.contains("경로를 따라 약 50미터"))
        assertFalse(text, text.contains("직선거리"))
        assertFalse(text, text.contains("시작점"))
    }

    @Test fun realMissingStaleAndFarPositionsRemainRejectedWhileBoundedOffsetsApproach() {
        val main = MainActivity()
        val origin = fix(0.0)
        assertEquals(RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE,
            main.routeResponseLocationStatus(route(), origin, null, 1_000L))
        assertEquals(RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE,
            main.routeResponseLocationStatus(route(), origin, origin, 11_001L))
        assertEquals(RouteResponseInstallLocationStatus.APPROACH_REQUIRED,
            main.routeResponseLocationStatus(route(), origin, fix(50.0, northM = 60.0, at = 2_000L), 2_000L))
        assertEquals(RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED,
            main.routeResponseLocationStatus(route(), origin, fix(50.0, northM = 110.0, at = 2_000L), 2_000L))
    }

    @Test fun directStartKeepsTheInstalledSummaryBeforeNormalDirectionsAndCompletesOnlyOnce() {
        val main = MainActivity()
        val current = fix(50.0)
        main.installRequestedRoute(route(), destination(), fix(0.0), null, "user_destination")
        val nav = navigator(main)
        val update = nav.update(current, 1_000L, false, facingObservation = facing())
        assertFalse(nav.isWaitingForInitialRouteJoin())
        assertNotNull(nav.currentAcceptedRouteMatchFor(current.elapsedRealtimeMs))
        val summary = routeStartAnnouncementKo("목적지", route().summary.distanceM, true, route().priority)
        val coordinator = MainActivityControllerTestFixture.field("installedRouteStartSpeechCoordinator")
            .apply { isAccessible = true }.get(main) as InstalledRouteStartSpeechCoordinator
        coordinator.install(summary) { true }
        val heard = mutableListOf<String>()
        var done: (() -> Unit)? = null
        repeat(3) {
            assertTrue(main.holdRouteInstructionsForStartAnnouncement(
                locationReady = { nav.currentAcceptedRouteMatchFor(current.elapsedRealtimeMs) != null },
                submitSpeech = { text, currentOwner, terminal ->
                    assertTrue(currentOwner()); heard += text; done = terminal; true
                }, resumeInstructions = { heard += requireNotNull(update.instruction) },
                speechBusy = { false }, deferInstructions = { fail("ready") },
            ))
        }
        assertEquals(listOf(summary), heard)
        done!!.invoke()
        done!!.invoke()
        assertEquals(listOf(summary, requireNotNull(update.instruction)), heard)
        assertFalse(heard.any { "시작점" in it || "직선거리" in it })
    }

    @Test fun actualDeviationStillRequiresAUserChoiceInsteadOfAStartPointOrAutomaticReroute() {
        val main = MainActivity()
        main.installRequestedRoute(route(), destination(), fix(50.0), null, "user_destination")
        val nav = navigator(main)
        nav.update(fix(50.0), 1_000L, false, facingObservation = facing())
        val first = nav.update(fix(50.0, northM = 60.0, at = 2_000L), 2_000L, false)
        val confirmed = nav.update(fix(50.0, northM = 60.0, at = 3_000L), 3_000L, false)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, first.pendingUserDecision)
        assertEquals(RouteNavigatorUserDecision.REROUTE, confirmed.pendingUserDecision)
        assertTrue(confirmed.userDecisionRequired)
        assertFalse(first.shouldReroute)
        assertFalse(confirmed.shouldReroute)
        assertFalse(requireNotNull(confirmed.instruction).contains("시작점"))
        val actual = NavigationPolicySource.guidance(source())
        assertTrue(actual.indexOf("applyRouteDeviationSafetyUpdate(update)") <
            actual.indexOf("holdRouteInstructionsForStartAnnouncement("))
        val safety = MainActivityRefactorSource.function(source(), "private fun applyRouteDeviationSafetyUpdate(")
        assertTrue(safety.contains("speakRouteDecision(it, token)"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(safety,
            "navigationRunState.pauseDirectionUntilRouteMatch(update.reason)", "speakRouteDecision(it, token)"))
        val runSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/NavigationRunState.kt").readText()
        val pause = MainActivityRefactorSource.factory(runSource, "pauseDirectionUntilRouteMatch")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(pause,
            "onRoute = false", "directionPauseReason = reason"))
        assertFalse(pause.contains("selectDestination("))
        assertFalse(pause.contains("markInactive("))
        assertFalse(pause.contains("navigator."))
        assertTrue(MainActivityRefactorSource.composition(source(), "routeGuidanceController").contains("RouteGuidanceController(run,"))
    }

    @Test fun disconnectedGeometryNeverRetainsRerouteResumeOrChangedDestinationIntent() {
        val main = MainActivity()
        selectDestination(main)
        for (status in RouteResponseInstallLocationStatus.values()) {
            assertFalse(main.canRetainInitialRouteResponseForLocation(destination(), "off_route", true, status))
            assertFalse(main.canRetainInitialRouteResponseForLocation(destination(), "walk_resume", false, status))
            assertFalse(main.canRetainInitialRouteResponseForLocation(point(200.0), "user_destination", false, status))
        }
        val requestBody = MainActivityRefactorSource.function(source(), "private fun requestRoute(")
        val request = ReportStaticSourceInspector.functionBlock(requestBody, "fun rejectDisconnectedResponse")
        assertTrue(request.contains("if (!finishCurrentRouteRequest()) return"))
        assertTrue(request.contains("canRetainInitialRouteResponseForLocation(destination, reason,"))
        assertTrue(request.contains("navigation=route_failed"))
        assertTrue(request.contains("else presentation.speak(detail)"))
        assertTrue(MainActivityRefactorSource.composition(source(), "routeAcquisitionController")
            .contains("presentation = resources.presentation()"))
        assertTrue(MainActivityRefactorSource.factory(source(), "createNavigationSessions").contains("presentation = { routePresentation }"))
        val presentation = MainActivityRefactorSource.property(source(), "routePresentation")
        assertTrue(presentation.contains("override fun speak(message: String) { speakInteraction(message) }"))
        assertFalse(requestBody.contains("pendingInitialRouteConnection"))
        assertFalse(requestBody.contains("initial_route_connection_pending"))
        assertFalse(source().contains("pendingInitialRouteConnection"))
        assertFalse(source().contains("initial_route_connection_pending"))
    }

    @Test fun missingLocationActionDoesNotAssumeABuildingOrAskTheUserToExitOne() {
        val main = MainActivity()
        val pending = kr.co.hanium.dreamup.walksafe.navigation.start.GuidancePreparationController.PendingGuidancePreparationSpeech(1_000L) { true }
        set(main, "pendingGuidancePreparationSpeech", pending)
        set(main, "positionGuidancePaused", true)
        val messages = mutableListOf<String>()
        repeat(2) {
            main.maybeSpeakGuidancePreparationWait(5_000L, true, false,
                submitSpeech = { text, current, done, _ ->
                    assertTrue(current()); messages += text; done(); true
                }, locationStillWaiting = { true }, publishFailure = { fail("delivery succeeded") })
        }
        assertEquals("Position measurement wait is silent", 0, messages.size)
        assertFalse(pending.delivered)
        assertNull(pending.responseGeneration)
    }

    @Test fun compassWatchdogChecksAtThreeHundredMillisecondsWithoutRetimingSensorObservations() {
        val source = source()
        val activityPorts = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val compass = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteCompassSession.kt").readText()
        val watchdog = MainActivityRefactorSource.member(compass, "scheduleRouteCompassPresentationUpdate")
        val scheduler = MainActivityRefactorSource.member(compass, "scheduleAdmittedRouteCompassUpdate")
        val compassPorts = MainActivityRefactorSource.composition(source, "routeCompassSession")
        assertTrue(compassPorts.contains("RouteCompassSession("))
        assertTrue(MainActivityRefactorSource.member(source, "scheduleRouteCompassPresentationUpdate")
            .contains("routeCompassSession.scheduleRouteCompassPresentationUpdate(observation, firstBadAtMs)"))
        assertTrue("The actual compass watchdog must use the requested 0.3 second cadence",
            scheduler.contains("postDelayed(refresh, 300L)") &&
                activityPorts.contains("nativeHomeUiPresenter.compassTarget().postDelayed(task, delayMs)"))
        assertTrue(watchdog.contains("currentRouteFacingObservation(nowMs)"))
        assertTrue(watchdog.contains("lease.evaluatePosition(nowMs)"))
        assertTrue(compassPorts.contains("binding.coordinator.evaluate(nowMs, binding.inputGeneration)"))
        assertTrue(watchdog.contains("actions.retry(nowMs)"))
        assertTrue(compassPorts.contains("guidance.value.retryCurrentNavigationSpeechAfterReady(nowMs)"))
        assertFalse(watchdog.contains("recordGeneralLocationGpsObservation("))
        assertFalse(watchdog.contains("observedAtElapsedRealtimeMs = nowMs"))
        val ready = MainActivityRefactorSource.function(source, "private fun retryCurrentNavigationSpeechAfterReady(")
        assertTrue(ready.contains("if (!isRouteSpeechCurrent(epoch, requestGeneration, lifecycleGeneration)) return"))
        assertTrue(ready.contains("cancelNavigationSpeechRetry()"))
        val retry = MainActivityRefactorSource.function(source, "private fun retryCurrentNavigationSpeech(")
        assertTrue(retry.contains("if (!isRouteSpeechCurrent(epoch, requestGeneration, lifecycleGeneration) || positionGuidancePaused) return"))
        assertTrue(retry.contains("|| positionGuidancePaused) return"))
        assertTrue(retry.contains("retryAdmittedNavigationSpeech(nowMs, epoch, compass)"))
        val admitted = MainActivityRefactorSource.function(source, "internal fun retryAdmittedNavigationSpeech(")
        assertTrue(admitted.contains("= ::tryDispatchRouteInstruction"))
        val guidance = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText()
        val retryBody = MainActivityRefactorSource.member(guidance, "retryAdmittedNavigationSpeech", occurrence = 1)
        assertTrue(retryBody.contains("routeNavigator.retryGuidance("))
        assertTrue(retryBody.contains("dispatch(update, epoch, compass)"))
        val dispatch = MainActivityRefactorSource.function(source, "private fun tryDispatchRouteInstruction(")
        assertTrue(dispatch.contains("if (update.cancelStaleNavigationSpeech) speech.cancelNavigation()"))
        val speechBinding = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(speechBinding.contains("GuidanceSpeechPeerAdapter("))
        assertTrue(speechBinding.contains("output = { speechOutputController }"))
        assertTrue(java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/GuidanceSpeechPeerAdapter.kt").readText()
            .contains("override fun cancelNavigation() { output().currentActuatorOrNull()?.cancelNavigationSpeech() }"))
        assertTrue(dispatch.contains("\"guidance_facing_recheck_wait\""))
        assertTrue(dispatch.contains("scheduleNavigationSpeechRetry()"))
    }

    @Test fun actualMainRetryDoesNotTurnRepeatedSensorObservationsIntoTwoConfirmationSamples() {
        val main = MainActivity()
        var clockNow = 1_000L
        MainActivityControllerTestFixture.bindRouteGuidanceClock(main) { clockNow }
        main.installRequestedRoute(route(), destination(), fix(50.0), null, "user_destination")
        val nav = navigator(main)
        assertTrue(requireNotNull(nav.update(fix(50.0), 1_000L, false,
            facingObservation = facing()).instruction).contains("약 12시 방향"))
        val epoch = WalkSessionLifecycle().snapshot().epoch
        val observations = mutableListOf<RouteNavigatorUpdate>()
        fun retry(now: Long, measuredAt: Long) {
            clockNow = now
            val compass = RouteCompassHeadingResult(RouteFacingObservation(0.0, 3.0, measuredAt),
                RouteCompassHeadingReason.VALID)
            main.retryAdmittedNavigationSpeech(now, epoch, compass) { update, owner, actualCompass ->
                assertEquals(epoch, owner)
                assertSame(compass, actualCompass)
                observations += update
            }
        }
        retry(1_100L, 1_100L)
        retry(1_400L, 1_100L)
        assertEquals(2, observations.size)
        assertTrue(observations.none { it.instruction.orEmpty().contains("약 3시 방향") })
        assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(nav.currentAcceptedRouteMatchFor(1_400L))
        retry(1_450L, 1_450L)
        assertTrue(requireNotNull(observations.last().instruction).contains("약 3시 방향"))
        assertFalse(observations.last().shouldReroute)
        assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
    }

    @Test fun periodicRetryHonorsTheMainTmapPauseWithoutChangingItsAcceptedLocation() {
        val main = MainActivity()
        var clockNow = 1_000L
        MainActivityControllerTestFixture.bindRouteGuidanceClock(main) { clockNow }
        main.installRequestedRoute(route(), destination(), fix(50.0), null, "user_destination")
        val nav = navigator(main)
        nav.update(fix(50.0), 1_000L, false, facingObservation = facing())
        val match = requireNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
        val epoch = WalkSessionLifecycle().snapshot().epoch
        val compass = RouteCompassHeadingResult(facing(1_300L), RouteCompassHeadingReason.VALID)
        var dispatches = 0
        clockNow = 1_300L
        set(main, "directionGuidancePauseReason", "tmap_unavailable")
        main.retryAdmittedNavigationSpeech(1_300L, epoch, compass) { _, _, _ -> dispatches++ }
        assertEquals(0, dispatches)
        assertEquals(match, nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(nav.currentAcceptedRouteMatchFor(1_300L))
        set(main, "directionGuidancePauseReason", null)
        main.retryAdmittedNavigationSpeech(1_300L, epoch, compass) { update, _, _ ->
            assertNotNull(update.instruction); dispatches++
        }
        assertEquals(1, dispatches)
        assertEquals(match, nav.currentAcceptedRouteMatchFor(1_000L))
    }

    @Test fun bothStorageBoundariesKeepCurrentIdentityAndLocationBeforeTheActualInstallHelper() {
        val request = MainActivityRefactorSource.function(source(), "private fun requestRoute(")
        assertEquals(2, Regex("routeResponseLocationStatus\\(").findAll(request).count())
        assertTrue(request.indexOf("routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)") <
            request.indexOf("val installLocationStatus = routeResponseLocationStatus("))
        assertTrue(request.indexOf("val installLocationStatus = routeResponseLocationStatus(") <
            request.indexOf("submitRouteSnapshotResponseStorage("))
        assertTrue(request.indexOf("val completedLocationStatus = routeResponseLocationStatus(") <
            request.indexOf("installRequestedRoute("))
        assertTrue(request.contains("if (!storageAdmissionCurrent())"))
        assertTrue(request.contains("val origin = facts.currentLocation"))
        assertTrue(MainActivityRefactorSource.composition(source(), "routeAcquisitionController")
            .contains("override val currentLocation get() = positioning.value.currentRouteLocationOrNull()"))
        assertTrue(request.contains("installRequestedRoute(route, destination, origin,"))
        assertTrue(request.contains("canRetainInitialRouteResponseForLocation(destination, reason,"))
    }

    private fun selectDestination(main: MainActivity) {
        val pageType = Class.forName("kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController\$NativeUiPage")
        set(main, "nativeUiPage", pageType.enumConstants.first { (it as Enum<*>).name == "GUIDANCE" })
        set(main, "pendingUiDestination", DestinationSearchResult("destination", "목적지", destination(),
            null, null, null, null))
    }
    private fun route() = WalkingRoute("TEST", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(100.0), point(100.0, -100.0)),
        listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 13)))
    private fun destination() = point(100.0, -100.0)
    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)
    private fun fix(eastM: Double, northM: Double = 0.0, at: Long = 1_000L) =
        point(eastM, northM).let { TrustedLocation(it.latitude, it.longitude, 2f, at) }
    private fun facing(at: Long = 1_000L) = RouteFacingObservation(90.0, 3.0, at)
    private fun navigator(main: MainActivity) = MainActivityControllerTestFixture.field("routeNavigator")
        .apply { isAccessible = true }.get(main) as RouteNavigator
    private fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
        .apply { isAccessible = true }.set(main, value)
    private fun source() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
}
