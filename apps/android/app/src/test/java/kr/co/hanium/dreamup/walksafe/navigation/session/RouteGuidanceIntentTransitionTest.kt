package kr.co.hanium.dreamup.walksafe.navigation.session

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.voice.interaction.*
import kr.co.hanium.dreamup.walksafe.voice.composition.VoiceRoutePeerAdapter
import org.junit.Assert.*
import org.junit.Test

/** Runs the real Navigator, guidance intents and voice entry; records only external effects. */
class RouteGuidanceIntentTransitionTest {
    @Test fun recheckPublishesDecisionBeforePausingAndStartsServicesBeforeExplaining() {
        val f = Fixture().apply { install(); requestRecheckDecision() }
        val token = requireNotNull(f.navigator.pendingDecisionToken())
        assertTrue(f.guidance.requestLocationRecheck(token, f::startServices))
        assertEquals(listOf(
            "decision:null:true:null", "services:false:off_route_recheck",
            "status:navigation=off_route_location_recheck_requested auto_resume=false",
            "interaction:현재 위치를 다시 확인합니다. 새 위치가 정상일 때만 방향 안내를 재개합니다.",
        ), f.events)
        assertNull(f.navigator.pendingDecisionToken())
        assertFalse(f.run.onRoute)
        assertTrue(f.run.active)
        assertTrue(f.navigator.hasRoute())
    }

    @Test fun staleExpiredMissingAndInactiveRechecksHaveNoStateOrServiceEffects() {
        for (case in listOf("stale", "expired", "missing", "inactive")) {
            val f = Fixture().apply { install(); requestRecheckDecision() }
            val token = requireNotNull(f.navigator.pendingDecisionToken())
            when (case) {
                "stale" -> { f.install(); f.requestRecheckDecision() }
                "expired" -> f.now += 30_000L
                "inactive" -> f.run.markInactive()
            }
            val active = f.run.active
            assertFalse(case, f.guidance.requestLocationRecheck(token.takeUnless { case == "missing" }, f::startServices))
            assertTrue(case, f.events.isEmpty())
            assertEquals(active, f.run.active)
            assertTrue(case, f.run.onRoute)
            assertNull(f.run.directionPauseReason)
        }
    }

    @Test fun confirmedDeviationRecheckKeepsTheRerouteDecisionAndCannotAuthorizeAutomaticResume() {
        val f = Fixture().apply { install() }
        f.navigator.update(TrustedLocation(.001, 0.0, 2f, f.now), f.now, false)
        assertEquals(RouteNavigatorUserDecision.REROUTE, f.navigator.pendingUserDecision())
        val token = requireNotNull(f.navigator.pendingDecisionToken())
        assertTrue(f.guidance.requestLocationRecheck(token, f::startServices))
        assertEquals(RouteNavigatorUserDecision.REROUTE, f.navigator.pendingUserDecision())
        assertNotEquals(token, f.navigator.pendingDecisionToken())
        assertFalse(f.navigator.canResumeAfterPositionRecovery())
        assertEquals("decision:REROUTE:true:null", f.events.first())
        assertTrue(f.events.last().contains("기존 경로는 자동으로 재개하지 않습니다."))
    }

    @Test fun environmentalRestrictionRecordsResumeAndPauseBeforeCancellingOutputs() {
        val f = Fixture().apply { install() }
        val routeId = f.navigator.currentRouteId()
        val version = f.navigator.currentTactileRouteProgress().routeVersion
        f.guidance.restrictGpsNavigation {
            assertTrue(f.guidance.resumePending)
            assertEquals("environment_gps_quality", f.run.directionPauseReason)
            f.events += "cancel"
        }
        assertTrue(f.run.active)
        assertTrue(f.run.onRoute)
        assertEquals(routeId, f.navigator.currentRouteId())
        assertEquals(version, f.navigator.currentTactileRouteProgress().routeVersion)
        f.guidance.resumeGpsNavigation(f::startServices)
        assertEquals(listOf("cancel", "services:true:null"), f.events)
        assertTrue("Only accepted guidance consumes the resume intent", f.guidance.resumePending)
    }

    @Test fun noInstalledActiveRouteDoesNotAcquireResumeIntentButStillCancelsOutputs() {
        for (installed in listOf(false, true)) {
            val f = Fixture()
            if (installed) { f.install(); f.run.markInactive() } else f.run.markRequestActive()
            f.guidance.restrictGpsNavigation { f.events += "cancel" }
            assertFalse(f.guidance.resumePending)
            assertEquals("environment_gps_quality", f.run.directionPauseReason)
            assertEquals(listOf("cancel"), f.events)
        }
    }

