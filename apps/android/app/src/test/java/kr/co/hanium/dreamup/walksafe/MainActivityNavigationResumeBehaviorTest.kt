package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeStopCleanupCheckpoints
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual pause/lifecycle and admitted completion bodies. Android readiness, GPS admission and HTTP are not run. */
class MainActivityNavigationResumeBehaviorTest {
    @After fun releaseGateway() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun acceptedResumeAfterRealSuspensionDoesNotLeaveGuidanceWithoutRouteWork() {
        val fixture = Fixture()
        fixture.installRoute()

        fixture.pause()
        assertFalse(fixture.navigator.hasRoute())
        assertEquals(false, fixture.field("isRouteActive"))
        assertEquals(fixture.destination, fixture.field("currentDestination"))
        fixture.complete(fixture.confirmResume())
        fixture.deliverLocation()

        assertEquals("Completed explicit resume must dispatch the installed destination exactly once",
            1, fixture.requests.size)
        assertEquals(fixture.destination, fixture.requests.single().destination)
        assertEquals("walk_resume", fixture.requests.single().reason)
        assertTrue(fixture.requests.single().retainExplicitStart)
        assertEquals(fixture.evidence, fixture.requests.single().evidence)
    }

    @Test fun selectedDestinationWithoutInstalledGuidanceDoesNotStartOnResume() {
        val fixture = Fixture()
        fixture.set("currentDestination", fixture.destination)
        fixture.pause()
        fixture.complete(fixture.confirmResume())
        fixture.deliverLocation()
        assertTrue(fixture.requests.isEmpty())
        assertFalse(fixture.pendingRequest())
        assertEquals(false, fixture.field("isRouteActive"))
        assertFalse(fixture.explicitStartPending())
    }

