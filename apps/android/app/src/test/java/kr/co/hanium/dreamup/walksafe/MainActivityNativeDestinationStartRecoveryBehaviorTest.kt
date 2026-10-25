package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Main pending/posted handoff and installed-start arbitration with actual Navigator.
 * Android view posting, navigation SDK admission, destination HTTP handoff and TTS are test sinks;
 * actual Activity rendering, SDK permissions, HTTP and audible playback are not executed. */
class MainActivityNativeDestinationStartRecoveryBehaviorTest {
    @After fun clearSession() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun postedStartRejectedByNavigationAdmissionContinuesOnceAfterRecovery() {
        val f = Fixture()
        f.requestFromPrewalk()
        f.admitted = false
        f.renderDestinationContinuation()
        assertEquals(1, f.posts.size)
        f.drain()
        assertEquals(0, f.starts)
        f.admitted = true
        repeat(3) { f.renderDestinationContinuation() }
        f.drain()
        assertEquals("The original native start must survive a temporary admission rejection", 1, f.starts)
        repeat(3) { f.renderDestinationContinuation() }
        f.drain()
        assertEquals(1, f.starts)
    }

    @Test fun directActiveStartRejectedBeforeExplicitRouteHandoffContinuesAfterRecovery() {
        val f = Fixture()
        assertNull(f.field("pendingNativeUiPage"))
        f.admitted = false
        f.handoff()
        assertEquals(0, f.starts)
        f.admitted = true
        repeat(3) { f.renderDestinationContinuation() }
        f.drain()
        assertEquals("An ACTIVE start still owns the human request before navigation admission", 1, f.starts)
    }

    @Test fun ordinaryAdmittedStartHandsOffOnceAcrossRepeatedRenders() {
        val f = Fixture()
        f.requestFromPrewalk()
        repeat(3) { f.renderDestinationContinuation() }
        assertEquals(1, f.posts.size)
        f.drain()
        repeat(3) { f.renderDestinationContinuation() }
        f.drain()
        assertEquals(1, f.starts)
        assertSame(f.destination, f.started.single())
        assertNull(f.field("pendingNativeUiPage"))
    }