    @Test fun environmentRecoveryDoesNotClearAnUnrelatedProviderPause() {
        val f = Fixture().apply { install() }
        f.guidance.restrictGpsNavigation {}
        f.run.pauseDirection("tmap_unavailable")
        f.guidance.resumeGpsNavigation(f::startServices)
        assertEquals("tmap_unavailable", f.run.directionPauseReason)
        assertEquals(listOf("services:true:tmap_unavailable"), f.events)
        assertTrue(f.guidance.resumePending)
    }

    @Test fun serviceFailureRetainsTheRecheckPauseAndDoesNotAnnounceAStartedRecheck() {
        val f = Fixture().apply { install(); requestRecheckDecision() }
        val failure = IllegalStateException("service unavailable")
        try {
            f.guidance.requestLocationRecheck(f.navigator.pendingDecisionToken()) { throw failure }
            fail("Expected service failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertFalse(f.run.onRoute)
        assertEquals("off_route_recheck", f.run.directionPauseReason)
        assertEquals(listOf("decision:null:true:null"), f.events)
    }

    @Test fun voiceAndClickUseTheSameIntentAndRejectedOldVoiceTokenStartsNoServices() {
        for (click in listOf(false, true)) {
            val f = Fixture().apply { install(); requestRecheckDecision() }
            val oldToken = requireNotNull(f.navigator.pendingDecisionToken())
            if (click) f.commands.recheckLocationFromVoice() else f.commands.recheckLocationFromVoice(oldToken)
            assertEquals(1, f.events.count { it.startsWith("services:") })
            assertEquals(1, f.events.count { it.startsWith("interaction:") })
            assertFalse(f.events.any { it.startsWith("voice:") })
            f.events.clear()
            f.commands.recheckLocationFromVoice(oldToken)
            assertEquals(listOf("voice:경로를 따라가는 중입니다."), f.events)
        }
    }

    @Test fun deniedNavigationCollectionNeverEntersTheRecheckIntent() {
        val f = Fixture().apply { install(); requestRecheckDecision(); navigationAllowed = false }
        val token = f.navigator.pendingDecisionToken()
        f.commands.recheckLocationFromVoice(token)
        assertEquals(listOf("unavailable"), f.events)
        assertEquals(token, f.navigator.pendingDecisionToken())
        assertTrue(f.run.onRoute)
        assertNull(f.run.directionPauseReason)
    }

    private class Fixture {
        var now = 1_000L
        var navigationAllowed = true
        val events = mutableListOf<String>()
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1), nowElapsedRealtimeMs = { now })
        val run = NavigationRunState(navigator)
        val guidance = RouteGuidanceController(run, unused(), unused(), unused(), unused(),
            port { name, args ->
                events += when (name) {
                    "decisionActions" -> "decision:${args[0]}:${run.onRoute}:${run.directionPauseReason}"
                    "navigationStatus" -> "status:${args[0]}"
                    "interaction" -> "interaction:${args[0]}"
                    else -> error("Unexpected guidance effect $name")
                }
                null
            }, unused(), InstalledRouteStartSpeechCoordinator(), unused(), { now }, { true }, { it() }, { null })
        val commands = VoiceCommandInteractionController(
            search = { error("Recheck must not read search") }, routeRun = { run }, walk = unused(),
            route = VoiceRoutePeerAdapter(
                acquisition = { error("Recheck must not acquire routes") },
                archive = { error("Recheck must not acquire archive") }, guidance = { guidance },
                compass = { error("Recheck must not read compass") },
                position = { error("Recheck must not read position") },
                entry = { error("Recheck must not create a new guidance entry") },
                admission = port { name, _ -> when (name) {
                    "navigationCollectionAllowsWork" -> navigationAllowed
                    "explainNavigationFeatureUnavailable" -> { events += "unavailable"; null }
                    else -> error("Unexpected route admission $name")
                } }, effects = port { name, _ ->
                    check(name == "startNavigationServicesIfNeeded")
                    startServices()
                    null
                },
            ), identity = unused(), ui = unused(), input = unused(),
            output = port { name, args ->
                check(name == "speakInteraction")
                events += "voice:${args[0]}"
                true
            }, scheduler = unused(), dialog = { error("Recheck must not read dialog") },
        )
        fun install() {
            val destination = RoutePoint(0.0, .003)
            navigator.setRoute(WalkingRoute("intent", WalkingRouteSummary(334, 334),
                listOf(RoutePoint(0.0, 0.0), destination), emptyList()))
            run.selectDestination(destination)
            run.markRequestActive()
            run.updateAcceptedRouteMatch(true)
        }
        fun requestRecheckDecision() {
            assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, navigator.onUntrustedLocation()?.pendingUserDecision)
        }
        fun startServices() { events += "services:${run.onRoute}:${run.directionPauseReason}" }
    }

    companion object {
        private inline fun <reified T> unused(): T = port { name, _ -> error("Unexpected ${T::class.java.simpleName}.$name") }
        private inline fun <reified T> port(crossinline answer: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                answer(method.name, args ?: emptyArray())
            } as T
    }
}
