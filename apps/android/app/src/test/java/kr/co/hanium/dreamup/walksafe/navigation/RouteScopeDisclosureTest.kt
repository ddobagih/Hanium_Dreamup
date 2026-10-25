package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S07-12 (FP-022-DETAIL-03 interim): stairs avoided; other accessibility information not confirmed. */
class RouteScopeDisclosureTest {
    @Test fun stairAvoidRouteStatesItsScopeWithoutASafetyClaim() {
        val disclosure = requireNotNull(routeScopeDisclosureKo("STAIR_AVOID"))
        assertEquals("계단을 피하는 경로입니다. 보도 상태나 공사 같은 다른 접근성 정보는 확인되지 않았습니다.", disclosure)
        listOf("안전", "적합", "보장", "확인했습니다").forEach { claim -> assertFalse(claim, disclosure.contains(claim)) }
        assertNull(routeScopeDisclosureKo("RECOMMEND"))
    }

    @Test fun installedRouteVoiceNamesDestinationAndDistanceWithoutRepeatedScopeExplanation() {
        val message = routeStartAnnouncementKo("테스트 편의점", 350, true, "STAIR_AVOID")
        assertTrue(message.startsWith("테스트 편의점까지 안내를 시작합니다."))
        assertTrue(message.contains("350미터"))
        assertEquals("테스트 편의점까지 안내를 시작합니다. 경로 거리는 약 350미터입니다.", message)
        assertFalse(message.contains("계단"))
        assertFalse(message.contains("안전한"))
    }

    @Test fun lostLocationNeverClaimsThatDirectionsStarted() {
        val message = routeStartAnnouncementKo("테스트 편의점", 350, false, "STAIR_AVOID")
        assertTrue(message.contains("경로를 찾았습니다"))
        assertTrue(message.contains("현재 위치를 확인하면"))
        assertFalse(message.contains("안내를 시작합니다"))
        assertFalse(message.contains("계단"))
    }

    @Test fun unknownNameUsesDestination() {
        assertTrue(routeStartAnnouncementKo("  ", 0, true, "STAIR_AVOID").startsWith("목적지까지"))
    }

    @Test fun productionAnnouncesOnlyInstalledNewRoutesAndDoesNotBlockDirectionsOnSpeechFailure() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val request = MainActivityRefactorSource.function(source, "private fun requestRoute(")
        val ready = request.substringAfter("installRequestedRoute(route, destination, origin,")
            .substringBefore("} catch (_: CancellationException) {")
        assertTrue(ready.contains("""if (reason == "off_route")"""))
        assertTrue(ready.contains("routeStartAnnouncementKo("))
        assertTrue(ready.contains("requestedDestinationName, route.summary.distanceM, true, route.priority"))
        assertTrue(ready.contains("routeRequestGeneration == scopeRouteGeneration"))
        assertTrue(ready.contains("currentDestination == destination"))
        assertTrue(ready.contains("isCurrentRouteRequest() && facts.runtimeActive && isRouteActive"))
        assertTrue(ready.contains("installedStartSpeech.install("))
        assertTrue(ready.contains("current = ::startAnnouncementCurrent"))
        assertTrue(ready.contains("effects.updateGuidance(current, facts.latestRawLocation ?: current)"))
        val arbitration = MainActivityRefactorSource.function(source, "internal fun holdRouteInstructionsForStartAnnouncement(")
        assertTrue(arbitration.contains("installedRouteStartSpeechCoordinator.hold("))
        val coordinator = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/InstalledRouteStartSpeechCoordinator.kt").readText()
        assertTrue(coordinator.contains("owner.isCurrent() && locationReady()"))
        val guidance = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(guidance, "retryAdmittedNavigationSpeech", 1)
            .contains("initialJoinPending = routeNavigator.isWaitingForInitialRouteJoin()"))
        assertTrue(MainActivityRefactorSource.member(guidance, "installedRouteStartLocationReady")
            .contains("currentAcceptedRouteMatchFor(location.elapsedRealtimeMs)"))
        assertTrue(coordinator.contains("state === pendingState"))
        assertTrue(coordinator.contains("if (ready()) resumeInstructions()"))
        val speechAdapter = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/GuidanceSpeechPeerAdapter.kt").readText()
        val submit = MainActivityRefactorSource.member(speechAdapter, "submitInstalledStart")
        assertTrue(submit.contains("onCompleted = { clearWaitingFailure(current); terminal() }"))
        for (callback in listOf("onFailed", "onCancelled", "onRetired")) {
            assertTrue(submit.contains("$callback = terminal"))
        }
        assertTrue(coordinator.contains("if (!accepted) terminal()"))
        val assembly = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(assembly.contains("GuidanceSpeechPeerAdapter("))
        assertTrue(assembly.contains("clearWaitingFailure = { clearWaitingRouteSpeechFailureIfCurrent(it); Unit }"))
        assertTrue(assembly.contains("installedStartSpeech = { installedRouteStartSpeechCoordinator }"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeAcquisitionController")
            .contains("installedStartSpeech = resources.installedStartSpeech()"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeGuidanceController")
            .contains("installedRouteStartSpeechCoordinator = resources.installedStartSpeech()"))
    }
}