    @Test fun oldPostedContinuationCannotStartAfterDestinationAccountEpochSensorOrForegroundChange() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.set("pendingUiDestination", it.destination.copy(id = "another")) },
            { it.set("pendingUiDestination", null); it.page("HOME") },
            { it.set("reporterUserId", "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") },
            { GatewaySessionProcessCoordinator.beginOperation() },
            { it.base.lifecycle.handle(WalkSessionEvent.EnteredBackground) },
            { it.set("isActivityForeground", false) },
            { it.base.now += 1; it.base.nativeCheck() },
        )
        changes.forEach { change ->
            val f = Fixture()
            f.requestFromPrewalk()
            f.renderDestinationContinuation()
            change(f)
            f.drain()
            assertEquals("Retired native approval must not start a route", 0, f.starts)
        }
    }

    @Test fun aDownstreamExplicitGpsWaitConsumesNativeIntentWithoutRenewingItsRequest() {
        val f = Fixture()
        f.requestFromPrewalk()
        var selections = 0
        val waits = f.field("pendingExplicitRouteStart") as PendingExplicitRouteStart<kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController.ExplicitRouteStartContext>
        f.main.handOffNativeDestinationStart(f.destination, prepare = {}, select = {
            selections++
            f.set("currentDestination", it.point)
            waits.replace(kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController.ExplicitRouteStartContext(f.base.epoch,
                GatewaySessionProcessCoordinator.snapshot().generation, it.point, it.coordinateEvidence, true),
                1_234L, keepUntilCancelled = true)
            false // requestRoute has accepted the human intent, but no HTTP call exists yet.
        }, refreshUi = {}, navigationAdmitted = { true })
        val original = requireNotNull(waits.currentOrNull())
        repeat(4) { f.renderDestinationContinuation() }
        f.drain()
        assertEquals(1, selections)
        assertEquals(0, f.starts)
        assertSame(original, waits.currentOrNull())
        assertEquals(1_234L, original.requestedAtElapsedRealtimeMs)
        assertTrue(original.keepUntilCancelled)
        assertNull(f.field("pendingNativeUiPage"))
    }

    @Test fun ordinaryDownstreamFailureDoesNotCreateAnAutomaticRouteRetry() {
        val f = Fixture()
        var selections = 0
        f.main.handOffNativeDestinationStart(f.destination, prepare = {}, select = { selections++; false },
            refreshUi = {}, navigationAdmitted = { true })
        repeat(3) { f.renderDestinationContinuation() }
        f.drain()
        assertEquals(1, selections)
        assertEquals(0, f.starts)
        assertNull(f.field("pendingNativeUiPage"))
    }

    @Test fun admissionClosingInsideSelectionRetainsOnlyTheSameApproval() {
        val f = Fixture()
        f.main.handOffNativeDestinationStart(f.destination, prepare = {}, select = {
            f.admitted = false; false
        }, refreshUi = {}, navigationAdmitted = { f.admitted })
        assertNotNull(f.field("pendingNativeUiPage"))
        f.admitted = true
        f.renderDestinationContinuation()
        f.drain()
        assertEquals(1, f.starts)
    }

    @Test fun renderingDuringTheActiveHandoffCannotQueueAnotherStart() {
        val f = Fixture()
        f.requestFromPrewalk()
        f.main.handOffNativeDestinationStart(f.destination,
            prepare = { f.renderDestinationContinuation() },
            select = { f.renderDestinationContinuation(); true },
            refreshUi = { f.renderDestinationContinuation() }, navigationAdmitted = { true })
        assertTrue(f.posts.isEmpty())
        assertNull(f.field("pendingNativeUiPage"))
    }

    @Test fun cancelThenNewApprovalForTheSameDestinationRejectsOldPostedCallbacks() {
        val f = Fixture()
        f.requestFromPrewalk()
        f.renderDestinationContinuation()
        val old = f.posts.removeAt(0)
        f.cancel()
        f.requestFromPrewalk()
        f.renderDestinationContinuation()
        old.run()
        assertEquals(0, f.starts)
        f.drain()
        assertEquals(1, f.starts)
    }

    @Test fun equivalentReplacementDestinationPermissionAndSourceChangesRetireTheOldApproval() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.set("pendingUiDestination", it.destination.copy()) },
            { it.set("permissionRecoveryGate", PermissionRecoveryGate()) },
            { it.set("locationCallbackGeneration", (it.field("locationCallbackGeneration") as Int) + 1) },
            { it.set("positioningCollectionBinding", null) },
            { it.set("routeRequestGeneration", (it.field("routeRequestGeneration") as Int) + 1) },
        )
        changes.forEach { change ->
            val f = Fixture()
            f.requestFromPrewalk()
            f.admitted = false
            f.renderDestinationContinuation()
            f.drain()
            change(f)
            f.admitted = true
            repeat(2) { f.renderDestinationContinuation() }
            f.drain()
            assertEquals(0, f.starts)
            assertNull(f.field("pendingNativeUiPage"))
        }
    }

    @Test fun firstGeneralSourceRegistrationKeepsTheSameNativeApproval() {
        val f = Fixture()
        val originalOwner = f.field("locationCollectionOwner")
        val originalCallback = f.field("locationCallback")
        val coordinator = f.field("positioningCoordinator")
        val originalBinding = f.field("positioningCollectionBinding")!!
        val oldGeneration = f.field("locationCallbackGeneration") as Int
        f.set("positioningCollectionBinding", null)
        f.set("locationCollectionOwner", null)
        f.set("locationCallback", null)
        f.requestFromPrewalk()
        f.admitted = false
        f.renderDestinationContinuation()
        f.drain()
        val generation = oldGeneration + 1
        val binding = originalBinding.javaClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(coordinator, (coordinator as kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator)
                .currentInputGeneration(), originalOwner, generation, f.base.now)
        f.set("locationCallbackGeneration", generation)
        f.set("positioningCollectionBinding", binding)
        f.set("locationCollectionOwner", originalOwner)
        f.set("locationCallback", originalCallback)
        f.admitted = true
        f.renderDestinationContinuation()
        f.drain()
        assertEquals(1, f.starts)
    }

    @Test fun connectedLowConfidenceRouteCanDeliverItsNonActionReferenceWhileOrdinaryStartWaits() {
        val f = RouteFixture(lateralM = 5.0)
        assertEquals(RouteResponseInstallLocationStatus.CONNECTED,
            f.main.routeResponseLocationStatus(f.route, f.fix, f.fix, 1_000L))
        assertNull(f.nav.currentAcceptedRouteMatchFor(f.fix.elapsedRealtimeMs))
        assertTrue(f.update.directionOnly)
        assertFalse(f.update.userDecisionRequired)
        assertFalse(f.update.shouldReroute)
        assertTrue(requireNotNull(f.update.speechCueToken).requiresFacingEvidence)
        val text = requireNotNull(f.update.instruction)
        assertTrue(text, text.contains("경로 진행 방향"))
        assertTrue(text, text.contains("남은 거리와 회전 지점은 아직 안내할 수 없습니다"))
        assertFalse(text, text.contains("시작점"))
        var references = 0
        val held = f.hold()
        if (!held) {
            assertTrue(f.nav.reserveInstruction(f.update))
            assertTrue(f.nav.isInstructionCurrent(f.update, 1_000L, f.facing))
            references++
        }
        assertEquals(0, f.starts)
        assertSame(f.pending, f.installedStart.pending)
        assertEquals("A valid non-action reference must reach the existing START guards", 1, references)
    }

    @Test fun referenceCanPrecedeAcceptedStartWithoutConsumingItsOrdinaryAnnouncement() {
        val f = RouteFixture(lateralM = 5.0)
        assertFalse(f.hold())
        assertTrue(f.nav.reserveInstruction(f.update))
        assertTrue(f.nav.isInstructionCurrent(f.update, 1_000L, f.facing))
        f.nav.acknowledgeInstruction(f.update, 1_000L)
        val onRouteLatitude = f.fix.latitude - 5.0 / 111_194.92664455874
        val crossingFix = TrustedLocation(onRouteLatitude, f.fix.longitude, 1f, 2_000L)
        val crossing = f.nav.update(crossingFix, 2_000L, false,
            facingObservation = f.facing.copy(observedAtElapsedRealtimeMs = 2_000L))
        // A five-metre sideways correction creates a southbound inferred course, which cannot
        // confirm the eastbound route. Keep that real rejection; do not relax the matcher.
        assertNull(f.nav.currentAcceptedRouteMatchFor(2_000L))
        assertEquals(kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason.LOW_CONFIDENCE,
            f.nav.currentRouteMatch()?.reason)
        // The preceding reference was completed only one second ago: a rate-limited update has
        // no speech token/instruction, so its default directionOnly flag describes no new cue.
        assertEquals("guidance_rate_limited", crossing.reason)
        assertNull(crossing.instruction)
        assertNull(crossing.pendingUserDecision)
        // A distinct subsequent observation confirms the stable position without sideways motion.
        val stableFix = TrustedLocation(onRouteLatitude, f.fix.longitude, 1f, 3_000L)
        val accepted = f.nav.update(stableFix, 3_000L, false,
            facingObservation = f.facing.copy(observedAtElapsedRealtimeMs = 3_000L))
        assertNotNull(f.nav.currentAcceptedRouteMatchFor(3_000L))
        assertFalse(accepted.directionOnly)
        var done: (() -> Unit)? = null
        var starts = 0
        var resumes = 0
        repeat(3) {
            assertTrue(f.main.holdRouteInstructionsForStartAnnouncement(
                locationReady = { f.nav.currentAcceptedRouteMatchFor(3_000L) != null },
                submitSpeech = { _, current, terminal -> assertTrue(current()); starts++; done = terminal; true },
                resumeInstructions = { resumes++ }, speechBusy = { false }, routeUpdate = accepted))
        }
        assertEquals(1, starts)
        assertEquals(0, resumes)
        done!!.invoke()
        assertEquals(1, resumes)
    }

    @Test fun referenceStillRejectsStaleFacingChangedRouteAndNonReferenceMovement() {
        val f = RouteFixture(lateralM = 5.0)
        assertFalse(f.hold())
        assertTrue(f.nav.reserveInstruction(f.update))
        assertFalse(f.nav.isInstructionCurrent(f.update, 1_501L, f.facing))
        assertFalse(f.nav.isInstructionCurrent(f.update, 1_000L, f.facing.copy(degreesTrueNorth = 270.0)))
        f.nav.clear()
        assertFalse(f.nav.isInstructionCurrent(f.update, 1_000L, f.facing))
        val movement = RouteFixture(lateralM = 0.0)
        assertFalse(movement.update.directionOnly)
        assertTrue(movement.main.holdRouteInstructionsForStartAnnouncement(
            locationReady = { false }, submitSpeech = { _, _, _ -> fail("not accepted"); false },
            speechBusy = { false }, routeUpdate = movement.update))
        assertTrue(movement.main.holdRouteInstructionsForStartAnnouncement(
            locationReady = { false }, submitSpeech = { _, _, _ -> fail("no reference token"); false },
            speechBusy = { false }, routeUpdate = movement.update.copy(directionOnly = true)))
    }

    @Test fun submittedOrdinaryStartKeepsReferenceSerializedUntilItsTerminal() {
        val f = RouteFixture(lateralM = 5.0)
        // The location-admission sink changes after the ordinary announcement was submitted.
        var locationReady = true
        var ordinaryStarts = 0
        var terminal: (() -> Unit)? = null
        assertTrue(f.main.holdRouteInstructionsForStartAnnouncement(
            locationReady = { locationReady },
            submitSpeech = { _, current, done ->
                assertTrue(current()); ordinaryStarts++; terminal = done; true
            }, resumeInstructions = { fail("location is no longer ready at terminal") },
            speechBusy = { false }, deferInstructions = { fail("not busy") },
        ))
        assertEquals(1, ordinaryStarts)
        assertNotNull(terminal)
        locationReady = false
        repeat(3) { assertTrue(f.hold()) }
        assertEquals(0, f.starts)
        assertSame(f.pending, f.installedStart.pending)
        terminal!!.invoke()
        terminal!!.invoke()
        assertNull(f.installedStart.pending)
        assertFalse(f.hold())
        assertEquals(1, ordinaryStarts)
        assertEquals(0, f.starts)
    }

    @Test fun acceptedLocationSubmitsOrdinaryStartOnceAndResumesOnlyAfterTerminal() {
        val f = RouteFixture(lateralM = 0.0)
        assertNotNull(f.nav.currentAcceptedRouteMatchFor(f.fix.elapsedRealtimeMs))
        assertFalse(f.update.directionOnly)
        repeat(3) { assertTrue(f.hold()) }
        assertEquals(1, f.starts)
        assertEquals(0, f.resumes)
        f.done!!.invoke()
        f.done!!.invoke()
        assertEquals(1, f.resumes)
        assertNull(f.installedStart.pending)
    }

    @Test fun actualRenderButtonAndBothRouteCallersUseTheTestedBodies() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/ui/workflow/NativeWorkflowController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val render = method("renderMainUi")
        assertTrue(render.contains("homeAvailable && pending != null && queries.isWalkSessionRuntimeActive()"))
        assertTrue(render.contains("queries.nativeFeatureAvailable(pending)"))
        assertTrue(render.contains("startDestination = consumePendingNativeDestinationStart()"))
        assertTrue(render.contains("destinations.postNativeDestinationStart(it)"))
        val post = MainActivityRefactorSource.member(source, "postNativeDestinationStart")
        assertTrue(post.contains("navigationAdmitted: () -> Boolean = ::currentNavigationCollectionAllowsWork"))
        assertTrue(post.contains("start: () -> Unit = ::startNativeDestinationGuidance"))
        assertTrue(method("postNativeDestinationStart").contains("queries.isWalkSessionRuntimeActive() && navigationAdmitted()"))
        assertTrue(method("postNativeDestinationStart").contains("startCoordinator.post("))
        val coordinator = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/NativeDestinationStartCoordinator.kt").readText()
        assertTrue(coordinator.contains("if (pending !== owner) return@Runnable"))
        assertTrue(coordinator.contains("owner.posted || owner.handingOff || isDispatching"))
        assertTrue(coordinator.contains("if (handedOff || !owner.isCurrent() || admitted())"))
        val button = MainActivityRefactorSource.member(source, "startNativeDestinationGuidance")
        val handoff = MainActivityRefactorSource.member(source, "handOffNativeDestinationStart")
        val entry = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/start/NativeGuidanceEntryController.kt")
        assertTrue(button.contains("nativeGuidanceEntryController.start()"))
        assertTrue(MainActivityRefactorSource.member(entry, "start").contains("actions.handOffDestinationStart(destination)"))
        assertTrue(MainActivityRefactorSource.property(source, "nativeGuidanceEntryController").contains("handOffNativeDestinationStart(destination)"))
        assertTrue(method("handOffNativeDestinationStart").contains("startCoordinator.handOff("))
        assertTrue(handoff.contains("onDestinationSelected(it, retainExplicitStartUntilTrustedLocation = true)"))
        assertTrue(handoff.contains("nativeWorkflowController.handOffNativeDestinationStart(destination, prepare, select, refreshUi, navigationAdmitted)"))
        assertTrue(post.contains("nativeWorkflowController.postNativeDestinationStart(destination, post, navigationAdmitted, start)"))
        val guidance = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt")
        assertEquals(2, Regex("routeUpdate = update,").findAll(guidance).count())
        val update = MainActivityRefactorSource.member(guidance, "updateRouteGuidance")
        assertTrue(update.indexOf("applyRouteDeviationSafetyUpdate(update)") < update.indexOf("holdRouteInstructionsForStartAnnouncement("))
        assertTrue(update.indexOf("holdRouteInstructionsForStartAnnouncement(") < update.indexOf("tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)"))
        assertTrue(MainActivityRefactorSource.member(source, "renderMainUi").contains("nativeWorkflowController.renderMainUi()"))
    }

    private class Fixture {
        val base = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val main = base.main
        val destination = DestinationSearchResult("selected", "목적지", RoutePoint(37.001, 127.0),
            null, null, null, null)
        val posts = mutableListOf<Runnable>()
        val started = mutableListOf<DestinationSearchResult>()
        var admitted = true
        var starts = 0
        init {
            base.activate()
            val actor = field("reporterUserId") as String
            val education = PriorityUserOnboardingSnapshot(
                ageBand = PriorityUserAgeBand.VERIFIED_14_PLUS,
                educationReviewed = true, safePracticePlaceConfirmed = true,
                completedPractices = PriorityUserPractice.entries.toSet(),
                phonePostureAcknowledged = true, practiceNecessityReviewed = true,
                educationAccepted = true, usageConditionsAcknowledged = true,
                appUsageReviewed = true, appUsageAccepted = true, hazardResponseConfirmed = true)
            set("priorityUserOnboardingPolicy", PriorityUserOnboardingPolicy(education))
            set("priorityUserOnboardingActorId", actor)
            val authority = GatewayWalkAuthorityController(elapsedClock = { base.now })
            val start = requireNotNull(authority.beginStart(base.epoch))
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                authority.completeStart(start, GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "GRANTED", actor, "fixture-device", base.epoch.walkSessionId, "fixture-lease", 1L,
                    100L, 100_000L, 100L, 100_000L))))
            set("gatewayWalkAuthorityController", authority)
            set("pendingUiDestination", destination)
            page("GUIDANCE")
            assertTrue(actualBoolean("shouldShowNativeHome"))
            assertTrue(actualBoolean("isWalkSessionRuntimeActive"))
        }
        fun requestFromPrewalk() = set("pendingNativeUiPage", pageValue("DESTINATION_CONFIRM"))
        fun renderDestinationContinuation() {
            main.consumePendingNativeDestinationStart()?.let { selected ->
                main.postNativeDestinationStart(selected, post = { posts += it },
                    navigationAdmitted = { admitted }, start = ::handoff)
            }
        }
        fun handoff() = main.handOffNativeDestinationStart(destination, prepare = {}, select = {
            // The existing onDestinationSelected Boolean reports the downstream admission result.
            if (admitted) { starts++; started += it; true } else false
        }, refreshUi = {}, navigationAdmitted = { admitted })
        fun drain() { while (posts.isNotEmpty()) posts.removeAt(0).run() }
        fun cancel() {
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, "cancelNativePendingFeatureEntry",
                    MethodType.methodType(Void.TYPE)).invokeWithArguments(main)
        }
        fun page(name: String) = set("nativeUiPage", pageValue(name))
        fun pageValue(name: String): Any = Class.forName("kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController\$NativeUiPage")
            .enumConstants.single { (it as Enum<*>).name == name }
        fun field(name: String) = base.field(name)
        fun set(name: String, value: Any?) = base.set(name, value)
        private fun actualBoolean(name: String): Boolean = MethodHandles.privateLookupIn(
            MainActivity::class.java, MethodHandles.lookup()).findVirtual(MainActivity::class.java,
            name, MethodType.methodType(Boolean::class.javaPrimitiveType)).invokeWithArguments(main) as Boolean
    }

    private class RouteFixture(lateralM: Double) {
        val main = MainActivity()
        val route = WalkingRoute("TEST", WalkingRouteSummary(200, 200),
            listOf(point(0.0), point(100.0), point(100.0, -100.0)),
            listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 13)))
        val fix = point(50.0, lateralM).let { TrustedLocation(it.latitude, it.longitude, 1f, 1_000L) }
        val facing = RouteFacingObservation(90.0, 3.0, 1_000L)
        val nav = RouteNavigator(nowElapsedRealtimeMs = { 1_000L })
        val update: RouteNavigatorUpdate
        val installedStart: InstalledRouteStartSpeechCoordinator
            get() = field("installedRouteStartSpeechCoordinator") as InstalledRouteStartSpeechCoordinator
        val pending: InstalledRouteStartSpeechCoordinator.PendingStart
        var starts = 0
        var resumes = 0
        var done: (() -> Unit)? = null
        init {
            set("routeNavigator", nav)
            main.installRequestedRoute(route, route.polyline.last(), fix, null, "user_destination")
            update = nav.update(fix, 1_000L, false, facingObservation = facing)
            pending = installedStart.install("목적지 안내를 시작합니다.") { true }
        }
        fun hold() = main.holdRouteInstructionsForStartAnnouncement(
            locationReady = { nav.currentAcceptedRouteMatchFor(fix.elapsedRealtimeMs) != null },
            submitSpeech = { _, current, terminal -> assertTrue(current()); starts++; done = terminal; true },
            resumeInstructions = { resumes++ }, initialJoinPending = nav.isWaitingForInitialRouteJoin(),
            speechBusy = { false }, deferInstructions = { fail("not busy") }, routeUpdate = update)
        fun field(name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        fun set(name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        private fun point(eastM: Double, northM: Double = 0.0) =
            RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)
    }
}