    @Test fun voicePauseRouteCleanupStillResumesThePreviouslyInstalledDestination() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.lifecycle.handle(WalkSessionEvent.RecheckRequested)
        assertEquals(WalkSessionState.PAUSED, fixture.lifecycle.snapshot().state)
        // Match only the incidental Android debug-log state; exercise the real cleanup body.
        fixture.set("latestNavigationState", "navigation=route_revalidation_required")
        fixture.main.cancelWalkSessionRouteState("voice_pause", null, AndroidNavigationCancellation(false, false))
        assertFalse(fixture.navigator.hasRoute())
        fixture.complete(fixture.confirmResume())
        assertEquals("Completed voice pause resume must dispatch the installed destination exactly once",
            listOf(fixture.expectedRequest()), fixture.requests)
    }

    @Test fun voicePauseRetiresPendingStartAndStopOwnersBeforeRetainingTheDestination() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.lifecycle.handle(WalkSessionEvent.RecheckRequested)
        fixture.seedPendingStartAndStopOwners()
        fixture.set("latestNavigationState", "navigation=route_revalidation_required")
        fixture.main.cancelWalkSessionRouteState("voice_pause", null, AndroidNavigationCancellation(false, false))
        fixture.assertStartAndStopOwnersCleared()
        assertEquals(fixture.destination, fixture.field("currentDestination"))
        fixture.complete(fixture.confirmResume())
        assertEquals(listOf(fixture.expectedRequest()), fixture.requests)
    }

    @Test fun otherRecheckReasonsAndCheckpointedCleanupStillResetTheDestination() {
        val cases = listOf("resume_readiness_changed" to null, "gateway_session_cleared" to null,
            "voice_pause" to WalkRuntimeStopCleanupCheckpoints())
        cases.forEach { (reason, checkpoints) ->
            val fixture = Fixture()
            fixture.installRoute()
            fixture.lifecycle.handle(WalkSessionEvent.RecheckRequested)
            fixture.seedPendingStartAndStopOwners()
            fixture.set("latestNavigationState", "navigation=destination_none")
            fixture.main.cancelWalkSessionRouteState(reason, checkpoints, AndroidNavigationCancellation(false, false))
            fixture.assertStartAndStopOwnersCleared()
            assertNull("reason=$reason checkpoints=${checkpoints != null}", fixture.field("currentDestination"))
            assertNull(fixture.field("suspendedNavigationResume"))
            assertFalse(fixture.navigator.hasRoute())
            fixture.complete(fixture.confirmResume())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun endAndSafetyStopCannotRetainAnEarlierResumeIntentEvenWithVoicePauseReason() {
        listOf(WalkSessionEvent.EndRequested, WalkSessionEvent.SafetyStopRequested).forEach { event ->
            val fixture = Fixture()
            fixture.installRoute()
            fixture.pause()
            val token = fixture.confirmResume()
            fixture.seedPendingStartAndStopOwners()
            fixture.lifecycle.handle(event)
            fixture.set("latestNavigationState", "navigation=destination_none")
            fixture.main.cancelWalkSessionRouteState("voice_pause", null, AndroidNavigationCancellation(false, false))
            fixture.assertStartAndStopOwnersCleared()
            assertNull(fixture.field("currentDestination"))
            assertNull(fixture.field("suspendedNavigationResume"))
            assertFalse(fixture.tryComplete(token))
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun duplicateCompletionCannotDispatchAgain() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        val token = fixture.confirmResume()
        fixture.complete(token)
        assertFalse(fixture.tryComplete(token))
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.completions)
    }

    @Test fun repeatedPausePreservesTheOriginallyInstalledDestination() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        fixture.pause()
        fixture.complete(fixture.confirmResume())
        assertEquals(listOf(fixture.expectedRequest()), fixture.requests)
    }

    @Test fun staleCompletionAfterAnotherPauseCannotConsumeTheNewResume() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        val oldToken = fixture.confirmResume()
        fixture.pause()
        val newToken = fixture.confirmResume()
        assertFalse(fixture.tryComplete(oldToken))
        assertEquals(newToken, fixture.field("walkSessionResumeActivationToken"))
        assertTrue(fixture.requests.isEmpty())
        fixture.complete(newToken)
        assertEquals(1, fixture.requests.size)
    }

    @Test fun pauseDuringCompletionPublicationDefersTheRouteUntilTheNextExplicitResume() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        fixture.complete(fixture.confirmResume(), duringPublication = fixture::pause)
        assertTrue(fixture.requests.isEmpty())
        fixture.complete(fixture.confirmResume())
        assertEquals(listOf(fixture.expectedRequest()), fixture.requests)
    }

    @Test fun endDuringCompletionPublicationDoesNotRestartTheRoute() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        fixture.complete(fixture.confirmResume()) {
            fixture.lifecycle.handle(WalkSessionEvent.EndRequested)
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun confirmedDestinationReplacementInvalidatesEvenAnEqualDestination() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        val token = fixture.confirmResume()
        fixture.main.cancelNavigationRequestsForDestinationSelection()
        fixture.set("currentDestination", fixture.destination.copy(latitude = 37.002))
        fixture.set("currentDestination", fixture.destination)
        fixture.complete(token)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun routeStopClearsTheSavedResumeIntent() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        val token = fixture.confirmResume()
        fixture.set("latestNavigationState", "navigation=destination_none")
        fixture.method("resetRouteState", Void.TYPE, Boolean::class.javaPrimitiveType!!)
            .invokeWithArguments(fixture.main, false)
        fixture.complete(token)
        assertTrue(fixture.requests.isEmpty())
        assertNull(fixture.field("currentDestination"))
    }

    @Test fun actorChangeAndSameActorNewLoginCannotInheritTheOldIntent() {
        for (changeActor in listOf(true, false)) {
            val fixture = Fixture()
            fixture.installRoute()
            fixture.pause()
            val token = fixture.confirmResume()
            if (changeActor) fixture.set("reporterUserId", "another-actor")
            else fixture.publish(GatewayFieldSession.verified("https://gateway.example.test",
                fixture.session.actorId, "walksafe_field_session=v2.new-login", Long.MAX_VALUE))
            fixture.complete(token)
            assertTrue("changeActor=$changeActor", fixture.requests.isEmpty())
        }
    }

    @Test fun verifiedRenewalOfTheSameLoginPreservesTheInstalledDestination() {
        val fixture = Fixture(longLived = true)
        fixture.installRoute()
        fixture.pause()
        fixture.publish(fixture.longLivedSession(rotation = 1L))
        fixture.complete(fixture.confirmResume())
        assertEquals(listOf(fixture.expectedRequest()), fixture.requests)
    }

    @Test fun differentSessionFamilyAndUnusableSessionCannotResume() {
        for (newFamily in listOf(true, false)) {
            val fixture = Fixture(longLived = true)
            fixture.installRoute()
            fixture.pause()
            if (newFamily) fixture.publish(fixture.longLivedSession(rotation = 1L, family = "b".repeat(32)))
            else fixture.session.invalidate()
            fixture.complete(fixture.confirmResume())
            assertTrue("newFamily=$newFamily", fixture.requests.isEmpty())
        }
    }

    @Test fun resumeGpsWaitSurvivesAnotherPause() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        fixture.complete(fixture.confirmResume())
        // The request sink models the admission boundary; a GPS wait has not allocated a request id.
        fixture.pause()
        fixture.complete(fixture.confirmResume())
        assertEquals(listOf(fixture.expectedRequest(), fixture.expectedRequest()), fixture.requests)
    }

    @Test fun exactResumeHttpAdmissionSurvivesAnotherPause() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        val token = fixture.confirmResume()
        fixture.complete(token)
        fixture.admitRequest(token, "walk_resume")
        fixture.pause()
        fixture.complete(fixture.confirmResume())
        assertEquals(listOf(fixture.expectedRequest(), fixture.expectedRequest()), fixture.requests)
        assertFalse(fixture.navigator.hasRoute())
    }

    @Test fun consumedGpsContextKeepsResumeProvenanceThroughHttpAdmissionAndPause() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        val token = fixture.confirmResume()
        fixture.complete(token)
        val reason = fixture.main.resumedNavigationRequestReason(token.epoch,
            GatewaySessionProcessCoordinator.snapshot().generation, fixture.destination, fixture.evidence)
        assertEquals("walk_resume", reason)
        fixture.admitRequest(token, reason)
        fixture.pause()
        fixture.complete(fixture.confirmResume())
        assertEquals(listOf(fixture.expectedRequest(), fixture.expectedRequest()), fixture.requests)
    }

    @Test fun staleGpsContextAndUnrelatedRequestIdCannotAcquireResumeProvenance() {
        val fixture = Fixture()
        fixture.installRoute()
        fixture.pause()
        val token = fixture.confirmResume()
        fixture.complete(token)
        val generation = GatewaySessionProcessCoordinator.snapshot().generation
        assertEquals("user_destination", fixture.main.resumedNavigationRequestReason(
            token.epoch, generation - 1, fixture.destination, fixture.evidence))
        assertEquals("user_destination", fixture.main.resumedNavigationRequestReason(
            token.epoch, generation, fixture.destination, null))
        fixture.admitRequest(token, "walk_resume", idJump = 2)
        fixture.pause()
        fixture.complete(fixture.confirmResume())
        assertEquals("An unrelated id must not be promoted to installed-route provenance", 1, fixture.requests.size)
    }

    @Test fun ordinaryRequestAndExplicitCancellationDoNotRetainTheResumeOwner() {
        for (cancel in listOf(false, true)) {
            val fixture = Fixture()
            fixture.installRoute()
            fixture.pause()
            val token = fixture.confirmResume()
            fixture.complete(token)
            fixture.admitRequest(token, if (cancel) "walk_resume" else "user_destination")
            if (cancel) fixture.main.invalidateActiveRouteRequestLease()
            fixture.pause()
            fixture.complete(fixture.confirmResume())
            assertEquals("cancel=$cancel", 1, fixture.requests.size)
        }
    }

    @Test fun initialRequestWithoutAnInstalledRouteIsNotPromotedOnPause() {
        val fixture = Fixture()
        fixture.set("currentDestination", fixture.destination)
        fixture.set("isRouteActive", true)
        (fixture.field("routeRequestInFlight") as AtomicBoolean).set(true)
        fixture.pause()
        fixture.complete(fixture.confirmResume())
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun productionWiringKeepsFreshRouteAdmissionAndConsumedGpsContext() {
        val source = sequenceOf(File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"))
            .first { it.isFile }.readText()
        fun owner(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()
        val commands = owner("voice/interaction/VoiceCommandInteractionController.kt")
        val voice = MainActivityRefactorSource.member(commands, "executeWalkSessionVoiceAction")
        assertTrue(voice.contains("WalkSessionVoiceAction.PAUSE ->"))
        assertTrue(voice.contains("walk.pauseWalk()"))
        val voiceWire = MainActivityRefactorSource.factory(source, "createVoiceInteractionControllers")
        assertTrue(voiceWire.contains("enterWalkSessionForegroundRecheckAndCancelOutputs(\"voice_pause\")"))
        val cleanup = owner("session/runtime/WalkSessionCleanupController.kt")
        val recheck = MainActivityRefactorSource.member(cleanup, "enterWalkSessionForegroundRecheckAndCancelOutputs")
        assertTrue(recheck.contains("walk.transition(WalkSessionEvent.RecheckRequested)"))
        assertTrue(recheck.contains("cancelWalkSessionOutputs(reason)"))
        val outputs = MainActivityRefactorSource.member(cleanup, "cancelWalkSessionOutputsInternal")
        assertTrue(outputs.contains("navigation.cancelWalkSessionRouteState(reason, checkpoints, navigationCancellation)"))
        val terminal = MainActivityRefactorSource.member(source, "completeAdmittedWalkSessionResume")
        assertTrue(terminal.contains("-> Unit = ::requestRoute"))
        assertFalse(terminal.contains("setRoute("))
        MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        val acquisition = owner("navigation/session/RouteAcquisitionController.kt")
        val request = MainActivityRefactorSource.member(acquisition, "requestRoute", 1)
        assertTrue(ReportStaticSourceInspector.appearsInOrder(request,
            "val origin = facts.currentLocation", "val requestId = ++routeRequestGeneration",
            "bindResumedNavigationRequest(expectedWalkEpoch, requestId, gatewaySession, destination, reason)"))
        val continuation = MainActivityRefactorSource.member(acquisition, "continueAdmittedExplicitRouteStartIfReady")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(continuation,
            "consumeIfCurrent(", "resumedNavigationRequestReason("))
        assertTrue(continuation.contains("resumedNavigationRequestReason(retained.walkEpoch, retained.gatewayGeneration,"))
    }

    private data class Request(val destination: RoutePoint, val reason: String,
        val retainExplicitStart: Boolean, val evidence: DestinationCoordinateEvidence?)

    private class Fixture(longLived: Boolean = false) {
        val main = MainActivity()
        var now = 1_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "navigation-resume-walk" },
            clock = { now }, elapsedRealtimeClock = { now })
        val destination = RoutePoint(37.0009, 127.0, "목적지")
        val evidence = DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR,
            listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, destination)))
        val acquisition get() = MainActivityControllerTestFixture.controller(main, "routeAcquisitionController") as RouteAcquisitionController
        val navigator get() = field("routeNavigator") as RouteNavigator
        val requests = mutableListOf<Request>()
        var completions = 0
        val session: GatewayFieldSession
        init {
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            session = if (longLived) longLivedSession() else GatewayFieldSession.verified("https://gateway.example.test",
                "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "walksafe_field_session=v2.resume", Long.MAX_VALUE)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session,
                FirstRunOnboardingPolicy.initial()))
            set("reporterUserId", session.actorId)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness(WalkSessionAction.START_WALK)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            set("walkSessionLifecycle", lifecycle)
            set("isActivityForeground", true)
        }
        fun installRoute() {
            navigator.setRoute(WalkingRoute("STAIR_AVOID", WalkingRouteSummary(100, 80),
                listOf(RoutePoint(37.0, 127.0), destination), emptyList()), destination = destination,
                destinationCoordinateEvidence = evidence)
            set("currentDestination", destination)
            set("isRouteActive", true)
        }
        fun pause() {
            lifecycle.handle(WalkSessionEvent.EnteredBackground)
            set("isActivityForeground", false)
            // Keep the unchanged debug status out of Android Log; route-state code is executed.
            set("latestNavigationState", "navigation=route_revalidation_required")
            acquisition.suspendNavigationForRecovery(AndroidNavigationCancellation(false, false))
        }
        fun confirmResume(): WalkSessionConfirmationToken {
            now += 1_000L
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(readiness(WalkSessionAction.RESUME_WALK)))
            val token = requireNotNull(lifecycle.snapshot().confirmationToken)
            lifecycle.handle(WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.START, token))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            set("isActivityForeground", true)
            set("walkSessionResumeActivationToken", token)
            return token
        }
        @Suppress("UNCHECKED_CAST")
        fun seedPendingStartAndStopOwners() {
            val context = RouteAcquisitionController.ExplicitRouteStartContext(
                walkEpoch = lifecycle.snapshot().epoch,
                gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation,
                destination = destination,
                destinationCoordinateEvidence = evidence,
                waitForInitialLocationConnection = true,
                gatewaySession = session,
            )
            (field("pendingExplicitRouteStart") as PendingExplicitRouteStart<Any>).replace(context, now)
            val starts = field("installedRouteStartSpeechCoordinator") as InstalledRouteStartSpeechCoordinator
            val installed = starts.install("안내 시작") { true }
            assertTrue(starts.inheritWaitingFailure("이 시작에 속한 대기 음성 실패"))
            assertSame(installed, starts.failureOwner)
            set("guidanceStopConfirmationRouteGeneration", field("routeRequestGeneration"))
            set("guidanceStopConfirmationWalkEpoch", lifecycle.snapshot().epoch)
        }
        fun assertStartAndStopOwnersCleared() {
            assertFalse(explicitStartPending())
            val starts = field("installedRouteStartSpeechCoordinator") as InstalledRouteStartSpeechCoordinator
            assertNull(starts.pending)
            assertNull(starts.failureOwner)
            assertNull(field("guidanceStopConfirmationRouteGeneration"))
            assertNull(field("guidanceStopConfirmationWalkEpoch"))
        }
        fun expectedRequest() = Request(destination, "walk_resume", true, evidence)
        fun complete(token: WalkSessionConfirmationToken, duringPublication: () -> Unit = {}) {
            val previousCompletions = completions
            assertTrue(tryComplete(token, duringPublication))
            assertNull(field("walkSessionResumeActivationToken"))
            assertEquals(previousCompletions + 1, completions)
        }
        fun tryComplete(token: WalkSessionConfirmationToken, duringPublication: () -> Unit = {}) =
            main.completeAdmittedWalkSessionResume(token,
                publishCompletion = { completions += 1; duringPublication() },
                requestNavigation = { destination, reason, retain, evidence ->
                    requests += Request(destination, reason, retain, evidence)
                })
        // Execute the real ownership hook at the already-admitted request boundary; no HTTP is sent.
        fun admitRequest(token: WalkSessionConfirmationToken, reason: String, idJump: Int = 1) {
            val requestId = (field("routeRequestGeneration") as Int) + idJump
            (field("routeRequestInFlight") as AtomicBoolean).set(true)
            set("isRouteActive", true)
            set("routeRequestGeneration", requestId)
            main.bindResumedNavigationRequest(token.epoch, requestId,
                requireNotNull(GatewaySessionProcessCoordinator.snapshot().session), destination, reason)
        }
        fun longLivedSession(rotation: Long = 0L, family: String = "f".repeat(32)): GatewayFieldSession =
            GatewayFieldSession.longLivedSession("https://gateway.example.test",
                "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "fixture-device", family, rotation,
                "walksafe_field_session=v2.resume.rotation.$rotation", "r".repeat(64),
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        fun publish(session: GatewayFieldSession) {
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session,
                FirstRunOnboardingPolicy.initial()))
        }
        fun deliverLocation() {
            val fix = TrustedLocation(37.0, 127.0, 5f, now)
            set("latestTrustedLocation", fix)
            method("updateRouteGuidance", Void.TYPE, TrustedLocation::class.java, TrustedLocation::class.java)
                .invokeWithArguments(main, fix, fix)
        }
        fun readiness(action: WalkSessionAction): WalkSessionReadinessSnapshot {
            val epoch = lifecycle.snapshot().epoch
            val collector = WalkSessionReadinessCollector(epoch, WalkSessionReadinessPlan(action, WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach {
                collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY))
            }
            return collector.build(now)
        }
        fun pendingRequest() = (field("routeRequestInFlight") as AtomicBoolean).get()
        fun explicitStartPending() = (field("pendingExplicitRouteStart") as PendingExplicitRouteStart<*>).currentOrNull() != null
        fun field(name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        fun set(name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        fun method(name: String, result: Class<*>, vararg arguments: Class<*>) =
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup()).findVirtual(
                MainActivity::class.java, name, MethodType.methodType(result, arguments.toList()))
    }
}
