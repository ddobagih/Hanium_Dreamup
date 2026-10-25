package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Current acquisition ownership, storage acknowledgement and navigation-cancellation wiring. */
class MainActivityRouteReplacementStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val acquisition = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt").readText()
    private fun function(name: String, occurrence: Int = 0): String =
        MainActivityRefactorSource.member(acquisition, name, occurrence)

    @Test fun selectingAReplacementRetainsTheInstalledRouteAndItsDestination() {
        MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        val selection = function("onDestinationSelected")
        assertTrue(selection.contains("val preserveExistingRoute = isRouteActive && routeNavigator.hasRoute()"))
        assertTrue(selection.contains("preserveExistingRoute = preserveExistingRoute"))
        assertFalse(selection.contains("resetRouteState()"))
        val pending = ReportStaticSourceInspector.blockAfter(selection, "if (!preserveExistingRoute)")
        assertTrue(pending.contains("run.selectPendingDestination(result.point)"))
        assertTrue(pending.contains("effects.showDestinationCoordinates(result.point)"))
        val run = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/NavigationRunState.kt")
        val selected = MainActivityRefactorSource.member(run, "selectPendingDestination")
        assertTrue(selected.contains("this.destination = destination"))
        assertTrue(selected.contains("active = false"))
        assertTrue(selected.contains("onRoute = false"))
        assertFalse(selected.contains("navigator."))
        val wire = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val coordinates = ReportStaticSourceInspector.blockAfter(wire, "override fun showDestinationCoordinates(destination: RoutePoint)")
        assertTrue(coordinates.contains("destinationLatInput.setText"))
        assertTrue(coordinates.contains("destinationLngInput.setText"))
    }

    @Test fun routeAndDestinationCommitOnlyAfterTheCurrentValidatedResponse() {
        val request = function("requestRoute", 1)
        val owner = ReportStaticSourceInspector.blockAfter(request, "fun isCurrentRouteOwner()")
        assertTrue(owner.contains("isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)"))
        assertTrue(owner.contains("currentSession.generation == expectedGatewaySessionGeneration"))
        assertTrue(owner.contains("currentSession.session === gatewaySession"))
        assertTrue(owner.contains("facts.actorId == gatewaySession.actorId"))
        assertTrue(request.contains("if (!preserveExistingRoute) currentDestination = destination"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(request, "routeCall.execute()",
            "archive.submitRouteSnapshotResponseStorage(", ".thenAccept { storedRouteSnapshot ->",
            "if (!storageAdmissionCurrent()", "installRequestedRoute(route, destination, origin,",
            "currentDestination = destination"))
        assertTrue(request.contains("if (storedRouteSnapshot == null && !facts.developmentBypass)"))
        assertTrue(request.contains("return@completeSnapshot"))
        assertTrue(function("installRequestedRoute", 1).contains("routeNavigator.setRoute("))
        assertFalse(request.contains("purgeEncryptedRouteSnapshot()"))
    }

    @Test fun requestCancellationAndNavigationEndHaveDifferentRouteOwnership() {
        val cancel = function("cancelActiveRouteRequest")
        assertTrue(cancel.contains("preserveExistingRoute: Boolean = true"))
        assertTrue(cancel.contains("preserveExistingRoute = preserveExistingRoute"))
        val voice = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt").readText()
        val stop = MainActivityRefactorSource.member(voice, "stopNavigationFromVoice", 1)
        assertTrue(stop.contains("route.cancelActiveRouteRequest(preserveExistingRoute = false)"))
        val request = function("requestRoute", 1)
        val retained = ReportStaticSourceInspector.blockAfter(request, "fun retainExistingRouteAfterFailure(message: String)")
        assertTrue(retained.contains("if (reason == \"off_route\")"))
        assertTrue(retained.contains("retainRouteAfterRerouteFailure(message)"))
        assertTrue(request.contains("presentation.safetyStop(\"route_consecutive_failures\")"))
        assertTrue(request.contains("if (navigationFailure.isUserSessionAuthentication)"))
        assertTrue(MainActivityRefactorSource.factory(source, "createNavigationSessions")
            .contains("expectedAuthenticationFailure = authenticationFailure"))
        assertTrue(request.contains("navigationFailure.isUserSessionAuthentication)"))
    }
}
